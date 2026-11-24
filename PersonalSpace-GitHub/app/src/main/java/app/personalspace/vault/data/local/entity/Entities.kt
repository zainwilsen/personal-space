package app.personalspace.vault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vault_metadata")
data class VaultMetadataEntity(
    @PrimaryKey val vaultId: String,
    val schemaVersion: Int = 1,
    val createdAt: Long,
    val lastModifiedAt: Long,
    val envelopeBundleJson: String
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["parentFolderId"]),
        Index(value = ["isDeleted"]),
        Index(value = ["name"])
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val colorId: String = "YELLOW",
    val createdAt: Long,
    val modifiedAt: Long,
    val sortOrder: Int = 0,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val deletedBatchId: String? = null,
    val syncVersion: Long = 1L,
    val lastModifiedByDeviceId: String = "",
    val syncPending: Boolean = true
)

@Entity(
    tableName = "records",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["folderId"]),
        Index(value = ["category"]),
        Index(value = ["modifiedAt"]),
        Index(value = ["createdAt"]),
        Index(value = ["isDeleted"])
    ]
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val folderId: String? = null,
    val category: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val customFieldsJson: String = "[]",
    val currentVersionNumber: Int = 1,
    val currentVersionId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val deletedBatchId: String? = null,
    val syncVersion: Long = 1L,
    val lastModifiedByDeviceId: String = "",
    val syncPending: Boolean = true
)

/**
 * Offline Full-Text Search (FTS4) virtual table backed by RecordEntity inside SQLCipher.
 */
@Fts4(contentEntity = RecordEntity::class)
@Entity(tableName = "records_fts")
data class RecordFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val title: String,
    val description: String,
    val category: String?,
    val customFieldsJson: String
)

@Entity(tableName = "record_drafts")
data class RecordDraftEntity(
    @PrimaryKey val draftKey: String,
    val folderId: String? = null,
    val title: String,
    val description: String,
    val category: String? = null,
    val customFieldsJson: String = "[]",
    val updatedAt: Long
)

@Entity(
    tableName = "record_versions",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["recordId"]),
        Index(value = ["createdAt"])
    ]
)
data class RecordVersionEntity(
    @PrimaryKey val id: String,
    val recordId: String,
    val versionNumber: Int,
    val title: String,
    val description: String,
    val category: String? = null,
    val customFieldsJson: String = "[]",
    val createdAt: Long,
    val createdByDeviceId: String,
    val changeSummary: String
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["recordId"]),
        Index(value = ["attachmentKind"]),
        Index(value = ["originalFileName"]),
        Index(value = ["isDeleted"])
    ]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val recordId: String,
    val originalFileName: String,
    val mimeType: String,
    val attachmentKind: String,
    val plaintextSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val encryptedRelativePath: String,
    val plaintextSha256Hex: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val syncVersion: Long = 1L,
    val lastModifiedByDeviceId: String = "",
    val syncPending: Boolean = true
)

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = RecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedRecordId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["linkedRecordId"]),
        Index(value = ["scheduledAtMillis"]),
        Index(value = ["status"]),
        Index(value = ["isDeleted"])
    ]
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String = "",
    val scheduledAtMillis: Long,
    val recurrenceType: String = "ONCE",
    val priority: String = "MEDIUM",
    val status: String = "PENDING",
    val snoozedUntilMillis: Long? = null,
    val linkedRecordId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val isDeleted: Boolean = false,
    val syncVersion: Long = 1L,
    val lastModifiedByDeviceId: String = "",
    val syncPending: Boolean = true
)

@Entity(
    tableName = "reminder_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["reminderId"]),
        Index(value = ["occurrenceScheduledMillis"])
    ]
)
data class ReminderOccurrenceEntity(
    @PrimaryKey val id: String,
    val reminderId: String,
    val reminderTitle: String,
    val occurrenceScheduledMillis: Long,
    val actualHandledMillis: Long,
    val outcome: String
)

@Entity(
    tableName = "timeline_events",
    indices = [Index(value = ["timestampMillis"])]
)
data class TimelineEventEntity(
    @PrimaryKey val id: String,
    val eventType: String,
    val entityType: String,
    val entityId: String? = null,
    val displayTitle: String,
    val accentColor: String,
    val timestampMillis: Long
)

@Entity(
    tableName = "deleted_items",
    indices = [Index(value = ["expiresAtMillis"])]
)
data class DeletedItemEntity(
    @PrimaryKey val batchId: String,
    val rootEntityType: String,
    val rootEntityId: String,
    val displayTitle: String,
    val parentFolderIdSnapshot: String? = null,
    val subfolderCount: Int = 0,
    val recordCount: Int = 0,
    val deletedAtMillis: Long,
    val expiresAtMillis: Long
)

@Entity(tableName = "sync_devices")
data class SyncDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val modelName: String,
    val registeredAtMillis: Long,
    val lastSeenSyncMillis: Long
)

@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["bundledInChangeId"]),
        Index(value = ["timestampMillis"]),
        Index(value = ["entityType", "entityId"])
    ]
)
data class SyncOperationEntity(
    @PrimaryKey val operationId: String,
    val entityType: String,
    val entityId: String,
    val operationType: String, // UPSERT or TOMBSTONE
    val baseVersion: Long,
    val newVersion: Long,
    val deviceId: String,
    val timestampMillis: Long,
    val payloadJson: String,
    val bundledInChangeId: String? = null
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val syncStatus: String = "NOT_CONNECTED",
    val lastSyncedAtMillis: Long? = null,
    val lastErrorMessage: String? = null,
    val appliedBundleIdsJson: String = "[]",
    val driveVaultFolderId: String? = null
)

@Entity(
    tableName = "conflict_records",
    indices = [Index(value = ["resolvedAtMillis"])]
)
data class ConflictRecordEntity(
    @PrimaryKey val conflictId: String,
    val entityType: String,
    val entityId: String,
    val conflictKind: String,
    val localTitle: String,
    val localPayloadJson: String,
    val localModifiedAt: Long,
    val localDeviceId: String,
    val remoteTitle: String,
    val remotePayloadJson: String,
    val remoteModifiedAt: Long,
    val remoteDeviceId: String,
    val detectedAtMillis: Long,
    val resolvedAtMillis: Long? = null,
    val resolutionChoice: String? = null
)

@Entity(
    tableName = "backup_metadata",
    indices = [Index(value = ["createdAtMillis"])]
)
data class BackupMetadataEntity(
    @PrimaryKey val backupId: String,
    val backupKind: String,
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
