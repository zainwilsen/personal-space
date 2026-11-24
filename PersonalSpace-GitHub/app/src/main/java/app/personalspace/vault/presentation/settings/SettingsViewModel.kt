package app.personalspace.vault.presentation.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.personalspace.vault.core.crypto.BiometricHardwareStatus
import app.personalspace.vault.core.crypto.KeystoreBiometricManager
import app.personalspace.vault.core.crypto.VaultKeyManager
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.data.attachment.OrphanQuarantineManager
import app.personalspace.vault.data.backup.BackupRestoreResult
import app.personalspace.vault.data.backup.BackupVerificationException
import app.personalspace.vault.data.backup.MissingBackupAttachmentException
import app.personalspace.vault.data.backup.MissingBackupImageException
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.data.reminder.AlarmReminderScheduler
import app.personalspace.vault.data.sync.BackgroundWorkCoordinator
import app.personalspace.vault.data.sync.DriveAuthOutcome
import app.personalspace.vault.data.sync.GoogleDriveAuthManager
import app.personalspace.vault.domain.model.DriveBackupFile
import app.personalspace.vault.domain.model.ConflictResolutionChoice
import app.personalspace.vault.domain.model.RecordItem
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.model.SyncConflictItem
import app.personalspace.vault.domain.model.SyncHealthState
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.domain.model.VaultStatisticsOverview
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class LocalBackupInfo(
    val exists: Boolean = false,
    val sizeBytes: Long = 0L,
    val modifiedMillis: Long = 0L,
    val externalCopied: Boolean = false,
    val externalPath: String = ""
)

/** WhatsApp-style backup status surfaced to the Backup & Sync screen. */
enum class BackupStatus(val label: String) {
    NOT_CONNECTED("Not Connected"),
    CONNECTED_NO_BACKUP("Connected · No backup yet"),
    BACKING_UP("Backing Up…"),
    BACKUP_COMPLETED("Backup Completed"),
    BACKUP_FAILED("Backup Failed"),
    RESTORE_IN_PROGRESS("Restore in Progress…"),
    RESTORE_COMPLETED("Restore Completed"),
    RESTORE_FAILED("Restore Failed"),
    UP_TO_DATE("Already Backed Up")
}

