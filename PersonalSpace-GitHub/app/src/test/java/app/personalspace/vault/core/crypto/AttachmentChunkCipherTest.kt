package app.personalspace.vault.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class AttachmentChunkCipherTest {

    private val masterKey = ByteArray(32) { (it * 3 + 11).toByte() }

    @Test
    fun `multi-chunk streaming AES-256-GCM roundtrips 210 KiB payload byte-for-byte with SHA-256 verification`() {
        val plainBytes = ByteArray(210 * 1024).also { Random(42L).nextBytes(it) }
        val cipherOut = ByteArrayOutputStream()

        val outcome = AttachmentChunkCipher.encryptStream(
            attachmentMasterKey = masterKey,
            attachmentId = "att-multi-chunk-001",
            input = ByteArrayInputStream(plainBytes),
            output = cipherOut,
            chunkSize = 64 * 1024 // 4 chunks (64K + 64K + 64K + 18K)
        )

        val encryptedBytes = cipherOut.toByteArray()
        assertEquals(plainBytes.size.toLong(), outcome.totalPlaintextBytes)
        assertEquals(encryptedBytes.size.toLong(), outcome.totalCiphertextBytes)
        assertEquals(VaultCryptoEngine.sha256Hex(plainBytes), outcome.plaintextSha256Hex)

        // Verify ciphertext does not leak plaintext slice
        val probeSlice = plainBytes.copyOfRange(100, 164)
        assertFalse(encryptedBytes.toList().windowed(probeSlice.size).any { it.toByteArray().contentEquals(probeSlice) })

        val decryptedOut = ByteArrayOutputStream()
        val decryptedLen = AttachmentChunkCipher.decryptStream(
            attachmentMasterKey = masterKey,
            attachmentId = "att-multi-chunk-001",
            input = ByteArrayInputStream(encryptedBytes),
            output = decryptedOut,
            expectedSha256Hex = outcome.plaintextSha256Hex
        )

        assertEquals(plainBytes.size.toLong(), decryptedLen)
        assertArrayEquals(plainBytes, decryptedOut.toByteArray())
    }

    @Test
    fun `empty zero-byte attachment stream encrypts and decrypts cleanly with authenticated final tag`() {
        val cipherOut = ByteArrayOutputStream()
        val outcome = AttachmentChunkCipher.encryptStream(
            attachmentMasterKey = masterKey,
            attachmentId = "att-empty-000",
            input = ByteArrayInputStream(ByteArray(0)),
            output = cipherOut
        )
        assertEquals(0L, outcome.totalPlaintextBytes)

        val decryptedOut = ByteArrayOutputStream()
        val len = AttachmentChunkCipher.decryptStream(
            attachmentMasterKey = masterKey,
            attachmentId = "att-empty-000",
            input = ByteArrayInputStream(cipherOut.toByteArray()),
            output = decryptedOut,
            expectedSha256Hex = outcome.plaintextSha256Hex
        )
        assertEquals(0L, len)
        assertEquals(0, decryptedOut.size())
    }

    @Test
    fun `tampered chunk ciphertext, wrong attachmentId AAD, and truncated stream are all rejected`() {
        val plainBytes = ByteArray(150 * 1024) { (it % 251).toByte() }
        val cipherOut = ByteArrayOutputStream()
        val outcome = AttachmentChunkCipher.encryptStream(
            attachmentMasterKey = masterKey,
            attachmentId = "att-genuine-id",
            input = ByteArrayInputStream(plainBytes),
            output = cipherOut,
            chunkSize = 64 * 1024
        )
        val validCipher = cipherOut.toByteArray()

        // 1. Flip one bit inside chunk #2 ciphertext
        val tampered = validCipher.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()
        try {
            AttachmentChunkCipher.decryptStream(
                attachmentMasterKey = masterKey,
                attachmentId = "att-genuine-id",
                input = ByteArrayInputStream(tampered),
                output = ByteArrayOutputStream(),
                expectedSha256Hex = outcome.plaintextSha256Hex
            )
            fail("Expected CryptoAuthException on bit-flipped ciphertext")
        } catch (_: CryptoAuthException) {
            // Expected
        }

        // 2. Wrong attachmentId (cross-attachment substitution attack) fails AAD + HKDF binding
        try {
            AttachmentChunkCipher.decryptStream(
                attachmentMasterKey = masterKey,
                attachmentId = "att-substituted-id",
                input = ByteArrayInputStream(validCipher),
                output = ByteArrayOutputStream()
            )
            fail("Expected CryptoAuthException when decrypting with mismatched attachmentId")
        } catch (_: CryptoAuthException) {
            // Expected
        }

        // 3. Truncated stream (drop final chunk) fails before completion
        val truncated = validCipher.copyOfRange(0, 70 * 1024)
        try {
            AttachmentChunkCipher.decryptStream(
                attachmentMasterKey = masterKey,
                attachmentId = "att-genuine-id",
                input = ByteArrayInputStream(truncated),
                output = ByteArrayOutputStream()
            )
            fail("Expected CryptoAuthException on truncated encrypted attachment stream")
        } catch (_: CryptoAuthException) {
            // Expected
        }
    }
}
