package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * P5 BASELINE PROBE — measures the CURRENT (pre-P5) deleted-content behaviour.
 *
 * It asserts NOTHING about correctness; every line is a measurement written to
 * /home/user/verification/evidence/p5_baseline_*.txt, so the P5 work is driven by observed
 * behaviour instead of assumptions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletedContentBaselineProbeTest {

    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("P5BASE| $s") }
    private fun dump(t: String) {
        File("/home/user/verification/evidence/$t.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    private fun sha256(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256"); md.update(b)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withImage(id: String, text: String = "body") =
        RichNoteCodec.encodeBlocks(
            listOf(RichNoteBlock.Text(text = text), RichNoteBlock.Image(imagePath = id))
        )

    @Test
    fun `baseline — what survives a soft delete, a backup, and a restore`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p5_base")
        try {
            rig.initializeAndUnlockVault("P5Pass#2026")
            val db = rig.dbHolder.getDatabase()

            // ---------- a note with an inline image and an attachment ----------
            val img = rig.inlineImageStore.importFromStream(byteArrayOf(1, 2, 3, 4).inputStream(), "a.png")
            val rec = rig.repository.saveRecord(null, "Doomed", withImage(img, "important"), null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "SECRET-BYTES".toByteArray().inputStream(), "report.pdf", "application/pdf"
            )
            val plainBefore = ByteArrayOutputStream().also { rig.repository.exportAttachmentToStream(att.id, it) }.toByteArray()
            note("seeded: record=${rec.id} image=$img attachment=${att.id} plaintext=${sha256(plainBefore)}")

            val backupBeforeDelete = rig.backupManager
                .createFullBackupFile(BackupKind.FULL_MANUAL, targetFile = File(rig.context.cacheDir, "b_before.psbackup"), recordHistoryInDb = false).file
            note("backup BEFORE delete: ${backupBeforeDelete.length()} B")

            // ---------- soft delete the note ----------
            rig.clock.advanceBy(60_000)
            rig.repository.softDeleteRecord(rec.id)
            val deletedRow = db.recordDao().getRecordById(rec.id)!!
            note("after softDeleteRecord: isDeleted=${deletedRow.isDeleted} deletedAt=${deletedRow.deletedAt} batch=${deletedRow.deletedBatchId}")
            note("inline image still on disk after soft delete = ${rig.inlineImageStore.exists(img)}")
            val attRowAfter = db.attachmentDao().getAttachmentById(att.id)
            note("attachment row after note soft delete = ${attRowAfter?.let { "isDeleted=${it.isDeleted} deletedAt=${it.deletedAt}" } ?: "MISSING"}")
            val fileAfter = rig.attachmentStorage.resolveEncryptedFile(attRowAfter?.encryptedRelativePath ?: "")
            note("attachment file still on disk = ${fileAfter.exists()}")

            // ---------- backup WITH the deleted note ----------
            val backup = rig.backupManager
                .createFullBackupFile(BackupKind.FULL_MANUAL, targetFile = File(rig.context.cacheDir, "b_with_deleted.psbackup"), recordHistoryInDb = false).file
            val manifest = rig.backupManager.inspectAndValidatePackageStructure(backup)
            note("backup WITH deleted note: records=${manifest.recordCount} attachments=${manifest.attachmentCount} inlineImages=${manifest.inlineImages.size}")
            note("inline image identifiers in package = ${manifest.inlineImages.map { it.identifier }}")
            note("attachment refs in package = ${manifest.attachments.map { it.attachmentId }}")

            // ---------- restore that backup ----------
            val result = rig.backupManager.restoreFromBackupFile(backup, "P5Pass#2026".toCharArray())
            note("restore result = $result")
            val restored = db.recordDao().getRecordById(rec.id)
            note("after restore: record present=${restored != null} isDeleted=${restored?.isDeleted} deletedAt=${restored?.deletedAt} batch=${restored?.deletedBatchId}")
            note("after restore: recycle bin batches = " +
                db.recycleBinDao().getAllDeletedItems().map { it.batchId to it.displayTitle })
            val attRestored = db.attachmentDao().getAttachmentById(att.id)
            note("after restore: attachment row = ${attRestored?.let { "isDeleted=${it.isDeleted} deletedAt=${it.deletedAt} path=${it.encryptedRelativePath}" } ?: "MISSING"}")
            note("after restore: attachment decryptable = " + runCatching {
                val out = ByteArrayOutputStream()
                rig.repository.exportAttachmentToStream(att.id, out)
                sha256(out.toByteArray()) == sha256(plainBefore)
            }.getOrElse { "ERROR: ${it.javaClass.simpleName}" })
            note("after restore: inline image on disk = ${rig.inlineImageStore.exists(img)}")

            // ---------- what a restore-back-to-life would need ----------
            val batchId = restored?.deletedBatchId
            note("can the deleted note still be restored from the recycle bin? batchId=$batchId")
            if (batchId != null) {
                runCatching { rig.repository.restoreDeletedBatch(batchId) }
                    .onSuccess {
                        val back = db.recordDao().getRecordById(rec.id)
                        note("  restoreDeletedBatch OK: isDeleted=${back?.isDeleted}")
                    }
                    .onFailure { note("  restoreDeletedBatch FAILED: ${it.javaClass.simpleName}: ${it.message}") }
            }
            dump("p5_baseline_deleted_content")
        } finally { rig.close() }
    }

    @Test
    fun `baseline — what deleteAttachment does to the bytes and the rows`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p5_base2")
        try {
            rig.initializeAndUnlockVault("P5Pass#2026")
            val db = rig.dbHolder.getDatabase()
            val rec = rig.repository.saveRecord(null, "Owner", "b", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "PAYLOAD".toByteArray().inputStream(), "keep.txt", "text/plain"
            )
            val encFile = rig.attachmentStorage.resolveEncryptedFile(
                db.attachmentDao().getAttachmentById(att.id)!!.encryptedRelativePath
            )
            note("before deleteAttachment: row=${db.attachmentDao().getAttachmentById(att.id) != null} file=${encFile.exists()} size=${encFile.length()}")

            rig.repository.deleteAttachment(att.id)

            note("after  deleteAttachment: row=${db.attachmentDao().getAttachmentById(att.id) != null} (incl. deleted=${db.attachmentDao().getAllAttachmentsIncludingDeleted().map { it.id }})")
            note("after  deleteAttachment: encrypted file exists=${encFile.exists()}")
            note("=> deleteAttachment is a HARD delete: row removed from the attachments table AND the ciphertext deleted")
            dump("p5_baseline_delete_attachment")
        } finally { rig.close() }
    }
}
