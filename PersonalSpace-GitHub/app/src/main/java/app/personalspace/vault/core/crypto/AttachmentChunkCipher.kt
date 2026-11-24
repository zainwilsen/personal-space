package app.personalspace.vault.core.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Streaming chunked AES-256-GCM file cipher for attachments and large backup streams.
 *
 * Security invariants:
 * - Bounded heap memory (64 KiB plaintext chunks — never loads large files into RAM).
 * - Unique per-file 256-bit key derived via HKDF-SHA256 from Attachment Master Key + 16-byte random file salt.
 * - Unique 12-byte GCM nonce per chunk: 8-byte random stream prefix || 4-byte big-endian chunk index.
 * - Explicit AAD per chunk binding: attachmentId || chunkIndex || isFinalChunk, preventing
 *   chunk reordering, splicing, cross-attachment substitution, and silent truncation!
 */
object AttachmentChunkCipher {
    private val MAGIC = "PSATT01\u0000".toByteArray(StandardCharsets.US_ASCII)
    const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KiB

    data class EncryptionOutcome(
        val totalPlaintextBytes: Long,
        val totalCiphertextBytes: Long,
        val plaintextSha256Hex: String
    )

    fun encryptStream(
        attachmentMasterKey: ByteArray,
        attachmentId: String,
        input: InputStream,
        output: OutputStream,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): EncryptionOutcome {
        require(attachmentMasterKey.size == VaultCryptoEngine.KEY_BYTES) {
            "Attachment master key must be 32 bytes"
        }
        val fileSalt = VaultCryptoEngine.randomBytes(VaultCryptoEngine.SALT_BYTES)
        val noncePrefix = VaultCryptoEngine.randomBytes(8)
        val fileKey = VaultCryptoEngine.deriveHkdfKey(
            ikm = attachmentMasterKey,
            salt = fileSalt,
            info = "PersonalSpace/v1/attachment/$attachmentId"
        )

        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val dos = DataOutputStream(output)
        var totalPlain = 0L
        var totalWritten = 0L

        try {
            dos.write(MAGIC)
            dos.write(fileSalt)
            dos.write(noncePrefix)
            dos.writeInt(chunkSize)
            totalWritten += MAGIC.size + fileSalt.size + noncePrefix.size + 4

            val buffer = ByteArray(chunkSize)
            var chunkIndex = 0
            var pendingBytes = readUpToChunk(input, buffer)

            if (pendingBytes <= 0) {
                // Empty file: write single authenticated final empty chunk
                val iv = buildChunkNonce(noncePrefix, 0)
                val aad = buildChunkAad(attachmentId, 0, isFinal = true)
                val (_, cipherBytes) = VaultCryptoEngine.encryptAesGcm(
                    key = fileKey,
                    plaintext = ByteArray(0),
                    aad = aad,
                    iv = iv
                )
                dos.writeBoolean(true)
                dos.writeInt(cipherBytes.size)
                dos.write(cipherBytes)
                totalWritten += 1 + 4 + cipherBytes.size
            } else {
                val nextBuffer = ByteArray(chunkSize)
                while (pendingBytes > 0) {
                    val nextBytes = readUpToChunk(input, nextBuffer)
                    val isFinal = nextBytes <= 0

                    val plainSlice = buffer.copyOfRange(0, pendingBytes)
                    sha256.update(plainSlice)
                    totalPlain += pendingBytes

                    val iv = buildChunkNonce(noncePrefix, chunkIndex)
                    val aad = buildChunkAad(attachmentId, chunkIndex, isFinal = isFinal)
                    val (_, cipherBytes) = VaultCryptoEngine.encryptAesGcm(
                        key = fileKey,
                        plaintext = plainSlice,
                        aad = aad,
                        iv = iv
                    )
                    plainSlice.fill(0)

                    dos.writeBoolean(isFinal)
                    dos.writeInt(cipherBytes.size)
                    dos.write(cipherBytes)
                    totalWritten += 1 + 4 + cipherBytes.size

                    if (isFinal) break
                    System.arraycopy(nextBuffer, 0, buffer, 0, nextBytes)
                    pendingBytes = nextBytes
                    chunkIndex++
                }
                buffer.fill(0)
                nextBuffer.fill(0)
            }
            dos.flush()

            val digestHex = sha256.digest().joinToString("") { "%02x".format(it) }
            return EncryptionOutcome(
                totalPlaintextBytes = totalPlain,
                totalCiphertextBytes = totalWritten,
                plaintextSha256Hex = digestHex
            )
        } finally {
            fileKey.fill(0)
        }
    }

