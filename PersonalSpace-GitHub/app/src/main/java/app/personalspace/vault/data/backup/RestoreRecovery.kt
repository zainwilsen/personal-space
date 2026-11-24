package app.personalspace.vault.data.backup

import android.content.Context
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * P3 — restore crash recovery at startup.
 *
 * Runs **before vault unlock** and **before any database write**:
 *
 *  - It never asks for the master password and never touches session keys. Everything it needs is
 *    on disk (journal + rollback copy), which `p2_T5_journal_key_free` already proved is readable
 *    with the vault locked.
 *  - It never opens the Room database. Files are copied with plain `java.io`; the database is closed
 *    defensively first so no open connection can hold a stale handle across the swap.
 *
 * Safety rules (all enforced here, all tested):
 *
 *  1. **Never delete a rollback copy until recovery is verified.** A copy is removed only after the
 *     restored files have been hashed and matched against the manifest that was written when the
 *     copy was taken ([CopyManifest]).
 *  2. **Missing or corrupt recovery data blocks vault access** and preserves every remaining file.
 *     Nothing is deleted, moved aside, or "repaired" by guesswork.
 *  3. **Rollback copies are append-only for this class.** No cleanup path exists for
 *     `PREPARED..ROLLBACK_FAILED`; see [RestoreGuard.mayDeleteRollbackCopy].
 */
internal object RestoreRecovery {

    /** Outcome of one startup check. Never carries keys or vault content. */
    sealed interface Outcome {
        /** Nothing to do. */
        data object Clean : Outcome

        /** A restore had already finished; only leftover files were cleaned. */
        data class CommittedCleanup(val removedRollbackDir: File?) : Outcome

        /** An unfinished restore was rolled back from the rollback copy and verified. */
        data class Recovered(val phase: RestorePhase, val verifiedFiles: Int) : Outcome

        /** A retry was attempted and completed with hash verification. */
        data class Retried(val verifiedFiles: Int) : Outcome

        /**
         * Recovery data is missing or corrupt. Vault access must be blocked; every remaining file is
         * preserved and reported so a human can act.
         */
        data class Blocked(
            val reason: String,
            val preservedFiles: List<String>,
            /** Rollback copy that a retry could use; null when the copy itself is gone. */
            val retryableDirName: String? = null
        ) : Outcome
    }

    /** A manifest of what a rollback copy contained when it was taken. */
    private data class CopyManifest(val entries: Map<String, String>)

    private const val MANIFEST_NAME = "copy_manifest.txt"

    // ---------------------------------------------------------------- public entry point

    /**
     * Runs the startup check. Safe to call on every launch; returns [Outcome.Clean] when there is
     * nothing to do.
     *
     * @param filesDir the app `files/` directory
     * @param dbDir the directory holding `personal_space_encrypted.db`
     * @param attachmentsDir live encrypted attachments directory
     * @param imagesDir live inline-image directory
     * @param closeDatabase defensive close, called before any file move
     */
    fun onAppStart(
        filesDir: File,
        dbDir: File,
        attachmentsDir: File,
        imagesDir: File,
        closeDatabase: () -> Unit
    ): Outcome {
        val journal = RestoreJournal.readFrom(filesDir) ?: return handleWithoutJournal(filesDir)

        return when (journal.phase) {
            RestorePhase.COMMITTED, RestorePhase.ROLLED_BACK -> {
                val dir = File(filesDir, journal.rollbackDirName)
                closeDatabase()
                if (RestoreGuard.mayDeleteRollbackCopy(journal.phase)) dir.deleteRecursively()
                Outcome.CommittedCleanup(if (dir.exists()) null else dir)
            }
            else -> recoverUnfinished(filesDir, dbDir, attachmentsDir, imagesDir, journal, closeDatabase)
        }
    }

    /**
     * Journal present but already terminal is handled above. A journal file that exists but cannot be
     * parsed is a hard block: we must not guess which copy belongs to which restore.
     */
    private fun handleWithoutJournal(filesDir: File): Outcome {
        val strayCopies = RestoreGuard.unresolvedRollbackDirs(filesDir)
        if (strayCopies.isEmpty()) return Outcome.Clean
        return Outcome.Blocked(
            reason = "A pre-restore copy exists but its journal is missing or unreadable. " +
                "No file was deleted; the copy is preserved for manual recovery.",
            preservedFiles = strayCopies.map { it.name }
        )
    }

