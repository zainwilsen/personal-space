package app.personalspace.vault.core.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.core.common.TimeProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultCryptoAndRecoveryTest {

    private lateinit var context: Context
    private lateinit var vaultKeyManager: VaultKeyManager
    private var fakeNowMillis = 1_763_184_000_000L

    private val fastArgon2 = Argon2KdfConfig(
        memoryKiB = 4096,
        iterations = 2,
        parallelism = 1
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "vault_keystore").deleteRecursively()
        val timeProvider = object : TimeProvider {
            override fun nowMillis(): Long = fakeNowMillis
            override fun zoneId(): ZoneId = ZoneId.of("UTC")
        }
        vaultKeyManager = VaultKeyManager(
            context = context,
            timeProvider = timeProvider,
            keystoreBiometricManager = KeystoreBiometricManager(context)
        ).apply {
            defaultArgon2Config = fastArgon2
        }
    }

    @Test
    fun `Argon2id derivation is deterministic for identical input and distinct across password or salt`() {
        val saltA = ByteArray(16) { (it + 1).toByte() }
        val saltB = ByteArray(16) { (it + 9).toByte() }

        val key1 = VaultCryptoEngine.derivePasswordWrappingKey("BauhausVault#2026".toCharArray(), saltA, fastArgon2)
        val key2 = VaultCryptoEngine.derivePasswordWrappingKey("BauhausVault#2026".toCharArray(), saltA, fastArgon2)
        val diffPwdKey = VaultCryptoEngine.derivePasswordWrappingKey("BauhausVault#2027".toCharArray(), saltA, fastArgon2)
        val diffSaltKey = VaultCryptoEngine.derivePasswordWrappingKey("BauhausVault#2026".toCharArray(), saltB, fastArgon2)

        assertEquals(32, key1.size)
        assertArrayEquals(key1, key2)
        assertFalse(key1.contentEquals(diffPwdKey))
        assertFalse(key1.contentEquals(diffSaltKey))
    }

    @Test
    fun `24-word BIP-39 Recovery Key validates checksum and rejects invalid words or bad checksum`() {
        val bip39 = Bip39Mnemonic.fromContext(context)
        val fixedEntropy = ByteArray(32) { (it * 7 + 3).toByte() }
        val (words, generatedEntropy) = bip39.generate24WordRecoveryKey(fixedEntropy)

        assertEquals(24, words.size)
        assertArrayEquals(fixedEntropy, generatedEntropy)

        // Normalize mixed case, newlines, and extra spaces
        val messyInput = "  " + words.mapIndexed { idx, w ->
            if (idx % 2 == 0) w.uppercase() else w
        }.joinToString(separator = "   \n ") + "  "

        val recoveredEntropy = bip39.decodeAndValidate24WordPhrase(messyInput)
        assertArrayEquals(fixedEntropy, recoveredEntropy)

        // Flip the final word to another valid dictionary word -> checksum must fail
        val mutatedWords = words.toMutableList()
        mutatedWords[23] = if (mutatedWords[23] == "abandon") "ability" else "abandon"
        try {
            bip39.decodeAndValidate24WordPhrase(mutatedWords.joinToString(" "))
            fail("Expected InvalidRecoveryPhraseException for corrupted BIP-39 checksum")
        } catch (expected: InvalidRecoveryPhraseException) {
            assertTrue(expected.message!!.isNotBlank())
        }

        // Unknown word rejection
        val unknownWords = words.toMutableList().apply { this[4] = "notabip39wordxyz" }
        try {
            bip39.decodeAndValidate24WordPhrase(unknownWords.joinToString(" "))
            fail("Expected InvalidRecoveryPhraseException for unknown dictionary word")
        } catch (expected: InvalidRecoveryPhraseException) {
            assertTrue(expected.message!!.contains("Word #5"))
        }
    }

    @Test
    fun `VaultKeyManager wraps VDK independently with Master Password and 24-word Recovery Key and supports password reset and key rotation`() {
        assertFalse(vaultKeyManager.isVaultInitialized())

        val initialPassword = "StrongMasterPassword!99".toCharArray()
        val initResult = vaultKeyManager.createNewVault(initialPassword, fastArgon2)
        assertTrue(vaultKeyManager.isVaultInitialized())
        assertEquals(24, initResult.recoveryWords.size)

        // Purpose-specific HKDF subkeys must be 32 bytes and mutually isolated
        val subKeys = initResult.subKeys
        assertEquals(32, subKeys.vaultDataKey.size)
        assertEquals(32, subKeys.sqlCipherDbKey.size)
        assertEquals(32, subKeys.attachmentMasterKey.size)
        assertEquals(32, subKeys.backupPayloadKey.size)
        assertEquals(32, subKeys.backupManifestKey.size)
        assertFalse(subKeys.sqlCipherDbKey.contentEquals(subKeys.attachmentMasterKey))
        assertFalse(subKeys.attachmentMasterKey.contentEquals(subKeys.backupPayloadKey))

        val originalVdk = subKeys.vaultDataKey.copyOf()

        // 1. Unlock with Master Password yields identical VDK & SQLCipher key
        val pwdUnlocked = vaultKeyManager.unlockWithMasterPassword(initialPassword)
        assertArrayEquals(originalVdk, pwdUnlocked.vaultDataKey)
        assertArrayEquals(subKeys.sqlCipherDbKey, pwdUnlocked.sqlCipherDbKey)

        // 2. Wrong Master Password fails cleanly
        try {
            vaultKeyManager.unlockWithMasterPassword("WrongPassword!99".toCharArray())
            fail("Expected CryptoAuthException on wrong Master Password")
        } catch (_: CryptoAuthException) {
            // Expected
        }

        // 3. Unlock independently via 24-word Recovery Key (simulating forgotten Master Password)
        val phrase = initResult.recoveryWords.joinToString(" ")
        val recUnlocked = vaultKeyManager.unlockWithRecoveryPhrase(phrase)
        assertArrayEquals(originalVdk, recUnlocked.vaultDataKey)

        // 4. Reset Master Password using 24-word Recovery Key WITHOUT changing VDK
        val resetPassword = "RecoveredNewPassword#42".toCharArray()
        val resetUnlocked = vaultKeyManager.resetPasswordWithRecoveryPhrase(phrase, resetPassword, fastArgon2)
        assertArrayEquals(originalVdk, resetUnlocked.vaultDataKey)

        // Old password must now fail; new password must succeed
        try {
            vaultKeyManager.unlockWithMasterPassword(initialPassword)
            fail("Old Master Password should no longer unlock after password reset")
        } catch (_: CryptoAuthException) {
            // Expected
        }
        val afterResetPwdUnlock = vaultKeyManager.unlockWithMasterPassword(resetPassword)
        assertArrayEquals(originalVdk, afterResetPwdUnlock.vaultDataKey)

        // 5. Rotate 24-word Recovery Key while unlocked -> old phrase rejected, new phrase unlocks same VDK
        fakeNowMillis += 10_000L
        val rotatedWords = vaultKeyManager.rotateRecoveryKey(resetPassword)
        val rotatedPhrase = rotatedWords.joinToString(" ")
        assertNotEquals(phrase, rotatedPhrase)

        try {
            vaultKeyManager.unlockWithRecoveryPhrase(phrase)
            fail("Old 24-word Recovery Key must fail after key rotation")
        } catch (_: CryptoAuthException) {
            // Expected
        }

        val afterRotateUnlock = vaultKeyManager.unlockWithRecoveryPhrase(rotatedPhrase)
        assertArrayEquals(originalVdk, afterRotateUnlock.vaultDataKey)

        // 6. Memory wipe zeros sensitive key arrays
        afterRotateUnlock.wipe()
        assertTrue(afterRotateUnlock.vaultDataKey.all { it == 0.toByte() })
        assertTrue(afterRotateUnlock.sqlCipherDbKey.all { it == 0.toByte() })
    }
}
