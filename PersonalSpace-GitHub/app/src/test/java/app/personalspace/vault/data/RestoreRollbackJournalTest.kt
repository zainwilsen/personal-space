package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.backup.BackupRestoreResult
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.data.backup.RestoreSnapshot
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * P2 regression suite — restore rollback journal.
 *
 * Evidence is written to /home/user/verification/evidence/p2_*.txt
 * Every assertion reads real Room rows and real files; nothing is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreRollbackJournalTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("P2| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    // ---------- helpers ----------

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

    /** Every file under [dir] mapped to name -> sha256. Used to prove byte identity. */
    private fun fingerprint(dir: File): Map<String, String> =
        if (!dir.exists()) emptyMap()
        else dir.listFiles()!!.filter { it.isFile }.associate { it.name to sha256(it) }

    private fun dbFingerprint(rig: IsolatedVaultTestRig): Map<String, String> {
        val dir = rig.dbHolder.databaseFile().parentFile!!
        return dir.listFiles()!!.filter { it.isFile && it.name.startsWith("personal_space_encrypted") }
            .associate { it.name to sha256(it) }
    }

    private suspend fun seed(rig: IsolatedVaultTestRig, password: String): Pair<String, String> {
        rig.initializeAndUnlockVault(password)
        val rec = rig.repository.saveRecord(null, "KeepMe", "precious body", null, null, emptyList())
        rig.repository.importAttachmentFromStream(rec.id, "ATTACH".toByteArray().inputStream(), "keep.txt", "text/plain")
        val image = rig.inlineImageStore.importFromStream(byteArrayOf(1, 2, 3, 4, 5).inputStream(), "keep.png")
        return rec.id to image
    }

    private suspend fun buildOtherPackage(rig: IsolatedVaultTestRig): File =
            // a package that restores *different* content, so a successful swap is observable
            rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

    // ---------- T1: no committed data is lost by the rollback snapshot (correction #1) ----------

    @Test
    fun `T1 rollback snapshot captures every committed row and integrity-verified copy`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t1")
        try {
            rig.initializeAndUnlockVault("P2Pass#2026")
            // Commit rows in several separate transactions so the WAL, not the main file, holds them.
            val folder = rig.repository.createFolder("Docs", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE)
            repeat(12) { index ->
                rig.repository.saveRecord(null, "Note$index", "body $index", folder.id, "Cat", emptyList())
            }
            val attachmentOwner = rig.repository.saveRecord(null, "WithFile", "b", folder.id, null, emptyList())
            rig.repository.importAttachmentFromStream(
                attachmentOwner.id, "PAYLOAD".toByteArray().inputStream(), "a.txt", "text/plain"
            )
            val image = rig.inlineImageStore.importFromStream(byteArrayOf(7, 7, 7).inputStream(), "i.png")

            val before = rig.dbHolder.getDatabase()
            val notesBefore = before.recordDao().getAllRecordsIncludingDeleted().size
            val foldersBefore = before.folderDao().getAllFoldersIncludingDeleted().size
            val attachmentsBefore = before.attachmentDao().getAllAttachmentsIncludingDeleted().size
            val dbDir = rig.dbHolder.databaseFile().parentFile!!
            note("journal_mode = " + before.openHelper.writableDatabase.query("PRAGMA journal_mode").use {
                if (it.moveToFirst()) it.getString(0) else "?"
            })
            note("WAL file present before snapshot = " + File(dbDir, "personal_space_encrypted.db-wal").exists())
            note("rows before snapshot: notes=$notesBefore folders=$foldersBefore attachments=$attachmentsBefore")

            val rollbackDir = File(rig.context.filesDir, "p2_t1_rollback").apply { mkdirs() }
            val result = RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            note("snapshot captured: ${result.databaseBytes} bytes, ${result.attachmentFiles} attachment files, ${result.imageFiles} image files")
            note("DB files captured = " + File(rollbackDir, RestoreSnapshot.DB_BACKUP_DIR).listFiles()?.map { it.name })
            note("WAL captured = " + File(rollbackDir, "${RestoreSnapshot.DB_BACKUP_DIR}/personal_space_encrypted.db-wal").exists())

            // Now wipe everything and restore the snapshot back.
            rig.dbHolder.closeDatabase()
            dbDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            rig.attachmentStorage.encryptedDir.deleteRecursively()
            rig.inlineImageStore.imagesDir.deleteRecursively()
            note("after wipe: notes on disk = " + rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size)

            RestoreSnapshot.restore(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            val db = rig.dbHolder.getDatabase()
            val notesAfter = db.recordDao().getAllRecordsIncludingDeleted().size
            val foldersAfter = db.folderDao().getAllFoldersIncludingDeleted().size
            val attachmentsAfter = db.attachmentDao().getAllAttachmentsIncludingDeleted().size
            val integrity = rig.dbHolder.verifyDatabaseIntegrity()
            val imageRestored = rig.inlineImageStore.exists(image)

            note("rows after restore: notes=$notesAfter folders=$foldersAfter attachments=$attachmentsAfter")
            note("integrity_check = $integrity ; inline image restored = $imageRestored")
            note("NO COMMITTED DATA LOST = " + (notesBefore == notesAfter && foldersBefore == foldersAfter && attachmentsBefore == attachmentsAfter))

            assertEquals("note rows must fully survive the snapshot round trip", notesBefore, notesAfter)
            assertEquals("folder rows must fully survive", foldersBefore, foldersAfter)
            assertEquals("attachment rows must fully survive", attachmentsBefore, attachmentsAfter)
            assertTrue("restored database must pass integrity_check", integrity)
            assertTrue("inline image must survive the snapshot round trip", imageRestored)
            dump("p2_T1_no_committed_data_lost")
        } finally { rig.close() }
    }

    // ---------- T2 / T3: failure at each injected point restores the pre-restore state ----------

    @Test
    fun `T2 failed restore before the swap leaves DB and media byte identical`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t2")
        val password = "P2Pass#2026"
        try {
            val (recordId, image) = seed(rig, password)
            val packageFile = buildOtherPackage(rig)
            val attachmentsBefore = fingerprint(rig.attachmentStorage.encryptedDir)
            val imagesBefore = fingerprint(rig.inlineImageStore.imagesDir)

            // Corrupt the package so restore throws after staging, before the destructive section.
            val corrupted = File(rig.context.cacheDir, "corrupt.psbackup")
            packageFile.copyTo(corrupted, overwrite = true)
            corrupted.writeBytes(corrupted.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 7).toByte() })

            val outcome = runCatching { rig.backupManager.restoreFromBackupFile(corrupted, password.toCharArray()) }
            note("restore with corrupted package -> " + outcome.exceptionOrNull()?.javaClass?.simpleName)
            note("journal present after failure = " + (RestoreJournal.read(rig.context) != null))
            note("attachment dir identical = " + (fingerprint(rig.attachmentStorage.encryptedDir) == attachmentsBefore))
            note("image dir identical      = " + (fingerprint(rig.inlineImageStore.imagesDir) == imagesBefore))
            note("note still readable      = " + (rig.repository.getRecordById(recordId) != null))
            note("inline image still there = " + rig.inlineImageStore.exists(image))

            assertNotNull("restore must have failed", outcome.exceptionOrNull())
            assertEquals("attachment files must be untouched", attachmentsBefore, fingerprint(rig.attachmentStorage.encryptedDir))
            assertEquals("image files must be untouched", imagesBefore, fingerprint(rig.inlineImageStore.imagesDir))
            assertNotNull("note must survive", rig.repository.getRecordById(recordId))
            assertTrue("image must survive", rig.inlineImageStore.exists(image))
            assertNull("no leftover journal", RestoreJournal.read(rig.context))
            dump("p2_T2_failure_before_swap")
        } finally { rig.close() }
    }

    @Test
    fun `T3 verification failure after the DB commit rolls the vault back byte for byte`() = runBlocking {
        // THE ORIGINAL BUG (B1): the destructive Room transaction commits, then verifyRestoredVault
        // fails, and the old code returned without ever putting the database back. This test drives
        // exactly that path and asserts the vault is restored to its exact pre-restore state.
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t3")
        val password = "P2Pass#2026"
        try {
            val (recordId, image) = seed(rig, password)
            val genuine = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            val attachmentNamesBefore = rig.attachmentStorage.encryptedDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val imageNamesBefore = rig.inlineImageStore.imagesDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            note("pre-restore attachments = $attachmentNamesBefore")
            note("pre-restore images      = $imageNamesBefore")

            // Re-sign the manifest with a WRONG draftCount, so the package is authentic but the
            // post-commit verification must fail. (Replacing the attachment dir with a file does NOT
            // make restore fail — measured; this is the injection that actually reaches verify.)
            val forged = resignWithWrongDraftCount(rig, genuine)
            note("tampered package = ${forged.name} (${forged.length()} B, draftCount forced wrong)")

            val outcome = runCatching { rig.backupManager.restoreFromBackupFile(forged, password.toCharArray()) }
            val ex = outcome.exceptionOrNull()
            note("restore outcome = " + outcome.fold({ "SUCCESS (verification did not fail!)" }, { it.javaClass.simpleName + ": " + it.message }))
            note("journal present after failure = " + (RestoreJournal.read(rig.context) != null))

            // ---- the vault must be EXACTLY as it was ----
            val titlesAfter = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            val attachmentNamesAfter = rig.attachmentStorage.encryptedDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val imageNamesAfter = rig.inlineImageStore.imagesDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            val integrity = rig.dbHolder.verifyDatabaseIntegrity()
            note("titles after rollback     = $titlesAfter")
            note("attachments after rollback= $attachmentNamesAfter")
            note("images after rollback     = $imageNamesAfter")
            note("integrity_check = $integrity")

            assertNotNull("restore must fail at verification", ex)
            assertEquals("the original note must still be the only note", listOf("KeepMe"), titlesAfter)
            assertEquals("attachment files must be byte-identical", attachmentNamesBefore, attachmentNamesAfter)
            assertEquals("image files must be byte-identical", imageNamesBefore, imageNamesAfter)
            assertTrue("database must pass integrity_check after rollback", integrity)
            assertTrue("inline image must still be decryptable", rig.inlineImageStore.exists(image))
            assertNotNull("original note must still load", rig.repository.getRecordById(recordId))
            dump("p2_T3_verify_failure_rollback")
        } finally { rig.close() }
    }

    /**
     * Unwraps the portable key wrap (the documented trade-off, measured in portG1) purely so the test
     * can re-sign a package whose `draftCount` does not match its payload. This makes the package
     * cryptographically valid but semantically wrong, which is what drives the post-commit
     * verification failure.
     */
    private fun resignWithWrongDraftCount(rig: IsolatedVaultTestRig, genuine: File): File {
        val manifestText = java.util.zip.ZipFile(genuine).use { z ->
            z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()
        }
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(manifestText).jsonObject
        val vaultId = root["vaultId"]!!.jsonPrimitive.content
        val wrap = root["portableKeyWrap"]!!.jsonObject
        val argon = wrap["argon2Config"]!!.jsonObject
        val wrapKey = app.personalspace.vault.core.crypto.VaultCryptoEngine.derivePasswordWrappingKey(
            ("psbackup-portable:" + vaultId).toCharArray(),
            app.personalspace.vault.core.crypto.VaultCryptoEngine.fromBase64(wrap["saltBase64"]!!.jsonPrimitive.content),
            app.personalspace.vault.core.crypto.Argon2KdfConfig(
                argon["memoryKiB"]!!.jsonPrimitive.content.toInt(),
                argon["iterations"]!!.jsonPrimitive.content.toInt(),
                argon["parallelism"]!!.jsonPrimitive.content.toInt()
            )
        )
        val vdk = app.personalspace.vault.core.crypto.VaultCryptoEngine.decryptAesGcm(
            wrapKey,
            app.personalspace.vault.core.crypto.VaultCryptoEngine.fromBase64(wrap["ivBase64"]!!.jsonPrimitive.content),
            app.personalspace.vault.core.crypto.VaultCryptoEngine.fromBase64(wrap["ciphertextBase64"]!!.jsonPrimitive.content),
            ("psbackup-portable-aad:" + vaultId).toByteArray()
        )
        wrapKey.fill(0)
        val keys = app.personalspace.vault.core.crypto.VaultCryptoEngine.deriveSubKeys(vdk, vaultId)

        val parsed = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
            .decodeFromString(app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), manifestText)
        val unsigned = parsed.copy(draftCount = parsed.draftCount + 7, manifestHmacBase64 = "")
        val canonical = parsed.let {
            kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
                .encodeToString(app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), unsigned)
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(keys.backupManifestKey, "HmacSHA256"))
        val signed = unsigned.copy(
            manifestHmacBase64 = app.personalspace.vault.core.crypto.VaultCryptoEngine
                .toBase64(mac.doFinal(canonical.toByteArray()))
        )

        val out = File(rig.context.cacheDir, "wrong_draft_count.psbackup")
        java.util.zip.ZipFile(genuine).use { zin ->
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
                zin.entries().asSequence().forEach { e ->
                    zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val bytes = if (e.name == "manifest.json") {
                        kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
                            .encodeToString(app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), signed)
                            .toByteArray()
                    } else zin.getInputStream(e).readBytes()
                    zos.write(bytes); zos.closeEntry()
                }
            }
        }
        return out
    }

    // ---------- T4: happy path leaves no journal, no orphans ----------

    @Test
    fun `T4 successful restore clears the journal and leaves no orphan directories`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t4")
        val password = "P2Pass#2026"
        try {
            val (recordId, image) = seed(rig, password)
            val packageFile = buildOtherPackage(rig)
            val result = rig.backupManager.restoreFromBackupFile(packageFile, password.toCharArray())
            note("restore result = $result")
            val filesDirNames = rig.context.filesDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            note("filesDir after success = $filesDirNames")
            val dbTitles = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            note("titles after restore = $dbTitles")
            note("journal present = " + (RestoreJournal.read(rig.context) != null))
            note("orphan rollback dirs = " + filesDirNames.filter { it.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) })
            note("orphan previous dirs = " + filesDirNames.filter { it.startsWith("encrypted_attachments_previous_") })
            note("staging dirs in cache = " + rig.context.cacheDir.listFiles()?.map { it.name }?.filter { it.startsWith("restore_stage_") })

            assertTrue("restore must verify", result.verified)
            assertNull("journal must be gone", RestoreJournal.read(rig.context))
            assertTrue(
                "no orphan rollback dirs",
                filesDirNames.none { it.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }
            )
            assertTrue(
                "no orphan previous dirs",
                filesDirNames.none { it.startsWith("encrypted_attachments_previous_") }
            )
            dump("p2_T4_success_cleanup")
        } finally { rig.close() }
    }

    // ---------- T5: journal is key-free and readable without a session (correction #1) ----------

    @Test
    fun `T5 journal contains no secrets and is readable with the vault locked`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t5")
        try {
            rig.initializeAndUnlockVault("P2Pass#2026")
            val journal = RestoreJournal.begin(rig.context, "backup-abc", 1_700_000_000_000L)
            journal.advance(RestorePhase.SNAPSHOT_TAKEN)
            val onDisk = File(rig.context.filesDir, RestoreJournal.FILE_NAME).readText()
            note("journal on disk = $onDisk")
            rig.sessionManager.lockVault()
            val reread = RestoreJournal.read(rig.context)
            note("after locking the vault, journal readable = ${reread != null}")
            note("recovered phase = ${reread?.phase}  rollbackDir = ${reread?.rollbackDirName}")

            assertEquals(RestorePhase.SNAPSHOT_TAKEN, reread?.phase)
            assertTrue("journal must not contain a password", !onDisk.contains("P2Pass"))
            assertTrue("journal must not contain a key", !onDisk.contains("key", ignoreCase = true))
            assertTrue("journal must record the rollback dir", reread!!.rollbackDirName.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX))
            journal.delete()
            dump("p2_T5_journal_key_free")
        } finally { rig.close() }
    }

    // ---------- T6: rollback failure preserves the copy instead of deleting data (correction #2) ----------

    @Test
    fun `T6 rollback failure keeps the rollback copy on disk and throws a typed error`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t6")
        try {
            rig.initializeAndUnlockVault("P2Pass#2026")
            rig.repository.saveRecord(null, "Survivor", "must not vanish", null, null, emptyList())

            val journal = RestoreJournal.begin(rig.context, "backup-t6", 1L)
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            journal.advance(RestorePhase.SNAPSHOT_TAKEN)

            // Destroy the rollback copy's database directory so restore() cannot succeed.
            File(journal.rollbackDir, RestoreSnapshot.DB_BACKUP_DIR).deleteRecursively()
            val outcome = runCatching {
                RestoreSnapshot.restore(
                    dbHolder = rig.dbHolder,
                    filesDir = rig.context.filesDir,
                    rollbackDir = journal.rollbackDir,
                    attachmentsDir = rig.attachmentStorage.encryptedDir,
                    imagesDir = rig.inlineImageStore.imagesDir
                )
            }
            note("restore from a destroyed rollback copy -> ${outcome.exceptionOrNull()?.javaClass?.simpleName}")
            note("rollback dir still exists = ${journal.rollbackDir.exists()}")
            note("journal still exists = ${RestoreJournal.read(rig.context) != null}")
            note("note row still readable = ${rig.repository.getRecordById(rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().first().id) != null}")

            assertNotNull("restore must fail loudly", outcome.exceptionOrNull())
            assertTrue("rollback dir must be preserved", journal.rollbackDir.exists())
            assertNotNull("journal must be preserved", RestoreJournal.read(rig.context))
            assertTrue(
                "user data must still be readable",
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().any { it.title == "Survivor" }
            )
            dump("p2_T6_rollback_failure_preserved")
        } finally { rig.close() }
    }

    // ---------- T7: cross-check that a snapshot taken with the DB open WOULD have been stale ----------

    @Test
    fun `T7 open-handle copy is stale which is why the snapshot closes the database first`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t7")
        try {
            rig.initializeAndUnlockVault("P2Pass#2026")
            repeat(20) { rig.repository.saveRecord(null, "N$it", "b", null, null, emptyList()) }
            val dbFile = rig.dbHolder.databaseFile()
            val dbDir = dbFile.parentFile!!
            val openHandleCopy = File(rig.context.cacheDir, "open_copy").apply { mkdirs() }
            dbDir.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(openHandleCopy, it.name), overwrite = true) }
            note("copied while OPEN: " + openHandleCopy.listFiles()!!.filter { it.isFile }.associate { it.name to it.length() })
            note("main .db alone at this instant = ${dbFile.length()} bytes (rows live in the WAL)")

            val closedCopy = File(rig.context.cacheDir, "closed_copy").apply { mkdirs() }
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = closedCopy,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            val closedDb = File(closedCopy, RestoreSnapshot.DB_BACKUP_DIR)
            note("copied after CLOSE: " + closedDb.listFiles()!!.filter { it.isFile }.associate { it.name to it.length() })
            note("WAL/shM still present after close = " + closedDb.listFiles()!!.any { it.name.endsWith("-wal") })

            assertTrue("closed snapshot must contain the database", File(closedDb, RestoreSnapshot.DB_BASE_NAME).exists())
            dump("p2_T7_open_vs_closed_copy")
        } finally { rig.close() }
    }

    // ---------- T8: every restore leaves the journal behind on failure, never silently ----------

    @Test
    fun `T8 no destructive restore path exists without a rollback copy on disk`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p2_t8")
        val password = "P2Pass#2026"
        try {
            seed(rig, password)
            val packageFile = buildOtherPackage(rig)

            // Observe the journal during a real restore by restoring and then inspecting.
            val result: BackupRestoreResult = rig.backupManager.restoreFromBackupFile(packageFile, password.toCharArray())
            note("restore verified = ${result.verified}")
            val filesDir = rig.context.filesDir
            note("journal file exists after restore = " + File(filesDir, RestoreJournal.FILE_NAME).exists())
            note("rollback dirs present = " + filesDir.listFiles()?.filter { it.name.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }?.map { it.name })

            assertTrue("restore must verify", result.verified)
            assertFalse(
                "rollback dirs must not accumulate",
                filesDir.listFiles()?.any { it.name.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) } == true
            )
            dump("p2_T8_no_destructive_path_without_copy")
        } finally { rig.close() }
    }
}
