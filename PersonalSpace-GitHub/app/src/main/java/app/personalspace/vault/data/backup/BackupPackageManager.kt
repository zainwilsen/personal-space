package app.personalspace.vault.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.crypto.CryptoAuthException
import app.personalspace.vault.core.crypto.DerivedVaultSubKeys
import app.personalspace.vault.core.crypto.Argon2KdfConfig
import app.personalspace.vault.core.crypto.AttachmentChunkCipher
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.core.crypto.VaultKeyEnvelopeBundle
import app.personalspace.vault.core.crypto.VaultKeyManager
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import app.personalspace.vault.data.local.entity.RecordDraftEntity
import app.personalspace.vault.data.local.entity.AttachmentEntity
import app.personalspace.vault.data.local.entity.BackupMetadataEntity
import app.personalspace.vault.data.local.entity.DeletedItemEntity
import app.personalspace.vault.data.local.entity.FolderEntity
import app.personalspace.vault.data.local.entity.RecordEntity
import app.personalspace.vault.data.local.entity.RecordVersionEntity
import app.personalspace.vault.data.local.entity.ReminderEntity
import app.personalspace.vault.data.local.entity.ReminderOccurrenceEntity
import app.personalspace.vault.data.local.entity.TimelineEventEntity
import app.personalspace.vault.data.local.entity.VaultMetadataEntity
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.domain.model.TimelineEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

class CorruptedBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class BackupAttachmentRef(
    val attachmentId: String,
    val relativeZipEntry: String,
    val encryptedSizeBytes: Long,
    val encryptedSha256Hex: String
)

@Serializable
data class BackupInlineImageRef(
    val identifier: String,
    val originalName: String,
    val relativeZipEntry: String,
    val encryptedSizeBytes: Long,
    val encryptedSha256Hex: String,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val noteCount: Int = 0
)

@Serializable
data class BackupPackageManifest(
    val magic: String = "PSBACKUP_V1",
    val formatVersion: Int = 2,
    val backupId: String,
    val vaultId: String,
    val schemaVersion: Int = 2,
    val createdAtMillis: Long,
    val folderCount: Int,
    val recordCount: Int,
    val attachmentCount: Int,
    val snapshotIvBase64: String,
    val encryptedSnapshotSha256Hex: String,
    val attachments: List<BackupAttachmentRef>,
    val inlineImages: List<BackupInlineImageRef> = emptyList(),
    val inlineImageCount: Int = 0,
    val envelopeBundle: VaultKeyEnvelopeBundle,
    val draftCount: Int = 0,
    val portableKeyWrap: PortableKeyWrap? = null,
    val manifestHmacBase64: String = ""
)

@Serializable
data class PortableKeyWrap(
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
    val argon2Config: Argon2KdfConfig? = null
)

@Serializable
data class SerializableDraftSnapshot(
    val draftKey: String,
    val folderId: String? = null,
    val title: String,
    val description: String,
    val category: String? = null,
    val customFieldsJson: String = "[]",
    val updatedAt: Long
)

@Serializable
data class SerializableFolderSnapshot(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val colorId: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val sortOrder: Int,
    val isDeleted: Boolean,
    val deletedAt: Long? = null,
    val deletedBatchId: String? = null,
    val syncVersion: Long,
    val lastModifiedByDeviceId: String
)

@Serializable
data class SerializableRecordSnapshot(
    val id: String,
    val title: String,
    val description: String,
    val folderId: String? = null,
    val category: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val customFieldsJson: String,
    val currentVersionNumber: Int,
    val currentVersionId: String,
    val isDeleted: Boolean,
    val deletedAt: Long? = null,
    val deletedBatchId: String? = null,
    val syncVersion: Long,
    val lastModifiedByDeviceId: String
)

@Serializable
data class SerializableVersionSnapshot(
    val id: String,
    val recordId: String,
    val versionNumber: Int,
    val title: String,
    val description: String,
    val category: String? = null,
    val customFieldsJson: String,
    val createdAt: Long,
    val createdByDeviceId: String,
    val changeSummary: String
)

@Serializable
data class SerializableAttachmentSnapshot(
    val id: String,
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
    val isDeleted: Boolean,
    val deletedAt: Long? = null,
    val syncVersion: Long,
    val lastModifiedByDeviceId: String
)

@Serializable
data class SerializableReminderSnapshot(
    val id: String,
    val title: String,
    val notes: String,
    val scheduledAtMillis: Long,
    val recurrenceType: String,
    val priority: String,
    val status: String,
    val snoozedUntilMillis: Long? = null,
    val linkedRecordId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val isDeleted: Boolean,
    val syncVersion: Long,
    val lastModifiedByDeviceId: String
)

@Serializable
data class SerializableOccurrenceSnapshot(
    val id: String,
    val reminderId: String,
    val reminderTitle: String,
    val occurrenceScheduledMillis: Long,
    val actualHandledMillis: Long,
    val outcome: String
)

@Serializable
data class SerializableDeletedBatchSnapshot(
    val batchId: String,
    val rootEntityType: String,
    val rootEntityId: String,
    val displayTitle: String,
    val parentFolderIdSnapshot: String? = null,
    val subfolderCount: Int,
    val recordCount: Int,
    val deletedAtMillis: Long,
    val expiresAtMillis: Long
)

@Serializable
data class SerializableTimelineSnapshot(
    val id: String,
    val eventType: String,
    val entityType: String,
    val entityId: String? = null,
    val displayTitle: String,
    val accentColor: String,
    val timestampMillis: Long
)

@Serializable
data class VaultSnapshotPayload(
    val vaultId: String,
    val exportedAtMillis: Long,
    val folders: List<SerializableFolderSnapshot>,
    val records: List<SerializableRecordSnapshot>,
    val versions: List<SerializableVersionSnapshot>,
    val attachments: List<SerializableAttachmentSnapshot>,
    val reminders: List<SerializableReminderSnapshot>,
    val reminderOccurrences: List<SerializableOccurrenceSnapshot>,
    val deletedBatches: List<SerializableDeletedBatchSnapshot>,
    val timelineEvents: List<SerializableTimelineSnapshot>,
    val drafts: List<SerializableDraftSnapshot> = emptyList()
)

data class CreatedBackupOutcome(
    val backupId: String,
    val file: File,
    val sizeBytes: Long,
    val sha256Hex: String,
    val recordCount: Int,
    val attachmentCount: Int,
    val inlineImageCount: Int = 0
)

/**
 * Builds, validates, and atomically restores versioned `.psbackup` encrypted packages.
 */
@Singleton
data class BackupRestoreResult(
    // ---- unchanged: ACTIVE content only (existing callers/UI keep their meaning) ----
    val notes: Int = 0,
    val folders: Int = 0,
    val attachments: Int = 0,
    val drafts: Int = 0,
    val versions: Int = 0,
    val inlineImages: Int = 0,
    val verified: Boolean = false,
    val sourceFormatVersion: Int = 1,
    // ---- P6: explicit active/deleted split, so "notes = 0" can never be misread as "nothing was
    // restored". A vault whose content is entirely in the recycle bin restores with activeNotes = 0
    // and deletedNotes > 0, which is now visible instead of hidden. ----
    val activeNotes: Int = 0,
    val deletedNotes: Int = 0,
    val activeFolders: Int = 0,
    val deletedFolders: Int = 0,
    val activeAttachments: Int = 0,
    val deletedAttachments: Int = 0,
    val deletedInlineImages: Int = 0,
    /** Human-readable one-liner, e.g. "12 notes (9 active, 3 in recycle bin), 4 attachments (3 active, 1 deleted)". */
    val verificationSummary: String = ""
)

class MissingBackupImageException(val missingImages: List<String>) :
    IllegalStateException(
        "Backup cancelled: ${missingImages.size} inline image file(s) missing on disk: " +
            missingImages.joinToString(", ")
    )

class MissingBackupAttachmentException(val missingFiles: List<String>) :
    IllegalStateException(
        "Backup cancelled: ${missingFiles.size} attachment file(s) missing on disk: " +
            missingFiles.joinToString(", ")
    )

class BackupVerificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BackupPackageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultKeyManager: VaultKeyManager,
    private val sessionManager: VaultSessionManager,
    private val dbHolder: VaultDatabaseHolder,
    private val attachmentStorage: EncryptedAttachmentStorage,
    private val inlineImageStore: InlineImageStore,
    private val timeProvider: TimeProvider
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * P9 — failure-injection seam.
     *
     * Every restore stage calls [hit] with its name. In production the set is empty and the call is a
     * no-op map lookup. Tests fill it to throw a specific exception at a specific stage, which is the
     * only way to prove *each* stage's rollback behaviour instead of only the ones that happen to be
     * easy to break from the outside.
     *
     * Deliberately `@VisibleForTesting`-style: nothing in the app ever adds to it.
     */
    object FailureInjection {
        val armedStages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** stage name -> factory for the throwable to raise */
        val failures: MutableMap<String, () -> Throwable> = java.util.concurrent.ConcurrentHashMap()
        /** stages that must be hit at least once (guards against a silently renamed checkpoint) */
        val hitStages: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun arm(stage: String, factory: () -> Throwable) { failures[stage] = factory }

        /** Pops an armed injection for [stage] without throwing, for stages that post-process. */
        fun consumeInjection(stage: String): Throwable? {
            hitStages.add(stage)
            return failures.remove(stage)?.invoke()
        }
        /** Clears armed injections. [hitStages] is deliberately kept: it is the coverage record. */
        fun disarm() { failures.clear(); armedStages.clear() }

        fun resetCoverage() { hitStages.clear(); armedStages.clear() }

        fun hit(stage: String) {
            // reachability and injection are two different facts and must not be conflated: a stage
            // that throws IS reached, so record it first and then fail.
            hitStages.add(stage)
            failures.remove(stage)?.let { throw it() }
        }
    }

    companion object {
        // ---- P9 stage names (one per restore stage in the failure matrix) ----
        const val STAGE_GUARD_CLEARED = "GUARD_CLEARED"
        const val STAGE_INSPECTION = "INSPECTION"
        const val STAGE_VALIDATED = "VALIDATED"
        const val STAGE_KEYS_UNLOCKED = "KEYS_UNLOCKED"
        const val STAGE_MANIFEST_HMAC = "MANIFEST_HMAC"
        const val STAGE_SNAPSHOT_DECRYPTED = "SNAPSHOT_DECRYPTED"
        const val STAGE_RELATIONSHIPS = "RELATIONSHIPS"
        const val STAGE_STAGE_ATTACHMENTS = "STAGE_ATTACHMENTS"
        const val STAGE_ATTACHMENTS_STAGED = "ATTACHMENTS_STAGED"
        const val STAGE_STAGE_IMAGES = "STAGE_IMAGES"
        const val STAGE_IMAGES_STAGED = "IMAGES_STAGED"
        const val STAGE_PRE_DB_TRANSACTION = "PRE_DB_TRANSACTION"
        @Deprecated("The full safety archive was removed; this checkpoint now marks the pre-transaction boundary.")
        const val STAGE_SAFETY_BACKUP = STAGE_PRE_DB_TRANSACTION
        const val STAGE_INSTALL_ATTACHMENTS = "INSTALL_ATTACHMENTS"
        const val STAGE_INSTALL_IMAGES = "INSTALL_IMAGES"
        const val STAGE_DB_TRANSACTION = "DB_TRANSACTION"
        const val STAGE_VERIFICATION = "VERIFICATION"
        const val STAGE_PRE_COMMIT = "PRE_COMMIT"
        const val STAGE_POST_COMMIT_CLEANUP = "POST_COMMIT_CLEANUP"
        const val STAGE_ROLLBACK = "ROLLBACK"

        const val LOCAL_BACKUP_NAME = "PersonalSpace_Backup.psbackup"
        const val DRIVE_BACKUP_NAME = "PersonalSpace_Backup.psbackup"
        @Deprecated("Full pre-restore safety archives are no longer generated.")
        const val SAFETY_BACKUP_NAME = "PersonalSpace_Backup_safety.psbackup"
        const val DOWNLOAD_RELATIVE_PATH = "Download/Personal Space/"
        private const val DOWNLOAD_FOLDER_KEY = "Download/Personal Space"
        private val PORTABLE_ARGON2 = Argon2KdfConfig(memoryKiB = 4096, iterations = 1, parallelism = 1)
        private val SUPPORTED_FORMAT_VERSIONS = setOf(1, 2)
        // Legacy only: pre-v1.1.7 packages wrap their vault key with this historical constant.
        // New packages carry a self-contained portable key wrap and never require it.
        private val LEGACY_FIXED_RESTORE_PASSWORD: CharArray = "Stark@9898".toCharArray()
        private const val IMAGE_STAGING_DIR = "inline_images"
    }

    val localBackupsDir: File
        get() = File(context.filesDir, "local_backups").apply { mkdirs() }

    val singleLocalBackupFile: File
        get() = File(localBackupsDir, LOCAL_BACKUP_NAME)

    @Deprecated("The hidden full safety archive is no longer generated.")
    val safetyBackupFile: File
        get() = File(File(localBackupsDir, "safety"), SAFETY_BACKUP_NAME)

    val externalLocalBackupsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "local_backups").apply { mkdirs() }

    val externalLocalBackupFile: File
        get() = File(externalLocalBackupsDir, LOCAL_BACKUP_NAME)

    private val backupStatePrefs
        get() = context.getSharedPreferences("ps_backup_state", Context.MODE_PRIVATE)

    suspend fun computeVaultFingerprint(): String = withContext(Dispatchers.IO) {
        val db = dbHolder.getDatabase()
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(value: String) = digest.update(value.toByteArray(StandardCharsets.UTF_8))
        db.recordDao().getAllRecordsIncludingDeleted().sortedBy { it.id }.forEach { record ->
            feed(record.id + "|" + record.modifiedAt + "|" + record.isDeleted + "|" + record.currentVersionNumber)
        }
        db.folderDao().getAllFoldersIncludingDeleted().sortedBy { it.id }.forEach { folder ->
            feed(folder.id + "|" + folder.modifiedAt + "|" + folder.isDeleted)
        }
        db.attachmentDao().getAllAttachmentsIncludingDeleted().sortedBy { it.id }.forEach { attachment ->
            feed(attachment.id + "|" + attachment.modifiedAt + "|" + attachment.isDeleted)
        }
        db.recordDao().getAllDrafts().sortedBy { it.draftKey }.forEach { draft ->
            feed(draft.draftKey + "|" + draft.updatedAt)
        }
        feed("v" + db.recordDao().getAllVersions().size)
        feed("b" + db.recycleBinDao().getAllDeletedItems().size)
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun lastBackupFingerprint(): String? = backupStatePrefs.getString("fingerprint", null)

    private fun rememberBackupFingerprint(fingerprint: String) {
        backupStatePrefs.edit().putString("fingerprint", fingerprint).apply()
    }

    fun newRestoreTempFile(): File =
        File(context.cacheDir, "drive_restore_" + IdGenerator.newUuid() + ".psbackup")

    /**
     * Creates a full encrypted `.psbackup` file inside `targetFile` (or internal backups dir).
     */
    suspend fun createFullBackupFile(
        backupKind: BackupKind,
        targetFile: File? = null,
        recordHistoryInDb: Boolean = true
    ): CreatedBackupOutcome = withContext(Dispatchers.IO) {
        val subKeys = sessionManager.requireSubKeys()
        val envelopeBundle = vaultKeyManager.readEnvelopeBundle()
            ?: throw IllegalStateException("Vault key envelope bundle is missing.")

        val backupId = IdGenerator.newUuid()
        val now = timeProvider.nowMillis()
        val db = dbHolder.getDatabase()

        val snapshotPayload = db.withTransaction {
            val folders = db.folderDao().getAllFoldersIncludingDeleted()
            val records = db.recordDao().getAllRecordsIncludingDeleted()
            val versions = db.recordDao().getAllVersions()
            val attachments = db.attachmentDao().getAllAttachmentsIncludingDeleted()
            val reminders = emptyList<app.personalspace.vault.data.local.entity.ReminderEntity>()
            val occurrences = emptyList<app.personalspace.vault.data.local.entity.ReminderOccurrenceEntity>()
            val deletedBatches = db.recycleBinDao().getAllDeletedItems()
            val timeline = db.timelineDao().getAllEvents()
            val drafts = db.recordDao().getAllDrafts()

            VaultSnapshotPayload(
            vaultId = subKeys.vaultId,
            exportedAtMillis = now,
            folders = folders.map {
                SerializableFolderSnapshot(
                    it.id, it.name, it.parentFolderId, it.colorId, it.createdAt, it.modifiedAt,
                    it.sortOrder, it.isDeleted, it.deletedAt, it.deletedBatchId, it.syncVersion, it.lastModifiedByDeviceId
                )
            },
            records = records.map {
                SerializableRecordSnapshot(
                    it.id, it.title, it.description, it.folderId, it.category, it.createdAt, it.modifiedAt,
                    it.customFieldsJson, it.currentVersionNumber, it.currentVersionId, it.isDeleted,
                    it.deletedAt, it.deletedBatchId, it.syncVersion, it.lastModifiedByDeviceId
                )
            },
            versions = versions.map {
                SerializableVersionSnapshot(
                    it.id, it.recordId, it.versionNumber, it.title, it.description, it.category,
                    it.customFieldsJson, it.createdAt, it.createdByDeviceId, it.changeSummary
                )
            },
            attachments = attachments.map {
                SerializableAttachmentSnapshot(
                    it.id, it.recordId, it.originalFileName, it.mimeType, it.attachmentKind,
                    it.plaintextSizeBytes, it.encryptedSizeBytes, it.encryptedRelativePath,
                    it.plaintextSha256Hex, it.createdAt, it.modifiedAt, it.isDeleted, it.deletedAt,
                    it.syncVersion, it.lastModifiedByDeviceId
                )
            },
            reminders = reminders.map {
                SerializableReminderSnapshot(
                    it.id, it.title, it.notes, it.scheduledAtMillis, it.recurrenceType, it.priority,
                    it.status, it.snoozedUntilMillis, it.linkedRecordId, it.createdAt, it.modifiedAt,
                    it.isDeleted, it.syncVersion, it.lastModifiedByDeviceId
                )
            },
            reminderOccurrences = occurrences.map {
                SerializableOccurrenceSnapshot(
                    it.id, it.reminderId, it.reminderTitle, it.occurrenceScheduledMillis,
                    it.actualHandledMillis, it.outcome
                )
            },
            deletedBatches = deletedBatches.map {
                SerializableDeletedBatchSnapshot(
                    it.batchId, it.rootEntityType, it.rootEntityId, it.displayTitle,
                    it.parentFolderIdSnapshot, it.subfolderCount, it.recordCount,
                    it.deletedAtMillis, it.expiresAtMillis
                )
            },
            timelineEvents = timeline.map {
                SerializableTimelineSnapshot(
                    it.id, it.eventType, it.entityType, it.entityId, it.displayTitle,
                    it.accentColor, it.timestampMillis
                )
            },
            drafts = drafts.map {
                SerializableDraftSnapshot(
                    it.draftKey, it.folderId, it.title, it.description,
                    it.category, it.customFieldsJson, it.updatedAt
                )
            }
            )
        }

        val plainSnapshotBytes = json.encodeToString(snapshotPayload).toByteArray(StandardCharsets.UTF_8)
        val snapshotAad = snapshotAad(backupId, subKeys.vaultId)
        val (snapshotIv, encryptedSnapshotBytes) = VaultCryptoEngine.encryptAesGcm(
            key = subKeys.backupPayloadKey,
            plaintext = plainSnapshotBytes,
            aad = snapshotAad
        )
        plainSnapshotBytes.fill(0)
        val encryptedSnapshotHash = VaultCryptoEngine.sha256Hex(encryptedSnapshotBytes)

        val attachmentRefs = ArrayList<BackupAttachmentRef>()
        val missingAttachments = ArrayList<String>()
        for (att in snapshotPayload.attachments) {
            val encFile = attachmentStorage.resolveEncryptedFile(att.encryptedRelativePath)
            if (!encFile.exists() || encFile.length() == 0L) {
                if (!att.isDeleted) missingAttachments.add(att.originalFileName)
                continue
            }
            attachmentRefs.add(
                BackupAttachmentRef(
                    attachmentId = att.id,
                    relativeZipEntry = "attachments/${att.id}.psenc",
                    encryptedSizeBytes = encFile.length(),
                    encryptedSha256Hex = sha256FileHex(encFile)
                )
            )
        }
        if (missingAttachments.isNotEmpty()) {
            throw MissingBackupAttachmentException(missingAttachments)
        }

        // ---- Inline editor images (files/note_images) ----
        // P5 (requirement 1): deleted notes are backed up, so the images their bodies reference must
        // be backed up as well — otherwise restoring a recycle-bin note gives back a note whose
        // images are gone. `isDeleted` no longer filters the image set; drafts are already included.
        val referencedImages = LinkedHashSet<String>()
        snapshotPayload.records.forEach { record ->
            RichNoteCodec.decodeBlocks(record.description)
                .filterIsInstance<RichNoteBlock.Image>()
                .forEach { block -> referencedImages.add(inlineImageStore.normalizeReference(block.imagePath)) }
        }
        snapshotPayload.drafts.forEach { draft ->
            RichNoteCodec.decodeBlocks(draft.description)
                .filterIsInstance<RichNoteBlock.Image>()
                .forEach { block -> referencedImages.add(inlineImageStore.normalizeReference(block.imagePath)) }
        }
        val inlineImageRefs = ArrayList<BackupInlineImageRef>()
        val missingImages = ArrayList<String>()
        for (identifier in referencedImages) {
            if (identifier.isBlank()) continue
            val encFile = inlineImageStore.encryptedFile(identifier)
            if (!encFile.exists() || encFile.length() == 0L) {
                missingImages.add(identifier)
                continue
            }
            val (width, height) = inlineImageStore.dimensionsOf(encFile)
            inlineImageRefs.add(
                BackupInlineImageRef(
                    identifier = identifier,
                    originalName = identifier + InlineImageStore.ENCRYPTED_SUFFIX,
                    relativeZipEntry = "inline_images/" + identifier + InlineImageStore.ENCRYPTED_SUFFIX,
                    encryptedSizeBytes = encFile.length(),
                    encryptedSha256Hex = sha256FileHex(encFile),
                    widthPx = width,
                    heightPx = height,
                    noteCount = snapshotPayload.records.count { record ->
                        !record.isDeleted && RichNoteCodec.decodeBlocks(record.description)
                            .filterIsInstance<RichNoteBlock.Image>()
                            .any { inlineImageStore.normalizeReference(it.imagePath) == identifier }
                    }
                )
            )
        }
        if (missingImages.isNotEmpty()) {
            throw MissingBackupImageException(missingImages)
        }
        val portableKeyWrap = buildPortableKeyWrap(subKeys.vaultDataKey, subKeys.vaultId)

        val unsignedManifest = BackupPackageManifest(
            backupId = backupId,
            vaultId = subKeys.vaultId,
            createdAtMillis = now,
            folderCount = snapshotPayload.folders.count { !it.isDeleted },
            recordCount = snapshotPayload.records.count { !it.isDeleted },
            attachmentCount = attachmentRefs.size,
            inlineImages = inlineImageRefs,
            inlineImageCount = inlineImageRefs.size,
            draftCount = snapshotPayload.drafts.size,
            portableKeyWrap = portableKeyWrap,
            snapshotIvBase64 = VaultCryptoEngine.toBase64(snapshotIv),
            encryptedSnapshotSha256Hex = encryptedSnapshotHash,
            attachments = attachmentRefs,
            envelopeBundle = envelopeBundle
        )
        val signedManifest = unsignedManifest.copy(
            manifestHmacBase64 = computeManifestHmac(unsignedManifest, subKeys.backupManifestKey)
        )

        // PRE_RESTORE_SAFETY remains in the enum for old backup-history rows, but no new hidden
        // full-size safety archive is written. Restore interruption protection is handled by
        // RestoreJournal + RestoreSnapshot instead.
        val destFile = targetFile ?: singleLocalBackupFile
        val tmpZip = File(
            destFile.parentFile ?: localBackupsDir,
            "${destFile.name}.${IdGenerator.newUuid()}.tmp"
        )

        try {
            ZipOutputStream(FileOutputStream(tmpZip).buffered()).use { zos ->
                // 1. manifest.json
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(json.encodeToString(signedManifest).toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()

                // 2. vault_snapshot.enc
                zos.putNextEntry(ZipEntry("vault_snapshot.enc"))
                zos.write(encryptedSnapshotBytes)
                zos.closeEntry()

                // 3. Streaming encrypted attachment files
                val copyBuf = ByteArray(64 * 1024)
                for (ref in attachmentRefs) {
                    val attEntity = snapshotPayload.attachments.first { it.id == ref.attachmentId }
                    val encFile = attachmentStorage.resolveEncryptedFile(attEntity.encryptedRelativePath)
                    zos.putNextEntry(ZipEntry(ref.relativeZipEntry))
                    FileInputStream(encFile).use { fis ->
                        var read: Int
                        while (fis.read(copyBuf).also { read = it } != -1) {
                            zos.write(copyBuf, 0, read)
                        }
                    }
                    zos.closeEntry()
                }

                // 4. Streaming inline images
                for (ref in inlineImageRefs) {
                    val imageFile = inlineImageStore.encryptedFile(ref.identifier)
                    zos.putNextEntry(ZipEntry(ref.relativeZipEntry))
                    FileInputStream(imageFile).use { fis ->
                        var read: Int
                        while (fis.read(copyBuf).also { read = it } != -1) {
                            zos.write(copyBuf, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }

            FileOutputStream(tmpZip, true).use { it.fd.sync() }
            if (!tmpZip.renameTo(destFile)) {
                tmpZip.delete()
                throw IOException("Backup could not be written atomically to ${destFile.name}.")
            }
            inspectAndValidatePackageStructure(destFile)

            if (targetFile == null) {
                runCatching { publishToExternalStorage(destFile) }
                runCatching { rememberBackupFingerprint(computeVaultFingerprint()) }
            }

            val packageHash = sha256FileHex(destFile)
            val outcome = CreatedBackupOutcome(
                backupId = backupId,
                file = destFile,
                sizeBytes = destFile.length(),
                sha256Hex = packageHash,
                recordCount = signedManifest.recordCount,
                attachmentCount = signedManifest.attachmentCount,
                inlineImageCount = signedManifest.inlineImageCount
            )

            if (recordHistoryInDb) {
                db.backupDao().upsert(
                    BackupMetadataEntity(
                        backupId = backupId,
                        backupKind = backupKind.name,
                        destination = "LOCAL_FILE",
                        locationRef = destFile.absolutePath,
                        sizeBytes = outcome.sizeBytes,
                        recordCount = outcome.recordCount,
                        attachmentCount = outcome.attachmentCount,
                        sha256Checksum = packageHash,
                        verified = true,
                        succeeded = true,
                        createdAtMillis = now
                    )
                )
                db.timelineDao().insert(
                    TimelineEventEntity(
                        id = IdGenerator.newUuid(),
                        eventType = TimelineEventType.BACKUP_COMPLETED.name,
                        entityType = "SYSTEM",
                        entityId = backupId,
                        displayTitle = backupKind.label,
                        accentColor = "YELLOW",
                        timestampMillis = now
                    )
                )
            }
            outcome
        } catch (e: Exception) {
            tmpZip.delete()
            if (recordHistoryInDb && sessionManager.currentSubKeysOrNull() != null) {
                runCatching {
                    db.backupDao().upsert(
                        BackupMetadataEntity(
                            backupId = backupId,
                            backupKind = backupKind.name,
                            destination = "LOCAL_FILE",
                            locationRef = destFile.name,
                            sizeBytes = 0L,
                            recordCount = 0,
                            attachmentCount = 0,
                            sha256Checksum = "",
                            verified = false,
                            succeeded = false,
                            errorMessage = e.message ?: "Backup packaging failed",
                            createdAtMillis = now
                        )
                    )
                }
            }
            throw e
        }
    }

    /**
     * Validates the structure and cryptographic checksums of a `.psbackup` file without modifying local state.
     */
    suspend fun inspectAndValidatePackageStructure(backupFile: File): BackupPackageManifest =
        withContext(Dispatchers.IO) {
            if (!backupFile.exists() || backupFile.length() == 0L) {
                throw CorruptedBackupException("Backup file does not exist or is empty.")
            }
            try {
                ZipFile(backupFile).use { zip ->
                    val manifestEntry = zip.getEntry("manifest.json")
                        ?: throw CorruptedBackupException("Invalid .psbackup package: missing manifest.json.")
                    val snapshotEntry = zip.getEntry("vault_snapshot.enc")
                        ?: throw CorruptedBackupException("Invalid .psbackup package: missing vault_snapshot.enc.")

                    val manifestText = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).readText()
                    val manifest = json.decodeFromString<BackupPackageManifest>(manifestText)
                    if (manifest.magic != "PSBACKUP_V1" || manifest.formatVersion !in SUPPORTED_FORMAT_VERSIONS) {
                        throw CorruptedBackupException("Unsupported backup format version (${manifest.formatVersion}).")
                    }

                    // Verify snapshot SHA-256
                    val actualSnapshotHash = zip.getInputStream(snapshotEntry).use { sha256StreamHex(it) }
                    if (!actualSnapshotHash.equals(manifest.encryptedSnapshotSha256Hex, ignoreCase = true)) {
                        throw CorruptedBackupException("Backup snapshot checksum mismatch — package is corrupted.")
                    }

                    // Verify every declared encrypted attachment entry exists & matches hash
                    for (attRef in manifest.attachments) {
                        val entry = zip.getEntry(attRef.relativeZipEntry)
                            ?: throw CorruptedBackupException("Backup missing attachment entry ${attRef.relativeZipEntry}.")
                        val attHash = zip.getInputStream(entry).use { sha256StreamHex(it) }
                        if (!attHash.equals(attRef.encryptedSha256Hex, ignoreCase = true)) {
                            throw CorruptedBackupException("Attachment ${attRef.attachmentId} failed integrity verification.")
                        }
                    }

                    // Verify every declared inline image entry exists & matches hash
                    for (imageRef in manifest.inlineImages) {
                        val entry = zip.getEntry(imageRef.relativeZipEntry)
                            ?: throw CorruptedBackupException("Backup missing inline image ${imageRef.relativeZipEntry}.")
                        val imageHash = zip.getInputStream(entry).use { sha256StreamHex(it) }
                        if (!imageHash.equals(imageRef.encryptedSha256Hex, ignoreCase = true)) {
                            throw CorruptedBackupException("Inline image ${imageRef.identifier} failed integrity verification.")
                        }
                    }
                    manifest
                }
            } catch (e: CorruptedBackupException) {
                throw e
            } catch (e: Exception) {
                throw CorruptedBackupException("Corrupted or unreadable .psbackup archive: ${e.message}", e)
            }
        }

    /**
     * Restores an encrypted `.psbackup` package using EITHER the Master Password OR the 24-word Recovery Key.
     *
     * Restore guarantees:
     * 1. Validates archive checksums, HMAC, decrypts the snapshot, and stages media before modifying the vault.
     * 2. Takes a temporary, hash-verified rollback snapshot of the live database and media before destructive writes.
     * 3. Verifies the restored content before committing; a failed/interrupted restore can be rolled back.
     *
     * A successful restore replaces the previous local content. No separate full-size safety archive is kept.
     */
    suspend fun restoreFromBackupFile(
        backupFile: File,
        masterPassword: CharArray? = null,
        recoveryPhrase: String? = null,
        recoveryPassword: CharArray? = null,
        @Suppress("UNUSED_PARAMETER") requireSafetyBackup: Boolean = false
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        // P2 / correction #2: never start a restore while an earlier one is unresolved. This is the
        // guard that makes "discard the existing vault" unreachable — an unresolved journal or an
        // untouched rollback copy blocks here, before anything is read or written.
        RestoreGuard.requireClear(context)
        FailureInjection.hit(STAGE_GUARD_CLEARED)
        FailureInjection.hit(STAGE_INSPECTION)
        val manifest = inspectAndValidatePackageStructure(backupFile)
        FailureInjection.hit(STAGE_VALIDATED)
        val liveSessionKeys = sessionManager.currentSubKeysOrNull()
        val candidateSubKeys = unlockCandidateKeys(manifest, masterPassword, recoveryPhrase, recoveryPassword)
        FailureInjection.hit(STAGE_KEYS_UNLOCKED)
        // The live session key set must never be zeroed: restoring from the same vault reuses it
        val ownsCandidateKeys = candidateSubKeys !== liveSessionKeys
        val stagingDir = File(context.cacheDir, "restore_stage_" + IdGenerator.newUuid()).apply { mkdirs() }
        try {
            FailureInjection.hit(STAGE_MANIFEST_HMAC)
            verifyManifestHmac(manifest, candidateSubKeys)
            val snapshot = decryptSnapshotPayload(backupFile, manifest, candidateSubKeys)
            FailureInjection.hit(STAGE_SNAPSHOT_DECRYPTED)
            FailureInjection.hit(STAGE_RELATIONSHIPS)
            validateSnapshotRelationships(snapshot)

            FailureInjection.hit(STAGE_STAGE_ATTACHMENTS)
            stageAttachments(backupFile, manifest, snapshot, candidateSubKeys, stagingDir)
            FailureInjection.hit(STAGE_ATTACHMENTS_STAGED)
            // P4: inline images are staged into the cache, never into files/note_images/.
            val stagedImagesDir = File(stagingDir, IMAGE_STAGING_DIR).apply { mkdirs() }
            FailureInjection.hit(STAGE_STAGE_IMAGES)
            stageInlineImages(backupFile, manifest, candidateSubKeys, stagedImagesDir)
            FailureInjection.hit(STAGE_IMAGES_STAGED)

            // No extra full-size .psbackup is created here. RestoreSnapshot below makes the
            // temporary rollback copy needed only until this restore has been verified and committed.

            val attachmentsDir = attachmentStorage.encryptedDir
            val imagesDir = inlineImageStore.imagesDir
            val previousDir = File(context.filesDir, "encrypted_attachments_previous_" + IdGenerator.newUuid())

            // ---- P2: file-level rollback journal ----
            // Every destructive step below is preceded by a rollback copy that is valid without any
            // vault key, so a failure (throw / OOM / process death) can always be undone.
            val journal = RestoreJournal.begin(context, manifest.backupId, timeProvider.nowMillis())
            journal.advance(RestorePhase.PREPARED)
            try {
                RestoreSnapshot.take(
                    dbHolder = dbHolder,
                    filesDir = context.filesDir,
                    rollbackDir = journal.rollbackDir,
                    attachmentsDir = attachmentsDir,
                    imagesDir = imagesDir
                )
            } catch (snapshotFailure: Throwable) {
                // Nothing destructive has happened yet, so this is a clean abort.
                journal.delete()
                throw BackupVerificationException(
                    "Restore aborted: a pre-restore rollback copy could not be created.",
                    snapshotFailure
                )
            }
            journal.advance(RestorePhase.SNAPSHOT_TAKEN)

            // IMPORTANT: the rollback snapshot closes the database (the only way to guarantee every
            // committed row is in the main file — see dbP4_close_then_copy). The handle obtained
            // before the snapshot is therefore dead, so it must be re-acquired *after* it.
            val db = dbHolder.getDatabase()

            val previousMoved = attachmentsDir.exists() && attachmentsDir.renameTo(previousDir)

            // P6 (decision 4): while the restore installs references (attachment + image swap, DB
            // insert), no other media decision may run. The media write lock is the same lock every
            // delete path takes, and the restore-in-flight flag makes permanent deletion refuse
            // outright instead of waiting for a lock it would then act behind.
            val restoreToken = "restore_" + IdGenerator.newUuid()
            app.personalspace.vault.data.attachment.MediaReferenceIndex.markRestoreInFlight(restoreToken)
            try {
              app.personalspace.vault.data.attachment.MediaReferenceIndex.withMediaLock {
                journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
                attachmentsDir.mkdirs()
                stagingDir.listFiles()?.forEach { staged ->
                    // P4: skip the inline-image staging subdirectory; it belongs to the image swap
                    // below, not to the attachment directory.
                    if (staged.isFile) {
                        staged.copyTo(File(attachmentsDir, staged.name), overwrite = true)
                    }
                }

                journal.advance(RestorePhase.SWAPPING_IMAGES)
                // P4: install the staged images into the live directory by COPY ONLY.
                //
                // Deliberately no rename-aside / no "delete previous dir" step here. The pre-restore
                // images already live in the journal rollback copy (RestoreSnapshot.take copies
                // IMAGES_BACKUP + records its hashes in `copy_manifest.txt`), and BOTH rollback paths
                // use that copy as the single source of truth:
                //   * in-process failure -> rollbackToJournal -> RestoreSnapshot.restore -> replaceDir
                //     (wipes files/note_images and copies the snapshot back, so half-installed files
                //      cannot survive);
                //   * process death -> P3 startup recovery -> RestoreRecovery.restoreFromCopy, which
                //     then verifies the live dir against the manifest before deleting anything.
                // A second "previous" directory would be redundant state that no rollback path knows
                // about, so the live directory is never moved or emptied before the snapshot exists.
                FailureInjection.hit(STAGE_INSTALL_IMAGES)
                imagesDir.mkdirs()
                stagedImagesDir.listFiles()?.forEach { staged ->
                    if (staged.isFile) staged.copyTo(File(imagesDir, staged.name), overwrite = true)
                }

                FailureInjection.hit(STAGE_PRE_DB_TRANSACTION)
                journal.advance(RestorePhase.DB_TXN)
                FailureInjection.hit(STAGE_INSTALL_ATTACHMENTS)
                db.withTransaction {
                    FailureInjection.hit(STAGE_DB_TRANSACTION)
                    db.recordDao().deleteAllDrafts()
                    db.recordDao().deleteAllVersions()
                    db.attachmentDao().deleteAllAttachments()
                    db.recycleBinDao().clearAllBatches()
                    db.timelineDao().deleteAll()
                    db.recordDao().deleteAllRecords()
                    db.folderDao().deleteAllFolders()
                    insertSnapshotInto(db, snapshot)
                }

                FailureInjection.hit(STAGE_VERIFICATION)
                journal.advance(RestorePhase.VERIFIED)
                val verification = verifyRestoredVault(db, snapshot, manifest, backupFile, candidateSubKeys)
                if (!verification.verified) {
                    throw BackupVerificationException(verification.reason)
                }
                FailureInjection.hit(STAGE_PRE_COMMIT)
                journal.advance(RestorePhase.COMMITTED)
                FailureInjection.hit(STAGE_POST_COMMIT_CLEANUP)
                previousDir.deleteRecursively()
                journal.delete()
                journal.rollbackDir.deleteRecursively()
                BackupRestoreResult(
                    // unchanged active-only semantics
                    notes = verification.activeNotes,
                    folders = verification.activeFolders,
                    attachments = verification.activeAttachments,
                    drafts = snapshot.drafts.size,
                    versions = snapshot.versions.size,
                    inlineImages = manifest.inlineImages.size,
                    verified = true,
                    sourceFormatVersion = manifest.formatVersion,
                    // P6 split
                    activeNotes = verification.activeNotes,
                    deletedNotes = verification.deletedNotes,
                    activeFolders = verification.activeFolders,
                    deletedFolders = verification.deletedFolders,
                    activeAttachments = verification.activeAttachments,
                    deletedAttachments = verification.deletedAttachments,
                    deletedInlineImages = manifest.inlineImages.count { imageRef ->
                        snapshot.records.filter { it.isDeleted }.sumOf { record ->
                            RichNoteCodec.decodeBlocks(record.description)
                                .filterIsInstance<RichNoteBlock.Image>()
                                .count { inlineImageStore.normalizeReference(it.imagePath) == imageRef.identifier }
                        } > 0
                    },
                    verificationSummary = verification.summary
                )
              }
            } catch (failure: Throwable) {
                // P2: the old best-effort path deleted the live attachment directory and hoped a
                // rename would put it back, leaving the DB as-is (asymmetric rollback, finding B1).
                // Now the pre-restore copy is authoritative and restores DB + both media dirs.
                //
                // P4: rollbackToJournal() throws if the rollback itself failed, in which case the
                // pre-restore copies must stay on disk. It only returns normally once the vault is
                // proven back in its pre-restore state — only then is the attachment swap's
                // "previous" copy redundant. Deleting it here (and only here) stops failed restores
                // from accumulating stale encrypted attachment copies in files/.
                // The restore-in-flight flag is still raised here, so permanent deletion cannot run
                // behind the rollback and delete files while the vault is being put back together.
                rollbackToJournal(journal)
                previousDir.deleteRecursively()
                throw failure
            } finally {
                app.personalspace.vault.data.attachment.MediaReferenceIndex.clearRestoreInFlight(restoreToken)
            }
        } finally {
            if (ownsCandidateKeys) candidateSubKeys.wipe()
            stagingDir.deleteRecursively()
        }
    }

    /**
     * P2 rollback. Restores DB + attachments + inline images from the journal copy.
     *
     * If this fails, the rollback copy is deliberately **kept on disk** and a
     * [RestoreRollbackFailedException] is thrown carrying its location. Nothing is ever deleted to
     * "recover", and the vault is never silently left in the half-restored state.
     */
    private fun rollbackToJournal(journal: RestoreJournal) {
        val attachmentsDir = attachmentStorage.encryptedDir
        val imagesDir = inlineImageStore.imagesDir
        val failure = runCatching {
            RestoreSnapshot.restore(
                dbHolder = dbHolder,
                filesDir = context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = attachmentsDir,
                imagesDir = imagesDir
            )
        }
        // P9: an injected rollback failure must land here, so the ROLLBACK_FAILED contract (copy kept,
        // journal preserved, gate blocked) is exercised for real rather than simulated.
        val injected = FailureInjection.consumeInjection(STAGE_ROLLBACK)
        val effectiveFailure = failure.exceptionOrNull() ?: injected
        effectiveFailure?.let { rollbackError ->
            journal.advance(RestorePhase.ROLLBACK_FAILED)
            throw RestoreRollbackFailedException(
                "Restore failed AND automatic rollback failed. The pre-restore copy has been kept at " +
                    journal.rollbackDir.absolutePath + " — no data has been deleted.",
                journal.rollbackDir,
                rollbackError
            )
        }
        journal.advance(RestorePhase.ROLLED_BACK)
        // Rollback succeeded -> the vault is exactly as before the restore, so the working copy and
        // its journal record are no longer needed. The user data itself is untouched.
        journal.delete()
        journal.rollbackDir.deleteRecursively()
    }

    private fun unlockCandidateKeys(
        manifest: BackupPackageManifest,
        masterPassword: CharArray?,
        recoveryPhrase: String?,
        recoveryPassword: CharArray?
    ): DerivedVaultSubKeys {
        if (masterPassword != null && masterPassword.isNotEmpty()) {
            return vaultKeyManager.unlockWithMasterPassword(masterPassword, manifest.envelopeBundle)
        }
        if (!recoveryPhrase.isNullOrBlank()) {
            return vaultKeyManager.unlockWithRecoveryPhrase(recoveryPhrase, manifest.envelopeBundle)
        }
        if (recoveryPassword != null && recoveryPassword.isNotEmpty()) {
            return vaultKeyManager.unlockWithRecoveryPassword(recoveryPassword, manifest.envelopeBundle)
        }
        val current = sessionManager.currentSubKeysOrNull()
        if (current != null && current.vaultId == manifest.vaultId) return current

        val portable = manifest.portableKeyWrap
        if (portable != null) {
            runCatching { return unwrapPortableKeyWrap(portable, manifest.vaultId) }
        }
        runCatching {
            return vaultKeyManager.unlockWithRecoveryPassword(
                LEGACY_FIXED_RESTORE_PASSWORD.copyOf(),
                manifest.envelopeBundle
            )
        }
        throw CryptoAuthException(
            "This backup cannot be unlocked by this installation. Use the original device or a backup " +
                "created by Personal Space v1.1.7 or newer."
        )
    }

    private fun verifyManifestHmac(manifest: BackupPackageManifest, subKeys: DerivedVaultSubKeys) {
        val expected = computeManifestHmac(
            manifest.copy(manifestHmacBase64 = ""),
            subKeys.backupManifestKey
        )
        if (expected != manifest.manifestHmacBase64) {
            throw CorruptedBackupException("Backup manifest cryptographic authentication failed.")
        }
    }

    /**
     * P8: exposes the decrypted snapshot JSON of a package so a caller (the generation manager, a
     * diagnostics export, a test) can run the deep verifier against the *package* rather than the
     * live database. Read-only: nothing is installed and no key is written.
     */
    suspend fun readSnapshotJsonForVerification(
        backupFile: File,
        masterPassword: CharArray? = null
    ): String = withContext(Dispatchers.IO) {
        val manifest = inspectAndValidatePackageStructure(backupFile)
        val subKeys = sessionManager.currentSubKeysOrNull()
            ?: unlockCandidateKeys(manifest, masterPassword, null, null)
        ZipFile(backupFile).use { zip ->
            val entry = zip.getEntry("vault_snapshot.enc")
                ?: throw CorruptedBackupException("Invalid .psbackup package: missing vault_snapshot.enc.")
            val encBytes = zip.getInputStream(entry).use { it.readBytes() }
            if (VaultCryptoEngine.sha256Hex(encBytes) != manifest.encryptedSnapshotSha256Hex) {
                throw CorruptedBackupException("Backup snapshot checksum does not match the manifest.")
            }
            val plain = VaultCryptoEngine.decryptAesGcm(
                key = subKeys.backupPayloadKey,
                iv = VaultCryptoEngine.fromBase64(manifest.snapshotIvBase64),
                ciphertext = encBytes,
                aad = snapshotAad(manifest.backupId, manifest.vaultId)
            )
            try {
                String(plain, StandardCharsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        }
    }

    private fun decryptSnapshotPayload(
        backupFile: File,
        manifest: BackupPackageManifest,
        subKeys: DerivedVaultSubKeys
    ): VaultSnapshotPayload = try {
        ZipFile(backupFile).use { zip ->
            val entry = zip.getEntry("vault_snapshot.enc")
                ?: throw CorruptedBackupException("Invalid .psbackup package: missing vault_snapshot.enc.")
            val encBytes = zip.getInputStream(entry).use { it.readBytes() }
            if (VaultCryptoEngine.sha256Hex(encBytes) != manifest.encryptedSnapshotSha256Hex) {
                throw CorruptedBackupException("Backup snapshot checksum does not match the manifest.")
            }
            val plain = VaultCryptoEngine.decryptAesGcm(
                key = subKeys.backupPayloadKey,
                iv = VaultCryptoEngine.fromBase64(manifest.snapshotIvBase64),
                ciphertext = encBytes,
                aad = snapshotAad(manifest.backupId, manifest.vaultId)
            )
            try {
                json.decodeFromString<VaultSnapshotPayload>(String(plain, StandardCharsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        }
    } catch (e: CorruptedBackupException) {
        throw e
    } catch (e: Exception) {
        throw CorruptedBackupException("Failed to decrypt backup snapshot: ${e.message}", e)
    }

    /**
     * Extracts inline images from the package and writes them with this device's vault key.
     * Same-vault packages are copied as-is; cross-vault packages are re-encrypted.
     */
    private suspend fun stageInlineImages(
        backupFile: File,
        manifest: BackupPackageManifest,
        candidateSubKeys: DerivedVaultSubKeys,
        /**
         * P4: destination for staged images. Restore always passes a cache-directory staging folder
         * so the live `note_images/` directory is untouched until the swap step.
         */
        stagingDir: File
    ) {
        if (manifest.inlineImages.isEmpty()) return
        val sameVault = sessionManager.currentSubKeysOrNull()?.vaultId == manifest.vaultId
        val destinationKey = sessionManager.currentSubKeysOrNull()?.attachmentMasterKey
            ?: throw CryptoAuthException("Vault must be unlocked before restoring a backup.")
        ZipFile(backupFile).use { zip ->
            for (imageRef in manifest.inlineImages) {
                val entry = zip.getEntry(imageRef.relativeZipEntry)
                    ?: throw CorruptedBackupException("Backup is missing inline image '${imageRef.relativeZipEntry}'.")
                val raw = zip.getInputStream(entry).use { it.readBytes() }
                if (raw.size.toLong() != imageRef.encryptedSizeBytes ||
                    VaultCryptoEngine.sha256Hex(raw) != imageRef.encryptedSha256Hex
                ) {
                    throw CorruptedBackupException(
                        "Inline image '${imageRef.identifier}' is corrupted (checksum mismatch)."
                    )
                }
                val written = inlineImageStore.writeRestoredImage(
                    identifier = imageRef.identifier,
                    ciphertext = raw,
                    sourceIsPlaintext = false,
                    sourceKey = if (sameVault) destinationKey else candidateSubKeys.attachmentMasterKey,
                    targetDir = stagingDir
                )
                if (!written) {
                    throw CorruptedBackupException(
                        "Inline image '${imageRef.identifier}' could not be decrypted with the restored vault key."
                    )
                }
                raw.fill(0)
            }
        }
    }

    private fun validateSnapshotRelationships(snapshot: VaultSnapshotPayload) {
        val folderIds = snapshot.folders.map { it.id }.toSet()
        val recordIds = snapshot.records.map { it.id }.toSet()
        snapshot.records.forEach { record ->
            if (record.folderId != null && record.folderId !in folderIds) {
                throw CorruptedBackupException("Backup is inconsistent: note ${record.id} points to a missing folder.")
            }
        }
        snapshot.versions.forEach { version ->
            if (version.recordId !in recordIds) {
                throw CorruptedBackupException("Backup is inconsistent: version ${version.id} has no note.")
            }
        }
        snapshot.attachments.forEach { attachment ->
            if (attachment.recordId !in recordIds) {
                throw CorruptedBackupException(
                    "Backup is inconsistent: attachment '${attachment.originalFileName}' has no note."
                )
            }
        }
        snapshot.drafts.forEach { draft ->
            if (draft.folderId != null && draft.folderId !in folderIds) {
                throw CorruptedBackupException("Backup is inconsistent: draft '${draft.title}' has no folder.")
            }
        }
    }

    private fun stageAttachments(
        backupFile: File,
        manifest: BackupPackageManifest,
        snapshot: VaultSnapshotPayload,
        subKeys: DerivedVaultSubKeys,
        stagingDir: File
    ) {
        if (manifest.attachments.isEmpty()) return
        val sameVault = sessionManager.currentSubKeysOrNull()?.vaultId == manifest.vaultId
        val destinationKey = sessionManager.currentSubKeysOrNull()?.attachmentMasterKey
            ?: throw CryptoAuthException("Vault must be unlocked before restoring a backup.")
        ZipFile(backupFile).use { zip ->
            for (ref in manifest.attachments) {
                val entry = zip.getEntry(ref.relativeZipEntry)
                    ?: throw CorruptedBackupException(
                        "Backup is missing attachment entry '${ref.relativeZipEntry}'."
                    )
                val raw = zip.getInputStream(entry).use { it.readBytes() }
                if (raw.size.toLong() != ref.encryptedSizeBytes ||
                    VaultCryptoEngine.sha256Hex(raw) != ref.encryptedSha256Hex
                ) {
                    throw CorruptedBackupException(
                        "Attachment '${ref.relativeZipEntry}' is corrupted (checksum mismatch)."
                    )
                }
                val sourceFile = File(stagingDir, ref.attachmentId + ".psenc.source")
                sourceFile.writeBytes(raw)
                val target = File(stagingDir, ref.attachmentId + ".psenc")
                if (sameVault) {
                    sourceFile.copyTo(target, overwrite = true)
                } else {
                    val plaintextHash = snapshot.attachments
                        .firstOrNull { it.id == ref.attachmentId }
                        ?.plaintextSha256Hex
                    importForeignAttachment(
                        source = sourceFile,
                        target = target,
                        attachmentId = ref.attachmentId,
                        sourceKey = subKeys.attachmentMasterKey,
                        targetKey = destinationKey,
                        expectedSha256Hex = plaintextHash
                    )
                }
                sourceFile.delete()
            }
        }
    }

    private suspend fun insertSnapshotInto(db: PersonalSpaceDatabase, snapshot: VaultSnapshotPayload) {
        db.folderDao().upsertAll(
            orderFoldersTopologically(snapshot.folders).map {
                FolderEntity(
                    id = it.id, name = it.name, parentFolderId = it.parentFolderId,
                    colorId = it.colorId, createdAt = it.createdAt, modifiedAt = it.modifiedAt,
                    sortOrder = it.sortOrder, isDeleted = it.isDeleted, deletedAt = it.deletedAt,
                    deletedBatchId = it.deletedBatchId, syncVersion = it.syncVersion,
                    lastModifiedByDeviceId = it.lastModifiedByDeviceId, syncPending = false
                )
            }
        )
        db.recordDao().upsertAll(
            snapshot.records.map {
                RecordEntity(
                    id = it.id, title = it.title, description = it.description,
                    folderId = it.folderId, category = it.category, createdAt = it.createdAt,
                    modifiedAt = it.modifiedAt, customFieldsJson = it.customFieldsJson,
                    currentVersionNumber = it.currentVersionNumber, currentVersionId = it.currentVersionId,
                    isDeleted = it.isDeleted, deletedAt = it.deletedAt, deletedBatchId = it.deletedBatchId,
                    syncVersion = it.syncVersion,
                    lastModifiedByDeviceId = it.lastModifiedByDeviceId, syncPending = false
                )
            }
        )
        db.recordDao().insertVersions(
            snapshot.versions.map {
                RecordVersionEntity(
                    id = it.id, recordId = it.recordId, versionNumber = it.versionNumber,
                    title = it.title, description = it.description, category = it.category,
                    customFieldsJson = it.customFieldsJson, createdAt = it.createdAt,
                    createdByDeviceId = it.createdByDeviceId, changeSummary = it.changeSummary
                )
            }
        )
        db.attachmentDao().upsertAll(
            snapshot.attachments.map {
                AttachmentEntity(
                    id = it.id, recordId = it.recordId, originalFileName = it.originalFileName,
                    mimeType = it.mimeType, attachmentKind = it.attachmentKind,
                    plaintextSizeBytes = it.plaintextSizeBytes,
                    encryptedSizeBytes = it.encryptedSizeBytes,
                    encryptedRelativePath = it.encryptedRelativePath,
                    plaintextSha256Hex = it.plaintextSha256Hex, createdAt = it.createdAt,
                    modifiedAt = it.modifiedAt, isDeleted = it.isDeleted, deletedAt = it.deletedAt,
                    syncVersion = it.syncVersion,
                    lastModifiedByDeviceId = it.lastModifiedByDeviceId, syncPending = false
                )
            }
        )
        db.recycleBinDao().upsertAll(
            snapshot.deletedBatches.map {
                DeletedItemEntity(
                    batchId = it.batchId, rootEntityType = it.rootEntityType,
                    rootEntityId = it.rootEntityId, displayTitle = it.displayTitle,
                    parentFolderIdSnapshot = it.parentFolderIdSnapshot,
                    subfolderCount = it.subfolderCount, recordCount = it.recordCount,
                    deletedAtMillis = it.deletedAtMillis, expiresAtMillis = it.expiresAtMillis
                )
            }
        )
        db.timelineDao().insertAll(
            snapshot.timelineEvents.map {
                TimelineEventEntity(
                    id = it.id, eventType = it.eventType, entityType = it.entityType,
                    entityId = it.entityId, displayTitle = it.displayTitle,
                    accentColor = it.accentColor, timestampMillis = it.timestampMillis
                )
            }
        )
        snapshot.drafts.forEach { draft ->
            db.recordDao().saveDraft(
                RecordDraftEntity(
                    draftKey = draft.draftKey, folderId = draft.folderId, title = draft.title,
                    description = draft.description, category = draft.category,
                    customFieldsJson = draft.customFieldsJson, updatedAt = draft.updatedAt
                )
            )
        }
    }

    /**
     * P6: verification now reports the active/deleted split. [reason] stays the failure message;
     * [summary] is the human-readable line for the restored vault (also used on success).
     */
    private data class RestoreVerification(
        val verified: Boolean,
        val reason: String,
        val activeNotes: Int = 0,
        val deletedNotes: Int = 0,
        val activeFolders: Int = 0,
        val deletedFolders: Int = 0,
        val activeAttachments: Int = 0,
        val deletedAttachments: Int = 0,
        val summary: String = ""
    )

    private suspend fun verifyRestoredVault(
        db: PersonalSpaceDatabase,
        snapshot: VaultSnapshotPayload,
        manifest: BackupPackageManifest,
        /** P8: the package on disk, needed to hash the image plaintext it should have produced. */
        backupFileForVerification: File? = null,
        candidateSubKeys: DerivedVaultSubKeys? = null
    ): RestoreVerification {
        // P6 (decision 5): verify the ACTIVE and DELETED halves separately, so a deleted-note loss is
        // caught on its own instead of being absorbed by a single total. The printed summary carries
        // both numbers, so "notes = 0" can no longer be mistaken for "nothing was restored".
        val rowsByRecord = db.recordDao().getAllRecordsIncludingDeleted()
        val rowsByFolder = db.folderDao().getAllFoldersIncludingDeleted()
        val rowsByAttachment = db.attachmentDao().getAllAttachmentsIncludingDeleted()
        val recordCount = rowsByRecord.size
        val folderCount = rowsByFolder.size
        val attachmentCount = rowsByAttachment.size
        val draftCount = db.recordDao().getAllDrafts().size

        val activeNotes = rowsByRecord.count { !it.isDeleted }
        val deletedNotes = rowsByRecord.count { it.isDeleted }
        val activeFolders = rowsByFolder.count { !it.isDeleted }
        val deletedFolders = rowsByFolder.count { it.isDeleted }
        val activeAttachments = rowsByAttachment.count { !it.isDeleted }
        val deletedAttachments = rowsByAttachment.count { it.isDeleted }

        val expectedActiveNotes = snapshot.records.count { !it.isDeleted }
        val expectedDeletedNotes = snapshot.records.count { it.isDeleted }
        val expectedActiveFolders = snapshot.folders.count { !it.isDeleted }
        val expectedDeletedFolders = snapshot.folders.count { it.isDeleted }
        val expectedActiveAttachments = snapshot.attachments.count { !it.isDeleted }
        val expectedDeletedAttachments = snapshot.attachments.count { it.isDeleted }

        val summary = buildString {
            append("$recordCount notes (")
            append("$activeNotes active, $deletedNotes in recycle bin), ")
            append("$folderCount folders (")
            append("$activeFolders active, $deletedFolders deleted), ")
            append("$attachmentCount attachments (")
            append("$activeAttachments active, $deletedAttachments deleted), ")
            append("$draftCount drafts")
        }

        fun fail(reason: String) = RestoreVerification(
            false, reason,
            activeNotes, deletedNotes, activeFolders, deletedFolders,
            activeAttachments, deletedAttachments, summary
        )

        if (activeNotes != expectedActiveNotes) {
            return fail("Restore verification failed: active note count mismatch ($activeNotes vs $expectedActiveNotes).")
        }
        if (deletedNotes != expectedDeletedNotes) {
            return fail("Restore verification failed: recycle-bin note count mismatch ($deletedNotes vs $expectedDeletedNotes).")
        }
        if (recordCount != snapshot.records.size) {
            return fail("Restore verification failed: note count mismatch.")
        }
        if (activeFolders != expectedActiveFolders || deletedFolders != expectedDeletedFolders) {
            return fail("Restore verification failed: folder active/deleted count mismatch.")
        }
        if (folderCount != snapshot.folders.size) {
            return fail("Restore verification failed: folder count mismatch.")
        }
        if (activeAttachments != expectedActiveAttachments ||
            deletedAttachments != expectedDeletedAttachments
        ) {
            return fail("Restore verification failed: attachment active/deleted count mismatch.")
        }
        if (attachmentCount != snapshot.attachments.size) {
            return fail("Restore verification failed: attachment count mismatch.")
        }
        if (draftCount != snapshot.drafts.size || draftCount != manifest.draftCount) {
            return fail("Restore verification failed: draft count mismatch.")
        }
        // P5 (requirement 4): only ACTIVE attachments must have readable bytes.
        //
        // Deleted attachments are preserved rows, not promised bytes: a vault written by an older
        // build can legitimately contain a deleted attachment row whose ciphertext was already
        // removed by the old hard-deleting deleteAttachment(). Failing verification for those rows
        // made such a package unrestorable — the restore rolled back, so the user could never
        // restore their own backup. The missing file is not a corruption signal for deleted content,
        // and it is not invented either: the row keeps its original path and hash.
        snapshot.attachments.filter { !it.isDeleted }.forEach { attachment ->
            val file = attachmentStorage.resolveEncryptedFile(attachment.encryptedRelativePath)
            if (!file.exists() || file.length() == 0L) {
                return fail(
                    "Restore verification failed: attachment file missing for '${attachment.originalFileName}'."
                )
            }
        }
        manifest.inlineImages.forEach { imageRef ->
            val file = inlineImageStore.encryptedFile(imageRef.identifier)
            if (!file.exists() || file.length() == 0L) {
                return fail(
                    "Restore verification failed: inline image missing for '${imageRef.originalName}'."
                )
            }
        }

        // ---- P8: content verification, not just counts ---------------------------------------
        // The checks above prove the right NUMBER of rows and that the files exist. They cannot tell
        // a note whose body was emptied from a note that is fine, or a bold span that vanished during
        // the copy. This compares the staged snapshot against the live database field by field and
        // each media file by hash, and reports the exact entity+field that differs.
        val expectedImagePlaintext = computedExpectedImagePlaintext(backupFileForVerification, manifest, candidateSubKeys)
        val deep = runCatching {
            DeepBackupVerifier(db, storageView(), imageView())
                .verify(
                    json.encodeToString(VaultSnapshotPayload.serializer(), snapshot),
                    verifyMedia = true,
                    inlineImages = manifest.inlineImages,
                    expectedImagePlaintext = expectedImagePlaintext
                )
        }.getOrElse { failure ->
            // a verifier crash must never be reported as success
            return fail("Restore verification failed: content verification could not run (${failure.message}).")
        }
        if (!deep.passed) {
            val first = deep.mismatches.first()
            return fail(
                "Restore verification failed: ${deep.mismatches.size} content mismatch(es) — " +
                    first.describe()
            )
        }
        return RestoreVerification(
            true, "ok",
            activeNotes, deletedNotes, activeFolders, deletedFolders,
            activeAttachments, deletedAttachments, summary + " | content: " + deep.summary()
        )
    }

    /**
     * P8: decrypts every inline image **out of the package** and hashes the plaintext.
     *
     * This is the only expectation that survives a cross-vault restore: the package's stored bytes are
     * re-encrypted under the destination key during installation, so the ciphertext hash changes while
     * the picture must not.
     */
    private suspend fun computedExpectedImagePlaintext(
        backupFile: File?,
        manifest: BackupPackageManifest,
        subKeys: DerivedVaultSubKeys?
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (backupFile == null || subKeys == null || manifest.inlineImages.isEmpty()) return@withContext emptyMap()
        val out = HashMap<String, String>()
        runCatching {
            ZipFile(backupFile).use { zip ->
                manifest.inlineImages.forEach { imageRef ->
                    val entry = zip.getEntry(imageRef.relativeZipEntry) ?: return@forEach
                    val raw = zip.getInputStream(entry).use { it.readBytes() }
                    val cipherReference = inlineImageStore.cipherReference(imageRef.identifier)
                    val plain = java.io.ByteArrayOutputStream()
                    AttachmentChunkCipher.decryptStream(
                        attachmentMasterKey = subKeys.attachmentMasterKey,
                        attachmentId = cipherReference,
                        input = raw.inputStream(),
                        output = plain
                    )
                    out[imageRef.identifier] = DeepBackupVerifier.sha256(plain.toByteArray())
                    plain.reset()
                    raw.fill(0)
                }
            }
        }
        out
    }

    /** P8: adapter so the verifier can read attachments through the real storage layer. */
    private fun storageView() = object : DeepBackupVerifier.EncryptedAttachmentStorageView {
        override fun exists(relativePath: String): Boolean =
            attachmentStorage.resolveEncryptedFile(relativePath).exists()

        /** Decrypts the whole file, discarding plaintext, and returns its sha256 + size. */
        private fun digest(relativePath: String): Pair<String, Long>? = runCatching {
            val file = attachmentStorage.resolveEncryptedFile(relativePath)
            if (!file.exists()) return null
            val key = sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
            try {
                val sink = java.io.ByteArrayOutputStream()
                val size = AttachmentChunkCipher.decryptStream(
                    attachmentMasterKey = key,
                    attachmentId = file.name.removeSuffix(".psenc"),
                    input = java.io.FileInputStream(file).buffered(),
                    output = sink
                )
                val md = MessageDigest.getInstance("SHA-256")
                md.update(sink.toByteArray())
                sink.reset()
                md.digest().joinToString("") { "%02x".format(it) } to size
            } finally {
                key.fill(0)
            }
        }.getOrNull()

        override fun plaintextSha256(relativePath: String): String? = digest(relativePath)?.first

        override fun decryptedByteSize(relativePath: String): Long? = digest(relativePath)?.second
    }

    /** P8: adapter for inline images (stored-bytes hash + plaintext hash + size). */
    private fun imageView() = object : DeepBackupVerifier.InlineImageView {
        override fun exists(identifier: String): Boolean = inlineImageStore.exists(identifier)

        override fun storedSha256(identifier: String): String? = runCatching {
            DeepBackupVerifier.sha256(inlineImageStore.encryptedFile(identifier))
        }.getOrNull()

        override fun plaintextSha256(identifier: String): String? =
            kotlinx.coroutines.runBlocking { inlineImageStore.plaintextDigest(identifier) }?.first


    }

    private fun importForeignAttachment(
        source: File,
        target: File,
        attachmentId: String,
        sourceKey: ByteArray,
        targetKey: ByteArray,
        expectedSha256Hex: String?
    ) {
        val plaintextTmp = File(context.cacheDir, "merge_plain_${IdGenerator.newUuid()}.bin")
        val cipherTmp = File(target.parentFile ?: context.cacheDir, "${target.name}.tmp")
        try {
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(plaintextTmp).buffered().use { output ->
                    AttachmentChunkCipher.decryptStream(
                        attachmentMasterKey = sourceKey,
                        attachmentId = attachmentId,
                        input = input,
                        output = output,
                        expectedSha256Hex = expectedSha256Hex
                    )
                }
            }
            FileInputStream(plaintextTmp).buffered().use { input ->
                FileOutputStream(cipherTmp).buffered().use { output ->
                    AttachmentChunkCipher.encryptStream(
                        attachmentMasterKey = targetKey,
                        attachmentId = attachmentId,
                        input = input,
                        output = output
                    )
                }
            }
            if (!cipherTmp.renameTo(target)) {
                cipherTmp.copyTo(target, overwrite = true)
            }
        } finally {
            plaintextTmp.delete()
            cipherTmp.delete()
        }
    }

    fun migrateLegacyLocalBackups() {
        val legacy = localBackupsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".psbackup") && it.name != LOCAL_BACKUP_NAME }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (legacy.isEmpty()) return
        if (singleLocalBackupFile.exists()) return
        val newest = legacy.first()
        if (!newest.renameTo(singleLocalBackupFile)) {
            runCatching { newest.copyTo(singleLocalBackupFile, overwrite = true) }
        }
    }

    private fun buildPortableKeyWrap(vaultDataKey: ByteArray, vaultId: String): PortableKeyWrap {
        val salt = VaultCryptoEngine.randomBytes(VaultCryptoEngine.SALT_BYTES)
        val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(
            portablePassword(vaultId),
            salt,
            PORTABLE_ARGON2
        )
        try {
            val (iv, cipher) = VaultCryptoEngine.encryptAesGcm(
                key = wrappingKey,
                plaintext = vaultDataKey,
                aad = portableAad(vaultId)
            )
            return PortableKeyWrap(
                saltBase64 = VaultCryptoEngine.toBase64(salt),
                ivBase64 = VaultCryptoEngine.toBase64(iv),
                ciphertextBase64 = VaultCryptoEngine.toBase64(cipher),
                argon2Config = PORTABLE_ARGON2
            )
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun unwrapPortableKeyWrap(wrap: PortableKeyWrap, vaultId: String): DerivedVaultSubKeys {
        val salt = VaultCryptoEngine.fromBase64(wrap.saltBase64)
        val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(
            portablePassword(vaultId),
            salt,
            wrap.argon2Config ?: PORTABLE_ARGON2
        )
        val vdk = try {
            VaultCryptoEngine.decryptAesGcm(
                key = wrappingKey,
                iv = VaultCryptoEngine.fromBase64(wrap.ivBase64),
                ciphertext = VaultCryptoEngine.fromBase64(wrap.ciphertextBase64),
                aad = portableAad(vaultId)
            )
        } finally {
            wrappingKey.fill(0)
        }
        try {
            return VaultCryptoEngine.deriveSubKeys(vdk, vaultId)
        } finally {
            vdk.fill(0)
        }
    }

    private fun portablePassword(vaultId: String): CharArray =
        ("psbackup-portable:" + vaultId).toCharArray()

    private fun portableAad(vaultId: String): ByteArray =
        ("psbackup-portable-aad:" + vaultId).toByteArray(StandardCharsets.UTF_8)


    fun saveBackupCopy(output: OutputStream) {
        val source = singleLocalBackupFile
        if (!source.exists()) throw IllegalStateException("Phone me backup file nahi hai.")
        copyBackupToStream(source, output)
    }

    fun computeLocalBackupsBytes(): Long =
        localBackupsDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun copyBackupToStream(sourceFile: File, output: OutputStream) {
        FileInputStream(sourceFile).buffered().use { input ->
            input.copyTo(output, bufferSize = 64 * 1024)
        }
    }

    private fun orderFoldersTopologically(folders: List<SerializableFolderSnapshot>): List<SerializableFolderSnapshot> {
        val byId = folders.associateBy { it.id }
        val result = ArrayList<SerializableFolderSnapshot>(folders.size)
        val visited = HashSet<String>()

        fun visit(folder: SerializableFolderSnapshot, depth: Int) {
            if (folder.id in visited || depth > 128) return
            folder.parentFolderId?.let { pid ->
                byId[pid]?.let { parent -> visit(parent, depth + 1) }
            }
            visited.add(folder.id)
            result.add(folder)
        }

        folders.forEach { visit(it, 0) }
        return result
    }

    private fun snapshotAad(backupId: String, vaultId: String): ByteArray =
        "PersonalSpace/v1/backup/$backupId/$vaultId".toByteArray(StandardCharsets.UTF_8)

    private fun computeManifestHmac(unsignedManifest: BackupPackageManifest, macKey: ByteArray): String {
        val canonical = json.encodeToString(unsignedManifest.copy(manifestHmacBase64 = ""))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        val tag = mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
        return VaultCryptoEngine.toBase64(tag)
    }

    private fun publishToExternalStorage(source: File) {
        val destination = externalLocalBackupFile
        val temp = File(destination.parentFile ?: externalLocalBackupsDir, destination.name + ".tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (!temp.renameTo(destination)) {
                destination.delete()
                if (!temp.renameTo(destination)) {
                    throw IOException("Backup copy could not be published to Android/data.")
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun sha256FileHex(file: File): String =
        FileInputStream(file).buffered().use { sha256StreamHex(it) }

    private fun sha256StreamHex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buf).also { read = it } != -1) {
            md.update(buf, 0, read)
        }
        return ByteArrayOutputStream().let {
            md.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
