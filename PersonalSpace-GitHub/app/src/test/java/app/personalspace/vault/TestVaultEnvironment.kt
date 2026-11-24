package app.personalspace.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.crypto.Argon2KdfConfig
import app.personalspace.vault.core.crypto.KeystoreBiometricManager
import app.personalspace.vault.core.crypto.VaultInitializationResult
import app.personalspace.vault.core.crypto.VaultKeyEnvelopeBundle
import app.personalspace.vault.core.crypto.VaultKeyManager
import app.personalspace.vault.core.security.EncryptedTokenStore
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.backup.InlineImageStore
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.data.reminder.AlarmReminderScheduler
import app.personalspace.vault.data.repository.PersonalSpaceRepositoryImpl
import app.personalspace.vault.data.sync.BackgroundWorkCoordinator
import app.personalspace.vault.data.sync.DriveRemoteDataSource
import app.personalspace.vault.data.sync.GoogleDriveAuthManager
import app.personalspace.vault.data.sync.MultiDeviceSyncEngine
import app.personalspace.vault.data.sync.RemoteDriveFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import java.util.UUID

class MutableTestClock(
    var currentMillis: Long = 1_763_184_000_000L, // 2025-11-15T05:20:00Z
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
) : TimeProvider {
    override fun nowMillis(): Long = currentMillis
    override fun zoneId(): ZoneId = zone
    fun advanceBy(deltaMillis: Long) {
        currentMillis += deltaMillis
    }
}

/**
 * In-memory Google Drive backend implementing DriveRemoteDataSource so two simulated
 * Personal Space devices can exchange encrypted `.psbundle` & `.psenc` files offline in tests.
 */
class FakeSharedDriveCloud : DriveRemoteDataSource {
    private data class StoredFile(
        val id: String,
        val folderId: String,
        val name: String,
        val bytes: ByteArray,
        val createdTime: String,
        val createdSeq: Int = 0
    )

    private val files = LinkedHashMap<String, StoredFile>()
    private var seq = 1

    /** Simulated hidden Google Drive `appDataFolder` (never visible in My Drive). */
    var failNextAppDataUpload: Boolean = false
    var failNextAppDataDownload: Boolean = false
    var appDataUploadCount: Int = 0
    private var appDataSeq = 0

    fun appDataFileNames(): List<String> =
        files.values.filter { it.folderId == APP_DATA_FOLDER }.map { it.name }

    fun appDataSnapshot(name: String): ByteArray? =
        files.values.firstOrNull { it.folderId == APP_DATA_FOLDER && it.name == name }?.bytes

