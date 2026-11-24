package app.personalspace.vault.domain.model

import androidx.compose.runtime.Immutable

import app.personalspace.vault.core.designsystem.FolderAccentColor
import kotlinx.serialization.Serializable

@Serializable
enum class CustomFieldType {
    TEXT,
    NUMBER,
    DATE,
    BOOLEAN
}

@Serializable
@Immutable
data class CustomField(
    val id: String,
    val label: String,
    val type: CustomFieldType,
    val value: String
)

@Serializable
@Immutable
data class Folder(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val color: FolderAccentColor = FolderAccentColor.YELLOW,
    val createdAt: Long,
    val modifiedAt: Long,
    val sortOrder: Int = 0,
    val recordCount: Int = 0,
    val subfolderCount: Int = 0,
    val syncVersion: Long = 1L
)

@Serializable
@Immutable
data class RecordItem(
    val id: String,
    val title: String,
    val description: String,
    val folderId: String? = null,
    val folderName: String? = null,
    val category: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val customFields: List<CustomField> = emptyList(),
    val currentVersionNumber: Int = 1,
    val currentVersionId: String,
    val attachmentCount: Int = 0,
    val syncVersion: Long = 1L
)

@Serializable
@Immutable
data class RecordDraft(
    val draftKey: String,
    val folderId: String? = null,
    val title: String,
    val description: String,
    val category: String? = null,
    val customFields: List<CustomField> = emptyList(),
    val updatedAt: Long
)

@Serializable
@Immutable
data class RecordVersion(
    val id: String,
    val recordId: String,
    val versionNumber: Int,
    val title: String,
    val description: String,
    val category: String? = null,
    val customFields: List<CustomField> = emptyList(),
    val createdAt: Long,
    val createdByDeviceId: String,
    val changeSummary: String
)

@Serializable
enum class AttachmentKind {
    IMAGE,
    PDF,
    DOCUMENT,
    AUDIO,
    OTHER;

