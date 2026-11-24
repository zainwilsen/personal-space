package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.Argon2KdfConfig
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.data.backup.BackupPackageManifest
import app.personalspace.vault.data.backup.CorruptedBackupException
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.data.backup.RestoreRecovery
import app.personalspace.vault.data.backup.RestoreSnapshot
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * P4 regression suite — staging isolation.
 *
 * Binding claims proven here with real files and real Room rows (nothing mocked):
 *
 *   P4-a  staging never writes into a live vault directory (`files/note_images/`,
 *         `files/attachments_encrypted/`), not even transiently;
 *   P4-b  a restore that dies inside staging leaves the live vault byte-identical and leaves no
 *         journal, no rollback copy and no staging leftovers behind;
 *   P4-c  a restore that dies AFTER the images were installed still recovers the pre-restore image
 *         directory exactly — in-process (journal rollback) and after process death (P3 startup
 *         recovery from a fresh process), asserted on plaintext hashes of every image;
 *   P4-d  a failed staging/restore never modifies a previous valid backup file.
 *
 * Evidence: /home/user/verification/evidence/p4_*.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagingIsolationTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("P4| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- fingerprints

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** name -> sha256 of every regular file directly inside [dir]. */
    private fun fingerprint(dir: File): Map<String, String> =
        if (!dir.isDirectory) emptyMap()
        else dir.listFiles()!!.filter { it.isFile }.associate { it.name to sha256(it) }.toSortedMap()

    private fun names(dir: File): List<String> =
        dir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    private fun copyTree(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = File(dst, f.relativeTo(src).path)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
    }

    /**
     * WHAT THE USER WOULD ACTUALLY LOSE: row values + the *plaintext* hash of every attachment and
     * inline image (decrypted through the vault key). Byte-identical DB files are not a valid
     * expectation — SQLite rewrites the main file when a connection is reopened (measured in P3).
     */
    private suspend fun contentFingerprint(rig: IsolatedVaultTestRig): Map<String, String> {
        val out = sortedMapOf<String, String>()
        val db = rig.dbHolder.getDatabase()
        db.recordDao().getAllRecordsIncludingDeleted().sortedBy { it.id }.forEach {
            out["record/${it.id}"] = sha256Bytes(
                listOf(
                    it.title, it.description, it.folderId ?: "", it.category ?: "",
                    it.isDeleted.toString(), it.createdAt.toString(), it.modifiedAt.toString(),
                    it.currentVersionNumber.toString(), it.syncVersion.toString()
                ).joinToString("\u0001").toByteArray()
            )
        }
        db.folderDao().getAllFoldersIncludingDeleted().sortedBy { it.id }.forEach {
            out["folder/${it.id}"] = sha256Bytes(
                listOf(it.name, it.parentFolderId ?: "", it.isDeleted.toString())
                    .joinToString("\u0001").toByteArray()
            )
        }
        db.attachmentDao().getAllAttachmentsIncludingDeleted().sortedBy { it.id }.forEach { entity ->
            val plain = ByteArrayOutputStream()
            runCatching {
                rig.repository.getAttachmentById(entity.id)
                    ?.let { rig.repository.exportAttachmentToStream(it.id, plain) }
            }
            out["attachment/${entity.id}"] =
                sha256Bytes(plain.toByteArray()) + ":" + entity.originalFileName
        }
        rig.inlineImageStore.imagesDir.listFiles()?.filter { it.isFile }
            ?.sortedBy { it.name }?.forEach { file ->
                val identifier = rig.inlineImageStore.identifierOf(file.name)
                val plain = runCatching {
                    rig.inlineImageStore.openEncryptedStream(identifier)?.use { it.readBytes() }
                }.getOrNull()
                out["image/$identifier"] =
                    if (plain == null) "UNDECRYPTABLE" else sha256Bytes(plain)
            }
        return out
    }

    // ---------------------------------------------------------------- seed

    private data class Seeded(
        val recordId: String,
        val image: String,
        val liveImages: Map<String, String>,
        val liveAttachments: Map<String, String>,
        val content: Map<String, String>,
        val firstBackup: File
    )

    private suspend fun seed(rig: IsolatedVaultTestRig): Seeded {
        rig.initializeAndUnlockVault(PASSWORD)
        val folder = rig.repository.createFolder(
            "Archive", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE
        )
        val record = rig.repository.saveRecord(
            null, "Precious", "text that must survive", folder.id, "Cat", emptyList()
        )
        rig.repository.importAttachmentFromStream(
            record.id, "PAYLOAD-1".toByteArray().inputStream(), "keep.txt", "text/plain"
        )
        val image = rig.inlineImageStore.importFromStream(
            byteArrayOf(11, 22, 33, 44, 55).inputStream(), "keep.png"
        )
        // images must be REFERENCED by live notes: packages only carry referenced images
        val image2 = rig.inlineImageStore.importFromStream(
            byteArrayOf(21, 22, 23, 24).inputStream(), "keep2.png"
        )
        val image3 = rig.inlineImageStore.importFromStream(
            byteArrayOf(31, 32, 33, 34).inputStream(), "keep3.png"
        )
        rig.repository.saveRecord(
            null, "Illustrated", withImage(image, "body"), folder.id, null, emptyList()
        )
        rig.repository.saveRecord(
            null, "Illustrated2", withImage(image2, "two"), folder.id, null, emptyList()
        )
        rig.repository.saveRecord(
            null, "Illustrated3", withImage(image3, "three"), folder.id, null, emptyList()
        )
        rig.repository.saveRecord(null, "Second", "another body", folder.id, null, emptyList())

        val previous = rig.backupManager
            .createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
        return Seeded(
            recordId = record.id,
            image = image,
            liveImages = fingerprint(rig.inlineImageStore.imagesDir),
            liveAttachments = fingerprint(rig.attachmentStorage.encryptedDir),
            content = contentFingerprint(rig),
            firstBackup = previous
        )
    }

    /** A note body that references [imageId] as a rich-note image block. */
    private fun withImage(imageId: String, text: String = "see image"): String =
        app.personalspace.vault.presentation.records.RichNoteCodec.encodeBlocks(
            listOf(
                app.personalspace.vault.presentation.records.RichNoteBlock.Text(text = text),
                app.personalspace.vault.presentation.records.RichNoteBlock.Image(imagePath = imageId)
            )
        )

    // ---------------------------------------------------------------- package tampering

    /**
     * Rebuilds [genuine] into [target] with `rewrite` applied to every entry's bytes (null = keep).
     * The manifest is re-signed over the *result*, so the package stays cryptographically
     * self-consistent: whatever checksum bookkeeping the manifest does, it matches the payload.
     * Entries are STORED with a real CRC so the zip is never structurally corrupt.
     */
    private fun rewritePackage(
        genuine: File,
        target: File,
        rewrite: (name: String, bytes: ByteArray) -> ByteArray?,
        editManifest: (BackupPackageManifest) -> BackupPackageManifest = { it }
    ): File {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(genuine).use { z ->
            z.entries().asSequence().forEach { e ->
                val bytes = z.getInputStream(e).readBytes()
                entries[e.name] = if (e.name == "manifest.json") bytes else (rewrite(e.name, bytes) ?: bytes)
            }
        }
        val parsed = json.decodeFromString(
            BackupPackageManifest.serializer(), entries.getValue("manifest.json").decodeToString()
        )
        // recompute every declared hash/size from the rewritten payload, then optionally edit fields
        val rehashed = parsed.copy(
            attachments = parsed.attachments.mapNotNull { ref ->
                val bytes = entries[ref.relativeZipEntry] ?: return@mapNotNull null
                ref.copy(encryptedSizeBytes = bytes.size.toLong(), encryptedSha256Hex = sha256Bytes(bytes))
            },
            inlineImages = parsed.inlineImages.mapNotNull { ref ->
                val bytes = entries[ref.relativeZipEntry] ?: return@mapNotNull null
                ref.copy(encryptedSizeBytes = bytes.size.toLong(), encryptedSha256Hex = sha256Bytes(bytes))
            },
            encryptedSnapshotSha256Hex = sha256Bytes(entries.getValue("vault_snapshot.enc")),
            manifestHmacBase64 = ""
        )
        val edited = editManifest(rehashed)
        val canonical = json.encodeToString(BackupPackageManifest.serializer(), edited)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(manifestKey(genuine), "HmacSHA256"))
        val signed = edited.copy(
            manifestHmacBase64 = VaultCryptoEngine.toBase64(mac.doFinal(canonical.toByteArray()))
        )

        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zos.putNextEntry(entry); zos.write(bytes); zos.closeEntry()
            }
            put("manifest.json", json.encodeToString(BackupPackageManifest.serializer(), signed).toByteArray())
            entries.forEach { (name, bytes) -> if (name != "manifest.json") put(name, bytes) }
        }
        return target
    }

    /** Manifest HMAC key of the package's own vault, unwrapped from its portable key wrap. */
    private fun manifestKey(genuine: File): ByteArray {
        val root = Json.parseToJsonElement(
            ZipFile(genuine).use { z ->
                z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()
            }
        ).jsonObject
        val vaultId = root["vaultId"]!!.jsonPrimitive.content
        val wrap = root["portableKeyWrap"]!!.jsonObject
        val argon = wrap["argon2Config"]!!.jsonObject
        val wrapKey = VaultCryptoEngine.derivePasswordWrappingKey(
            ("psbackup-portable:" + vaultId).toCharArray(),
            VaultCryptoEngine.fromBase64(wrap["saltBase64"]!!.jsonPrimitive.content),
            Argon2KdfConfig(
                argon["memoryKiB"]!!.jsonPrimitive.content.toInt(),
                argon["iterations"]!!.jsonPrimitive.content.toInt(),
                argon["parallelism"]!!.jsonPrimitive.content.toInt()
            )
        )
        val vdk = VaultCryptoEngine.decryptAesGcm(
            wrapKey,
            VaultCryptoEngine.fromBase64(wrap["ivBase64"]!!.jsonPrimitive.content),
            VaultCryptoEngine.fromBase64(wrap["ciphertextBase64"]!!.jsonPrimitive.content),
            ("psbackup-portable-aad:" + vaultId).toByteArray()
        )
        wrapKey.fill(0)
        return VaultCryptoEngine.deriveSubKeys(vdk, vaultId).backupManifestKey
    }

    /**
     * A package that passes every package-level check but whose inline image cannot be decrypted, so
     * the failure happens INSIDE staging (after earlier images were already staged).
     */
    /**
     * Builds a package in a SEPARATE vault and copies it next to [rig], so restoring it into [rig]
     * exercises the cross-vault code path (every image is really decrypted and re-encrypted).
     *
     * This matters: for a same-vault package staging copies ciphertext verbatim and never decrypts,
     * so a byte-level image corruption cannot be detected there by design (the manifest HMAC covers
     * the ciphertext, and only the vault key holder can re-sign it).
     */
    private suspend fun foreignPackage(rig: IsolatedVaultTestRig): File {
        val other = IsolatedVaultTestRig(
            ApplicationProvider.getApplicationContext(), "p4_foreign_${System.nanoTime()}"
        )
        try {
            other.initializeAndUnlockVault(FOREIGN_PASSWORD)
            repeat(3) { index ->
                val img = other.inlineImageStore.importFromStream(
                    byteArrayOf(90, 91, 92, 93, index.toByte()).inputStream(), "foreign$index.png"
                )
                other.repository.saveRecord(
                    null, "Foreign$index", withImage(img, "foreign body $index"), null, null, emptyList()
                )
            }
            val produced = other.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL,
                targetFile = File(other.context.cacheDir, "foreign.psbackup"),
                recordHistoryInDb = false
            ).file
            // copy it out of the rig directory, which is deleted when the rig closes
            val copy = File(rig.context.cacheDir, "foreign_${System.nanoTime()}.psbackup")
            produced.copyTo(copy, overwrite = true)
            note("foreign package built in another vault: ${copy.name} (${copy.length()} B)")
            return copy
        } finally {
            other.close()
        }
    }

    /** Corrupts exactly one inline image (never the first) of an otherwise valid package. */
    private fun packageWhoseImageFailsToDecrypt(rig: IsolatedVaultTestRig, genuine: File): File {
        val imageNames = ZipFile(genuine).use { z ->
            z.entries().asSequence().map { it.name }
                .filter { it.startsWith("inline_images/") }.sorted().toList()
        }
        val victim = imageNames.getOrNull(1) ?: throw IllegalStateException("need >= 2 inline images")
        note("corrupting inline image '$victim' (index 1 of ${imageNames.size})")
        return rewritePackage(
            genuine,
            File(rig.context.cacheDir, "p4_bad_image_${System.nanoTime()}.psbackup"),
            rewrite = { name, bytes ->
                if (name != victim) null else bytes.copyOf().also { damaged ->
                    // corrupt the ciphertext body, leaving structure intact
                    for (i in 16 until minOf(damaged.size, 48)) {
                        damaged[i] = (damaged[i].toInt() xor 0x5A).toByte()
                    }
                }
            }
        )
    }

    /**
     * A restore that dies inside staging after some images were staged: the last inline-image entry
     * is removed from the zip while the manifest still declares it (its hash/size are unchanged), so
     * the package fails only when staging asks for the missing entry.
     */
    private fun packageMissingLastImage(rig: IsolatedVaultTestRig, genuine: File): File {
        val lastImage = ZipFile(genuine).use { z ->
            z.entries().asSequence().map { it.name }
                .filter { it.startsWith("inline_images/") }.sorted().lastOrNull()
        } ?: throw IllegalStateException("seed vault must contain inline images")
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(genuine).use { z ->
            z.entries().asSequence().filter { it.name != lastImage }.forEach { e ->
                entries[e.name] = z.getInputStream(e).readBytes()
            }
        }
        val out = File(rig.context.cacheDir, "p4_missing_image_${System.nanoTime()}.psbackup")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zos.putNextEntry(entry); zos.write(bytes); zos.closeEntry()
            }
        }
        note("dropped payload entry '$lastImage' from ${out.name}")
        return out
    }

    /** Cryptographically valid package whose declared draft count cannot match the payload. */
    private fun resignWithWrongDraftCount(rig: IsolatedVaultTestRig, genuine: File): File =
        rewritePackage(
            genuine,
            File(rig.context.cacheDir, "p4_wrong_drafts_${System.nanoTime()}.psbackup"),
            rewrite = { _, _ -> null },
            editManifest = { it.copy(draftCount = it.draftCount + 7) }
        )

    // ---------------------------------------------------------------- P4-a / P4-b

    /**
     * A restore that fails during staging must not even *open* a live vault directory for writing:
     * contents AND directory timestamps of both live media dirs are unchanged.
     */
    @Test
    fun `A staging failure never writes to a live vault directory`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_a")
        try {
            val seeded = seed(rig)
            val genuine = rig.backupManager
                .createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            val broken = packageMissingLastImage(rig, genuine)

            val imagesDir = rig.inlineImageStore.imagesDir
            val attachmentsDir = rig.attachmentStorage.encryptedDir
            val imagesMtime = imagesDir.lastModified()
            val attachmentsMtime = attachmentsDir.lastModified()
            note("live images before      = ${names(imagesDir)}")
            note("live attachments before = ${names(attachmentsDir)}")
            note("imagesDir mtime before  = $imagesMtime")
            note("attachmentsDir mtime    = $attachmentsMtime")

            val outcome = runCatching {
                rig.backupManager.restoreFromBackupFile(broken, PASSWORD.toCharArray())
            }
            val ex = outcome.exceptionOrNull()
            note("restore outcome = " +
                (ex?.let { it.javaClass.simpleName + ": " + it.message } ?: "SUCCESS (unexpected)"))
            assertNotNull("staging must fail", ex)
            assertTrue("failure must come from staging", ex is CorruptedBackupException)

            note("live images after       = ${names(imagesDir)}")
            note("live attachments after  = ${names(attachmentsDir)}")
            note("imagesDir mtime after   = ${imagesDir.lastModified()}")
            note("attachmentsDir mtime    = ${attachmentsDir.lastModified()}")

            assertEquals("live images must be byte-identical", seeded.liveImages, fingerprint(imagesDir))
            assertEquals(
                "live attachments must be byte-identical",
                seeded.liveAttachments, fingerprint(attachmentsDir)
            )
            assertEquals(
                "the live image directory must not even be touched",
                imagesMtime, imagesDir.lastModified()
            )
            assertEquals(
                "the live attachment directory must not even be touched",
                attachmentsMtime, attachmentsDir.lastModified()
            )

            val filesDirNames = rig.context.filesDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            note("filesDir after failure = $filesDirNames")
            val cacheNamesAfter: List<String> =
                rig.context.cacheDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            note("cacheDir after failure = $cacheNamesAfter")
            assertNull("no journal must be left behind", RestoreJournal.read(rig.context))
            assertTrue(
                "no rollback copies",
                filesDirNames.none { it.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }
            )
            assertTrue(
                "no image previous-dir orphans",
                filesDirNames.none { it.startsWith("note_images_previous_") }
            )
            assertTrue(
                "staging directory must be cleaned up",
                cacheNamesAfter.none { it.startsWith("restore_stage_") }
            )

            assertEquals("row content must be identical", seeded.content, contentFingerprint(rig))
            assertEquals(
                "previous backup must be untouched",
                mapOf(genuine.name to sha256(genuine)),
                mapOf(seeded.firstBackup.name to sha256(seeded.firstBackup))
            )
            assertTrue("image still decryptable", rig.inlineImageStore.exists(seeded.image))
            assertNotNull("note still readable", rig.repository.getRecordById(seeded.recordId))
            dump("p4_A_staging_failure_live_untouched")
        } finally { rig.close() }
    }

    /**
     * Positive control: during a failed staging run the images must be flowing into the CACHE, and
     * the live directory must only ever change after the rollback copy exists on disk (SNAPSHOT_TAKEN).
     */
    @Test
    fun `B staged images are produced in cache and the live dir only changes after the rollback copy exists`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_b")
            try {
                val seeded = seed(rig)
                val genuine = rig.backupManager
                    .createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
                val broken = packageMissingLastImage(rig, genuine)
                val imagesDir = rig.inlineImageStore.imagesDir
                val liveNamesBefore = names(imagesDir)
                note("live images before restore = $liveNamesBefore")
                note("staged image identifiers in the package = " +
                    rig.backupManager.inspectAndValidatePackageStructure(genuine)
                        .inlineImages.map { it.identifier })

                val outcome = runCatching {
                    rig.backupManager.restoreFromBackupFile(broken, PASSWORD.toCharArray())
                }
                note("restore outcome = " +
                    (outcome.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }
                        ?: "SUCCESS (unexpected)"))
                assertNotNull("restore must fail during staging", outcome.exceptionOrNull())
                assertEquals(
                    "staging must never touch the live image directory",
                    liveNamesBefore, names(imagesDir)
                )
                assertEquals(
                    "live image files must be byte identical",
                    seeded.liveImages, fingerprint(imagesDir)
                )
                assertTrue(
                    "the staging folder must be removed again",
                    (rig.context.cacheDir.listFiles()?.map { it.name } ?: emptyList())
                        .none { it.startsWith("restore_stage_") }
                )

                // ---- and with a HEALTHY package the staged images land in the live dir only via the
                // swap, i.e. after the rollback copy exists. Proven by the journal phase sequence.
                val phaseLog = java.util.Collections.synchronizedList(mutableListOf<String>())
                var liveChangedBeforeSnapshot = false
                var snapshotSeen = false
                val watcher = Thread {
                    val deadline = System.currentTimeMillis() + 20_000
                    while (System.currentTimeMillis() < deadline) {
                        val journal = RestoreJournal.read(rig.context)
                        val liveNow = names(imagesDir)
                        if (journal != null) {
                            if (phaseLog.isEmpty() || phaseLog.last() != journal.phase.name) {
                                phaseLog.add(journal.phase.name)
                            }
                            if (journal.phase.ordinal >= RestorePhase.SNAPSHOT_TAKEN.ordinal) {
                                snapshotSeen = true
                            }
                        }
                        if (!snapshotSeen && liveNow != liveNamesBefore) {
                            liveChangedBeforeSnapshot = true
                        }
                        Thread.sleep(1)
                    }
                }.apply { isDaemon = true; start() }

                val result = rig.backupManager
                    .restoreFromBackupFile(genuine, PASSWORD.toCharArray())
                watcher.join(5_000)

                note("journal phases observed = ${phaseLog.toList()}")
                note("snapshot phase observed before any live change = $snapshotSeen")
                note("live dir changed while no snapshot existed = $liveChangedBeforeSnapshot")
                note("restore verified = ${result.verified}, images = ${result.inlineImages}")
                note("live images after restore = ${names(imagesDir)}")

                assertTrue("restore must succeed", result.verified)
                assertTrue("the journal must reach SNAPSHOT_TAKEN", snapshotSeen)
                assertFalse(
                    "the live image directory must never change before the rollback copy exists",
                    liveChangedBeforeSnapshot
                )
                assertNull("no journal after success", RestoreJournal.read(rig.context))
                dump("p4_B_staging_location_observed")
            } finally { rig.close() }
        }

    // ---------------------------------------------------------------- P4-c

    /**
     * In-process path: the restore fails AFTER the staged images were installed into the live dir.
     * The journal rollback must restore the previous image directory exactly, with nothing extra.
     */
    @Test
    fun `C failure after the image swap rolls the live image dir back exactly`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_c")
        try {
            val seeded = seed(rig)
            val packageFile = rig.backupManager
                .createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            // change the vault AFTER the package was built, so package != live vault
            val extraImage = rig.inlineImageStore.importFromStream(
                byteArrayOf(70, 71, 72, 73, 74, 75).inputStream(), "extra.png"
            )
            rig.repository.saveRecord(null, "Third", "body", null, null, emptyList())
            val liveImagesBefore = fingerprint(rig.inlineImageStore.imagesDir)
            val liveAttachmentsBefore = fingerprint(rig.attachmentStorage.encryptedDir)
            val contentBefore = contentFingerprint(rig)
            note("live images before = ${liveImagesBefore.keys}")
            note("image only in the live vault = '$extraImage'")

            val forged = resignWithWrongDraftCount(rig, packageFile)
            val outcome = runCatching {
                rig.backupManager.restoreFromBackupFile(forged, PASSWORD.toCharArray())
            }
            val ex = outcome.exceptionOrNull()
            note("restore outcome = " +
                (ex?.let { it.javaClass.simpleName + ": " + it.message } ?: "SUCCESS (unexpected)"))
            assertNotNull("restore must fail at verification", ex)

            note("live images identical after rollback = " +
                (liveImagesBefore == fingerprint(rig.inlineImageStore.imagesDir)))
            note("live attachments identical after rollback = " +
                (liveAttachmentsBefore == fingerprint(rig.attachmentStorage.encryptedDir)))
            note("row content identical after rollback = " + (contentBefore == contentFingerprint(rig)))
            note("journal after rollback = " + (RestoreJournal.read(rig.context) != null))
            note("filesDir after rollback = " +
                (rig.context.filesDir.listFiles()?.map { it.name }?.sorted()))
            assertEquals(
                "live images must be byte-identical after the rollback",
                liveImagesBefore, fingerprint(rig.inlineImageStore.imagesDir)
            )
            assertEquals(
                "live attachments must be byte-identical after the rollback",
                liveAttachmentsBefore, fingerprint(rig.attachmentStorage.encryptedDir)
            )
            assertEquals(
                "row content must be identical after the rollback",
                contentBefore, contentFingerprint(rig)
            )
            assertTrue("image added after the backup must survive", rig.inlineImageStore.exists(extraImage))
            assertNull("no journal must survive", RestoreJournal.read(rig.context))
            val leftovers = rig.context.filesDir.listFiles()?.map { it.name } ?: emptyList()
            assertTrue(
                "no rollback dirs",
                leftovers.none { it.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }
            )
            assertTrue(
                "no image previous dirs",
                leftovers.none { it.startsWith("note_images_previous_") }
            )
            assertTrue(
                "no attachment previous dirs",
                leftovers.none { it.startsWith("encrypted_attachments_previous_") }
            )
            assertTrue("seeded image still present", rig.inlineImageStore.exists(seeded.image))
            dump("p4_C_swap_then_failure_rollback")
        } finally { rig.close() }
    }

    /**
     * Fresh-process path: a restore dies mid image swap. A brand-new process must recover the
     * pre-restore image directory exactly — asserted on per-image plaintext hashes.
     */
    @Test
    fun `D startup recovery after a crash mid image swap restores every image exactly`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_d")
        // deliberately outside the rig root: rig.close() deletes its own root directory
        val crashImage = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "p4_d_crash_image_${System.nanoTime()}"
        ).apply { deleteRecursively(); mkdirs() }
        try {
            val seeded = seed(rig)
            note("seeded images = ${seeded.liveImages.keys}")

            val journal = RestoreJournal.begin(rig.context, "p4_d", rig.clock.nowMillis())
            journal.advance(RestorePhase.PREPARED)
            note("journal file on disk = " + File(rig.context.filesDir, RestoreJournal.FILE_NAME).exists())
            RestoreSnapshot.take(
                dbHolder = rig.dbHolder,
                filesDir = rig.context.filesDir,
                rollbackDir = journal.rollbackDir,
                attachmentsDir = rig.attachmentStorage.encryptedDir,
                imagesDir = rig.inlineImageStore.imagesDir
            )
            journal.advance(RestorePhase.SNAPSHOT_TAKEN)
            note("rollback copy images = " +
                File(journal.rollbackDir, RestoreJournal.IMAGES_BACKUP).listFiles()?.map { it.name })
            note("copy manifest present = " +
                File(journal.rollbackDir, "copy_manifest.txt").exists())

            // crash during the image swap: some staged files installed, one live image lost,
            // a half-written file left behind — the worst realistic half-installed state
            journal.advance(RestorePhase.SWAPPING_IMAGES)
            note("journal on disk before crash = " +
                File(rig.context.filesDir, RestoreJournal.FILE_NAME).readText())
            val imagesDir = rig.inlineImageStore.imagesDir
            // the staged result of a different vault: an extra image copied in and one file
            // half-written when the process died
            File(imagesDir, "stagedfrompackage.psenc").writeBytes(ByteArray(29) { 7 })
            File(imagesDir, "p4halfwritten.psenc").writeBytes(ByteArray(37) { 3 })
            note("crashed live images = ${names(imagesDir)}")
            rig.dbHolder.closeDatabase()

            copyTree(rig.context.filesDir, File(crashImage, "files"))
            copyTree(rig.dbHolder.databaseFile().parentFile!!, File(crashImage, "databases"))
            note("crash snapshot = " + crashImage.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(crashImage).path }.sorted().toList().take(40))
            rig.close()

            // ------------------------------ FRESH PROCESS ------------------------------
            val fresh = IsolatedVaultTestRig(
                ApplicationProvider.getApplicationContext(), "p4_d_fresh"
            )
            try {
                copyTree(File(crashImage, "files"), fresh.context.filesDir)
                copyTree(File(crashImage, "databases"), fresh.dbHolder.databaseFile().parentFile!!)
                assertNull("fresh process starts locked", fresh.sessionManager.currentSubKeysOrNull())

                val outcome = RestoreRecovery.onAppStart(
                    filesDir = fresh.context.filesDir,
                    dbDir = fresh.dbHolder.databaseFile().parentFile!!,
                    attachmentsDir = fresh.attachmentStorage.encryptedDir,
                    imagesDir = fresh.inlineImageStore.imagesDir,
                    closeDatabase = { fresh.dbHolder.closeDatabase() }
                )
                note("recovery outcome = $outcome")
                assertTrue(
                    "recovery must roll the interrupted restore back",
                    outcome is RestoreRecovery.Outcome.Recovered
                )

                note("live images after recovery = ${names(fresh.inlineImageStore.imagesDir)}")
                note("journal after recovery = " +
                    (RestoreJournal.readFrom(fresh.context.filesDir) != null))
                assertNull("journal must be resolved", RestoreJournal.readFrom(fresh.context.filesDir))
                assertEquals(
                    "encrypted image files must be byte-identical to the pre-restore state",
                    seeded.liveImages, fingerprint(fresh.inlineImageStore.imagesDir)
                )
                assertEquals(
                    "attachments must be byte-identical",
                    seeded.liveAttachments,
                    fingerprint(fresh.attachmentStorage.encryptedDir)
                )
                assertFalse(
                    "no half-written staged file may survive",
                    File(fresh.inlineImageStore.imagesDir, "p4halfwritten.psenc").exists()
                )

                val bundle = fresh.vaultKeyManager.readEnvelopeBundle()!!
                fresh.joinExistingVaultEnvelope(bundle, PASSWORD)
                val contentAfter = contentFingerprint(fresh)
                note("image content keys = " +
                    contentAfter.keys.filter { it.startsWith("image/") })
                assertEquals("rows + plaintext media must be identical", seeded.content, contentAfter)
                dump("p4_D_crash_mid_swap_fresh_process")
            } finally { fresh.close() }
        } finally { runCatching { rig.close() } }
    }

    // ---------------------------------------------------------------- P4-d

    /**
     * A failed restore must leave every previous valid backup byte-identical: the ones the user
     * already owns in `local_backups/`, and the package that was being restored.
     */
    @Test
    fun `E failed staging leaves every previous valid backup byte identical`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_e")
        try {
            val seeded = seed(rig)
            rig.clock.advanceBy(2_000)
            // a second, separately named backup the user owns
            val olderTarget = File(rig.backupManager.localBackupsDir, "older_valid_backup.psbackup")
            rig.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL, targetFile = olderTarget, recordHistoryInDb = true
            )
            note("local_backups contents = " +
                (rig.backupManager.localBackupsDir.listFiles()?.map { it.name }?.sorted()))
            note("safety backup present   = " + rig.backupManager.safetyBackupFile.exists())
            note("external slot present   = " + rig.backupManager.externalLocalBackupFile.exists())

            /** every backup the user owns: the local slot directory + the external copy */
            fun backupHashes(): Map<String, String> = buildMap {
                fingerprint(rig.backupManager.localBackupsDir)
                    .forEach { (name, hash) -> put("local/$name", hash) }
                rig.backupManager.safetyBackupFile.takeIf { it.exists() }
                    ?.let { put("safety/${it.name}", sha256(it)) }
                rig.backupManager.externalLocalBackupFile.takeIf { it.exists() }
                    ?.let { put("external/${it.name}", sha256(it)) }
            }

            val before = backupHashes()
            note("backups before = $before")
            assertTrue("the seeded backup must be present", before.containsKey("local/older_valid_backup.psbackup"))

            val genuine = rig.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL,
                targetFile = File(rig.context.cacheDir, "p4_e_package.psbackup"),
                recordHistoryInDb = false
            ).file
            note("package under test = ${genuine.name} (outside local_backups)")
            val broken = packageMissingLastImage(rig, genuine)
            val outcome = runCatching {
                rig.backupManager.restoreFromBackupFile(broken, PASSWORD.toCharArray())
            }
            note("restore outcome = " +
                (outcome.exceptionOrNull()?.javaClass?.simpleName ?: "SUCCESS (unexpected)"))
            assertNotNull("restore must fail", outcome.exceptionOrNull())

            val after = backupHashes()
            note("backups after  = $after")
            assertEquals("previous backups must be byte identical", before, after)
            assertEquals("row content unchanged", seeded.content, contentFingerprint(rig))
            dump("p4_E_previous_backups_untouched")
        } finally { rig.close() }
    }

    /**
     * Control for test A/F: proves the tampered package really is accepted by package validation and
     * fails later, inside staging. Without this, test A could pass because the package was rejected
     * before any staging happened.
     */
    @Test
    fun `F validly signed package with a corrupted image fails inside staging not at validation`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_f")
            try {
                val seeded = seed(rig)
                val foreign = foreignPackage(rig)
                val tampered = packageWhoseImageFailsToDecrypt(rig, foreign)

                // package-level validation must ACCEPT it: the manifest describes the bytes exactly
                val manifest = rig.backupManager.inspectAndValidatePackageStructure(tampered)
                note("package validation PASSED, inlineImages = ${manifest.inlineImages.size}")

                val outcome = runCatching {
                    rig.backupManager.restoreFromBackupFile(tampered, FOREIGN_PASSWORD.toCharArray())
                }
                val ex = outcome.exceptionOrNull()
                note("restore outcome = " +
                    (ex?.let { it.javaClass.simpleName + ": " + it.message } ?: "SUCCESS (unexpected)"))
                assertNotNull("restore must fail", ex)
                assertTrue(
                    "failure must be an inline-image staging failure, not a package rejection",
                    ex is CorruptedBackupException &&
                        ex.message!!.contains("inline image", ignoreCase = true)
                )
                assertEquals(
                    "the live image directory must be untouched by the failed staging",
                    seeded.liveImages, fingerprint(rig.inlineImageStore.imagesDir)
                )
                assertEquals(
                    "row content must be untouched",
                    seeded.content, contentFingerprint(rig)
                )
                assertNull("no journal must be created for a staging failure", RestoreJournal.read(rig.context))
                assertTrue(
                    "staging must be cleaned up",
                    (rig.context.cacheDir.listFiles()?.map { it.name } ?: emptyList())
                        .none { it.startsWith("restore_stage_") }
                )
                dump("p4_F_tampered_package_fails_at_staging")
            } finally { rig.close() }
        }

    /**
     * Unit-level contract of the new staging parameter, independent of the restore flow:
     * `writeRestoredImage(targetDir = ...)` writes ONLY into that directory and never deletes a
     * pre-existing live file, while the legacy call (`targetDir = null`) still writes into the live
     * `note_images/` folder as before.
     */
    @Test
    fun `G writeRestoredImage writes to the staging dir only and never deletes live data`() =
        runBlocking {
            val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_g")
            try {
                val seeded = seed(rig)
                val store = rig.inlineImageStore
                val liveDir = store.imagesDir
                val liveBefore = fingerprint(liveDir)
                val liveMtime = liveDir.lastModified()

                val staging = File(rig.context.cacheDir, "p4_g_stage").apply { mkdirs() }
                val ok = store.writeRestoredImage(
                    identifier = "p4_staged_only",
                    ciphertext = byteArrayOf(1, 2, 3, 4, 5, 6),
                    sourceIsPlaintext = true,
                    sourceKey = null,
                    targetDir = staging
                )
                note("writeRestoredImage(targetDir = staging) returned = $ok")
                note("staged dir contents = " + (staging.listFiles()?.map { it.name }))
                note("live dir contents   = " + (liveDir.listFiles()?.map { it.name }?.sorted()))
                assertTrue("the image must be written", ok)
                assertEquals(
                    "the file must exist in the staging directory only",
                    listOf("p4_staged_only" + app.personalspace.vault.data.backup.InlineImageStore.ENCRYPTED_SUFFIX),
                    staging.listFiles()?.map { it.name }
                )
                assertEquals("the live dir must be byte identical", liveBefore, fingerprint(liveDir))
                assertEquals("the live dir must not be touched at all", liveMtime, liveDir.lastModified())

                // failing re-encryption must not delete anything in the live dir
                val victimIdentifier = store.identifierOf(seeded.liveImages.keys.first())
                val failed = store.writeRestoredImage(
                    identifier = victimIdentifier,          // identical identifier as a live image
                    ciphertext = byteArrayOf(9, 9, 9),
                    sourceIsPlaintext = false,
                    sourceKey = ByteArray(32) { 7 },         // wrong key -> decryption fails
                    targetDir = staging
                )
                note("writeRestoredImage with a wrong source key returned = $failed")
                note("live dir after the failed write = " + (liveDir.listFiles()?.map { it.name }?.sorted()))
                assertFalse("the write must report failure", failed)
                assertEquals(
                    "a live image with the same identifier must NOT be deleted",
                    liveBefore, fingerprint(liveDir)
                )
                assertEquals(
                    "the failed staging write must leave no partial file",
                    listOf("p4_staged_only" + app.personalspace.vault.data.backup.InlineImageStore.ENCRYPTED_SUFFIX),
                    staging.listFiles()?.map { it.name }
                )

                // legacy call keeps writing into the live folder (unchanged behaviour)
                val legacy = store.writeRestoredImage(
                    identifier = "p4_legacy_call",
                    ciphertext = byteArrayOf(4, 4, 4),
                    sourceIsPlaintext = true,
                    sourceKey = null
                )
                val legacyFile = File(
                    liveDir, "p4_legacy_call" + app.personalspace.vault.data.backup.InlineImageStore.ENCRYPTED_SUFFIX
                )
                note("legacy call (targetDir = null) returned = $legacy, wrote ${legacyFile.name} = ${legacyFile.exists()}")
                assertTrue("legacy behaviour must be unchanged", legacy && legacyFile.exists())
                legacyFile.delete()
                dump("p4_G_write_restored_image_contract")
            } finally { rig.close() }
        }

    /**
     * P4 must not become MORE destructive than P3.1: images that exist live but are not part of the
     * package (e.g. an image added after the backup) are still on disk after a successful restore,
     * and the restored notes reference exactly the restored images. Nothing is deleted to make the
     * directory "match".
     */
    @Test
    fun `H successful restore keeps live-only images instead of deleting them`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p4_h")
        try {
            val seeded = seed(rig)
            val packageFile = rig.backupManager.createFullBackupFile(
                BackupKind.FULL_MANUAL,
                targetFile = File(rig.context.cacheDir, "p4_h_package.psbackup"),
                recordHistoryInDb = false
            ).file
            // image added AFTER the package was built -> not in the package
            val liveOnly = rig.inlineImageStore.importFromStream(
                byteArrayOf(61, 62, 63).inputStream(), "after_backup.png"
            )
            note("images before restore = " + names(rig.inlineImageStore.imagesDir))
            note("image not in the package = '$liveOnly'")

            val result = rig.backupManager.restoreFromBackupFile(packageFile, PASSWORD.toCharArray())
            note("restore verified = ${result.verified}")
            val after = names(rig.inlineImageStore.imagesDir)
            note("images after restore = $after")
            assertTrue("restore must verify", result.verified)
            seeded.liveImages.keys.forEach { name ->
                assertTrue("packaged image $name must exist after restore", after.contains(name))
                assertEquals(
                    "packaged image $name must be byte identical",
                    seeded.liveImages[name], fingerprint(rig.inlineImageStore.imagesDir)[name]
                )
            }
            assertTrue("the live-only image must not be deleted", after.contains(liveOnly + ".psenc"))
            val titles = rig.dbHolder.getDatabase().recordDao()
                .getAllRecordsIncludingDeleted().map { it.title }.sorted()
            note("titles after restore = $titles")
            assertEquals(
                "restored rows must be exactly the packaged ones",
                listOf("Illustrated", "Illustrated2", "Illustrated3", "Precious", "Second").sorted(),
                titles
            )
            dump("p4_H_live_only_images_preserved")
        } finally { rig.close() }
    }

    private companion object {
        const val PASSWORD = "P4Pass#2026"
        const val FOREIGN_PASSWORD = "Foreign#2026"
    }
}
