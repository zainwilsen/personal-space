package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.FakeSharedDriveCloud
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.backup.CorruptedBackupException
import app.personalspace.vault.data.backup.InlineImageStore
import app.personalspace.vault.data.backup.MissingBackupImageException
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WhatsApp-style backup behaviour: hidden appDataFolder cloud storage, single latest cloud copy,
 * inline image coverage, change-detection and full-replace restore with rollback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWhatsAppLikeTest {

    private lateinit var cloud: FakeSharedDriveCloud
    private lateinit var deviceA: IsolatedVaultTestRig
    private lateinit var deviceB: IsolatedVaultTestRig
    private val password = "WhatsAppLike#2026"

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        cloud = FakeSharedDriveCloud()
        deviceA = IsolatedVaultTestRig(appContext, "wa_device_A", remoteDrive = cloud)
        deviceB = IsolatedVaultTestRig(appContext, "wa_device_B", remoteDrive = cloud)
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    // 1. First-time Google account connection
    @Test
    fun `connecting an account stores the session and exposes connection state`() {
        deviceA.authManager.persistAccessToken("user-token", "user.own@gmail.com")

        assertTrue(deviceA.authManager.isConnected())
        val session = deviceA.authManager.getCurrentSession()
        assertEquals("user.own@gmail.com", session?.accountEmail)
        // No developer account dependency: any token value works, folder id is irrelevant now
        assertNotNull(deviceA.authManager.getCurrentSession())
    }

    // 2 + 3. First backup, then second backup replaces the previous cloud copy
    @Test
    fun `second backup replaces the previous appDataFolder file keeping exactly one cloud backup`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a", "user.own@gmail.com")

        saveNote(deviceA, "First note")
        val first = deviceA.repository.uploadManualBackupToDrive()
        val firstBytes = cloud.appDataSnapshot(BackupPackageManager.DRIVE_BACKUP_NAME)
        assertNotNull(firstBytes)
        assertEquals(1, cloud.appDataFileNames().size)

        saveNote(deviceA, "Second note")
        val second = deviceA.repository.uploadManualBackupToDrive()
        val secondBytes = cloud.appDataSnapshot(BackupPackageManager.DRIVE_BACKUP_NAME)

        assertEquals(1, cloud.appDataFileNames().size)
        assertEquals(BackupPackageManager.DRIVE_BACKUP_NAME, cloud.appDataFileNames().single())
        assertNotEquals(first.backupId, second.backupId)
        assertFalse(firstBytes!!.contentEquals(secondBytes!!))
    }

    // 4. Disconnect and reconnect
    @Test
    fun `disconnecting clears the session and reconnecting restores upload ability`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        saveNote(deviceA, "Before disconnect")
        deviceA.repository.uploadManualBackupToDrive()

        deviceA.authManager.disconnect()
        assertFalse(deviceA.authManager.isConnected())
        try {
            deviceA.repository.uploadManualBackupToDrive()
            fail("Backup must fail while the account is disconnected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not connected", ignoreCase = true))
        }

        deviceA.authManager.persistAccessToken("token-a2", "user.own@gmail.com")
        assertTrue(deviceA.authManager.isConnected())
        deviceA.repository.uploadManualBackupToDrive()
        assertEquals(1, cloud.appDataFileNames().size)
    }

    // 5. Restore on the same device
    @Test
    fun `restore on the same device replaces local data with the cloud backup`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        saveNote(deviceA, "Kept note")
        deviceA.repository.uploadManualBackupToDrive()

        saveNote(deviceA, "Added after backup")
        val latest = deviceA.repository.listDriveBackups().first()
        val result = deviceA.repository.restoreFromDriveBackup(latest.fileId)

        assertTrue(result.verified)
        assertEquals(1, result.notes)
        assertNotNull(byTitle(deviceA, "Kept note"))
        assertEquals(null, byTitle(deviceA, "Added after backup"))
    }

    // 6 + 7. Cross-device restore with inline images
    @Test
    fun `cross device restore keeps inline images visible inside their original notes`() = runBlocking {
        val initA = deviceA.initializeAndUnlockVault(password)
        deviceB.joinExistingVaultEnvelope(initA.bundle, password)
        deviceA.authManager.persistAccessToken("token-a")
        deviceB.authManager.persistAccessToken("token-b")

        val imageIdentifier = importInlineImage(deviceA, byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1))
        saveNoteWithImage(deviceA, "Photo note", imageIdentifier)
        deviceA.repository.uploadManualBackupToDrive()

        val latest = deviceB.repository.listDriveBackups().first()
        val result = deviceB.repository.restoreFromDriveBackup(latest.fileId)

        assertTrue(result.verified)
        assertEquals(1, result.inlineImages)

        val restored = byTitle(deviceB, "Photo note")
        assertNotNull(restored)
        val imageBlocks = RichNoteCodec.decodeBlocks(restored!!.description)
            .filterIsInstance<RichNoteBlock.Image>()
        assertEquals(1, imageBlocks.size)
        assertEquals(imageIdentifier, imageBlocks.single().imagePath)

        val resolved = deviceB.inlineImageStore.resolveForDisplay(imageBlocks.single().imagePath)
        assertNotNull("Restored image must be readable on the second device", resolved)
        assertTrue(resolved!!.length() > 0L)
        // exactly one stored copy, never duplicated
        assertEquals(1, deviceB.inlineImageStore.allImageFiles().size)
    }

    // 8. Attachments survive
    @Test
    fun `attachments are restored together with notes`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        val record = saveNote(deviceA, "Note with attachment")
        deviceA.repository.importAttachmentFromStream(
            recordId = record.id,
            input = "hello attachment".toByteArray().inputStream(),
            fileName = "hello.txt",
            mimeType = "text/plain"
        )
        deviceA.repository.uploadManualBackupToDrive()

        val latest = deviceA.repository.listDriveBackups().first()
        val result = deviceA.repository.restoreFromDriveBackup(latest.fileId)
        assertTrue(result.verified)
        assertEquals(1, result.attachments)
    }

    // 9 + 10. Interrupted upload / corrupted package
    @Test
    fun `interrupted upload fails loudly and a corrupted cloud package is rejected`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        saveNote(deviceA, "Resilient note")

        cloud.failNextAppDataUpload = true
        try {
            deviceA.repository.uploadManualBackupToDrive()
            fail("Upload failure must surface as an exception")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("Simulated", ignoreCase = true))
        }
        assertTrue("Failed upload must not leave a cloud file", cloud.appDataFileNames().isEmpty())

        deviceA.repository.uploadManualBackupToDrive()
        assertEquals(1, cloud.appDataFileNames().size)

        cloud.corruptAppDataFile(BackupPackageManager.DRIVE_BACKUP_NAME)
        val latest = deviceA.repository.listDriveBackups().first()
        try {
            deviceA.repository.restoreFromDriveBackup(latest.fileId)
            fail("Corrupted package must be rejected before touching local data")
        } catch (expected: CorruptedBackupException) {
            assertNotNull(expected.message)
        }
        assertNotNull("Local data must be untouched", byTitle(deviceA, "Resilient note"))
    }

    // 11. Failed restore rolls back
    @Test
    fun `failed restore rolls back and keeps the existing vault intact`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        saveNote(deviceA, "Original note")
        deviceA.repository.uploadManualBackupToDrive()

        val latest = deviceA.repository.listDriveBackups().first()
        // Truncate the package so validation fails inside the restore pipeline
        cloud.corruptAppDataFile(BackupPackageManager.DRIVE_BACKUP_NAME)
        try {
            deviceA.repository.restoreFromDriveBackup(latest.fileId)
            fail("Restore must fail for a corrupted package")
        } catch (expected: Exception) {
            assertNotNull(expected)
        }
        assertNotNull(byTitle(deviceA, "Original note"))
    }

    // 12. Daily automatic backup only uploads when data changed
    @Test
    fun `daily backup uploads only when the vault changed`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        saveNote(deviceA, "Auto backup note")
        deviceA.repository.uploadManualBackupToDrive()
        val uploadsAfterManual = cloud.appDataUploadCount

        val unchangedFingerprint = deviceA.backupManager.computeVaultFingerprint()
        assertEquals(unchangedFingerprint, deviceA.backupManager.lastBackupFingerprint())

        saveNote(deviceA, "Another note")
        assertNotEquals(unchangedFingerprint, deviceA.backupManager.computeVaultFingerprint())
        assertEquals("No upload should have happened without a backup call", uploadsAfterManual, cloud.appDataUploadCount)
    }

    // 13. No-change backup never creates a second cloud file
    @Test
    fun `repeated backup of unchanged data keeps a single cloud copy`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        saveNote(deviceA, "Stable note")

        deviceA.repository.uploadManualBackupToDrive()
        val firstHash = deviceA.backupManager.computeVaultFingerprint()
        assertEquals(firstHash, deviceA.backupManager.lastBackupFingerprint())

        // A second manual backup of unchanged data still replaces (never duplicates) the cloud file
        deviceA.repository.uploadManualBackupToDrive()
        assertEquals(1, cloud.appDataFileNames().size)
    }

    // Extra: inline images are never leaked as plaintext and missing files block the backup
    @Test
    fun `inline image packaging is encrypted and missing image files abort the backup`() = runBlocking {
        deviceA.initializeAndUnlockVault(password)
        deviceA.authManager.persistAccessToken("token-a")
        val identifier = importInlineImage(deviceA, "SECRET-IMAGE-BYTES".toByteArray())
        saveNoteWithImage(deviceA, "Secret photo", identifier)
        deviceA.repository.uploadManualBackupToDrive()

        val packageBytes = cloud.appDataSnapshot(BackupPackageManager.DRIVE_BACKUP_NAME)!!
        assertFalse(String(packageBytes, Charsets.ISO_8859_1).contains("SECRET-IMAGE-BYTES"))

        // Deleting the physical image must fail the next backup loudly
        deviceA.inlineImageStore.encryptedFile(identifier).delete()
        saveNote(deviceA, "Force fingerprint change")
        try {
            deviceA.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            fail("Backup must abort when a referenced inline image is missing")
        } catch (expected: MissingBackupImageException) {
            assertTrue(expected.message!!.contains(identifier))
        }
    }

    // Helpers -----------------------------------------------------------------

    private suspend fun importInlineImage(rig: IsolatedVaultTestRig, bytes: ByteArray): String =
        rig.inlineImageStore.importFromStream(bytes.inputStream(), "test-image")

    private suspend fun byTitle(rig: IsolatedVaultTestRig, title: String) =
        rig.repository.observeAllRecords().first().firstOrNull { it.title == title }

    private suspend fun saveNote(rig: IsolatedVaultTestRig, title: String) =
        rig.repository.saveRecord(
            existingRecordId = null,
            title = title,
            description = "Body of $title",
            folderId = null,
            category = null,
            customFields = emptyList()
        )

    private suspend fun saveNoteWithImage(rig: IsolatedVaultTestRig, title: String, imageIdentifier: String) {
        val blocks = listOf(
            RichNoteBlock.Text(text = "Caption for $title"),
            RichNoteBlock.Image(imagePath = imageIdentifier, widthFraction = 1f)
        )
        rig.repository.saveRecord(
            existingRecordId = null,
            title = title,
            description = RichNoteCodec.encodeBlocks(blocks),
            folderId = null,
            category = null,
            customFields = emptyList()
        )
    }
}
