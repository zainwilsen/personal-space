package app.personalspace.vault.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.crypto.AttachmentChunkCipher
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.core.security.VaultSessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the note editor's inline images.
 *
 * Images are stored as `note_images/<name>.psenc` using the existing AttachmentChunkCipher
 * (chunked AES-256-GCM) so a restored cross-device vault never owns unreadable files.
 * Notes reference images by bare file name only, which keeps a note portable across devices.
 */
@Singleton
class InlineImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: VaultSessionManager
) {

    companion object {
        const val IMAGE_DIR_NAME = "note_images"
        const val ENCRYPTED_SUFFIX = ".psenc"
        private const val LEGACY_RAW_SUFFIX = ".img"

        /** Images are bounded; anything bigger is not an inline image and is not hashed in memory. */
        private const val MAX_IN_MEMORY_IMAGE_BYTES = 64L * 1024 * 1024

        /**
         * P6: the same normalisation as [normalizeReference], but static, so reference counting can
         * run without an instance (the reference index must work before/independent of the store).
         * Behaviour is identical: absolute legacy paths and bare identifiers both collapse to the
         * bare identifier.
         */
        fun normalizeImageReference(reference: String): String =
            File(reference).name.substringBefore(ENCRYPTED_SUFFIX)
    }

    val imagesDir: File
        get() = File(context.filesDir, IMAGE_DIR_NAME).apply { mkdirs() }

    private val renderCacheDir: File
        get() = File(context.cacheDir, "render_images").apply { mkdirs() }

    /** Stable identifier stored inside the note document. */
    fun identifierOf(fileName: String): String = fileName.substringBefore(ENCRYPTED_SUFFIX)

    /**
     * Accepts a historical absolute path or a bare identifier and returns the bare identifier
     * so a note stays valid on another device.
     */
    fun normalizeReference(reference: String): String = normalizeImageReference(reference)

    /** Small 8-hex prefix used to bind the chunk cipher AAD to this image. */
    fun cipherReference(identifier: String): String = "noteimg_" + identifier.take(8)

    fun encryptedFile(identifier: String): File =
        File(imagesDir, identifier + ENCRYPTED_SUFFIX)

    private fun legacyRawFile(identifier: String): File =
        File(imagesDir, identifier + LEGACY_RAW_SUFFIX)

    fun exists(identifier: String): Boolean = encryptedFile(identifier).exists()

    /**
     * P8: decrypts an image and returns (plaintext sha256 hex, plaintext byte count) without writing
     * a file anywhere. Used by the deep verifier to prove a restored image still holds the same bytes
     * as the one that was backed up.
     */
    suspend fun plaintextDigest(identifier: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val encrypted = encryptedFile(identifier)
        if (!encrypted.exists()) return@withContext null
        if (encrypted.length() > MAX_IN_MEMORY_IMAGE_BYTES) return@withContext null
        val key = sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
        val sink = java.io.ByteArrayOutputStream()
        try {
            val size = AttachmentChunkCipher.decryptStream(
                attachmentMasterKey = key,
                attachmentId = cipherReference(identifier),
                input = FileInputStream(encrypted).buffered(),
                output = sink
            )
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(sink.toByteArray())
            sink.reset()
            digest.digest().joinToString("") { "%02x".format(it) } to size
        } catch (e: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }


    /** True for files this store owns (used by the stats screen byte totals). */
    fun allImageFiles(): List<File> =
        imagesDir.listFiles()?.filter { it.isFile && it.length() > 0L }?.sortedBy { it.name }.orEmpty()

    suspend fun importFromStream(
        input: InputStream,
        sourceLabel: String = ""
    ): String = withContext(Dispatchers.IO) {
        val key = sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
        val identifier = "note_img_" + IdGenerator.newUuid()
        val target = encryptedFile(identifier)
        val tmp = File(imagesDir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).buffered().use { output ->
                AttachmentChunkCipher.encryptStream(
                    attachmentMasterKey = key,
                    attachmentId = cipherReference(identifier),
                    input = input,
                    output = output
                )
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            identifier
        } catch (e: Exception) {
            tmp.delete()
            target.delete()
            throw e
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts an image into the private cache so Image decoders can read it. Legacy plaintext
     * files are migrated to encrypted storage on first touch.
     */
    suspend fun resolveForDisplay(reference: String): File? = withContext(Dispatchers.IO) {
        val identifier = normalizeReference(reference)
        if (identifier.isBlank()) return@withContext null

        val legacy = legacyRawFile(identifier)
        if (!exists(identifier) && legacy.exists() && legacy.length() > 0L) {
            encryptIfLegacy(legacy)
        }

        val encrypted = encryptedFile(identifier)
        if (!encrypted.exists()) return@withContext if (legacy.exists()) legacy else null

        val cached = File(renderCacheDir, identifier + ".img")
        if (cached.exists() && cached.length() > 0L && cached.lastModified() >= encrypted.lastModified()) {
            return@withContext cached
        }
        runCatching {
            val key = sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
            try {
                val tmp = File(renderCacheDir, cached.name + ".tmp")
                FileInputStream(encrypted).buffered().use { input ->
                    FileOutputStream(tmp).buffered().use { output ->
                        AttachmentChunkCipher.decryptStream(
                            attachmentMasterKey = key,
                            attachmentId = cipherReference(identifier),
                            input = input,
                            output = output
                        )
                    }
                }
                if (!tmp.renameTo(cached)) {
                    tmp.copyTo(cached, overwrite = true)
                    tmp.delete()
                }
            } finally {
                key.fill(0)
            }
            cached
        }.getOrElse { if (legacy.exists()) legacy else null }
    }

    /** Width/height of an image for compact backup metadata (0 when unreadable). */
    fun dimensionsOf(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        return opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    }

    /** Copies the raw encrypted bytes out for packaging. */
    fun openEncryptedStream(identifier: String): InputStream? {
        val file = encryptedFile(identifier)
        return if (file.exists() && file.length() > 0L) FileInputStream(file).buffered() else null
    }

    /**
     * Writes restored or migrated image bytes with the local vault key. Source bytes must either be
     * this vault's ciphertext (same device) or plaintext (cross-device / legacy plaintext).
     */
    suspend fun writeRestoredImage(
        identifier: String,
        ciphertext: ByteArray,
        sourceIsPlaintext: Boolean,
        sourceKey: ByteArray?,
        /**
         * P4: when non-null, the image is written into this directory instead of the live
         * `note_images/` folder. Restore staging MUST pass a directory here so a failed restore can
         * never mutate the live vault (see P4 report). Defaults to null to keep every other caller's
         * behaviour byte-identical.
         */
        targetDir: File? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val outputDir = targetDir ?: imagesDir
        outputDir.mkdirs()
        val target = File(outputDir, identifier + ENCRYPTED_SUFFIX)
        val tmp = File(outputDir, target.name + ".tmp")
        val key = sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
        try {
            if (sourceIsPlaintext) {
                FileOutputStream(tmp).buffered().use { output ->
                    AttachmentChunkCipher.encryptStream(
                        attachmentMasterKey = key,
                        attachmentId = cipherReference(identifier),
                        input = ciphertext.inputStream(),
                        output = output
                    )
                }
            } else if (sourceKey != null && sourceKey.contentEquals(key)) {
                tmp.writeBytes(ciphertext)
            } else if (sourceKey != null) {
                val plainTmp = File(context.cacheDir, "img_rekey_" + IdGenerator.newUuid() + ".bin")
                try {
                    FileOutputStream(plainTmp).buffered().use { output ->
                        AttachmentChunkCipher.decryptStream(
                            attachmentMasterKey = sourceKey,
                            attachmentId = cipherReference(identifier),
                            input = ciphertext.inputStream(),
                            output = output
                        )
                    }
                    FileOutputStream(tmp).buffered().use { output ->
                        AttachmentChunkCipher.encryptStream(
                            attachmentMasterKey = key,
                            attachmentId = cipherReference(identifier),
                            input = FileInputStream(plainTmp).buffered(),
                            output = output
                        )
                    }
                } finally {
                    plainTmp.delete()
                }
            } else {
                return@withContext false
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            tmp.delete()
            // P4: only remove a file we just created in the staging directory. When writing straight
            // into the live folder (legacy callers) keep the old behaviour, but never delete a
            // pre-existing live image that this call did not create.
            if (targetDir != null) runCatching { target.delete() }
            false
        } finally {
            key.fill(0)
        }
    }

    fun deleteImage(identifier: String) {
        runCatching { encryptedFile(identifier).delete() }
        runCatching { legacyRawFile(identifier).delete() }
        runCatching { File(renderCacheDir, identifier + ".img").delete() }
    }

    fun purgeRenderCache() {
        renderCacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun encryptIfLegacy(file: File) {
        if (!file.exists() || file.name.endsWith(ENCRYPTED_SUFFIX)) return
        sessionManager.currentSubKeysOrNull() ?: return
        runCatching {
            val identifier = normalizeReference(file.name)
            FileInputStream(file).buffered().use { input ->
                val key = sessionManager.requireSubKeys().attachmentMasterKey.copyOf()
                try {
                    val tmp = File(imagesDir, encryptedFile(identifier).name + ".tmp")
                    FileOutputStream(tmp).buffered().use { output ->
                        AttachmentChunkCipher.encryptStream(
                            attachmentMasterKey = key,
                            attachmentId = cipherReference(identifier),
                            input = input,
                            output = output
                        )
                    }
                    if (!tmp.renameTo(encryptedFile(identifier))) {
                        tmp.copyTo(encryptedFile(identifier), overwrite = true)
                        tmp.delete()
                    }
                } finally {
                    key.fill(0)
                }
            }
            file.delete()
        }
    }

    fun sha256HexOf(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun plaintextSha256Hex(bytes: ByteArray): String =
        VaultCryptoEngine.sha256Hex(bytes)
}