    companion object {
        fun fromMimeType(mimeType: String, fileName: String): AttachmentKind {
            val lowerMime = mimeType.lowercase()
            val lowerName = fileName.lowercase()
            return when {
                lowerMime.startsWith("image/") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") -> IMAGE
                lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> PDF
                lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") -> AUDIO
                lowerMime.startsWith("text/") || lowerMime.contains("document") || lowerMime.contains("word") || lowerMime.contains("sheet") || lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".docx") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

@Serializable
@Immutable
data class AttachmentItem(
    val id: String,
    val recordId: String,
    val originalFileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val plaintextSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val encryptedRelativePath: String,
    val plaintextSha256Hex: String,
    val createdAt: Long,
    val modifiedAt: Long
)

@Serializable
enum class RecurrenceType(val label: String) {
    ONCE("One-time"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly")
}

@Serializable
enum class ReminderPriority(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}

@Serializable
enum class ReminderStatus(val label: String) {
    PENDING("Pending"),
    SNOOZED("Snoozed"),
    MISSED("Missed"),
    COMPLETED("Completed")
}

@Serializable
@Immutable
data class ReminderItem(
    val id: String,
    val title: String,
    val notes: String = "",
    val scheduledAtMillis: Long,
    val recurrence: RecurrenceType = RecurrenceType.ONCE,
    val priority: ReminderPriority = ReminderPriority.MEDIUM,
    val status: ReminderStatus = ReminderStatus.PENDING,
    val snoozedUntilMillis: Long? = null,
    val linkedRecordId: String? = null,
    val linkedRecordTitle: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val syncVersion: Long = 1L
) {
    val effectiveTriggerMillis: Long
        get() = if (status == ReminderStatus.SNOOZED && snoozedUntilMillis != null) {
            snoozedUntilMillis
        } else {
            scheduledAtMillis
        }
}

@Serializable
@Immutable
data class ReminderOccurrence(
    val id: String,
    val reminderId: String,
    val reminderTitle: String,
    val occurrenceScheduledMillis: Long,
    val actualHandledMillis: Long,
    val outcome: ReminderStatus
)

@Serializable
enum class TimelineEventType(val label: String) {
    RECORD_CREATED("Note created"),
    RECORD_UPDATED("Note updated"),
    RECORD_DELETED("Note moved to Recycle Bin"),
    RECORD_RESTORED("Note restored"),
    VERSION_RESTORED("Previous version restored"),
    FOLDER_CREATED("Folder created"),
    FOLDER_UPDATED("Folder updated"),
    FOLDER_DELETED("Folder moved to Recycle Bin"),
    FOLDER_RESTORED("Folder restored"),
    REMINDER_COMPLETED("Reminder completed"),
    BACKUP_COMPLETED("Encrypted backup created"),
    SYNC_COMPLETED("Vault synchronized")
}

@Serializable
@Immutable
data class TimelineEvent(
    val id: String,
    val eventType: TimelineEventType,
    val entityType: String,
    val entityId: String?,
    val displayTitle: String,
    val accentColor: String,
    val timestampMillis: Long
)

@Serializable
@Immutable
data class DeletedVaultItem(
    val batchId: String,
    val rootEntityType: String, // "FOLDER" or "RECORD"
    val rootEntityId: String,
    val displayTitle: String,
    val subfolderCount: Int,
    val recordCount: Int,
    val deletedAtMillis: Long,
    val expiresAtMillis: Long
)

@Serializable
enum class SyncHealthState(val displayLabel: String) {
    NOT_CONNECTED("Not Connected"),
    READY("Ready"),
    SYNCING("Syncing"),
    SYNCED("Synced"),
    PENDING_CHANGES("Pending Changes"),
    ATTENTION_REQUIRED("Attention Required"),
    FAILED("Failed")
}

@Serializable
enum class ConflictKind(val label: String) {
    CONCURRENT_EDIT("Concurrent Edit"),
    DELETE_VS_EDIT("Delete vs Edit")
}

@Serializable
enum class ConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH,
    MANUAL_MERGE
}

@Serializable
@Immutable
data class SyncConflictItem(
    val conflictId: String,
    val entityType: String,
    val entityId: String,
    val conflictKind: ConflictKind,
    val localTitle: String,
    val localPayloadJson: String,
    val localModifiedAt: Long,
    val localDeviceId: String,
    val remoteTitle: String,
    val remotePayloadJson: String,
    val remoteModifiedAt: Long,
    val remoteDeviceId: String,
    val detectedAtMillis: Long,
    val resolvedAtMillis: Long? = null
)

@Serializable
enum class BackupKind(val label: String) {
    FULL_MANUAL("Manual Full Backup"),
    FULL_SCHEDULED("Scheduled Full Backup"),
    INCREMENTAL("Incremental Bundle"),
    PRE_RESTORE_SAFETY("Pre-Restore Safety Backup")
}

@Serializable
@Immutable
data class DriveBackupFile(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdMillis: Long
)

@Immutable
data class BackupHistoryItem(
    val backupId: String,
    val backupKind: BackupKind,
    val destination: String,
    val locationRef: String,
    val sizeBytes: Long,
    val recordCount: Int,
    val attachmentCount: Int,
    val sha256Checksum: String,
    val verified: Boolean,
    val succeeded: Boolean,
    val errorMessage: String? = null,
    val createdAtMillis: Long
)

enum class SortOption(val label: String) {
    RECENTLY_MODIFIED("Recently modified"),
    RECENTLY_CREATED("Recently created"),
    TITLE_ASC("Title (A–Z)"),
    TITLE_DESC("Title (Z–A)"),
    RELEVANCE("Relevance")
}

@Immutable
data class SearchFilters(
    val query: String = "",
    val folderId: String? = null,
    val category: String? = null,
    val attachmentKind: AttachmentKind? = null,
    val fromDateMillis: Long? = null,
    val toDateMillis: Long? = null,
    val sortBy: SortOption = SortOption.RELEVANCE
)

@Immutable
data class SearchResultsBundle(
    val records: List<RecordItem>,
    val matchingFolders: List<Folder>,
    val matchingAttachments: List<AttachmentItem>
)

@Immutable
data class DailyActivityMetric(
    val dateLabel: String,
    val fullDateLabel: String = dateLabel,
    val epochDay: Long,
    val createdCount: Int,
    val modifiedCount: Int,
    val foldersCreatedCount: Int = 0,
    val remindersCount: Int = 0
) {
    val totalCount: Int
        get() = createdCount + modifiedCount + foldersCreatedCount + remindersCount
}

@Immutable
data class DashboardOverview(
    val totalRecords: Int,
    val totalFolders: Int,
    val pendingReminders: Int,
    val syncState: SyncHealthState,
    val lastBackupMillis: Long?,
    val recentDailyActivity: List<DailyActivityMetric>,
    val recentRecords: List<RecordItem>,
    val upcomingReminders: List<ReminderItem>,
    val recentTimeline: List<TimelineEvent>,
    val unresolvedConflicts: Int
)

@Immutable
data class VaultStatisticsOverview(
    val totalRecords: Int,
    val totalFolders: Int,
    val totalAttachments: Int,
    val databaseBytes: Long,
    val encryptedAttachmentsBytes: Long,
    val localBackupsBytes: Long,
    val completedRemindersCount: Int,
    val pendingRemindersCount: Int,
    val missedRemindersCount: Int,
    val dailyCreationSeries: List<DailyActivityMetric>,
    val syncState: SyncHealthState,
    val lastSyncedMillis: Long?,
    val lastBackupMillis: Long?
)

@Immutable
data class UserSettings(
    val defaultSortOrder: SortOption = SortOption.RECENTLY_MODIFIED,
    val autoSaveNotes: Boolean = true,
    val reducedMotion: Boolean = false,
    val defaultSnoozeMinutes: Int = 10,
    val autoLockSeconds: Int = 300,
    val lockOnScreenOff: Boolean = false,
    val secureTaskSwitcher: Boolean = false,
    val autoIncrementalSync: Boolean = true,
    val dailyBackupEnabled: Boolean = false,
    val dailyBackupHour24: Int = 2,
    val dailyBackupMinute: Int = 0,
    val deviceId: String = "",
    val deviceName: String = ""
)
