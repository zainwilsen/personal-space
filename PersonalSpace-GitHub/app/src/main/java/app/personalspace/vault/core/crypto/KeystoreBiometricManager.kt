package app.personalspace.vault.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

enum class BiometricHardwareStatus {
    AVAILABLE,
    NONE_ENROLLED,
    NO_HARDWARE,
    UNAVAILABLE
}

class BiometricKeyInvalidatedException(
    message: String = "Biometric enrollment changed on this device. Unlock with your Master Password or Recovery Key to re-enable biometric unlock."
) : Exception(message)

/**
 * Manages device-bound Android Keystore AES-256-GCM keys gated by BiometricPrompt.CryptoObject.
 * Biometric templates are never read or stored by the application; Keystore enforces hardware/TEE
 * user authentication before releasing cipher operations.
 */
@Singleton
class KeystoreBiometricManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val BIOMETRIC_KEY_ALIAS = "personal_space_biometric_vdk_wrap_v1"
        private const val DEVICE_SECRET_KEY_ALIAS = "personal_space_device_token_store_v1"
    }

    fun queryBiometricStatus(): BiometricHardwareStatus {
        return runCatching {
            val bm = BiometricManager.from(context)
            when (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricHardwareStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricHardwareStatus.NONE_ENROLLED
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricHardwareStatus.NO_HARDWARE
                else -> BiometricHardwareStatus.UNAVAILABLE
            }
        }.getOrDefault(BiometricHardwareStatus.UNAVAILABLE)
    }

    /**
     * Generates a fresh biometric-bound Keystore key and returns an initialized ENCRYPT Cipher
     * to pass into BiometricPrompt.CryptoObject during biometric enrollment.
     */
    fun createEnrollmentCipher(): Cipher {
        deleteBiometricKey()
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGenerator.init(spec)
        val secretKey = keyGenerator.generateKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    /**
     * Returns an initialized DECRYPT Cipher to pass into BiometricPrompt.CryptoObject during unlock.
     * Throws BiometricKeyInvalidatedException if device biometrics changed since enrollment.
     */
    fun createUnlockCipher(iv: ByteArray): Cipher {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
            ?: throw BiometricKeyInvalidatedException("Biometric key is missing. Unlock with your Master Password.")

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            return cipher
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteBiometricKey()
            throw BiometricKeyInvalidatedException()
        }
    }

    fun deleteBiometricKey() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(BIOMETRIC_KEY_ALIAS)) {
                ks.deleteEntry(BIOMETRIC_KEY_ALIAS)
            }
        }
    }

    /**
     * Device-bound (non-biometric) Android Keystore AES-256-GCM key used strictly to encrypt
     * local OAuth tokens at rest inside EncryptedTokenStore. Falls back gracefully in JVM unit tests
     * where AndroidKeyStore provider is unavailable.
     */
    fun getOrCreateDeviceStorageKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getKey(DEVICE_SECRET_KEY_ALIAS, null) as? SecretKey) ?: run {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    DEVICE_SECRET_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                kg.init(spec)
                kg.generateKey()
            }
        }.getOrNull()
    }
}
