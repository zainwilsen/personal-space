package app.personalspace.vault.data.sync

import android.content.Context
import androidx.room.withTransaction
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.data.backup.SerializableAttachmentSnapshot
import app.personalspace.vault.data.backup.SerializableFolderSnapshot
import app.personalspace.vault.data.backup.SerializableRecordSnapshot
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.entity.AttachmentEntity
import app.personalspace.vault.data.local.entity.ConflictRecordEntity
import app.personalspace.vault.data.local.entity.DeletedItemEntity
import app.personalspace.vault.data.local.entity.FolderEntity
import app.personalspace.vault.data.local.entity.RecordEntity
import app.personalspace.vault.data.local.entity.RecordVersionEntity
import app.personalspace.vault.data.local.entity.SyncDeviceEntity
import app.personalspace.vault.data.local.entity.SyncMetadataEntity
import app.personalspace.vault.data.local.entity.TimelineEventEntity
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.domain.model.ConflictKind
import app.personalspace.vault.domain.model.ConflictResolutionChoice
import app.personalspace.vault.domain.model.SyncHealthState
import app.personalspace.vault.domain.model.TimelineEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class EncryptedChangeBundleEnvelope(
    val formatVersion: Int = 1,
    val bundleId: String,
    val vaultId: String,
    val originDeviceId: String,
    val createdAtMillis: Long,
    val ivBase64: String,
    val ciphertextBase64: String
)

@Serializable
data class BundledSyncOperation(
    val operationId: String,
    val entityType: String, // "FOLDER", "RECORD", "ATTACHMENT"
    val entityId: String,
    val operationType: String, // "UPSERT" or "TOMBSTONE"
    val baseVersion: Long,
    val newVersion: Long,
    val deviceId: String,
    val timestampMillis: Long,
    val payloadJson: String,
    val driveAttachmentFileId: String? = null
)

@Serializable
data class ChangeBundleCleartextPayload(
    val bundleId: String,
    val originDeviceId: String,
    val originDeviceName: String,
    val createdAtMillis: Long,
    val operations: List<BundledSyncOperation>
)

data class SyncExecutionReport(
    val finalState: SyncHealthState,
    val downloadedBundles: Int,
    val uploadedOperations: Int,
    val conflictsDetected: Int,
    val errorMessage: String? = null
)

/**
 * Multi-device synchronization engine using immutable, uniquely identified encrypted change bundles
 * (`.psbundle`) uploaded to Google Drive.
 *
 * Core safety guarantees:
 * 1. Every bundle is encrypted locally with AES-256-GCM before upload.
 * 2. Downloaded bundles are reconciled idempotently by tracking `appliedBundleIds`.
 * 3. Concurrent edits (`local.syncPending == true` vs remote edit) NEVER overwrite silently —
 *    both versions are preserved and surfaced in `conflict_records`!
 * 4. Delete-vs-Edit conflicts preserve the edited record rather than silently destroying data!
 * 5. Tombstones synchronize clean deletions across devices.
 */
