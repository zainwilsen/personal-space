package app.personalspace.vault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.personalspace.vault.data.local.entity.AttachmentEntity
import app.personalspace.vault.data.local.entity.BackupMetadataEntity
import app.personalspace.vault.data.local.entity.ConflictRecordEntity
import app.personalspace.vault.data.local.entity.DeletedItemEntity
import app.personalspace.vault.data.local.entity.FolderEntity
import app.personalspace.vault.data.local.entity.RecordDraftEntity
import app.personalspace.vault.data.local.entity.RecordEntity
import app.personalspace.vault.data.local.entity.RecordVersionEntity
import app.personalspace.vault.data.local.entity.ReminderEntity
import app.personalspace.vault.data.local.entity.ReminderOccurrenceEntity
import app.personalspace.vault.data.local.entity.SyncDeviceEntity
import app.personalspace.vault.data.local.entity.SyncMetadataEntity
import app.personalspace.vault.data.local.entity.SyncOperationEntity
import app.personalspace.vault.data.local.entity.TimelineEventEntity
import app.personalspace.vault.data.local.entity.VaultMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultMetadataDao {
    @Upsert
    suspend fun upsert(metadata: VaultMetadataEntity)

    @Query("SELECT * FROM vault_metadata LIMIT 1")
    suspend fun getMetadata(): VaultMetadataEntity?
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE isDeleted = 0 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeActiveFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE isDeleted = 0 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun getAllActiveFolders(): List<FolderEntity>

    @Query("SELECT * FROM folders")
    suspend fun getAllFoldersIncludingDeleted(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :folderId LIMIT 1")
    suspend fun getFolderById(folderId: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE parentFolderId = :parentId AND isDeleted = 0 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeSubfolders(parentId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentFolderId IS NULL AND isDeleted = 0 ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeRootFolders(): Flow<List<FolderEntity>>

    @Query("SELECT COUNT(*) FROM folders WHERE isDeleted = 0")
    fun observeActiveFolderCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM folders WHERE isDeleted = 0")
    suspend fun getActiveFolderCount(): Int

    @Query("SELECT * FROM folders WHERE isDeleted = 0 AND name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE ASC")
    suspend fun searchFoldersByName(query: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE deletedBatchId = :batchId")
    suspend fun getFoldersByDeletedBatch(batchId: String): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Upsert
    suspend fun upsertAll(folders: List<FolderEntity>)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()

    @Query("DELETE FROM folders WHERE id IN (:folderIds)")
    suspend fun hardDeleteByIds(folderIds: List<String>)
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE isDeleted = 0 ORDER BY modifiedAt DESC")
    fun observeAllActiveRecords(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE isDeleted = 0 ORDER BY modifiedAt DESC LIMIT :limit")
    fun observeRecentRecords(limit: Int): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE isDeleted = 0 ORDER BY modifiedAt DESC")
    suspend fun getAllActiveRecords(): List<RecordEntity>

    @Query("SELECT * FROM records")
    suspend fun getAllRecordsIncludingDeleted(): List<RecordEntity>

    @Query("SELECT * FROM records WHERE folderId = :folderId AND isDeleted = 0 ORDER BY modifiedAt DESC")
    fun observeRecordsInFolder(folderId: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE folderId IN (:folderIds) AND isDeleted = 0")
    suspend fun getActiveRecordsInFolders(folderIds: List<String>): List<RecordEntity>

    @Query("SELECT * FROM records WHERE id = :recordId LIMIT 1")
    fun observeRecordById(recordId: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :recordId LIMIT 1")
    suspend fun getRecordById(recordId: String): RecordEntity?

    @Query("SELECT COUNT(*) FROM records WHERE isDeleted = 0")
    fun observeActiveRecordCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM records WHERE isDeleted = 0")
    suspend fun getActiveRecordCount(): Int

    @Query("SELECT DISTINCT category FROM records WHERE isDeleted = 0 AND category IS NOT NULL AND category != '' ORDER BY category COLLATE NOCASE ASC")
    fun observeDistinctCategories(): Flow<List<String>>

    @Query(
        """
        SELECT records.* FROM records
        JOIN records_fts ON records.rowid = records_fts.rowid
        WHERE records.isDeleted = 0 AND records_fts MATCH :ftsQuery
        """
    )
    suspend fun searchRecordsFts(ftsQuery: String): List<RecordEntity>

    @Query(
        """
        SELECT * FROM records
        WHERE isDeleted = 0 AND (
            title LIKE '%' || :rawQuery || '%'
            OR description LIKE '%' || :rawQuery || '%'
            OR COALESCE(category, '') LIKE '%' || :rawQuery || '%'
            OR customFieldsJson LIKE '%' || :rawQuery || '%'
        )
        """
    )
    suspend fun searchRecordsSubstring(rawQuery: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE deletedBatchId = :batchId")
    suspend fun getRecordsByDeletedBatch(batchId: String): List<RecordEntity>

    @Upsert
    suspend fun upsert(record: RecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<RecordEntity>)

    @Query("DELETE FROM records")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM record_versions")
    suspend fun deleteAllVersions()

    @Query("DELETE FROM records WHERE id IN (:recordIds)")
    suspend fun hardDeleteByIds(recordIds: List<String>)

    // Version History
    @Query("DELETE FROM record_versions WHERE recordId = :recordId AND versionNumber <= :keepBelow")
    suspend fun deleteVersionsAtOrBelow(recordId: String, keepBelow: Int)

    @Query("SELECT * FROM record_versions WHERE recordId = :recordId ORDER BY versionNumber DESC")
    fun observeVersionsForRecord(recordId: String): Flow<List<RecordVersionEntity>>

    @Query("SELECT * FROM record_versions WHERE recordId = :recordId ORDER BY versionNumber DESC")
    suspend fun getVersionsForRecord(recordId: String): List<RecordVersionEntity>

    @Query("SELECT * FROM record_versions")
    suspend fun getAllVersions(): List<RecordVersionEntity>

    @Query("SELECT * FROM record_versions WHERE id = :versionId LIMIT 1")
    suspend fun getVersionById(versionId: String): RecordVersionEntity?

    @Upsert
    suspend fun insertVersion(version: RecordVersionEntity)

    @Upsert
    suspend fun insertVersions(versions: List<RecordVersionEntity>)

    // Draft persistence
    @Query("SELECT * FROM record_drafts WHERE draftKey = :draftKey LIMIT 1")
    suspend fun getDraft(draftKey: String): RecordDraftEntity?

    @Upsert
    suspend fun saveDraft(draft: RecordDraftEntity)

    @Query("SELECT * FROM record_drafts")
    suspend fun getAllDrafts(): List<RecordDraftEntity>

    @Query("DELETE FROM record_drafts")
    suspend fun deleteAllDrafts()

    @Query("DELETE FROM record_drafts WHERE draftKey = :draftKey")
    suspend fun deleteDraft(draftKey: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE recordId = :recordId AND isDeleted = 0 ORDER BY createdAt ASC")
    fun observeAttachmentsForRecord(recordId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE recordId = :recordId AND isDeleted = 0 ORDER BY createdAt ASC")
    suspend fun getAttachmentsForRecord(recordId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE isDeleted = 0")
    fun observeAllActiveAttachments(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE isDeleted = 0")
    suspend fun getAllActiveAttachments(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments")
    suspend fun getAllAttachmentsIncludingDeleted(): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :attachmentId LIMIT 1")
    suspend fun getAttachmentById(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE recordId IN (:recordIds)")
    suspend fun getAttachmentsForRecords(recordIds: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE isDeleted = 0 AND (originalFileName LIKE '%' || :query || '%' OR mimeType LIKE '%' || :query || '%')")
    suspend fun searchAttachments(query: String): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun hardDeleteById(attachmentId: String)

    @Query("DELETE FROM attachments")
    suspend fun deleteAllAttachments()

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun hardDeleteByIds(ids: List<String>)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE isDeleted = 0 ORDER BY scheduledAtMillis ASC")
    fun observeAllActiveReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isDeleted = 0 ORDER BY scheduledAtMillis ASC")
    suspend fun getAllActiveReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun getAllRemindersIncludingDeleted(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :reminderId LIMIT 1")
    suspend fun getReminderById(reminderId: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE id = :reminderId LIMIT 1")
    fun observeReminderById(reminderId: String): Flow<ReminderEntity?>

    @Query("SELECT COUNT(*) FROM reminders WHERE isDeleted = 0 AND status IN ('PENDING', 'SNOOZED')")
    fun observePendingReminderCount(): Flow<Int>

    @Query("SELECT * FROM reminders WHERE isDeleted = 0 AND status IN ('PENDING', 'SNOOZED') ORDER BY COALESCE(snoozedUntilMillis, scheduledAtMillis) ASC LIMIT :limit")
    fun observeUpcomingReminders(limit: Int): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder_occurrences ORDER BY actualHandledMillis DESC")
    fun observeAllOccurrences(): Flow<List<ReminderOccurrenceEntity>>

    @Query("SELECT * FROM reminder_occurrences")
    suspend fun getAllOccurrences(): List<ReminderOccurrenceEntity>

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Upsert
    suspend fun upsertAll(reminders: List<ReminderEntity>)

    @Upsert
    suspend fun insertOccurrence(occurrence: ReminderOccurrenceEntity)

    @Upsert
    suspend fun insertOccurrences(occurrences: List<ReminderOccurrenceEntity>)

    @Query("DELETE FROM reminders")
    suspend fun deleteAllReminders()

    @Query("DELETE FROM reminder_occurrences")
    suspend fun deleteAllOccurrences()

    @Query("DELETE FROM reminders WHERE id = :reminderId")
    suspend fun hardDeleteById(reminderId: String)
}

@Dao
interface TimelineDao {
    @Query("SELECT * FROM timeline_events ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM timeline_events ORDER BY timestampMillis DESC")
    fun observeAllEvents(): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM timeline_events ORDER BY timestampMillis DESC")
    suspend fun getAllEvents(): List<TimelineEventEntity>

    @Query("DELETE FROM timeline_events")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: TimelineEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<TimelineEventEntity>)
}

@Dao
interface RecycleBinDao {
    @Query("SELECT * FROM deleted_items ORDER BY deletedAtMillis DESC")
    fun observeDeletedItems(): Flow<List<DeletedItemEntity>>

    @Query("SELECT * FROM deleted_items ORDER BY deletedAtMillis DESC")
    suspend fun getAllDeletedItems(): List<DeletedItemEntity>

    @Query("SELECT * FROM deleted_items WHERE batchId = :batchId LIMIT 1")
    suspend fun getDeletedBatch(batchId: String): DeletedItemEntity?

    @Query("SELECT * FROM deleted_items WHERE expiresAtMillis <= :nowMillis")
    suspend fun getExpiredBatches(nowMillis: Long): List<DeletedItemEntity>

    @Upsert
    suspend fun upsert(item: DeletedItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<DeletedItemEntity>)

    @Query("DELETE FROM deleted_items WHERE batchId = :batchId")
    suspend fun deleteBatch(batchId: String)

    /**
     * P6: when records are permanently deleted, any standalone attachment batches of those records
     * become meaningless (their `parentFolderIdSnapshot` holds the record id) and must not linger as
     * phantom entries in the recycle bin. Matched by the snapshot column, so no schema change.
     */
    @Query("DELETE FROM deleted_items WHERE rootEntityType = 'ATTACHMENT' AND parentFolderIdSnapshot IN (:recordIds)")
    suspend fun deleteAttachmentBatchesForRecords(recordIds: List<String>)

    @Query("SELECT * FROM deleted_items WHERE rootEntityType = 'ATTACHMENT' AND parentFolderIdSnapshot = :recordId")
    suspend fun getAttachmentBatchesForRecord(recordId: String): List<DeletedItemEntity>

    @Query("DELETE FROM deleted_items")
    suspend fun clearAllBatches()
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_metadata WHERE id = 1 LIMIT 1")
    fun observeSyncMetadata(): Flow<SyncMetadataEntity?>

    @Query("SELECT * FROM sync_metadata WHERE id = 1 LIMIT 1")
    suspend fun getSyncMetadata(): SyncMetadataEntity?

    @Upsert
    suspend fun upsertSyncMetadata(metadata: SyncMetadataEntity)

    @Upsert
    suspend fun upsertDevice(device: SyncDeviceEntity)

    @Query("SELECT * FROM sync_devices ORDER BY lastSeenSyncMillis DESC")
    fun observeDevices(): Flow<List<SyncDeviceEntity>>

    @Upsert
    suspend fun insertOperation(op: SyncOperationEntity)

    @Query("SELECT * FROM sync_operations WHERE bundledInChangeId IS NULL ORDER BY timestampMillis ASC")
    suspend fun getPendingOperations(): List<SyncOperationEntity>

    @Query("SELECT COUNT(*) FROM sync_operations WHERE bundledInChangeId IS NULL")
    fun observePendingOperationCount(): Flow<Int>

    @Query("DELETE FROM sync_operations WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOperationsOlderThan(cutoffMillis: Long): Int

    @Query("UPDATE sync_operations SET bundledInChangeId = :bundleId WHERE operationId IN (:opIds)")
    suspend fun markOperationsBundled(opIds: List<String>, bundleId: String)

    @Query("SELECT * FROM conflict_records WHERE resolvedAtMillis IS NULL ORDER BY detectedAtMillis DESC")
    fun observeUnresolvedConflicts(): Flow<List<ConflictRecordEntity>>

    @Query("SELECT COUNT(*) FROM conflict_records WHERE resolvedAtMillis IS NULL")
    fun observeUnresolvedConflictCount(): Flow<Int>

    @Query("SELECT * FROM conflict_records WHERE conflictId = :conflictId LIMIT 1")
    suspend fun getConflictById(conflictId: String): ConflictRecordEntity?

    @Query("SELECT * FROM conflict_records WHERE entityType = :entityType AND entityId = :entityId AND resolvedAtMillis IS NULL LIMIT 1")
    suspend fun getUnresolvedConflictForEntity(entityType: String, entityId: String): ConflictRecordEntity?

    @Upsert
    suspend fun upsertConflict(conflict: ConflictRecordEntity)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_metadata ORDER BY createdAtMillis DESC")
    fun observeBackupHistory(): Flow<List<BackupMetadataEntity>>

    @Query("SELECT * FROM backup_metadata WHERE succeeded = 1 ORDER BY createdAtMillis DESC LIMIT 1")
    fun observeLatestSuccessfulBackup(): Flow<BackupMetadataEntity?>

    @Query("SELECT * FROM backup_metadata ORDER BY createdAtMillis DESC")
    suspend fun getAllBackups(): List<BackupMetadataEntity>

    @Upsert
    suspend fun upsert(entry: BackupMetadataEntity)
}
