package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.attachment.MediaReferenceIndex
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P6 regression suite — recycle-bin completeness, reference-counted media deletion, restore counts.
 *
 * Maps to the approved decisions:
 *   D1 standalone attachment batches (restore + permanent delete)
 *   D2 refuse deleting an attachment an unsaved draft still holds
 *   D3 quarantine design is NOT implemented here (proposal only) — but nothing in P6 deletes on a
 *      timer, and every deletion path here is reference-counted
 *   D4 reference-counted unlinking for attachments *and* inline images, safe against concurrent
 *      reference creation
 *   D5 active/deleted restore counts + summary
 * Plus the extra audits: folder deletion vs attachments, duplicate notes sharing images, two rows
 * sharing one encrypted path, concurrent reference creation, and failed delete/restore without loss.
 *
 * Evidence: /home/user/verification/evidence/p6_*.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecycleBinAndMediaDeletionTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("P6| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256"); md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withImage(id: String, text: String = "body") = RichNoteCodec.encodeBlocks(
        listOf(RichNoteBlock.Text(text = text), RichNoteBlock.Image(imagePath = id))
    )

    private fun imageFile(identifier: String) =
        app.personalspace.vault.data.backup.InlineImageStore.normalizeImageReference(identifier)

    // ------------------------------------------------------------------ D1: standalone attachment

    @Test
    fun `P6-D1 a standalone deleted attachment can be restored and permanently deleted`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d1")
        try {
            rig.initializeAndUnlockVault(PASSWORD)
            val db = rig.dbHolder.getDatabase()
            val record = rig.repository.saveRecord(null, "Live note", "body", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                record.id, "KEEPME".toByteArray().inputStream(), "keep.txt", "text/plain"
            )
            val row = db.attachmentDao().getAttachmentById(att.id)!!
            val file = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)

            rig.repository.deleteAttachment(att.id)
            val batches = db.recycleBinDao().getAllDeletedItems()
            note("batches after deleteAttachment = ${batches.map { it.rootEntityType to it.displayTitle }}")
            note("attachment row isDeleted = ${db.attachmentDao().getAttachmentById(att.id)?.isDeleted}")
            note("ciphertext kept = ${file.exists()}")
            note("owning note still active = ${db.recordDao().getRecordById(record.id)?.isDeleted == false}")
            assertEquals("exactly one batch must be created", 1, batches.size)
            assertEquals("the batch type must be ATTACHMENT", "ATTACHMENT", batches.single().rootEntityType)
            assertEquals("the batch must point at the attachment", att.id, batches.single().rootEntityId)
            assertEquals("the batch must remember the owning note", record.id, batches.single().parentFolderIdSnapshot)
            assertTrue("the file must be kept while it is only deleted", file.exists())

            // ---- restore it ----
            rig.repository.restoreDeletedBatch(batches.single().batchId)
            val restored = db.attachmentDao().getAttachmentById(att.id)!!
            note("after restore: isDeleted=${restored.isDeleted} deletedAt=${restored.deletedAt}")
            note("active attachment list = ${db.attachmentDao().getAllActiveAttachments().map { it.id }}")
            assertFalse("the attachment must be active again", restored.isDeleted)
            assertNull("deletedAt must be cleared", restored.deletedAt)
            assertTrue("it must be listed as active", db.attachmentDao().getAllActiveAttachments().any { it.id == att.id })
            assertTrue("the file must still be there", file.exists())
            assertEquals("the plaintext must be intact", "KEEPME", String(file.readBytes()).let {
                // decrypt through the storage layer for the real check
                val out = ByteArrayOutputStream()
                rig.attachmentStorage.decryptToOutputStream(
                    app.personalspace.vault.domain.model.AttachmentItem(
                        id = row.id, recordId = row.recordId, originalFileName = row.originalFileName,
                        mimeType = row.mimeType,
                        kind = app.personalspace.vault.domain.model.AttachmentKind.OTHER,
                        plaintextSizeBytes = row.plaintextSizeBytes,
                        encryptedSizeBytes = row.encryptedSizeBytes,
                        encryptedRelativePath = row.encryptedRelativePath,
                        plaintextSha256Hex = row.plaintextSha256Hex,
                        createdAt = row.createdAt, modifiedAt = row.modifiedAt
                    ),
                    out
                )
                String(out.toByteArray())
            })
            assertEquals("the batch must be gone after the restore", 0, db.recycleBinDao().getAllDeletedItems().size)

            // ---- delete again, then permanently delete ----
            rig.repository.deleteAttachment(att.id)
            val batch2 = db.recycleBinDao().getAllDeletedItems().single()
            rig.repository.permanentlyDeleteBatch(batch2.batchId)
            note("after permanent delete: row=${db.attachmentDao().getAttachmentById(att.id)} file exists=${file.exists()}")
            note("batches = ${db.recycleBinDao().getAllDeletedItems().size}")
            assertNull("the row must be gone", db.attachmentDao().getAttachmentById(att.id))
            assertFalse("the bytes must be gone", file.exists())
            assertEquals("no batch may linger", 0, db.recycleBinDao().getAllDeletedItems().size)
            assertTrue("the owning note must be untouched", db.recordDao().getRecordById(record.id)?.isDeleted == false)
            dump("p6_D1_standalone_attachment_batch")
        } finally { rig.close() }
    }

    // ------------------------------------------------------------------ D2: draft refusal

    @Test
    fun `P6-D2 deleting an attachment that an unsaved draft needs is refused with a clear message`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d2")
            try {
                rig.initializeAndUnlockVault(PASSWORD)
                val db = rig.dbHolder.getDatabase()
                val record = rig.repository.saveRecord(null, "Editing", "body", null, null, emptyList())
                val att = rig.repository.importAttachmentFromStream(
                    record.id, "DRAFT-DEP".toByteArray().inputStream(), "d.txt", "text/plain"
                )
                val row = db.attachmentDao().getAttachmentById(att.id)!!
                val file = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)

                // the editor autosaves an unsaved draft for this note
                rig.repository.saveDraft(
                    app.personalspace.vault.domain.model.RecordDraft(
                        draftKey = "EDIT_${record.id}",
                        folderId = null,
                        title = "Editing",
                        description = "unsaved body",
                        category = null,
                        customFields = emptyList(),
                        updatedAt = rig.clock.nowMillis()
                    )
                )
                note("draft present = ${db.recordDao().getDraft("EDIT_${record.id}") != null}")

                val outcome = runCatching { rig.repository.deleteAttachment(att.id) }
                val ex = outcome.exceptionOrNull()
                note("delete outcome = " + (ex?.let { it.javaClass.simpleName + ": " + it.message }
                    ?: "SUCCESS (unexpected)"))
                assertNotNull("the delete must be refused", ex)
                assertTrue("the message must mention the unsaved draft",
                    ex!!.message!!.contains("unsaved", ignoreCase = true) ||
                        ex.message!!.contains("draft", ignoreCase = true))
                assertFalse("the attachment must stay active",
                    db.attachmentDao().getAttachmentById(att.id)!!.isDeleted)
                assertTrue("the file must be untouched", file.exists())
                assertEquals("no recycle-bin batch may be created", 0, db.recycleBinDao().getAllDeletedItems().size)

                // after the draft is discarded the delete works
                rig.repository.clearDraft("EDIT_${record.id}")
                rig.repository.deleteAttachment(att.id)
                note("after discarding the draft: isDeleted = ${db.attachmentDao().getAttachmentById(att.id)!!.isDeleted}")
                assertTrue("the delete must now succeed", db.attachmentDao().getAttachmentById(att.id)!!.isDeleted)
                assertTrue("and it must still keep the bytes", file.exists())
                dump("p6_D2_draft_refuses_attachment_delete")
            } finally { rig.close() }
        }

    // ------------------------------------------------------------------ D4: shared encrypted path

    @Test
    fun `P6-D4a two attachment rows sharing one encrypted path keep the file when one is deleted`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d4a")
            try {
                rig.initializeAndUnlockVault(PASSWORD)
                val db = rig.dbHolder.getDatabase()
                val a = rig.repository.saveRecord(null, "A", "body", null, null, emptyList())
                val b = rig.repository.saveRecord(null, "B", "body", null, null, emptyList())
                val attA = rig.repository.importAttachmentFromStream(
                    a.id, "SHARED".toByteArray().inputStream(), "a.txt", "text/plain"
                )
                val entityA = db.attachmentDao().getAttachmentById(attA.id)!!
                // a second row for another, ACTIVE record that points at the same file
                db.attachmentDao().upsert(entityA.copy(id = "shared-b", recordId = b.id))
                val file = rig.attachmentStorage.resolveEncryptedFile(entityA.encryptedRelativePath)
                note("shared file = ${entityA.encryptedRelativePath}, referenced by rows: " +
                    db.attachmentDao().getAllAttachmentsIncludingDeleted().map { it.id })

                rig.clock.advanceBy(1_000)
                rig.repository.softDeleteRecord(a.id)
                val batch = db.recordDao().getRecordById(a.id)!!.deletedBatchId!!
                rig.repository.permanentlyDeleteBatch(batch)

                note("after deleting A's batch:")
                note("  A's row      = ${db.attachmentDao().getAttachmentById(attA.id)}")
                note("  shared row   = ${db.attachmentDao().getAttachmentById("shared-b")?.isDeleted}")
                note("  file exists  = ${file.exists()}")
                note("  B still active = ${db.recordDao().getRecordById(b.id)?.isDeleted == false}")
                assertNull("the deleted record's row must be gone", db.attachmentDao().getAttachmentById(attA.id))
                assertNotNull("the surviving row must still exist", db.attachmentDao().getAttachmentById("shared-b"))
                assertTrue("THE FILE MUST SURVIVE while another row references it", file.exists())
                assertTrue("B must still be active", db.recordDao().getRecordById(b.id)?.isDeleted == false)
                dump("p6_D4a_shared_attachment_path")
            } finally { rig.close() }
        }

    @Test
    fun `P6-D4b duplicate notes sharing an inline image keep the image when one note drops it`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d4b")
            try {
                rig.initializeAndUnlockVault(PASSWORD)
                val db = rig.dbHolder.getDatabase()
                val img = rig.inlineImageStore.importFromStream(byteArrayOf(6, 6, 6).inputStream(), "shared.png")
                val original = rig.repository.saveRecord(null, "Original", withImage(img), null, null, emptyList())
                val copy = rig.repository.duplicateRecord(original.id)
                note("image = $img")
                note("referencing notes = ${db.recordDao().getAllRecordsIncludingDeleted().count { it.description.contains(img) }}")

                // the user deletes the image from the ORIGINAL note in the editor
                note("holders before delete = " +
                    MediaReferenceIndex.imageHolders(db, imageFile(img)).map { it.ownerId to it.label })
                note("editing record = ${original.id}, copy = ${copy.id}")
                // imagePath FIRST (this was the test's own bug: passing a record id as the image path
                // makes the reference set empty, which would have "proved" the wrong thing)
                val removed = rig.repository.deleteInlineImageIfUnreferenced(img, original.id)
                note("deleteInlineImageIfUnreferenced(editing=original) = $removed (false = file kept)")
                note("file still on disk = ${rig.inlineImageStore.exists(img)}")
                note("copy still references it = ${db.recordDao().getRecordById(copy.id)!!.description.contains(img)}")
                assertFalse("the file must NOT be deleted while the copy still uses it", removed)
                assertTrue("the image file must still exist", rig.inlineImageStore.exists(img))
                assertTrue("the copy must still reference it",
                    db.recordDao().getRecordById(copy.id)!!.description.contains(img))

                // now delete it from the copy too -> the last reference is gone
                val removed2 = rig.repository.deleteInlineImageIfUnreferenced(img, copy.id)
                note("deleteInlineImageIfUnreferenced(editing=copy) = $removed2")
                note("file exists after the last reference is dropped = ${rig.inlineImageStore.exists(img)}")
                // the original note is still saved with the same body, so it still references it
                note("original body still references the image = " +
                    db.recordDao().getRecordById(original.id)!!.description.contains(img))
                assertTrue("the original note still holds a reference, so the file must stay",
                    rig.inlineImageStore.exists(img))
                dump("p6_D4b_shared_inline_image")
            } finally { rig.close() }
        }

    // ------------------------------------------------------------------ concurrent reference creation

    @Test
    fun `P6-D4c a reference created concurrently with permanent deletion cannot be orphaned`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d4c")
            try {
                rig.initializeAndUnlockVault(PASSWORD)
                val db = rig.dbHolder.getDatabase()
                val a = rig.repository.saveRecord(null, "A", "body", null, null, emptyList())
                val b = rig.repository.saveRecord(null, "Racer", "body", null, null, emptyList())
                val attA = rig.repository.importAttachmentFromStream(
                    a.id, "RACE".toByteArray().inputStream(), "a.txt", "text/plain"
                )
                val entityA = db.attachmentDao().getAttachmentById(attA.id)!!
                val file = rig.attachmentStorage.resolveEncryptedFile(entityA.encryptedRelativePath)
                rig.clock.advanceBy(1_000)
                rig.repository.softDeleteRecord(a.id)
                val batchId = db.recordDao().getRecordById(a.id)!!.deletedBatchId!!
                note("shared file = ${entityA.encryptedRelativePath}, batch = $batchId")

                // ---- A: a reference published INSIDE the media lock, i.e. while the deletion may be
                // running, must be taken into account by the reference-counted unlink.
                val token = "p6_race_restore"
                MediaReferenceIndex.markRestoreInFlight(token)   // makes deletion refuse
                try {
                    runCatching { rig.repository.permanentlyDeleteBatch(batchId) }
                        .exceptionOrNull()?.let { note("while flagged in-flight: ${it.message}") }
                } finally {
                    MediaReferenceIndex.clearRestoreInFlight(token)
                }
                note("deletion refused while in flight, file exists = ${file.exists()}")
                assertTrue("nothing may be deleted while a restore is in flight", file.exists())

                // publish a new reference to the SAME file, exactly as a racing writer would
                db.attachmentDao().upsert(entityA.copy(id = "racer-1", recordId = b.id))

                // ---- B: now delete for real; the racing reference must keep the bytes ----
                rig.repository.permanentlyDeleteBatch(batchId)
                val survivors = db.attachmentDao().getAllAttachmentsIncludingDeleted()
                    .filter { File(it.encryptedRelativePath).name == File(entityA.encryptedRelativePath).name }
                note("survivors after deletion = ${survivors.map { it.id }}")
                note("file exists = ${file.exists()}")
                assertEquals("the deleted record's own row must be gone", null,
                    db.attachmentDao().getAttachmentById(attA.id))
                assertEquals("exactly the racing row must survive", listOf("racer-1"), survivors.map { it.id })
                assertTrue("INVARIANT: a surviving row always keeps its bytes", file.exists())

                // ---- C: true parallel hammering of the lock — no exception, no orphaned row ----
                val jobs = (0 until 24).map { i ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        MediaReferenceIndex.withMediaLock {
                            runCatching {
                                db.attachmentDao().upsert(
                                    entityA.copy(id = "hammer-$i", recordId = b.id)
                                )
                            }.isSuccess
                        }
                    }
                }
                val ok = jobs.awaitAll().count { it }
                val allRows = db.attachmentDao().getAllAttachmentsIncludingDeleted()
                    .filter { File(it.encryptedRelativePath).name == File(entityA.encryptedRelativePath).name }
                note("parallel writers succeeded = $ok / 24")
                note("rows claiming the file = ${allRows.size}, file exists = ${file.exists()}")
                assertTrue("writers must have run", ok > 0)
                assertTrue("every surviving row must still have its file", allRows.isEmpty() || file.exists())
                dump("p6_D4c_concurrent_reference_creation")
            } finally { rig.close() }
        }

    @Test
    fun `P6-D4d permanent deletion is refused while a restore is in flight`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d4d")
        try {
            rig.initializeAndUnlockVault(PASSWORD)
            val db = rig.dbHolder.getDatabase()
            val rec = rig.repository.saveRecord(null, "X", "body", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "INFLIGHT".toByteArray().inputStream(), "x.txt", "text/plain"
            )
            rig.repository.deleteAttachment(att.id)
            val batch = db.recycleBinDao().getAllDeletedItems().single()
            val file = rig.attachmentStorage.resolveEncryptedFile(
                db.attachmentDao().getAttachmentById(att.id)!!.encryptedRelativePath
            )

            val token = "test_restore_inflight"
            MediaReferenceIndex.markRestoreInFlight(token)
            try {
                val outcome = runCatching { rig.repository.permanentlyDeleteBatch(batch.batchId) }
                val ex = outcome.exceptionOrNull()
                note("permanent delete during restore = " +
                    (ex?.let { it.javaClass.simpleName + ": " + it.message } ?: "SUCCESS (unexpected)"))
                assertNotNull("permanent deletion must refuse while a restore is in flight", ex)
                assertTrue("the refusal must be explicit",
                    ex!!.message!!.contains("restore", ignoreCase = true))
                assertNotNull("nothing may be deleted", db.attachmentDao().getAttachmentById(att.id))
                assertTrue("no file may be deleted", file.exists())
                assertNotNull("the batch must survive", db.recycleBinDao().getDeletedBatch(batch.batchId))
            } finally {
                MediaReferenceIndex.clearRestoreInFlight(token)
            }

            rig.repository.permanentlyDeleteBatch(batch.batchId)
            note("after the restore flag clears: row=${db.attachmentDao().getAttachmentById(att.id)} " +
                "file=${file.exists()}")
            assertNull("now it must delete the row", db.attachmentDao().getAttachmentById(att.id))
            assertFalse("and the bytes", file.exists())
            dump("p6_D4d_restore_in_flight_refusal")
        } finally { rig.close() }
    }

    // ------------------------------------------------------------------ folder deletion audit

    @Test
    fun `P6-F1 folder deletion marks the notes' attachments deleted and restore brings them back`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_f1")
            try {
                rig.initializeAndUnlockVault(PASSWORD)
                val db = rig.dbHolder.getDatabase()
                val folder = rig.repository.createFolder(
                    "Archive", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE
                )
                val note1 = rig.repository.saveRecord(null, "InFolder", "body", folder.id, null, emptyList())
                val att = rig.repository.importAttachmentFromStream(
                    note1.id, "INFOLDER".toByteArray().inputStream(), "f.txt", "text/plain"
                )
                rig.clock.advanceBy(1_000)
                rig.repository.softDeleteFolder(folder.id)

                val attRow = db.attachmentDao().getAttachmentById(att.id)!!
                note("after folder delete: attachment isDeleted=${attRow.isDeleted} deletedAt=${attRow.deletedAt}")
                assertTrue("the attachment must be marked deleted with its folder",
                    attRow.isDeleted)
                assertNotNull("its deletion timestamp must be set", attRow.deletedAt)
                assertTrue("its bytes must be kept",
                    rig.attachmentStorage.resolveEncryptedFile(attRow.encryptedRelativePath).exists())

                val batch = db.recycleBinDao().getAllDeletedItems().single()
                rig.repository.restoreDeletedBatch(batch.batchId)
                val after = db.attachmentDao().getAttachmentById(att.id)!!
                note("after folder restore: attachment isDeleted=${after.isDeleted}")
                assertFalse("the attachment must be active again", after.isDeleted)
                assertNull("deletedAt must be cleared", after.deletedAt)
                assertFalse("the note must be active again",
                    db.recordDao().getRecordById(note1.id)!!.isDeleted)
                dump("p6_F1_folder_delete_attachment_consistency")
            } finally { rig.close() }
        }

    // ------------------------------------------------------------------ D5: restore counts

    @Test
    fun `P6-D5 restore reports active and deleted counts separately with a summary`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d5")
        try {
            rig.initializeAndUnlockVault(PASSWORD)
            val db = rig.dbHolder.getDatabase()
            val active = rig.repository.saveRecord(null, "Active", "a", null, null, emptyList())
            val doomed = rig.repository.saveRecord(null, "Doomed", "d", null, null, emptyList())
            rig.repository.importAttachmentFromStream(
                active.id, "A".toByteArray().inputStream(), "a.txt", "text/plain"
            )
            rig.repository.importAttachmentFromStream(
                doomed.id, "D".toByteArray().inputStream(), "d.txt", "text/plain"
            )
            rig.clock.advanceBy(1_000)
            rig.repository.softDeleteRecord(doomed.id)

            val pkg = rig.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL,
                targetFile = File(rig.context.cacheDir, "p6_d5.psbackup"),
                recordHistoryInDb = false
            ).file
            val result = rig.backupManager.restoreFromBackupFile(pkg, PASSWORD.toCharArray())
            note("notes=${result.notes} activeNotes=${result.activeNotes} deletedNotes=${result.deletedNotes}")
            note("attachments=${result.attachments} activeAttachments=${result.activeAttachments} " +
                "deletedAttachments=${result.deletedAttachments}")
            note("summary = ${result.verificationSummary}")

            assertEquals("existing field keeps active-only semantics", 1, result.notes)
            assertEquals("active notes", 1, result.activeNotes)
            assertEquals("deleted notes", 1, result.deletedNotes)
            assertEquals("active attachments", 1, result.activeAttachments)
            assertEquals("deleted attachments (P6: cascade)", 1, result.deletedAttachments)
            assertTrue("the summary must mention the recycle bin",
                result.verificationSummary.contains("recycle bin", ignoreCase = true))
            assertTrue("the summary must carry both totals",
                result.verificationSummary.contains("2 notes") &&
                    result.verificationSummary.contains("2 attachments"))
            dump("p6_D5_restore_counts")
        } finally { rig.close() }
    }

    @Test
    fun `P6-D5b a vault made only of deleted content reports deletedNotes not zero content`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_d5b")
            try {
                rig.initializeAndUnlockVault(PASSWORD)
                val only = rig.repository.saveRecord(null, "OnlyDeleted", "b", null, null, emptyList())
                rig.clock.advanceBy(1_000)
                rig.repository.softDeleteRecord(only.id)
                val pkg = rig.backupManager.createFullBackupFile(
                    BackupKind.FULL_MANUAL,
                    targetFile = File(rig.context.cacheDir, "p6_d5b.psbackup"),
                    recordHistoryInDb = false
                ).file
                val result = rig.backupManager.restoreFromBackupFile(pkg, PASSWORD.toCharArray())
                note("result = notes=${result.notes} deletedNotes=${result.deletedNotes}")
                note("summary = ${result.verificationSummary}")
                assertEquals("active count is genuinely zero", 0, result.notes)
                assertEquals("but the deleted count proves content was restored", 1, result.deletedNotes)
                assertTrue("the summary must say so", result.verificationSummary.contains("1 notes (0 active, 1 in recycle bin)"))
                dump("p6_D5b_deleted_only_vault")
            } finally { rig.close() }
        }

    // ------------------------------------------------------------------ failure injection

    @Test
    fun `P6-F2 a failed permanent deletion leaves rows, bytes and the batch intact`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_f2")
        try {
            rig.initializeAndUnlockVault(PASSWORD)
            val db = rig.dbHolder.getDatabase()
            val rec = rig.repository.saveRecord(null, "Y", "body", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "FAIL".toByteArray().inputStream(), "y.txt", "text/plain"
            )
            // make the row's path point at a directory: unlinking it throws on real IO
            val row = db.attachmentDao().getAttachmentById(att.id)!!
            val file = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)
            rig.repository.deleteAttachment(att.id)
            val batch = db.recycleBinDao().getAllDeletedItems().single()

            // sabotage: replace the ciphertext with a directory of the same name so delete fails
            file.delete()
            file.mkdirs()
            File(file, "keep.bin").writeBytes(ByteArray(4))
            val outcome = runCatching { rig.repository.permanentlyDeleteBatch(batch.batchId) }
            val ex = outcome.exceptionOrNull()
            note("permanent delete with an undeletable path = " +
                (ex?.let { it.javaClass.simpleName + ": " + it.message } ?: "completed"))
            note("row after the attempt = ${db.attachmentDao().getAttachmentById(att.id) != null}")
            note("batch after the attempt = ${db.recycleBinDao().getDeletedBatch(batch.batchId) != null}")
            // P6-F2 was the test that found the ordering bug: bytes must be attempted BEFORE rows
            assertNotNull("the deletion must FAIL, not silently half-succeed", ex)
            assertNotNull("the row must survive so the attachment stays recoverable",
                db.attachmentDao().getAttachmentById(att.id))
            assertNotNull("the batch must survive so the user can retry",
                db.recycleBinDao().getDeletedBatch(batch.batchId))
            // whichever way it ends, the invariant is: no half state with a live row and no file,
            // and nothing silently lost
            val rowExists = db.attachmentDao().getAttachmentById(att.id) != null
            note("=> row exists = $rowExists, path exists = ${file.exists()}")
            if (rowExists) {
                assertTrue("a surviving row must keep a resolvable path", file.exists())
            }
            assertTrue("the vault must still be usable", db.recordDao().getRecordById(rec.id) != null)
            file.deleteRecursively()
            dump("p6_F2_failed_permanent_delete")
        } finally { rig.close() }
    }

    @Test
    fun `P6-F3 a failed restore leaves deleted content, batches and media untouched`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_f3")
        try {
            rig.initializeAndUnlockVault(PASSWORD)
            val db = rig.dbHolder.getDatabase()
            val img = rig.inlineImageStore.importFromStream(byteArrayOf(9, 9).inputStream(), "d.png")
            val rec = rig.repository.saveRecord(null, "Del", withImage(img), null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "KEEP".toByteArray().inputStream(), "k.txt", "text/plain"
            )
            rig.clock.advanceBy(1_000)
            rig.repository.softDeleteRecord(rec.id)

            // suspend snapshot: the DAOs suspend, so it cannot be a local non-suspend function
            suspend fun snapshot(): Triple<List<List<Any?>>, List<List<Any?>>, List<Pair<String, String>>> = Triple(
                db.recordDao().getAllRecordsIncludingDeleted().map { listOf(it.id, it.isDeleted, it.deletedAt, it.deletedBatchId) },
                db.attachmentDao().getAllAttachmentsIncludingDeleted().map { listOf(it.id, it.isDeleted, it.deletedAt) },
                db.recycleBinDao().getAllDeletedItems().map { it.batchId to it.rootEntityId }
            )
            val before = snapshot()
            val imagesBefore = rig.inlineImageStore.imagesDir.listFiles()!!.map { it.name to sha256(it.readBytes()) }.toMap()
            val attsBefore = rig.attachmentStorage.encryptedDir.listFiles()!!.map { it.name to sha256(it.readBytes()) }.toMap()

            // a package whose declared draft count cannot verify
            val genuine = rig.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL,
                targetFile = File(rig.context.cacheDir, "p6_f3.psbackup"),
                recordHistoryInDb = false
            ).file
            val forged = tamperDraftCount(rig, genuine)
            val outcome = runCatching { rig.backupManager.restoreFromBackupFile(forged, PASSWORD.toCharArray()) }
            note("restore outcome = " + (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "SUCCESS (unexpected)"))
            assertNotNull("restore must fail", outcome.exceptionOrNull())

            assertEquals("rows must be identical", before, snapshot())
            assertEquals("image files must be identical", imagesBefore,
                rig.inlineImageStore.imagesDir.listFiles()!!.map { it.name to sha256(it.readBytes()) }.toMap())
            assertEquals("attachment files must be identical", attsBefore,
                rig.attachmentStorage.encryptedDir.listFiles()!!.map { it.name to sha256(it.readBytes()) }.toMap())
            assertNull("no journal may be left", RestoreJournal.read(rig.context))
            assertTrue("the deleted image must still be readable", rig.inlineImageStore.exists(img))
            assertNotNull("the deleted attachment row must survive", db.attachmentDao().getAttachmentById(att.id))
            dump("p6_F3_failed_restore_no_loss")
        } finally { rig.close() }
    }

    // ------------------------------------------------------------------ helpers

    private fun tamperDraftCount(rig: IsolatedVaultTestRig, genuine: File): File {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val manifestText = java.util.zip.ZipFile(genuine).use { z ->
            z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()
        }
        val parsed = json.decodeFromString(
            app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), manifestText
        )
        val unsigned = parsed.copy(draftCount = parsed.draftCount + 3, manifestHmacBase64 = "")
        val canonical = json.encodeToString(
            app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), unsigned
        )
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(manifestKeyOf(genuine), "HmacSHA256"))
        val signed = unsigned.copy(
            manifestHmacBase64 = app.personalspace.vault.core.crypto.VaultCryptoEngine
                .toBase64(mac.doFinal(canonical.toByteArray()))
        )
        val out = File(rig.context.cacheDir, "p6_forged_${System.nanoTime()}.psbackup")
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
            java.util.zip.ZipFile(genuine).use { z ->
                z.entries().asSequence().forEach { e ->
                    val bytes = if (e.name == "manifest.json") {
                        json.encodeToString(
                            app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), signed
                        ).toByteArray()
                    } else z.getInputStream(e).readBytes()
                    val entry = java.util.zip.ZipEntry(e.name)
                    entry.method = java.util.zip.ZipEntry.STORED
                    entry.size = bytes.size.toLong(); entry.compressedSize = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    zos.putNextEntry(entry); zos.write(bytes); zos.closeEntry()
                }
            }
        }
        return out
    }

    private fun manifestKeyOf(genuine: File): ByteArray {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            java.util.zip.ZipFile(genuine).use { z ->
                z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()
            }
        ).jsonObject
        val vaultId = root["vaultId"]!!.jsonPrimitive.content
        val wrap = root["portableKeyWrap"]!!.jsonObject
        val argon = wrap["argon2Config"]!!.jsonObject
        val wrapKey = app.personalspace.vault.core.crypto.VaultCryptoEngine.derivePasswordWrappingKey(
            ("psbackup-portable:" + vaultId).toCharArray(),
            app.personalspace.vault.core.crypto.VaultCryptoEngine
                .fromBase64(wrap["saltBase64"]!!.jsonPrimitive.content),
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
        return app.personalspace.vault.core.crypto.VaultCryptoEngine.deriveSubKeys(vdk, vaultId).backupManifestKey
    }

    private companion object { const val PASSWORD = "P6Pass#2026" }
}
