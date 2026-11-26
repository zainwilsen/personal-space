package app.personalspace.vault.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.entity.BackupMetadataEntity
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.domain.model.SyncHealthState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class IncrementalSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: MultiDeviceSyncEngine,
    private val sessionManager: VaultSessionManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) {
            return Result.retry()
        }
        val report = syncEngine.runSyncCycle()
        return when (report.finalState) {
            SyncHealthState.FAILED -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}

@HiltWorker
class ScheduledDailyBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: VaultSessionManager,
    private val backupManager: BackupPackageManager,
    private val authManager: GoogleDriveAuthManager,
    private val remoteDataSource: DriveRemoteDataSource,
    private val dbHolder: VaultDatabaseHolder,
    private val timeProvider: TimeProvider
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) {
            // Defer cleanly until vault is next unlocked; never report a fake completed backup
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        val storedSession = authManager.getCurrentSession()
        if (storedSession == null || storedSession.accessToken.isBlank()) {
            // Not connected: nothing to upload, and a success would be a lie
            return Result.success()
        }
        // Refresh a token that is about to expire instead of uploading with a dead one for the
        // rest of the day (the cached `expiresAtMillis` used to be written but never read).
        val session = authManager.getValidSession() ?: return Result.success()
        return try {
            val fingerprint = backupManager.computeVaultFingerprint()
            if (fingerprint == backupManager.lastBackupFingerprint()) {
                return Result.success()
            }
            val created = backupManager.createFullBackupFile(BackupKind.FULL_SCHEDULED, recordHistoryInDb = false)
            val uploaded = remoteDataSource.uploadOrReplaceInAppData(
                accessToken = session.accessToken,
                remoteName = BackupPackageManager.DRIVE_BACKUP_NAME,
                localCiphertextFile = created.file
            )
            dbHolder.getDatabase().backupDao().upsert(
                BackupMetadataEntity(
                    backupId = created.backupId,
                    backupKind = BackupKind.FULL_SCHEDULED.name,
                    destination = "GOOGLE_DRIVE",
                    locationRef = uploaded.id,
                    sizeBytes = created.sizeBytes,
                    recordCount = created.recordCount,
                    attachmentCount = created.attachmentCount,
                    sha256Checksum = created.sha256Hex,
                    verified = true,
                    succeeded = true,
                    createdAtMillis = timeProvider.nowMillis()
                )
            )
            Result.success()
        } catch (expired: DriveAuthExpiredException) {
            // A dead OAuth token will never succeed on retry. Record a visible failure so the
            // backup history cannot pretend the daily backup ran, and tell the user what to do.
            recordFailedBackup(
                "Google Drive authorization expired. Reconnect Drive in Settings to resume daily backups."
            )
            Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun recordFailedBackup(message: String) {
        try {
            dbHolder.getDatabase().backupDao().upsert(
                BackupMetadataEntity(
                    backupId = IdGenerator.newUuid(),
                    backupKind = BackupKind.FULL_SCHEDULED.name,
                    destination = "GOOGLE_DRIVE",
                    locationRef = "",
                    sizeBytes = 0L,
                    recordCount = 0,
                    attachmentCount = 0,
                    sha256Checksum = "",
                    verified = false,
                    succeeded = false,
                    errorMessage = message,
                    createdAtMillis = timeProvider.nowMillis()
                )
            )
        } catch (_: Exception) {
            // Never let a failed audit row mask the original authorization failure.
        }
    }
}

@HiltWorker
class RecycleBinExpiryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: VaultSessionManager,
    private val dbHolder: VaultDatabaseHolder,
    private val attachmentStorage: EncryptedAttachmentStorage,
    private val timeProvider: TimeProvider
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) {
            return Result.success()
        }
        val db = dbHolder.getDatabase()
        val expired = db.recycleBinDao().getExpiredBatches(timeProvider.nowMillis())
        for (batch in expired) {
            val records = db.recordDao().getRecordsByDeletedBatch(batch.batchId)
            val recordIds = records.map { it.id }
            if (recordIds.isNotEmpty()) {
                val attachments = db.attachmentDao().getAttachmentsForRecords(recordIds)
                attachments.forEach { attachmentStorage.deleteEncryptedFile(it.encryptedRelativePath) }
                db.recordDao().hardDeleteByIds(recordIds)
            }
            val folders = db.folderDao().getFoldersByDeletedBatch(batch.batchId)
            if (folders.isNotEmpty()) {
                db.folderDao().hardDeleteByIds(folders.map { it.id })
            }
            db.recycleBinDao().deleteBatch(batch.batchId)
        }
        return Result.success()
    }
}

@Singleton
class BackgroundWorkCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val INCREMENTAL_SYNC_WORK = "personal_space_incremental_sync"
        private const val DAILY_BACKUP_WORK = "personal_space_daily_full_backup"
        private const val RECYCLE_EXPIRY_WORK = "personal_space_recycle_bin_expiry"
    }

    private val workManager: WorkManager?
        get() = runCatching { WorkManager.getInstance(context) }.getOrNull()

    fun enqueueIncrementalSyncWhenConnected() {
        val wm = workManager ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<IncrementalSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(INCREMENTAL_SYNC_WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun configureDailyBackupSchedule(enabled: Boolean, hour24: Int, minute: Int) {
        val wm = workManager ?: return
        if (!enabled) {
            wm.cancelUniqueWork(DAILY_BACKUP_WORK)
            return
        }
        val nowZoned = ZonedDateTime.now(timeProvider.zoneId())
        var target = nowZoned.withHour(hour24.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        if (!target.isAfter(nowZoned)) {
            target = target.plusDays(1)
        }
        val initialDelayMinutes = Duration.between(nowZoned, target).toMinutes().coerceAtLeast(1L)

        val req = PeriodicWorkRequestBuilder<ScheduledDailyBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        wm.enqueueUniquePeriodicWork(DAILY_BACKUP_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun ensureRecycleBinMaintenanceScheduled() {
        val wm = workManager ?: return
        val req = PeriodicWorkRequestBuilder<RecycleBinExpiryWorker>(12, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(RECYCLE_EXPIRY_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
