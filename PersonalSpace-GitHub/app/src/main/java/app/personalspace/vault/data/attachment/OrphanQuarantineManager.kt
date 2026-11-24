package app.personalspace.vault.data.attachment

import app.personalspace.vault.data.backup.InlineImageStore
import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * P6 item 8 — **safe orphan detection and quarantine**.
 *
 * Contract, in order of importance:
 *
 *  1. **Detection is read-only.** [scan] only reads files and the database. Nothing is moved or
 *     deleted. It can therefore be run from anywhere, including a settings screen or a diagnostics
 *     export, without any data risk.
 *  2. **Nothing is deleted, ever, by this class.** [quarantine] *moves* orphans into
 *     `files/orphan_quarantine/` and writes a manifest that preserves each file's hash, size and
 *     timestamps, so a file can always be restored by hand.
 *  3. **A file must be old enough.** A file modified within [minAgeMillis] is never even reported,
 *     because a write that is still in progress (import, restore staging, sync download) looks exactly
 *     like an orphan for a moment. The default is a full day.
 *  4. **Quarantine is never emptied automatically.** [emptyQuarantine] only runs when a caller asks
 *     for it explicitly, i.e. a user pressing a button.
 *  5. **Never during backup, restore or startup recovery.** Those paths run inside [MediaReferenceIndex]
 *     locks with files staged and swapped; moving files then would corrupt a restore. Callers must go
 *     through [assertSafeToRun], and the storage snapshot is taken under the media lock.
 *
 * References are counted across **everything that can own a file**: active records, recycle-bin
 * records, drafts, and attachment rows in any deletion state.
 */
