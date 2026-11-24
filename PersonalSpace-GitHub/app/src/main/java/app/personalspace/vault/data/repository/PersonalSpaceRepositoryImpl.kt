package app.personalspace.vault.data.repository

import android.net.Uri
import androidx.room.withTransaction
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.data.attachment.MediaReferenceIndex
import app.personalspace.vault.data.backup.BackupRestoreResult
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.backup.SerializableAttachmentSnapshot
import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.entity.AttachmentEntity
import app.personalspace.vault.data.local.entity.BackupMetadataEntity
import app.personalspace.vault.data.local.entity.DeletedItemEntity
import app.personalspace.vault.data.local.entity.FolderEntity
import app.personalspace.vault.data.local.entity.RecordDraftEntity
import app.personalspace.vault.data.local.entity.RecordEntity
import app.personalspace.vault.data.local.entity.RecordVersionEntity
import app.personalspace.vault.data.local.entity.ReminderEntity
import app.personalspace.vault.data.local.entity.ReminderOccurrenceEntity
import app.personalspace.vault.data.local.entity.SyncOperationEntity
import app.personalspace.vault.data.local.entity.TimelineEventEntity
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.data.reminder.AlarmReminderScheduler
import app.personalspace.vault.data.reminder.ReminderRecurrenceCalculator
import app.personalspace.vault.data.sync.BackgroundWorkCoordinator
import app.personalspace.vault.data.sync.DriveRemoteDataSource
import app.personalspace.vault.data.sync.GoogleDriveAuthManager
import app.personalspace.vault.data.sync.MultiDeviceSyncEngine
import app.personalspace.vault.data.sync.SyncExecutionReport
import app.personalspace.vault.domain.model.AttachmentItem
import app.personalspace.vault.domain.model.AttachmentKind
import app.personalspace.vault.domain.model.DriveBackupFile
import app.personalspace.vault.data.backup.BackupPackageManager.Companion.DRIVE_BACKUP_NAME
import app.personalspace.vault.domain.model.BackupHistoryItem
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.domain.model.ConflictKind
import app.personalspace.vault.domain.model.ConflictResolutionChoice
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.DailyActivityMetric
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
import app.personalspace.vault.domain.model.ReminderStatus
import app.personalspace.vault.domain.model.SearchFilters
import app.personalspace.vault.domain.model.SearchResultsBundle
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.model.SyncConflictItem
import app.personalspace.vault.domain.model.SyncHealthState
import app.personalspace.vault.domain.model.TimelineEvent
import app.personalspace.vault.domain.model.TimelineEventType
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.domain.model.VaultStatisticsOverview
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.domain.repository.RecycleBinPurgeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PersonalSpaceRepositoryImpl @Inject constructor(
    private val sessionManager: VaultSessionManager,
    private val dbHolder: VaultDatabaseHolder,
    private val attachmentStorage: EncryptedAttachmentStorage,
    private val reminderScheduler: AlarmReminderScheduler,
    private val backupManager: BackupPackageManager,
    private val authManager: GoogleDriveAuthManager,
    private val remoteDataSource: DriveRemoteDataSource,
    private val syncEngine: MultiDeviceSyncEngine,
    private val workCoordinator: BackgroundWorkCoordinator,
    private val preferences: UserPreferencesDataSource,
    private val timeProvider: TimeProvider,
    /** P6: needed to delete an inline image file once no entity references it any more. */
    private val inlineImageStore: app.personalspace.vault.data.backup.InlineImageStore
) : PersonalSpaceRepository {

    companion object {
        const val RECYCLE_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L

        /** P6: recycle-bin batch type for a single deleted attachment (no schema change needed). */
        const val ROOT_ATTACHMENT = "ATTACHMENT"
        const val MAX_RECORD_VERSIONS = 2
        const val OUTBOX_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val DRIVE_BACKUPS_KEPT = 1
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val walCheckpointLock = Any()

    @Volatile
    private var lastWalCheckpointAtMillis = 0L

    /**
     * Keeps the SQLite write-ahead log from growing without bound. Cheap: only runs when the
     * WAL is already large enough to matter, and at most once every few seconds.
     */
    private fun trimWriteAheadLogIfNeeded(db: PersonalSpaceDatabase, force: Boolean = false) {
        val dbFile = dbHolder.databaseFile()
        val walFile = java.io.File(dbFile.path + "-wal")
        if (!walFile.exists()) return
        val now = timeProvider.nowMillis()
        if (!force && (walFile.length() < 1_048_576L || now - lastWalCheckpointAtMillis < 5_000L)) return
        synchronized(walCheckpointLock) {
            if (!force && now - lastWalCheckpointAtMillis < 5_000L) return
            runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            lastWalCheckpointAtMillis = now
        }
    }

    private suspend fun pruneRetention(db: PersonalSpaceDatabase) {
        runCatching {
            db.syncDao().deleteOperationsOlderThan(
                timeProvider.nowMillis() - OUTBOX_RETENTION_MILLIS
            )
        }
        runCatching { backupManager.migrateLegacyLocalBackups() }
    }

    @Volatile
    private var cachedOverview: DashboardOverview? = null

    @Volatile
    private var cachedFoldersList: List<Folder>? = null

    @Volatile
    private var cachedRecordsList: List<RecordItem>? = null

    @Volatile
    private var cachedRecordsById: Map<String, RecordItem> = emptyMap()

    override fun latestCachedDashboardOverview(): DashboardOverview? = cachedOverview
    override fun latestCachedFolders(): List<Folder>? = cachedFoldersList
    override fun latestCachedRecords(): List<RecordItem>? = cachedRecordsList
    override fun latestCachedRecordById(recordId: String): RecordItem? = cachedRecordsById[recordId]

    override suspend fun preloadStartupData() = withContext(Dispatchers.IO) {
        sessionManager.ensureAutoUnlocked()
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) return@withContext
        // Open & warm encrypted SQLCipher database off the main thread during LaunchAnimationScreen
        dbHolder.getDatabase()
        runCatching { refreshMissedReminders() }
        dbHolder.getDatabase().let { runCatching { pruneRetention(it) } }
        coroutineScope {
            val d1 = async { observeDashboardOverview().first() }
            val d2 = async { observeAllFolders().first() }
            val d3 = async { observeAllRecords().first() }
            cachedOverview = d1.await()
            cachedFoldersList = d2.await()
            val recs = d3.await()
            cachedRecordsList = recs
            cachedRecordsById = recs.associateBy { it.id }
        }
    }

    private fun <T> unlockedFlow(defaultValue: T, block: () -> Flow<T>): Flow<T> =
        sessionManager.lockState.flatMapLatest { state ->
            if (state == VaultLockState.UNLOCKED) block() else flowOf(defaultValue)
        }.distinctUntilChanged()

    override fun observeDashboardOverview(): Flow<DashboardOverview> =
        unlockedFlow(
            DashboardOverview(
                totalRecords = 0,
                totalFolders = 0,
                pendingReminders = 0,
                syncState = SyncHealthState.NOT_CONNECTED,
                lastBackupMillis = null,
                recentDailyActivity = buildDailySeries(emptyList(), days = 7),
                recentRecords = emptyList(),
                upcomingReminders = emptyList(),
                recentTimeline = emptyList(),
                unresolvedConflicts = 0
            )
        ) {
            val db = dbHolder.getDatabase()
            combine(
                db.recordDao().observeAllActiveRecords(),
                db.folderDao().observeActiveFolders(),
                db.attachmentDao().observeAllActiveAttachments(),
                db.reminderDao().observeAllActiveReminders(),
                combine(
                    db.timelineDao().observeRecentEvents(8),
                    db.syncDao().observeSyncMetadata(),
                    db.syncDao().observePendingOperationCount(),
                    db.syncDao().observeUnresolvedConflictCount(),
                    db.backupDao().observeLatestSuccessfulBackup()
                ) { timeline, syncMeta, pendingOps, conflicts, latestBackup ->
                    Quint(timeline, syncMeta, pendingOps, conflicts, latestBackup)
                }
            ) { records, folders, attachments, reminders, metaQuint ->
                val foldersById = folders.associateBy { it.id }
                val attCountByRec = attachments.groupingBy { it.recordId }.eachCount()
                val mappedRecords = records.take(5).map { toRecordDomain(it, foldersById, attCountByRec) }
                val recordsById = records.associateBy { it.id }

                val upcoming = reminders
                    .filter { it.status == ReminderStatus.PENDING.name || it.status == ReminderStatus.SNOOZED.name }
                    .sortedBy { it.snoozedUntilMillis ?: it.scheduledAtMillis }
                    .take(5)
                    .map { toReminderDomain(it, recordsById) }

                val pendingCount = reminders.count {
                    it.status == ReminderStatus.PENDING.name || it.status == ReminderStatus.SNOOZED.name
                }

                val effectiveSyncState = resolveHonestSyncState(
                    syncMetaStatus = metaQuint.second?.syncStatus,
                    pendingOps = metaQuint.third,
                    unresolvedConflicts = metaQuint.fourth
                )

                DashboardOverview(
                    totalRecords = records.size,
                    totalFolders = folders.size,
                    pendingReminders = pendingCount,
                    syncState = effectiveSyncState,
                    lastBackupMillis = metaQuint.fifth?.createdAtMillis,
                    recentDailyActivity = buildDailySeries(
                        records = records,
                        folders = folders,
                        reminders = reminders,
                        days = 7
                    ),
                    recentRecords = mappedRecords,
                    upcomingReminders = upcoming,
                    recentTimeline = metaQuint.first.map { toTimelineDomain(it) },
                    unresolvedConflicts = metaQuint.fourth
                )
            }
        }.onEach { cachedOverview = it }

    override fun observeVaultStatistics(): Flow<VaultStatisticsOverview> =
        unlockedFlow(
            VaultStatisticsOverview(
                totalRecords = 0,
                totalFolders = 0,
                totalAttachments = 0,
                databaseBytes = 0L,
                encryptedAttachmentsBytes = 0L,
                localBackupsBytes = 0L,
                completedRemindersCount = 0,
                pendingRemindersCount = 0,
                missedRemindersCount = 0,
                dailyCreationSeries = buildDailySeries(emptyList(), days = 7),
                syncState = SyncHealthState.NOT_CONNECTED,
                lastSyncedMillis = null,
                lastBackupMillis = null
            )
        ) {
            val db = dbHolder.getDatabase()
            combine(
                db.recordDao().observeAllActiveRecords(),
                db.folderDao().observeActiveFolders(),
                db.attachmentDao().observeAllActiveAttachments(),
                db.reminderDao().observeAllActiveReminders(),
                combine(
                    db.reminderDao().observeAllOccurrences(),
                    db.syncDao().observeSyncMetadata(),
                    db.syncDao().observePendingOperationCount(),
                    db.syncDao().observeUnresolvedConflictCount(),
                    db.backupDao().observeLatestSuccessfulBackup()
                ) { occs, syncMeta, pendingOps, conflicts, latestBackup ->
                    Quint(occs, syncMeta, pendingOps, conflicts, latestBackup)
                }
            ) { records, folders, attachments, reminders, quint ->
                val dbFile = dbHolder.databaseFile()
                val dbTotalBytes = listOf(
                    dbFile,
                    File(dbFile.path + "-wal"),
                    File(dbFile.path + "-shm")
                ).filter { it.exists() }.sumOf { it.length() }

                val completedCount = reminders.count { it.status == ReminderStatus.COMPLETED.name } +
                    quint.first.count { it.outcome == ReminderStatus.COMPLETED.name }
                val pendingCount = reminders.count {
                    it.status == ReminderStatus.PENDING.name || it.status == ReminderStatus.SNOOZED.name
                }
                val missedCount = reminders.count { it.status == ReminderStatus.MISSED.name } +
                    quint.first.count { it.outcome == ReminderStatus.MISSED.name }

                VaultStatisticsOverview(
                    totalRecords = records.size,
                    totalFolders = folders.size,
                    totalAttachments = attachments.size,
                    databaseBytes = dbTotalBytes,
                    encryptedAttachmentsBytes = attachmentStorage.computeTotalEncryptedAttachmentsBytes(),
                    localBackupsBytes = backupManager.computeLocalBackupsBytes(),
                    completedRemindersCount = completedCount,
                    pendingRemindersCount = pendingCount,
                    missedRemindersCount = missedCount,
                    dailyCreationSeries = buildDailySeries(
                        records = records,
                        folders = folders,
                        reminders = reminders,
                        days = 7
                    ),
                    syncState = resolveHonestSyncState(
                        quint.second?.syncStatus,
                        quint.third,
                        quint.fourth
                    ),
                    lastSyncedMillis = quint.second?.lastSyncedAtMillis,
                    lastBackupMillis = quint.fifth?.createdAtMillis
                )
            }
        }

    // ==================== FOLDERS ====================

    override fun observeAllFolders(): Flow<List<Folder>> = unlockedFlow(emptyList()) {
        val db = dbHolder.getDatabase()
        combine(
            db.folderDao().observeActiveFolders(),
            db.recordDao().observeAllActiveRecords()
        ) { folders, records ->
            val recCounts = records.groupingBy { it.folderId }.eachCount()
            val subCounts = folders.groupingBy { it.parentFolderId }.eachCount()
            folders.map { f ->
                Folder(
                    id = f.id,
                    name = f.name,
                    parentFolderId = f.parentFolderId,
                    color = FolderAccentColor.fromId(f.colorId),
                    createdAt = f.createdAt,
                    modifiedAt = f.modifiedAt,
                    sortOrder = f.sortOrder,
                    recordCount = recCounts[f.id] ?: 0,
                    subfolderCount = subCounts[f.id] ?: 0,
                    syncVersion = f.syncVersion
                )
            }
        }
    }.onEach { cachedFoldersList = it }

    override fun observeSubfolders(parentFolderId: String?): Flow<List<Folder>> =
        observeAllFolders().map { all -> all.filter { it.parentFolderId == parentFolderId } }

    override suspend fun getFolderById(folderId: String): Folder? = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val f = db.folderDao().getFolderById(folderId)?.takeIf { !it.isDeleted } ?: return@withContext null
        val allFolders = db.folderDao().getAllActiveFolders()
        val allRecords = db.recordDao().getAllActiveRecords()
        Folder(
            id = f.id,
            name = f.name,
            parentFolderId = f.parentFolderId,
            color = FolderAccentColor.fromId(f.colorId),
            createdAt = f.createdAt,
            modifiedAt = f.modifiedAt,
            sortOrder = f.sortOrder,
            recordCount = allRecords.count { it.folderId == f.id },
            subfolderCount = allFolders.count { it.parentFolderId == f.id },
            syncVersion = f.syncVersion
        )
    }

    override suspend fun getFolderBreadcrumbs(folderId: String?): List<Folder> = withContext(Dispatchers.IO) {
        if (folderId == null) return@withContext emptyList()
        val all = dbHolder.getDatabase().folderDao().getAllActiveFolders().associateBy { it.id }
        val trail = ArrayList<Folder>()
        var cursorId: String? = folderId
        var guard = 0
        while (cursorId != null && guard < 128) {
            val f = all[cursorId] ?: break
            trail.add(
                Folder(
                    id = f.id,
                    name = f.name,
                    parentFolderId = f.parentFolderId,
                    color = FolderAccentColor.fromId(f.colorId),
                    createdAt = f.createdAt,
                    modifiedAt = f.modifiedAt,
                    sortOrder = f.sortOrder
                )
            )
            cursorId = f.parentFolderId
            guard++
        }
        trail.reversed()
    }

    override suspend fun createFolder(
        name: String,
        parentFolderId: String?,
        color: FolderAccentColor
    ): Folder = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Folder name cannot be empty." }

        val db = dbHolder.getDatabase()
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val folderId = IdGenerator.newUuid()

        if (parentFolderId != null) {
            val parent = db.folderDao().getFolderById(parentFolderId)
            require(parent != null && !parent.isDeleted) { "Selected parent folder does not exist." }
        }

        val entity = FolderEntity(
            id = folderId,
            name = cleanName,
            parentFolderId = parentFolderId,
            colorId = color.id,
            createdAt = now,
            modifiedAt = now,
            sortOrder = 0,
            syncVersion = 1L,
            lastModifiedByDeviceId = deviceId,
            syncPending = true
        )

        db.withTransaction {
            db.folderDao().upsert(entity)
            db.timelineDao().insert(
                TimelineEventEntity(
                    id = IdGenerator.newUuid(),
                    eventType = TimelineEventType.FOLDER_CREATED.name,
                    entityType = "FOLDER",
                    entityId = folderId,
                    displayTitle = cleanName,
                    accentColor = color.id,
                    timestampMillis = now
                )
            )
            queueSyncOp(
                entityType = "FOLDER",
                entityId = folderId,
                opType = "UPSERT",
                baseVersion = 0L,
                newVersion = 1L,
                deviceId = deviceId,
                timestamp = now,
                payloadJson = json.encodeToString(MultiDeviceSyncEngine.toFolderSnapshot(entity))
            )
        }
        triggerAutoSyncIfEnabled()

        Folder(
            id = entity.id,
            name = entity.name,
            parentFolderId = entity.parentFolderId,
            color = color,
            createdAt = now,
            modifiedAt = now
        )
    }

    override suspend fun renameFolder(
        folderId: String,
        newName: String,
        color: FolderAccentColor
    ) = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        require(cleanName.isNotEmpty()) { "Folder name cannot be empty." }
        val db = dbHolder.getDatabase()
        val existing = db.folderDao().getFolderById(folderId)
            ?: throw IllegalArgumentException("Folder not found.")
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val updated = existing.copy(
            name = cleanName,
            colorId = color.id,
            modifiedAt = now,
            syncVersion = existing.syncVersion + 1,
            lastModifiedByDeviceId = deviceId,
            syncPending = true
        )

        db.withTransaction {
            db.folderDao().upsert(updated)
            db.timelineDao().insert(
                TimelineEventEntity(
                    id = IdGenerator.newUuid(),
                    eventType = TimelineEventType.FOLDER_UPDATED.name,
                    entityType = "FOLDER",
                    entityId = folderId,
                    displayTitle = cleanName,
                    accentColor = color.id,
                    timestampMillis = now
                )
            )
            queueSyncOp(
                entityType = "FOLDER",
                entityId = folderId,
                opType = "UPSERT",
                baseVersion = existing.syncVersion,
                newVersion = updated.syncVersion,
                deviceId = deviceId,
                timestamp = now,
                payloadJson = json.encodeToString(MultiDeviceSyncEngine.toFolderSnapshot(updated))
            )
        }
        triggerAutoSyncIfEnabled()
    }

    override suspend fun moveFolder(folderId: String, newParentFolderId: String?) =
        withContext(Dispatchers.IO) {
            if (folderId == newParentFolderId) {
                throw IllegalArgumentException("A folder cannot be moved inside itself.")
            }
            val db = dbHolder.getDatabase()
            val allFolders = db.folderDao().getAllActiveFolders().associateBy { it.id }
            val folder = allFolders[folderId] ?: throw IllegalArgumentException("Folder not found.")

            // Prevent circular folder hierarchy at arbitrary depth
            if (newParentFolderId != null) {
                if (newParentFolderId !in allFolders) {
                    throw IllegalArgumentException("Target destination folder does not exist.")
                }
                var cursor: String? = newParentFolderId
                var depth = 0
                while (cursor != null) {
                    if (cursor == folderId) {
                        throw IllegalArgumentException("Cannot move '${folder.name}' into one of its own subfolders.")
                    }
                    cursor = allFolders[cursor]?.parentFolderId
                    depth++
                    if (depth > 120) {
                        throw IllegalArgumentException("Maximum folder nesting depth exceeded.")
                    }
                }
            }

            val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
            val now = timeProvider.nowMillis()
            val updated = folder.copy(
                parentFolderId = newParentFolderId,
                modifiedAt = now,
                syncVersion = folder.syncVersion + 1,
                lastModifiedByDeviceId = deviceId,
                syncPending = true
            )

            db.withTransaction {
                db.folderDao().upsert(updated)
                db.timelineDao().insert(
                    TimelineEventEntity(
                        id = IdGenerator.newUuid(),
                        eventType = TimelineEventType.FOLDER_UPDATED.name,
                        entityType = "FOLDER",
                        entityId = folderId,
                        displayTitle = folder.name,
                        accentColor = folder.colorId,
                        timestampMillis = now
                    )
                )
                queueSyncOp(
                    entityType = "FOLDER",
                    entityId = folderId,
                    opType = "UPSERT",
                    baseVersion = folder.syncVersion,
                    newVersion = updated.syncVersion,
                    deviceId = deviceId,
                    timestamp = now,
                    payloadJson = json.encodeToString(MultiDeviceSyncEngine.toFolderSnapshot(updated))
                )
            }
            triggerAutoSyncIfEnabled()
        }

    override suspend fun softDeleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val allActiveFolders = db.folderDao().getAllActiveFolders()
        val root = allActiveFolders.firstOrNull { it.id == folderId } ?: return@withContext

        val subtreeFolderIds = collectSubtreeFolderIds(folderId, allActiveFolders)
        val affectedFolders = allActiveFolders.filter { it.id in subtreeFolderIds }
        val affectedRecords = db.recordDao().getActiveRecordsInFolders(subtreeFolderIds.toList())

        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val batchId = IdGenerator.newUuid()

        db.withTransaction {
            db.folderDao().upsertAll(
                affectedFolders.map { f ->
                    f.copy(
                        isDeleted = true,
                        deletedAt = now,
                        deletedBatchId = batchId,
                        modifiedAt = now,
                        syncVersion = f.syncVersion + 1,
                        lastModifiedByDeviceId = deviceId,
                        syncPending = true
                    )
                }
            )
            db.recordDao().upsertAll(
                affectedRecords.map { r ->
                    r.copy(
                        isDeleted = true,
                        deletedAt = now,
                        deletedBatchId = batchId,
                        modifiedAt = now,
                        syncVersion = r.syncVersion + 1,
                        lastModifiedByDeviceId = deviceId,
                        syncPending = true
                    )
                }
            )
            // P6 (folder-deletion audit): the attachments of the notes in this folder are part of the
            // deleted content, exactly like a single-note deletion. Without this the folder batch and
            // its notes/attachments disagreed: restoring the folder left attachments of a *deleted*
            // note looking active, and permanent deletion had to special-case them.
            val attachmentsOfSubtree = if (affectedRecords.isNotEmpty()) {
                db.attachmentDao().getAttachmentsForRecords(affectedRecords.map { it.id })
                    .filter { !it.isDeleted }
            } else {
                emptyList()
            }
            if (attachmentsOfSubtree.isNotEmpty()) {
                db.attachmentDao().upsertAll(
                    attachmentsOfSubtree.map { att ->
                        att.copy(
                            isDeleted = true,
                            deletedAt = now,
                            modifiedAt = now,
                            syncVersion = att.syncVersion + 1,
                            lastModifiedByDeviceId = deviceId,
                            syncPending = true
                        )
                    }
                )
            }
            db.recycleBinDao().upsert(
                DeletedItemEntity(
                    batchId = batchId,
                    rootEntityType = "FOLDER",
                    rootEntityId = root.id,
                    displayTitle = root.name,
                    parentFolderIdSnapshot = root.parentFolderId,
                    subfolderCount = (affectedFolders.size - 1).coerceAtLeast(0),
                    recordCount = affectedRecords.size,
                    deletedAtMillis = now,
                    expiresAtMillis = now + RECYCLE_RETENTION_MILLIS
                )
            )
            db.timelineDao().insert(
                TimelineEventEntity(
                    id = IdGenerator.newUuid(),
                    eventType = TimelineEventType.FOLDER_DELETED.name,
                    entityType = "FOLDER",
                    entityId = root.id,
                    displayTitle = root.name,
                    accentColor = "RED",
                    timestampMillis = now
                )
            )
            affectedFolders.forEach { f ->
                queueSyncOp("FOLDER", f.id, "TOMBSTONE", f.syncVersion, f.syncVersion + 1, deviceId, now, "{}")
            }
            affectedRecords.forEach { r ->
                queueSyncOp("RECORD", r.id, "TOMBSTONE", r.syncVersion, r.syncVersion + 1, deviceId, now, "{}")
            }
        }
        triggerAutoSyncIfEnabled()
    }

    // ==================== RECORDS, DRAFTS & VERSIONS ====================

    override fun observeAllRecords(): Flow<List<RecordItem>> = unlockedFlow(emptyList()) {
        val db = dbHolder.getDatabase()
        combine(
            db.recordDao().observeAllActiveRecords(),
            db.folderDao().observeActiveFolders(),
            db.attachmentDao().observeAllActiveAttachments()
        ) { records, folders, attachments ->
            val foldersById = folders.associateBy { it.id }
            val attCountByRec = attachments.groupingBy { it.recordId }.eachCount()
            records.map { toRecordDomain(it, foldersById, attCountByRec) }
        }
    }.onEach { list ->
        cachedRecordsList = list
        cachedRecordsById = list.associateBy { it.id }
    }

    override fun observeRecordsInFolder(folderId: String): Flow<List<RecordItem>> =
        observeAllRecords().map { all -> all.filter { it.folderId == folderId } }

    override fun observeRecordById(recordId: String): Flow<RecordItem?> = unlockedFlow(null) {
        val db = dbHolder.getDatabase()
        combine(
            db.recordDao().observeRecordById(recordId),
            db.folderDao().observeActiveFolders(),
            db.attachmentDao().observeAttachmentsForRecord(recordId)
        ) { rec, folders, atts ->
            if (rec == null || rec.isDeleted) null
            else toRecordDomain(rec, folders.associateBy { it.id }, mapOf(recordId to atts.size))
        }
    }.onEach { item ->
        if (item != null) {
            cachedRecordsById = cachedRecordsById + (item.id to item)
        }
    }

    override suspend fun getRecordById(recordId: String): RecordItem? = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val rec = db.recordDao().getRecordById(recordId)?.takeIf { !it.isDeleted } ?: return@withContext null
        val folderName = rec.folderId?.let { db.folderDao().getFolderById(it)?.name }
        val attCount = db.attachmentDao().getAttachmentsForRecord(recordId).size
        RecordItem(
            id = rec.id,
            title = rec.title,
            description = rec.description,
            folderId = rec.folderId,
            folderName = folderName,
            category = rec.category,
            createdAt = rec.createdAt,
            modifiedAt = rec.modifiedAt,
            customFields = decodeCustomFields(rec.customFieldsJson),
            currentVersionNumber = rec.currentVersionNumber,
            currentVersionId = rec.currentVersionId,
            attachmentCount = attCount,
            syncVersion = rec.syncVersion
        )
    }

    override fun observeDistinctCategories(): Flow<List<String>> = unlockedFlow(emptyList()) {
        dbHolder.getDatabase().recordDao().observeDistinctCategories()
    }

    override suspend fun saveRecord(
        existingRecordId: String?,
        title: String,
        description: String,
        folderId: String?,
        category: String?,
        customFields: List<CustomField>,
        updateCurrentVersionInPlace: Boolean
    ): RecordItem = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Note title cannot be empty." }

        val cleanCategory = category?.trim()?.takeIf { it.isNotEmpty() }
        val cleanFields = customFields.filter { it.label.isNotBlank() }
        val fieldsJson = json.encodeToString(cleanFields)
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val db = dbHolder.getDatabase()

        val existing = existingRecordId?.let { db.recordDao().getRecordById(it) }
        val savedEntity: RecordEntity = db.withTransaction {
            if (existing == null) {
                val recId = existingRecordId ?: IdGenerator.newUuid()
                val versionId = IdGenerator.newUuid()
                val created = RecordEntity(
                    id = recId,
                    title = cleanTitle,
                    description = description,
                    folderId = folderId,
                    category = cleanCategory,
                    createdAt = now,
                    modifiedAt = now,
                    customFieldsJson = fieldsJson,
                    currentVersionNumber = 1,
                    currentVersionId = versionId,
                    syncVersion = 1L,
                    lastModifiedByDeviceId = deviceId,
                    syncPending = true
                )
                db.recordDao().upsert(created)
                db.recordDao().insertVersion(
                    RecordVersionEntity(
                        id = versionId,
                        recordId = recId,
                        versionNumber = 1,
                        title = cleanTitle,
                        description = description,
                        category = cleanCategory,
                        customFieldsJson = fieldsJson,
                        createdAt = now,
                        createdByDeviceId = deviceId,
                        changeSummary = "Initial note created"
                    )
                )
                db.recordDao().deleteDraft(existingRecordId ?: "NEW_RECORD")
                db.recordDao().deleteDraft("NEW_${folderId ?: "ROOT"}")
                existingRecordId?.let { db.recordDao().deleteDraft("EDIT_$it") }
                db.timelineDao().insert(
                    TimelineEventEntity(
                        id = IdGenerator.newUuid(),
                        eventType = TimelineEventType.RECORD_CREATED.name,
                        entityType = "RECORD",
                        entityId = recId,
                        displayTitle = cleanTitle,
                        accentColor = "RED",
                        timestampMillis = now
                    )
                )
                queueSyncOp(
                    entityType = "RECORD",
                    entityId = recId,
                    opType = "UPSERT",
                    baseVersion = 0L,
                    newVersion = 1L,
                    deviceId = deviceId,
                    timestamp = now,
                    payloadJson = json.encodeToString(MultiDeviceSyncEngine.toRecordSnapshot(created))
                )
                created
            } else {
                // Only create a new version when meaningful committed content has changed!
                val hasContentChanged =
                    existing.title != cleanTitle ||
                        existing.description != description ||
                        existing.category != cleanCategory ||
                        existing.customFieldsJson != fieldsJson

                val hasFolderChanged = existing.folderId != folderId

                if (!hasContentChanged && !hasFolderChanged) {
                    db.recordDao().deleteDraft(existing.id)
                    db.recordDao().deleteDraft("EDIT_${existing.id}")
                    existing
                } else {
                    val reuseCurrentVer = hasContentChanged && updateCurrentVersionInPlace
                    val nextVerNum = if (hasContentChanged && !reuseCurrentVer) {
                        existing.currentVersionNumber + 1
                    } else {
                        existing.currentVersionNumber
                    }
                    val nextVerId = if (hasContentChanged && !reuseCurrentVer) {
                        IdGenerator.newUuid()
                    } else {
                        existing.currentVersionId
                    }
                    val updated = existing.copy(
                        title = cleanTitle,
                        description = description,
                        folderId = folderId,
                        category = cleanCategory,
                        modifiedAt = now,
                        customFieldsJson = fieldsJson,
                        currentVersionNumber = nextVerNum,
                        currentVersionId = nextVerId,
                        syncVersion = existing.syncVersion + 1,
                        lastModifiedByDeviceId = deviceId,
                        syncPending = true
                    )
                    db.recordDao().upsert(updated)
                    if (hasContentChanged) {
                        db.recordDao().insertVersion(
                            RecordVersionEntity(
                                id = nextVerId,
                                recordId = existing.id,
                                versionNumber = nextVerNum,
                                title = cleanTitle,
                                description = description,
                                category = cleanCategory,
                                customFieldsJson = fieldsJson,
                                createdAt = now,
                                createdByDeviceId = deviceId,
                                changeSummary = if (nextVerNum == 1) {
                                    "Initial note created"
                                } else {
                                    "Updated note (v$nextVerNum)"
                                }
                            )
                        )
                    }
                    if (hasContentChanged && nextVerNum > MAX_RECORD_VERSIONS) {
                        db.recordDao().deleteVersionsAtOrBelow(
                            recordId = existing.id,
                            keepBelow = nextVerNum - MAX_RECORD_VERSIONS
                        )
                    }
                    db.recordDao().deleteDraft(existing.id)
                    db.recordDao().deleteDraft("EDIT_${existing.id}")
                    db.timelineDao().insert(
                        TimelineEventEntity(
                            id = IdGenerator.newUuid(),
                            eventType = TimelineEventType.RECORD_UPDATED.name,
                            entityType = "RECORD",
                            entityId = existing.id,
                            displayTitle = cleanTitle,
                            accentColor = "BLUE",
                            timestampMillis = now
                        )
                    )
                    queueSyncOp(
                        entityType = "RECORD",
                        entityId = existing.id,
                        opType = "UPSERT",
                        baseVersion = existing.syncVersion,
                        newVersion = updated.syncVersion,
                        deviceId = deviceId,
                        timestamp = now,
                        payloadJson = json.encodeToString(MultiDeviceSyncEngine.toRecordSnapshot(updated))
                    )
                    updated
                }
            }
        }

        runCatching { trimWriteAheadLogIfNeeded(db) }
        triggerAutoSyncIfEnabled()
        getRecordById(savedEntity.id)!!
    }

    override suspend fun renameRecordTitle(recordId: String, newTitle: String): RecordItem =
        withContext(Dispatchers.IO) {
            val cleanTitle = newTitle.trim()
            require(cleanTitle.isNotEmpty()) { "Note title cannot be empty." }
            val existing = getRecordById(recordId)
                ?: throw IllegalArgumentException("Note not found.")
            saveRecord(
                existingRecordId = existing.id,
                title = cleanTitle,
                description = existing.description,
                folderId = existing.folderId,
                category = existing.category,
                customFields = existing.customFields
            )
        }

    override suspend fun duplicateRecord(recordId: String): RecordItem = withContext(Dispatchers.IO) {
        val original = getRecordById(recordId) ?: throw IllegalArgumentException("Note not found.")
        saveRecord(
            existingRecordId = null,
            title = "${original.title} (Copy)",
            description = original.description,
            folderId = original.folderId,
            category = original.category,
            customFields = original.customFields
        )
    }

    override suspend fun moveRecord(recordId: String, targetFolderId: String?) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val existing = db.recordDao().getRecordById(recordId) ?: return@withContext
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val updated = existing.copy(
            folderId = targetFolderId,
            modifiedAt = now,
            syncVersion = existing.syncVersion + 1,
            lastModifiedByDeviceId = deviceId,
            syncPending = true
        )
        db.withTransaction {
            db.recordDao().upsert(updated)
            queueSyncOp(
                entityType = "RECORD",
                entityId = recordId,
                opType = "UPSERT",
                baseVersion = existing.syncVersion,
                newVersion = updated.syncVersion,
                deviceId = deviceId,
                timestamp = now,
                payloadJson = json.encodeToString(MultiDeviceSyncEngine.toRecordSnapshot(updated))
            )
        }
        triggerAutoSyncIfEnabled()
    }

    override suspend fun softDeleteRecord(recordId: String) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val existing = db.recordDao().getRecordById(recordId)?.takeIf { !it.isDeleted } ?: return@withContext
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val batchId = IdGenerator.newUuid()

        db.withTransaction {
            db.recordDao().upsert(
                existing.copy(
                    isDeleted = true,
                    deletedAt = now,
                    deletedBatchId = batchId,
                    modifiedAt = now,
                    syncVersion = existing.syncVersion + 1,
                    lastModifiedByDeviceId = deviceId,
                    syncPending = true
                )
            )
            // P5 (requirement 2): the record's attachments are part of the deleted content. They are
            // soft-deleted in the SAME transaction and carry the same batch id, so restoring the
            // batch restores them too and permanent deletion removes exactly this batch.
            val attachments = db.attachmentDao().getAttachmentsForRecords(listOf(existing.id))
                .filter { !it.isDeleted }
            if (attachments.isNotEmpty()) {
                db.attachmentDao().upsertAll(
                    attachments.map { att ->
                        att.copy(
                            isDeleted = true,
                            deletedAt = now,
                            modifiedAt = now,
                            syncVersion = att.syncVersion + 1,
                            lastModifiedByDeviceId = deviceId,
                            syncPending = true
                        )
                    }
                )
            }
            db.recycleBinDao().upsert(
                DeletedItemEntity(
                    batchId = batchId,
                    rootEntityType = "RECORD",
                    rootEntityId = existing.id,
                    displayTitle = existing.title,
                    parentFolderIdSnapshot = existing.folderId,
                    subfolderCount = 0,
                    recordCount = 1,
                    deletedAtMillis = now,
                    expiresAtMillis = now + RECYCLE_RETENTION_MILLIS
                )
            )
            db.timelineDao().insert(
                TimelineEventEntity(
                    id = IdGenerator.newUuid(),
                    eventType = TimelineEventType.RECORD_DELETED.name,
                    entityType = "RECORD",
                    entityId = existing.id,
                    displayTitle = existing.title,
                    accentColor = "RED",
                    timestampMillis = now
                )
            )
            queueSyncOp("RECORD", existing.id, "TOMBSTONE", existing.syncVersion, existing.syncVersion + 1, deviceId, now, "{}")
        }
        triggerAutoSyncIfEnabled()
    }

    override suspend fun getDraft(draftKey: String): RecordDraft? = withContext(Dispatchers.IO) {
        val d = dbHolder.getDatabase().recordDao().getDraft(draftKey) ?: return@withContext null
        RecordDraft(
            draftKey = d.draftKey,
            folderId = d.folderId,
            title = d.title,
            description = d.description,
            category = d.category,
            customFields = decodeCustomFields(d.customFieldsJson),
            updatedAt = d.updatedAt
        )
    }

    override suspend fun saveDraft(draft: RecordDraft) = withContext(Dispatchers.IO) {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) return@withContext
        dbHolder.getDatabase().recordDao().saveDraft(
            RecordDraftEntity(
                draftKey = draft.draftKey,
                folderId = draft.folderId,
                title = draft.title,
                description = draft.description,
                category = draft.category,
                customFieldsJson = json.encodeToString(draft.customFields),
                updatedAt = timeProvider.nowMillis()
            )
        )
    }

    override suspend fun clearDraft(draftKey: String) = withContext(Dispatchers.IO) {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) return@withContext
        dbHolder.getDatabase().recordDao().deleteDraft(draftKey)
    }

    override fun observeRecordVersions(recordId: String): Flow<List<RecordVersion>> =
        unlockedFlow(emptyList()) {
            dbHolder.getDatabase().recordDao().observeVersionsForRecord(recordId).map { list ->
                list.map { v ->
                    RecordVersion(
                        id = v.id,
                        recordId = v.recordId,
                        versionNumber = v.versionNumber,
                        title = v.title,
                        description = v.description,
                        category = v.category,
                        customFields = decodeCustomFields(v.customFieldsJson),
                        createdAt = v.createdAt,
                        createdByDeviceId = v.createdByDeviceId,
                        changeSummary = v.changeSummary
                    )
                }
            }
        }

    /**
     * Restores a previous version safely by creating a NEW version rather than deleting current history.
     */
    override suspend fun restoreRecordVersion(recordId: String, versionId: String): RecordItem =
        withContext(Dispatchers.IO) {
            val db = dbHolder.getDatabase()
            val current = db.recordDao().getRecordById(recordId)
                ?: throw IllegalArgumentException("Record not found.")
            val targetVersion = db.recordDao().getVersionById(versionId)
                ?: throw IllegalArgumentException("Selected version not found.")

            val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
            val now = timeProvider.nowMillis()
            val newVersionNumber = current.currentVersionNumber + 1
            val newVersionId = IdGenerator.newUuid()

            val updatedRecord = current.copy(
                title = targetVersion.title,
                description = targetVersion.description,
                category = targetVersion.category,
                customFieldsJson = targetVersion.customFieldsJson,
                currentVersionNumber = newVersionNumber,
                currentVersionId = newVersionId,
                modifiedAt = now,
                syncVersion = current.syncVersion + 1,
                lastModifiedByDeviceId = deviceId,
                syncPending = true
            )

            db.withTransaction {
                db.recordDao().upsert(updatedRecord)
                db.recordDao().insertVersion(
                    RecordVersionEntity(
                        id = newVersionId,
                        recordId = recordId,
                        versionNumber = newVersionNumber,
                        title = targetVersion.title,
                        description = targetVersion.description,
                        category = targetVersion.category,
                        customFieldsJson = targetVersion.customFieldsJson,
                        createdAt = now,
                        createdByDeviceId = deviceId,
                        changeSummary = "Restored from version v${targetVersion.versionNumber}"
                    )
                )
                db.timelineDao().insert(
                    TimelineEventEntity(
                        id = IdGenerator.newUuid(),
                        eventType = TimelineEventType.VERSION_RESTORED.name,
                        entityType = "RECORD",
                        entityId = recordId,
                        displayTitle = "${targetVersion.title} (v${targetVersion.versionNumber} -> v$newVersionNumber)",
                        accentColor = "YELLOW",
                        timestampMillis = now
                    )
                )
                queueSyncOp(
                    entityType = "RECORD",
                    entityId = recordId,
                    opType = "UPSERT",
                    baseVersion = current.syncVersion,
                    newVersion = updatedRecord.syncVersion,
                    deviceId = deviceId,
                    timestamp = now,
                    payloadJson = json.encodeToString(MultiDeviceSyncEngine.toRecordSnapshot(updatedRecord))
                )
            }
            triggerAutoSyncIfEnabled()
            getRecordById(recordId)!!
        }

    // ==================== ATTACHMENTS ====================

    override fun observeAttachmentsForRecord(recordId: String): Flow<List<AttachmentItem>> =
        unlockedFlow(emptyList()) {
            dbHolder.getDatabase().attachmentDao().observeAttachmentsForRecord(recordId).map { list ->
                list.map { toAttachmentDomain(it) }
            }
        }

    override suspend fun getAttachmentById(attachmentId: String): AttachmentItem? =
        withContext(Dispatchers.IO) {
            dbHolder.getDatabase().attachmentDao().getAttachmentById(attachmentId)
                ?.takeIf { !it.isDeleted }
                ?.let { toAttachmentDomain(it) }
        }

    override suspend fun importAttachmentFromUri(recordId: String, uri: Uri): AttachmentItem =
        withContext(Dispatchers.IO) {
            val imported = attachmentStorage.importFromUri(uri)
            persistImportedAttachment(recordId, imported)
        }

    override suspend fun importAttachmentFromStream(
        recordId: String,
        input: InputStream,
        fileName: String,
        mimeType: String
    ): AttachmentItem = withContext(Dispatchers.IO) {
        val imported = attachmentStorage.importFromStream(input, fileName, mimeType)
        persistImportedAttachment(recordId, imported)
    }

    private suspend fun persistImportedAttachment(
        recordId: String,
        imported: app.personalspace.vault.data.attachment.EncryptedFileImportResult
    ): AttachmentItem {
        val db = dbHolder.getDatabase()
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val entity = AttachmentEntity(
            id = imported.attachmentId,
            recordId = recordId,
            originalFileName = imported.originalFileName,
            mimeType = imported.mimeType,
            attachmentKind = imported.kind.name,
            plaintextSizeBytes = imported.plaintextSizeBytes,
            encryptedSizeBytes = imported.encryptedSizeBytes,
            encryptedRelativePath = imported.encryptedRelativePath,
            plaintextSha256Hex = imported.plaintextSha256Hex,
            createdAt = now,
            modifiedAt = now,
            syncVersion = 1L,
            lastModifiedByDeviceId = deviceId,
            syncPending = true
        )

        db.withTransaction {
            db.attachmentDao().upsert(entity)
            queueSyncOp(
                entityType = "ATTACHMENT",
                entityId = entity.id,
                opType = "UPSERT",
                baseVersion = 0L,
                newVersion = 1L,
                deviceId = deviceId,
                timestamp = now,
                payloadJson = json.encodeToString(
                    SerializableAttachmentSnapshot(
                        entity.id, entity.recordId, entity.originalFileName, entity.mimeType,
                        entity.attachmentKind, entity.plaintextSizeBytes, entity.encryptedSizeBytes,
                        entity.encryptedRelativePath, entity.plaintextSha256Hex, entity.createdAt,
                        entity.modifiedAt, entity.isDeleted, entity.deletedAt, entity.syncVersion,
                        entity.lastModifiedByDeviceId
                    )
                )
            )
        }
        triggerAutoSyncIfEnabled()
        return toAttachmentDomain(entity)
    }

    override suspend fun exportAttachmentToStream(attachmentId: String, output: OutputStream) =
        withContext(Dispatchers.IO) {
            val att = getAttachmentById(attachmentId)
                ?: throw IllegalArgumentException("Attachment not found.")
            attachmentStorage.decryptToOutputStream(att, output)
            Unit
        }

    override suspend fun prepareEphemeralViewerFile(attachmentId: String): File =
        withContext(Dispatchers.IO) {
            val att = getAttachmentById(attachmentId)
                ?: throw IllegalArgumentException("Attachment not found.")
            attachmentStorage.decryptToEphemeralCacheFile(att)
        }

    /**
     * P5 (requirement 1/2/4): removing an attachment from a note is a SOFT delete.
     *
     * Before P5 this method deleted the ciphertext and hard-deleted the row, so the attachment could
     * never be recovered — not by the recycle bin, not by a backup taken afterwards. It now only
     * flips `isDeleted` (keeping `createdAt`, the file path and the plaintext hash) and leaves the
     * encrypted bytes in place. The bytes are removed only by a real permanent deletion
     * ([permanentlyDeleteBatch] / [emptyRecycleBin] / [purgeExpiredRecycleBinItems]) or by the
     * orphan sweep, which is part of permanent deletion.
     */
    override suspend fun deleteAttachment(attachmentId: String) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        MediaReferenceIndex.withMediaLock delete@{
            val existing = db.attachmentDao().getAttachmentById(attachmentId) ?: return@delete
            if (existing.isDeleted) return@delete

            // P6 (decision 2): an attachment that an unsaved draft still needs must NOT disappear
            // under the editor. The draft lives in record_drafts keyed "EDIT_<recordId>" and its
            // body can reference the attachment's note; refusing is always recoverable, deleting
            // silently is not.
            val draftHolders = MediaReferenceIndex.draftKeysForRecord(db, existing.recordId)
            if (draftHolders.isNotEmpty()) {
                throw IllegalStateException(
                    "This attachment cannot be deleted because the note is being edited and has unsaved " +
                        "changes (${draftHolders.joinToString(", ")}). Save or discard the draft, then delete it."
                )
            }

            val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
            val now = timeProvider.nowMillis()
            val batchId = IdGenerator.newUuid()

            db.withTransaction {
                db.attachmentDao().upsert(
                    existing.copy(
                        isDeleted = true,
                        deletedAt = now,
                        modifiedAt = now,
                        syncVersion = existing.syncVersion + 1,
                        lastModifiedByDeviceId = deviceId,
                        syncPending = true
                    )
                )
                // P6 (decision 1): a standalone deleted attachment gets its own recycle-bin batch, so
                // it can be restored or permanently deleted while its note stays active. The batch
                // table is reused as-is and the payload format does not change.
                db.recycleBinDao().upsert(
                    DeletedItemEntity(
                        batchId = batchId,
                        rootEntityType = ROOT_ATTACHMENT,
                        rootEntityId = existing.id,
                        displayTitle = existing.originalFileName,
                        parentFolderIdSnapshot = existing.recordId,
                        subfolderCount = 0,
                        recordCount = 0,
                        deletedAtMillis = now,
                        expiresAtMillis = now + RECYCLE_RETENTION_MILLIS
                    )
                )
                db.timelineDao().insert(
                    TimelineEventEntity(
                        id = IdGenerator.newUuid(),
                        eventType = TimelineEventType.RECORD_DELETED.name,
                        entityType = ROOT_ATTACHMENT,
                        entityId = existing.id,
                        displayTitle = existing.originalFileName,
                        accentColor = "RED",
                        timestampMillis = now
                    )
                )
                queueSyncOp("ATTACHMENT", attachmentId, "TOMBSTONE", existing.syncVersion, existing.syncVersion + 1, deviceId, now, "{}")
            }
        }
        triggerAutoSyncIfEnabled()
    }

    // ==================== OFFLINE GLOBAL SEARCH ====================

    override suspend fun searchVault(filters: SearchFilters): SearchResultsBundle =
        withContext(Dispatchers.IO) {
            val db = dbHolder.getDatabase()
            val rawQ = filters.query.trim()
            val allFolders = db.folderDao().getAllActiveFolders()
            val foldersById = allFolders.associateBy { it.id }
            val allAttachments = db.attachmentDao().getAllActiveAttachments()
            val attCountByRecord = allAttachments.groupingBy { it.recordId }.eachCount()

            // Matching folders
            val matchingFolders = if (rawQ.isEmpty()) {
                emptyList()
            } else {
                db.folderDao().searchFoldersByName(rawQ).map { f ->
                    Folder(
                        id = f.id,
                        name = f.name,
                        parentFolderId = f.parentFolderId,
                        color = FolderAccentColor.fromId(f.colorId),
                        createdAt = f.createdAt,
                        modifiedAt = f.modifiedAt,
                        sortOrder = f.sortOrder
                    )
                }
            }

            // Matching attachments
            val matchingAttachments = if (rawQ.isEmpty() && filters.attachmentKind == null) {
                emptyList()
            } else {
                allAttachments.filter { att ->
                    val qMatch = rawQ.isEmpty() ||
                        att.originalFileName.contains(rawQ, ignoreCase = true) ||
                        att.mimeType.contains(rawQ, ignoreCase = true)
                    val kindMatch = filters.attachmentKind == null || att.attachmentKind == filters.attachmentKind.name
                    qMatch && kindMatch
                }.map { toAttachmentDomain(it) }
            }

            // Candidate records via FTS4 + substring + folder/attachment/date matches
            val candidateRecords: List<RecordEntity> = if (rawQ.isEmpty()) {
                db.recordDao().getAllActiveRecords()
            } else {
                val ftsTokens = rawQ.split(Regex("\\s+"))
                    .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
                    .filter { it.isNotEmpty() }
                val ftsHits = if (ftsTokens.isNotEmpty()) {
                    val ftsExpr = ftsTokens.joinToString(" ") { "$it*" }
                    runCatching { db.recordDao().searchRecordsFts(ftsExpr) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val substringHits = db.recordDao().searchRecordsSubstring(rawQ)
                val attachmentRecordIds = matchingAttachments.map { it.recordId }.toSet()
                val matchingFolderIds = matchingFolders.map { it.id }.toSet()
                val extraHits = db.recordDao().getAllActiveRecords().filter { rec ->
                    rec.id in attachmentRecordIds ||
                        (rec.folderId != null && rec.folderId in matchingFolderIds) ||
                        DateFormatters.formatDate(rec.modifiedAt, timeProvider.zoneId()).contains(rawQ, ignoreCase = true) ||
                        DateFormatters.formatDate(rec.createdAt, timeProvider.zoneId()).contains(rawQ, ignoreCase = true)
                }
                (ftsHits + substringHits + extraHits).distinctBy { it.id }.filter { rec ->
                    val cleanBody = app.personalspace.vault.presentation.records.RichNoteCodec.extractPlainText(rec.description)
                    rec.id in attachmentRecordIds ||
                        (rec.folderId != null && rec.folderId in matchingFolderIds) ||
                        rec.title.contains(rawQ, ignoreCase = true) ||
                        cleanBody.contains(rawQ, ignoreCase = true) ||
                        (rec.category?.contains(rawQ, ignoreCase = true) == true) ||
                        rec.customFieldsJson.contains(rawQ, ignoreCase = true) ||
                        DateFormatters.formatDate(rec.modifiedAt, timeProvider.zoneId()).contains(rawQ, ignoreCase = true) ||
                        DateFormatters.formatDate(rec.createdAt, timeProvider.zoneId()).contains(rawQ, ignoreCase = true)
                }
            }

            // Allowed folder filter (includes subfolders of selected folder)
            val allowedFolderIds: Set<String>? = filters.folderId?.let { collectSubtreeFolderIds(it, allFolders) }
            val recordIdsWithAttachmentKind: Set<String>? = filters.attachmentKind?.let { kind ->
                allAttachments.filter { it.attachmentKind == kind.name }.map { it.recordId }.toSet()
            }

            val filtered = candidateRecords.filter { rec ->
                val folderOk = allowedFolderIds == null || (rec.folderId != null && rec.folderId in allowedFolderIds)
                val catOk = filters.category.isNullOrBlank() || rec.category.equals(filters.category, ignoreCase = true)
                val attOk = recordIdsWithAttachmentKind == null || rec.id in recordIdsWithAttachmentKind
                val fromOk = filters.fromDateMillis == null || rec.modifiedAt >= filters.fromDateMillis
                val toOk = filters.toDateMillis == null || rec.modifiedAt <= filters.toDateMillis
                folderOk && catOk && attOk && fromOk && toOk
            }.map { toRecordDomain(it, foldersById, attCountByRecord) }

            val sorted = when (filters.sortBy) {
                SortOption.RELEVANCE -> {
                    if (rawQ.isEmpty()) {
                        filtered.sortedByDescending { it.modifiedAt }
                    } else {
                        filtered.sortedWith(
                            compareByDescending<RecordItem> { relevanceScore(it, rawQ) }
                                .thenByDescending { it.modifiedAt }
                        )
                    }
                }
                SortOption.RECENTLY_MODIFIED -> filtered.sortedByDescending { it.modifiedAt }
                SortOption.RECENTLY_CREATED -> filtered.sortedByDescending { it.createdAt }
                SortOption.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
                SortOption.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            }

            SearchResultsBundle(
                records = sorted,
                matchingFolders = matchingFolders,
                matchingAttachments = matchingAttachments
            )
        }

    private fun relevanceScore(record: RecordItem, query: String): Int {
        var score = 0
        if (record.title.equals(query, ignoreCase = true)) score += 100
        if (record.title.startsWith(query, ignoreCase = true)) score += 50
        if (record.title.contains(query, ignoreCase = true)) score += 30
        if (record.category?.contains(query, ignoreCase = true) == true) score += 20
        if (record.folderName?.contains(query, ignoreCase = true) == true) score += 15
        if (record.description.contains(query, ignoreCase = true)) score += 10
        return score
    }

    // ==================== REMINDERS & CALENDAR ====================

    override fun observeAllReminders(): Flow<List<ReminderItem>> = unlockedFlow(emptyList()) {
        val db = dbHolder.getDatabase()
        combine(
            db.reminderDao().observeAllActiveReminders(),
            db.recordDao().observeAllActiveRecords()
        ) { reminders, records ->
            val recordsById = records.associateBy { it.id }
            reminders.map { toReminderDomain(it, recordsById) }
        }
    }

    override fun observeReminderOccurrences(): Flow<List<ReminderOccurrence>> =
        unlockedFlow(emptyList()) {
            dbHolder.getDatabase().reminderDao().observeAllOccurrences().map { list ->
                list.map {
                    ReminderOccurrence(
                        id = it.id,
                        reminderId = it.reminderId,
                        reminderTitle = it.reminderTitle,
                        occurrenceScheduledMillis = it.occurrenceScheduledMillis,
                        actualHandledMillis = it.actualHandledMillis,
                        outcome = ReminderStatus.entries.firstOrNull { s -> s.name == it.outcome } ?: ReminderStatus.COMPLETED
                    )
                }
            }
        }

    override suspend fun getReminderById(reminderId: String): ReminderItem? = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val entity = db.reminderDao().getReminderById(reminderId)?.takeIf { !it.isDeleted } ?: return@withContext null
        val recMap = entity.linkedRecordId?.let { id ->
            db.recordDao().getRecordById(id)?.let { mapOf(id to it) }
        }.orEmpty()
        toReminderDomain(entity, recMap)
    }

    override suspend fun saveReminder(
        existingReminderId: String?,
        title: String,
        notes: String,
        scheduledAtMillis: Long,
        recurrence: RecurrenceType,
        priority: ReminderPriority,
        linkedRecordId: String?
    ): Pair<ReminderItem, Boolean> = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Reminder title cannot be empty." }

        val db = dbHolder.getDatabase()
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()
        val existing = existingReminderId?.let { db.reminderDao().getReminderById(it) }

        val entity = if (existing == null) {
            ReminderEntity(
                id = IdGenerator.newUuid(),
                title = cleanTitle,
                notes = notes.trim(),
                scheduledAtMillis = scheduledAtMillis,
                recurrenceType = recurrence.name,
                priority = priority.name,
                status = ReminderStatus.PENDING.name,
                snoozedUntilMillis = null,
                linkedRecordId = linkedRecordId,
                createdAt = now,
                modifiedAt = now,
                syncVersion = 1L,
                lastModifiedByDeviceId = deviceId,
                syncPending = true
            )
        } else {
            existing.copy(
                title = cleanTitle,
                notes = notes.trim(),
                scheduledAtMillis = scheduledAtMillis,
                recurrenceType = recurrence.name,
                priority = priority.name,
                status = ReminderStatus.PENDING.name,
                snoozedUntilMillis = null,
                linkedRecordId = linkedRecordId,
                modifiedAt = now,
                syncVersion = existing.syncVersion + 1,
                lastModifiedByDeviceId = deviceId,
                syncPending = true
            )
        }

        db.reminderDao().upsert(entity)
        val domainItem = getReminderById(entity.id)!!
        val exactGranted = reminderScheduler.scheduleReminder(domainItem)
        domainItem to exactGranted
    }

    override suspend fun completeReminder(reminderId: String) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val entity = db.reminderDao().getReminderById(reminderId) ?: return@withContext
        val now = timeProvider.nowMillis()
        val recurrence = RecurrenceType.entries.firstOrNull { it.name == entity.recurrenceType } ?: RecurrenceType.ONCE

        db.withTransaction {
            db.reminderDao().insertOccurrence(
                ReminderOccurrenceEntity(
                    id = IdGenerator.newUuid(),
                    reminderId = entity.id,
                    reminderTitle = entity.title,
                    occurrenceScheduledMillis = entity.scheduledAtMillis,
                    actualHandledMillis = now,
                    outcome = ReminderStatus.COMPLETED.name
                )
            )
            val nextScheduled = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
                currentScheduledMillis = entity.scheduledAtMillis,
                recurrence = recurrence,
                nowMillis = now,
                zoneId = timeProvider.zoneId()
            )
            val updated = if (nextScheduled != null) {
                entity.copy(
                    scheduledAtMillis = nextScheduled,
                    status = ReminderStatus.PENDING.name,
                    snoozedUntilMillis = null,
                    modifiedAt = now,
                    syncVersion = entity.syncVersion + 1
                )
            } else {
                entity.copy(
                    status = ReminderStatus.COMPLETED.name,
                    snoozedUntilMillis = null,
                    modifiedAt = now,
                    syncVersion = entity.syncVersion + 1
                )
            }
            db.reminderDao().upsert(updated)
            db.timelineDao().insert(
                TimelineEventEntity(
                    id = IdGenerator.newUuid(),
                    eventType = TimelineEventType.REMINDER_COMPLETED.name,
                    entityType = "REMINDER",
                    entityId = entity.id,
                    displayTitle = entity.title,
                    accentColor = "BLUE",
                    timestampMillis = now
                )
            )
        }

        val refreshed = getReminderById(reminderId)
        if (refreshed != null && refreshed.status == ReminderStatus.PENDING) {
            reminderScheduler.scheduleReminder(refreshed)
        } else {
            reminderScheduler.cancelReminder(reminderId)
        }
    }

    override suspend fun snoozeReminder(reminderId: String, snoozeMinutes: Int) =
        withContext(Dispatchers.IO) {
            val db = dbHolder.getDatabase()
            val entity = db.reminderDao().getReminderById(reminderId) ?: return@withContext
            val now = timeProvider.nowMillis()
            val snoozeUntil = now + snoozeMinutes.coerceAtLeast(1) * 60_000L
            val updated = entity.copy(
                status = ReminderStatus.SNOOZED.name,
                snoozedUntilMillis = snoozeUntil,
                modifiedAt = now,
                syncVersion = entity.syncVersion + 1
            )
            db.withTransaction {
                db.reminderDao().upsert(updated)
                db.reminderDao().insertOccurrence(
                    ReminderOccurrenceEntity(
                        id = IdGenerator.newUuid(),
                        reminderId = entity.id,
                        reminderTitle = entity.title,
                        occurrenceScheduledMillis = entity.scheduledAtMillis,
                        actualHandledMillis = now,
                        outcome = ReminderStatus.SNOOZED.name
                    )
                )
            }
            getReminderById(reminderId)?.let { reminderScheduler.scheduleReminder(it) }
            Unit
        }

    override suspend fun refreshMissedReminders() = withContext(Dispatchers.IO) {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) return@withContext
        val db = dbHolder.getDatabase()
        val now = timeProvider.nowMillis()
        val overdueGraceMillis = 60_000L
        val active = db.reminderDao().getAllActiveReminders()

        for (r in active) {
            if (r.status == ReminderStatus.PENDING.name || r.status == ReminderStatus.SNOOZED.name) {
                val trigger = r.snoozedUntilMillis ?: r.scheduledAtMillis
                if (trigger + overdueGraceMillis < now) {
                    db.withTransaction {
                        db.reminderDao().upsert(
                            r.copy(
                                status = ReminderStatus.MISSED.name,
                                snoozedUntilMillis = null,
                                modifiedAt = now
                            )
                        )
                        db.reminderDao().insertOccurrence(
                            ReminderOccurrenceEntity(
                                id = IdGenerator.newUuid(),
                                reminderId = r.id,
                                reminderTitle = r.title,
                                occurrenceScheduledMillis = trigger,
                                actualHandledMillis = now,
                                outcome = ReminderStatus.MISSED.name
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun deleteReminder(reminderId: String) = withContext(Dispatchers.IO) {
        reminderScheduler.cancelReminder(reminderId)
        dbHolder.getDatabase().reminderDao().hardDeleteById(reminderId)
    }

    // ==================== TIMELINE & RECYCLE BIN ====================

    override fun observeTimelineEvents(): Flow<List<TimelineEvent>> = unlockedFlow(emptyList()) {
        dbHolder.getDatabase().timelineDao().observeAllEvents().map { list ->
            list.map { toTimelineDomain(it) }
        }
    }

    override fun observeDeletedItems(): Flow<List<DeletedVaultItem>> = unlockedFlow(emptyList()) {
        dbHolder.getDatabase().recycleBinDao().observeDeletedItems().map { list ->
            list.map {
                DeletedVaultItem(
                    batchId = it.batchId,
                    rootEntityType = it.rootEntityType,
                    rootEntityId = it.rootEntityId,
                    displayTitle = it.displayTitle,
                    subfolderCount = it.subfolderCount,
                    recordCount = it.recordCount,
                    deletedAtMillis = it.deletedAtMillis,
                    expiresAtMillis = it.expiresAtMillis
                )
            }
        }
    }

    override suspend fun restoreDeletedBatch(batchId: String) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val batch = db.recycleBinDao().getDeletedBatch(batchId) ?: return@withContext
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()

        // P6 (decision 1): a standalone deleted attachment is restored on its own — its bytes were
        // never removed, so this only clears the deletion state and drops the batch.
        if (batch.rootEntityType == ROOT_ATTACHMENT) {
            MediaReferenceIndex.withMediaLock {
                db.withTransaction {
                    db.attachmentDao().getAttachmentById(batch.rootEntityId)?.let { row ->
                        db.attachmentDao().upsert(
                            row.copy(
                                isDeleted = false,
                                deletedAt = null,
                                modifiedAt = now,
                                syncVersion = row.syncVersion + 1,
                                lastModifiedByDeviceId = deviceId,
                                syncPending = true
                            )
                        )
                    }
                    db.recycleBinDao().deleteBatch(batchId)
                }
            }
            triggerAutoSyncIfEnabled()
            return@withContext
        }

        val foldersInBatch = db.folderDao().getFoldersByDeletedBatch(batchId)
        val recordsInBatch = db.recordDao().getRecordsByDeletedBatch(batchId)
        val activeFolderIds = db.folderDao().getAllActiveFolders().map { it.id }.toSet()
        val batchFolderIds = foldersInBatch.map { it.id }.toSet()

        db.withTransaction {
            if (foldersInBatch.isNotEmpty()) {
                db.folderDao().upsertAll(
                    foldersInBatch.map { f ->
                        val parentStillValid = f.parentFolderId == null ||
                            f.parentFolderId in activeFolderIds ||
                            f.parentFolderId in batchFolderIds
                        val restored = f.copy(
                            parentFolderId = if (parentStillValid) f.parentFolderId else null,
                            isDeleted = false,
                            deletedAt = null,
                            deletedBatchId = null,
                            modifiedAt = now,
                            syncVersion = f.syncVersion + 1,
                            lastModifiedByDeviceId = deviceId,
                            syncPending = true
                        )
                        restored
                    }
                )
            }

            if (recordsInBatch.isNotEmpty()) {
                db.recordDao().upsertAll(
                    recordsInBatch.map { r ->
                        val folderStillValid = r.folderId == null ||
                            r.folderId in activeFolderIds ||
                            r.folderId in batchFolderIds
                        r.copy(
                            folderId = if (folderStillValid) r.folderId else null,
                            isDeleted = false,
                            deletedAt = null,
                            deletedBatchId = null,
                            modifiedAt = now,
                            syncVersion = r.syncVersion + 1,
                            lastModifiedByDeviceId = deviceId,
                            syncPending = true
                        )
                    }
                )
            }

            // P5 (requirement 2): bring back the attachments that were deleted with these records,
            // together with their original timestamps and relationships (recordId is unchanged).
            val restoredRecordIds = recordsInBatch.map { it.id }
            if (restoredRecordIds.isNotEmpty()) {
                val attachments = db.attachmentDao().getAttachmentsForRecords(restoredRecordIds)
                    .filter { it.isDeleted }
                if (attachments.isNotEmpty()) {
                    db.attachmentDao().upsertAll(
                        attachments.map { att ->
                            att.copy(
                                isDeleted = false,
                                deletedAt = null,
                                modifiedAt = now,
                                syncVersion = att.syncVersion + 1,
                                lastModifiedByDeviceId = deviceId,
                                syncPending = true
                            )
                        }
                    )
                }
            }

            db.recycleBinDao().deleteBatch(batchId)
            db.timelineDao().insert(
                TimelineEventEntity(
                    id = IdGenerator.newUuid(),
                    eventType = if (batch.rootEntityType == "FOLDER") {
                        TimelineEventType.FOLDER_RESTORED.name
                    } else {
                        TimelineEventType.RECORD_RESTORED.name
                    },
                    entityType = batch.rootEntityType,
                    entityId = batch.rootEntityId,
                    displayTitle = batch.displayTitle,
                    accentColor = "YELLOW",
                    timestampMillis = now
                )
            )
        }
        triggerAutoSyncIfEnabled()
    }

    override suspend fun permanentlyDeleteBatch(batchId: String) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()

        // P6 (decision 4): a permanent deletion must never remove a file that a surviving entity
        // still references, so the whole decision runs under the media write lock — the same lock
        // every reference-creating path takes. And it refuses to run at all while a restore is in
        // flight, because the restore's staging swap installs references the caller cannot see.
        MediaReferenceIndex.withMediaLock {
            if (MediaReferenceIndex.isRestoreInFlight()) {
                throw IllegalStateException(
                    "A restore is in progress. Nothing was deleted — retry the permanent deletion after the restore finishes."
                )
            }
            permanentlyDeleteBatchLocked(db, batchId)
            // NOTE (P6 item 8): there is deliberately no orphan sweep here any more. The previous
            // sweep deleted unreferenced .psenc files straight from the permanent-delete path, which
            // is exactly the automatic deletion the approved design forbids. Unreferenced files are
            // now only ever *detected* (read-only) and, if the user asks, moved into quarantine by
            // OrphanQuarantineManager — never deleted by a delete code path.
        }
        Unit
    }

    /**
     * P6 (decision 4): deletes an inline image file only when nothing else needs it.
     *
     * The editor removes the image block from the note body in memory; this is asked afterwards.
     * Counting runs under the media write lock and looks at **every** record (active and in the
     * recycle bin) and **every** draft. The note being edited is excluded from the count for the
     * reference the user is removing, but a *different* reference from the same note still counts.
     */
    override suspend fun deleteInlineImageIfUnreferenced(
        imagePath: String,
        editingRecordId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val identifier = app.personalspace.vault.data.backup.InlineImageStore
            .normalizeImageReference(imagePath)
        if (identifier.isBlank()) return@withContext false
        MediaReferenceIndex.withMediaLock {
            val holders = MediaReferenceIndex.imageHolders(db, identifier)
                .filterNot { editingRecordId != null && !it.isDraft && it.ownerId == editingRecordId }
                .filterNot { editingRecordId != null && it.isDraft && it.ownerId == "EDIT_$editingRecordId" }
            if (holders.isEmpty()) {
                inlineImageStore.deleteImage(identifier)
                true
            } else {
                false
            }
        }
    }

    /** The body of [permanentlyDeleteBatch]; always called while holding the media write lock. */
    private suspend fun permanentlyDeleteBatchLocked(db: PersonalSpaceDatabase, batchId: String) {
        val batch = db.recycleBinDao().getDeletedBatch(batchId) ?: return

        // ---- P6: a standalone deleted attachment ------------------------------------------------
        if (batch.rootEntityType == ROOT_ATTACHMENT) {
            val row = db.attachmentDao().getAttachmentById(batch.rootEntityId)
            val path = row?.encryptedRelativePath

            // ORDER MATTERS (found by the P6-F2 failure-injection test): the bytes are attempted
            // first, while the row still exists. If the unlink throws, the transaction below never
            // runs, so the row and the batch survive and the attachment remains recoverable. Doing
            // it the other way round deleted the row and then failed to delete the file, leaving an
            // unreachable ciphertext that no UI could ever restore or remove.
            if (path != null && row != null) {
                val sharedWithOthers = MediaReferenceIndex.attachmentRowsClaiming(db, File(path).name)
                    .any { it != row.id }
                if (!sharedWithOthers) {
                    // Strict: throws when the file could not actually be removed, so the rows below
                    // are not deleted and the attachment stays recoverable and retryable.
                    attachmentStorage.deleteEncryptedFileStrict(path)
                }
            }

            db.withTransaction {
                if (row != null) db.attachmentDao().hardDeleteById(row.id)
                db.recycleBinDao().deleteBatch(batchId)
            }
            return
        }

        // ---- folder / record batches (unchanged behaviour, reference-counted unlink) ------------
        val foldersInBatch = db.folderDao().getFoldersByDeletedBatch(batchId)
        val recordsInBatch = db.recordDao().getRecordsByDeletedBatch(batchId)
        val recordIds = recordsInBatch.map { it.id }

        // Paths are captured BEFORE the transaction: after it the rows are gone and the files could
        // not be located. Deletion of the bytes happens after the transaction, reference-counted.
        val attachmentsOfBatch = if (recordIds.isNotEmpty()) {
            db.attachmentDao().getAttachmentsForRecords(recordIds)
        } else {
            emptyList()
        }
        val attachmentPaths = attachmentsOfBatch.map { it.encryptedRelativePath }

        db.withTransaction {
            if (attachmentsOfBatch.isNotEmpty()) {
                db.attachmentDao().hardDeleteByIds(attachmentsOfBatch.map { it.id })
            }
            if (recordIds.isNotEmpty()) {
                db.recordDao().hardDeleteByIds(recordIds)
            }
            if (foldersInBatch.isNotEmpty()) {
                db.folderDao().hardDeleteByIds(foldersInBatch.map { it.id })
            }
            // any attachment batch of these records dies with them
            db.recycleBinDao().deleteAttachmentBatchesForRecords(recordIds)
            db.recycleBinDao().deleteBatch(batchId)
        }

        // Bytes last, and only while nobody else claims them (§4 fix; measured defect in P5).
        attachmentPaths.distinct().forEach { unlinkAttachmentIfUnreferenced(db, it) }
    }

    /**
     * Unlinks one attachment file **only if no row (active or soft-deleted) still references it**.
     *
     * Must be called with the media write lock held — the reference set is read and acted on inside
     * that lock, so no reference can be created between the check and the unlink.
     */
    private suspend fun unlinkAttachmentIfUnreferenced(db: PersonalSpaceDatabase, relativePath: String) {
        val fileName = File(relativePath).name
        val survivors = MediaReferenceIndex.attachmentRowsClaiming(db, fileName)
        if (survivors.isEmpty()) {
            attachmentStorage.deleteEncryptedFile(relativePath)
        }
    }

    /**
     * Empties the recycle bin, visiting EVERY batch.
     *
     * Previously this was `for (b in batches) permanentlyDeleteBatch(b.batchId)`: one failure (a file
     * that cannot be unlinked, a restore in flight, a transient IO error) threw out of the loop and
     * every remaining item was silently left behind, with no indication which item was the problem.
     * The loop now isolates each batch, keeps failing items in the recycle bin for a later retry, and
     * returns an explicit report.
     */
    override suspend fun emptyRecycleBin(): RecycleBinPurgeResult = withContext(Dispatchers.IO) {
        purgeBatches(dbHolder.getDatabase().recycleBinDao().getAllDeletedItems())
    }

    override suspend fun purgeExpiredRecycleBinItems(): RecycleBinPurgeResult = withContext(Dispatchers.IO) {
        purgeBatches(dbHolder.getDatabase().recycleBinDao().getExpiredBatches(timeProvider.nowMillis()))
    }

    private suspend fun purgeBatches(
        batches: List<app.personalspace.vault.data.local.entity.DeletedItemEntity>
    ): RecycleBinPurgeResult {
        val failed = ArrayList<RecycleBinPurgeResult.FailedBatch>()
        var deleted = 0
        for (batch in batches) {
            val outcome = runCatching { permanentlyDeleteBatch(batch.batchId) }
            val error = outcome.exceptionOrNull()
            if (error == null) {
                deleted++
            } else {
                // the batch is deliberately left in place: nothing was removed for it, so the user can
                // fix the cause (free space, finish the restore) and retry
                failed.add(
                    RecycleBinPurgeResult.FailedBatch(
                        batchId = batch.batchId,
                        displayTitle = batch.displayTitle,
                        rootEntityType = batch.rootEntityType,
                        reason = error.message ?: error.javaClass.simpleName,
                        retained = true
                    )
                )
            }
        }
        return RecycleBinPurgeResult(attempted = batches.size, deleted = deleted, failed = failed)
    }

    // ==================== BACKUP, RESTORE & SYNC ====================

    override fun observeSyncHealthState(): Flow<SyncHealthState> =
        unlockedFlow(SyncHealthState.NOT_CONNECTED) {
            val db = dbHolder.getDatabase()
            combine(
                db.syncDao().observeSyncMetadata(),
                db.syncDao().observePendingOperationCount(),
                db.syncDao().observeUnresolvedConflictCount()
            ) { meta, pendingOps, conflicts ->
                resolveHonestSyncState(meta?.syncStatus, pendingOps, conflicts)
            }
        }

    override fun observeBackupHistory(): Flow<List<BackupHistoryItem>> = unlockedFlow(emptyList()) {
        dbHolder.getDatabase().backupDao().observeBackupHistory().map { list ->
            list.map { toBackupDomain(it) }
        }
    }

    override fun observeUnresolvedConflicts(): Flow<List<SyncConflictItem>> =
        unlockedFlow(emptyList()) {
            dbHolder.getDatabase().syncDao().observeUnresolvedConflicts().map { list ->
                list.map {
                    SyncConflictItem(
                        conflictId = it.conflictId,
                        entityType = it.entityType,
                        entityId = it.entityId,
                        conflictKind = ConflictKind.entries.firstOrNull { k -> k.name == it.conflictKind }
                            ?: ConflictKind.CONCURRENT_EDIT,
                        localTitle = it.localTitle,
                        localPayloadJson = it.localPayloadJson,
                        localModifiedAt = it.localModifiedAt,
                        localDeviceId = it.localDeviceId,
                        remoteTitle = it.remoteTitle,
                        remotePayloadJson = it.remotePayloadJson,
                        remoteModifiedAt = it.remoteModifiedAt,
                        remoteDeviceId = it.remoteDeviceId,
                        detectedAtMillis = it.detectedAtMillis,
                        resolvedAtMillis = it.resolvedAtMillis
                    )
                }
            }
        }

    override suspend fun createManualLocalBackup(destinationStream: OutputStream?): BackupHistoryItem =
        withContext(Dispatchers.IO) {
            val outcome = backupManager.createFullBackupFile(BackupKind.FULL_MANUAL)
            if (destinationStream != null) {
                backupManager.copyBackupToStream(outcome.file, destinationStream)
            }
            BackupHistoryItem(
                backupId = outcome.backupId,
                backupKind = BackupKind.FULL_MANUAL,
                destination = "LOCAL_FILE",
                locationRef = outcome.file.absolutePath,
                sizeBytes = outcome.sizeBytes,
                recordCount = outcome.recordCount,
                attachmentCount = outcome.attachmentCount,
                sha256Checksum = outcome.sha256Hex,
                verified = true,
                succeeded = true,
                createdAtMillis = timeProvider.nowMillis()
            )
        }

    override suspend fun uploadManualBackupToDrive(): BackupHistoryItem = withContext(Dispatchers.IO) {
        val session = authManager.getCurrentSession()
            ?: throw IllegalStateException("Google Drive is not connected. Connect Drive first in Settings.")
        val created = backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
        val now = timeProvider.nowMillis()
        try {
            val uploaded = remoteDataSource.uploadOrReplaceInAppData(
                accessToken = session.accessToken,
                remoteName = DRIVE_BACKUP_NAME,
                localCiphertextFile = created.file
            )
            val entity = BackupMetadataEntity(
                backupId = created.backupId,
                backupKind = BackupKind.FULL_MANUAL.name,
                destination = "GOOGLE_DRIVE",
                locationRef = uploaded.id,
                sizeBytes = created.sizeBytes,
                recordCount = created.recordCount,
                attachmentCount = created.attachmentCount,
                sha256Checksum = created.sha256Hex,
                verified = true,
                succeeded = true,
                createdAtMillis = now
            )
            dbHolder.getDatabase().backupDao().upsert(entity)
            pruneDriveBackups(DRIVE_BACKUPS_KEPT)
            toBackupDomain(entity)
        } catch (e: Exception) {
            val failedEntity = BackupMetadataEntity(
                backupId = created.backupId,
                backupKind = BackupKind.FULL_MANUAL.name,
                destination = "GOOGLE_DRIVE",
                locationRef = created.file.name,
                sizeBytes = created.sizeBytes,
                recordCount = created.recordCount,
                attachmentCount = created.attachmentCount,
                sha256Checksum = created.sha256Hex,
                verified = false,
                succeeded = false,
                errorMessage = e.message ?: "Drive backup upload failed",
                createdAtMillis = now
            )
            dbHolder.getDatabase().backupDao().upsert(failedEntity)
            throw e
        }
    }

    override suspend fun verifyBackupFile(file: File): Boolean = withContext(Dispatchers.IO) {
        backupManager.inspectAndValidatePackageStructure(file)
        true
    }

    private fun requireDriveToken(): String {
        val session = authManager.getCurrentSession()
            ?: throw IllegalStateException("Google Drive is not connected. Connect Drive first in Settings.")
        return session.accessToken
    }

    override suspend fun listDriveBackups(): List<DriveBackupFile> = withContext(Dispatchers.IO) {
        val token = requireDriveToken()
        remoteDataSource.listAppDataFiles(token)
            .filter { it.name.endsWith(".psbackup") }
            .map { file ->
                DriveBackupFile(
                    fileId = file.id,
                    fileName = file.name,
                    sizeBytes = file.sizeBytes,
                    createdMillis = file.createdTime
                        ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?: 0L
                )
            }
            .sortedByDescending { it.createdMillis }
    }

    override suspend fun restoreFromDriveBackup(
        fileId: String,
        recoveryPassword: CharArray?
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
            val token = requireDriveToken()
            val temp = backupManager.newRestoreTempFile()
            try {
                remoteDataSource.downloadEncryptedFile(token, fileId, temp)
                backupManager.restoreFromBackupFile(
                    backupFile = temp,
                    recoveryPassword = recoveryPassword?.copyOf(),
                    requireSafetyBackup = true
                )
            } finally {
                temp.delete()
                recoveryPassword?.fill('\u0000')
            }
        }

    override suspend fun pruneDriveBackups(keep: Int): Int = withContext(Dispatchers.IO) {
        runCatching {
            val token = requireDriveToken()
            val backups = remoteDataSource.listAppDataFiles(token)
                .filter { it.name.endsWith(".psbackup") }
                .sortedByDescending { it.createdTime.orEmpty() }
            val stale = backups.drop(keep)
            for (file in stale) {
                runCatching { remoteDataSource.deleteFile(token, file.id) }
            }
            stale.size
        }.getOrElse { 0 }
    }

    override suspend fun runSyncNow(): SyncExecutionReport = syncEngine.runSyncCycle()

    override suspend fun resolveConflict(
        conflictId: String,
        choice: ConflictResolutionChoice,
        mergedTitle: String?,
        mergedDescription: String?
    ) {
        syncEngine.resolveConflict(conflictId, choice, mergedTitle, mergedDescription)
    }

    override fun observeUserSettings(): Flow<UserSettings> = preferences.settingsFlow

    // ==================== PRIVATE HELPERS ====================

    private suspend fun queueSyncOp(
        entityType: String,
        entityId: String,
        opType: String,
        baseVersion: Long,
        newVersion: Long,
        deviceId: String,
        timestamp: Long,
        payloadJson: String
    ) {
        dbHolder.getDatabase().syncDao().insertOperation(
            SyncOperationEntity(
                operationId = IdGenerator.newUuid(),
                entityType = entityType,
                entityId = entityId,
                operationType = opType,
                baseVersion = baseVersion,
                newVersion = newVersion,
                deviceId = deviceId,
                timestampMillis = timestamp,
                payloadJson = payloadJson
            )
        )
    }

    private suspend fun triggerAutoSyncIfEnabled() {
        val settings = preferences.settingsFlow.first()
        if (settings.autoIncrementalSync && authManager.isConnected()) {
            workCoordinator.enqueueIncrementalSyncWhenConnected()
        }
    }

    private fun resolveHonestSyncState(
        syncMetaStatus: String?,
        pendingOps: Int,
        unresolvedConflicts: Int
    ): SyncHealthState {
        if (unresolvedConflicts > 0) return SyncHealthState.ATTENTION_REQUIRED
        if (syncMetaStatus == SyncHealthState.SYNCING.name) return SyncHealthState.SYNCING
        if (syncMetaStatus == SyncHealthState.FAILED.name) return SyncHealthState.FAILED
        if (syncMetaStatus == SyncHealthState.ATTENTION_REQUIRED.name) return SyncHealthState.ATTENTION_REQUIRED
        if (!authManager.isConnected()) {
            return if (pendingOps > 0) SyncHealthState.PENDING_CHANGES else SyncHealthState.NOT_CONNECTED
        }
        if (pendingOps > 0) return SyncHealthState.PENDING_CHANGES
        return if (syncMetaStatus == SyncHealthState.SYNCED.name) {
            SyncHealthState.SYNCED
        } else {
            SyncHealthState.READY
        }
    }

    private fun collectSubtreeFolderIds(rootId: String, allFolders: List<FolderEntity>): Set<String> {
        val childrenByParent = allFolders.groupBy { it.parentFolderId }
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty() && visited.size < 10_000) {
            val current = queue.removeFirst()
            if (visited.add(current)) {
                childrenByParent[current]?.forEach { child -> queue.add(child.id) }
            }
        }
        return visited
    }

    private fun buildDailySeries(
        records: List<RecordEntity>,
        folders: List<FolderEntity> = emptyList(),
        reminders: List<ReminderEntity> = emptyList(),
        days: Int
    ): List<DailyActivityMetric> {
        val zone = timeProvider.zoneId()
        val today = timeProvider.today()
        val createdByDay = HashMap<Long, Int>()
        val modifiedByDay = HashMap<Long, Int>()
        val foldersByDay = HashMap<Long, Int>()
        val reminderIdsByDay = HashMap<Long, HashSet<String>>()

        for (rec in records) {
            val cDay = Instant.ofEpochMilli(rec.createdAt).atZone(zone).toLocalDate().toEpochDay()
            createdByDay[cDay] = (createdByDay[cDay] ?: 0) + 1
            if (rec.modifiedAt > rec.createdAt + 1_000L) {
                val mDay = Instant.ofEpochMilli(rec.modifiedAt).atZone(zone).toLocalDate().toEpochDay()
                modifiedByDay[mDay] = (modifiedByDay[mDay] ?: 0) + 1
            }
        }

        for (folder in folders) {
            val fDay = Instant.ofEpochMilli(folder.createdAt).atZone(zone).toLocalDate().toEpochDay()
            foldersByDay[fDay] = (foldersByDay[fDay] ?: 0) + 1
        }

        for (rem in reminders) {
            val cDay = Instant.ofEpochMilli(rem.createdAt).atZone(zone).toLocalDate().toEpochDay()
            reminderIdsByDay.getOrPut(cDay) { HashSet() }.add(rem.id)
            val sDay = Instant.ofEpochMilli(rem.scheduledAtMillis).atZone(zone).toLocalDate().toEpochDay()
            reminderIdsByDay.getOrPut(sDay) { HashSet() }.add(rem.id)
        }

        return ((days - 1) downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            val epochDay = d.toEpochDay()
            DailyActivityMetric(
                dateLabel = DateFormatters.formatShortDay(d),
                fullDateLabel = DateFormatters.formatDayMonth(d),
                epochDay = epochDay,
                createdCount = createdByDay[epochDay] ?: 0,
                modifiedCount = modifiedByDay[epochDay] ?: 0,
                foldersCreatedCount = foldersByDay[epochDay] ?: 0,
                remindersCount = reminderIdsByDay[epochDay]?.size ?: 0
            )
        }
    }

    private fun decodeCustomFields(raw: String): List<CustomField> =
        runCatching { json.decodeFromString<List<CustomField>>(raw) }.getOrDefault(emptyList())

    private fun toRecordDomain(
        e: RecordEntity,
        foldersById: Map<String, FolderEntity>,
        attCountByRecord: Map<String, Int>
    ): RecordItem = RecordItem(
        id = e.id,
        title = e.title,
        description = e.description,
        folderId = e.folderId,
        folderName = e.folderId?.let { foldersById[it]?.name },
        category = e.category,
        createdAt = e.createdAt,
        modifiedAt = e.modifiedAt,
        customFields = decodeCustomFields(e.customFieldsJson),
        currentVersionNumber = e.currentVersionNumber,
        currentVersionId = e.currentVersionId,
        attachmentCount = attCountByRecord[e.id] ?: 0,
        syncVersion = e.syncVersion
    )

    private fun toAttachmentDomain(e: AttachmentEntity) = AttachmentItem(
        id = e.id,
        recordId = e.recordId,
        originalFileName = e.originalFileName,
        mimeType = e.mimeType,
        kind = AttachmentKind.entries.firstOrNull { it.name == e.attachmentKind } ?: AttachmentKind.OTHER,
        plaintextSizeBytes = e.plaintextSizeBytes,
        encryptedSizeBytes = e.encryptedSizeBytes,
        encryptedRelativePath = e.encryptedRelativePath,
        plaintextSha256Hex = e.plaintextSha256Hex,
        createdAt = e.createdAt,
        modifiedAt = e.modifiedAt
    )

    private fun toReminderDomain(
        e: ReminderEntity,
        recordsById: Map<String, RecordEntity>
    ) = ReminderItem(
        id = e.id,
        title = e.title,
        notes = e.notes,
        scheduledAtMillis = e.scheduledAtMillis,
        recurrence = RecurrenceType.entries.firstOrNull { it.name == e.recurrenceType } ?: RecurrenceType.ONCE,
        priority = ReminderPriority.entries.firstOrNull { it.name == e.priority } ?: ReminderPriority.MEDIUM,
        status = ReminderStatus.entries.firstOrNull { it.name == e.status } ?: ReminderStatus.PENDING,
        snoozedUntilMillis = e.snoozedUntilMillis,
        linkedRecordId = e.linkedRecordId,
        linkedRecordTitle = e.linkedRecordId?.let { recordsById[it]?.title },
        createdAt = e.createdAt,
        modifiedAt = e.modifiedAt,
        syncVersion = e.syncVersion
    )

    private fun toTimelineDomain(e: TimelineEventEntity) = TimelineEvent(
        id = e.id,
        eventType = TimelineEventType.entries.firstOrNull { it.name == e.eventType }
            ?: TimelineEventType.RECORD_UPDATED,
        entityType = e.entityType,
        entityId = e.entityId,
        displayTitle = e.displayTitle,
        accentColor = e.accentColor,
        timestampMillis = e.timestampMillis
    )

    private fun toBackupDomain(e: BackupMetadataEntity) = BackupHistoryItem(
        backupId = e.backupId,
        backupKind = BackupKind.entries.firstOrNull { it.name == e.backupKind } ?: BackupKind.FULL_MANUAL,
        destination = e.destination,
        locationRef = e.locationRef,
        sizeBytes = e.sizeBytes,
        recordCount = e.recordCount,
        attachmentCount = e.attachmentCount,
        sha256Checksum = e.sha256Checksum,
        verified = e.verified,
        succeeded = e.succeeded,
        errorMessage = e.errorMessage,
        createdAtMillis = e.createdAtMillis
    )

    private data class Quint<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