    // ---------------------------------------------------------------- recovery

    private fun recoverUnfinished(
        filesDir: File,
        dbDir: File,
        attachmentsDir: File,
        imagesDir: File,
        journal: RestoreJournalState,
        closeDatabase: () -> Unit
    ): Outcome {
        val rollbackDir = File(filesDir, journal.rollbackDirName)
        val preserved = mutableListOf<String>()

        if (!rollbackDir.isDirectory) {
            return Outcome.Blocked(
                reason = "Restore was interrupted during phase ${journal.phase.name} and its " +
                    "rollback copy is missing. No file was deleted; the vault is unchanged.",
                preservedFiles = listOf(journal.rollbackDirName),
                retryableDirName = null
            )
        }
        preserved += rollbackDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(filesDir).path }.take(200)

        val manifest = readManifest(rollbackDir)
        if (manifest == null) {
            return Outcome.Blocked(
                reason = "The rollback copy has no integrity manifest, so it cannot be verified. " +
                    "Nothing was restored and nothing was deleted.",
                preservedFiles = preserved,
                retryableDirName = null   // without a manifest no retry can ever verify
            )
        }

        // 1. verify the COPY is intact before trusting it (corrupt copy => block, never guess)
        val copyProblem = verify(rollbackDir, manifest)
        if (copyProblem != null) {
            return Outcome.Blocked(
                reason = "The rollback copy failed its own integrity check ($copyProblem). " +
                    "Nothing was restored and nothing was deleted.",
                preservedFiles = preserved,
                // the copy is on disk but corrupt; a retry re-checks it and will block again rather
                // than ever deleting it
                retryableDirName = rollbackDir.name
            )
        }

        // 2. nothing may hold a handle on the database while files are swapped
        closeDatabase()

        // 3. put the pre-restore files back
        val restored = runCatching { restoreFromCopy(rollbackDir, dbDir, attachmentsDir, imagesDir) }
        if (restored.isFailure) {
            return Outcome.Blocked(
                reason = "Restoring the rollback copy failed: " +
                    (restored.exceptionOrNull()?.message ?: "unknown error") +
                    ". Every file is preserved.",
                preservedFiles = preserved,
                retryableDirName = rollbackDir.name
            )
        }

        // 4. VERIFY the restored state against the manifest — this is what gates deletion
        val restoreProblem = verify(dbDir, attachmentsDir, imagesDir, manifest)
        if (restoreProblem != null) {
            return Outcome.Blocked(
                reason = "The restored files did not match the rollback copy ($restoreProblem). " +
                    "The copy is preserved and vault access is blocked.",
                preservedFiles = preserved,
                retryableDirName = rollbackDir.name
            )
        }

        // 5. only now is the copy redundant
        journal.let {
            val terminal = it.copy(phase = RestorePhase.ROLLED_BACK)
            RestoreJournal.writeTo(filesDir, terminal)
        }
        val verifiedFiles = manifest.entries.size
        rollbackDir.deleteRecursively()
        RestoreJournal.deleteFrom(filesDir)

