package app.personalspace.vault.core.crypto

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class Argon2KdfConfig(
    val memoryKiB: Int = 65536, // 64 MiB default on device
    val iterations: Int = 3,
    val parallelism: Int = 2
)

@Serializable
data class EncryptedKeyEnvelope(
    val saltBase64: String,
    val ivBase64: String,
    val ciphertextBase64: String,
    val argon2Config: Argon2KdfConfig? = null
)

@Serializable
data class VaultKeyEnvelopeBundle(
    val formatVersion: Int = 1,
    val vaultId: String,
    val createdAtMillis: Long,
    val passwordUpdatedAtMillis: Long,
    val recoveryUpdatedAtMillis: Long,
    val passwordEnvelope: EncryptedKeyEnvelope,
    val recoveryEnvelope: EncryptedKeyEnvelope,
    val recoveryPasswordEnvelope: EncryptedKeyEnvelope? = null,
    val recoveryPasswordUpdatedAtMillis: Long = 0L
)

@Serializable
data class LocalBiometricEnvelope(
    val vaultId: String,
    val ivBase64: String,
    val ciphertextBase64: String,
    val enrolledAtMillis: Long
)

class CryptoAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Audited standard cryptographic primitives:
 * - AES-256-GCM (96-bit random IV, 128-bit auth tag, explicit AAD binding)
 * - Argon2id v1.3 (BouncyCastle RFC 9106) for Master Password key derivation
 * - HKDF-SHA256 (RFC 5869) for domain-separated subkey derivation
 */
object VaultCryptoEngine {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val GCM_IV_BYTES = 12
    const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded.trim())

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String =
        sha256(data).joinToString("") { "%02x".format(it) }

    /**
     * Derives a 256-bit wrapping key from Master Password using Argon2id.
     */
    fun derivePasswordWrappingKey(
        password: CharArray,
        salt: ByteArray,
        config: Argon2KdfConfig = Argon2KdfConfig()
    ): ByteArray {
        require(salt.size >= SALT_BYTES) { "Argon2id salt must be at least 16 bytes" }
        val normalized = Normalizer.normalize(String(password), Normalizer.Form.NFKC)
        val pwdBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(KEY_BYTES)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(config.memoryKiB)
                .withIterations(config.iterations)
                .withParallelism(config.parallelism)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            generator.generateBytes(pwdBytes, out, 0, KEY_BYTES)
            return out
        } finally {
            pwdBytes.fill(0)
        }
    }

    /**
     * Derives a purpose-bound 256-bit key via HKDF-SHA256 (RFC 5869).
     */
    fun deriveHkdfKey(
        ikm: ByteArray,
        salt: ByteArray?,
        info: String,
        outputLength: Int = KEY_BYTES
    ): ByteArray {
        require(ikm.isNotEmpty()) { "HKDF input key material cannot be empty" }
        val infoBytes = info.toByteArray(StandardCharsets.UTF_8)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, infoBytes))
        val out = ByteArray(outputLength)
        hkdf.generateBytes(out, 0, outputLength)
        return out
    }

    /**
     * Encrypts plaintext with AES-256-GCM and binds optional Associated Authenticated Data (AAD).
     */
    fun encryptAesGcm(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null,
        iv: ByteArray = randomBytes(GCM_IV_BYTES)
    ): Pair<ByteArray, ByteArray> {
        require(key.size == KEY_BYTES) { "AES-256-GCM requires a 32-byte key" }
        require(iv.size == GCM_IV_BYTES) { "AES-256-GCM requires a 12-byte IV" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return iv to ciphertext
    }

    /**
     * Decrypts AES-256-GCM ciphertext and verifies authenticity tag + AAD.
     */
    fun decryptAesGcm(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256-GCM requires a 32-byte key" }
        require(iv.size == GCM_IV_BYTES) { "AES-256-GCM requires a 12-byte IV" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            return cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw CryptoAuthException("Authentication failed: invalid key or tampered ciphertext", e)
        } catch (e: Exception) {
            throw CryptoAuthException("Decryption failed: ${e.message}", e)
        }
    }

    fun passwordAad(vaultId: String): ByteArray =
        "PersonalSpace/v1/envelope/password/$vaultId".toByteArray(StandardCharsets.UTF_8)

    fun recoveryAad(vaultId: String): ByteArray =
        "PersonalSpace/v1/envelope/recovery/$vaultId".toByteArray(StandardCharsets.UTF_8)

    fun recoveryPasswordAad(vaultId: String): ByteArray =
        "PersonalSpace/v1/envelope/recovery-password/$vaultId".toByteArray(StandardCharsets.UTF_8)

    fun deriveSubKeys(vaultDataKey: ByteArray, vaultId: String): DerivedVaultSubKeys {
        require(vaultDataKey.size == KEY_BYTES) { "Vault Data Key must be 32 bytes" }
        val domainSalt = sha256("PersonalSpace/v1/vault/$vaultId".toByteArray(StandardCharsets.UTF_8))
        return DerivedVaultSubKeys(
            vaultId = vaultId,
            vaultDataKey = vaultDataKey.copyOf(),
            sqlCipherDbKey = deriveHkdfKey(vaultDataKey, domainSalt, "PersonalSpace/v1/sqlcipher-db-key"),
            attachmentMasterKey = deriveHkdfKey(vaultDataKey, domainSalt, "PersonalSpace/v1/attachment-master-key"),
            backupPayloadKey = deriveHkdfKey(vaultDataKey, domainSalt, "PersonalSpace/v1/backup-payload-key"),
            backupManifestKey = deriveHkdfKey(vaultDataKey, domainSalt, "PersonalSpace/v1/backup-manifest-key")
        )
    }
}

class DerivedVaultSubKeys(
    val vaultId: String,
    val vaultDataKey: ByteArray,
    val sqlCipherDbKey: ByteArray,
    val attachmentMasterKey: ByteArray,
    val backupPayloadKey: ByteArray,
    val backupManifestKey: ByteArray
) {
    fun wipe() {
        vaultDataKey.fill(0)
        sqlCipherDbKey.fill(0)
        attachmentMasterKey.fill(0)
        backupPayloadKey.fill(0)
        backupManifestKey.fill(0)
    }
}
