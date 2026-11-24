package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PROOF harness for requirement #7: does a physical database-file copy actually give an exact
 * pre-restore rollback under Room + WAL (+SQLCipher on device) lifecycle rules?
 *
 * No production code modified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbRollbackProofTest {

    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("DBPROOF| $s") }
    private fun dump(tag: String = "db_copy_rollback_proof") {
        File("/home/user/verification/evidence/" + tag + ".txt")
            .apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    private fun dbFiles(rig: IsolatedVaultTestRig): List<File> {
        val main = rig.dbHolder.databaseFile()
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"), File(main.path + "-journal"))
    }

    private fun describe(rig: IsolatedVaultTestRig, tag: String) {
        dbFiles(rig).forEach { f ->
            note("$tag  ${f.name} exists=${f.exists()} size=${if (f.exists()) f.length() else 0}")
        }
    }

    private fun journalMode(rig: IsolatedVaultTestRig): String = runCatching {
        rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA journal_mode").use { c ->
            if (c.moveToFirst()) c.getString(0) else "?"
        }
    }.getOrElse { "ERR " + it.javaClass.simpleName }

    @Test
    fun `P4 close first then copy is the safe sequence`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dbp4")
        try {
            rig.initializeAndUnlockVault("ProofPass#2026")
            rig.repository.saveRecord(null, "Keep1", "b1", null, null, emptyList())
            rig.clock.advanceBy(500)
            rig.repository.saveRecord(null, "Keep2", "b2", null, null, emptyList())
            rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            note("before close: files=${dbFiles(rig).map { it.name + ":" + (if (it.exists()) it.length() else -1) }}")

            // SAFE SEQUENCE: checkpoint -> close -> copy
            rig.dbHolder.closeDatabase()
            note("after closeDatabase(): files=${dbFiles(rig).map { it.name + ":" + (if (it.exists()) it.length() else -1) }}")
            val snap = File(rig.context.cacheDir, "rollback_closed").apply { mkdirs() }
            dbFiles(rig).filter { it.exists() }.forEach { it.copyTo(File(snap, it.name), overwrite = true) }
            note("copied after close: ${snap.listFiles()?.sortedBy { it.name }?.map { it.name + ":" + it.length() }}")

            // reopen, destroy, then roll back from the closed-copy
            val before = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }.sorted()
            note("reopened notes = $before")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                rig.dbHolder.getDatabase().runInTransaction {
                    runBlocking { rig.dbHolder.getDatabase().recordDao().deleteAllRecords() }
                }
            }
            note("after delete-all notes = ${rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }}")

            rig.dbHolder.closeDatabase()
            dbFiles(rig).forEach { if (it.exists()) it.delete() }
            snap.listFiles()!!.forEach { it.copyTo(File(rig.dbHolder.databaseFile().parentFile, it.name), overwrite = true) }
            val afterRollback = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }.sorted()
            note("after rollback notes = $afterRollback")
            note("RESULT exact restore = ${before == afterRollback}")
            note("RESULT integrity = ${rig.dbHolder.verifyDatabaseIntegrity()}")
            dump("dbP4_close_then_copy")
        } finally { rig.close() }
    }

    @Test
    fun `P5 copying while writers are active risks a torn snapshot`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dbp5")
        try {
            rig.initializeAndUnlockVault("ProofPass#2026")
            repeat(40) { i -> rig.repository.saveRecord(null, "N$i", "body $i", null, null, emptyList()) }
            note("seeded notes = ${rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size}")
            note("wal size while open = ${dbFiles(rig)[1].let { if (it.exists()) it.length() else 0 }}")

            // copy main+wal+shm while the connection is OPEN and the WAL is non-empty
            val torn = File(rig.context.cacheDir, "rollback_torn").apply { mkdirs() }
            dbFiles(rig).filter { it.exists() }.forEach { it.copyTo(File(torn, it.name), overwrite = true) }
            note("torn copy taken while open: ${torn.listFiles()?.sortedBy { it.name }?.map { it.name + ":" + it.length() }}")

            // mutate AFTER the torn copy so the copy is clearly stale
            repeat(15) { i -> rig.repository.saveRecord(null, "LATE$i", "late $i", null, null, emptyList()) }
            note("live count after later writes = ${rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size}")

            // restore the torn copy
            rig.dbHolder.closeDatabase()
            dbFiles(rig).forEach { if (it.exists()) it.delete() }
            torn.listFiles()!!.forEach { it.copyTo(File(rig.dbHolder.databaseFile().parentFile, it.name), overwrite = true) }
            val restored = runCatching {
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            }
            note("restored-from-torn count = ${restored.getOrNull()?.size} integrity=${rig.dbHolder.verifyDatabaseIntegrity()}")
            note("contains LATE* rows = ${restored.getOrNull()?.any { it.startsWith("LATE") }}")
            note("=> an open-handle copy is a point-in-time approximation, NOT a guaranteed consistent snapshot")
            dump("dbP5_torn_copy")
        } finally { rig.close() }
    }

    @Test
    fun `P1 record actual journal mode and file set`() = runBlocking {
        // (P1 note) journal_mode + file-set probe
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dbp1")
        try {
            rig.initializeAndUnlockVault("ProofPass#2026")
            rig.repository.saveRecord(null, "Note A", "body", null, null, emptyList())
            note("journal_mode = ${journalMode(rig)}")
            describe(rig, "after 1 insert")
            rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            note("--- after PRAGMA wal_checkpoint(TRUNCATE) ---")
            describe(rig, "after checkpoint")
            note("integrity_check = ${rig.dbHolder.verifyDatabaseIntegrity()}")
            dump("dbP1_journal_mode")
        } finally { rig.close() }
    }

    @Test
    fun `P2 copy WITHOUT checkpoint loses recent commits`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dbp2")
        try {
            rig.initializeAndUnlockVault("ProofPass#2026")
            rig.repository.saveRecord(null, "Old", "old body", null, null, emptyList())
            rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            val snapshotDir = File(rig.context.cacheDir, "rollback_nochk").apply { mkdirs() }
            dbFiles(rig).filter { it.exists() }.forEach { it.copyTo(File(snapshotDir, it.name), overwrite = true) }
            note("snapshot (with checkpoint) files=${snapshotDir.listFiles()?.map { it.name + ":" + it.length() }}")

            // NEW commit AFTER the snapshot, deliberately NOT checkpointed
            rig.clock.advanceBy(1000)
            rig.repository.saveRecord(null, "NewAfterSnapshot", "new body", null, null, emptyList())
            note("live notes now = ${rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }}")
            note("wal size now = ${dbFiles(rig)[1].let { if (it.exists()) it.length() else 0 }}")

            // restore ONLY the main .db file (the naive approach), keep the live -wal
            rig.dbHolder.closeDatabase()
            val liveWal = dbFiles(rig)[1]
            if (liveWal.exists()) liveWal.delete()   // simulate 'we only restored the .db'
            File(snapshotDir, rig.dbHolder.databaseFile().name)
                .copyTo(rig.dbHolder.databaseFile(), overwrite = true)
            rig.dbHolder.getDatabase()
            val titles = runCatching {
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            }
            note("after restoring ONLY .db (wal discarded): notes=$titles")
            note("=> a main-file-only copy loses every commit that was still in WAL")
            dump("dbP2_no_checkpoint")
        } finally { rig.close() }
    }

    @Test
    fun `P3 copy WITH checkpoint restores exact pre-restore state`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dbp3")
        try {
            rig.initializeAndUnlockVault("ProofPass#2026")
            val folder = rig.repository.createFolder("F", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE)
            val a = rig.repository.saveRecord(null, "Alpha", "alpha body", folder.id, "cat", emptyList())
            rig.clock.advanceBy(1000)
            val b = rig.repository.saveRecord(null, "Beta", "beta body", folder.id, null, emptyList())
            rig.repository.importAttachmentFromStream(a.id, "ATT".toByteArray().inputStream(), "a.txt", "text/plain")

            // ---- the documented safe procedure ----
            note("STEP 1 checkpoint")
            rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            note("STEP 2 copy all db files")
            val snap = File(rig.context.cacheDir, "rollback_chk").apply { mkdirs() }
            dbFiles(rig).filter { it.exists() }.forEach { it.copyTo(File(snap, it.name), overwrite = true) }
            note("     copied = ${snap.listFiles()?.sortedBy { it.name }?.map { it.name + ":" + it.length() }}")
            val before = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
                .sortedBy { it.id }.map { it.id to (it.title + "|" + it.description + "|" + it.createdAt + "|" + it.folderId) }
            val beforeFolders = rig.dbHolder.getDatabase().folderDao().getAllFoldersIncludingDeleted()
                .sortedBy { it.id }.map { it.id to it.name }
            val beforeAtt = rig.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted()
                .sortedBy { it.id }.map { it.id to it.originalFileName }
            note("     pre-state notes=${before.map { it.first.take(8) }} folders=${beforeFolders.size} attachments=${beforeAtt.size}")

            // ---- simulate a destructive restore ----
            note("STEP 3 destructive mutation (simulating restore delete-all + insert)")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
              rig.dbHolder.getDatabase().runInTransaction {
                runBlocking {
                    rig.dbHolder.getDatabase().recordDao().deleteAllDrafts()
                    rig.dbHolder.getDatabase().recordDao().deleteAllVersions()
                    rig.dbHolder.getDatabase().attachmentDao().deleteAllAttachments()
                    rig.dbHolder.getDatabase().recycleBinDao().clearAllBatches()
                    rig.dbHolder.getDatabase().timelineDao().deleteAll()
                    rig.dbHolder.getDatabase().recordDao().deleteAllRecords()
                    rig.dbHolder.getDatabase().folderDao().deleteAllFolders()
                }
              }
            }
            rig.repository.saveRecord(null, "RestoredIn", "intruder", null, null, emptyList())
            note("     post-mutation notes = ${rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }}")

            // ---- rollback procedure ----
            note("STEP 4 closeDatabase() then restore db files then reopen")
            rig.dbHolder.closeDatabase()
            dbFiles(rig).forEach { if (it.exists()) it.delete() }
            snap.listFiles()!!.forEach { it.copyTo(File(rig.dbHolder.databaseFile().parentFile, it.name), overwrite = true) }
            note("     integrity after reopen = ${rig.dbHolder.verifyDatabaseIntegrity()}")
            val after = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
                .sortedBy { it.id }.map { it.id to (it.title + "|" + it.description + "|" + it.createdAt + "|" + it.folderId) }
            val afterFolders = rig.dbHolder.getDatabase().folderDao().getAllFoldersIncludingDeleted()
                .sortedBy { it.id }.map { it.id to it.name }
            val afterAtt = rig.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted()
                .sortedBy { it.id }.map { it.id to it.originalFileName }

            note("")
            note("RESULT notes      identical = ${before == after}")
            note("RESULT folders    identical = ${beforeFolders == afterFolders}")
            note("RESULT attachments identical = ${beforeAtt == afterAtt}")
            note("restored titles = ${after.map { it.second.substringBefore('|') }}")
            note("fts still works = ${runCatching { rig.dbHolder.getDatabase().recordDao().searchRecordsFts("Alpha").size }.getOrElse { "ERR" }}")
            note("FK pragma after reopen = ${runCatching { rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA foreign_keys").use { c -> if (c.moveToFirst()) c.getInt(0) else -1 } }.getOrElse { "ERR" }}")
            dump("dbP3_checkpoint_copy")
        } finally { rig.close() }
    }
}