        return Outcome.Recovered(journal.phase, verifiedFiles)
    }

    // ---------------------------------------------------------------- copy handling

    /**
     * Writes the integrity manifest for a rollback copy. P2's snapshot path calls this through
     * [app.personalspace.vault.data.backup.RestoreSnapshot], so every copy is self-describing.
     */
    fun writeManifest(rollbackDir: File, dbDir: File, attachmentsDir: File, imagesDir: File) {
        val entries = LinkedHashMap<String, String>()
        collectInto(entries, rollbackDir, File(rollbackDir, "database"), "database")
        collectHashes(entries, File(rollbackDir, RestoreJournal.ATTACHMENTS_BACKUP), "attachments")
        collectHashes(entries, File(rollbackDir, RestoreJournal.IMAGES_BACKUP), "note_images")
        val text = buildString {
            entries.forEach { (path, hash) -> append(path).append('\t').append(hash).append('\n') }
        }
        File(rollbackDir, MANIFEST_NAME).writeText(text, StandardCharsets.UTF_8)
    }

    private fun collectInto(entries: MutableMap<String, String>, rollbackDir: File, dir: File, prefix: String) {
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            entries["$prefix/${file.name}"] = sha256(file)
        }
    }

    private fun collectHashes(entries: MutableMap<String, String>, dir: File, prefix: String) {
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            entries["$prefix/${file.name}"] = sha256(file)
        }
    }

    private fun readManifest(rollbackDir: File): CopyManifest? {
        val file = File(rollbackDir, MANIFEST_NAME)
        if (!file.isFile) return null
        val entries = LinkedHashMap<String, String>()
        file.readLines(StandardCharsets.UTF_8).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size != 2) return null
            entries[parts[0]] = parts[1]
        }
        if (entries.isEmpty()) return null
        return CopyManifest(entries)
    }

    /** Verifies every manifest entry against files rooted at [root]. Returns a reason or null. */
    private fun verify(root: File, manifest: CopyManifest): String? {
        // paths inside the copy are relative to the copy root, which mirrors the live layout
        manifest.entries.forEach { (path, expected) ->
            val file = File(root, path)
            if (!file.isFile) return "missing $path"
            if (sha256(file) != expected) return "hash mismatch on $path"
        }
        return null
    }

    private fun verify(dbDir: File, attachmentsDir: File, imagesDir: File, manifest: CopyManifest): String? {
        manifest.entries.forEach { (path, expected) ->
            val file = when {
                path.startsWith("database/") -> File(dbDir, path.removePrefix("database/"))
                path.startsWith("attachments/") -> File(attachmentsDir, path.removePrefix("attachments/"))
                path.startsWith("note_images/") -> File(imagesDir, path.removePrefix("note_images/"))
                else -> null
            }
            if (file == null || !file.isFile) return "missing $path"
            if (sha256(file) != expected) return "hash mismatch on $path"
        }
        return null
    }

    private fun restoreFromCopy(rollbackDir: File, dbDir: File, attachmentsDir: File, imagesDir: File) {
        val dbCopy = File(rollbackDir, "database")
        if (!dbCopy.isDirectory) throw IOException("rollback copy has no database directory")
        dbDir.mkdirs()
        dbDir.listFiles()?.filter { it.isFile && it.name.startsWith(DB_BASE_NAME) }?.forEach { it.delete() }
        dbCopy.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(dbDir, it.name), overwrite = true) }

        replaceDir(File(rollbackDir, RestoreJournal.ATTACHMENTS_BACKUP), attachmentsDir)
        replaceDir(File(rollbackDir, RestoreJournal.IMAGES_BACKUP), imagesDir)
    }

    private fun replaceDir(source: File, target: File) {
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        if (!source.isDirectory) return
        source.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal const val DB_BASE_NAME = "personal_space_encrypted.db"
}

/**
 * P3 — the Hilt-facing entry point used at application start.
 *
 * Kept deliberately tiny: it resolves the real directories, refuses to open the database, and
 * reports a state the UI can render. It performs **no** write when the outcome is [Blocked].
 */
