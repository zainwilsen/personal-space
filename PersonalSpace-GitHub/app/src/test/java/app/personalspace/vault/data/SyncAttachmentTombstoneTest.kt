package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.FakeSharedDriveCloud
import app.personalspace.vault.IsolatedVaultTestRig
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Item 2 — sync must not bypass the Recycle Bin.
 *
 * The previous `ATTACHMENT` tombstone handler called `deleteEncryptedFile()` and then
 * `hardDeleteById()`: a file deleted on another device vanished here immediately, with no recycle bin
 * entry, no way to restore it, and no protection for a local edit that was in flight.
 *
 * These tests drive a real two-device sync through the fake shared cloud and assert the recovery
 * contract, not just the resulting row state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncAttachmentTombstoneTest {

    private val ev = StringBuilder()
    private lateinit var sharedCloud: FakeSharedDriveCloud
    private lateinit var deviceA: IsolatedVaultTestRig
    private lateinit var deviceB: IsolatedVaultTestRig

    private fun note(line: String) { ev.append(line).append('\n'); println("SYNCT| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        sharedCloud = FakeSharedDriveCloud()
        deviceA = IsolatedVaultTestRig(appContext, "sync_a", remoteDrive = sharedCloud)
        deviceB = IsolatedVaultTestRig(appContext, "sync_b", remoteDrive = sharedCloud)
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    private suspend fun joinBoth(password: String) {
        val initA = deviceA.initializeAndUnlockVault(password)
        deviceB.joinExistingVaultEnvelope(initA.bundle, password)
        deviceA.authManager.persistAccessToken("token-a", "user@example.com")
        deviceB.authManager.persistAccessToken("token-b", "user@example.com")
    }

    @Test
    fun `S1 a remote attachment deletion is recoverable from the Recycle Bin on this device`() {
        kotlinx.coroutines.runBlocking {
            val password = "SyncTomb#2026"
            joinBoth(password)

            // A creates a note with an attachment and syncs it to the cloud
            val rec = deviceA.repository.saveRecord(null, "Trip", "with receipt", null, null, emptyList())
            val att = deviceA.repository.importAttachmentFromStream(
                rec.id, "RECEIPT-BYTES".toByteArray().inputStream(), "receipt.txt", "text/plain"
            )
            deviceA.repository.runSyncNow()
            deviceB.repository.runSyncNow()

            val dbB = deviceB.dbHolder.getDatabase()
            val onB = dbB.attachmentDao().getAttachmentById(att.id)
            note("attachment arrived on B = ${onB != null}, isDeleted=${onB?.isDeleted}")
            assertNotNull("the attachment must reach device B", onB)
            val fileB = deviceB.attachmentStorage.resolveEncryptedFile(onB!!.encryptedRelativePath)
            assertTrue("the ciphertext must be on B", fileB.exists())

            // A deletes it, which produces a TOMBSTONE operation
            deviceA.repository.deleteAttachment(att.id)
            deviceA.repository.runSyncNow()
            val before = dbB.attachmentDao().getAttachmentById(att.id)
            note("on B before the tombstone arrives: isDeleted=${before?.isDeleted}")
            deviceB.repository.runSyncNow()

            val after = dbB.attachmentDao().getAttachmentById(att.id)
            note("on B after the tombstone: row=${after != null} isDeleted=${after?.isDeleted} " +
                "file exists=${fileB.exists()}")
            note("recycle-bin batches on B = " +
                dbB.recycleBinDao().getAllDeletedItems().map { it.rootEntityType to it.displayTitle })

            // THE CONTRACT
            assertNotNull("the row must NOT be hard-deleted — it must go to the recycle bin", after)
            assertTrue("and it must be marked deleted", after!!.isDeleted)
            assertTrue("the ciphertext must be preserved so it can be restored", fileB.exists())
            val batch = dbB.recycleBinDao().getAllDeletedItems()
                .firstOrNull { it.rootEntityType == "ATTACHMENT" && it.rootEntityId == att.id }
            assertNotNull("a recycle-bin batch must exist on B", batch)
            assertEquals("it must remember the owning note", rec.id, batch!!.parentFolderIdSnapshot)
            assertEquals("it must keep the file name for the UI", "receipt.txt", batch.displayTitle)

            // and it must actually restore, with the bytes intact
            deviceB.repository.restoreDeletedBatch(batch.batchId)
            val restored = dbB.attachmentDao().getAttachmentById(att.id)!!
            note("after restore on B: isDeleted=${restored.isDeleted}")
            assertFalse("restore must bring it back", restored.isDeleted)
            val plain = ByteArrayOutputStream()
            deviceB.attachmentStorage.decryptToOutputStream(
                app.personalspace.vault.domain.model.AttachmentItem(
                    id = restored.id, recordId = restored.recordId,
                    originalFileName = restored.originalFileName, mimeType = restored.mimeType,
                    kind = app.personalspace.vault.domain.model.AttachmentKind.OTHER,
                    plaintextSizeBytes = restored.plaintextSizeBytes,
                    encryptedSizeBytes = restored.encryptedSizeBytes,
                    encryptedRelativePath = restored.encryptedRelativePath,
                    plaintextSha256Hex = restored.plaintextSha256Hex,
                    createdAt = restored.createdAt, modifiedAt = restored.modifiedAt
                ), plain
            )
            assertEquals("THE RESTORED FILE MUST BE THE ORIGINAL BYTES",
                "RECEIPT-BYTES", String(plain.toByteArray()))
        }
        dump("item2_S1_remote_delete_is_recoverable")
    }

    @Test
    fun `S2 a remote deletion never destroys a local edit that is in flight`() {
        kotlinx.coroutines.runBlocking {
            val password = "SyncTomb2#2026"
            joinBoth(password)

            val rec = deviceA.repository.saveRecord(null, "Plan", "body", null, null, emptyList())
            val att = deviceA.repository.importAttachmentFromStream(
                rec.id, "LOCAL-COPY".toByteArray().inputStream(), "plan.txt", "text/plain"
            )
            deviceA.repository.runSyncNow()
            deviceB.repository.runSyncNow()

            val dbB = deviceB.dbHolder.getDatabase()
            val onB = dbB.attachmentDao().getAttachmentById(att.id)!!
            val fileB = deviceB.attachmentStorage.resolveEncryptedFile(onB.encryptedRelativePath)
            assertTrue(fileB.exists())

            // Device B has a pending local change for this attachment (syncPending = true) while the
            // deletion arrives from A. The old handler destroyed it regardless.
            dbB.attachmentDao().upsert(onB.copy(syncPending = true, syncVersion = onB.syncVersion + 5))

            deviceA.repository.deleteAttachment(att.id)
            deviceA.repository.runSyncNow()
            deviceB.repository.runSyncNow()

            val afterB = dbB.attachmentDao().getAttachmentById(att.id)
            val conflicts = dbB.syncDao().observeUnresolvedConflicts().first()
            note("on B after the racing tombstone: row=${afterB != null} isDeleted=${afterB?.isDeleted} " +
                "syncPending=${afterB?.syncPending} file=${fileB.exists()}")
            note("conflicts on B = " + conflicts.map { it.entityType + "/" + it.conflictKind })

            assertNotNull("the locally edited attachment must not be destroyed", afterB)
            assertTrue("its bytes must still be there", fileB.exists())
            assertTrue("a DELETE_VS_EDIT conflict must be recorded so the user can choose",
                conflicts.any { it.entityType == "ATTACHMENT" && it.conflictKind == "DELETE_VS_EDIT" })
            note("=> the local version survives and the user decides")
        }
        dump("item2_S2_local_edit_protected")
    }

    @Test
    fun `S3 the recycle-bin entry from sync can be permanently deleted through the normal path`() =
        kotlinx.coroutines.runBlocking {
            val password = "SyncTomb3#2026"
            joinBoth(password)
            val rec = deviceA.repository.saveRecord(null, "Doc", "body", null, null, emptyList())
            val att = deviceA.repository.importAttachmentFromStream(
                rec.id, "PERM-DELETE".toByteArray().inputStream(), "doc.txt", "text/plain"
            )
            deviceA.repository.runSyncNow()
            deviceB.repository.runSyncNow()

            val dbB = deviceB.dbHolder.getDatabase()
            val onB = dbB.attachmentDao().getAttachmentById(att.id)!!
            val fileB = deviceB.attachmentStorage.resolveEncryptedFile(onB.encryptedRelativePath)

            deviceA.repository.deleteAttachment(att.id)
            deviceA.repository.runSyncNow()
            deviceB.repository.runSyncNow()

            val batch = dbB.recycleBinDao().getAllDeletedItems()
                .first { it.rootEntityType == "ATTACHMENT" && it.rootEntityId == att.id }
            deviceB.repository.permanentlyDeleteBatch(batch.batchId)

            note("after permanent delete: row=${dbB.attachmentDao().getAttachmentById(att.id)} " +
                "file=${fileB.exists()}")
            assertNull("the normal permanent-delete path must remove the row", dbB.attachmentDao().getAttachmentById(att.id))
            assertFalse("and the bytes", fileB.exists())
            assertTrue("the batch must be gone", dbB.recycleBinDao().getAllDeletedItems()
                .none { it.batchId == batch.batchId })
            note("=> sync-deleted content follows exactly the same lifecycle as locally deleted content")
            dump("item2_S3_permanent_delete_path")
        }
}
