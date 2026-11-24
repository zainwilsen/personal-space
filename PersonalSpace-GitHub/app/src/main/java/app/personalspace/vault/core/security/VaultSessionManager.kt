package app.personalspace.vault.core.security

import android.content.Context
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.crypto.DerivedVaultSubKeys
import app.personalspace.vault.core.crypto.KeystoreBiometricManager
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.core.crypto.VaultKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

enum class VaultLockState {
    UNINITIALIZED,
    LOCKED,
    UNLOCKED
}

/**
 * Manages the in-memory lifecycle of unlocked cryptographic subkeys, background auto-lock timing,
 * screen-off lock enforcement, and cold-start launch animation tracking.
 */
@Singleton
class VaultSessionManager @Inject constructor(
    private val vaultKeyManager: VaultKeyManager,
    private val timeProvider: TimeProvider
) {
    private val lock = Any()
    private var activeSubKeys: DerivedVaultSubKeys? = null
    private var backgroundedAtMillis: Long? = null
    private var onVaultLockedCallback: (() -> Unit)? = null

    private val _lockState = MutableStateFlow(
        if (vaultKeyManager.isVaultInitialized()) VaultLockState.LOCKED else VaultLockState.UNINITIALIZED
    )
    val lockState: StateFlow<VaultLockState> = _lockState.asStateFlow()

    /**
     * Tracks whether cold-start launch choreography has already played in this process
     * so returning from brief background transitions never replays the splash sequence.
     */
    var hasPlayedColdLaunchAnimation: Boolean = false

    fun setOnVaultLockedListener(listener: () -> Unit) {
        onVaultLockedCallback = listener
    }

    fun refreshInitializationState() {
        synchronized(lock) {
            if (!vaultKeyManager.isVaultInitialized()) {
                activeSubKeys?.wipe()
                activeSubKeys = null
                _lockState.value = VaultLockState.UNINITIALIZED
            } else if (activeSubKeys == null) {
                _lockState.value = VaultLockState.LOCKED
            }
        }
    }

    fun unlockSession(subKeys: DerivedVaultSubKeys) {
        // P3.1: recovery gate. Installing keys while recovery is unresolved would let the caller
        // read and write a vault that may still be half-restored.
        if (RecoveryGate.isBlocked()) {
            subKeys.wipe()
            throw RecoveryGate.RecoveryBlockedException.fromGate()
        }
        synchronized(lock) {
            activeSubKeys?.wipe()
            activeSubKeys = subKeys
            backgroundedAtMillis = null
            _lockState.value = VaultLockState.UNLOCKED
        }
    }

    /**
     * Ensures the vault session is unlocked automatically for frictionless direct-to-Dashboard entry.
     */
    fun ensureAutoUnlocked() {
        // P3.1: recovery gate. This is called from PersonalSpaceApp, MainActivity and
        // PersonalSpaceNavHost, so it is the highest-traffic unlock path and must refuse first.
        if (RecoveryGate.isBlocked()) {
            throw RecoveryGate.RecoveryBlockedException.fromGate()
        }
        synchronized(lock) {
            if (activeSubKeys != null && _lockState.value == VaultLockState.UNLOCKED) {
                return
            }
            val subKeys = vaultKeyManager.ensureAutoUnlockedVault()
            activeSubKeys = subKeys
            backgroundedAtMillis = null
            _lockState.value = VaultLockState.UNLOCKED
        }
    }

    /**
     * Non-throwing variant for startup wiring (`Application`, `Activity`, navigation) where an
     * exception would crash the process before any screen can explain the situation.
     * Returns true only if the session is unlocked and the gate allows it.
     */
    fun tryEnsureAutoUnlocked(): Boolean =
        runCatching { ensureAutoUnlocked() }.isSuccess && !RecoveryGate.isBlocked()

    fun lockVault() {
        var didLock = false
        synchronized(lock) {
            if (activeSubKeys != null) {
                activeSubKeys?.wipe()
                activeSubKeys = null
                didLock = true
            }
            _lockState.value = if (vaultKeyManager.isVaultInitialized()) {
                VaultLockState.LOCKED
            } else {
                VaultLockState.UNINITIALIZED
            }
        }
        if (didLock) {
            onVaultLockedCallback?.invoke()
        }
    }

    fun requireSubKeys(): DerivedVaultSubKeys {
        // P3.1: recovery gate. Checked BEFORE the cached-key fast path, so even an already-unlocked
        // session stops handing out keys the moment a block is recorded.
        if (RecoveryGate.isBlocked()) {
            throw RecoveryGate.RecoveryBlockedException.fromGate()
        }
        synchronized(lock) {
            activeSubKeys?.let { return it }
            if (vaultKeyManager.hasAutoOpenVault()) {
                val autoKeys = vaultKeyManager.ensureAutoUnlockedVault()
                activeSubKeys = autoKeys
                _lockState.value = VaultLockState.UNLOCKED
                return autoKeys
            }
            throw IllegalStateException("Vault is currently locked.")
        }
    }

    fun currentSubKeysOrNull(): DerivedVaultSubKeys? = synchronized(lock) { activeSubKeys }

    fun onAppBackgrounded() {
        synchronized(lock) {
            if (_lockState.value == VaultLockState.UNLOCKED) {
                backgroundedAtMillis = timeProvider.nowMillis()
            }
        }
    }

    fun onAppForegrounded(autoLockTimeoutSeconds: Int) {
        val shouldLock = synchronized(lock) {
            if (_lockState.value != VaultLockState.UNLOCKED) return@synchronized false
            val bgTime = backgroundedAtMillis ?: return@synchronized false
            backgroundedAtMillis = null
            if (autoLockTimeoutSeconds <= 0) {
                true
            } else {
                val elapsedMillis = timeProvider.nowMillis() - bgTime
                elapsedMillis >= autoLockTimeoutSeconds * 1000L
            }
        }
        if (shouldLock) {
            lockVault()
        }
    }

    fun onScreenTurnedOff(lockOnScreenOff: Boolean) {
        if (lockOnScreenOff) {
            lockVault()
        }
    }
}

