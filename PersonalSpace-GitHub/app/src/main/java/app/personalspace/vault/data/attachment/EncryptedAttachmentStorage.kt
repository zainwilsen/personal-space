package app.personalspace.vault.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.crypto.AttachmentChunkCipher
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.domain.model.AttachmentItem
import app.personalspace.vault.domain.model.AttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedFileImportResult(
    val attachmentId: String,
    val originalFileName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val plaintextSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val encryptedRelativePath: String,
    val plaintextSha256Hex: String
)

/**
 * Manages encrypted-at-rest attachment files using AttachmentChunkCipher (64 KiB AES-256-GCM chunks).
 * Never stores plaintext permanently on disk; ephemeral viewer/share files live strictly in
 * private internal cache (`cacheDir/ephemeral_share`) and are purged on lock and startup.
 */
@Singleton
class EncryptedAttachmentStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: VaultSessionManager
) {
    val encryptedDir: File
        get() = File(context.filesDir, "encrypted_attachments").apply { mkdirs() }

    private val ephemeralShareDir: File
        get() = File(context.cacheDir, "ephemeral_share").apply { mkdirs() }

    suspend fun importFromUri(uri: Uri): EncryptedFileImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: "attachment_${System.currentTimeMillis()}"
        val mimeType = resolver.getType(uri) ?: guessMimeTypeFromName(displayName)

        resolver.openInputStream(uri)?.use { input ->
            importFromStream(
                input = input,
                originalFileName = displayName,
                mimeType = mimeType
            )
        } ?: throw IllegalStateException("Unable to read selected attachment URI.")
    }

    suspend fun importFromStream(
        input: InputStream,
        originalFileName: String,
        mimeType: String,
        attachmentId: String = IdGenerator.newUuid()
    ): EncryptedFileImportResult = withContext(Dispatchers.IO) {
        val masterKey = sessionManager.requireSubKeys().attachmentMasterKey
        val relativeName = "$attachmentId.psenc"
        val destFile = File(encryptedDir, relativeName)
        val tmpFile = File(encryptedDir, "$relativeName.tmp")

        try {
            val outcome = FileOutputStream(tmpFile).buffered().use { output ->
                AttachmentChunkCipher.encryptStream(
                    attachmentMasterKey = masterKey,
                    attachmentId = attachmentId,
                    input = input.buffered(),
                    output = output
                )
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            EncryptedFileImportResult(
                attachmentId = attachmentId,
                originalFileName = originalFileName,
                mimeType = mimeType,
                kind = AttachmentKind.fromMimeType(mimeType, originalFileName),
                plaintextSizeBytes = outcome.totalPlaintextBytes,
                encryptedSizeBytes = outcome.totalCiphertextBytes,
                encryptedRelativePath = relativeName,
                plaintextSha256Hex = outcome.plaintextSha256Hex
            )
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    suspend fun decryptToOutputStream(
        attachment: AttachmentItem,
        output: OutputStream,
        attachmentMasterKey: ByteArray = sessionManager.requireSubKeys().attachmentMasterKey
    ): Long = withContext(Dispatchers.IO) {
        val encFile = resolveEncryptedFile(attachment.encryptedRelativePath)
        if (!encFile.exists()) {
            throw IllegalStateException("Encrypted attachment file '${attachment.originalFileName}' is missing on disk.")
        }
        FileInputStream(encFile).buffered().use { input ->
            AttachmentChunkCipher.decryptStream(
                attachmentMasterKey = attachmentMasterKey,
                attachmentId = attachment.id,
                input = input,
                output = output,
                expectedSha256Hex = attachment.plaintextSha256Hex
            )
        }
    }

    /**
     * Decrypts an attachment to a short-lived private cache file ONLY when needed for
     * in-app PdfRenderer/MediaPlayer or user-requested system share, verifying SHA-256 integrity first.
     */
    suspend fun decryptToEphemeralCacheFile(attachment: AttachmentItem): File = withContext(Dispatchers.IO) {
        purgeStaleEphemeralFiles(maxAgeMillis = 15 * 60 * 1000L)
        val safeName = attachment.originalFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val target = File(ephemeralShareDir, "${attachment.id.take(8)}_$safeName")
        FileOutputStream(target).buffered().use { out ->
            decryptToOutputStream(attachment, out)
        }
        target
    }

    fun getShareableUriForEphemeralFile(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun resolveEncryptedFile(relativePath: String): File = File(encryptedDir, File(relativePath).name)

    fun deleteEncryptedFile(relativePath: String) {
        val file = resolveEncryptedFile(relativePath)
        if (file.exists()) {
            // Overwrite header bytes before unlinking for defense-in-depth
            runCatching {
                FileOutputStream(file).use { it.write(ByteArray(64)) }
            }
            file.delete()
        }
    }

    /**
     * P6: like [deleteEncryptedFile], but **reports failure instead of swallowing it**.
     *
     * `File.delete()` returns false when the unlink does not happen (missing permission, the path is
     * a directory, a handle still open on some filesystems). The lenient version above ignores that,
     * which is fine for best-effort cache cleanup but not for a permanent deletion: there the caller
     * must not remove the database row if the ciphertext is still on disk, otherwise the user is left
     * with bytes nothing can reach.
     *
     * A file that is already gone counts as success (idempotent).
     *
     * @throws IllegalStateException when the file exists and could not be removed.
     */
    fun deleteEncryptedFileStrict(relativePath: String) {
        val file = resolveEncryptedFile(relativePath)
        if (!file.exists()) return
        runCatching {
            FileOutputStream(file).use { it.write(ByteArray(64)) }
        }
        val removed = file.delete()
        if (!removed && file.exists()) {
            throw IllegalStateException(
                "Could not remove the encrypted attachment file '${file.name}'. Nothing else was deleted — " +
                    "the attachment is still in the recycle bin and can be retried."
            )
        }
    }

    fun purgeAllEphemeralFiles() {
        ephemeralShareDir.listFiles()?.forEach { it.delete() }
    }

    fun purgeStaleEphemeralFiles(maxAgeMillis: Long = 5 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        ephemeralShareDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun computeTotalEncryptedAttachmentsBytes(): Long =
        encryptedDir.listFiles()?.filter { it.isFile && it.name.endsWith(".psenc") }
            ?.sumOf { it.length() } ?: 0L

    fun wipeAllEncryptedAttachments() {
        purgeAllEphemeralFiles()
        encryptedDir.listFiles()?.forEach { it.delete() }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun guessMimeTypeFromName(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