    fun corruptAppDataFile(name: String) {
        val key = files.values.firstOrNull { it.folderId == APP_DATA_FOLDER && it.name == name }?.id ?: return
        val existing = files.getValue(key)
        files[key] = existing.copy(bytes = existing.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 7).toByte() })
    }

    override suspend fun uploadOrReplaceInAppData(
        accessToken: String,
        remoteName: String,
        localCiphertextFile: File
    ): RemoteDriveFile {
        if (failNextAppDataUpload) {
            failNextAppDataUpload = false
            throw java.io.IOException("Simulated network failure during appDataFolder upload")
        }
        appDataUploadCount++
        val bytes = localCiphertextFile.readBytes()
        val existing = files.values.firstOrNull { it.folderId == APP_DATA_FOLDER && it.name == remoteName }
        val id = existing?.id ?: "appdata-file-${seq++}"
        files[id] = StoredFile(
            id = id,
            folderId = APP_DATA_FOLDER,
            name = remoteName,
            bytes = bytes,
            createdTime = "2026-11-16T0" + (appDataSeq % 9) + ":00:00Z",
            createdSeq = appDataSeq++
        )
        // Replacing keeps exactly one file with this name, mirroring Drive semantics
        files.values.filter { it.folderId == APP_DATA_FOLDER && it.name == remoteName && it.id != id }
            .forEach { files.remove(it.id) }
        return RemoteDriveFile(id = id, name = remoteName, sizeBytes = bytes.size.toLong(), createdTime = "2026-11-16T09:00:00Z")
    }

    override suspend fun listAppDataFiles(accessToken: String, namePrefix: String?): List<RemoteDriveFile> =
        files.values
            .filter { it.folderId == APP_DATA_FOLDER && (namePrefix.isNullOrBlank() || it.name.contains(namePrefix)) }
            .map {
                RemoteDriveFile(
                    id = it.id,
                    name = it.name,
                    sizeBytes = it.bytes.size.toLong(),
                    createdTime = it.createdTime
                )
            }

    override suspend fun ensureAppVaultFolder(accessToken: String): String = "drive-vault-folder-001"

    override suspend fun listFilesInFolder(
        accessToken: String,
        folderId: String,
        namePrefix: String?
    ): List<RemoteDriveFile> {
        return files.values
            .filter { it.folderId == folderId && (namePrefix.isNullOrBlank() || it.name.contains(namePrefix)) }
            .map {
                RemoteDriveFile(
                    id = it.id,
                    name = it.name,
                    sizeBytes = it.bytes.size.toLong(),
                    createdTime = it.createdTime
                )
            }
    }

    override suspend fun uploadEncryptedFileResumable(
        accessToken: String,
        folderId: String,
        remoteName: String,
        localCiphertextFile: File,
        mimeType: String
    ): RemoteDriveFile {
        val bytes = localCiphertextFile.readBytes()
        val existing = files.values.firstOrNull { it.folderId == folderId && it.name == remoteName }
        val id = existing?.id ?: "remote-file-${seq++}"
        val stored = StoredFile(
            id = id,
            folderId = folderId,
            name = remoteName,
            bytes = bytes,
            createdTime = "2026-11-15T10:00:00Z"
        )
        files[id] = stored
        return RemoteDriveFile(id = id, name = remoteName, sizeBytes = bytes.size.toLong(), createdTime = stored.createdTime)
    }

    override suspend fun downloadEncryptedFile(accessToken: String, fileId: String, destinationFile: File) {
        if (failNextAppDataDownload) {
            failNextAppDataDownload = false
            throw java.io.IOException("Simulated network failure during download")
        }
        val stored = files[fileId] ?: throw IllegalArgumentException("Missing remote file $fileId")
        destinationFile.parentFile?.mkdirs()
        destinationFile.writeBytes(stored.bytes)
    }

    override suspend fun uploadOrReplaceFile(
        accessToken: String,
        folderId: String,
        remoteName: String,
        localCiphertextFile: File
    ): RemoteDriveFile = uploadEncryptedFileResumable(accessToken, folderId, remoteName, localCiphertextFile)

    override suspend fun deleteFile(accessToken: String, fileId: String) {
        files.remove(fileId)
    }

    fun rawStoredBytesByName(fileNameSubstring: String): List<ByteArray> =
        files.values.filter { it.name.contains(fileNameSubstring) }.map { it.bytes }

    private companion object {
        const val APP_DATA_FOLDER = "appDataFolder"
    }
}

/**
 * Isolated per-device sandbox wrapping a dedicated filesystem root, DataStore, Room SQLite file,
 * VaultKeyManager, VaultSessionManager, Repository, BackupManager, ExportManager, and SyncEngine.
 */