@Serializable
data class StoredDriveAuthSession(
    val accessToken: String,
    val accountEmail: String? = null,
    val driveFolderId: String? = null,
    val obtainedAtMillis: Long,
    val expiresAtMillis: Long
)

/**
 * Encrypts Google Drive OAuth access tokens at rest using a device-bound Android Keystore key.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreBiometricManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenFile: File
        get() = File(context.filesDir, "vault_keystore/drive_auth.enc")

    // Ephemeral fallback key used strictly in non-Android JVM unit test environments
    private val jvmFallbackKey: ByteArray by lazy { VaultCryptoEngine.randomBytes(32) }

    fun saveSession(session: StoredDriveAuthSession) {
        tokenFile.parentFile?.mkdirs()
        val plain = json.encodeToString(session).toByteArray(StandardCharsets.UTF_8)
        val ksKey = keystoreManager.getOrCreateDeviceStorageKey()
        if (ksKey != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, ksKey)
            val ct = cipher.doFinal(plain)
            val payload = "${VaultCryptoEngine.toBase64(cipher.iv)}:${VaultCryptoEngine.toBase64(ct)}"
            tokenFile.writeText(payload, Charsets.UTF_8)
        } else {
            val (iv, ct) = VaultCryptoEngine.encryptAesGcm(jvmFallbackKey, plain)
            val payload = "${VaultCryptoEngine.toBase64(iv)}:${VaultCryptoEngine.toBase64(ct)}"
            tokenFile.writeText(payload, Charsets.UTF_8)
        }
        plain.fill(0)
    }

    fun readSession(): StoredDriveAuthSession? {
        if (!tokenFile.exists()) return null
        return runCatching {
            val parts = tokenFile.readText(Charsets.UTF_8).split(":")
            if (parts.size != 2) return null
            val iv = VaultCryptoEngine.fromBase64(parts[0])
            val ct = VaultCryptoEngine.fromBase64(parts[1])
            val ksKey = keystoreManager.getOrCreateDeviceStorageKey()
            val plain = if (ksKey != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(128, iv))
                cipher.doFinal(ct)
            } else {
                VaultCryptoEngine.decryptAesGcm(jvmFallbackKey, iv, ct)
            }
            json.decodeFromString<StoredDriveAuthSession>(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    fun clearSession() {
        tokenFile.delete()
    }
}
