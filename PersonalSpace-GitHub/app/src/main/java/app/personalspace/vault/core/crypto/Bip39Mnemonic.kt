package app.personalspace.vault.core.crypto

import android.content.Context
import app.personalspace.vault.R
import java.io.File
import java.security.MessageDigest

class InvalidRecoveryPhraseException(message: String) : IllegalArgumentException(message)

/**
 * Standard 24-word BIP-39 recovery key implementation (256-bit entropy + 8-bit SHA-256 checksum).
 * Validates every word against the canonical 2,048-word English dictionary and verifies checksum.
 */
class Bip39Mnemonic(private val wordList: List<String>) {

    init {
        require(wordList.size == 2048) {
            "BIP-39 English dictionary must contain exactly 2048 words, got ${wordList.size}"
        }
    }

    private val wordIndexMap: Map<String, Int> =
        wordList.withIndex().associate { (idx, w) -> w.lowercase() to idx }

    /**
     * Generates a random 256-bit secret and encodes it into a 24-word BIP-39 phrase.
     */
    fun generate24WordRecoveryKey(
        entropy256: ByteArray = VaultCryptoEngine.randomBytes(32)
    ): Pair<List<String>, ByteArray> {
        require(entropy256.size == 32) { "24-word BIP-39 phrase requires 32 bytes (256 bits) of entropy" }
        val checksumByte = MessageDigest.getInstance("SHA-256").digest(entropy256)[0].toInt() and 0xFF

        // Build 264-bit array (256 entropy bits + 8 checksum bits)
        val bits = BooleanArray(264)
        for (byteIdx in 0 until 32) {
            val b = entropy256[byteIdx].toInt() and 0xFF
            for (bit in 0 until 8) {
                bits[byteIdx * 8 + bit] = ((b ushr (7 - bit)) and 1) == 1
            }
        }
        for (bit in 0 until 8) {
            bits[256 + bit] = ((checksumByte ushr (7 - bit)) and 1) == 1
        }

        val words = ArrayList<String>(24)
        for (wIdx in 0 until 24) {
            var index = 0
            for (bit in 0 until 11) {
                index = (index shl 1) or if (bits[wIdx * 11 + bit]) 1 else 0
            }
            words.add(wordList[index])
        }
        return words to entropy256.copyOf()
    }

    /**
     * Validates a 24-word BIP-39 phrase and extracts the underlying 32-byte (256-bit) entropy.
     */
    fun decodeAndValidate24WordPhrase(rawPhrase: String): ByteArray {
        val tokens = rawPhrase
            .trim()
            .lowercase()
            .split(Regex("[\\s,\\n\\r\\t]+"))
            .filter { it.isNotBlank() }

        if (tokens.size != 24) {
            throw InvalidRecoveryPhraseException(
                "Recovery Key must contain exactly 24 words (entered ${tokens.size})."
            )
        }

        val bits = BooleanArray(264)
        tokens.forEachIndexed { pos, word ->
            val dictIndex = wordIndexMap[word]
                ?: throw InvalidRecoveryPhraseException(
                    "Word #${pos + 1} ('$word') is not a valid recovery dictionary word."
                )
            for (bit in 0 until 11) {
                bits[pos * 11 + bit] = ((dictIndex ushr (10 - bit)) and 1) == 1
            }
        }

        val entropy = ByteArray(32)
        for (byteIdx in 0 until 32) {
            var value = 0
            for (bit in 0 until 8) {
                value = (value shl 1) or if (bits[byteIdx * 8 + bit]) 1 else 0
            }
            entropy[byteIdx] = value.toByte()
        }

        var suppliedChecksum = 0
        for (bit in 0 until 8) {
            suppliedChecksum = (suppliedChecksum shl 1) or if (bits[256 + bit]) 1 else 0
        }

        val expectedChecksum =
            MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() and 0xFF
        if (suppliedChecksum != expectedChecksum) {
            entropy.fill(0)
            throw InvalidRecoveryPhraseException(
                "Recovery Key checksum verification failed. Please double-check word order and spelling."
            )
        }

        return entropy
    }

    companion object {
        fun fromContext(context: Context): Bip39Mnemonic {
            val words = context.resources.openRawResource(R.raw.bip39_english)
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
            return Bip39Mnemonic(words)
        }

        fun forTestingFromResourceFile(
            file: File = File("src/main/res/raw/bip39_english.txt")
        ): Bip39Mnemonic {
            val candidates = listOf(
                file,
                File("app/src/main/res/raw/bip39_english.txt"),
                File("/home/user/app/src/main/res/raw/bip39_english.txt")
            )
            val resolved = candidates.firstOrNull { it.exists() }
                ?: error("Could not locate bip39_english.txt for unit tests")
            val words = resolved.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
            return Bip39Mnemonic(words)
        }
    }
}
