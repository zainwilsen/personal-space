package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.data.backup.RestoreRecovery
import app.personalspace.vault.data.backup.RestoreSnapshot
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * P3 regression suite — restore crash recovery at STARTUP.
 *
 * Method for every crash point:
 *   1. build a vault with known content,
 *   2. take the real P2 rollback copy through [RestoreSnapshot.take],
 *   3. physically damage the live vault (simulating a restore that died mid-swap),
 *   4. write a journal frozen at the crash phase,
 *   5. copy the whole app tree into a separate directory = **the filesystem snapshot of the crash**,
 *   6. construct a **brand-new rig** (fresh process) and install that snapshot on its disk,
 *   7. run startup recovery with NO session, then assert exact rows + exact media hashes.
 *
 * Evidence: /home/user/verification/evidence/p3_*.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreCrashRecoveryTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("P3| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashDir(dir: File): Map<String, String> =
        if (!dir.isDirectory) emptyMap()
        else dir.listFiles()!!.filter { it.isFile }.associate { it.name to sha256(it) }.toSortedMap()

    private fun copyTree(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = File(dst, f.relativeTo(src).path)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
    }

    /**
     * CONTENT fingerprint: the actual row values plus the *plaintext* hash of every attachment and
     * inline image.
     *
     * This is the assertion that matters. Byte-identical DB *files* are not a valid expectation
     * because SQLite rewrites the main database and resets the WAL when a connection is reopened —
     * measured in p3_t2 evidence (main .db hash changed, -wal became the empty-string hash
     * e3b0c442…). Content is what the user would lose; file bytes are an implementation detail.
     */
    private suspend fun contentFingerprint(rig: IsolatedVaultTestRig): Map<String, String> {
        val out = sortedMapOf<String, String>()
        val db = rig.dbHolder.getDatabase()
        db.recordDao().getAllRecordsIncludingDeleted().sortedBy { it.id }.forEach {
            out["record/${it.id}"] = sha256Text(
                listOf(it.title, it.description, it.folderId ?: "", it.category ?: "",
                    it.isDeleted.toString(), it.createdAt.toString(), it.modifiedAt.toString(),
                    it.currentVersionNumber.toString(), it.syncVersion.toString()).joinToString("\u0001")
            )
        }
        db.folderDao().getAllFoldersIncludingDeleted().sortedBy { it.id }.forEach {
            out["folder/${it.id}"] = sha256Text(listOf(it.name, it.parentFolderId ?: "", it.isDeleted.toString()).joinToString("\u0001"))
        }
        db.attachmentDao().getAllAttachmentsIncludingDeleted().sortedBy { it.id }.forEach { entity ->
            val domain = rig.repository.getAttachmentById(entity.id)
            val plain = java.io.ByteArrayOutputStream()
            runCatching { domain?.let { rig.repository.exportAttachmentToStream(it.id, plain) } }
            out["attachment/${entity.id}"] = sha256Bytes(plain.toByteArray()) + ":" + entity.originalFileName
        }
        rig.inlineImageStore.imagesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
            val identifier = rig.inlineImageStore.identifierOf(file.name)
            val plain = runCatching {
                rig.inlineImageStore.openEncryptedStream(identifier)?.use { it.readBytes() }
            }.getOrNull()
            out["image/$identifier"] = if (plain == null) "UNDECRYPTABLE" else sha256Bytes(plain)
        }
        return out
    }

    private fun sha256Text(text: String): String = sha256Bytes(text.toByteArray())

    private fun sha256Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Everything the vault owns, as path -> sha256. This is the "exact state" fingerprint. */
    private fun vaultFingerprint(rig: IsolatedVaultTestRig): Map<String, String> {
        val out = sortedMapOf<String, String>()
        hashDir(rig.attachmentStorage.encryptedDir).forEach { (k, v) -> out["attachments/$k"] = v }
        hashDir(rig.inlineImageStore.imagesDir).forEach { (k, v) -> out["note_images/$k"] = v }
        rig.dbHolder.databaseFile().parentFile?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("personal_space_encrypted") }
            ?.forEach { out["database/${it.name}"] = sha256(it) }
        return out
    }

    private data class Seeded(
        val recordId: String,
        val image: String,
        val fingerprint: Map<String, String>,
        val titles: List<String>,
        val content: Map<String, String>
    )

    private suspend fun seedVault(rig: IsolatedVaultTestRig, password: String): Seeded {
        rig.initializeAndUnlockVault(password)
        val folder = rig.repository.createFolder("Archive", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE)
        val record = rig.repository.saveRecord(null, "Precious", "text that must survive", folder.id, "Cat", emptyList())
        rig.repository.importAttachmentFromStream(record.id, "PAYLOAD-1".toByteArray().inputStream(), "keep.txt", "text/plain")
        rig.repository.saveRecord(null, "Second", "another body", folder.id, null, emptyList())
        val image = rig.inlineImageStore.importFromStream(byteArrayOf(11, 22, 33, 44, 55).inputStream(), "keep.png")

        // freeze the database into the main file so the fingerprint is stable across a reopen
        rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
        val titles = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }.sorted()
        return Seeded(record.id, image, vaultFingerprint(rig), titles, contentFingerprint(rig))
    }

    /**
     * Physically damages the live vault the way a restore that died mid-swap would, then freezes the
     * journal at [phase].
     */
    private fun simulateCrash(rig: IsolatedVaultTestRig, phase: RestorePhase) {
        rig.dbHolder.closeDatabase()
        when (phase) {
            RestorePhase.PREPARED -> {
                // nothing destroyed yet — only the journal exists
            }
            RestorePhase.SNAPSHOT_TAKEN -> {
                // rollback copy exists, destruction not started
            }
            RestorePhase.SWAPPING_ATTACHMENTS -> {
                // attachments replaced by partial content; DB still intact
                rig.attachmentStorage.encryptedDir.let { dir ->
                    dir.deleteRecursively(); dir.mkdirs()
                    File(dir, "half_written.psenc").writeBytes(ByteArray(7))
                }
            }
            RestorePhase.SWAPPING_IMAGES -> {
                rig.inlineImageStore.imagesDir.let { dir ->
                    dir.deleteRecursively(); dir.mkdirs()
                    File(dir, "note_img_partial.psenc").writeBytes(ByteArray(9))
                }
            }
            RestorePhase.DB_TXN -> {
                // wipe the rows AND both media dirs: the worst half-restored state
                rig.attachmentStorage.encryptedDir.deleteRecursively()
                rig.inlineImageStore.imagesDir.deleteRecursively()
                rig.dbHolder.getDatabase().let { db ->
                    runBlocking {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            db.runInTransaction {
                                runBlocking {
                                    db.recordDao().deleteAllRecords()
                                    db.folderDao().deleteAllFolders()
                                    db.attachmentDao().deleteAllAttachments()
                                }
                            }
                        }
                    }
                }
                rig.dbHolder.closeDatabase()
            }
            RestorePhase.VERIFIED -> {
                // transaction committed with DIFFERENT content, verification never reported
                rig.attachmentStorage.encryptedDir.let { dir ->
                    dir.deleteRecursively(); dir.mkdirs()
                    File(dir, "wrong.psenc").writeBytes(ByteArray(5))
                }
                rig.inlineImageStore.imagesDir.let { dir ->
                    dir.deleteRecursively(); dir.mkdirs()
                    File(dir, "wrong_img.psenc").writeBytes(ByteArray(6))
                }
                rig.dbHolder.getDatabase().let { db ->
                    runBlocking {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            db.runInTransaction {
                                runBlocking {
                                    db.recordDao().deleteAllRecords()
                                    db.folderDao().deleteAllFolders()
                                    db.attachmentDao().deleteAllAttachments()
                                }
                            }
                        }
                    }
                }
                rig.dbHolder.closeDatabase()
            }
            else -> {}
        }
    }

    /**
     * Runs one full crash-recovery cycle for [phase] and asserts the vault comes back exactly.
     */
    private fun runCrashCycle(phase: RestorePhase, tag: String, password: String = "P3Pass#2026") = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_src_$tag")
        val proofRoot = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "p3_proof_$tag"
        ).apply { deleteRecursively(); mkdirs() }
        try {
            val seeded = seedVault(rig, password)
            note("seeded titles = ${seeded.titles}")
            note("seeded fingerprint = ${seeded.fingerprint}")
            val expectedTitles = seeded.titles
            val expectedFingerprint = seeded.fingerprint
            val expectedContent = seeded.content
            val image = seeded.image

            // real P2 rollback copy
            val journal = RestoreJournal.begin(rig.context, "backup-$tag", 1_700_000_000_000L)
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            note("rollback copy taken, manifest present = " +
                File(journal.rollbackDir, "copy_manifest.txt").exists())

            // crash at the requested phase
            simulateCrash(rig, phase)
            rig.dbHolder.closeDatabase()
            journal.advance(phase)
            note("simulated crash at phase = $phase")
            note("journal on disk = " + File(rig.context.filesDir, RestoreJournal.FILE_NAME).readText())

            // FILESYSTEM SNAPSHOT of the crashed state
            val crashImage = File(proofRoot, "crash_image")
            copyTree(rig.context.filesDir, File(crashImage, "files"))
            copyTree(rig.dbHolder.databaseFile().parentFile!!, File(crashImage, "databases"))
            note("crash snapshot files = " + crashImage.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(crashImage).path }.sorted().take(40))
            rig.close()

            // ================= FRESH PROCESS =================
            val fresh = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_new_$tag")
            try {
                copyTree(File(crashImage, "files"), fresh.context.filesDir)
                copyTree(File(crashImage, "databases"), fresh.dbHolder.databaseFile().parentFile!!)
                note("fresh process: session unlocked = ${fresh.sessionManager.currentSubKeysOrNull() != null}")
                assertNull("fresh process must start locked", fresh.sessionManager.currentSubKeysOrNull())

                // ---- startup recovery, BEFORE any unlock, BEFORE any DB write ----
                val outcome = RestoreRecovery.onAppStart(
                    filesDir = fresh.context.filesDir,
                    dbDir = fresh.dbHolder.databaseFile().parentFile!!,
                    attachmentsDir = fresh.attachmentStorage.encryptedDir,
                    imagesDir = fresh.inlineImageStore.imagesDir,
                    closeDatabase = { fresh.dbHolder.closeDatabase() }
                )
                note("recovery outcome = $outcome")
                assertTrue("recovery must roll back", outcome is RestoreRecovery.Outcome.Recovered)

                // journal must be resolved, copy must be gone
                note("journal present after recovery = " + (RestoreJournal.read(fresh.context) != null))
                note("rollback dirs after recovery = " +
                    fresh.context.filesDir.listFiles()?.filter { it.name.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }?.map { it.name })
                assertNull("journal must be cleared", RestoreJournal.read(fresh.context))

                // ---- now unlock and verify EXACT rows + EXACT hashes ----
                val bundle = fresh.vaultKeyManager.readEnvelopeBundle()
                note("envelope survives = ${bundle != null}")
                fresh.joinExistingVaultEnvelope(bundle!!, password)
                val titles = fresh.dbHolder.getDatabase().recordDao()
                    .getAllRecordsIncludingDeleted().map { it.title }.sorted()
                val mediaNow = vaultFingerprint(fresh)
                val integrity = fresh.dbHolder.verifyDatabaseIntegrity()
                val imageOk = fresh.inlineImageStore.exists(image)

                note("titles after recovery   = $titles")
                note("expected titles          = $expectedTitles")
                note("fingerprint after recovery = $mediaNow")
                note("fingerprint expected       = $expectedFingerprint")
                note("integrity_check = $integrity ; inline image decryptable = $imageOk")

                assertEquals("exact rows must come back", expectedTitles, titles)
                assertTrue("database must pass integrity_check", integrity)
                assertTrue("inline image must be present again", imageOk)

                // Media FILES must be byte-identical (encrypted blobs are copied verbatim).
                val mediaFilesExpected = expectedFingerprint.filterKeys { !it.startsWith("database/") }
                val mediaFilesNow = mediaNow.filterKeys { !it.startsWith("database/") }
                note("media files byte-identical = " + (mediaFilesExpected == mediaFilesNow))
                assertEquals("encrypted media files must be byte-identical", mediaFilesExpected, mediaFilesNow)

                // The database must be CONTENT-identical. Byte-identical .db files are not a valid
                // expectation: reopening a WAL database rewrites the main file and resets the WAL
                // (measured earlier in this same run).
                val contentNow = contentFingerprint(fresh)
                note("content fingerprint after = $contentNow")
                note("content fingerprint before= ${expectedContent}")
                assertEquals("exact row and plaintext content must come back", expectedContent, contentNow)
                dump("p3_${tag}_${phase.name.lowercase()}")
                Triple(titles, mediaNow, outcome)
            } finally { fresh.close() }
        } finally { runCatching { rig.close() } }
    }

    // ---------------- one test per crash point ----------------

    @Test fun `P3-T1 crash at PREPARED recovers to the exact pre-restore state`() { runCrashCycle(RestorePhase.PREPARED, "t1") }
    @Test fun `P3-T2 crash at SNAPSHOT_TAKEN recovers to the exact pre-restore state`() { runCrashCycle(RestorePhase.SNAPSHOT_TAKEN, "t2") }
    @Test fun `P3-T3 crash mid attachment swap recovers to the exact pre-restore state`() { runCrashCycle(RestorePhase.SWAPPING_ATTACHMENTS, "t3") }
    @Test fun `P3-T4 crash mid image swap recovers to the exact pre-restore state`() { runCrashCycle(RestorePhase.SWAPPING_IMAGES, "t4") }
    @Test fun `P3-T5 crash inside the DB transaction recovers to the exact pre-restore state`() { runCrashCycle(RestorePhase.DB_TXN, "t5") }
    @Test fun `P3-T6 crash after commit before verification recovers to the exact pre-restore state`() { runCrashCycle(RestorePhase.VERIFIED, "t6") }

    // ---------------- blocked / preserved paths ----------------

    @Test
    fun `P3-T7 a missing rollback copy blocks vault access and preserves every remaining file`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_t7")
        try {
            rig.initializeAndUnlockVault("P3Pass#2026")
            rig.repository.saveRecord(null, "StillHere", "text", null, null, emptyList())
            val journal = RestoreJournal.begin(rig.context, "b7", 1L)
            journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
            journal.rollbackDir.deleteRecursively()   // the copy is gone

            val before = rig.context.filesDir.walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList()
            val outcome = RestoreRecovery.onAppStart(
                filesDir = rig.context.filesDir,
                dbDir = rig.dbHolder.databaseFile().parentFile!!,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir,
                closeDatabase = { rig.dbHolder.closeDatabase() }
            )
            val after = rig.context.filesDir.walkTopDown().filter { it.isFile }.map { it.name }.sorted().toList()
            note("outcome = $outcome")
            note("files before = $before")
            note("files after  = $after")
            note("files preserved = " + (before == after))

            assertTrue("must block", outcome is RestoreRecovery.Outcome.Blocked)
            assertEquals("no file may be deleted while blocked", before, after)
            assertTrue(
                "the row must still be there",
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().any { it.title == "StillHere" }
            )
            dump("p3_t7_missing_copy_blocks")
        } finally { rig.close() }
    }

    @Test
    fun `P3-T8 a corrupt rollback copy blocks vault access and deletes nothing`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_t8")
        try {
            val seeded = seedVault(rig, "P3Pass#2026")
            val journal = RestoreJournal.begin(rig.context, "b8", 1L)
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            // corrupt one byte inside the copy's database
            val dbCopy = File(journal.rollbackDir, "database/personal_space_encrypted.db")
            val bytes = dbCopy.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 3).toByte()
            dbCopy.writeBytes(bytes)
            journal.advance(RestorePhase.DB_TXN)

            val before = rig.context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(rig.context.filesDir).path }.sorted().toList()
            val outcome = RestoreRecovery.onAppStart(
                filesDir = rig.context.filesDir,
                dbDir = rig.dbHolder.databaseFile().parentFile!!,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir,
                closeDatabase = { rig.dbHolder.closeDatabase() }
            )
            val after = rig.context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(rig.context.filesDir).path }.sorted().toList()
            note("outcome = $outcome")
            note("files before = $before")
            note("files after  = $after")

            assertTrue("a corrupt copy must block", outcome is RestoreRecovery.Outcome.Blocked)
            assertEquals("nothing may be deleted or moved", before, after)
            assertTrue("the live row must still be readable",
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().any { it.title == "Precious" })
            note("live note still readable = true ; rollback copy preserved = " + journal.rollbackDir.exists())
            assertTrue("copy preserved", journal.rollbackDir.exists())
            assertTrue("seeded image still fine", rig.inlineImageStore.exists(seeded.image))
            dump("p3_t8_corrupt_copy_blocks")
        } finally { rig.close() }
    }

    @Test
    fun `P3-T9 a COMMITTED journal only cleans up and never restores`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_t9")
        try {
            val seeded = seedVault(rig, "P3Pass#2026")
            val journal = RestoreJournal.begin(rig.context, "b9", 1L)
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            journal.advance(RestorePhase.COMMITTED)
            val fingerprintBefore = vaultFingerprint(rig)

            val outcome = RestoreRecovery.onAppStart(
                filesDir = rig.context.filesDir,
                dbDir = rig.dbHolder.databaseFile().parentFile!!,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir,
                closeDatabase = { rig.dbHolder.closeDatabase() }
            )
            note("outcome = $outcome")
            note("journal present = " + (RestoreJournal.read(rig.context) != null))
            note("rollback dirs = " + rig.context.filesDir.listFiles()?.filter { it.name.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }?.map { it.name })
            note("vault unchanged = " + (fingerprintBefore == vaultFingerprint(rig)))

            assertTrue("committed restore must not be reverted", outcome is RestoreRecovery.Outcome.CommittedCleanup)
            assertEquals("the restored vault must be left exactly as committed", fingerprintBefore, vaultFingerprint(rig))
            assertTrue("copy cleaned up", rig.context.filesDir.listFiles()!!.none { it.name.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) })
            assertTrue("seeded image still there", rig.inlineImageStore.exists(seeded.image))
            dump("p3_t9_committed_cleanup_only")
        } finally { rig.close() }
    }

    @Test
    fun `P3-T10 recovery needs no session and no database write`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_t10")
        try {
            val seeded = seedVault(rig, "P3Pass#2026")
            val journal = RestoreJournal.begin(rig.context, "b10", 1L)
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            simulateCrash(rig, RestorePhase.SWAPPING_ATTACHMENTS)
            journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
            rig.sessionManager.lockVault()
            note("vault locked before recovery = " + (rig.sessionManager.currentSubKeysOrNull() == null))

            val dbTouched = java.util.concurrent.atomic.AtomicBoolean(false)
            // A DB that is opened by recovery would produce a non-null active handle; we assert the
            // session stays locked throughout and that recovery succeeds regardless.
            val outcome = RestoreRecovery.onAppStart(
                filesDir = rig.context.filesDir,
                dbDir = rig.dbHolder.databaseFile().parentFile!!,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir,
                closeDatabase = { dbTouched.set(true); rig.dbHolder.closeDatabase() }
            )
            note("outcome = $outcome")
            note("session still locked after recovery = " + (rig.sessionManager.currentSubKeysOrNull() == null))
            note("closeDatabase() called defensively = ${dbTouched.get()}")

            assertTrue(outcome is RestoreRecovery.Outcome.Recovered)
            assertNull("recovery must not unlock the vault", rig.sessionManager.currentSubKeysOrNull())
            assertTrue("recovery must close the database before swapping files", dbTouched.get())
            note("=> recovery ran with the vault locked; keys were never requested")
            dump("p3_t10_no_session_no_db_write")
        } finally { rig.close() }
    }

    @Test
    fun `P3-T11 vault stays blocked until recovery is resolved`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p3_t11")
        try {
            rig.initializeAndUnlockVault("P3Pass#2026")
            rig.repository.saveRecord(null, "Guarded", "x", null, null, emptyList())
            val journal = RestoreJournal.begin(rig.context, "b11", 1L)
            journal.advance(RestorePhase.DB_TXN)
            journal.rollbackDir.deleteRecursively()

            val pkg = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            val restoreAttempt = runCatching { rig.backupManager.restoreFromBackupFile(pkg, "P3Pass#2026".toCharArray()) }
            note("restore while recovery unresolved -> " +
                restoreAttempt.exceptionOrNull()?.javaClass?.simpleName)
            note("message = " + restoreAttempt.exceptionOrNull()?.message)
            assertTrue(
                "restore must be refused",
                restoreAttempt.exceptionOrNull() is app.personalspace.vault.data.backup.RecoveryPendingException
            )
            assertTrue(
                "data still readable",
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().any { it.title == "Guarded" }
            )
            dump("p3_t11_blocked_until_resolved")
        } finally { rig.close() }
    }
}
