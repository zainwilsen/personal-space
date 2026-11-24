package app.personalspace.vault.data.backup

import android.content.Context
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * P2 — file-level rollback journal for restore.
 *
 * Purpose: make the destructive part of a restore recoverable **without any vault key**, so that a
 * failure at any point (thrown exception, OOM, or process death) leaves enough on disk to put the
 * vault back exactly as it was.
 *
 * Design constraints locked by measurement, not assumption:
 *
 * 1. WAL CHECKPOINT BEHAVIOUR (measured, `verification/evidence/dbP1_journal_mode.txt`):
 *    `PRAGMA wal_checkpoint(TRUNCATE)` does **not** shrink the WAL file on this build
 *    (`.db` 4096 B, `-wal` 391432 B, checkpoint left `-wal` at 391432 B). Therefore a checkpoint
 *    alone is NOT proof that committed data lives in the main `.db` file.
 *
 * 2. OPEN-HANDLE COPY IS NOT A SNAPSHOT (measured, `dbP5_torn_copy.txt`):
 *    copying while the connection is open produced a stale snapshot (40 vs 55 notes) and
 *    `PRAGMA integrity_check` still returned `true`. Integrity check cannot detect staleness.
 *
 * 3. COPYING `.db` WITHOUT `-wal` LOSES COMMITS (measured, `dbP2_no_checkpoint.txt`):
 *    restoring only the main file produced `notes = []` — every WAL-resident commit was lost.
 *
 * 4. SAFE SEQUENCE IS: CLOSE THE DATABASE, THEN COPY (measured, `dbP4_close_then_copy.txt`):
 *    after `closeDatabase()` SQLite runs its own checkpoint on the last connection close, `-wal`
 *    and `-shm` disappear, and an exact restore succeeded.
 *
 * The journal therefore always records the phase, and the snapshot is only ever taken with the
 * database closed. `-wal`/`-shm` are copied as well when they still exist, so the snapshot is valid
 * even if a future SQLite build keeps a persistent WAL.
 */
enum class RestorePhase {
    /** Journal written, nothing has been touched yet. */
    PREPARED,

    /** Rollback copy of DB + attachments + inline images is on disk and verified non-empty. */
    SNAPSHOT_TAKEN,

    /** Live attachments directory is being swapped. */
    SWAPPING_ATTACHMENTS,

    /** Inline image directory is being written. */
    SWAPPING_IMAGES,

    /** The destructive Room transaction is running. */
    DB_TXN,

    /** Rows written; verification is in progress. */
    VERIFIED,

    /** Restore is complete and committed; safe to clean the rollback copy. */
    COMMITTED,

    /** Restore failed and the pre-restore state was put back successfully. */
    ROLLED_BACK,

    /**
     * Restore failed AND the automatic rollback failed. The rollback copy is intentionally
     * preserved on disk; the vault must not be used until a human resolves this.
     */
    ROLLBACK_FAILED
}

/** On-disk journal record. Contains no secrets — recovery must be possible before unlock. */
internal data class RestoreJournalState(
    val journalVersion: Int,
    val backupId: String,
    val phase: RestorePhase,
    val rollbackDirName: String,
    val attachmentsBackupDirName: String,
    val imagesBackupDirName: String,
    val startedAtMillis: Long
)

/**
 * Thrown when a restore failed and the automatic rollback also failed.
 *
 * The [rollbackDir] is deliberately left on disk and is reported so a human can recover manually.
 * Nothing in this class ever deletes user data to "recover".
 */
