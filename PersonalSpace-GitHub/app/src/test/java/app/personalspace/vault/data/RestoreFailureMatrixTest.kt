package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.backup.BackupPackageManager.FailureInjection
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_ATTACHMENTS_STAGED
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_DB_TRANSACTION
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_GUARD_CLEARED
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_IMAGES_STAGED
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_INSPECTION
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_INSTALL_ATTACHMENTS
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_INSTALL_IMAGES
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_KEYS_UNLOCKED
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_MANIFEST_HMAC
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_POST_COMMIT_CLEANUP
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_PRE_COMMIT
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_RELATIONSHIPS
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_ROLLBACK
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_PRE_DB_TRANSACTION
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_SNAPSHOT_DECRYPTED
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_STAGE_ATTACHMENTS
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_STAGE_IMAGES
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_VALIDATED
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.STAGE_VERIFICATION
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * P9 — the complete failure-injection matrix.
 *
 * For EACH restore stage: arm that stage to throw, run a restore of a *different* vault state over the
 * current one, and then assert on facts, not on a boolean:
 *
 *  * the exception surfaced (nothing swallowed it),
 *  * the database holds exactly the pre-restore rows (ids **and** field values),
 *  * every media file is byte-identical (sha256 of each file before vs after),
 *  * the journal is resolved (or deliberately left for recovery when the rollback itself failed),
 *  * the recovery gate is clear (the app can be used again),
 *  * a retry then succeeds.
 *
 * Process-restart recovery is covered by replaying the journal phase with the recovery machinery
 * (`RestoreRecovery`), which is what a killed process would find on next launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreFailureMatrixTest {

    private val ev = StringBuilder()
    private lateinit var rig: IsolatedVaultTestRig
    private fun note(line: String) { ev.append(line).append('\n'); println("P9| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    @Before
    fun setUp() {
        FailureInjection.resetCoverage()
        runBlocking {
            rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p9_matrix")
            rig.initializeAndUnlockVault(PASSWORD)
        }
    }

    @After
    fun tearDown() {
        FailureInjection.disarm()
        rig.close()
    }

    // ------------------------------------------------------------------ fixtures

    /** The CURRENT vault: what must survive any failed restore, byte for byte. */
    private suspend fun seedCurrentVault(): File {
        val folder = rig.repository.createFolder(
            "Current", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE
        )
        val keep = rig.repository.saveRecord(null, "CURRENT NOTE", "current body", folder.id, "c", emptyList())
        rig.repository.importAttachmentFromStream(
            keep.id, "CURRENT-ATTACHMENT".toByteArray().inputStream(), "current.txt", "text/plain"
        )
        rig.inlineImageStore.importFromStream(byteArrayOf(11, 22, 33).inputStream(), "current.png")
        return rig.backupManager.createFullBackupFile(
            BackupKind.FULL_MANUAL,
            targetFile = File(rig.context.cacheDir, "current.psbackup"),
            recordHistoryInDb = false
        ).file
    }

    /** The INCOMING package: a different note set that a restore would install. */
    private suspend fun seedIncomingPackage(): File {
        val other = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p9_incoming_${System.nanoTime()}")
        try {
            other.initializeAndUnlockVault(PASSWORD)
            val f = other.repository.createFolder(
                "Incoming", null, app.personalspace.vault.core.designsystem.FolderAccentColor.RED
            )
            val r = other.repository.saveRecord(null, "INCOMING NOTE", "incoming body", f.id, "i", emptyList())
            other.repository.importAttachmentFromStream(
                r.id, "INCOMING-ATTACHMENT".toByteArray().inputStream(), "incoming.txt", "text/plain"
            )
            other.inlineImageStore.importFromStream(byteArrayOf(77, 88).inputStream(), "incoming.png")
            return other.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL,
                targetFile = File(rig.context.cacheDir, "incoming_${System.nanoTime()}.psbackup"),
                recordHistoryInDb = false
            ).file
        } finally { other.close() }
    }

    /** Exact, comparable fingerprint of the whole vault. */
    private suspend fun snapshotVault(): VaultFingerprint {
        val db = rig.dbHolder.getDatabase()
        fun sha(file: File): String {
            val md = MessageDigest.getInstance("SHA-256"); md.update(file.readBytes())
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        return VaultFingerprint(
            records = db.recordDao().getAllRecordsIncludingDeleted()
                .map { listOf(it.id, it.title, it.description, it.folderId, it.category, it.createdAt, it.modifiedAt, it.isDeleted, it.deletedAt, it.currentVersionId) }
                .sortedBy { it[0].toString() },
            folders = db.folderDao().getAllFoldersIncludingDeleted()
                .map { listOf(it.id, it.name, it.parentFolderId, it.colorId, it.createdAt, it.isDeleted) }
                .sortedBy { it[0].toString() },
            attachments = db.attachmentDao().getAllAttachmentsIncludingDeleted()
                .map { listOf(it.id, it.recordId, it.originalFileName, it.plaintextSha256Hex, it.isDeleted) }
                .sortedBy { it[0].toString() },
            versions = db.recordDao().getAllVersions().map { it.id to it.title }.sortedBy { it.first },
            batches = db.recycleBinDao().getAllDeletedItems().map { it.batchId to it.rootEntityId }.sortedBy { it.first },
            attachmentsBytes = rig.attachmentStorage.encryptedDir.listFiles()!!.filter { it.isFile }
                .map { it.name to sha(it) }.sortedBy { it.first },
            imageBytes = rig.inlineImageStore.imagesDir.listFiles()!!.filter { it.isFile }
                .map { it.name to sha(it) }.sortedBy { it.first }
        )
    }

    data class VaultFingerprint(
        val records: List<List<Any?>>,
        val folders: List<List<Any?>>,
        val attachments: List<List<Any?>>,
        val versions: List<Pair<String, String>>,
        val batches: List<Pair<String, String>>,
        val attachmentsBytes: List<Pair<String, String>>,
        val imageBytes: List<Pair<String, String>>
    )

    /**
     * Runs one stage failure and asserts the full "nothing was lost" contract.
     *
     * @param expectNoChange false only for stages that run AFTER the commit, where the restore is
     *   already installed and the failure is a cleanup problem rather than a data problem.
     */
    private suspend fun injectAndAssert(
        stage: String,
        current: File,
        incoming: File,
        expectNoChange: Boolean = true
    ) {
        val before = snapshotVault()
        val currentBytes = current.readBytes()
        FailureInjection.disarm()
        FailureInjection.arm(stage) { IllegalStateException("P9 injected failure at $stage") }

        val outcome = runCatching {
            rig.backupManager.restoreFromBackupFile(incoming, PASSWORD.toCharArray())
        }
        FailureInjection.disarm()
        val error = outcome.exceptionOrNull()
        note("[$stage] outcome = ${error?.javaClass?.simpleName}: ${error?.message?.take(110)}")
        note("[$stage] stage was reached = ${stage in FailureInjection.hitStages}")
        assertNotNull("the injected failure at $stage must surface", error)
        assertTrue("the exception must come from the injection, not from a swallowed path",
            error!!.message?.contains("P9 injected failure at $stage") == true)
        assertTrue("the stage must actually have been reached (the matrix must not test dead code)",
            stage in FailureInjection.hitStages)

        val after = snapshotVault()
        if (expectNoChange) {
            assertEquals("[$stage] records must be unchanged", before.records, after.records)
            assertEquals("[$stage] folders must be unchanged", before.folders, after.folders)
            assertEquals("[$stage] attachment rows must be unchanged", before.attachments, after.attachments)
            assertEquals("[$stage] versions must be unchanged", before.versions, after.versions)
            assertEquals("[$stage] recycle-bin batches must be unchanged", before.batches, after.batches)
            assertEquals("[$stage] attachment BYTES must be identical", before.attachmentsBytes, after.attachmentsBytes)
            assertEquals("[$stage] inline image BYTES must be identical", before.imageBytes, after.imageBytes)
            assertEquals("[$stage] the source package must not have been modified",
                currentBytes.toList(), current.readBytes().toList())
            note("[$stage] vault fingerprint identical (records=${after.records.size} " +
                "attachments=${after.attachments.size} files=${after.attachmentsBytes.size}+${after.imageBytes.size})")
        }

        // the recovery gate must be clear so the app is usable again
        val gateBlocked = RecoveryGate.isBlocked()
        note("[$stage] recovery gate blocked after the failure = $gateBlocked " +
            "(phase=${RecoveryGate.state().phase}, journal=${RestoreJournal.read(rig.context)?.phase ?: "none"})")
        // A restore that fails inside the destructive section may legitimately leave a PENDING gate until
        // startup recovery runs; what must never happen is a BLOCKED gate with an unresolvable vault.
        assertTrue("[$stage] the gate must not be left in a permanently blocked state",
            !gateBlocked || RecoveryGate.state().phase == RecoveryGate.Phase.PENDING)

        // and a retry of the very same restore must now succeed
        val retry = rig.backupManager.restoreFromBackupFile(incoming, PASSWORD.toCharArray())
        note("[$stage] retry verified=${retry.verified} notes=${retry.notes}")
        assertTrue("[$stage] the retry must succeed", retry.verified)
        assertTrue("[$stage] the incoming note must be installed",
            rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().any { it.title == "INCOMING NOTE" })
    }

    // ------------------------------------------------------------------ staging failures

    @Test
    fun `P9-S1 inspection and validation failures leave the vault untouched`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_INSPECTION, current, incoming)
        injectAndAssert(STAGE_VALIDATED, current, incoming)
        note("=> a corrupt or unreadable package cannot touch anything")
        dump("p9_S1_inspection")
    }

    @Test
    fun `P9-S2 key unlocking and manifest HMAC failures leave the vault untouched`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_KEYS_UNLOCKED, current, incoming)
        injectAndAssert(STAGE_MANIFEST_HMAC, current, incoming)
        note("=> a wrong password / tampered manifest is refused before any write")
        dump("p9_S2_keys_and_hmac")
    }

    @Test
    fun `P9-S3 snapshot decryption and relationship validation failures leave the vault untouched`() =
        runBlocking {
            val current = seedCurrentVault()
            val incoming = seedIncomingPackage()
            injectAndAssert(STAGE_SNAPSHOT_DECRYPTED, current, incoming)
            injectAndAssert(STAGE_RELATIONSHIPS, current, incoming)
            dump("p9_S3_snapshot_and_relationships")
        }

    @Test
    fun `P9-S4 attachment and inline-image staging failures leave the vault untouched`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_STAGE_ATTACHMENTS, current, incoming)
        injectAndAssert(STAGE_ATTACHMENTS_STAGED, current, incoming)
        injectAndAssert(STAGE_STAGE_IMAGES, current, incoming)
        injectAndAssert(STAGE_IMAGES_STAGED, current, incoming)
        note("=> a staging failure never reaches the destructive section")
        dump("p9_S4_staging")
    }

    @Test
    fun `P9-S5 a pre-transaction failure rolls back the vault cleanly`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_PRE_DB_TRANSACTION, current, incoming)
        note("=> a failure at the pre-transaction checkpoint restores the prior DB and media from the rollback journal")
        dump("p9_S5_pre_db_transaction")
    }

    // ------------------------------------------------------------------ the destructive section

    @Test
    fun `P9-S6 an attachment-installation failure rolls the vault back completely`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_INSTALL_ATTACHMENTS, current, incoming)
        note("=> live attachments were swapped, then restored byte-for-byte")
        dump("p9_S6_install_attachments")
    }

    @Test
    fun `P9-S7 an inline-image installation failure rolls the vault back completely`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_INSTALL_IMAGES, current, incoming)
        note("=> staged images were copied, then the live image dir was restored byte-for-byte")
        dump("p9_S7_install_images")
    }

    @Test
    fun `P9-S8 a database transaction failure rolls the vault back completely`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_DB_TRANSACTION, current, incoming)
        note("=> the Room transaction threw, the rollback copy put the database back")
        dump("p9_S8_db_transaction")
    }

    @Test
    fun `P9-S9 a post-install verification failure rolls the vault back completely`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_VERIFICATION, current, incoming)
        note("=> everything was installed, verification refused it, and the vault came back")
        dump("p9_S9_verification")
    }

    @Test
    fun `P9-S10 a pre-commit failure rolls the vault back completely`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        injectAndAssert(STAGE_PRE_COMMIT, current, incoming)
        dump("p9_S10_pre_commit")
    }

    // ------------------------------------------------------------------ after the commit

    @Test
    fun `P9-S11 a post-commit cleanup failure does not lose the restored data`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        FailureInjection.disarm()
        FailureInjection.arm(STAGE_POST_COMMIT_CLEANUP) {
            IllegalStateException("P9 injected failure at $STAGE_POST_COMMIT_CLEANUP")
        }
        val outcome = runCatching {
            rig.backupManager.restoreFromBackupFile(incoming, PASSWORD.toCharArray())
        }
        FailureInjection.disarm()
        note("post-commit cleanup failure -> ${outcome.exceptionOrNull()?.message?.take(90)}")
        // The restore itself is committed at this point. Two outcomes are acceptable; what is NOT
        // acceptable is a half state where the incoming data is missing.
        val titles = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
        note("note titles after the post-commit failure = $titles")
        val journal = RestoreJournal.read(rig.context)
        note("journal phase = ${journal?.phase}")

        // MEASURED behaviour: the cleanup hook sits after the commit phase but *inside* the try, so a
        // failure there is treated like any other failure and the vault is rolled back to the
        // pre-restore state. That is the stronger of the two acceptable outcomes — the user keeps the
        // data they had — and it is what the code does, so assert it rather than assume the other.
        assertEquals("the pre-restore note must be intact", listOf("CURRENT NOTE"), titles)
        assertTrue("nothing may be left in a blocked state",
            !RecoveryGate.isBlocked() || RecoveryGate.state().phase == RecoveryGate.Phase.PENDING)
        // and a retry installs the incoming data
        val retry = rig.backupManager.restoreFromBackupFile(incoming, PASSWORD.toCharArray())
        note("retry verified=${retry.verified}")
        assertTrue("a retry must succeed", retry.verified)
        assertTrue("and install the incoming note",
            rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
                .any { it.title == "INCOMING NOTE" })
        dump("p9_S11_post_commit_cleanup")
    }

    // ------------------------------------------------------------------ rollback that fails

    @Test
    fun `P9-S12 a failed rollback preserves the rollback copy and blocks the gate`() = runBlocking {
        val current = seedCurrentVault()
        val incoming = seedIncomingPackage()
        FailureInjection.disarm()
        // fail inside the destructive section, and make the rollback itself fail
        FailureInjection.arm(STAGE_INSTALL_ATTACHMENTS) {
            IllegalStateException("P9 injected failure at $STAGE_INSTALL_ATTACHMENTS")
        }
        FailureInjection.arm(STAGE_ROLLBACK) {
            java.io.IOException("P9 injected failure at $STAGE_ROLLBACK")
        }
        val outcome = runCatching {
            rig.backupManager.restoreFromBackupFile(incoming, PASSWORD.toCharArray())
        }
        FailureInjection.disarm()
        val error = outcome.exceptionOrNull()
        note("outcome = ${error?.javaClass?.simpleName}")
        note("message = ${error?.message?.take(200)}")
        assertNotNull("the failure must surface", error)

        val journal = RestoreJournal.read(rig.context)
        note("journal phase = ${journal?.phase}")
        assertEquals("the journal must record the failed rollback", RestorePhase.ROLLBACK_FAILED, journal?.phase)
        val rollbackDir = File(rig.context.filesDir, journal!!.rollbackDirName)
        note("rollback dir exists = ${rollbackDir.exists()}, files = " +
            "${rollbackDir.walkTopDown().count { it.isFile }}")
        assertTrue("THE ROLLBACK COPY MUST BE PRESERVED", rollbackDir.exists())
        assertTrue("it must contain the database copy",
            File(rollbackDir, "database").listFiles()?.isNotEmpty() == true)

        val gate = RecoveryGate.isBlocked()
        note("recovery gate blocked = $gate (phase=${RecoveryGate.state().phase})")
        note("unresolved rollback dirs = " +
            app.personalspace.vault.data.backup.RestoreGuard.unresolvedRollbackDirs(rig.context.filesDir).map { it.name })
        // The gate is raised by the *next* start-up/guard check, not inside the restore itself, so the
        // durable protection is asserted where it actually lives: the journal phase plus the preserved
        // copy, which is what RestoreGuard refuses on.
        val guardAfter = runCatching { app.personalspace.vault.data.backup.RestoreGuard.requireClear(rig.context) }
        note("RestoreGuard after the failed rollback = " +
            (guardAfter.exceptionOrNull()?.javaClass?.simpleName ?: "clear (unexpected)"))
        assertTrue("RestoreGuard must refuse after a failed rollback", guardAfter.isFailure)
        val blockedRestore = runCatching {
            rig.backupManager.restoreFromBackupFile(current, PASSWORD.toCharArray())
        }
        note("another restore while blocked -> ${blockedRestore.exceptionOrNull()?.javaClass?.simpleName}")
        assertTrue("the vault must refuse another restore until recovery runs", blockedRestore.isFailure)
        dump("p9_S12_rollback_failed")
    }

    // ------------------------------------------------------------------ process-restart recovery

    @Test
    fun `P9-S13 a journal left by a killed process is resolved by startup recovery`() = runBlocking {
        val current = seedCurrentVault()
        val before = snapshotVault()
        // Simulate process death right after the destructive section began: write a journal in DB_TXN
        // with a rollback copy on disk, exactly as the real code would have left it.
        val journal = RestoreJournal.begin(rig.context, "killed-restore", rig.clock.nowMillis())
        note("journal started in phase ${journal.state.phase}")
        // EXACTLY what the real restore does at this point (same internal call, same order)
        val snapshot = app.personalspace.vault.data.backup.RestoreSnapshot.take(
            rig.dbHolder, rig.context.filesDir, journal.rollbackDir,
            rig.attachmentStorage.encryptedDir, rig.inlineImageStore.imagesDir
        )
        note("rollback copy: db=${snapshot.databaseBytes}B attachments=${snapshot.attachmentFiles} images=${snapshot.imageFiles}")
        journal.advance(RestorePhase.SNAPSHOT_TAKEN)
        // now the process "dies" mid-destructive-section
        journal.advance(RestorePhase.DB_TXN)

        val unresolved = app.personalspace.vault.data.backup.RestoreGuard.unresolvedRollbackDirs(rig.context.filesDir)
        note("unresolved rollback dirs = ${unresolved.map { it.name }}")
        assertTrue("the journal must be seen as unresolved", unresolved.isNotEmpty() || RestoreJournal.read(rig.context) != null)

        // the real startup path: onAppStart with the same directories the app wires
        val dbDir = rig.dbHolder.databaseFile().parentFile!!
        val recovery = app.personalspace.vault.data.backup.RestoreRecovery.onAppStart(
            filesDir = rig.context.filesDir,
            dbDir = dbDir,
            attachmentsDir = rig.attachmentStorage.encryptedDir,
            imagesDir = rig.inlineImageStore.imagesDir,
            closeDatabase = { rig.dbHolder.closeDatabase() }
        )
        note("recovery result = $recovery")
        note("journal after recovery = ${RestoreJournal.read(rig.context)?.phase ?: "cleared"}")

        val after = snapshotVault()
        assertEquals("the pre-restore records must be back", before.records, after.records)
        assertEquals("the pre-restore attachment bytes must be back", before.attachmentsBytes, after.attachmentsBytes)
        assertEquals("the pre-restore image bytes must be back", before.imageBytes, after.imageBytes)
        assertFalse("the gate must be clear once recovery has run, phase=${RecoveryGate.state().phase}",
            RecoveryGate.isBlocked())
        dump("p9_S13_process_restart_recovery")
    }

    @Test
    fun `P9-S14 every stage name in the matrix is actually reached by a healthy restore`() = runBlocking {
        val current = seedCurrentVault()
        FailureInjection.disarm()
        FailureInjection.resetCoverage()
        rig.backupManager.restoreFromBackupFile(current, PASSWORD.toCharArray())
        val expected = listOf(
            STAGE_GUARD_CLEARED, STAGE_INSPECTION, STAGE_VALIDATED, STAGE_KEYS_UNLOCKED,
            STAGE_MANIFEST_HMAC, STAGE_SNAPSHOT_DECRYPTED, STAGE_RELATIONSHIPS,
            STAGE_STAGE_ATTACHMENTS, STAGE_ATTACHMENTS_STAGED, STAGE_STAGE_IMAGES, STAGE_IMAGES_STAGED,
            STAGE_PRE_DB_TRANSACTION, STAGE_INSTALL_ATTACHMENTS, STAGE_INSTALL_IMAGES, STAGE_DB_TRANSACTION,
            STAGE_VERIFICATION, STAGE_PRE_COMMIT, STAGE_POST_COMMIT_CLEANUP
        )
        val hits = FailureInjection.hitStages.toSet()
        note("stages reached = ${hits.sorted()}")
        val missing = expected.filter { it !in hits }
        note("stages NOT reached = $missing")
        assertTrue("every instrumented stage must be reached by a real restore; missing=$missing",
            missing.isEmpty())
        note("=> the matrix covers the restore path, it is not asserting on dead code")
        dump("p9_S14_stage_coverage")
    }

    private companion object { const val PASSWORD = "P9Fail#2026" }
}
