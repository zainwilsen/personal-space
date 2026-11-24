package app.personalspace.vault.data

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.CryptoAuthException
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.data.backup.BackupPackageManifest
import app.personalspace.vault.data.backup.CorruptedBackupException
import app.personalspace.vault.data.backup.MissingBackupAttachmentException
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.CustomFieldType
import app.personalspace.vault.domain.model.RecordDraft
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullBackupRestoreTest {

    private lateinit var source: IsolatedVaultTestRig
    private lateinit var target: IsolatedVaultTestRig

    private val masterPassword = "MasterVaultPass!1"

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        source = IsolatedVaultTestRig(appContext, "full_source")
        target = IsolatedVaultTestRig(appContext, "full_target")
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private suspend fun note(
        rig: IsolatedVaultTestRig,
        title: String,
        description: String = "desc",
        folderId: String? = null,
        customFields: List<CustomField> = emptyList()
    ) = rig.repository.saveRecord(
        existingRecordId = null,
        title = title,
        description = description,
        folderId = folderId,
        category = "General",
        customFields = customFields
    )

    private fun backup(rig: IsolatedVaultTestRig, name: String = "test.psbackup"): File =
        runBlocking {
            val file = File(rig.rootDir, name)
            rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, file)
            file
        }

    // 1. Zero notes
    @Test
    fun `backup and full restore work with zero notes`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val file = backup(source)
        assertTrue(file.exists())

        target.initializeAndUnlockVault("TargetPass!2")
        note(target, "Local only note")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(0, result.notes)
        assertEquals(0, target.repository.observeAllRecords().first().size)
        assertTrue(result.verified)
    }

    // 2. One note
    @Test
    fun `single note survives full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Ek hi note", "body text")
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(1, result.notes)
        val restored = target.repository.observeAllRecords().first()
        assertEquals(1, restored.size)
        assertEquals("Ek hi note", restored.first().title)
        assertEquals("body text", restored.first().description)
    }

    // 3. 1000 notes
    @Test
    fun `one thousand notes survive full restore with exact count`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val db = source.dbHolder.getDatabase()
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (i in 1..1000) {
                val id = "bulk-" + i
                db.recordDao().upsertAll(
                    listOf(
                        app.personalspace.vault.data.local.entity.RecordEntity(
                            id = id,
                            title = "Note " + i,
                            description = "Description " + i,
                            folderId = null,
                            category = "Bulk",
                            customFieldsJson = "[]",
                            createdAt = now + i,
                            modifiedAt = now + i,
                            currentVersionNumber = 1,
                            currentVersionId = "ver-" + i,
                            syncPending = false
                        )
                    )
                )
                // P8 found this: rows were inserted with a currentVersionId that pointed at a version
                // row that never existed. That is a dangling reference in the fixture (the app always
                // writes a version with a save), and the deep verifier correctly refuses to call such
                // a restore verified. Create the version rows so the fixture is a realistic vault.
                db.recordDao().insertVersions(
                    listOf(
                        app.personalspace.vault.data.local.entity.RecordVersionEntity(
                            id = "ver-" + i,
                            recordId = id,
                            versionNumber = 1,
                            title = "Note " + i,
                            description = "Description " + i,
                            category = "Bulk",
                            customFieldsJson = "[]",
                            createdAt = now + i,
                            createdByDeviceId = "test-device",
                            changeSummary = "seed"
                        )
                    )
                )
            }
        }
        assertEquals(1000, source.repository.observeAllRecords().first().size)
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(1000, result.notes)
        assertEquals(1000, target.repository.observeAllRecords().first().size)
        assertEquals("Note 777", target.repository.getRecordById("bulk-777")!!.title)
    }

    // 4. Descriptions + custom fields
    @Test
    fun `descriptions and custom fields survive full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(
            source,
            "Custom field note",
            "Lamaba description with newlines",
            customFields = listOf(
                CustomField("c1", "PAN", CustomFieldType.TEXT, "ABCDE1234F"),
                CustomField("c2", "Amount", CustomFieldType.NUMBER, "4500")
            )
        ).id
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        target.backupManager.restoreFromBackupFile(file)

        val restored = target.repository.observeAllRecords().first().single()
        assertEquals("Lamaba description with newlines", restored.description)
        assertEquals(2, restored.customFields.size)
        assertEquals("ABCDE1234F", restored.customFields.first { it.label == "PAN" }.value)
    }

    // 5. Attachments
    @Test
    fun `attachment bytes survive full restore across installations`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val rec = note(source, "Note with attachment")
        val bytes = "ATTACHMENT_PAYLOAD_998877".encodeToByteArray()
        val att = source.repository.importAttachmentFromStream(
            rec.id, ByteArrayInputStream(bytes), "proof.pdf", "application/pdf"
        )
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(1, result.attachments)
        val restoredAttachments = target.repository.observeAttachmentsForRecord(rec.id).first()
        assertEquals(1, restoredAttachments.size)
        val out = ByteArrayOutputStream()
        target.repository.exportAttachmentToStream(restoredAttachments.first().id, out)
        assertArrayEquals(bytes, out.toByteArray())
    }

    // 6. Version history
    @Test
    fun `note version history survives full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val rec = note(source, "Versioned note", "v1 body")
        source.repository.saveRecord(
            existingRecordId = rec.id,
            title = "Versioned note",
            description = "v2 body",
            folderId = null,
            category = "General",
            customFields = emptyList()
        )
        val sourceVersions = source.repository.observeRecordVersions(rec.id).first()
        assertTrue(sourceVersions.isNotEmpty())
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(sourceVersions.size, result.versions)
        val restoredVersions = target.repository.observeRecordVersions(rec.id).first()
        assertEquals(sourceVersions.size, restoredVersions.size)
    }

    // 7. Folders and relationships
    @Test
    fun `folders subfolders and note placement survive full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val parent = source.repository.createFolder("Parent", null, FolderAccentColor.RED)
        val child = source.repository.createFolder("Child", parent.id, FolderAccentColor.BLUE)
        note(source, "Child note", folderId = child.id)
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(2, result.folders)
        val folders = target.repository.observeAllFolders().first()
        assertEquals(2, folders.size)
        val restoredChild = folders.first { it.name == "Child" }
        assertEquals(parent.id, restoredChild.parentFolderId)
        val restoredNote = target.repository.observeAllRecords().first().single()
        assertEquals(restoredChild.id, restoredNote.folderId)
        assertEquals("Child", target.repository.getFolderById(restoredNote.folderId!!)!!.name)
    }

    // 8. Drafts
    @Test
    fun `saved drafts survive full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        source.repository.saveDraft(
            RecordDraft(
                draftKey = "draft-1",
                folderId = null,
                title = "Unfinished note",
                description = "typed but not saved",
                category = "General",
                customFields = emptyList(),
                updatedAt = System.currentTimeMillis()
            )
        )
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertEquals(1, result.drafts)
        val restoredDraft = target.repository.getDraft("draft-1")
        assertEquals("Unfinished note", restoredDraft!!.title)
        assertEquals("typed but not saved", restoredDraft.description)
    }

    // 9. Recycle bin
    @Test
    fun `recycle bin contents survive full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val rec = note(source, "Deleted note")
        source.repository.softDeleteRecord(rec.id)
        val sourceBin = source.repository.observeDeletedItems().first()
        assertTrue(sourceBin.isNotEmpty())
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        target.backupManager.restoreFromBackupFile(file)

        val restoredBin = target.repository.observeDeletedItems().first()
        assertEquals(sourceBin.size, restoredBin.size)
        assertEquals(0, target.repository.observeAllRecords().first().size)
    }

    // 10. Full restore removes notes absent from the backup
    @Test
    fun `full restore deletes notes that are not part of the backup`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Backup note")
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        note(target, "Stale note A")
        note(target, "Stale note B")
        assertEquals(2, target.repository.observeAllRecords().first().size)

        target.backupManager.restoreFromBackupFile(file)

        val restored = target.repository.observeAllRecords().first()
        assertEquals(1, restored.size)
        assertEquals("Backup note", restored.single().title)
    }

    // 11. No stale attachments after restore
    @Test
    fun `restore removes attachment files that do not exist in the backup`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val rec = note(source, "Backup note")
        source.repository.importAttachmentFromStream(
            rec.id, ByteArrayInputStream("keep".encodeToByteArray()), "keep.txt", "text/plain"
        )
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val staleNote = note(target, "Stale note")
        target.repository.importAttachmentFromStream(
            staleNote.id, ByteArrayInputStream("stale".encodeToByteArray()), "stale.txt", "text/plain"
        )
        assertEquals(1, target.repository.observeAllRecords().first().size)

        target.backupManager.restoreFromBackupFile(file)

        assertEquals(1, target.repository.observeAllRecords().first().size)
        assertEquals(0, target.repository.observeAttachmentsForRecord(staleNote.id).first().size)
        val restoredAttachments = target.repository.observeAttachmentsForRecord(rec.id).first()
        assertEquals(1, restoredAttachments.size)
    }

    // 12. Wrong password
    @Test
    fun `wrong password is rejected and existing vault is untouched`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Source note")
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        note(target, "Target keeps this")
        try {
            target.backupManager.restoreFromBackupFile(
                backupFile = file,
                masterPassword = "WrongPassword!999".toCharArray()
            )
            fail("Expected CryptoAuthException")
        } catch (_: CryptoAuthException) {
        }
        assertEquals("Target keeps this", target.repository.observeAllRecords().first().single().title)
    }

    // 13. Corrupted file
    @Test
    fun `corrupted backup is rejected and existing vault is untouched`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Source note")
        val good = backup(source)
        val corrupt = File(source.rootDir, "corrupt.psbackup")
        corrupt.writeBytes(good.readBytes().copyOf((good.length() / 2).toInt()))

        target.initializeAndUnlockVault("TargetPass!2")
        note(target, "Target keeps this")
        try {
            target.backupManager.restoreFromBackupFile(corrupt)
            fail("Expected CorruptedBackupException")
        } catch (_: CorruptedBackupException) {
        }
        assertEquals("Target keeps this", target.repository.observeAllRecords().first().single().title)
    }

    // 14. Missing attachment file makes the backup fail (no silent skip)
    @Test
    fun `backup fails loudly when an attachment file is missing on disk`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val rec = note(source, "Note with attachment")
        val att = source.repository.importAttachmentFromStream(
            rec.id, ByteArrayInputStream("bytes".encodeToByteArray()), "lost.txt", "text/plain"
        )
        source.attachmentStorage.deleteEncryptedFile(att.encryptedRelativePath)
        try {
            source.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL, File(source.rootDir, "broken.psbackup")
            )
            fail("Expected MissingBackupAttachmentException")
        } catch (e: MissingBackupAttachmentException) {
            assertEquals(1, e.missingFiles.size)
        }
    }

    // 15. A restore that fails mid-flight preserves the previous vault
    @Test
    fun `failed restore preserves the previous vault data and attachment files`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Source note")
        val good = backup(source)
        val tampered = File(source.rootDir, "tampered.psbackup")
        copyZipReplacingEntry(good, tampered, "vault_snapshot.enc", "corrupted-payload".encodeToByteArray())

        target.initializeAndUnlockVault("TargetPass!2")
        val keepNote = note(target, "Target keeps this")
        target.repository.importAttachmentFromStream(
            keepNote.id, ByteArrayInputStream("target bytes".encodeToByteArray()), "t.txt", "text/plain"
        )

        try {
            target.backupManager.restoreFromBackupFile(tampered)
            fail("Expected restore failure")
        } catch (_: Exception) {
        }

        val records = target.repository.observeAllRecords().first()
        assertEquals(1, records.size)
        assertEquals("Target keeps this", records.single().title)
        val attachments = target.repository.observeAttachmentsForRecord(keepNote.id).first()
        assertEquals(1, attachments.size)
        val out = ByteArrayOutputStream()
        target.repository.exportAttachmentToStream(attachments.first().id, out)
        assertArrayEquals("target bytes".encodeToByteArray(), out.toByteArray())
    }

    // 16. Restore on a different installation without any password prompt
    @Test
    fun `backup created on one installation restores on another with no extra login step`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        val rec = note(source, "Cross install note", "body")
        source.repository.importAttachmentFromStream(
            rec.id, ByteArrayInputStream("cross".encodeToByteArray()), "c.txt", "text/plain"
        )
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(file)

        assertTrue(result.verified)
        assertEquals(1, result.notes)
        assertEquals("Cross install note", target.repository.observeAllRecords().first().single().title)
        val out = ByteArrayOutputStream()
        target.repository.exportAttachmentToStream(
            target.repository.observeAttachmentsForRecord(rec.id).first().first().id, out
        )
        assertArrayEquals("cross".encodeToByteArray(), out.toByteArray())
    }

    // 18. Backup is published to Android/data so it can be pulled with adb / file manager
    @Test
    fun `backup is also published to Android data folder for manual copy`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Visible backup note")
        source.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL)

        val internal = source.backupManager.singleLocalBackupFile
        val external = source.backupManager.externalLocalBackupFile
        assertTrue(internal.exists())
        assertTrue(external.exists())
        assertTrue(external.absolutePath.contains("Android/data") || external.absolutePath.contains("files"))
        assertArrayEquals(internal.readBytes(), external.readBytes())
        assertEquals(1, internal.parentFile!!.listFiles()!!.count { it.name.endsWith(".psbackup") })
    }

    // 19. Restore works from the Android/data copy alone
    @Test
    fun `restore falls back to the android data copy when internal copy is missing`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Fallback note")
        source.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL)
        val external = source.backupManager.externalLocalBackupFile
        assertTrue(external.exists())

        target.initializeAndUnlockVault("TargetPass!2")
        target.backupManager.restoreFromBackupFile(external)

        assertEquals("Fallback note", target.repository.observeAllRecords().first().single().title)
    }

    // 20. Reminders stay on the device and are not part of the backup
    @Test
    fun `reminders are not backed up and survive a full restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Backed up note")
        val file = backup(source)

        target.initializeAndUnlockVault("TargetPass!2")
        val targetNote = note(target, "Note for reminder")
        target.repository.saveReminder(
            existingReminderId = null,
            title = "Device only reminder",
            notes = "",
            scheduledAtMillis = System.currentTimeMillis() + 600_000L,
            recurrence = app.personalspace.vault.domain.model.RecurrenceType.ONCE,
            priority = app.personalspace.vault.domain.model.ReminderPriority.MEDIUM,
            linkedRecordId = targetNote.id
        )

        target.backupManager.restoreFromBackupFile(file)

        val reminders = target.repository.observeAllReminders().first()
        assertEquals(1, reminders.size)
        assertEquals("Device only reminder", reminders.single().title)
    }

    // 21. Backup is skipped when nothing changed since the last one
    @Test
    fun `vault fingerprint changes only when data changes`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Fingerprint note")
        source.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL)

        val storedFingerprint = source.backupManager.lastBackupFingerprint()
        assertTrue(storedFingerprint != null)
        assertEquals(storedFingerprint, source.backupManager.computeVaultFingerprint())

        note(source, "One more note")
        assertTrue(storedFingerprint != source.backupManager.computeVaultFingerprint())
    }

    // 22. Backward compatibility: packages without an inline image section still restore
    @Test
    fun `backups created before inline image support still restore`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Pre-image backup note")
        val modernFile = backup(source)

        // Strip the inline image fields to mimic a v1.2.3 package produced before this change
        val stripped = File(source.rootDir, "pre_image_support.psbackup")
        stripInlineImageManifestFields(modernFile, stripped)

        target.initializeAndUnlockVault("TargetPass!3")
        val result = target.backupManager.restoreFromBackupFile(
            backupFile = stripped
        )

        assertTrue(result.verified)
        assertEquals(1, result.notes)
        assertEquals(0, result.inlineImages)
        assertEquals(
            "Pre-image backup note",
            target.repository.observeAllRecords().first().single().title
        )
    }

    private fun stripInlineImageManifestFields(sourceFile: File, targetFile: File) {
        val manifestEntry = "manifest.json"
        java.util.zip.ZipFile(sourceFile).use { zip ->
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(targetFile)).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val payload = if (entry.name == manifestEntry) {
                        val text = String(bytes, Charsets.UTF_8)
                        text.replace(Regex("\"inlineImages\":\\[.*?\\],"), "")
                            .replace(Regex("\"inlineImageCount\":\\d+,"), "")
                            .toByteArray(Charsets.UTF_8)
                    } else {
                        bytes
                    }
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    out.write(payload)
                    out.closeEntry()
                }
            }
        }
    }

    // 17. V1 packages still restore
    @Test
    fun `legacy v1 backup without portable key wrap still restores`() = runBlocking {
        source.initializeAndUnlockVault(masterPassword)
        note(source, "Legacy note")
        val modern = backup(source)
        val legacy = File(source.rootDir, "legacy_v1.psbackup")
        downgradeManifestToV1(source, modern, legacy)

        target.initializeAndUnlockVault("TargetPass!2")
        val result = target.backupManager.restoreFromBackupFile(
            backupFile = legacy,
            masterPassword = masterPassword.toCharArray()
        )

        assertEquals(1, result.sourceFormatVersion)
        assertEquals(1, result.notes)
        assertEquals("Legacy note", target.repository.observeAllRecords().first().single().title)
    }

    private fun copyZipReplacingEntry(source: File, dest: File, entryName: String, replacement: ByteArray) {
        ZipFile(source).use { zip ->
            ZipOutputStream(dest.outputStream()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == entryName) out.write(replacement)
                    else zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    private fun downgradeManifestToV1(rig: IsolatedVaultTestRig, source: File, dest: File) {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val key = rig.sessionManager.requireSubKeys().backupManifestKey
        ZipFile(source).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
            val manifest = json.decodeFromString<BackupPackageManifest>(
                zip.getInputStream(manifestEntry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            )
            val unsigned = manifest.copy(
                formatVersion = 1,
                portableKeyWrap = null,
                manifestHmacBase64 = ""
            )
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val hmac = VaultCryptoEngine.toBase64(
                mac.doFinal(json.encodeToString(BackupPackageManifest.serializer(), unsigned).toByteArray(StandardCharsets.UTF_8))
            )
            val signed = json.encodeToString(BackupPackageManifest.serializer(), unsigned.copy(manifestHmacBase64 = hmac))
                .toByteArray(StandardCharsets.UTF_8)
            ZipOutputStream(dest.outputStream()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") out.write(signed)
                    else zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}
