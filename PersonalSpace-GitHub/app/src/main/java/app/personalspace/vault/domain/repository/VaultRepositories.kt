package app.personalspace.vault.domain.repository

import android.net.Uri
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.data.sync.SyncExecutionReport
import app.personalspace.vault.domain.model.AttachmentItem
import app.personalspace.vault.domain.model.BackupHistoryItem
import app.personalspace.vault.domain.model.DriveBackupFile
import app.personalspace.vault.domain.model.ConflictResolutionChoice
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.DashboardOverview
import app.personalspace.vault.domain.model.DeletedVaultItem
import app.personalspace.vault.domain.model.Folder
import app.personalspace.vault.domain.model.RecordDraft
import app.personalspace.vault.domain.model.RecordItem
import app.personalspace.vault.domain.model.RecordVersion
import app.personalspace.vault.domain.model.RecurrenceType
import app.personalspace.vault.domain.model.ReminderItem
import app.personalspace.vault.domain.model.ReminderOccurrence
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.SearchFilters
import app.personalspace.vault.domain.model.SearchResultsBundle
import app.personalspace.vault.domain.model.SyncConflictItem
import app.personalspace.vault.domain.model.SyncHealthState
import app.personalspace.vault.domain.model.TimelineEvent
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.domain.model.VaultStatisticsOverview
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import app.personalspace.vault.data.backup.BackupRestoreResult

/**
 * Outcome of a bulk recycle-bin operation.
 *
 * A single undeletable item used to abort the whole loop, so one stuck file silently prevented every
 * remaining item from being handled and gave the user no way to see why. Now the loop always visits
 * everything and reports what failed, and failed items are deliberately KEPT (never removed from the
 * recycle bin) so the user can retry after fixing the cause.
 */
data class RecycleBinPurgeResult(
    val attempted: Int,
    val deleted: Int,
    val failed: List<FailedBatch>
) {
    val allSucceeded: Boolean get() = failed.isEmpty()

    data class FailedBatch(
        val batchId: String,
        val displayTitle: String,
        val rootEntityType: String,
        val reason: String,
        /** true when the item is still in the recycle bin and can be retried */
        val retained: Boolean = true
    )
}

interface PersonalSpaceRepository {
    // Startup Warm Preloading & Instant Initial Snapshots
    suspend fun preloadStartupData() {}
    fun latestCachedDashboardOverview(): DashboardOverview? = null
    fun latestCachedFolders(): List<Folder>? = null
    fun latestCachedRecords(): List<RecordItem>? = null
    fun latestCachedRecordById(recordId: String): RecordItem? = null
    /** Drop warmed snapshots after a vault restore or lock; callers must re-observe fresh data. */
    fun invalidateCaches() {}

    // Dashboard & Statistics
    fun observeDashboardOverview(): Flow<DashboardOverview>
    fun observeVaultStatistics(): Flow<VaultStatisticsOverview>

    // Folders
    fun observeAllFolders(): Flow<List<Folder>>
    fun observeSubfolders(parentFolderId: String?): Flow<List<Folder>>
    suspend fun getFolderById(folderId: String): Folder?
    suspend fun getFolderBreadcrumbs(folderId: String?): List<Folder>
    suspend fun createFolder(name: String, parentFolderId: String?, color: FolderAccentColor): Folder
    suspend fun renameFolder(folderId: String, newName: String, color: FolderAccentColor)
    suspend fun moveFolder(folderId: String, newParentFolderId: String?)
    suspend fun softDeleteFolder(folderId: String)

    // Records, Drafts & Versions
    fun observeAllRecords(): Flow<List<RecordItem>>
    fun observeRecordsInFolder(folderId: String): Flow<List<RecordItem>>
    fun observeRecordById(recordId: String): Flow<RecordItem?>
    suspend fun getRecordById(recordId: String): RecordItem?
    fun observeDistinctCategories(): Flow<List<String>>
    suspend fun saveRecord(
        existingRecordId: String?,
        title: String,
        description: String,
        folderId: String?,
        category: String?,
        customFields: List<CustomField>,
        updateCurrentVersionInPlace: Boolean = false
    ): RecordItem
    suspend fun renameRecordTitle(recordId: String, newTitle: String): RecordItem
    suspend fun duplicateRecord(recordId: String): RecordItem
    suspend fun moveRecord(recordId: String, targetFolderId: String?)
    suspend fun softDeleteRecord(recordId: String)

