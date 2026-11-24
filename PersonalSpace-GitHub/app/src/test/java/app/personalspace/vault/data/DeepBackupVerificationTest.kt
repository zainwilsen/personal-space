package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.backup.DeepBackupVerifier
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.presentation.records.BlockAlignment
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.RichSpan
import app.personalspace.vault.presentation.records.RichSpanKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P8 — deep content verification.
 *
 * The point of these tests is that a *count* level check cannot see the damage they inject: the
 * verifier must catch a changed body, a lost bold span, a changed timestamp, a wrong attachment
 * hash, a missing draft, a broken folder relationship and a reordered list, and it must name the
 * exact entity and field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepBackupVerificationTest {

    private val ev = StringBuilder()
    private lateinit var rig: IsolatedVaultTestRig
    private fun note(line: String) { ev.append(line).append('\n'); println("P8| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    @Before
    fun setUp() {
        runBlocking {
            rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p8_deep")
            rig.initializeAndUnlockVault(PASSWORD)
        }
    }

    @After
    fun tearDown() { rig.close() }

    private fun verifier() = DeepBackupVerifier(
        db = rig.dbHolder.getDatabase(),
        attachmentStorage = object : DeepBackupVerifier.EncryptedAttachmentStorageView {
            override fun exists(relativePath: String) =
                rig.attachmentStorage.resolveEncryptedFile(relativePath).exists()
            /**
             * Hash the real decrypted bytes INDEPENDENTLY of the row's recorded hash. Passing the
             * row's own hash as `expectedSha256Hex` would make the storage layer vouch for the value
             * it is being asked to verify.
             */
            override fun plaintextSha256(relativePath: String): String? = runBlocking {
                val file = rig.attachmentStorage.resolveEncryptedFile(relativePath)
                if (!file.exists()) return@runBlocking null
                val key = rig.sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
                try {
                    val sink = java.io.ByteArrayOutputStream()
                    app.personalspace.vault.core.crypto.AttachmentChunkCipher.decryptStream(
                        attachmentMasterKey = key,
                        attachmentId = file.name.removeSuffix(".psenc"),
                        input = java.io.FileInputStream(file).buffered(),
                        output = sink
                    )
                    DeepBackupVerifier.sha256(sink.toByteArray())
                } catch (e: Exception) { null } finally { key.fill(0) }
            }
            override fun decryptedByteSize(relativePath: String): Long? = runBlocking {
                val row = rig.dbHolder.getDatabase().attachmentDao()
                    .getAllAttachmentsIncludingDeleted()
                    .firstOrNull { it.encryptedRelativePath == relativePath } ?: return@runBlocking null
                row.plaintextSizeBytes
            }
        },
        inlineImageStore = object : DeepBackupVerifier.InlineImageView {
            override fun exists(identifier: String) = rig.inlineImageStore.exists(identifier)
            override fun storedSha256(identifier: String): String? = runCatching {
                DeepBackupVerifier.sha256(rig.inlineImageStore.encryptedFile(identifier))
            }.getOrNull()
            override fun plaintextSha256(identifier: String): String? = runBlocking {
                rig.inlineImageStore.plaintextDigest(identifier)?.first
            }
        }
    )

    /** A rich vault: formatting, image, attachment, draft, deleted note, two folders. */
    private suspend fun buildRichVault(): Map<String, String> {
        val folderA = rig.repository.createFolder(
            "Alpha", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE
        )
        val folderB = rig.repository.createFolder(
            "Beta", null, app.personalspace.vault.core.designsystem.FolderAccentColor.RED
        )
        val img = rig.inlineImageStore.importFromStream(byteArrayOf(1, 2, 3, 4).inputStream(), "pic.png")
        val body = RichNoteCodec.encodeBlocks(
            listOf(
                RichNoteBlock.Text(
                    text = "Bold heading",
                    spans = listOf(RichSpan(0, 4, RichSpanKind.BOLD), RichSpan(5, 12, RichSpanKind.HIGHLIGHT, "#FFF176"))
                ),
                RichNoteBlock.Image(imagePath = img, widthFraction = 0.6f),
                RichNoteBlock.Text(text = "second", spans = listOf(RichSpan(0, 6, RichSpanKind.ITALIC)), alignment = BlockAlignment.CENTER)
            )
        )
        val kept = rig.repository.saveRecord(null, "Kept note", body, folderA.id, "work", emptyList())
        val att = rig.repository.importAttachmentFromStream(
            kept.id, "ATTACHMENT-BYTES".toByteArray().inputStream(), "proof.txt", "text/plain"
        )
        val doomed = rig.repository.saveRecord(null, "Doomed note", "plain body", folderB.id, null, emptyList())
        rig.repository.saveDraft(
            app.personalspace.vault.domain.model.RecordDraft(
                draftKey = "EDIT_${doomed.id}",
                folderId = folderB.id,
                title = "Doomed note (editing)",
                description = "unsaved words",
                category = null,
                customFields = emptyList(),
                updatedAt = rig.clock.nowMillis()
            )
        )
        rig.clock.advanceBy(1_000)
        rig.repository.softDeleteRecord(doomed.id)
        return mapOf(
            "kept" to kept.id, "doomed" to doomed.id, "image" to img,
            "attachment" to att.id, "folderA" to folderA.id, "folderB" to folderB.id
        )
    }

    private var manifestImages: List<app.personalspace.vault.data.backup.BackupInlineImageRef> = emptyList()

    private suspend fun backupSnapshotJson(): String {
        val pkg = rig.backupManager.createFullBackupFile(
            BackupKind.FULL_MANUAL,
            targetFile = File(rig.context.cacheDir, "p8.psbackup"),
            recordHistoryInDb = false
        ).file
        manifestImages = rig.backupManager.inspectAndValidatePackageStructure(pkg).inlineImages
        return rig.backupManager.readSnapshotJsonForVerification(pkg)
    }

    /** Verify against the live DB, using the package manifest for the declared inline images. */
    private suspend fun verifyNow(snapshotJson: String) =
        verifier().verify(snapshotJson, verifyMedia = true, inlineImages = manifestImages)

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `P8-V1 a healthy restore passes deep verification field by field`() = runBlocking {
        buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val report = verifyNow(snapshotJson)
        note("report = " + report.summary())
        note("checked = records ${report.checkedRecords} folders ${report.checkedFolders} " +
            "attachments ${report.checkedAttachments} drafts ${report.checkedDrafts} " +
            "relationships ${report.checkedRelationships}")
        assertTrue("a healthy vault must verify: " + report.summary(), report.passed)
        assertEquals("no mismatches", 0, report.mismatches.size)
        assertEquals("the notes must all be checked", 2, report.checkedRecords)
        assertEquals("both folders must be checked", 2, report.checkedFolders)
        assertEquals("the attachment must be checked", 1, report.checkedAttachments)
        assertTrue("relationships must be checked", report.checkedRelationships >= 3)
        dump("p8_V1_healthy_verification")
    }

    // ------------------------------------------------------------------ field-level detection

    @Test
    fun `P8-V2 a changed body is caught as a RECORD description mismatch`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        // tamper the live row behind the verifier's back (simulates a restore that wrote a wrong body)
        val db = rig.dbHolder.getDatabase()
        val row = db.recordDao().getRecordById(ids["kept"]!!)!!
        db.recordDao().upsert(row.copy(description = "replaced text"))
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse("the change must be detected", report.passed)
        val mismatch = report.mismatches.first()
        assertEquals("the exact entity must be named", "RECORD", mismatch.entity)
        assertEquals("with its id", ids["kept"], mismatch.entityId)
        assertEquals("and the exact field", "description", mismatch.field)
        dump("p8_V2_body_change_detected")
    }

    @Test
    fun `P8-V3 lost rich formatting is caught even though the visible text is identical`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val db = rig.dbHolder.getDatabase()
        val row = db.recordDao().getRecordById(ids["kept"]!!)!!
        // keep every character, drop the bold span -> a string comparison of the visible text passes
        val blocks = RichNoteCodec.decodeBlocks(row.description).map { block ->
            if (block is RichNoteBlock.Text) block.copy(spans = block.spans.filterNot { it.kind == RichSpanKind.BOLD })
            else block
        }
        val stripped = RichNoteCodec.encodeBlocks(blocks)
        note("visible text unchanged = ${RichNoteCodec.extractPlainText(stripped) == RichNoteCodec.extractPlainText(row.description)}")
        db.recordDao().upsert(row.copy(description = stripped))
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse("losing a bold span must be an ERROR, not silence", report.passed)
        assertTrue("the failure must name the formatting field",
            report.mismatches.any { it.field.contains("span", ignoreCase = true) })
        dump("p8_V3_formatting_loss_detected")
    }

    @Test
    fun `P8-V4 a changed timestamp and deletion metadata are caught`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val db = rig.dbHolder.getDatabase()
        val row = db.recordDao().getRecordById(ids["doomed"]!!)!!
        db.recordDao().upsert(row.copy(createdAt = row.createdAt + 5_000, deletedAt = null))
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse(report.passed)
        assertTrue("the createdAt change must be named",
            report.mismatches.any { it.entityId == ids["doomed"] && it.field == "createdAt" })
        assertTrue("the deletedAt change must be named",
            report.mismatches.any { it.entityId == ids["doomed"] && it.field == "deletedAt" })
        dump("p8_V4_timestamps_and_deletion_metadata")
    }

    @Test
    fun `P8-V5 a changed attachment plaintext hash is caught`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val db = rig.dbHolder.getDatabase()
        val att = db.attachmentDao().getAttachmentById(ids["attachment"]!!)!!
        // lie about the hash the backup recorded
        db.attachmentDao().upsert(att.copy(plaintextSha256Hex = "0".repeat(64)))
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse(report.passed)
        assertTrue("the attachment hash change must be named",
            report.mismatches.any {
                it.entity == "ATTACHMENT" && it.entityId == ids["attachment"] &&
                    (it.field == "plaintextSha256Hex" || it.field == "plaintextSha256")
            })
        dump("p8_V5_attachment_hash")
    }

    @Test
    fun `P8-V6 a missing attachment file is caught even though the row is present`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val db = rig.dbHolder.getDatabase()
        val att = db.attachmentDao().getAttachmentById(ids["attachment"]!!)!!
        rig.attachmentStorage.resolveEncryptedFile(att.encryptedRelativePath).delete()
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse(report.passed)
        assertTrue("the missing file must be reported against the attachment",
            report.mismatches.any { it.entity == "ATTACHMENT" && it.field == "file" })
        dump("p8_V6_missing_attachment_file")
    }

    @Test
    fun `P8-V7 a missing draft and a missing note are both caught`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val db = rig.dbHolder.getDatabase()
        db.recordDao().deleteDraft("EDIT_${ids["doomed"]!!}")
        db.recordDao().hardDeleteByIds(listOf(ids["kept"]!!))
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse(report.passed)
        assertTrue("the missing note must be named",
            report.mismatches.any { it.entity == "RECORD" && it.entityId == ids["kept"] && it.field == "presence" })
        assertTrue("the missing draft must be named",
            report.mismatches.any { it.entity == "DRAFT" && it.field == "presence" })
        dump("p8_V7_missing_note_and_draft")
    }

    @Test
    fun `P8-V8 a broken folder relationship is caught`() = runBlocking {
        val ids = buildRichVault()
        val snapshotJson = backupSnapshotJson()
        val db = rig.dbHolder.getDatabase()
        // the FK prevents writing a dangling folderId, so delete the folder row instead: this is what a
        // partial restore actually looks like (note restored, its folder not)
        db.folderDao().hardDeleteByIds(listOf(ids["folderA"]!!))
        val report = verifyNow(snapshotJson)
        note("mismatches = " + report.mismatches.map { it.describe() })
        assertFalse(report.passed)
        assertTrue("the missing folder must be named as a FOLDER problem",
            report.mismatches.any { it.entity == "FOLDER" && it.entityId == ids["folderA"] && it.field == "presence" })
        // the FK is ON DELETE SET NULL, so the note keeps pointing nowhere: that must be visible too
        assertTrue("the note that lost its folder must be named",
            report.mismatches.any {
                it.entity == "RECORD" && it.entityId == ids["kept"] && it.field == "folderId"
            })
        assertTrue("and the folder ordering must be re-checked",
            report.mismatches.any { it.entity == "ORDERING" && it.field == "folderOrder" })
        dump("p8_V8_broken_relationship")
    }

    @Test
    fun `P8-V9 verification is used by the real restore and reported in the result`() = runBlocking {
        buildRichVault()
        val pkg = rig.backupManager.createFullBackupFile(
            BackupKind.FULL_MANUAL,
            targetFile = File(rig.context.cacheDir, "p8_restore.psbackup"),
            recordHistoryInDb = false
        ).file
        val result = rig.backupManager.restoreFromBackupFile(pkg, PASSWORD.toCharArray())
        note("restore verified = ${result.verified}")
        note("summary = ${result.verificationSummary}")
        assertTrue("the restore must report success", result.verified)
        assertTrue("the summary must now include the content verification",
            result.verificationSummary.contains("content:", ignoreCase = true))
        assertTrue("and it must mention what was verified",
            result.verificationSummary.contains("notes", ignoreCase = true))
        note("=> counts alone are no longer the acceptance criterion")
        dump("p8_V9_restore_uses_verifier")
    }

    private companion object { const val PASSWORD = "P8Deep#2026" }
}
