package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.attachment.MediaReferenceIndex
import app.personalspace.vault.data.attachment.OrphanQuarantineManager
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
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
 * P6 item 8 — safe orphan detection and quarantine.
 *
 * The invariants under test:
 *   * detection is read-only (no file moves, no deletions, no DB writes),
 *   * a file younger than the age threshold is never reported,
 *   * quarantine MOVES files and records sha256 + size + timestamps in a manifest,
 *   * nothing is emptied without an explicit call,
 *   * quarantine refuses to run while a restore is in flight,
 *   * references are counted across active records, recycle-bin records, drafts and attachment rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrphanQuarantineTest {

    private val ev = StringBuilder()
    private lateinit var rig: IsolatedVaultTestRig
    private lateinit var quarantine: OrphanQuarantineManager
    private val oldTime = System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L  // 3 days ago

    private fun note(line: String) { ev.append(line).append('\n'); println("P6Q| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(file.readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ageIt(file: File) { file.setLastModified(oldTime) }

    @Before
    fun setUp() = runBlocking {
        rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p6_quarantine")
        rig.initializeAndUnlockVault(PASSWORD)
        // both stores root themselves directly under context.filesDir
        // (InlineImageStore -> note_images, EncryptedAttachmentStorage -> encrypted_attachments)
        quarantine = OrphanQuarantineManager(rig.dbHolder.getDatabase(), rig.context.filesDir)
    }

    @After
    fun tearDown() { rig.close() }

    // ---------------------------------------------------------------- detection is read-only

    @Test
    fun `P6-Q1 detection reports an unreferenced file and changes nothing`() = runBlocking {
        val db = rig.dbHolder.getDatabase()
        val rec = rig.repository.saveRecord(null, "Kept", "body", null, null, emptyList())
        val att = rig.repository.importAttachmentFromStream(
            rec.id, "KEPT".toByteArray().inputStream(), "kept.txt", "text/plain"
        )
        val keptRow = db.attachmentDao().getAttachmentById(att.id)!!
        val keptFile = rig.attachmentStorage.resolveEncryptedFile(keptRow.encryptedRelativePath)

        // a stray ciphertext nobody references, old enough to judge
        val orphan = File(rig.attachmentStorage.encryptedDir, "stray-${System.nanoTime()}.psenc")
        orphan.writeBytes(ByteArray(2048) { 7 })
        ageIt(orphan)
        val orphanHash = sha256(orphan)

        val before = rig.attachmentStorage.encryptedDir.listFiles()!!.map { it.name }.sorted()
        val scan = quarantine.scan()
        val after = rig.attachmentStorage.encryptedDir.listFiles()!!.map { it.name }.sorted()

        note("seen = ${scan.attachmentFilesSeen}, referenced = ${scan.referencedAttachmentNames}")
        note("candidates = ${scan.candidates.map { it.fileName + " (" + it.sizeBytes + "B, " + it.reason + ")" }}")
        note("recent skipped = ${scan.recentFilesSkipped}")
        note("readOnly flag = ${scan.readOnly}")
        assertEquals("detection must be marked read-only", true, scan.readOnly)
        assertEquals("only the stray file may be a candidate", listOf(orphan.name), scan.candidates.map { it.fileName })
        assertEquals("the candidate hash must be recorded", orphanHash, scan.candidates.single().sha256Hex)
        assertEquals("the size must be recorded", 2048L, scan.candidates.single().sizeBytes)
        assertEquals("DETECTION MUST NOT TOUCH THE FILESYSTEM", before, after)
        assertTrue("both files must still be there", orphan.exists() && keptFile.exists())
        assertFalse("no quarantine folder may be created by a scan", quarantine.quarantineDir.exists())
        dump("p6_Q1_detection_read_only")
    }

    @Test
    fun `P6-Q2 a recently written file is never reported as an orphan`() = runBlocking {
        val fresh = File(rig.attachmentStorage.encryptedDir, "fresh-${System.nanoTime()}.psenc")
        fresh.writeBytes(ByteArray(16) { 1 })   // timestamp = now
        val scan = quarantine.scan()
        note("candidates = ${scan.candidates.map { it.fileName }}")
        note("recent skipped = ${scan.recentFilesSkipped}")
        assertTrue("a file written just now must not be a candidate",
            scan.candidates.none { it.fileName == fresh.name })
        assertTrue("it must be listed as skipped-because-recent",
            scan.recentFilesSkipped.any { it.endsWith(fresh.name) })
        note("=> the age threshold is what keeps in-flight imports/restores from being moved")
        dump("p6_Q2_age_threshold")
    }

    @Test
    fun `P6-Q3 drafts and recycle-bin records still count as references`() = runBlocking {
        val db = rig.dbHolder.getDatabase()
        // an image referenced ONLY by an unsaved draft
        val img = rig.inlineImageStore.importFromStream(byteArrayOf(3, 3, 3).inputStream(), "draft.png")
        val imgFile = File(rig.inlineImageStore.imagesDir, "$img.psenc")
        assertTrue("image file must exist", imgFile.exists())
        ageIt(imgFile)
        rig.repository.saveDraft(
            app.personalspace.vault.domain.model.RecordDraft(
                draftKey = "EDIT_draft-only",
                folderId = null,
                title = "draft",
                description = RichNoteCodec.encodeBlocks(
                    listOf(RichNoteBlock.Text(text = "x"), RichNoteBlock.Image(imagePath = img))
                ),
                category = null,
                customFields = emptyList(),
                updatedAt = rig.clock.nowMillis()
            )
        )

        // an attachment whose ONLY row is soft-deleted (recycle bin)
        val rec = rig.repository.saveRecord(null, "Del", "b", null, null, emptyList())
        val att = rig.repository.importAttachmentFromStream(
            rec.id, "DELME".toByteArray().inputStream(), "del.txt", "text/plain"
        )
        val row = db.attachmentDao().getAttachmentById(att.id)!!
        val delFile = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)
        rig.repository.deleteAttachment(att.id)     // soft delete -> recycle bin, single ATTACHMENT batch
        ageIt(delFile)

        val scan = quarantine.scan()
        note("candidates = ${scan.candidates.map { it.kind + ":" + it.fileName }}")
        assertTrue("an image held only by a draft must NOT be an orphan",
            scan.candidates.none { it.fileName.startsWith(img) })
        assertTrue("an attachment held only by a recycle-bin row must NOT be an orphan",
            scan.candidates.none { it.fileName == delFile.name })
        note("=> references are counted across drafts and deleted rows, as required")
        dump("p6_Q3_draft_and_deleted_references")
    }

    // ---------------------------------------------------------------- quarantine moves, never deletes

    @Test
    fun `P6-Q4 quarantine moves the file, preserves its hash and its timestamps`() = runBlocking {
        val orphan = File(rig.attachmentStorage.encryptedDir, "movable-${System.nanoTime()}.psenc")
        orphan.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        ageIt(orphan)
        val hashBefore = sha256(orphan)
        val sizeBefore = orphan.length()
        val mtimeBefore = orphan.lastModified()

        val scan = quarantine.scan()
        val manifest = quarantine.quarantine(scan.candidates)
        val entry = manifest.entries.single { it.quarantineFileName.endsWith(orphan.name) }
        val moved = File(quarantine.quarantineDir, entry.quarantineFileName)

        note("manifest entry = $entry")
        note("moved file exists = ${moved.exists()}, original still present = ${orphan.exists()}")
        assertFalse("the original must be gone from the media folder", orphan.exists())
        assertTrue("the file must exist in quarantine", moved.exists())
        assertEquals("the bytes must be identical", hashBefore, sha256(moved))
        assertEquals("the manifest must record the hash", hashBefore, entry.sha256Hex)
        assertEquals("the manifest must record the size", sizeBefore, entry.sizeBytes)
        assertEquals("the manifest must record the original mtime",
            mtimeBefore, entry.originalLastModifiedMillis)
        assertEquals("the mtime must be preserved on the moved file", mtimeBefore, moved.lastModified())
        assertTrue("the manifest must record where it came from",
            entry.originalRelativePath.startsWith("encrypted_attachments/"))
        assertTrue("a manifest file must be written on disk", quarantine.manifestFile.exists())
        note("manifest file = ${quarantine.manifestFile.name} (${quarantine.manifestFile.length()} B)")
        dump("p6_Q4_quarantine_manifest")
    }

    @Test
    fun `P6-Q5 quarantine is never emptied automatically and can be inspected, exported and restored`() =
        runBlocking {
            val orphan = File(rig.attachmentStorage.encryptedDir, "hold-${System.nanoTime()}.psenc")
            orphan.writeBytes(ByteArray(512) { 9 })
            ageIt(orphan)
            val hash = sha256(orphan)
            quarantine.quarantine(quarantine.scan().candidates)

            // "time passes": many scans, many reads — none of them may empty quarantine
            repeat(3) { quarantine.scan(); quarantine.inspect() }
            val held = quarantine.quarantineDir.listFiles()!!.filter { it.isFile }
            note("files still held after repeated scans = ${held.map { it.name }}")
            assertTrue("quarantine must still hold the file", held.any { it.name.endsWith(orphan.name) })
            assertEquals("the manifest must still describe it", 1, quarantine.inspect().entries.size)

            // export is read-only
            val exportDir = File(rig.context.cacheDir, "export-${System.nanoTime()}")
            val exported = quarantine.exportTo(exportDir)
            val exportedFiles = exported.listFiles()!!.map { it.name }
            note("exported = $exportedFiles")
            assertTrue("the manifest must be in the export", exportedFiles.contains(OrphanQuarantineManager.MANIFEST_NAME))
            assertTrue("the payload must be in the export",
                exportedFiles.any { it.endsWith(orphan.name) })
            assertTrue("export must not remove the original from quarantine",
                quarantine.quarantineDir.listFiles()!!.any { it.name.endsWith(orphan.name) })

            // explicit restore puts it back with the right bytes
            val entry = quarantine.inspect().entries.single()
            val restored = quarantine.restoreEntry(entry.quarantineFileName)
            val back = File(rig.attachmentStorage.encryptedDir, orphan.name)
            note("restoreEntry = $restored, file back = ${back.exists()}, hash ok = ${back.exists() && sha256(back) == hash}")
            assertTrue("restore must succeed", restored)
            assertTrue("the file must be back in the media folder", back.exists())
            assertEquals("with identical bytes", hash, sha256(back))
            assertEquals("the manifest entry must be dropped", 0, quarantine.inspect().entries.size)

            // and only an explicit call empties
            quarantine.quarantine(quarantine.scan().candidates)
            val removed = quarantine.emptyQuarantine()
            note("explicit emptyQuarantine removed = $removed files")
            assertTrue("the explicit call must remove the files", removed >= 1)
            assertEquals("nothing may be left", 0,
                quarantine.quarantineDir.listFiles()!!.count { it.isFile })
            dump("p6_Q5_quarantine_lifecycle")
        }

    @Test
    fun `P6-Q6 quarantine refuses to run during a restore and never touches referenced files`() =
        runBlocking {
            val rec = rig.repository.saveRecord(null, "Live", "b", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "LIVE".toByteArray().inputStream(), "live.txt", "text/plain"
            )
            val row = rig.dbHolder.getDatabase().attachmentDao().getAttachmentById(att.id)!!
            val live = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)
            ageIt(live)

            val token = "p6_q6_restore"
            MediaReferenceIndex.markRestoreInFlight(token)
            val during = runCatching { quarantine.assertSafeToRun("settings") }
            note("during restore -> " + (during.exceptionOrNull()?.message ?: "allowed (unexpected)"))
            assertTrue("the guard must refuse during a restore", during.isFailure)
            MediaReferenceIndex.clearRestoreInFlight(token)
            quarantine.assertSafeToRun("settings")
            note("after the restore flag clears -> allowed")

            val scan = quarantine.scan()
            note("referenced live file reported as orphan? ${scan.candidates.any { it.fileName == live.name }}")
            assertFalse("a referenced attachment must never be a candidate",
                scan.candidates.any { it.fileName == live.name })
            assertNull("no manifest may exist yet", quarantine.manifestFile.takeIf { it.exists() })
            assertNotNull("the live file must be untouched", live.takeIf { it.exists() })
            dump("p6_Q6_restore_guard_and_live_files")
        }

    @Test
    fun `P6-Q7 a reference created after the scan wins over the stale candidate list`() = runBlocking {
        val db = rig.dbHolder.getDatabase()
        val rec = rig.repository.saveRecord(null, "Later", "b", null, null, emptyList())
        val stray = File(rig.attachmentStorage.encryptedDir, "late-${System.nanoTime()}.psenc")
        stray.writeBytes(ByteArray(128) { 5 })
        ageIt(stray)
        val scan = quarantine.scan()
        assertEquals("the file must be a candidate before the reference exists",
            1, scan.candidates.count { it.fileName == stray.name })

        // a row is created pointing at that same file between the scan and the quarantine call
        val imported = rig.repository.importAttachmentFromStream(
            rec.id, "TEMPLATE".toByteArray().inputStream(), "t.txt", "text/plain"
        )
        val template = db.attachmentDao().getAttachmentById(imported.id)!!
        db.attachmentDao().upsert(
            template.copy(
                id = "late-row",
                recordId = rec.id,
                encryptedRelativePath = "encrypted_attachments/${stray.name}"
            )
        )

        val manifest = quarantine.quarantine(scan.candidates)
        val movedIn = manifest.entries.any { it.quarantineFileName.endsWith(stray.name) }
        note("file quarantined despite the new reference? $movedIn; file still in place = ${stray.exists()}")
        assertFalse("the move must be re-checked under the lock and skipped", movedIn)
        assertTrue("the file must still be in the media folder", stray.exists())
        dump("p6_Q7_stale_scan_recheck")
    }

    private companion object { const val PASSWORD = "P6Quar#2026" }
}
