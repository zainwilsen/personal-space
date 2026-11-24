package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P6-PREP AUDIT PROBE — read-only measurements for the five clarification points.
 *
 * Asserts nothing about correctness (it prints and records), so it can be run against the current
 * P5 code without changing any production behaviour. Output goes to
 * /home/user/verification/evidence/p6prep_*.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletedContentAuditProbeTest {

    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("P6PREP| $s") }
    private fun dump(t: String) {
        File("/home/user/verification/evidence/$t.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    private fun withImage(id: String) = RichNoteCodec.encodeBlocks(
        listOf(RichNoteBlock.Text(text = "body"), RichNoteBlock.Image(imagePath = id))
    )

    /** Q1: what exactly happens to an attachment deleted while its note stays active? */
    @Test
    fun `Q1 lifecycle of a standalone deleted attachment`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_q1")
        try {
            rig.initializeAndUnlockVault("P6Pass#2026")
            val db = rig.dbHolder.getDatabase()
            val rec = rig.repository.saveRecord(null, "Live note", "body", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "DATA".toByteArray().inputStream(), "keep.txt", "text/plain"
            )
            val entity = db.attachmentDao().getAttachmentById(att.id)!!
            val file = rig.attachmentStorage.resolveEncryptedFile(entity.encryptedRelativePath)
            rig.repository.deleteAttachment(att.id)

            note("note still active          = ${db.recordDao().getRecordById(rec.id)?.isDeleted == false}")
            note("attachment row exists      = ${db.attachmentDao().getAttachmentById(att.id) != null}")
            note("attachment isDeleted       = ${db.attachmentDao().getAttachmentById(att.id)?.isDeleted}")
            note("attachment ciphertext kept = ${file.exists()}")
            note("active attachment list     = ${db.attachmentDao().getAllActiveAttachments().map { it.id }}")
            note("recycle bin batches        = ${db.recycleBinDao().getAllDeletedItems().map { it.rootEntityType to it.rootEntityId }}")
            note("=> no batch owns this attachment, so:")
            note("   - the recycle bin UI cannot show it (it lists DeletedItemEntity batches only)")
            note("   - restoreDeletedBatch cannot bring it back (nothing references it)")
            note("   - permanentlyDeleteBatch cannot remove it (it walks records of a batch)")
            note("   - the row + ciphertext therefore stay until the OWNING NOTE is permanently deleted")
            note("orphan sweep would keep it too: row still references the path = " +
                (db.attachmentDao().getAllAttachmentsIncludingDeleted().any {
                    it.encryptedRelativePath == entity.encryptedRelativePath
                }))
            dump("p6prep_q1_standalone_deleted_attachment")
        } finally { rig.close() }
    }

    /** Q4-a: a file referenced by a row that is NOT in the deleted batch must survive deletion. */
    @Test
    fun `Q4a permanent deletion keeps files referenced by another record via the sweep`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_q4a")
        try {
            rig.initializeAndUnlockVault("P6Pass#2026")
            val db = rig.dbHolder.getDatabase()
            val a = rig.repository.saveRecord(null, "A", "body", null, null, emptyList())
            val b = rig.repository.saveRecord(null, "B", "body", null, null, emptyList())
            val attA = rig.repository.importAttachmentFromStream(
                a.id, "AAA".toByteArray().inputStream(), "a.txt", "text/plain"
            )
            val entityA = db.attachmentDao().getAttachmentById(attA.id)!!
            // a SECOND row (another record) pointing at the very same ciphertext file --
            // reachable through sync of a stale operation whose payload carries the same path
            db.attachmentDao().upsert(
                entityA.copy(id = "shared-row-b", recordId = b.id, syncVersion = 1)
            )
            val file = rig.attachmentStorage.resolveEncryptedFile(entityA.encryptedRelativePath)
            note("two rows share one file: ${entityA.encryptedRelativePath}")

            rig.clock.advanceBy(1_000)
            rig.repository.softDeleteRecord(a.id)
            val batch = db.recordDao().getRecordById(a.id)!!.deletedBatchId!!
            rig.repository.permanentlyDeleteBatch(batch)

            note("after permanently deleting batch of A:")
            note("  A's row   = ${db.attachmentDao().getAttachmentById(attA.id)}")
            note("  shared row= ${db.attachmentDao().getAttachmentById("shared-row-b")?.isDeleted}")
            note("  file exists = ${file.exists()}   <-- owned by the survivor row")
            note("  B still active = ${db.recordDao().getRecordById(b.id)?.isDeleted == false}")
            note("RESULT: the file was removed even though the survivor row still references it.")
            note("CAUSE: permanentlyDeleteBatch() unlinks attachmentPaths (captured from the batch's rows)")
            note("       unconditionally, AFTER the transaction; it never asks whether another row still")
            note("       points at the same path. sweepOrphanAttachments() is reference-counted, but it only")
            note("       ever RUNS on files no row references - the damage is already done before it runs.")
            note("SCOPE: reachable only when two attachment rows share one encryptedRelativePath. On this")
            note("       device paths are generated as <newUuid>.psenc per import, so a shared path needs")
            note("       injected state (sync replay of a stale payload carrying another row's path,")
            note("       or a hand-copied vault). It is a real defect, not a theoretical one.")
            dump("p6prep_q4a_shared_path_sweep")
        } finally { rig.close() }
    }

    /** Q4-b: the same question for a shared INLINE IMAGE (reachable through duplicateRecord). */
    @Test
    fun `Q4b duplicated note shares its inline image and the image survives a batch deletion`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_q4b")
            try {
                rig.initializeAndUnlockVault("P6Pass#2026")
                val db = rig.dbHolder.getDatabase()
                val img = rig.inlineImageStore.importFromStream(
                    byteArrayOf(5, 5, 5).inputStream(), "shared.png"
                )
                val original = rig.repository.saveRecord(null, "Original", withImage(img), null, null, emptyList())
                val copy = rig.repository.duplicateRecord(original.id)
                note("duplicateRecord kept the same image reference = " +
                    db.recordDao().getRecordById(copy.id)!!.description.contains(img))
                note("records referencing the image = " +
                    db.recordDao().getAllRecordsIncludingDeleted()
                        .count { RichNoteCodec.decodeBlocks(it.description)
                            .filterIsInstance<RichNoteBlock.Image>().any { b -> b.imagePath == img } })

                rig.clock.advanceBy(1_000)
                rig.repository.softDeleteRecord(original.id)
                // deleting the image from the ORIGINAL note happens in the editor UI:
                // inlineImageStore.deleteImage(identifier) is called with NO reference check
                rig.inlineImageStore.deleteImage(img)
                note("after deleteImage() from the original note's editor:")
                note("  image file still on disk = ${rig.inlineImageStore.exists(img)}")
                note("  the COPY note still references it = " +
                    db.recordDao().getRecordById(copy.id)!!.description.contains(img))
                note("=> image deletion is NOT reference-counted: removing an image from one note")
                note("   breaks every other note/draft that references the same identifier.")
                dump("p6prep_q4b_shared_inline_image")
            } finally { rig.close() }
        }
}