@Singleton
class RestoreRecoveryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dbHolder: VaultDatabaseHolder
) {

    @Volatile
    internal var lastOutcome: RestoreRecovery.Outcome? = null
        private set

    /**
     * Must be called before the vault is auto-unlocked and before any database write.
     * Returns the outcome; never throws for the "blocked" case.
     */
    internal fun runStartupRecovery(): RestoreRecovery.Outcome {
        val outcome = runCatching {
            RestoreRecovery.onAppStart(
                filesDir = context.filesDir,
                dbDir = dbHolder.databaseFile().parentFile ?: File(context.filesDir, "no_db_dir"),
                attachmentsDir = File(context.filesDir, "encrypted_attachments"),
                imagesDir = File(context.filesDir, "note_images"),
                closeDatabase = { dbHolder.closeDatabase() }
            )
        }.getOrElse { error ->
            RestoreRecovery.Outcome.Blocked(
                reason = "Startup recovery could not run: ${error.message ?: error.javaClass.simpleName}",
                preservedFiles = emptyList()
            )
        }
        lastOutcome = outcome
        return outcome
    }

    /** True when the vault must not be unlocked until a human resolves the recovery state. */
    fun blocksVaultAccess(): Boolean = lastOutcome is RestoreRecovery.Outcome.Blocked

    /**
     * Human-readable status for the blocking screen; null when access is allowed.
     * Prefers the live gate state so the UI and the enforcement can never disagree.
     */
    fun gateState(): app.personalspace.vault.core.security.RecoveryGate.State =
        app.personalspace.vault.core.security.RecoveryGate.state()

    /**
     * P3.1 "Retry Recovery".
     *
     * Re-runs the same verified recovery path. The gate is released **only** when recovery returns
     * [RestoreRecovery.Outcome.Recovered] / [RestoreRecovery.Outcome.CommittedCleanup] /
     * [RestoreRecovery.Outcome.Clean] — i.e. only after the hash verification inside
     * [RestoreRecovery] has succeeded. A failed retry keeps the gate blocked and deletes nothing.
     */
    internal fun retryRecovery(): RestoreRecovery.Outcome {
        val outcome = runStartupRecovery()
        when (outcome) {
            is RestoreRecovery.Outcome.Blocked -> {
                // stays blocked; refresh the reason/preserved list shown to the user
                app.personalspace.vault.core.security.RecoveryGate.markBlocked(
                    reason = outcome.reason,
                    preservedFiles = outcome.preservedFiles,
                    retryableDirName = outcome.retryableDirName
                )
            }
            else -> app.personalspace.vault.core.security.RecoveryGate.markRecovered()
        }
        return outcome
    }

    /**
     * P3.1 "Export / preserve recovery data".
     *
     * Copies the preserved rollback copy into an app-owned export directory and returns the file
     * list. **Never moves or deletes the original** — the copy stays where recovery expects it, so
     * a later retry still works.
     *
     * @return the exported files, or an empty list when there is nothing to export.
     */
    fun exportRecoveryData(destinationDir: File? = null): List<File> {
        val filesDir = context.filesDir
        val copies = RestoreGuard.unresolvedRollbackDirs(filesDir)
        if (copies.isEmpty()) return emptyList()
        val destination = destinationDir
            ?: File(context.getExternalFilesDir(null) ?: filesDir, "recovery_export")
        destination.mkdirs()
        val written = mutableListOf<File>()
        copies.forEach { copyDir ->
            val target = File(destination, copyDir.name).apply { mkdirs() }
            copyDir.walkTopDown().filter { it.isFile }.forEach { source ->
                val out = File(target, source.relativeTo(copyDir).path)
                out.parentFile?.mkdirs()
                source.copyTo(out, overwrite = true)
                written += out
            }
        }
        return written
    }

    /** Public result of a retry, so the UI layer needs no internal types. */
    data class RetryResult(val succeeded: Boolean, val message: String)

    /** P3.1 "Retry Recovery" for the UI layer. Gate is released only on verified success. */
    fun retryRecoveryForUi(): RetryResult {
        val outcome = retryRecovery()
        return when (outcome) {
            is RestoreRecovery.Outcome.Recovered ->
                RetryResult(true, "Recovery verified: restored from phase ${outcome.phase.name}.")
            is RestoreRecovery.Outcome.Retried ->
                RetryResult(true, "Recovery verified.")
            is RestoreRecovery.Outcome.CommittedCleanup ->
                RetryResult(true, "Previous restore was already complete; leftover files cleaned.")
            is RestoreRecovery.Outcome.Clean ->
                RetryResult(true, "Nothing to recover.")
            is RestoreRecovery.Outcome.Blocked ->
                RetryResult(false, outcome.reason)
        }
    }

    /** Human-readable status for the blocking screen; null when access is allowed. */
    fun blockingReason(): String? = (lastOutcome as? RestoreRecovery.Outcome.Blocked)?.reason

    /** Files preserved on disk while access is blocked. */
    fun preservedFiles(): List<String> =
        (lastOutcome as? RestoreRecovery.Outcome.Blocked)?.preservedFiles ?: emptyList()
}
