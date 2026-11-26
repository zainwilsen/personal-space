package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.data.local.entity.ConflictRecordEntity
import app.personalspace.vault.data.local.entity.SyncDeviceEntity
import app.personalspace.vault.data.local.entity.SyncMetadataEntity
import app.personalspace.vault.data.local.entity.SyncOperationEntity
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Regression for restore-as-a-new-sync-baseline: stale TOMBSTONE operations from the replaced
 * local vault must never be uploaded, and the restored content must be queued for peer devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreSyncRebaselineTest {

    private lateinit var source: IsolatedVaultTestRig
    private lateinit var target: IsolatedVaultTestRig

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        source = IsolatedVaultTestRig(appContext, "restore_source")
        target = IsolatedVaultTestRig(appContext, "restore_target")
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun `restore clears stale sync bookkeeping and queues the restored baseline`() = runBlocking {
        source.initializeAndUnlockVault("SourcePass!1")
        val sourceRecord = source.repository.saveRecord(
            existingRecordId = null,
            title = "Restored baseline note",
            description = "from backup",
            folderId = null,
            category = "General",
            customFields = emptyList()
        )
        val backupFile = File(source.rootDir, "rebaseline.psbackup")
        source.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, backupFile)

        target.initializeAndUnlockVault("TargetPass!2")
        target.repository.saveRecord(
            existingRecordId = null,
            title = "Local only note",
            description = "must be replaced",
            folderId = null,
            category = "General",
            customFields = emptyList()
        )

        val db = target.dbHolder.getDatabase()
        val syncDao = db.syncDao()
        val oldTimestamp = target.clock.nowMillis()
        syncDao.insertOperation(
            SyncOperationEntity(
                operationId = IdGenerator.newUuid(),
                entityType = "RECORD",
                entityId = "record-that-must-not-be-deleted-on-peers",
                operationType = "TOMBSTONE",
                baseVersion = 1L,
                newVersion = 2L,
                deviceId = "old-device",
                timestampMillis = oldTimestamp,
                payloadJson = "{}"
            )
        )
        syncDao.upsertConflict(
            ConflictRecordEntity(
                conflictId = IdGenerator.newUuid(),
                entityType = "RECORD",
                entityId = "old-conflict",
                conflictKind = "CONCURRENT_EDIT",
                localTitle = "Old local",
                localPayloadJson = "{}",
                localModifiedAt = oldTimestamp,
                localDeviceId = "old-device",
                remoteTitle = "Old remote",
                remotePayloadJson = "{}",
                remoteModifiedAt = oldTimestamp,
                remoteDeviceId = "old-remote-device",
                detectedAtMillis = oldTimestamp
            )
        )
        syncDao.upsertDevice(
            SyncDeviceEntity(
                deviceId = "old-device",
                deviceName = "Old phone",
                modelName = "Test",
                registeredAtMillis = oldTimestamp,
                lastSeenSyncMillis = oldTimestamp
            )
        )
        syncDao.upsertSyncMetadata(
            SyncMetadataEntity(
                syncStatus = "SYNCED",
                lastSyncedAtMillis = oldTimestamp,
                appliedBundleIdsJson = "[\"old-bundle-id\"]",
                driveVaultFolderId = "old-folder"
            )
        )

        target.backupManager.restoreFromBackupFile(backupFile)

        val pending = syncDao.getPendingOperations()
        assertTrue("stale TOMBSTONE must be gone: $pending", pending.none { it.operationType == "TOMBSTONE" })
        assertTrue(
            "restored record must be queued as a new UPSERT baseline: $pending",
            pending.any {
                it.entityType == "RECORD" &&
                    it.entityId == sourceRecord.id &&
                    it.operationType == "UPSERT"
            }
        )

        val metadata = syncDao.getSyncMetadata()
        assertNotNull(metadata)
        assertEquals("[]", metadata!!.appliedBundleIdsJson)
        assertNull(metadata.lastSyncedAtMillis)
        assertEquals(0, syncDao.observeUnresolvedConflicts().first().size)
        assertEquals(0, syncDao.observeDevices().first().size)

        val restoredRecords = target.repository.observeAllRecords().first()
        assertEquals(1, restoredRecords.size)
        assertEquals("Restored baseline note", restoredRecords.first().title)
    }
}
