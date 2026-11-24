package app.personalspace.vault.data.backup

import app.personalspace.vault.core.common.IdGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * P7 — **retained local backup generations**.
 *
 * The canonical `PersonalSpace_Backup.psbackup` keeps its exact path and name (rule: backward
 * compatibility). Generations are extra copies beside it:
 *
 * ```
 * files/local_backups/
 *     PersonalSpace_Backup.psbackup          <- canonical, untouched semantics
 *     generations/
 *         gen-<timestamp>-<id>.psbackup      <- retained generations (at least 3)
 *         generations.json                   <- index: hash, size, verify state
 *         rejected/                          <- corrupt candidates, excluded from retention
 * ```
 *
 * Rules implemented here, in the order they are enforced:
 *
 *  1. **Verify before promote.** [promote] only accepts a file whose sha256 matches the hash computed
 *     right after it was written and whose package verifies (the caller passes [BackupVerifier]).
 *     A failing candidate is moved to `rejected/` and never counts as a generation.
 *  2. **Never delete the last known-good backup.** [rotate] refuses to remove the final retained
 *     generation; it also refuses while any generation is unverified.
 *  3. **Corrupt generations are quarantined and excluded** from the retained count, and rotation is
 *     blocked until the user acknowledges them, so a corrupt file can never silently take the place of
 *     a good one.
 *  4. **Rotation is local-only.** Nothing here reads or writes Google Drive retention settings; the
 *     two policies are independent by construction (this class never touches the Drive API).
 *  5. **Every retained generation restores independently** — [verifyAll] re-verifies each generation
 *     from its own bytes using the same verifier a restore uses.
 */