@Singleton
class MultiDeviceSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: VaultSessionManager,
    private val dbHolder: VaultDatabaseHolder,
    private val attachmentStorage: EncryptedAttachmentStorage,
    private val authManager: GoogleDriveAuthManager,
    private val remoteDataSource: DriveRemoteDataSource,
    private val preferences: UserPreferencesDataSource,
    private val timeProvider: TimeProvider
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun runSyncCycle(): SyncExecutionReport = withContext(Dispatchers.IO) {
        if (sessionManager.lockState.value != VaultLockState.UNLOCKED) {
            return@withContext SyncExecutionReport(
                finalState = SyncHealthState.PENDING_CHANGES,
                downloadedBundles = 0,
                uploadedOperations = 0,
                conflictsDetected = 0,
                errorMessage = "Unlock vault to run encrypted synchronization."
            )
        }

        val authSession = authManager.getCurrentSession()
        val db = dbHolder.getDatabase()
        val syncDao = db.syncDao()

        if (authSession == null || authSession.accessToken.isBlank()) {
            val pendingCount = syncDao.getPendingOperations().size
            val state = if (pendingCount > 0) SyncHealthState.PENDING_CHANGES else SyncHealthState.NOT_CONNECTED
            updateSyncState(state, errorMessage = null)
            return@withContext SyncExecutionReport(state, 0, 0, 0)
        }

        val subKeys = sessionManager.requireSubKeys()
        val (deviceId, deviceName) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()

        syncDao.upsertDevice(
            SyncDeviceEntity(
                deviceId = deviceId,
                deviceName = deviceName,
                modelName = android.os.Build.MODEL.orEmpty(),
                registeredAtMillis = now,
                lastSeenSyncMillis = now
            )
        )

        updateSyncState(SyncHealthState.SYNCING, errorMessage = null)

        val tempDir = File(context.cacheDir, "sync_work_${IdGenerator.newUuid()}").apply { mkdirs() }
        var downloadedCount = 0
        var uploadedOpsCount = 0
        var newConflictsCount = 0

        try {
            val accessToken = authSession.accessToken
            val folderId = authSession.driveFolderId?.takeIf { it.isNotBlank() }
                ?: remoteDataSource.ensureAppVaultFolder(accessToken).also {
                    authManager.updateDriveFolderId(it)
                }

            val currentMeta = syncDao.getSyncMetadata() ?: SyncMetadataEntity()
            val appliedIds = runCatching {
                json.decodeFromString<List<String>>(currentMeta.appliedBundleIdsJson).toMutableSet()
            }.getOrElse { mutableSetOf() }

            // Phase A: Read new private appDataFolder bundles plus legacy visible-folder bundles.
            // Existing releases wrote .psbundle files into PersonalSpace_EncryptedVault in My Drive.
            // Keep reading that location for backwards compatibility, but all new sync payloads go
            // to appDataFolder so internal sync events do not clutter the user's visible Drive.
            val legacyRemoteBundles = remoteDataSource.listFilesInFolder(
                accessToken = accessToken,
                folderId = folderId,
                namePrefix = "psbundle_"
            )
            val privateRemoteBundles = remoteDataSource.listAppDataFiles(
                accessToken = accessToken,
                namePrefix = "psbundle_"
            ).filter { it.name.startsWith("psbundle_") && it.name.endsWith(".psbundle") }
            val remoteBundles = (legacyRemoteBundles + privateRemoteBundles)
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<RemoteDriveFile> { it.createdTime.orEmpty() }
                        .thenBy { it.name }
                )

            for (remoteFile in remoteBundles) {
                val bundleFile = File(tempDir, remoteFile.name)
                remoteDataSource.downloadEncryptedFile(accessToken, remoteFile.id, bundleFile)
                val envelope = json.decodeFromString<EncryptedChangeBundleEnvelope>(
                    bundleFile.readText(Charsets.UTF_8)
                )

                // Skip bundles belonging to another vault or already reconciled idempotently
                if (envelope.vaultId != subKeys.vaultId || envelope.bundleId in appliedIds) {
                    continue
                }

                val plainBytes = VaultCryptoEngine.decryptAesGcm(
                    key = subKeys.backupPayloadKey,
                    iv = VaultCryptoEngine.fromBase64(envelope.ivBase64),
                    ciphertext = VaultCryptoEngine.fromBase64(envelope.ciphertextBase64),
                    aad = bundleAad(envelope.vaultId, envelope.bundleId)
                )
                val payload = json.decodeFromString<ChangeBundleCleartextPayload>(
                    String(plainBytes, StandardCharsets.UTF_8)
                )
                plainBytes.fill(0)

                if (payload.originDeviceId != deviceId) {
                    newConflictsCount += reconcileRemoteBundle(
                        payload = payload,
                        accessToken = accessToken
                    )
                    downloadedCount++
                }

                appliedIds.add(envelope.bundleId)
            }

            // Phase B: Upload pending local operations as a single immutable encrypted bundle
            val pendingOps = syncDao.getPendingOperations()
            if (pendingOps.isNotEmpty()) {
                val bundleId = IdGenerator.newUuid()
                val bundledOps = ArrayList<BundledSyncOperation>(pendingOps.size)

                for (op in pendingOps) {
                    var uploadedAttId: String? = null
                    if (op.entityType == "ATTACHMENT" && op.operationType == "UPSERT") {
                        val attSnap = json.decodeFromString<SerializableAttachmentSnapshot>(op.payloadJson)
                        val localEncFile = attachmentStorage.resolveEncryptedFile(attSnap.encryptedRelativePath)
                        if (localEncFile.exists()) {
                            val uploaded = remoteDataSource.uploadOrReplaceInAppData(
                                accessToken = accessToken,
                                remoteName = "psatt_${attSnap.id}.psenc",
                                localCiphertextFile = localEncFile
                            )
                            uploadedAttId = uploaded.id
                        }
                    }
                    bundledOps.add(
                        BundledSyncOperation(
                            operationId = op.operationId,
                            entityType = op.entityType,
                            entityId = op.entityId,
                            operationType = op.operationType,
                            baseVersion = op.baseVersion,
                            newVersion = op.newVersion,
                            deviceId = op.deviceId,
                            timestampMillis = op.timestampMillis,
                            payloadJson = op.payloadJson,
                            driveAttachmentFileId = uploadedAttId
                        )
                    )
                }

                val bundlePayload = ChangeBundleCleartextPayload(
                    bundleId = bundleId,
                    originDeviceId = deviceId,
                    originDeviceName = deviceName,
                    createdAtMillis = now,
                    operations = bundledOps
                )
                val plainBundle = json.encodeToString(bundlePayload).toByteArray(StandardCharsets.UTF_8)
                val (iv, ct) = VaultCryptoEngine.encryptAesGcm(
                    key = subKeys.backupPayloadKey,
                    plaintext = plainBundle,
                    aad = bundleAad(subKeys.vaultId, bundleId)
                )
                plainBundle.fill(0)

                val envelope = EncryptedChangeBundleEnvelope(
                    bundleId = bundleId,
                    vaultId = subKeys.vaultId,
                    originDeviceId = deviceId,
                    createdAtMillis = now,
                    ivBase64 = VaultCryptoEngine.toBase64(iv),
                    ciphertextBase64 = VaultCryptoEngine.toBase64(ct)
                )
                val bundleOutFile = File(tempDir, "psbundle_${now}_${deviceId.take(6)}_${bundleId.take(8)}.psbundle")
                bundleOutFile.writeText(json.encodeToString(envelope), Charsets.UTF_8)

                // Upload the immutable change event to private appDataFolder BEFORE marking
                // operations synced. Unique bundle names are intentional: overwriting an older event
                // would make an offline device miss operations when it reconnects later.
                remoteDataSource.uploadOrReplaceInAppData(
                    accessToken = accessToken,
                    remoteName = bundleOutFile.name,
                    localCiphertextFile = bundleOutFile
                )

                // Only after confirmed upload do we mark local operations bundled and clear syncPending flags
                db.withTransaction {
                    syncDao.markOperationsBundled(pendingOps.map { it.operationId }, bundleId)
                    for (op in pendingOps) {
                        when (op.entityType) {
                            "RECORD" -> db.recordDao().getRecordById(op.entityId)?.let {
                                db.recordDao().upsert(it.copy(syncPending = false))
                            }
                            "FOLDER" -> db.folderDao().getFolderById(op.entityId)?.let {
                                db.folderDao().upsert(it.copy(syncPending = false))
                            }
                            "ATTACHMENT" -> db.attachmentDao().getAttachmentById(op.entityId)?.let {
                                db.attachmentDao().upsert(it.copy(syncPending = false))
                            }
                        }
                    }
                }
                appliedIds.add(bundleId)
                uploadedOpsCount = pendingOps.size
            }

            val unresolvedConflicts = db.syncDao().getSyncMetadata()
            val totalUnresolved = countUnresolvedConflicts()
            val finalState = if (totalUnresolved > 0) {
                SyncHealthState.ATTENTION_REQUIRED
            } else {
                SyncHealthState.SYNCED
            }

            syncDao.upsertSyncMetadata(
                SyncMetadataEntity(
                    id = 1,
                    syncStatus = finalState.name,
                    lastSyncedAtMillis = timeProvider.nowMillis(),
                    lastErrorMessage = if (totalUnresolved > 0) "$totalUnresolved sync conflict(s) need review" else null,
                    appliedBundleIdsJson = json.encodeToString(appliedIds.toList()),
                    driveVaultFolderId = folderId
                )
            )

            if (downloadedCount > 0 || uploadedOpsCount > 0) {
                db.timelineDao().insert(
                    TimelineEventEntity(
                        id = IdGenerator.newUuid(),
                        eventType = TimelineEventType.SYNC_COMPLETED.name,
                        entityType = "SYSTEM",
                        entityId = null,
                        displayTitle = "Synced ($uploadedOpsCount up, $downloadedCount remote bundles)",
                        accentColor = "BLUE",
                        timestampMillis = timeProvider.nowMillis()
                    )
                )
            }

            SyncExecutionReport(
                finalState = finalState,
                downloadedBundles = downloadedCount,
                uploadedOperations = uploadedOpsCount,
                conflictsDetected = newConflictsCount
            )
        } catch (expired: DriveAuthExpiredException) {
            updateSyncState(SyncHealthState.ATTENTION_REQUIRED, expired.message)
            SyncExecutionReport(SyncHealthState.ATTENTION_REQUIRED, downloadedCount, uploadedOpsCount, newConflictsCount, expired.message)
        } catch (e: Exception) {
            val err = e.message ?: "Synchronization failed"
            updateSyncState(SyncHealthState.FAILED, err)
            SyncExecutionReport(SyncHealthState.FAILED, downloadedCount, uploadedOpsCount, newConflictsCount, err)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Resolves a preserved conflict according to explicit user choice without losing data.
     */
    suspend fun resolveConflict(
        conflictId: String,
        choice: ConflictResolutionChoice,
        mergedTitle: String? = null,
        mergedDescription: String? = null
    ) = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val conflict = db.syncDao().getConflictById(conflictId) ?: return@withContext
        val (deviceId, _) = preferences.getOrCreateDeviceIdentity()
        val now = timeProvider.nowMillis()

        db.withTransaction {
            if (conflict.entityType == "RECORD") {
                val localSnap = runCatching {
                    json.decodeFromString<SerializableRecordSnapshot>(conflict.localPayloadJson)
                }.getOrNull()
                val remoteSnap = runCatching {
                    json.decodeFromString<SerializableRecordSnapshot>(conflict.remotePayloadJson)
                }.getOrNull()

                when (choice) {
                    ConflictResolutionChoice.KEEP_LOCAL -> {
                        if (localSnap != null) {
                            val bumped = toRecordEntity(localSnap).copy(
                                isDeleted = false,
                                deletedAt = null,
                                deletedBatchId = null,
                                modifiedAt = now,
                                syncVersion = maxOf(localSnap.syncVersion, remoteSnap?.syncVersion ?: 0L) + 1,
                                lastModifiedByDeviceId = deviceId,
                                syncPending = true
                            )
                            db.recordDao().upsert(bumped)
                            queueRecordUpsertOp(bumped, deviceId, now)
                        }
                    }
                    ConflictResolutionChoice.KEEP_REMOTE -> {
                        if (remoteSnap != null) {
                            val bumped = toRecordEntity(remoteSnap).copy(
                                isDeleted = false,
                                deletedAt = null,
                                deletedBatchId = null,
                                modifiedAt = now,
                                syncVersion = maxOf(localSnap?.syncVersion ?: 0L, remoteSnap.syncVersion) + 1,
                                lastModifiedByDeviceId = deviceId,
                                syncPending = true
                            )
                            db.recordDao().upsert(bumped)
                            recordConflictVersionSnapshot(bumped, deviceId, now, "Adopted remote conflict version")
                            queueRecordUpsertOp(bumped, deviceId, now)
                        }
                    }
                    ConflictResolutionChoice.KEEP_BOTH -> {
                        // Keep local under original ID and duplicate remote as a separate sibling record!
                        if (remoteSnap != null) {
                            val copyId = IdGenerator.newUuid()
                            val verId = IdGenerator.newUuid()
                            val copyEntity = toRecordEntity(remoteSnap).copy(
                                id = copyId,
                                title = "${remoteSnap.title} (Remote Copy)",
                                isDeleted = false,
                                deletedAt = null,
                                deletedBatchId = null,
                                currentVersionNumber = 1,
                                currentVersionId = verId,
                                createdAt = now,
                                modifiedAt = now,
                                syncVersion = 1L,
                                lastModifiedByDeviceId = deviceId,
                                syncPending = true
                            )
                            db.recordDao().upsert(copyEntity)
                            recordConflictVersionSnapshot(copyEntity, deviceId, now, "Preserved remote copy from conflict")
                            queueRecordUpsertOp(copyEntity, deviceId, now)
                        }
                    }
                    ConflictResolutionChoice.MANUAL_MERGE -> {
                        val base = localSnap ?: remoteSnap
                        if (base != null) {
                            val verId = IdGenerator.newUuid()
                            val nextVerNum = base.currentVersionNumber + 1
                            val merged = toRecordEntity(base).copy(
                                title = mergedTitle?.takeIf { it.isNotBlank() } ?: base.title,
                                description = mergedDescription ?: base.description,
                                isDeleted = false,
                                deletedAt = null,
                                deletedBatchId = null,
                                currentVersionNumber = nextVerNum,
                                currentVersionId = verId,
                                modifiedAt = now,
                                syncVersion = maxOf(localSnap?.syncVersion ?: 0L, remoteSnap?.syncVersion ?: 0L) + 1,
                                lastModifiedByDeviceId = deviceId,
                                syncPending = true
                            )
                            db.recordDao().upsert(merged)
                            recordConflictVersionSnapshot(merged, deviceId, now, "Manual conflict merge")
                            queueRecordUpsertOp(merged, deviceId, now)
                        }
                    }
                }
            }

            db.syncDao().upsertConflict(
                conflict.copy(
                    resolvedAtMillis = now,
                    resolutionChoice = choice.name
                )
            )
        }
    }

    private suspend fun reconcileRemoteBundle(
        payload: ChangeBundleCleartextPayload,
        accessToken: String
    ): Int {
        val db = dbHolder.getDatabase()
        val now = timeProvider.nowMillis()
        var conflicts = 0

        db.syncDao().upsertDevice(
            SyncDeviceEntity(
                deviceId = payload.originDeviceId,
                deviceName = payload.originDeviceName,
                modelName = "Remote Android",
                registeredAtMillis = payload.createdAtMillis,
                lastSeenSyncMillis = now
            )
        )

        for (op in payload.operations) {
            when (op.entityType) {
                "FOLDER" -> {
                    val local = db.folderDao().getFolderById(op.entityId)
                    if (op.operationType == "UPSERT") {
                        val remote = json.decodeFromString<SerializableFolderSnapshot>(op.payloadJson)
                        if (local != null && local.syncPending && local.lastModifiedByDeviceId != op.deviceId) {
                            // Concurrent folder edit: preserve local and record conflict
                            db.syncDao().upsertConflict(
                                ConflictRecordEntity(
                                    conflictId = IdGenerator.newUuid(),
                                    entityType = "FOLDER",
                                    entityId = op.entityId,
                                    conflictKind = ConflictKind.CONCURRENT_EDIT.name,
                                    localTitle = local.name,
                                    localPayloadJson = json.encodeToString(toFolderSnapshot(local)),
                                    localModifiedAt = local.modifiedAt,
                                    localDeviceId = local.lastModifiedByDeviceId,
                                    remoteTitle = remote.name,
                                    remotePayloadJson = op.payloadJson,
                                    remoteModifiedAt = remote.modifiedAt,
                                    remoteDeviceId = op.deviceId,
                                    detectedAtMillis = now
                                )
                            )
                            conflicts++
                        } else if (local == null || remote.syncVersion >= local.syncVersion) {
                            db.folderDao().upsert(toFolderEntity(remote))
                        }
                    } else if (op.operationType == "TOMBSTONE" && local != null) {
                        if (local.syncPending && !local.isDeleted) {
                            conflicts++
                        } else {
                            db.folderDao().upsert(
                                local.copy(
                                    isDeleted = true,
                                    deletedAt = op.timestampMillis,
                                    syncVersion = op.newVersion,
                                    syncPending = false
                                )
                            )
                        }
                    }
                }

                "RECORD" -> {
                    val local = db.recordDao().getRecordById(op.entityId)
                    if (op.operationType == "UPSERT") {
                        val remote = json.decodeFromString<SerializableRecordSnapshot>(op.payloadJson)
                        when {
                            // Case 1: Local has unbundled edit from this device -> CONCURRENT_EDIT!
                            local != null && local.syncPending && !local.isDeleted && local.lastModifiedByDeviceId != op.deviceId -> {
                                val existingConflict = db.syncDao().getUnresolvedConflictForEntity("RECORD", op.entityId)
                                db.syncDao().upsertConflict(
                                    ConflictRecordEntity(
                                        conflictId = existingConflict?.conflictId ?: IdGenerator.newUuid(),
                                        entityType = "RECORD",
                                        entityId = op.entityId,
                                        conflictKind = ConflictKind.CONCURRENT_EDIT.name,
                                        localTitle = local.title,
                                        localPayloadJson = json.encodeToString(toRecordSnapshot(local)),
                                        localModifiedAt = local.modifiedAt,
                                        localDeviceId = local.lastModifiedByDeviceId,
                                        remoteTitle = remote.title,
                                        remotePayloadJson = op.payloadJson,
                                        remoteModifiedAt = remote.modifiedAt,
                                        remoteDeviceId = op.deviceId,
                                        detectedAtMillis = now
                                    )
                                )
                                if (existingConflict == null) conflicts++
                            }
                            // Case 2: Local deleted item while remote edited -> DELETE_VS_EDIT! Preserve remote edited content!
                            local != null && local.isDeleted && local.syncPending -> {
                                val revived = toRecordEntity(remote).copy(isDeleted = false, deletedAt = null, deletedBatchId = null)
                                db.recordDao().upsert(revived)
                                val existingConflict = db.syncDao().getUnresolvedConflictForEntity("RECORD", op.entityId)
                                db.syncDao().upsertConflict(
                                    ConflictRecordEntity(
                                        conflictId = existingConflict?.conflictId ?: IdGenerator.newUuid(),
                                        entityType = "RECORD",
                                        entityId = op.entityId,
                                        conflictKind = ConflictKind.DELETE_VS_EDIT.name,
                                        localTitle = "${local.title} (Deleted Locally)",
                                        localPayloadJson = json.encodeToString(toRecordSnapshot(local)),
                                        localModifiedAt = local.modifiedAt,
                                        localDeviceId = local.lastModifiedByDeviceId,
                                        remoteTitle = remote.title,
                                        remotePayloadJson = op.payloadJson,
                                        remoteModifiedAt = remote.modifiedAt,
                                        remoteDeviceId = op.deviceId,
                                        detectedAtMillis = now
                                    )
                                )
                                if (existingConflict == null) conflicts++
                            }
                            // Case 3: Clean fast-forward
                            local == null || remote.syncVersion >= local.syncVersion -> {
                                val entity = toRecordEntity(remote)
                                db.recordDao().upsert(entity)
                                recordConflictVersionSnapshot(entity, op.deviceId, remote.modifiedAt, "Synced from ${payload.originDeviceName}")
                            }
                        }
                    } else if (op.operationType == "TOMBSTONE" && local != null) {
                        if (local.syncPending && !local.isDeleted) {
                            // Remote deleted while local edited -> DELETE_VS_EDIT! Preserve local edited content!
                            val existingConflict = db.syncDao().getUnresolvedConflictForEntity("RECORD", op.entityId)
                            db.syncDao().upsertConflict(
                                ConflictRecordEntity(
                                    conflictId = existingConflict?.conflictId ?: IdGenerator.newUuid(),
                                    entityType = "RECORD",
                                    entityId = op.entityId,
                                    conflictKind = ConflictKind.DELETE_VS_EDIT.name,
                                    localTitle = local.title,
                                    localPayloadJson = json.encodeToString(toRecordSnapshot(local)),
                                    localModifiedAt = local.modifiedAt,
                                    localDeviceId = local.lastModifiedByDeviceId,
                                    remoteTitle = "${local.title} (Deleted on ${payload.originDeviceName})",
                                    remotePayloadJson = op.payloadJson,
                                    remoteModifiedAt = op.timestampMillis,
                                    remoteDeviceId = op.deviceId,
                                    detectedAtMillis = now
                                )
                            )
                            if (existingConflict == null) conflicts++
                        } else {
                            db.recordDao().upsert(
                                local.copy(
                                    isDeleted = true,
                                    deletedAt = op.timestampMillis,
                                    syncVersion = op.newVersion,
                                    syncPending = false
                                )
                            )
                        }
                    }
                }

                "ATTACHMENT" -> {
                    if (op.operationType == "UPSERT") {
                        val attSnap = json.decodeFromString<SerializableAttachmentSnapshot>(op.payloadJson)
                        val destEncFile = attachmentStorage.resolveEncryptedFile(attSnap.encryptedRelativePath)
                        if (!destEncFile.exists() && !op.driveAttachmentFileId.isNullOrBlank()) {
                            remoteDataSource.downloadEncryptedFile(
                                accessToken = accessToken,
                                fileId = op.driveAttachmentFileId,
                                destinationFile = destEncFile
                            )
                        }
                        db.attachmentDao().upsert(
                            AttachmentEntity(
                                id = attSnap.id,
                                recordId = attSnap.recordId,
                                originalFileName = attSnap.originalFileName,
                                mimeType = attSnap.mimeType,
                                attachmentKind = attSnap.attachmentKind,
                                plaintextSizeBytes = attSnap.plaintextSizeBytes,
                                encryptedSizeBytes = attSnap.encryptedSizeBytes,
                                encryptedRelativePath = attSnap.encryptedRelativePath,
                                plaintextSha256Hex = attSnap.plaintextSha256Hex,
                                createdAt = attSnap.createdAt,
                                modifiedAt = attSnap.modifiedAt,
                                isDeleted = attSnap.isDeleted,
                                deletedAt = attSnap.deletedAt,
                                syncVersion = attSnap.syncVersion,
                                lastModifiedByDeviceId = attSnap.lastModifiedByDeviceId,
                                syncPending = false
                            )
                        )
                    } else if (op.operationType == "TOMBSTONE") {
                        // P6 follow-up: a remote deletion must go through the Recycle Bin like every
                        // local deletion does. The previous code called deleteEncryptedFile() and then
                        // hardDeleteById(), i.e. it destroyed the ciphertext and the row immediately:
                        //   * the user had no way to restore a file another device deleted,
                        //   * a DELETE_VS_EDIT race (the file was edited here while deletion was in
                        //     flight) lost the local version with no conflict record,
                        //   * and it bypassed the reference counting that protects shared files.
                        val existing = db.attachmentDao().getAttachmentById(op.entityId)
                        if (existing != null) {
                            if (existing.syncPending && !existing.isDeleted) {
                                // Local edit vs remote delete -> never destroy, record a conflict so the
                                // user chooses. This mirrors the RECORD branch above.
                                val existingConflict = db.syncDao()
                                    .getUnresolvedConflictForEntity("ATTACHMENT", op.entityId)
                                db.syncDao().upsertConflict(
                                    ConflictRecordEntity(
                                        conflictId = existingConflict?.conflictId ?: IdGenerator.newUuid(),
                                        entityType = "ATTACHMENT",
                                        entityId = op.entityId,
                                        conflictKind = ConflictKind.DELETE_VS_EDIT.name,
                                        localTitle = "${existing.originalFileName} (Edited Locally)",
                                        localPayloadJson = json.encodeToString(
                                            SerializableAttachmentSnapshot(
                                                id = existing.id,
                                                recordId = existing.recordId,
                                                originalFileName = existing.originalFileName,
                                                mimeType = existing.mimeType,
                                                attachmentKind = existing.attachmentKind,
                                                plaintextSizeBytes = existing.plaintextSizeBytes,
                                                encryptedSizeBytes = existing.encryptedSizeBytes,
                                                encryptedRelativePath = existing.encryptedRelativePath,
                                                plaintextSha256Hex = existing.plaintextSha256Hex,
                                                createdAt = existing.createdAt,
                                                modifiedAt = existing.modifiedAt,
                                                isDeleted = existing.isDeleted,
                                                deletedAt = existing.deletedAt,
                                                syncVersion = existing.syncVersion,
                                                lastModifiedByDeviceId = existing.lastModifiedByDeviceId
                                            )
                                        ),
                                        localModifiedAt = existing.modifiedAt,
                                        localDeviceId = existing.lastModifiedByDeviceId,
                                        remoteTitle = "${existing.originalFileName} " +
                                            "(Deleted on ${payload.originDeviceName})",
                                        remotePayloadJson = op.payloadJson,
                                        remoteModifiedAt = op.timestampMillis,
                                        remoteDeviceId = op.deviceId,
                                        detectedAtMillis = now
                                    )
                                )
                                if (existingConflict == null) conflicts++
                            } else if (!existing.isDeleted) {
                                // Soft delete exactly like the local path: the row stays, the file stays,
                                // and a recycle-bin batch makes it restorable.
                                val batchId = IdGenerator.newUuid()
                                val nowMillis = timeProvider.nowMillis()
                                db.withTransaction {
                                    db.attachmentDao().upsert(
                                        existing.copy(
                                            isDeleted = true,
                                            deletedAt = op.timestampMillis,
                                            modifiedAt = nowMillis,
                                            syncVersion = op.newVersion,
                                            syncPending = false
                                        )
                                    )
                                    if (db.recycleBinDao().getAttachmentBatchesForRecord(existing.recordId)
                                            .none { it.rootEntityId == existing.id }
                                    ) {
                                        db.recycleBinDao().upsertAll(
                                            listOf(
                                                DeletedItemEntity(
                                                    batchId = batchId,
                                                    rootEntityType = SYNC_ROOT_ATTACHMENT,
                                                    rootEntityId = existing.id,
                                                    displayTitle = existing.originalFileName,
                                                    parentFolderIdSnapshot = existing.recordId,
                                                    subfolderCount = 0,
                                                    recordCount = 0,
                                                    deletedAtMillis = op.timestampMillis,
                                                    expiresAtMillis = op.timestampMillis + SYNC_ATTACHMENT_RETENTION_MILLIS
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return conflicts
    }

    private suspend fun recordConflictVersionSnapshot(
        entity: RecordEntity,
        deviceId: String,
        timestamp: Long,
        summary: String
    ) {
        dbHolder.getDatabase().recordDao().insertVersion(
            RecordVersionEntity(
                id = entity.currentVersionId,
                recordId = entity.id,
                versionNumber = entity.currentVersionNumber,
                title = entity.title,
                description = entity.description,
                category = entity.category,
                customFieldsJson = entity.customFieldsJson,
                createdAt = timestamp,
                createdByDeviceId = deviceId,
                changeSummary = summary
            )
        )
    }

    private suspend fun queueRecordUpsertOp(entity: RecordEntity, deviceId: String, now: Long) {
        dbHolder.getDatabase().syncDao().insertOperation(
            app.personalspace.vault.data.local.entity.SyncOperationEntity(
                operationId = IdGenerator.newUuid(),
                entityType = "RECORD",
                entityId = entity.id,
                operationType = "UPSERT",
                baseVersion = (entity.syncVersion - 1L).coerceAtLeast(0L),
                newVersion = entity.syncVersion,
                deviceId = deviceId,
                timestampMillis = now,
                payloadJson = json.encodeToString(toRecordSnapshot(entity))
            )
        )
    }

    private suspend fun countUnresolvedConflicts(): Int {
        // Quick count via DAO query
        val db = dbHolder.getDatabase().openHelper.readableDatabase
        return db.query("SELECT COUNT(*) FROM conflict_records WHERE resolvedAtMillis IS NULL").use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private suspend fun updateSyncState(state: SyncHealthState, errorMessage: String?) {
        val syncDao = dbHolder.getDatabase().syncDao()
        val cur = syncDao.getSyncMetadata() ?: SyncMetadataEntity()
        syncDao.upsertSyncMetadata(
            cur.copy(
                syncStatus = state.name,
                lastErrorMessage = errorMessage
            )
        )
    }

    private fun bundleAad(vaultId: String, bundleId: String): ByteArray =
        "PersonalSpace/v1/syncbundle/$vaultId/$bundleId".toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun toFolderSnapshot(e: FolderEntity) = SerializableFolderSnapshot(
            e.id, e.name, e.parentFolderId, e.colorId, e.createdAt, e.modifiedAt,
            e.sortOrder, e.isDeleted, e.deletedAt, e.deletedBatchId, e.syncVersion, e.lastModifiedByDeviceId
        )

        fun toFolderEntity(s: SerializableFolderSnapshot) = FolderEntity(
            id = s.id, name = s.name, parentFolderId = s.parentFolderId, colorId = s.colorId,
            createdAt = s.createdAt, modifiedAt = s.modifiedAt, sortOrder = s.sortOrder,
            isDeleted = s.isDeleted, deletedAt = s.deletedAt, deletedBatchId = s.deletedBatchId,
            syncVersion = s.syncVersion, lastModifiedByDeviceId = s.lastModifiedByDeviceId, syncPending = false
        )

        fun toRecordSnapshot(e: RecordEntity) = SerializableRecordSnapshot(
            e.id, e.title, e.description, e.folderId, e.category, e.createdAt, e.modifiedAt,
            e.customFieldsJson, e.currentVersionNumber, e.currentVersionId, e.isDeleted,
            e.deletedAt, e.deletedBatchId, e.syncVersion, e.lastModifiedByDeviceId
        )

        fun toRecordEntity(s: SerializableRecordSnapshot) = RecordEntity(
            id = s.id, title = s.title, description = s.description, folderId = s.folderId,
            category = s.category, createdAt = s.createdAt, modifiedAt = s.modifiedAt,
            customFieldsJson = s.customFieldsJson, currentVersionNumber = s.currentVersionNumber,
            currentVersionId = s.currentVersionId, isDeleted = s.isDeleted, deletedAt = s.deletedAt,
            deletedBatchId = s.deletedBatchId, syncVersion = s.syncVersion,
            lastModifiedByDeviceId = s.lastModifiedByDeviceId, syncPending = false
        )
    }
}

/** Must match `PersonalSpaceRepositoryImpl.ROOT_ATTACHMENT`. */
private const val SYNC_ROOT_ATTACHMENT = "ATTACHMENT"

/** Must match `PersonalSpaceRepositoryImpl.RECYCLE_RETENTION_MILLIS` (30 days). */
private const val SYNC_ATTACHMENT_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