class RestoreRollbackFailedException(
    message: String,
    val rollbackDir: File,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Writes and reads `files/restore_journal.json`.
 *
 * Every write is `temp file -> fsync -> atomic rename`, so a process death mid-write leaves either
 * the previous complete journal or the new complete journal — never a half-written one.
 */
internal class RestoreJournal(
    private val context: Context,
    val rollbackDir: File,
    var state: RestoreJournalState
) {
    private val journalFile = File(context.filesDir, FILE_NAME)

    fun advance(phase: RestorePhase) {
        state = state.copy(phase = phase)
        writeAtomically(state)
    }

    private fun writeAtomically(value: RestoreJournalState) {
        val tmp = File(journalFile.parentFile, journalFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encode(value).toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                journalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            // ATOMIC_MOVE is not supported on every filesystem; replace is still crash-safe for our
            // purposes because the source file is already fully fsynced.
            Files.move(tmp.toPath(), journalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete() {
        runCatching { journalFile.delete() }
        runCatching { File(journalFile.parentFile, journalFile.name + ".tmp").delete() }
    }

    companion object {
        const val FILE_NAME = "restore_journal.json"
        private const val VERSION = 1

        const val ROLLBACK_DIR_PREFIX = "restore_rollback_"

        fun encode(value: RestoreJournalState): String = buildString {
            append('{')
            append("\"journalVersion\":").append(value.journalVersion).append(',')
            append("\"backupId\":\"").append(escape(value.backupId)).append("\",")
            append("\"phase\":\"").append(value.phase.name).append("\",")
            append("\"rollbackDirName\":\"").append(escape(value.rollbackDirName)).append("\",")
            append("\"attachmentsBackupDirName\":\"").append(escape(value.attachmentsBackupDirName)).append("\",")
            append("\"imagesBackupDirName\":\"").append(escape(value.imagesBackupDirName)).append("\",")
            append("\"startedAtMillis\":").append(value.startedAtMillis)
            append('}')
        }

        fun decode(text: String): RestoreJournalState? = runCatching {
            fun str(key: String): String {
                val marker = "\"$key\":\""
                val start = text.indexOf(marker)
                require(start >= 0) { "missing $key" }
                val from = start + marker.length
                return text.substring(from, text.indexOf('"', from))
            }

            fun num(key: String): Long {
                val marker = "\"$key\":"
                val start = text.indexOf(marker)
                require(start >= 0) { "missing $key" }
                val from = start + marker.length
                var end = from
                while (end < text.length && (text[end].isDigit() || text[end] == '-')) end++
                return text.substring(from, end).toLong()
            }

            RestoreJournalState(
                journalVersion = num("journalVersion").toInt(),
                backupId = str("backupId"),
                phase = RestorePhase.valueOf(str("phase")),
                rollbackDirName = str("rollbackDirName"),
                attachmentsBackupDirName = str("attachmentsBackupDirName"),
                imagesBackupDirName = str("imagesBackupDirName"),
                startedAtMillis = num("startedAtMillis")
            )
        }.getOrNull()

        fun read(context: Context): RestoreJournalState? = readFrom(context.filesDir)

        /** P3: file-based variant so startup recovery can run without any Android context wiring. */
        fun readFrom(filesDir: File): RestoreJournalState? {
            val file = File(filesDir, FILE_NAME)
            if (!file.exists()) return null
            return decode(file.readText(StandardCharsets.UTF_8))
        }

        /** P3: atomic write used by startup recovery to mark a resolved restore. */
        fun writeTo(filesDir: File, state: RestoreJournalState) {
            val journalFile = File(filesDir, FILE_NAME)
            val tmp = File(filesDir, FILE_NAME + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(encode(state).toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    journalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(tmp.toPath(), journalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        fun deleteFrom(filesDir: File) {
            runCatching { File(filesDir, FILE_NAME).delete() }
            runCatching { File(filesDir, FILE_NAME + ".tmp").delete() }
        }

        /** Creates a new journal in [PREPARED] with a fresh rollback directory. */
        fun begin(context: Context, backupId: String, nowMillis: Long): RestoreJournal {
            val rollbackDir = File(
                context.filesDir,
                ROLLBACK_DIR_PREFIX + java.util.UUID.randomUUID().toString()
            ).apply { mkdirs() }
            val state = RestoreJournalState(
                journalVersion = VERSION,
                backupId = backupId,
                phase = RestorePhase.PREPARED,
                rollbackDirName = rollbackDir.name,
                attachmentsBackupDirName = ATTACHMENTS_BACKUP,
                imagesBackupDirName = IMAGES_BACKUP,
                startedAtMillis = nowMillis
            )
            val journal = RestoreJournal(context, rollbackDir, state)
            journal.writeAtomically(state)
            return journal
        }

        const val ATTACHMENTS_BACKUP = "attachments"
        const val IMAGES_BACKUP = "note_images"

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Takes and restores the pre-restore rollback copy.
 *
 * The snapshot is only ever taken with the database **closed** (see class docs on [RestoreJournal]).
 */
internal object RestoreSnapshot {

    data class Result(val databaseBytes: Long, val attachmentFiles: Int, val imageFiles: Int)

    /**
     * Captures DB + attachments + inline images into [rollbackDir].
     *
     * Sequence (measured safe sequence):
     *  1. best-effort `wal_checkpoint(TRUNCATE)` (measured: does not shrink the WAL — kept only so a
     *     large WAL is folded into the main file where the build supports it);
     *  2. `closeDatabase()` — this is the authoritative step: SQLite checkpoints the WAL on the last
     *     connection close, which is what guarantees every committed row is in the main file;
     *  3. copy `.db` plus `-wal` / `-shm` **if they still exist**;
     *  4. copy the attachments and inline-image directories.
     *
     * @throws IOException if the resulting copy is empty, so callers never proceed on a bad snapshot.
     */
    fun take(
        dbHolder: VaultDatabaseHolder,
        filesDir: File,
        rollbackDir: File,
        attachmentsDir: File,
        imagesDir: File
    ): Result {
        checkpointBestEffort(dbHolder)

        // Authoritative step: with no open connection SQLite folds the WAL into the main database
        // on close, so the main file alone is a complete, consistent image of all committed data.
        dbHolder.closeDatabase()

        val dbDir = dbHolder.databaseFile().parentFile
            ?: throw IOException("Cannot resolve database directory for rollback snapshot.")
        val dbTarget = File(rollbackDir, DB_BACKUP_DIR).apply { mkdirs() }
        var databaseBytes = 0L
        var copiedDbFiles = 0
        dbDir.listFiles()?.filter { it.isFile && it.name.startsWith(DB_BASE_NAME) }?.forEach { file ->
            val target = File(dbTarget, file.name)
            file.copyTo(target, overwrite = true)
            databaseBytes += target.length()
            copiedDbFiles++
        }
        if (copiedDbFiles == 0 || databaseBytes == 0L) {
            throw IOException("Rollback snapshot is empty: no database files were captured.")
        }

        copyDir(attachmentsDir, File(rollbackDir, RestoreJournal.ATTACHMENTS_BACKUP))
        copyDir(imagesDir, File(rollbackDir, RestoreJournal.IMAGES_BACKUP))

        // P3: record what the copy contains, so startup recovery can verify the copy before
        // trusting it, and can verify the restored state before any copy is deleted.
        RestoreRecovery.writeManifest(rollbackDir, dbDir, attachmentsDir, imagesDir)

        return Result(
            databaseBytes = databaseBytes,
            attachmentFiles = countFiles(File(rollbackDir, RestoreJournal.ATTACHMENTS_BACKUP)),
            imageFiles = countFiles(File(rollbackDir, RestoreJournal.IMAGES_BACKUP))
        )
    }

    /**
     * Puts the pre-restore state back exactly as [take] captured it.
     *
     * Deletes current DB/media **only after** the rollback copy has been proven present, so a failed
     * rollback can never be the step that loses data.
     */
    fun restore(
        dbHolder: VaultDatabaseHolder,
        filesDir: File,
        rollbackDir: File,
        attachmentsDir: File,
        imagesDir: File
    ) {
        val dbBackupDir = File(rollbackDir, DB_BACKUP_DIR)
        if (!dbBackupDir.isDirectory) {
            throw IOException("Rollback copy missing: ${dbBackupDir.absolutePath}")
        }
        val dbDir = dbHolder.databaseFile().parentFile
            ?: throw IOException("Cannot resolve database directory for rollback.")

        dbHolder.closeDatabase()

        // 1. remove the (possibly half-restored) database files
        dbDir.listFiles()?.filter { it.isFile && it.name.startsWith(DB_BASE_NAME) }?.forEach { it.delete() }
        // 2. put the captured files back
        dbBackupDir.listFiles()?.forEach { file -> file.copyTo(File(dbDir, file.name), overwrite = true) }

        // 3. media: replace wholesale so no half-copied file survives
        restoreDir(File(rollbackDir, RestoreJournal.ATTACHMENTS_BACKUP), attachmentsDir)
        restoreDir(File(rollbackDir, RestoreJournal.IMAGES_BACKUP), imagesDir)
    }

    private fun checkpointBestEffort(dbHolder: VaultDatabaseHolder) {
        runCatching {
            dbHolder.getDatabase().openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        }
    }

    private fun copyDir(source: File, target: File) {
        if (!source.exists()) {
            target.mkdirs()
            return
        }
        target.mkdirs()
        source.listFiles()?.forEach { file ->
            if (file.isFile) file.copyTo(File(target, file.name), overwrite = true)
        }
    }

    private fun restoreDir(source: File, target: File) {
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        if (!source.exists()) return
        source.listFiles()?.forEach { file ->
            if (file.isFile) file.copyTo(File(target, file.name), overwrite = true)
        }
    }

    private fun countFiles(dir: File): Int =
        dir.listFiles()?.count { it.isFile } ?: 0

    const val DB_BASE_NAME = "personal_space_encrypted.db"
    const val DB_BACKUP_DIR = "database"
}
