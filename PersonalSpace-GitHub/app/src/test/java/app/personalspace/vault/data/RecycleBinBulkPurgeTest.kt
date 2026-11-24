package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
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

/**
 * Item 4 — one failing item must not stop the rest.
 *
 * Before: `for (b in batches) permanentlyDeleteBatch(b.batchId)` — the first failure threw out of the
 * loop and every remaining item was left untouched with nothing reported. The user pressed "empty
 * recycle bin", the bin still had items, and there was no way to know why.
 *
 * Now the loop isolates each batch, keeps the failing one for a retry, and returns a report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecycleBinBulkPurgeTest {

    private val ev = StringBuilder()
    private lateinit var rig: IsolatedVaultTestRig
    private fun note(line: String) { ev.append(line).append('\n'); println("PURGE| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    @Before
    fun setUp() {
        runBlocking {
            rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "purge")
            rig.initializeAndUnlockVault(PASSWORD)
        }
    }

    @After
    fun tearDown() { rig.close() }

    /** Creates [n] notes with an attachment each, deletes them, then sabotages one attachment file. */
    private suspend fun seedWithOneStuckAttachment(sabotageIndex: Int): Triple<List<String>, File, String> {
        val db = rig.dbHolder.getDatabase()
        val batchIds = ArrayList<String>()
        var stuckFile: File? = null
        var stuckBatchId = ""
        for (i in 1..4) {
            val rec = rig.repository.saveRecord(null, "Note $i", "body $i", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "BYTES-$i".toByteArray().inputStream(), "file$i.txt", "text/plain"
            )
            val row = db.attachmentDao().getAttachmentById(att.id)!!
            if (i == sabotageIndex) {
                // make the unlink genuinely fail: replace the ciphertext with a non-empty directory
                val file = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)
                file.delete()
                file.mkdirs()
                File(file, "keep.bin").writeBytes(ByteArray(8))
                stuckFile = file
            }
            rig.clock.advanceBy(1_000)
            rig.repository.deleteAttachment(att.id)
            val batch = db.recycleBinDao().getAllDeletedItems()
                .first { it.rootEntityId == att.id }
            batchIds.add(batch.batchId)
            if (i == sabotageIndex) stuckBatchId = batch.batchId
        }
        return Triple(batchIds, stuckFile!!, stuckBatchId)
    }

    @Test
    fun `B1 emptying the recycle bin processes every item even when one fails`() = runBlocking {
        val (batchIds, stuckFile, stuckBatchId) = seedWithOneStuckAttachment(sabotageIndex = 2)
        val db = rig.dbHolder.getDatabase()
        note("batches before = ${db.recycleBinDao().getAllDeletedItems().size}")

        val result = rig.repository.emptyRecycleBin()
        note("result: attempted=${result.attempted} deleted=${result.deleted} " +
            "failed=${result.failed.map { it.displayTitle to it.reason.take(60) }}")

        assertEquals("every batch must be attempted", 4, result.attempted)
        assertEquals("the three healthy items must be deleted", 3, result.deleted)
        assertEquals("exactly one must be reported as failed", 1, result.failed.size)
        assertEquals("and it must be the sabotaged one", stuckBatchId, result.failed.single().batchId)
        assertTrue("the failure must carry a reason", result.failed.single().reason.isNotBlank())
        assertTrue("and it must be flagged as retained for a retry", result.failed.single().retained)
        assertFalse("allSucceeded must be false", result.allSucceeded)

        val remaining = db.recycleBinDao().getAllDeletedItems()
        note("batches remaining = ${remaining.map { it.displayTitle }}")
        assertEquals("only the failed item may remain in the bin", 1, remaining.size)
        assertEquals("and it is the sabotaged one", stuckBatchId, remaining.single().batchId)
        assertTrue("its file must still be on disk", stuckFile.exists())
        note("=> one stuck item no longer blocks the other three")
        dump("item4_B1_empty_isolates_failures")
    }

    @Test
    fun `B2 the failed item is retryable once the cause is fixed`() = runBlocking {
        val (_, stuckFile, stuckBatchId) = seedWithOneStuckAttachment(sabotageIndex = 2)
        val first = rig.repository.emptyRecycleBin()
        assertEquals(1, first.failed.size)
        note("first attempt failed on ${first.failed.single().displayTitle}")

        // fix the cause the way a user would: the path is no longer a directory
        stuckFile.deleteRecursively()
        stuckFile.writeBytes(ByteArray(64))
        note("cause fixed; retrying the same batch")

        val retry = rig.repository.emptyRecycleBin()
        note("retry: attempted=${retry.attempted} deleted=${retry.deleted} failed=${retry.failed.size}")
        assertTrue("the retry must succeed", retry.allSucceeded)
        assertEquals("and remove the previously stuck item", 1, retry.deleted)
        assertEquals("the recycle bin must now be empty", 0,
            rig.dbHolder.getDatabase().recycleBinDao().getAllDeletedItems().size)
        assertNull("and the row must be gone",
            rig.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted()
                .firstOrNull { it.id == stuckBatchId })
        dump("item4_B2_retry_after_fix")
    }

    @Test
    fun `B3 every item failing is reported individually and nothing is lost`() = runBlocking {
        val db = rig.dbHolder.getDatabase()
        // three batches, all sabotaged
        val files = ArrayList<File>()
        for (i in 1..3) {
            val rec = rig.repository.saveRecord(null, "N$i", "b", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "X$i".toByteArray().inputStream(), "x$i.txt", "text/plain"
            )
            val row = db.attachmentDao().getAttachmentById(att.id)!!
            val f = rig.attachmentStorage.resolveEncryptedFile(row.encryptedRelativePath)
            rig.clock.advanceBy(1_000)
            rig.repository.deleteAttachment(att.id)
            f.delete(); f.mkdirs(); File(f, "k").writeBytes(ByteArray(4))
            files.add(f)
        }
        val result = rig.repository.emptyRecycleBin()
        note("all-failing run: attempted=${result.attempted} deleted=${result.deleted} " +
            "failed=${result.failed.size}")
        assertEquals(3, result.attempted)
        assertEquals(0, result.deleted)
        assertEquals("every failure must be reported, not just the first", 3, result.failed.size)
        assertEquals("all three must remain in the bin", 3, db.recycleBinDao().getAllDeletedItems().size)
        assertTrue("and all three files must still exist", files.all { it.exists() })
        note("=> nothing is lost and the user sees all three problems at once")
        dump("item4_B3_all_failing_reported")
    }

    @Test
    fun `B4 expired-item purge also isolates failures`() = runBlocking {
        val db = rig.dbHolder.getDatabase()
        val ids = ArrayList<Pair<String, String>>()   // batchId to attachmentId
        for (i in 1..3) {
            val rec = rig.repository.saveRecord(null, "E$i", "b", null, null, emptyList())
            val att = rig.repository.importAttachmentFromStream(
                rec.id, "E$i".toByteArray().inputStream(), "e$i.txt", "text/plain"
            )
            rig.clock.advanceBy(1_000)
            rig.repository.deleteAttachment(att.id)
            val batch = db.recycleBinDao().getAllDeletedItems().first { it.rootEntityId == att.id }
            ids.add(batch.batchId to att.id)
        }
        // make one of them undeletable
        val victimAtt = db.attachmentDao().getAttachmentById(ids[1].second)!!
        val victimFile = rig.attachmentStorage.resolveEncryptedFile(victimAtt.encryptedRelativePath)
        victimFile.delete(); victimFile.mkdirs(); File(victimFile, "k").writeBytes(ByteArray(4))

        // age every batch past its expiry
        rig.clock.advanceBy(40L * 24L * 60L * 60L * 1000L)
        db.recycleBinDao().getAllDeletedItems().forEach { stale ->
            db.recycleBinDao().upsertAll(listOf(stale.copy(expiresAtMillis = rig.clock.nowMillis() - 1_000)))
        }

        val result = rig.repository.purgeExpiredRecycleBinItems()
        note("expired purge: attempted=${result.attempted} deleted=${result.deleted} failed=${result.failed.size}")
        assertEquals("all expired items must be attempted", 3, result.attempted)
        assertEquals("the two healthy ones must go", 2, result.deleted)
        assertEquals("the stuck one must be reported", 1, result.failed.size)
        assertEquals("and stay in the bin for a retry", 1, db.recycleBinDao().getAllDeletedItems().size)
        assertNotNull("its row must survive", db.attachmentDao().getAttachmentById(ids[1].second))
        victimFile.deleteRecursively()
        dump("item4_B4_expired_purge_isolates")
    }

    private companion object { const val PASSWORD = "PurgePass#2026" }
}
