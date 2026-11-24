package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.presentation.records.BlockAlignment
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.RichSpan
import app.personalspace.vault.presentation.records.RichSpanKind
import app.personalspace.vault.presentation.records.applyParagraphAlignment
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleBackupDumpTest {

    @Test
    fun `dump sample psbackup structure for the audit document`() = runBlocking {
        var descSource = ""
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "audit_dump")
        try {
            rig.initializeAndUnlockVault("AuditPass#2026")
            val folder = rig.repository.createFolder("Health", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE)
            val text = "Blood report\nSugar level 98"
            val spans = applyParagraphAlignment(text, listOf(RichSpan(0, 12, RichSpanKind.BOLD)), 0, BlockAlignment.CENTER)
            val imageId = rig.inlineImageStore.importFromStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).inputStream(), "scan.png")
            val blocks = listOf(
                RichNoteBlock.Text(text = text, spans = spans),
                RichNoteBlock.Image(imagePath = imageId, widthFraction = 0.75f)
            )
            val record = rig.repository.saveRecord(
                existingRecordId = null,
                title = "Blood Test",
                description = RichNoteCodec.encodeBlocks(blocks),
                folderId = folder.id,
                category = "Medical",
                customFields = emptyList()
            )
            rig.repository.importAttachmentFromStream(
                recordId = record.id,
                input = "PDF-BYTES".toByteArray().inputStream(),
                fileName = "report.pdf",
                mimeType = "application/pdf"
            )
            rig.repository.saveDraft(
                app.personalspace.vault.domain.model.RecordDraft(
                    draftKey = "EDIT_" + record.id,
                    folderId = folder.id,
                    title = "Blood Test (draft)",
                    description = "unsaved edit",
                    category = "Medical",
                    customFields = emptyList(),
                    updatedAt = 1_700_000_000_000L
                )
            )

            val out = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val target = File("/home/user/audit_sample.psbackup")
            out.file.copyTo(target, overwrite = true)

            val lines = StringBuilder()
            val descHolder = arrayOf("")
            descSource = ""
            val manRef2 = arrayOfNulls<String>(1)
            ZipFile(target).use { zip ->
                zip.entries().asSequence().forEach { e ->
                    lines.append("ENTRY\t").append(e.name).append("\t").append(e.size).append("\n")
                }
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().toString(Charsets.UTF_8)
                lines.append("MANIFEST\t").append(manifest).append("\n")
                val snapshot = app.personalspace.vault.core.crypto.VaultCryptoEngine.decryptAesGcm(
                    key = rig.sessionManager.requireSubKeys().backupPayloadKey,
                    iv = app.personalspace.vault.core.crypto.VaultCryptoEngine.fromBase64(
                        Regex("\"snapshotIvBase64\":\"([^\"]+)\"").find(manifest)!!.groupValues[1]
                    ),
                    ciphertext = zip.getInputStream(zip.getEntry("vault_snapshot.enc")).readBytes(),
                    aad = ("PersonalSpace/v1/backup/" + Regex("\"backupId\":\"([^\"]+)\"").find(manifest)!!.groupValues[1] +
                        "/" + rig.sessionManager.requireSubKeys().vaultId).toByteArray()
                )
                val snapshotText = String(snapshot, Charsets.UTF_8)
                descSource = snapshotText
                lines.append("SNAPSHOT\t").append(snapshotText).append("\n")
                descHolder[0] = kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                }.parseToJsonElement(snapshotText).jsonObject["records"]!!.jsonArray[0]
                    .jsonObject["description"]!!.jsonPrimitive.content
                manRef2[0] = Regex("note_img_[0-9a-f-]+").find(descHolder[0])!!.value
            }
            // FTS behaviour probe (audit only)
            val ftsProbe = runCatching {
                rig.dbHolder.getDatabase().recordDao().searchRecordsFts("Blood")
            }
            lines.append("FTS_PROBE\t").append(
                ftsProbe.fold(
                    onSuccess = { "OK size=" + it.size + " titles=" + it.map { r -> r.title } },
                    onFailure = { "FAIL " + it.javaClass.simpleName + ": " + it.message }
                )
            ).append("\n")
            val ftsProbe2 = runCatching {
                rig.dbHolder.getDatabase().openHelper.writableDatabase.query("SELECT count(*) FROM records_fts").use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
            lines.append("FTS_COUNT\t").append(ftsProbe2.toString()).append("\n")
            // Restore-then-search probe (audit only)
            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "audit_dump_b")
            try {
                rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, "AuditPass#2026")
                rigB.backupManager.restoreFromBackupFile(backupFile = target, requireSafetyBackup = true)
                val afterRestore = runCatching {
                    rigB.dbHolder.getDatabase().recordDao().searchRecordsFts("Blood")
                }
                lines.append("FTS_AFTER_RESTORE\t").append(
                    afterRestore.fold(
                        onSuccess = { "OK size=" + it.size + " titles=" + it.map { r -> r.title } },
                        onFailure = { "FAIL " + it.javaClass.simpleName + ": " + it.message }
                    )
                ).append("\n")
                val rec = rigB.repository.observeAllRecords().first().first()
                val blocks = app.personalspace.vault.presentation.records.RichNoteCodec.decodeBlocks(rec.description)
                lines.append("RESTORED_ID\t").append(rec.id).append("\n")
                lines.append("RESTORED_CREATED\t").append(rec.createdAt).append("\t").append(rec.modifiedAt).append("\n")
                lines.append("RESTORED_VERSION_ID\t").append(rec.currentVersionId).append("\n")
                lines.append("RESTORED_BLOCKS\t").append(blocks.size).append("\t").append(
                    blocks.joinToString(" ;; ") { b -> b.javaClass.simpleName + ":" + b.id }
                ).append("\n")
                lines.append("DESC_BYTE_IDENTICAL\t").append(descHolder[0] == rec.description).append("\n")
                lines.append("DESC_LEN_SNAPSHOT\t").append(descHolder[0].length).append("\tRESTORED\t").append(rec.description.length).append("\n")
                val att = rigB.dbHolder.getDatabase().attachmentDao()
                lines.append("RESTORED_ATTACH\t").append(
                    kotlinx.coroutines.runBlocking { att.getAttachmentsForRecord(rec.id) }
                        .joinToString(",") { it -> it.originalFileName + "@" + it.plaintextSha256Hex.take(8) }
                ).append("\n")
                val dbB = rigB.dbHolder.getDatabase()
                lines.append("RESTORED_COUNTS\tdrafts=").append(dbB.recordDao().getAllDrafts().size)
                    .append("\tversions=").append(dbB.recordDao().getAllVersions().size)
                    .append("\ttimeline=").append(dbB.timelineDao().getAllEvents().size)
                    .append("\tdeletedBatches=").append(dbB.recycleBinDao().getAllDeletedItems().size)
                    .append("\n")
                val imgRead = runCatching {
                    val f = rigB.inlineImageStore.resolveForDisplay(manRef2[0]!!)!!
                    f.readBytes().size
                }
                lines.append("RESTORED_IMAGE_BYTES\t").append(imgRead.toString()).append("\n")
                lines.append("RESTORED_DRAFT_KEYS\t").append(
                    dbB.recordDao().getAllDrafts().joinToString(",") { it.draftKey }
                ).append("\n")
                val snapRec = kotlinx.serialization.json.Json.parseToJsonElement(descSource).jsonObject["records"]!!.jsonArray[0].jsonObject
                val snapFolder = kotlinx.serialization.json.Json.parseToJsonElement(descSource).jsonObject["folders"]!!.jsonArray[0].jsonObject
                val snapAtt = kotlinx.serialization.json.Json.parseToJsonElement(descSource).jsonObject["attachments"]!!.jsonArray[0].jsonObject
                lines.append("ID_EQUALITY\trecordId=").append(snapRec["id"]!!.jsonPrimitive.content == rec.id)
                    .append("\tversionId=").append(snapRec["currentVersionId"]!!.jsonPrimitive.content == rec.currentVersionId)
                    .append("\tcreatedAt=").append(snapRec["createdAt"]!!.jsonPrimitive.content.toLong() == rec.createdAt)
                    .append("\tfolderId=").append(snapFolder["id"]!!.jsonPrimitive.content == dbB.folderDao().getAllFoldersIncludingDeleted().first().id)
                    .append("\tattachId=").append(snapAtt["id"]!!.jsonPrimitive.content == dbB.attachmentDao().getAllAttachmentsIncludingDeleted().first().id)
                    .append("\n")
                lines.append("RESTORED_FOLDERS\t").append(
                    rigB.dbHolder.getDatabase().folderDao().observeActiveFolders().first()
                        .joinToString(",") { f -> f.name + ":" + f.sortOrder }
                ).append("\n")
            } finally {
                rigB.close()
            }
            File("/home/user/audit_sample_dump.txt").writeText(lines.toString())
            println("DUMP_WRITTEN " + target.length())
        } finally {
            rig.close()
        }
    }
}