    suspend fun getDraft(draftKey: String): RecordDraft?
    suspend fun saveDraft(draft: RecordDraft)
    suspend fun clearDraft(draftKey: String)

    fun observeRecordVersions(recordId: String): Flow<List<RecordVersion>>
    suspend fun restoreRecordVersion(recordId: String, versionId: String): RecordItem

    // Attachments
    fun observeAttachmentsForRecord(recordId: String): Flow<List<AttachmentItem>>
    suspend fun getAttachmentById(attachmentId: String): AttachmentItem?
    suspend fun importAttachmentFromUri(recordId: String, uri: Uri): AttachmentItem
    suspend fun importAttachmentFromStream(
        recordId: String,
        input: InputStream,
        fileName: String,
        mimeType: String
    ): AttachmentItem
    suspend fun exportAttachmentToStream(attachmentId: String, output: OutputStream)
    suspend fun prepareEphemeralViewerFile(attachmentId: String): File
    suspend fun deleteAttachment(attachmentId: String)

    /**
     * P6: removes an inline image from the note being edited, reference-counted.
     *
     * The caller has already dropped the image block from its in-memory body; this decides whether
     * the *file* may go. It may not while any other note, recycle-bin note or unsaved draft still
     * references the same image identifier (e.g. a note created by duplicateRecord()).
     *
     * @param editingRecordId the note being edited (its current, still-saved body is ignored when
     *   counting, because the user is in the middle of removing the reference)
     * @return true when the file was removed, false when it was kept because other entities need it
     */
    suspend fun deleteInlineImageIfUnreferenced(imagePath: String, editingRecordId: String?): Boolean

    // Offline Global Search
    suspend fun searchVault(filters: SearchFilters): SearchResultsBundle

    // Reminders & Calendar
    fun observeAllReminders(): Flow<List<ReminderItem>>
    fun observeReminderOccurrences(): Flow<List<ReminderOccurrence>>
    suspend fun getReminderById(reminderId: String): ReminderItem?
    suspend fun saveReminder(
        existingReminderId: String?,
        title: String,
        notes: String,
        scheduledAtMillis: Long,
        recurrence: RecurrenceType,
        priority: ReminderPriority,
        linkedRecordId: String?
    ): Pair<ReminderItem, Boolean> // Reminder + whether exact alarm was granted
    suspend fun completeReminder(reminderId: String)
    suspend fun snoozeReminder(reminderId: String, snoozeMinutes: Int)
    suspend fun refreshMissedReminders()
    suspend fun deleteReminder(reminderId: String)

    // Timeline
    fun observeTimelineEvents(): Flow<List<TimelineEvent>>

    // Recycle Bin
    fun observeDeletedItems(): Flow<List<DeletedVaultItem>>
    suspend fun restoreDeletedBatch(batchId: String)
    suspend fun permanentlyDeleteBatch(batchId: String)
    suspend fun emptyRecycleBin(): RecycleBinPurgeResult
    suspend fun purgeExpiredRecycleBinItems(): RecycleBinPurgeResult

    // Backup, Restore & Multi-Device Sync
    fun observeSyncHealthState(): Flow<SyncHealthState>
    fun observeBackupHistory(): Flow<List<BackupHistoryItem>>
    fun observeUnresolvedConflicts(): Flow<List<SyncConflictItem>>
    suspend fun createManualLocalBackup(destinationStream: OutputStream? = null): BackupHistoryItem
    suspend fun uploadManualBackupToDrive(): BackupHistoryItem
    suspend fun listDriveBackups(): List<DriveBackupFile>
    suspend fun restoreFromDriveBackup(fileId: String, recoveryPassword: CharArray? = null): BackupRestoreResult
    suspend fun pruneDriveBackups(keep: Int = 5): Int
    suspend fun verifyBackupFile(file: File): Boolean
    suspend fun runSyncNow(): SyncExecutionReport
    suspend fun resolveConflict(
        conflictId: String,
        choice: ConflictResolutionChoice,
        mergedTitle: String? = null,
        mergedDescription: String? = null
    )

    // Settings
    fun observeUserSettings(): Flow<UserSettings>
}