class OrphanQuarantineManager(
    private val db: PersonalSpaceDatabase,
    private val filesDir: File,
    private val minAgeMillis: Long = DEFAULT_MIN_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    // ------------------------------------------------------------------ paths

    private val attachmentsDir get() = File(filesDir, "encrypted_attachments")
    private val imagesDir get() = File(filesDir, "note_images")
    val quarantineDir get() = File(filesDir, QUARANTINE_DIR_NAME)
    val manifestFile get() = File(quarantineDir, MANIFEST_NAME)

    // ------------------------------------------------------------------ detection (read-only)

    @Serializable
    data class OrphanCandidate(
        val fileName: String,
        val kind: String,             // ATTACHMENT | IMAGE
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
        val ageMillis: Long,
        val sha256Hex: String,
        val reason: String            // why it was judged unreferenced
    )

    @Serializable
    data class ScanResult(
        val scannedAtMillis: Long,
        val minAgeMillis: Long,
        val attachmentFilesSeen: Int,
        val imageFilesSeen: Int,
        val referencedAttachmentNames: Int,
        val referencedImageNames: Int,
        val candidates: List<OrphanCandidate>,
        val recentFilesSkipped: List<String>,
        val readOnly: Boolean = true
    )

    /**
     * Looks for files that no entity references. **Never modifies anything** — [ScanResult.readOnly]
     * is carried in the payload so an exported report cannot be mistaken for an action.
     */
    suspend fun scan(): ScanResult = MediaReferenceIndex.withMediaLock {
        val referencedAttachments = MediaReferenceIndex.referencedAttachmentFileNames(db)
        val referencedImages = MediaReferenceIndex.referencedImageIdentifiers(db)
        val cutoff = now() - minAgeMillis
        val recent = ArrayList<String>()
        val candidates = ArrayList<OrphanCandidate>()

        fun consider(file: File, kind: String, referenced: Set<String>) {
            val name = if (kind == KIND_IMAGE) file.name.substringBefore('.') else file.name
            if (name in referenced) return
            val modified = file.lastModified()
            if (modified > cutoff) {
                // too new to judge: an in-flight import/restore/sync write looks orphaned for a moment
                recent.add("$kind:$name")
                return
            }
            candidates.add(
                OrphanCandidate(
                    fileName = file.name,
                    kind = kind,
                    sizeBytes = file.length(),
                    lastModifiedMillis = modified,
                    ageMillis = now() - modified,
                    sha256Hex = sha256Of(file),
                    reason = if (kind == KIND_IMAGE)
                        "no record or draft body references this image"
                    else
                        "no attachment row (active or deleted) references this file"
                )
            )
        }

        val attFiles = attachmentsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".psenc") } ?: emptyList()
        val imgFiles = imagesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".psenc") } ?: emptyList()
        attFiles.forEach { consider(it, KIND_ATTACHMENT, referencedAttachments) }
        imgFiles.forEach { consider(it, KIND_IMAGE, referencedImages) }

        ScanResult(
            scannedAtMillis = now(),
            minAgeMillis = minAgeMillis,
            attachmentFilesSeen = attFiles.size,
            imageFilesSeen = imgFiles.size,
            referencedAttachmentNames = referencedAttachments.size,
            referencedImageNames = referencedImages.size,
            candidates = candidates.sortedBy { it.fileName },
            recentFilesSkipped = recent.sorted()
        )
    }

    // ------------------------------------------------------------------ quarantine (move only)

    @Serializable
    data class QuarantinedEntry(
        val originalRelativePath: String,
        val quarantineFileName: String,
        val kind: String,
        val sizeBytes: Long,
        val sha256Hex: String,
        val originalLastModifiedMillis: Long,
        val quarantinedAtMillis: Long,
        val reason: String
    )

    @Serializable
    data class QuarantineManifest(
        val createdAtMillis: Long,
        val note: String = "Files here were moved out of the vault because nothing referenced them. " +
            "They were NOT deleted and can be restored by putting them back at originalRelativePath. " +
            "This folder is only ever emptied by an explicit user action.",
        val entries: MutableList<QuarantinedEntry> = mutableListOf()
    )

    /**
     * Moves the candidates from a [scan] into the quarantine folder and records them in the manifest.
     * A file is moved **only** if it is still unreferenced at the moment of the move (the media lock
     * is held throughout), so a reference that appeared in between wins.
     *
     * @return the manifest after the move.
     */
    suspend fun quarantine(candidates: List<OrphanCandidate>): QuarantineManifest =
        MediaReferenceIndex.withMediaLock {
            quarantineDir.mkdirs()
            val manifest = loadManifest() ?: QuarantineManifest(createdAtMillis = now())
            // re-check under the lock: the scan may be older than the current state
            val referencedAttachments = MediaReferenceIndex.referencedAttachmentFileNames(db)
            val referencedImages = MediaReferenceIndex.referencedImageIdentifiers(db)
            for (candidate in candidates) {
                val source = when (candidate.kind) {
                    KIND_ATTACHMENT -> File(attachmentsDir, candidate.fileName)
                    else -> File(imagesDir, candidate.fileName)
                }
                if (!source.exists()) continue
                val name = if (candidate.kind == KIND_IMAGE) source.name.substringBefore('.') else source.name
                val stillReferenced = if (candidate.kind == KIND_IMAGE) name in referencedImages
                else name in referencedAttachments
                if (stillReferenced) continue
                val target = File(quarantineDir, "${candidate.kind.lowercase()}_${source.name}")
                if (target.exists()) target.delete()   // stale entry from a previous quarantine
                val before = sha256Of(source)
                if (before != candidate.sha256Hex) {
                    // the file changed since the scan — do not move something we did not inspect
                    continue
                }
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    if (!source.delete()) { target.delete(); continue }
                }
                target.setLastModified(candidate.lastModifiedMillis)
                manifest.entries.removeAll { it.quarantineFileName == target.name }
                manifest.entries.add(
                    QuarantinedEntry(
                        originalRelativePath = "${source.parentFile!!.name}/${source.name}",
                        quarantineFileName = target.name,
                        kind = candidate.kind,
                        sizeBytes = candidate.sizeBytes,
                        sha256Hex = before,
                        originalLastModifiedMillis = candidate.lastModifiedMillis,
                        quarantinedAtMillis = now(),
                        reason = candidate.reason
                    )
                )
            }
            manifestFile.writeText(json.encodeToString(manifest))
            manifest
        }

    /** What is currently held in quarantine (read-only). */
    fun inspect(): QuarantineManifest = loadManifest() ?: QuarantineManifest(createdAtMillis = now())

    /**
     * Copies the whole quarantine folder to [targetDirectory] so the user can take it away.
     * Read-only with respect to the vault.
     */
    fun exportTo(targetDirectory: File): File {
        val destination = File(targetDirectory, "orphan_quarantine_export")
        destination.mkdirs()
        quarantineDir.listFiles()?.forEach { file ->
            if (file.isFile) file.copyTo(File(destination, file.name), overwrite = true)
        }
        return destination
    }

    /**
     * Puts a quarantined file back where it came from. Only meaningful before [emptyQuarantine].
     * @return true when the file is back in the vault with the expected hash.
     */
    fun restoreEntry(quarantineFileName: String): Boolean {
        val manifest = loadManifest() ?: return false
        val entry = manifest.entries.firstOrNull { it.quarantineFileName == quarantineFileName } ?: return false
        val source = File(quarantineDir, entry.quarantineFileName)
        if (!source.exists()) return false
        if (sha256Of(source) != entry.sha256Hex) return false
        val target = File(filesDir, entry.originalRelativePath)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            if (!source.delete()) return false
        }
        target.setLastModified(entry.originalLastModifiedMillis)
        val remaining = loadManifest() ?: QuarantineManifest(createdAtMillis = now())
        remaining.entries.removeAll { it.quarantineFileName == quarantineFileName }
        manifestFile.writeText(json.encodeToString(remaining))
        return true
    }

    /**
     * Irreversibly deletes everything in quarantine. **Only** ever called from an explicit user
     * action — there is deliberately no timer, no background worker and no call site in backup,
     * restore or startup recovery.
     *
     * @return number of files removed.
     */
    fun emptyQuarantine(): Int {
        var removed = 0
        quarantineDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                if (file.delete()) removed++
            }
        }
        quarantineDir.mkdirs()
        return removed
    }

    // ------------------------------------------------------------------ guards

    /** Throws when called from a context where moving files would be unsafe. */
    fun assertSafeToRun(context: String) {
        if (MediaReferenceIndex.isRestoreInFlight()) {
            throw IllegalStateException(
                "Orphan quarantine cannot run during a restore ($context). Nothing was changed."
            )
        }
    }

    private fun loadManifest(): QuarantineManifest? = runCatching {
        if (!manifestFile.exists()) null
        else json.decodeFromString<QuarantineManifest>(manifestFile.readText())
    }.getOrNull()

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val QUARANTINE_DIR_NAME = "orphan_quarantine"
        const val MANIFEST_NAME = "quarantine_manifest.json"
        const val KIND_ATTACHMENT = "ATTACHMENT"
        const val KIND_IMAGE = "IMAGE"

        /** One day. A file younger than this is never reported as an orphan. */
        const val DEFAULT_MIN_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
