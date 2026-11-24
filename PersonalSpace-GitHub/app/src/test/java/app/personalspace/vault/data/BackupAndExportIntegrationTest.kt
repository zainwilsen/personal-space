package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.CryptoAuthException
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.data.backup.CorruptedBackupException
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.CustomFieldType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupAndExportIntegrationTest {

    private lateinit var sourceRig: IsolatedVaultTestRig
    private lateinit var targetRig: IsolatedVaultTestRig

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        sourceRig = IsolatedVaultTestRig(appContext, "backup_source")
        targetRig = IsolatedVaultTestRig(appContext, "backup_target")
    }

    @After
    fun tearDown() {
        sourceRig.close()
        targetRig.close()
    }

    @Test
    fun `encrypted psbackup roundtrips records versions and attachments via the 24-word Recovery Key with a full replace and no hidden safety archive`() = runBlocking {
        val init = sourceRig.initializeAndUnlockVault("MasterVaultPass!1")
        val recoveryPhrase = init.recoveryWords.joinToString(" ")

        val folder = sourceRig.repository.createFolder("Tax Archives", null, FolderAccentColor.RED)
        val rec = sourceRig.repository.saveRecord(
            existingRecordId = null,
            title = "FY 2025-26 ITR Acknowledgement",
            description = "Filed on time with digital signature.",
            folderId = folder.id,
            category = "Tax",
            customFields = listOf(CustomField("c1", "PAN", CustomFieldType.TEXT, "ABCDE1234F"))
        )
        val attachmentBytes = "ENCRYPTED_ITR_RECEIPT_BINARY_DATA_998877".encodeToByteArray()
        val att = sourceRig.repository.importAttachmentFromStream(
            recordId = rec.id,
            input = ByteArrayInputStream(attachmentBytes),
            fileName = "itr_ack_2026.pdf",
            mimeType = "application/pdf"
        )

        // Create encrypted .psbackup package
        val backupFile = File(sourceRig.rootDir, "vault_manual.psbackup")
        val created = sourceRig.backupManager.createFullBackupFile(
            backupKind = BackupKind.FULL_MANUAL,
            targetFile = backupFile
        )
        assertTrue(backupFile.exists() && created.sizeBytes > 0L)
        assertEquals(1, created.recordCount)
        assertEquals(1, created.attachmentCount)

        // 1. Wrong password during restore must be rejected BEFORE touching target vault
        try {
            targetRig.backupManager.restoreFromBackupFile(
                backupFile = backupFile,
                masterPassword = "WrongPassword!999".toCharArray()
            )
            fail("Expected CryptoAuthException when restoring .psbackup with wrong password")
        } catch (_: CryptoAuthException) {
            // Expected
        }

        // 2. Initialize targetRig with local data so restore proves that successful replacement does not keep a hidden full backup.
        targetRig.initializeAndUnlockVault("TemporaryLocalPass!2")
        targetRig.repository.saveRecord(
            existingRecordId = null,
            title = "Pre-existing Local Note",
            description = "This local note should be replaced by the imported backup",
            folderId = null,
            category = null,
            customFields = emptyList()
        )

        // Restore source backup into targetRig using the 24-word Recovery Key!
        targetRig.backupManager.restoreFromBackupFile(
            backupFile = backupFile,
            recoveryPhrase = recoveryPhrase
        )

        // No hidden full-size .psbackup safety archive is created. Failed/interrupted restores are
        // protected separately by the temporary RestoreJournal rollback snapshot.
        assertTrue(
            "Hidden full-size safety archive must not be generated",
            !targetRig.backupManager.safetyBackupFile.exists()
        )

        // Verify restored vault contents & decrypted attachment match source byte-for-byte
        val restoredRecords = targetRig.repository.observeAllRecords().first()
        assertEquals(1, restoredRecords.size)
        assertTrue(restoredRecords.none { it.title == "Pre-existing Local Note" })
        val mergedRecord = restoredRecords.first { it.title == "FY 2025-26 ITR Acknowledgement" }
        assertEquals("ABCDE1234F", mergedRecord.customFields.first().value)

        val restoredAttachments = targetRig.repository.observeAttachmentsForRecord(rec.id).first()
        assertEquals(1, restoredAttachments.size)
        val decryptedOut = ByteArrayOutputStream()
        targetRig.repository.exportAttachmentToStream(att.id, decryptedOut)
        assertArrayEquals(attachmentBytes, decryptedOut.toByteArray())
    }

    @Test
    fun `corrupted or truncated psbackup archive is rejected cleanly without overwriting local data`() = runBlocking {
        sourceRig.initializeAndUnlockVault("MasterVaultPass!1")
        sourceRig.repository.saveRecord(
            existingRecordId = null,
            title = "Original Safar Nama",
            description = "Ye note restore ke baad bhi intact rehna chahiye.",
            folderId = null,
            category = "General",
            customFields = emptyList()
        )
        val created = sourceRig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL)
        val corrupt = File(sourceRig.backupManager.localBackupsDir, "corrupt_copy.psbackup")
        corrupt.writeBytes(created.file.readBytes().copyOf((created.file.length() / 2).toInt()))

        try {
            sourceRig.backupManager.restoreFromBackupFile(
                backupFile = corrupt,
                masterPassword = "MasterVaultPass!1".toCharArray()
            )
            fail("Truncated archive must be rejected")
        } catch (expected: CorruptedBackupException) {
            assertEquals(1, sourceRig.repository.observeAllRecords().first().size)
            assertEquals("Original Safar Nama", sourceRig.repository.observeAllRecords().first().first().title)
        } finally {
            corrupt.delete()
        }
    }
}