data class CloudBackupState(
    val status: BackupStatus = BackupStatus.NOT_CONNECTED,
    val accountEmail: String? = null,
    val lastBackupMillis: Long = 0L,
    val lastBackupSizeBytes: Long = 0L,
    val detail: String? = null
) {
    val connected: Boolean
        get() = status != BackupStatus.NOT_CONNECTED
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PersonalSpaceRepository,
    private val preferences: UserPreferencesDataSource,
    private val vaultKeyManager: VaultKeyManager,
    private val keystoreBiometricManager: KeystoreBiometricManager,
    private val sessionManager: VaultSessionManager,
    private val dbHolder: VaultDatabaseHolder,
    private val attachmentStorage: EncryptedAttachmentStorage,
    private val backupPackageManager: BackupPackageManager,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val workCoordinator: BackgroundWorkCoordinator,
    val reminderScheduler: AlarmReminderScheduler
) : ViewModel() {

    val settings: StateFlow<UserSettings> = repository.observeUserSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val syncState: StateFlow<SyncHealthState> = repository.observeSyncHealthState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncHealthState.NOT_CONNECTED)

    val conflicts: StateFlow<List<SyncConflictItem>> = repository.observeUnresolvedConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<VaultStatisticsOverview> = repository.observeVaultStatistics()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            VaultStatisticsOverview(0, 0, 0, 0L, 0L, 0L, 0, 0, 0, emptyList(), SyncHealthState.NOT_CONNECTED, null, null)
        )

    val allRecords: StateFlow<List<RecordItem>> = repository.observeAllRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _cloudBackup = MutableStateFlow(CloudBackupState())
    val cloudBackup: StateFlow<CloudBackupState> = _cloudBackup.asStateFlow()

    private val _localBackupInfo = MutableStateFlow(LocalBackupInfo())
    val localBackupInfo: StateFlow<LocalBackupInfo> = _localBackupInfo.asStateFlow()

    private val _driveBackups = MutableStateFlow<List<DriveBackupFile>?>(null)
    val driveBackups: StateFlow<List<DriveBackupFile>?> = _driveBackups.asStateFlow()

    private val _driveConnected = MutableStateFlow(driveAuthManager.isConnected())
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    private val _isLoadingDriveBackups = MutableStateFlow(false)
    val isLoadingDriveBackups: StateFlow<Boolean> = _isLoadingDriveBackups.asStateFlow()

    private val _rotatedRecoveryWords = MutableStateFlow<List<String>>(emptyList())
    val rotatedRecoveryWords: StateFlow<List<String>> = _rotatedRecoveryWords.asStateFlow()

    // ==================== ORPHAN QUARANTINE (item 3) ====================

    /**
     * UI state for the orphan-quarantine screen.
     *
     * Every field is a measured fact from [OrphanQuarantineManager], never a guess: the scan result,
     * the manifest, and the last operation's outcome. The screen never deletes anything on its own —
     * [emptyQuarantine] is reachable only from an explicit user tap behind a confirmation.
     */
    data class QuarantineUiState(
        val loading: Boolean = false,
        val scanned: OrphanQuarantineManager.ScanResult? = null,
        val manifest: OrphanQuarantineManager.QuarantineManifest? = null,
        val lastMessage: String? = null,
        val lastError: String? = null,
        val exportedTo: String? = null
    )

    private val _quarantine = MutableStateFlow(QuarantineUiState())
    val quarantine: StateFlow<QuarantineUiState> = _quarantine.asStateFlow()

    private val quarantineManager: OrphanQuarantineManager by lazy {
        OrphanQuarantineManager(
            db = dbHolder.getDatabase(),
            filesDir = appContext.filesDir
        )
    }

    /** Read-only scan of the vault for files nothing references. Moves nothing. */
    fun scanForOrphans() {
        viewModelScope.launch {
            _quarantine.value = _quarantine.value.copy(loading = true, lastError = null, lastMessage = null)
            runCatching {
                quarantineManager.assertSafeToRun("settings")
                val scan = quarantineManager.scan()
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    scanned = scan,
                    manifest = quarantineManager.inspect(),
                    lastMessage = "Scanned ${scan.attachmentFilesSeen} attachment file(s) and " +
                        "${scan.imageFilesSeen} image file(s); ${scan.candidates.size} unreferenced, " +
                        "${scan.recentFilesSkipped.size} too recent to judge."
                )
            }.onFailure { failure ->
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    lastError = failure.message ?: "The scan could not run."
                )
            }
        }
    }

    /** Moves the scanned candidates into quarantine (never deletes them). */
    fun quarantineScannedOrphans() {
        val candidates = _quarantine.value.scanned?.candidates ?: emptyList()
        if (candidates.isEmpty()) return
        viewModelScope.launch {
            _quarantine.value = _quarantine.value.copy(loading = true, lastError = null, lastMessage = null)
            runCatching {
                quarantineManager.assertSafeToRun("settings")
                val manifest = quarantineManager.quarantine(candidates)
                val scanAfter = quarantineManager.scan()
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    scanned = scanAfter,
                    manifest = manifest,
                    lastMessage = "${manifest.entries.size} file(s) held in quarantine. " +
                        "Nothing was deleted — they can be restored at any time."
                )
            }.onFailure { failure ->
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    lastError = failure.message ?: "The files could not be quarantined."
                )
            }
        }
    }

    /** Copies the quarantine folder out so the user can keep it. Read-only. */
    fun exportQuarantine(targetDirectory: File) {
        viewModelScope.launch {
            _quarantine.value = _quarantine.value.copy(loading = true, lastError = null, lastMessage = null)
            runCatching {
                val destination = quarantineManager.exportTo(targetDirectory)
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    exportedTo = destination.absolutePath,
                    lastMessage = "Exported to ${destination.absolutePath}"
                )
            }.onFailure { failure ->
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    lastError = failure.message ?: "The export failed."
                )
            }
        }
    }

    /** Puts one quarantined file back into the vault, verifying its hash first. */
    fun restoreQuarantinedFile(quarantineFileName: String) {
        viewModelScope.launch {
            _quarantine.value = _quarantine.value.copy(loading = true, lastError = null, lastMessage = null)
            runCatching {
                val ok = quarantineManager.restoreEntry(quarantineFileName)
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    manifest = quarantineManager.inspect(),
                    lastMessage = if (ok) "Restored $quarantineFileName to its original location."
                    else "That file could not be restored (its hash did not match the manifest).",
                    lastError = if (ok) null else "The file was not restored. Nothing was changed."
                )
            }.onFailure { failure ->
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    lastError = failure.message ?: "The file could not be restored."
                )
            }
        }
    }

    /** Permanently deletes everything in quarantine. Only ever called from an explicit user action. */
    fun emptyQuarantine() {
        viewModelScope.launch {
            _quarantine.value = _quarantine.value.copy(loading = true, lastError = null, lastMessage = null)
            runCatching {
                val removed = quarantineManager.emptyQuarantine()
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    manifest = quarantineManager.inspect(),
                    lastMessage = "$removed file(s) permanently deleted from quarantine."
                )
            }.onFailure { failure ->
                _quarantine.value = _quarantine.value.copy(
                    loading = false,
                    lastError = failure.message ?: "Quarantine could not be emptied."
                )
            }
        }
    }

    /**
     * Where an export copy is written. Uses the app's external files directory so writing needs no
     * storage permission and the user can reach the folder from a file manager.
     */
    fun quarantineExportRoot(): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "quarantine_exports")

    fun dismissQuarantineFeedback() {
        _quarantine.value = _quarantine.value.copy(lastMessage = null, lastError = null)
    }

    fun refreshLocalBackupInfo() {
        val internalFile = backupPackageManager.singleLocalBackupFile
        val externalFile = backupPackageManager.externalLocalBackupFile
        val source = when {
            internalFile.exists() -> internalFile
            externalFile.exists() -> externalFile
            else -> null
        }
        _localBackupInfo.value = if (source == null) {
            LocalBackupInfo()
        } else {
            LocalBackupInfo(
                exists = true,
                sizeBytes = source.length(),
                modifiedMillis = source.lastModified(),
                externalCopied = externalFile.exists(),
                externalPath = externalFile.absolutePath
            )
        }
    }

    fun restoreFromLatestLocalBackup(onRestored: () -> Unit) {
        clearFeedback()
        val file = backupPackageManager.singleLocalBackupFile
            .takeIf { it.exists() }
            ?: backupPackageManager.externalLocalBackupFile.takeIf { it.exists() }
        if (file == null) {
            _error.value = "Phone me koi backup file nahi mili. 'Backup file chuno' se file select karo."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _cloudBackup.value = _cloudBackup.value.copy(status = BackupStatus.RESTORE_IN_PROGRESS)
            runCatching {
                withContext(Dispatchers.Default) {
                    backupPackageManager.restoreFromBackupFile(backupFile = file)
                }
            }
                .onSuccess {
                    _isBusy.value = false
                    _cloudBackup.value = _cloudBackup.value.copy(status = BackupStatus.RESTORE_COMPLETED)
                    refreshLocalBackupInfo()
                    _message.value = restoreSummary(it)
                    onRestored()
                }
                .onFailure {
                    _isBusy.value = false
                    _error.value = friendlyRestoreError(it)
                }
        }
    }

    fun restoreFromLocalUri(uri: Uri, onRestored: () -> Unit) {
        clearFeedback()
        viewModelScope.launch {
            _isBusy.value = true
            val temp = File(appContext.cacheDir, "restore_" + IdGenerator.newUuid() + ".psbackup")
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output, 64 * 1024) }
                    } ?: error("Chuni hui file khul nahi payi.")
                }
                val result = withContext(Dispatchers.Default) {
                    backupPackageManager.restoreFromBackupFile(
                        backupFile = temp,
                    )
                }
                _isBusy.value = false
                refreshLocalBackupInfo()
                _message.value = restoreSummary(result)
                onRestored()
            } catch (e: Exception) {
                _isBusy.value = false
                _error.value = friendlyRestoreError(e)
            } finally {
                temp.delete()
            }
        }
    }

    fun restoreFromDriveBackup(fileId: String, onRestored: () -> Unit) {
        clearFeedback()
        viewModelScope.launch {
            _isBusy.value = true
            _cloudBackup.value = _cloudBackup.value.copy(status = BackupStatus.RESTORE_IN_PROGRESS)
            runCatching {
                withContext(Dispatchers.Default) { repository.restoreFromDriveBackup(fileId = fileId) }
            }
                .onSuccess {
                    _isBusy.value = false
                    _cloudBackup.value = _cloudBackup.value.copy(status = BackupStatus.RESTORE_COMPLETED)
                    refreshLocalBackupInfo()
                    _message.value = restoreSummary(it)
                    onRestored()
                }
                .onFailure {
                    _isBusy.value = false
                    _cloudBackup.value = _cloudBackup.value.copy(
                        status = BackupStatus.RESTORE_FAILED,
                        detail = it.message
                    )
                    _error.value = friendlyRestoreError(it)
                }
        }
    }

    private fun restoreSummary(result: BackupRestoreResult): String =
        "Restore complete · ${result.notes} notes · ${result.folders} folders · " +
            "${result.attachments} attachments · ${result.inlineImages} images · " +
            "${result.drafts} drafts · " + if (result.verified) "verified" else "NOT verified"

    private fun friendlyRestoreError(e: Throwable): String = when (e) {
        is MissingBackupAttachmentException -> e.message ?: "Backup incomplete: attachment missing."
        is MissingBackupImageException -> e.message ?: "Backup incomplete: note image missing."
        is BackupVerificationException -> e.message ?: "Restore verification failed."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Restore fail ho gaya."
    }

    fun saveBackupCopyToUri(uri: Uri) {
        clearFeedback()
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = backupPackageManager.singleLocalBackupFile
                    if (!file.exists()) repository.createManualLocalBackup()
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        backupPackageManager.saveBackupCopy(out)
                    } ?: error("File save nahi ho payi.")
                }
            }
                .onSuccess {
                    _isBusy.value = false
                    _message.value = "Backup exported — jahan aapne chuna wahan save ho gaya."
                }
                .onFailure {
                    _isBusy.value = false
                    _error.value = it.message ?: "Export fail ho gaya."
                }
        }
    }

    fun loadDriveBackups() {
        val connected = driveAuthManager.isConnected()
        _driveConnected.value = connected
        if (!connected) {
            _isLoadingDriveBackups.value = false
            _driveBackups.value = emptyList()
            return
        }
        if (_isLoadingDriveBackups.value) return

        viewModelScope.launch {
            _isLoadingDriveBackups.value = true
            runCatching { repository.listDriveBackups() }
                .onSuccess {
                    _driveBackups.value = it
                    markConnected(it.firstOrNull())
                }
                .onFailure {
                    _driveBackups.value = emptyList()
                    _error.value = it.message ?: "Drive backups load nahi ho paye."
                }
            _isLoadingDriveBackups.value = false
        }
    }

    fun clearFeedback() {
        _message.value = null
        _error.value = null
    }

    fun clearRotatedRecoveryWords() {
        _rotatedRecoveryWords.value = emptyList()
    }

    fun queryBiometricHardware(): BiometricHardwareStatus = keystoreBiometricManager.queryBiometricStatus()
    fun isBiometricEnabled(): Boolean = vaultKeyManager.isBiometricUnlockEnabled()
    fun isDriveConnected(): Boolean = driveAuthManager.isConnected()

    fun refreshCloudState() {
        val session = driveAuthManager.getCurrentSession()
        val connected = session != null && session.accessToken.isNotBlank()
        _driveConnected.value = connected
        if (!connected) {
            _isLoadingDriveBackups.value = false
            _driveBackups.value = emptyList()
            _cloudBackup.value = CloudBackupState(status = BackupStatus.NOT_CONNECTED)
            return
        }
        loadDriveBackups()
    }

    private fun markConnected(latest: DriveBackupFile?) {
        val session = driveAuthManager.getCurrentSession()
        _cloudBackup.value = CloudBackupState(
            status = if (latest == null) BackupStatus.CONNECTED_NO_BACKUP else BackupStatus.BACKUP_COMPLETED,
            accountEmail = session?.accountEmail,
            lastBackupMillis = latest?.createdMillis ?: 0L,
            lastBackupSizeBytes = latest?.sizeBytes ?: 0L
        )
    }
    fun currentVaultId(): String = vaultKeyManager.readEnvelopeBundle()?.vaultId ?: "—"

    fun lockVaultNow() {
        sessionManager.lockVault()
    }

    fun setDefaultSort(sort: SortOption) {
        viewModelScope.launch { preferences.updateDefaultSort(sort) }
    }

    fun setAutoSaveNotes(enabled: Boolean) {
        viewModelScope.launch { preferences.updateAutoSaveNotes(enabled) }
    }

    fun setReducedMotion(enabled: Boolean) {
        viewModelScope.launch { preferences.updateReducedMotion(enabled) }
    }

    fun setDefaultSnoozeMinutes(minutes: Int) {
        viewModelScope.launch { preferences.updateDefaultSnoozeMinutes(minutes) }
    }

    fun setAutoLockSeconds(seconds: Int) {
        viewModelScope.launch { preferences.updateAutoLockSeconds(seconds) }
    }

    fun setLockOnScreenOff(enabled: Boolean) {
        viewModelScope.launch { preferences.updateLockOnScreenOff(enabled) }
    }

    fun setSecureTaskSwitcher(enabled: Boolean) {
        viewModelScope.launch { preferences.updateSecureTaskSwitcher(enabled) }
    }

    fun setAutoIncrementalSync(enabled: Boolean) {
        viewModelScope.launch { preferences.updateAutoIncrementalSync(enabled) }
    }

    fun setDailyBackupSchedule(enabled: Boolean, hour24: Int, minute: Int) {
        viewModelScope.launch {
            preferences.updateDailyBackupSchedule(enabled, hour24, minute)
            workCoordinator.configureDailyBackupSchedule(enabled, hour24, minute)
            _message.value = if (enabled) {
                "Daily encrypted backup scheduled near %02d:%02d (best-effort Android background window).".format(hour24, minute)
            } else {
                "Daily scheduled backup disabled."
            }
        }
    }

    fun changeMasterPassword(currentPwd: String, newPwd: String, confirmPwd: String) {
        if (newPwd.length < 8) {
            _error.value = "New Master Password must be at least 8 characters."
            return
        }
        if (newPwd != confirmPwd) {
            _error.value = "New passwords do not match."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            clearFeedback()
            runCatching {
                withContext(Dispatchers.Default) {
                    val curChars = currentPwd.toCharArray()
                    val newChars = newPwd.toCharArray()
                    try {
                        vaultKeyManager.changeMasterPassword(curChars, newChars)
                    } finally {
                        curChars.fill('\u0000')
                        newChars.fill('\u0000')
                    }
                }
            }.onSuccess {
                _isBusy.value = false
                _message.value = "Master Password updated. Vault Data Key re-wrapped via Argon2id."
            }.onFailure { err ->
                _isBusy.value = false
                _error.value = err.message ?: "Failed to update Master Password."
            }
        }
    }

    fun rotateRecoveryKey(currentPwd: String) {
        if (currentPwd.isBlank()) {
            _error.value = "Enter your current Master Password to rotate the Recovery Key."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            clearFeedback()
            runCatching {
                withContext(Dispatchers.Default) {
                    val chars = currentPwd.toCharArray()
                    try {
                        vaultKeyManager.rotateRecoveryKey(chars)
                    } finally {
                        chars.fill('\u0000')
                    }
                }
            }.onSuccess { words ->
                _isBusy.value = false
                _rotatedRecoveryWords.value = words
                _message.value = "New 24-word Recovery Key generated. Save it carefully."
            }.onFailure { err ->
                _isBusy.value = false
                _error.value = err.message ?: "Recovery Key rotation failed."
            }
        }
    }

    fun enableBiometrics(activity: FragmentActivity, onStateChanged: () -> Unit) {
        val activeKeys = sessionManager.currentSubKeysOrNull()
        if (activeKeys == null) {
            _error.value = "Unlock vault first."
            return
        }
        runCatching {
            val cipher = keystoreBiometricManager.createEnrollmentCipher()
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        result.cryptoObject?.cipher?.let { authedCipher ->
                            vaultKeyManager.completeBiometricEnrollment(authedCipher, activeKeys)
                            _message.value = "Biometric unlocking enabled on this device."
                            onStateChanged()
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        _error.value = errString.toString()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable Biometric Unlock")
                .setSubtitle("Authorize Android Keystore protection for this device")
                .setNegativeButtonText("Cancel")
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }.onFailure {
            _error.value = it.message ?: "Biometric enrollment failed."
        }
    }

    fun disableBiometrics(onStateChanged: () -> Unit) {
        vaultKeyManager.clearBiometricUnlock()
        _message.value = "Device biometric unlock removed."
        onStateChanged()
    }

    fun connectDrive(onConsentRequired: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            _isBusy.value = true
            clearFeedback()
            when (val res = driveAuthManager.requestDriveAuthorization()) {
                is DriveAuthOutcome.Authorized -> {
                    _driveConnected.value = true
                    _driveBackups.value = null
                    _isBusy.value = false
                    _message.value = "Google account connected."
                    refreshCloudState()
                }
                is DriveAuthOutcome.ConsentResolutionRequired -> {
                    _isBusy.value = false
                    onConsentRequired(res.pendingIntent)
                }
                is DriveAuthOutcome.ConfigurationOrError -> {
                    _isBusy.value = false
                    _error.value = res.message
                }
            }
        }
    }

    fun handleDriveConsentResult(data: Intent?) {
        when (val res = driveAuthManager.handleConsentResult(data)) {
            is DriveAuthOutcome.Authorized -> {
                _driveConnected.value = true
                _driveBackups.value = null
                _message.value = "Google account connected."
                refreshCloudState()
            }
            is DriveAuthOutcome.ConfigurationOrError -> _error.value = res.message
            else -> Unit
        }
    }

    fun disconnectDrive() {
        driveAuthManager.disconnect()
        _driveConnected.value = false
        _cloudBackup.value = CloudBackupState(status = BackupStatus.NOT_CONNECTED)
        _driveBackups.value = emptyList()
        _message.value = "Google account disconnected. Local vault remains fully operational."
    }

    fun createLocalBackupNow() {
        viewModelScope.launch {
            _isBusy.value = true
            clearFeedback()
            val fingerprint = runCatching { backupPackageManager.computeVaultFingerprint() }.getOrNull()
            if (fingerprint != null && fingerprint == backupPackageManager.lastBackupFingerprint()) {
                _isBusy.value = false
                refreshLocalBackupInfo()
                _message.value = "Already backup — koi naya change nahi hai."
                return@launch
            }
            runCatching { repository.createManualLocalBackup() }
                .onSuccess {
                    _isBusy.value = false
                    refreshLocalBackupInfo()
                    _message.value = "Backup complete — ${it.recordCount} notes, ${it.attachmentCount} attachments."
                }
                .onFailure {
                    _isBusy.value = false
                    _error.value = it.message ?: "Local backup failed."
                }
        }
    }

    fun createDriveBackupNow() {
        if (!driveAuthManager.isConnected()) {
            _error.value = "Google account connected nahi hai. Pehle Connect karo."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            clearFeedback()
            _cloudBackup.value = _cloudBackup.value.copy(status = BackupStatus.BACKING_UP)

            val fingerprint = runCatching { backupPackageManager.computeVaultFingerprint() }.getOrNull()
            if (fingerprint != null && fingerprint == backupPackageManager.lastBackupFingerprint()) {
                _isBusy.value = false
                _cloudBackup.value = _cloudBackup.value.copy(status = BackupStatus.UP_TO_DATE)
                _message.value = "Already backed up — koi naya change nahi hai."
                loadDriveBackups()
                return@launch
            }

            runCatching { repository.uploadManualBackupToDrive() }
                .onSuccess {
                    _isBusy.value = false
                    _cloudBackup.value = _cloudBackup.value.copy(
                        status = BackupStatus.BACKUP_COMPLETED,
                        lastBackupMillis = it.createdAtMillis,
                        lastBackupSizeBytes = it.sizeBytes
                    )
                    loadDriveBackups()
                    _message.value = "Backup Completed — ${it.recordCount} notes, ${it.attachmentCount} attachments."
                }
                .onFailure { failure ->
                    _isBusy.value = false
                    _cloudBackup.value = _cloudBackup.value.copy(
                        status = BackupStatus.BACKUP_FAILED,
                        detail = failure.message
                    )
                    _error.value = failure.message ?: "Backup failed."
                }
        }
    }

    fun runSyncCycleNow() {
        viewModelScope.launch {
            _isBusy.value = true
            clearFeedback()
            val report = repository.runSyncNow()
            _isBusy.value = false
            if (report.errorMessage != null) {
                _error.value = report.errorMessage
            } else {
                _message.value = "Sync state: ${report.finalState.displayLabel} (${report.uploadedOperations} uploaded, ${report.downloadedBundles} remote bundles)."
            }
        }
    }

    fun verifyExistingLocalBackup(path: String) {
        viewModelScope.launch {
            val file = File(path)
            if (!file.exists()) {
                _error.value = "Backup file is located on Google Drive or was moved from local storage."
                return@launch
            }
            runCatching { repository.verifyBackupFile(file) }
                .onSuccess { _message.value = "Package SHA-256 & manifest structure verified OK." }
                .onFailure { _error.value = it.message ?: "Verification failed." }
        }
    }

    fun resolveSyncConflict(
        conflictId: String,
        choice: ConflictResolutionChoice,
        mergedTitle: String? = null,
        mergedDescription: String? = null
    ) {
        viewModelScope.launch {
            runCatching {
                repository.resolveConflict(conflictId, choice, mergedTitle, mergedDescription)
            }.onSuccess {
                _message.value = "Conflict resolved safely."
            }.onFailure {
                _error.value = it.message ?: "Failed to resolve conflict."
            }
        }
    }

    fun runDatabaseIntegrityCheck() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { dbHolder.verifyDatabaseIntegrity() }
            if (ok) {
                _message.value = "SQLCipher PRAGMA integrity_check passed (OK)."
            } else {
                _error.value = "Database integrity check reported an issue."
            }
        }
    }

    fun purgeTemporaryViewerCache() {
        attachmentStorage.purgeAllEphemeralFiles()
        _message.value = "Ephemeral viewer cache cleared."
    }

}