    fun decryptStream(
        attachmentMasterKey: ByteArray,
        attachmentId: String,
        input: InputStream,
        output: OutputStream,
        expectedSha256Hex: String? = null
    ): Long {
        require(attachmentMasterKey.size == VaultCryptoEngine.KEY_BYTES) {
            "Attachment master key must be 32 bytes"
        }
        val dis = DataInputStream(input)
        val headerMagic = ByteArray(MAGIC.size)
        val fileSalt = ByteArray(VaultCryptoEngine.SALT_BYTES)
        val noncePrefix = ByteArray(8)
        val declaredChunkSize: Int
        try {
            dis.readFully(headerMagic)
            if (!headerMagic.contentEquals(MAGIC)) {
                throw CryptoAuthException("Invalid encrypted attachment header")
            }
            dis.readFully(fileSalt)
            dis.readFully(noncePrefix)
            declaredChunkSize = dis.readInt()
        } catch (eof: EOFException) {
            throw CryptoAuthException("Encrypted attachment header truncated", eof)
        }
        if (declaredChunkSize <= 0 || declaredChunkSize > 8 * 1024 * 1024) {
            throw CryptoAuthException("Invalid attachment chunk size parameter")
        }

        val fileKey = VaultCryptoEngine.deriveHkdfKey(
            ikm = attachmentMasterKey,
            salt = fileSalt,
            info = "PersonalSpace/v1/attachment/$attachmentId"
        )

        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        var totalPlain = 0L
        var chunkIndex = 0
        var sawFinalChunk = false

        try {
            while (!sawFinalChunk) {
                val isFinal: Boolean
                val cipherBytes: ByteArray
                try {
                    isFinal = dis.readBoolean()
                    val cipherLen = dis.readInt()
                    if (cipherLen < 16 || cipherLen > declaredChunkSize + 64) {
                        throw CryptoAuthException("Corrupted encrypted chunk length: $cipherLen")
                    }
                    cipherBytes = ByteArray(cipherLen)
                    dis.readFully(cipherBytes)
                } catch (eof: EOFException) {
                    throw CryptoAuthException("Encrypted attachment stream truncated before final authenticated chunk", eof)
                }

                val iv = buildChunkNonce(noncePrefix, chunkIndex)
                val aad = buildChunkAad(attachmentId, chunkIndex, isFinal = isFinal)
                val plainBytes = VaultCryptoEngine.decryptAesGcm(
                    key = fileKey,
                    iv = iv,
                    ciphertext = cipherBytes,
                    aad = aad
                )

                if (plainBytes.isNotEmpty()) {
                    sha256.update(plainBytes)
                    output.write(plainBytes)
                    totalPlain += plainBytes.size
                    plainBytes.fill(0)
                }

                sawFinalChunk = isFinal
                chunkIndex++
            }
            output.flush()

            if (expectedSha256Hex != null) {
                val actualHex = sha256.digest().joinToString("") { "%02x".format(it) }
                if (!actualHex.equals(expectedSha256Hex, ignoreCase = true)) {
                    throw CryptoAuthException("Attachment integrity hash mismatch after decryption")
                }
            }
            return totalPlain
        } finally {
            fileKey.fill(0)
        }
    }

    private fun readUpToChunk(input: InputStream, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun buildChunkNonce(prefix8: ByteArray, chunkIndex: Int): ByteArray {
        val buf = ByteBuffer.allocate(VaultCryptoEngine.GCM_IV_BYTES)
        buf.put(prefix8)
        buf.putInt(chunkIndex)
        return buf.array()
    }

    private fun buildChunkAad(attachmentId: String, chunkIndex: Int, isFinal: Boolean): ByteArray {
        val idBytes = attachmentId.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(idBytes.size + 5)
        buf.put(idBytes)
        buf.putInt(chunkIndex)
        buf.put(if (isFinal) 1.toByte() else 0.toByte())
        return buf.array()
    }
}