class BackupGenerationManager(
    val generationsDir: File,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    val indexFile get() = File(generationsDir, "generations.json")
    val rejectedDir get() = File(generationsDir, "rejected")

    /** Who verifies a candidate/generation. Injected so tests can force failures. */
    fun interface BackupVerifier {
        /**
         * @return null when the package verifies, or a human-readable reason when it does not.
         */
        fun verify(file: File): String?
    }

    @Serializable
    data class Generation(
        val id: String,
        val fileName: String,
        val createdAtMillis: Long,
        val sizeBytes: Long,
        val sha256Hex: String,
        val verifiedAtMillis: Long,
        val verified: Boolean,
        val kind: String,
        val note: String = ""
    )

    @Serializable
    data class GenerationIndex(
        val latestGenerationId: String? = null,
        val updatedAtMillis: Long = 0,
        val generations: MutableList<Generation> = mutableListOf(),
        val rejected: MutableList<Generation> = mutableListOf()
    )

    @Serializable
    data class PromotionResult(
        val promoted: Boolean,
        val generation: Generation?,
        val failureReason: String?,
        val index: GenerationIndex
    )

    @Serializable
    data class RotationResult(
        val retainedIds: List<String>,
        val removedIds: List<String>,
        val blockedReason: String?
    )

    // ------------------------------------------------------------------ index

    fun readIndex(): GenerationIndex = runCatching {
        if (indexFile.exists()) json.decodeFromString<GenerationIndex>(indexFile.readText())
        else GenerationIndex()
    }.getOrElse { GenerationIndex() }

    private fun writeIndex(index: GenerationIndex) {
        generationsDir.mkdirs()
        indexFile.writeText(json.encodeToString(index.copy(updatedAtMillis = now())))
    }

    /** Retained, verified generations, newest first. Corrupt/rejected ones are not included. */
    fun retained(): List<Generation> = readIndex().generations
        .filter { it.verified }
        .sortedByDescending { it.createdAtMillis }

    fun latestKnownGood(): Generation? = readIndex().latestGenerationId
        ?.let { id -> readIndex().generations.firstOrNull { it.id == id && it.verified } }
        ?: retained().firstOrNull()

    // ------------------------------------------------------------------ promote

    /**
     * Verifies [candidate] and, only if it passes, moves it into the generations folder.
     *
     * Nothing is promoted on trust: the on-disk hash is recomputed, the verifier runs, and a
     * mismatch of either marks the candidate corrupt.
     */
    fun promote(
        candidate: File,
        expectedSha256: String,
        kind: String,
        verifier: BackupVerifier,
        note: String = ""
    ): PromotionResult {
        generationsDir.mkdirs()
        val index = readIndex()
        val actualHash = sha256(candidate)
        if (actualHash != expectedSha256) {
            return reject(candidate, index, kind, "hash mismatch: expected $expectedSha256, found $actualHash", note)
        }
        val failure = runCatching { verifier.verify(candidate) }.getOrElse { "verifier threw: ${it.message}" }
        if (failure != null) {
            return reject(candidate, index, kind, failure, note)
        }
        val id = IdGenerator.newUuid()
        val stamp = now()
        val fileName = "gen-$stamp-$id.psbackup"
        val target = File(generationsDir, fileName)
        if (!candidate.renameTo(target)) {
            candidate.copyTo(target, overwrite = true)
            if (!candidate.delete()) {
                target.delete()
                return PromotionResult(false, null, "could not move the candidate into the generation folder", index)
            }
        }
        val generation = Generation(
            id = id,
            fileName = fileName,
            createdAtMillis = stamp,
            sizeBytes = target.length(),
            sha256Hex = actualHash,
            verifiedAtMillis = now(),
            verified = true,
            kind = kind,
            note = note
        )
        val promoted = index.copy(
            latestGenerationId = id,
            generations = (index.generations + generation).toMutableList()
        )
        writeIndex(promoted)
        return PromotionResult(true, generation, null, readIndex())
    }

    private fun reject(
        candidate: File,
        index: GenerationIndex,
        kind: String,
        reason: String,
        note: String
    ): PromotionResult {
        rejectedDir.mkdirs()
        val stamp = now()
        val target = File(rejectedDir, "rejected-$stamp-${candidate.name}")
        if (candidate.exists()) {
            if (!candidate.renameTo(target)) {
                candidate.copyTo(target, overwrite = true)
                candidate.delete()
            }
        }
        index.rejected.add(
            Generation(
                id = IdGenerator.newUuid(),
                fileName = target.name,
                createdAtMillis = stamp,
                sizeBytes = if (target.exists()) target.length() else 0,
                sha256Hex = if (target.exists()) sha256(target) else "",
                verifiedAtMillis = now(),
                verified = false,
                kind = kind,
                note = "${note}REJECTED: $reason"
            )
        )
        writeIndex(index)
        return PromotionResult(false, null, reason, readIndex())
    }

    // ------------------------------------------------------------------ keep N

    /**
     * Trims retained generations to [keep] (>= [MIN_RETAINED]).
     *
     * Refuses outright when
     *   * fewer than [MIN_RETAINED] would remain,
     *   * nothing verified is left,
     *   * the newest generation would be removed,
     *   * unacknowledged corrupt generations exist (they must be dealt with first, so a corrupt file
     *     cannot quietly consume a retention slot).
     */
    fun trim(keep: Int = MIN_RETAINED): RotationResult {
        var index = readIndex()
        val verified = index.generations.filter { it.verified }.sortedByDescending { it.createdAtMillis }
        val blocked = when {
            keep < MIN_RETAINED -> "at least $MIN_RETAINED generations must be retained"
            verified.isEmpty() -> "no verified generation exists; refusing to rotate"
            index.rejected.any { !it.note.contains("ACKNOWLEDGED") } ->
                "corrupt generation(s) must be acknowledged before rotation"
            else -> null
        }
        if (blocked != null) {
            return RotationResult(verified.map { it.id }, emptyList(), blocked)
        }
        val survivors = verified.take(maxOf(keep, MIN_RETAINED))
        val removed = verified.drop(survivors.size)
        removed.forEach { gen ->
            val file = File(generationsDir, gen.fileName)
            if (file.exists() && !file.delete()) {
                return RotationResult(verified.map { it.id }, emptyList(),
                    "could not remove ${gen.fileName}; nothing else was removed")
            }
            index.generations.removeAll { it.id == gen.id }
        }
        if (survivors.none { it.id == index.latestGenerationId }) {
            index = index.copy(latestGenerationId = survivors.first().id)
        }
        writeIndex(index)
        return RotationResult(survivors.map { it.id }, removed.map { it.id }, null)
    }

    /** Marks every currently rejected generation as acknowledged, unblocking rotation. */
    fun acknowledgeRejected(): Int {
        var index = readIndex()
        val acknowledged = index.rejected.map {
            it.copy(note = it.note.replace("ACKNOWLEDGED", "") + " [ACKNOWLEDGED]")
        }
        index = index.copy(rejected = acknowledged.toMutableList())
        writeIndex(index)
        return acknowledged.size
    }

    // ------------------------------------------------------------------ verification

    /**
     * Re-verifies every retained generation from its own bytes. Returns the ones that no longer
     * verify; a generation that fails is marked unverified and moved to rejected/, so a later rotation
     * cannot count it.
     */
    fun verifyAll(verifier: BackupVerifier): Map<String, String> {
        val index = readIndex()
        val failures = LinkedHashMap<String, String>()
        val stillRetained = ArrayList<Generation>()
        val newlyRejected = ArrayList<Generation>()

        index.generations.filter { it.verified }.forEach { gen ->
            val file = File(generationsDir, gen.fileName)
            val reason = when {
                !file.exists() -> "file is missing"
                sha256(file) != gen.sha256Hex -> "hash changed on disk"
                else -> runCatching { verifier.verify(file) }
                    .getOrElse { "verifier threw: ${it.message}" }
            }
            if (reason == null) {
                stillRetained.add(gen)
            } else {
                failures[gen.id] = reason
                newlyRejected.add(gen.copy(verified = false, note = "${gen.note} CORRUPT: $reason"))
            }
        }

        if (failures.isNotEmpty()) {
            // a quarantined generation must not stay in the retained list, and the corrupt file itself
            // is moved into rejected/ so it cannot be mistaken for a good generation later
            val movedRejected = newlyRejected.map { corrupt ->
                val onDisk = File(generationsDir, corrupt.fileName)
                if (onDisk.exists()) {
                    rejectedDir.mkdirs()
                    val target = File(rejectedDir, "corrupt-${now()}-${corrupt.fileName}")
                    if (!onDisk.renameTo(target)) {
                        onDisk.copyTo(target, overwrite = true); onDisk.delete()
                    }
                    corrupt.copy(fileName = target.name)
                } else corrupt
            }
            val stillVerified = stillRetained.toMutableList()
            val latestStillGood = index.latestGenerationId?.takeIf { id -> stillVerified.any { it.id == id } }
                ?: stillVerified.maxByOrNull { it.createdAtMillis }?.id
            writeIndex(
                index.copy(
                    generations = stillVerified,
                    rejected = (index.rejected + movedRejected).toMutableList(),
                    latestGenerationId = latestStillGood
                )
            )
        }
        return failures
    }

    /** Files that exist on disk but are not in the index (leftovers from a crash). */
    fun untrackedFiles(): List<File> {
        val index = readIndex()
        val known = index.generations.map { it.fileName }.toSet() + index.rejected.map { it.fileName }.toSet()
        return generationsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".psbackup") && it.name !in known }
            ?: emptyList()
    }

    fun sha256(file: File): String {
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

    companion object {
        /** The canonical file name stays exactly as it always was. */
        const val CANONICAL_NAME = "PersonalSpace_Backup.psbackup"
        const val MIN_RETAINED = 3
        const val GENERATIONS_DIR_NAME = "generations"
    }
}
