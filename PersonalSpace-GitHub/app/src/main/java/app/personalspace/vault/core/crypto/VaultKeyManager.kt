package app.personalspace.vault.core.crypto

import android.content.Context
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.common.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AutoVaultKeyRecord(
    val vaultId: String,
    val vdkBase64: String,
    val createdAtMillis: Long
)

data class VaultInitializationResult(
    val bundle: VaultKeyEnvelopeBundle,
    val recoveryWords: List<String>,
    val subKeys: DerivedVaultSubKeys
)

/**
 * Implements the dual-envelope Vault Key Architecture:
 * - Random 256-bit Vault Data Key (VDK)
 * - Master Password -> Argon2id -> wraps VDK with AES-256-GCM
 * - 24-word BIP-39 Recovery Key -> HKDF-SHA256 -> wraps the SAME VDK with AES-256-GCM
 * - Optional device-bound Biometric Keystore Cipher -> wraps VDK locally (never synced)
 *
 * Neither the Master Password nor the Recovery Key is ever persisted in plaintext.
 */
@Singleton
class VaultKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider,
    private val keystoreBiometricManager: KeystoreBiometricManager
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var defaultArgon2Config: Argon2KdfConfig = Argon2KdfConfig()

    private val keystoreDir: File
        get() = File(context.filesDir, "vault_keystore").apply { mkdirs() }

    private val envelopeFile: File
        get() = File(keystoreDir, "vault_envelopes.json")

    private val biometricFile: File
        get() = File(keystoreDir, "device_biometric_envelope.json")

    private val autoVaultFile: File
        get() = File(keystoreDir, "auto_open_vdk.json")

    private val bip39: Bip39Mnemonic by lazy {
        runCatching { Bip39Mnemonic.fromContext(context) }
            .getOrElse { Bip39Mnemonic.forTestingFromResourceFile() }
    }

    fun isVaultInitialized(): Boolean =
        (envelopeFile.exists() && envelopeFile.length() > 0L) || hasAutoOpenVault()

    fun hasAutoOpenVault(): Boolean = autoVaultFile.exists() && autoVaultFile.length() > 0L

    /**
     * Automatically provisions or unlocks a local device vault without requiring any login,
     * Master Password, or biometric screen. If upgrading from an older password-locked database,
     * cleanly resets the incompatible database files once so the app opens straight to Dashboard.
     */
    fun ensureAutoUnlockedVault(): DerivedVaultSubKeys {
        if (hasAutoOpenVault()) {
            val existing = runCatching {
                json.decodeFromString<AutoVaultKeyRecord>(autoVaultFile.readText(Charsets.UTF_8))
            }.getOrNull()
            if (existing != null) {
                val vdk = VaultCryptoEngine.fromBase64(existing.vdkBase64)
                return try {
                    VaultCryptoEngine.deriveSubKeys(vdk, existing.vaultId)
                } finally {
                    vdk.fill(0)
                }
            }
        }

        val dbFile = context.getDatabasePath("personal_space_encrypted.db")
        if (dbFile.exists()) {
            throw CryptoAuthException(
                "Vault key is missing or unreadable, but an existing vault database was found. " +
                    "The data has been left untouched. Restore a backup file from Settings."
            )
        }

        val vaultId = IdGenerator.newUuid()
        val now = timeProvider.nowMillis()
        val vdk = VaultCryptoEngine.randomBytes(VaultCryptoEngine.KEY_BYTES)
        val (_, recoveryEntropy) = bip39.generate24WordRecoveryKey()
        val fastArgon2 = Argon2KdfConfig(memoryKiB = 4096, iterations = 1, parallelism = 1)
        val defaultAutoPass = "personal-space-auto-vault".toCharArray()

        return try {
            val record = AutoVaultKeyRecord(
                vaultId = vaultId,
                vdkBase64 = VaultCryptoEngine.toBase64(vdk),
                createdAtMillis = now
            )
            val tmp = File(keystoreDir, "auto_open_vdk.json.tmp")
            tmp.writeText(json.encodeToString(record), Charsets.UTF_8)
            if (!tmp.renameTo(autoVaultFile)) {
                tmp.copyTo(autoVaultFile, overwrite = true)
                tmp.delete()
            }

            // Maintain an envelope bundle so backup/export flows continue working seamlessly
            val pwEnv = wrapWithPassword(vdk, vaultId, defaultAutoPass, fastArgon2)
            val recEnv = wrapWithRecoveryEntropy(vdk, vaultId, recoveryEntropy)
            saveEnvelopeBundle(
                VaultKeyEnvelopeBundle(
                    vaultId = vaultId,
                    createdAtMillis = now,
                    passwordUpdatedAtMillis = now,
                    recoveryUpdatedAtMillis = now,
                    passwordEnvelope = pwEnv,
                    recoveryEnvelope = recEnv
                )
            )

            VaultCryptoEngine.deriveSubKeys(vdk, vaultId)
        } finally {
            vdk.fill(0)
            recoveryEntropy.fill(0)
            defaultAutoPass.fill('\u0000')
        }
    }

    fun readEnvelopeBundle(): VaultKeyEnvelopeBundle? {
        if (!envelopeFile.exists()) return null
        return runCatching {
            json.decodeFromString<VaultKeyEnvelopeBundle>(envelopeFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun saveEnvelopeBundle(bundle: VaultKeyEnvelopeBundle) {
        val tmp = File(keystoreDir, "vault_envelopes.json.tmp")
        tmp.writeText(json.encodeToString(bundle), Charsets.UTF_8)
        if (!tmp.renameTo(envelopeFile)) {
            tmp.copyTo(envelopeFile, overwrite = true)
            tmp.delete()
        }
    }

    fun isBiometricUnlockEnabled(): Boolean {
        val bundle = readEnvelopeBundle() ?: return false
        val bio = readBiometricEnvelope() ?: return false
        return bio.vaultId == bundle.vaultId
    }

    fun readBiometricEnvelope(): LocalBiometricEnvelope? {
        if (!biometricFile.exists()) return null
        return runCatching {
            json.decodeFromString<LocalBiometricEnvelope>(biometricFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Creates a brand-new encrypted vault with a random 256-bit VDK, Argon2id password envelope,
     * and 24-word BIP-39 recovery envelope.
     */
    fun createNewVault(
        masterPassword: CharArray,
        argon2Config: Argon2KdfConfig = defaultArgon2Config
    ): VaultInitializationResult {
        require(masterPassword.size >= 8) { "Master Password must be at least 8 characters." }

        val vaultId = IdGenerator.newUuid()
        val now = timeProvider.nowMillis()
        val vaultDataKey = VaultCryptoEngine.randomBytes(VaultCryptoEngine.KEY_BYTES)
        val (recoveryWords, recoveryEntropy) = bip39.generate24WordRecoveryKey()

        try {
            val passwordEnvelope = wrapWithPassword(
                vaultDataKey = vaultDataKey,
                vaultId = vaultId,
                password = masterPassword,
                argon2Config = argon2Config
            )
            val recoveryEnvelope = wrapWithRecoveryEntropy(
                vaultDataKey = vaultDataKey,
                vaultId = vaultId,
                recoveryEntropy = recoveryEntropy
            )

            val bundle = VaultKeyEnvelopeBundle(
                vaultId = vaultId,
                createdAtMillis = now,
                passwordUpdatedAtMillis = now,
                recoveryUpdatedAtMillis = now,
                passwordEnvelope = passwordEnvelope,
                recoveryEnvelope = recoveryEnvelope
            )
            saveEnvelopeBundle(bundle)
            clearBiometricUnlock()

            val subKeys = VaultCryptoEngine.deriveSubKeys(vaultDataKey, vaultId)
            return VaultInitializationResult(
                bundle = bundle,
                recoveryWords = recoveryWords,
                subKeys = subKeys
            )
        } finally {
            vaultDataKey.fill(0)
            recoveryEntropy.fill(0)
        }
    }

    /**
     * Unlocks VDK using the Master Password against the stored (or supplied backup) envelope bundle.
     */
    fun unlockWithMasterPassword(
        password: CharArray,
        bundle: VaultKeyEnvelopeBundle = readEnvelopeBundle()
            ?: throw CryptoAuthException("No vault is initialized on this device.")
    ): DerivedVaultSubKeys {
        val env = bundle.passwordEnvelope
        val salt = VaultCryptoEngine.fromBase64(env.saltBase64)
        val iv = VaultCryptoEngine.fromBase64(env.ivBase64)
        val ciphertext = VaultCryptoEngine.fromBase64(env.ciphertextBase64)
        val config = env.argon2Config ?: defaultArgon2Config

        val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(password, salt, config)
        try {
            val vdk = VaultCryptoEngine.decryptAesGcm(
                key = wrappingKey,
                iv = iv,
                ciphertext = ciphertext,
                aad = VaultCryptoEngine.passwordAad(bundle.vaultId)
            )
            try {
                return VaultCryptoEngine.deriveSubKeys(vdk, bundle.vaultId)
            } finally {
                vdk.fill(0)
            }
        } catch (e: CryptoAuthException) {
            throw CryptoAuthException("Incorrect Master Password.", e)
        } finally {
            wrappingKey.fill(0)
        }
    }

    /**
     * Unlocks VDK independently using the 24-word BIP-39 Recovery Key.
     */
    fun unlockWithRecoveryPhrase(
        recoveryPhrase: String,
        bundle: VaultKeyEnvelopeBundle = readEnvelopeBundle()
            ?: throw CryptoAuthException("No vault is initialized on this device.")
    ): DerivedVaultSubKeys {
        val recoveryEntropy = bip39.decodeAndValidate24WordPhrase(recoveryPhrase)
        val env = bundle.recoveryEnvelope
        val salt = VaultCryptoEngine.fromBase64(env.saltBase64)
        val iv = VaultCryptoEngine.fromBase64(env.ivBase64)
        val ciphertext = VaultCryptoEngine.fromBase64(env.ciphertextBase64)

        val wrappingKey = VaultCryptoEngine.deriveHkdfKey(
            ikm = recoveryEntropy,
            salt = salt,
            info = "PersonalSpace/v1/recovery-wrap-key"
        )
        try {
            val vdk = VaultCryptoEngine.decryptAesGcm(
                key = wrappingKey,
                iv = iv,
                ciphertext = ciphertext,
                aad = VaultCryptoEngine.recoveryAad(bundle.vaultId)
            )
            try {
                return VaultCryptoEngine.deriveSubKeys(vdk, bundle.vaultId)
            } finally {
                vdk.fill(0)
            }
        } catch (e: CryptoAuthException) {
            throw CryptoAuthException("Recovery Key does not match this vault.", e)
        } finally {
            recoveryEntropy.fill(0)
            wrappingKey.fill(0)
        }
    }

    fun persistAutoOpenRecord(vaultDataKey: ByteArray, vaultId: String) {
        val record = AutoVaultKeyRecord(
            vaultId = vaultId,
            vdkBase64 = VaultCryptoEngine.toBase64(vaultDataKey),
            createdAtMillis = timeProvider.nowMillis()
        )
        val tmp = File(keystoreDir, "auto_open_vdk.json.tmp")
        tmp.writeText(json.encodeToString(record), Charsets.UTF_8)
        if (!tmp.renameTo(autoVaultFile)) {
            tmp.copyTo(autoVaultFile, overwrite = true)
            tmp.delete()
        }
    }

    fun hasRecoveryPassword(): Boolean = readEnvelopeBundle()?.recoveryPasswordEnvelope != null

    fun setRecoveryPassword(password: CharArray) {
        if (password.size < 8) throw CryptoAuthException("Recovery password must be at least 8 characters.")
        val bundle = readEnvelopeBundle() ?: throw CryptoAuthException("Vault is not initialized on this device.")
        val record = runCatching {
            json.decodeFromString<AutoVaultKeyRecord>(autoVaultFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: throw CryptoAuthException("Local vault key is unavailable.")
        val vdk = VaultCryptoEngine.fromBase64(record.vdkBase64)
        try {
            val envelope = wrapWithRecoveryPassword(vdk, bundle.vaultId, password)
            saveEnvelopeBundle(
                bundle.copy(
                    recoveryPasswordEnvelope = envelope,
                    recoveryPasswordUpdatedAtMillis = timeProvider.nowMillis()
                )
            )
        } finally {
            vdk.fill(0)
            password.fill('\u0000')
        }
    }

    fun clearRecoveryPassword() {
        val bundle = readEnvelopeBundle() ?: return
        saveEnvelopeBundle(bundle.copy(recoveryPasswordEnvelope = null, recoveryPasswordUpdatedAtMillis = 0L))
    }

    fun unlockWithRecoveryPassword(
        password: CharArray,
        bundle: VaultKeyEnvelopeBundle = readEnvelopeBundle()
            ?: throw CryptoAuthException("No vault is initialized on this device.")
    ): DerivedVaultSubKeys {
        val env = bundle.recoveryPasswordEnvelope
            ?: throw CryptoAuthException("This vault has no recovery password configured.")
        val salt = VaultCryptoEngine.fromBase64(env.saltBase64)
        val iv = VaultCryptoEngine.fromBase64(env.ivBase64)
        val ciphertext = VaultCryptoEngine.fromBase64(env.ciphertextBase64)
        val config = env.argon2Config ?: defaultArgon2Config
        val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(password, salt, config)
        try {
            val vdk = VaultCryptoEngine.decryptAesGcm(
                key = wrappingKey,
                iv = iv,
                ciphertext = ciphertext,
                aad = VaultCryptoEngine.recoveryPasswordAad(bundle.vaultId)
            )
            try {
                return VaultCryptoEngine.deriveSubKeys(vdk, bundle.vaultId)
            } finally {
                vdk.fill(0)
            }
        } catch (e: CryptoAuthException) {
            throw CryptoAuthException("Incorrect Recovery Password.", e)
        } finally {
            wrappingKey.fill(0)
        }
    }

    /**
     * Re-wraps the existing Vault Data Key with a new Master Password without re-encrypting records.
     */
    fun changeMasterPassword(
        currentPassword: CharArray,
        newPassword: CharArray,
        argon2Config: Argon2KdfConfig = defaultArgon2Config
    ) {
        require(newPassword.size >= 8) { "New Master Password must be at least 8 characters." }
        val bundle = readEnvelopeBundle() ?: throw CryptoAuthException("Vault is not initialized.")
        val unlocked = unlockWithMasterPassword(currentPassword, bundle)
        try {
            val newPasswordEnvelope = wrapWithPassword(
                vaultDataKey = unlocked.vaultDataKey,
                vaultId = bundle.vaultId,
                password = newPassword,
                argon2Config = argon2Config
            )
            saveEnvelopeBundle(
                bundle.copy(
                    passwordUpdatedAtMillis = timeProvider.nowMillis(),
                    passwordEnvelope = newPasswordEnvelope
                )
            )
        } finally {
            unlocked.wipe()
        }
    }

    /**
     * Resets Master Password after authenticating via the 24-word Recovery Key.
     */
    fun resetPasswordWithRecoveryPhrase(
        recoveryPhrase: String,
        newPassword: CharArray,
        argon2Config: Argon2KdfConfig = defaultArgon2Config
    ): DerivedVaultSubKeys {
        require(newPassword.size >= 8) { "New Master Password must be at least 8 characters." }
        val bundle = readEnvelopeBundle() ?: throw CryptoAuthException("Vault is not initialized.")
        val unlocked = unlockWithRecoveryPhrase(recoveryPhrase, bundle)
        val newPasswordEnvelope = wrapWithPassword(
            vaultDataKey = unlocked.vaultDataKey,
            vaultId = bundle.vaultId,
            password = newPassword,
            argon2Config = argon2Config
        )
        saveEnvelopeBundle(
            bundle.copy(
                passwordUpdatedAtMillis = timeProvider.nowMillis(),
                passwordEnvelope = newPasswordEnvelope
            )
        )
        return unlocked
    }

    /**
     * Rotates the 24-word BIP-39 Recovery Key while preserving the existing VDK and Master Password.
     */
    fun rotateRecoveryKey(currentPassword: CharArray): List<String> {
        val bundle = readEnvelopeBundle() ?: throw CryptoAuthException("Vault is not initialized.")
        val unlocked = unlockWithMasterPassword(currentPassword, bundle)
        val (newWords, newEntropy) = bip39.generate24WordRecoveryKey()
        try {
            val newRecoveryEnvelope = wrapWithRecoveryEntropy(
                vaultDataKey = unlocked.vaultDataKey,
                vaultId = bundle.vaultId,
                recoveryEntropy = newEntropy
            )
            saveEnvelopeBundle(
                bundle.copy(
                    recoveryUpdatedAtMillis = timeProvider.nowMillis(),
                    recoveryEnvelope = newRecoveryEnvelope
                )
            )
            return newWords
        } finally {
            newEntropy.fill(0)
            unlocked.wipe()
        }
    }

    /**
     * Completes device-bound Biometric enrollment using an authenticated Keystore ENCRYPT Cipher.
     */
    fun completeBiometricEnrollment(
        authenticatedEncryptCipher: Cipher,
        activeSubKeys: DerivedVaultSubKeys
    ) {
        val aad = "PersonalSpace/v1/biometric/${activeSubKeys.vaultId}".toByteArray(StandardCharsets.UTF_8)
        authenticatedEncryptCipher.updateAAD(aad)
        val ciphertext = authenticatedEncryptCipher.doFinal(activeSubKeys.vaultDataKey)
        val iv = authenticatedEncryptCipher.iv
        val localEnvelope = LocalBiometricEnvelope(
            vaultId = activeSubKeys.vaultId,
            ivBase64 = VaultCryptoEngine.toBase64(iv),
            ciphertextBase64 = VaultCryptoEngine.toBase64(ciphertext),
            enrolledAtMillis = timeProvider.nowMillis()
        )
        biometricFile.writeText(json.encodeToString(localEnvelope), Charsets.UTF_8)
    }

    fun prepareBiometricUnlockCipher(): Cipher {
        val bio = readBiometricEnvelope()
            ?: throw CryptoAuthException("Biometric unlock is not configured.")
        val iv = VaultCryptoEngine.fromBase64(bio.ivBase64)
        return try {
            keystoreBiometricManager.createUnlockCipher(iv)
        } catch (invalidated: BiometricKeyInvalidatedException) {
            biometricFile.delete()
            throw invalidated
        }
    }

    fun unlockWithAuthenticatedBiometricCipher(authenticatedDecryptCipher: Cipher): DerivedVaultSubKeys {
        val bio = readBiometricEnvelope()
            ?: throw CryptoAuthException("Biometric unlock is not configured.")
        val ciphertext = VaultCryptoEngine.fromBase64(bio.ciphertextBase64)
        val aad = "PersonalSpace/v1/biometric/${bio.vaultId}".toByteArray(StandardCharsets.UTF_8)
        return try {
            authenticatedDecryptCipher.updateAAD(aad)
            val vdk = authenticatedDecryptCipher.doFinal(ciphertext)
            try {
                VaultCryptoEngine.deriveSubKeys(vdk, bio.vaultId)
            } finally {
                vdk.fill(0)
            }
        } catch (e: Exception) {
            throw CryptoAuthException("Biometric decryption failed. Please unlock with your Master Password.", e)
        }
    }

    fun clearBiometricUnlock() {
        biometricFile.delete()
        keystoreBiometricManager.deleteBiometricKey()
    }

    private fun wrapWithPassword(
        vaultDataKey: ByteArray,
        vaultId: String,
        password: CharArray,
        argon2Config: Argon2KdfConfig
    ): EncryptedKeyEnvelope {
        val salt = VaultCryptoEngine.randomBytes(VaultCryptoEngine.SALT_BYTES)
        val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(password, salt, argon2Config)
        try {
            val (iv, cipher) = VaultCryptoEngine.encryptAesGcm(
                key = wrappingKey,
                plaintext = vaultDataKey,
                aad = VaultCryptoEngine.passwordAad(vaultId)
            )
            return EncryptedKeyEnvelope(
                saltBase64 = VaultCryptoEngine.toBase64(salt),
                ivBase64 = VaultCryptoEngine.toBase64(iv),
                ciphertextBase64 = VaultCryptoEngine.toBase64(cipher),
                argon2Config = argon2Config
            )
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun wrapWithRecoveryPassword(
        vaultDataKey: ByteArray,
        vaultId: String,
        password: CharArray
    ): EncryptedKeyEnvelope {
        val salt = VaultCryptoEngine.randomBytes(VaultCryptoEngine.SALT_BYTES)
        val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(password, salt, defaultArgon2Config)
        try {
            val (iv, cipher) = VaultCryptoEngine.encryptAesGcm(
                key = wrappingKey,
                plaintext = vaultDataKey,
                aad = VaultCryptoEngine.recoveryPasswordAad(vaultId)
            )
            return EncryptedKeyEnvelope(
                saltBase64 = VaultCryptoEngine.toBase64(salt),
                ivBase64 = VaultCryptoEngine.toBase64(iv),
                ciphertextBase64 = VaultCryptoEngine.toBase64(cipher),
                argon2Config = defaultArgon2Config
            )
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun wrapWithRecoveryEntropy(
        vaultDataKey: ByteArray,
        vaultId: String,
        recoveryEntropy: ByteArray
    ): EncryptedKeyEnvelope {
        val salt = VaultCryptoEngine.randomBytes(VaultCryptoEngine.SALT_BYTES)
        val wrappingKey = VaultCryptoEngine.deriveHkdfKey(
            ikm = recoveryEntropy,
            salt = salt,
            info = "PersonalSpace/v1/recovery-wrap-key"
        )
        try {
            val (iv, cipher) = VaultCryptoEngine.encryptAesGcm(
                key = wrappingKey,
                plaintext = vaultDataKey,
                aad = VaultCryptoEngine.recoveryAad(vaultId)
            )
            return EncryptedKeyEnvelope(
                saltBase64 = VaultCryptoEngine.toBase64(salt),
                ivBase64 = VaultCryptoEngine.toBase64(iv),
                ciphertextBase64 = VaultCryptoEngine.toBase64(cipher),
                argon2Config = null
            )
        } finally {
            wrappingKey.fill(0)
        }
    }
}