class IsolatedVaultTestRig(
    baseContext: Context,
    rigName: String = "rig_${UUID.randomUUID()}",
    val clock: MutableTestClock = MutableTestClock(),
    val remoteDrive: DriveRemoteDataSource = FakeSharedDriveCloud()
) {
    val rootDir: File = File(baseContext.cacheDir, "test_rigs/$rigName").apply {
        deleteRecursively()
        mkdirs()
    }
    private val filesDirectory = File(rootDir, "files").apply { mkdirs() }
    private val cacheDirectory = File(rootDir, "cache").apply { mkdirs() }
    private val dbDirectory = File(rootDir, "databases").apply { mkdirs() }

    val context: Context = object : ContextWrapper(baseContext) {
        override fun getFilesDir(): File = filesDirectory
        override fun getCacheDir(): File = cacheDirectory
        override fun getDatabasePath(name: String): File = File(dbDirectory, name)
        override fun getApplicationContext(): Context = this
    }

    private val dsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = UserPreferencesDataSource(
        PreferenceDataStoreFactory.create(scope = dsScope) {
            File(filesDirectory, "prefs_$rigName.preferences_pb")
        }
    )

    val fastArgon2 = Argon2KdfConfig(memoryKiB = 4096, iterations = 2, parallelism = 1)
    val keystoreManager = KeystoreBiometricManager(context)
    val vaultKeyManager = VaultKeyManager(context, clock, keystoreManager).apply {
        defaultArgon2Config = fastArgon2
    }
    val sessionManager = VaultSessionManager(vaultKeyManager, clock)
    val dbHolder = VaultDatabaseHolder(context, sessionManager).apply {
        testOpenHelperFactoryOverride = { FrameworkSQLiteOpenHelperFactory() }
    }
    val attachmentStorage = EncryptedAttachmentStorage(context, sessionManager)
    val inlineImageStore = InlineImageStore(context, sessionManager)
    val reminderScheduler = AlarmReminderScheduler(context, clock)
    val backupManager = BackupPackageManager(
        context = context,
        vaultKeyManager = vaultKeyManager,
        sessionManager = sessionManager,
        dbHolder = dbHolder,
        attachmentStorage = attachmentStorage,
        inlineImageStore = inlineImageStore,
        timeProvider = clock
    )
    /** P3.1: the same coordinator Hilt would inject into Application/Activity. */
    val recoveryCoordinator = app.personalspace.vault.data.backup.RestoreRecoveryCoordinator(context, dbHolder)
    val tokenStore = EncryptedTokenStore(context, keystoreManager)
    val authManager = GoogleDriveAuthManager(context, tokenStore, clock)
    val syncEngine = MultiDeviceSyncEngine(
        context = context,
        sessionManager = sessionManager,
        dbHolder = dbHolder,
        attachmentStorage = attachmentStorage,
        authManager = authManager,
        remoteDataSource = remoteDrive,
        preferences = preferences,
        timeProvider = clock
    )
    val workCoordinator = BackgroundWorkCoordinator(context, clock)
    val repository = PersonalSpaceRepositoryImpl(
        sessionManager = sessionManager,
        dbHolder = dbHolder,
        attachmentStorage = attachmentStorage,
        reminderScheduler = reminderScheduler,
        backupManager = backupManager,
        authManager = authManager,
        remoteDataSource = remoteDrive,
        syncEngine = syncEngine,
        workCoordinator = workCoordinator,
        preferences = preferences,
        timeProvider = clock,
        inlineImageStore = inlineImageStore
    )

    fun initializeAndUnlockVault(password: String = "PersonalSpace#2026"): VaultInitializationResult {
        val init = vaultKeyManager.createNewVault(password.toCharArray(), fastArgon2)
        // P3.1: unlockSession now refuses while the process-wide recovery gate is blocked. A fresh
        // rig always starts from a clean gate; this keeps existing tests honest rather than silently
        // relying on a bypass.
        app.personalspace.vault.core.security.RecoveryGate.markClearIfNotBlocked()
        sessionManager.unlockSession(init.subKeys)
        return init
    }

    /** P3.1: runs startup recovery and records the verdict in the process-wide gate. */
    fun restoreRecoveryForTest(): app.personalspace.vault.data.backup.RestoreRecovery.Outcome =
        app.personalspace.vault.data.backup.RestoreRecovery.onAppStart(
            filesDir = context.filesDir,
            dbDir = dbHolder.databaseFile().parentFile!!,
            attachmentsDir = attachmentStorage.encryptedDir,
            imagesDir = inlineImageStore.imagesDir,
            closeDatabase = { dbHolder.closeDatabase() }
        ).also { outcome ->
            val gate = app.personalspace.vault.core.security.RecoveryGate
            when (outcome) {
                is app.personalspace.vault.data.backup.RestoreRecovery.Outcome.Blocked ->
                    gate.markBlocked(
                        reason = outcome.reason,
                        preservedFiles = outcome.preservedFiles,
                        retryableDirName = outcome.retryableDirName
                    )
                else -> gate.markRecovered()
            }
        }

    /** P3.1: export recovery data through the same code the blocking screen uses. */
    fun exportRecoveryForTest(): List<java.io.File> {
        val dest = java.io.File(context.cacheDir, "p31_export")
        val copies = app.personalspace.vault.data.backup.RestoreGuard.unresolvedRollbackDirs(context.filesDir)
        val out = mutableListOf<java.io.File>()
        copies.forEach { copyDir ->
            val target = java.io.File(dest, copyDir.name).apply { mkdirs() }
            copyDir.walkTopDown().filter { it.isFile }.forEach { source ->
                val f = java.io.File(target, source.relativeTo(copyDir).path)
                f.parentFile?.mkdirs()
                source.copyTo(f, overwrite = true)
                out += f
            }
        }
        return out
    }

    fun joinExistingVaultEnvelope(bundle: VaultKeyEnvelopeBundle, password: String) {
        vaultKeyManager.saveEnvelopeBundle(bundle)
        val subKeys = vaultKeyManager.unlockWithMasterPassword(password.toCharArray(), bundle)
        sessionManager.unlockSession(subKeys)
    }

    fun close() {
        dbHolder.closeDatabase()
        sessionManager.lockVault()
        dsScope.cancel()
        rootDir.deleteRecursively()
    }
}
