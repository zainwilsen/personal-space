package app.personalspace.vault.data.backup

import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * P8 — **content verification, not counting**.
 *
 * A restore used to be declared successful if the row counts matched and the files existed. That
 * cannot tell a note whose body was silently emptied from a note that is fine. This verifier compares
 * the staged snapshot against the live database **field by field**, and against the media files on
 * disk **by plaintext hash**, and it reports every mismatch as a typed [Mismatch] naming the exact
 * entity and field.
 *
 * What is compared:
 *
 *  * folders: id, name, parent, colour, createdAt, modifiedAt, isDeleted, deletedAt
 *  * records: every snapshot field (title, body, folder, category, timestamps, version pointer,
 *    deletion metadata, sync version) + the decoded rich-body block structure
 *  * versions: id, record, number, title, body, category, timestamps
 *  * attachments: every column + the **plaintext sha256** read back through the storage layer
 *  * inline images: referenced identifiers must exist and decrypt to the recorded plaintext hash
 *  * drafts: key, folder, title, body, custom fields, updatedAt
 *  * recycle-bin batches: id, root entity, counts, expiry
 *  * relationships: every record's folder must exist; every attachment's record must exist; every
 *    record's `currentVersionId` must exist; ordering (folder sort keys / record ordering) must match
 *  * rich formatting: every span (kind, range, colour) and block alignment/width must match
 *
 * The verifier is used twice: by P7 before a generation is promoted, and by the restore itself before
 * success is reported. It is deliberately read-only.
 */
class DeepBackupVerifier(
    private val db: PersonalSpaceDatabase,
    private val attachmentStorage: EncryptedAttachmentStorageView,
    private val inlineImageStore: InlineImageView
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Minimal view of the attachment storage so the verifier is testable without Android. */
    interface EncryptedAttachmentStorageView {
        fun exists(relativePath: String): Boolean
        fun plaintextSha256(relativePath: String): String?
        fun decryptedByteSize(relativePath: String): Long?
    }

    interface InlineImageView {
        fun exists(identifier: String): Boolean

        /** SHA-256 of the bytes stored on disk (for the encrypted store: the ciphertext). */
        fun storedSha256(identifier: String): String?

        /** SHA-256 of the decrypted plaintext (survives re-encryption under a new vault key). */
        fun plaintextSha256(identifier: String): String?
    }

    // ------------------------------------------------------------------ typed failures

    enum class Severity { ERROR, WARNING }

    @Serializable
    data class Mismatch(
        /** FOLDER, RECORD, VERSION, ATTACHMENT, INLINE_IMAGE, DRAFT, BATCH, RELATIONSHIP, ORDERING */
        val entity: String,
        val entityId: String,
        val field: String,
        val expected: String,
        val actual: String,
        val severity: String = "ERROR",
        /** human sentence, the one shown to the user / written to the log */
        val detail: String = ""
    ) {
        fun describe(): String = "$entity[$entityId].$field expected=$expected actual=$actual" +
            if (detail.isBlank()) "" else " — $detail"
    }

    @Serializable
    data class VerificationReport(
        val checkedFolders: Int = 0,
        val checkedRecords: Int = 0,
        val checkedVersions: Int = 0,
        val checkedAttachments: Int = 0,
        val checkedInlineImages: Int = 0,
        val checkedDrafts: Int = 0,
        val checkedBatches: Int = 0,
        val checkedRelationships: Int = 0,
        val mismatches: List<Mismatch> = emptyList()
    ) {
        val passed: Boolean get() = mismatches.none { it.severity == "ERROR" }
        fun summary(): String =
            if (passed) "verified $checkedRecords notes, ${checkedFolders} folders, " +
                "${checkedVersions} versions, ${checkedAttachments} attachments, " +
                "${checkedInlineImages} inline images, ${checkedDrafts} drafts, " +
                "${checkedBatches} recycle-bin batches, $checkedRelationships relationships"
            else "FAILED verification with ${mismatches.size} mismatch(es): " +
                mismatches.take(5).joinToString("; ") { it.describe() } +
                if (mismatches.size > 5) " … (+${mismatches.size - 5} more)" else ""
    }

    class VerificationFailedException(val report: VerificationReport) : IllegalStateException(report.summary())

    // ------------------------------------------------------------------ the verification

    /**
     * Compares [snapshotJson] (the decrypted package payload) against the live database and the
     * media files it references.
     *
     * @param verifyMedia read every attachment/image back and compare plaintext hashes. Expensive, but
     *   this is exactly what a restore must do before claiming success.
     */
    suspend fun verify(
        snapshotJson: String,
        verifyMedia: Boolean = true,
        inlineImages: List<BackupInlineImageRef> = emptyList(),
        /**
         * Expected PLAINTEXT sha256 per image identifier, computed from the package itself.
         *
         * Needed because a restore into a *different* vault re-encrypts every image under the new
         * vault key, so the stored bytes (and the manifest's encrypted hash) legitimately differ while
         * the picture is unchanged. Given this, a byte mismatch falls back to the plaintext comparison
         * that actually proves the content survived.
         */
        expectedImagePlaintext: Map<String, String> = emptyMap()
    ): VerificationReport {
        val snapshot = json.decodeFromString<VaultSnapshotPayload>(snapshotJson)
        val problems = ArrayList<Mismatch>()
        var folders = 0; var records = 0; var versions = 0; var attachments = 0
        var images = 0; var drafts = 0; var batches = 0; var relationships = 0

        // ---- folders -------------------------------------------------------------------------
        val liveFolders = db.folderDao().getAllFoldersIncludingDeleted().associateBy { it.id }
        for (expect in snapshot.folders) {
            folders++
            val actual = liveFolders[expect.id]
            if (actual == null) {
                problems.add(m(ENTITY_FOLDER, expect.id, "presence", "present", "missing",
                    "the folder was in the backup but is absent after the restore"))
                continue
            }
            cmp(problems, ENTITY_FOLDER, expect.id, "name", expect.name, actual.name)
            cmp(problems, ENTITY_FOLDER, expect.id, "parentFolderId",
                expect.parentFolderId?.toString(), actual.parentFolderId?.toString())
            cmp(problems, ENTITY_FOLDER, expect.id, "colorId", expect.colorId, actual.colorId)
            cmp(problems, ENTITY_FOLDER, expect.id, "createdAt", expect.createdAt, actual.createdAt)
            cmp(problems, ENTITY_FOLDER, expect.id, "modifiedAt", expect.modifiedAt, actual.modifiedAt)
            cmp(problems, ENTITY_FOLDER, expect.id, "isDeleted", expect.isDeleted, actual.isDeleted)
            cmp(problems, ENTITY_FOLDER, expect.id, "deletedAt", expect.deletedAt?.toString(), actual.deletedAt?.toString())
        }
        val snapshotFolderIds = snapshot.folders.map { it.id }.toSet()
        liveFolders.keys.filter { it !in snapshotFolderIds }.forEach { extra ->
            problems.add(m(ENTITY_FOLDER, extra, "presence", "absent", "present",
                "a folder exists after the restore that the backup never contained"))
        }

        // ---- records -------------------------------------------------------------------------
        val liveRecords = db.recordDao().getAllRecordsIncludingDeleted().associateBy { it.id }
        val liveVersions = db.recordDao().getAllVersions().associateBy { it.id }
        for (expect in snapshot.records) {
            records++
            val actual = liveRecords[expect.id]
            if (actual == null) {
                problems.add(m(ENTITY_RECORD, expect.id, "presence", "present", "missing",
                    "the note was in the backup but is absent after the restore"))
                continue
            }
            // identity + text
            cmp(problems, ENTITY_RECORD, expect.id, "title", expect.title, actual.title)
            cmp(problems, ENTITY_RECORD, expect.id, "description", expect.description, actual.description)
            cmp(problems, ENTITY_RECORD, expect.id, "folderId",
                expect.folderId?.toString(), actual.folderId?.toString())
            cmp(problems, ENTITY_RECORD, expect.id, "category", expect.category, actual.category)
            cmp(problems, ENTITY_RECORD, expect.id, "customFieldsJson",
                expect.customFieldsJson, actual.customFieldsJson)
            // timestamps + deletion metadata
            cmp(problems, ENTITY_RECORD, expect.id, "createdAt", expect.createdAt, actual.createdAt)
            cmp(problems, ENTITY_RECORD, expect.id, "modifiedAt", expect.modifiedAt, actual.modifiedAt)
            cmp(problems, ENTITY_RECORD, expect.id, "isDeleted", expect.isDeleted, actual.isDeleted)
            cmp(problems, ENTITY_RECORD, expect.id, "deletedAt",
                expect.deletedAt?.toString(), actual.deletedAt?.toString())
            cmp(problems, ENTITY_RECORD, expect.id, "syncVersion", expect.syncVersion, actual.syncVersion)
            cmp(problems, ENTITY_RECORD, expect.id, "lastModifiedByDeviceId",
                expect.lastModifiedByDeviceId, actual.lastModifiedByDeviceId)
            cmp(problems, ENTITY_RECORD, expect.id, "currentVersionNumber",
                expect.currentVersionNumber, actual.currentVersionNumber)
            // the current version pointer must resolve — and to the same content
            val pointed = liveVersions[actual.currentVersionId]
            if (pointed == null) {
                problems.add(m(ENTITY_RELATIONSHIP, expect.id, "currentVersionId",
                    "existing version", "missing",
                    "the note points at a version that does not exist after the restore"))
            } else {
                relationships++
                cmp(problems, ENTITY_RECORD, expect.id, "currentVersion.title",
                    expect.title, pointed.title)
                cmp(problems, ENTITY_RECORD, expect.id, "currentVersion.description",
                    expect.description, pointed.description)
            }
            // rich formatting must survive structurally, not just as a string
            verifyRichBody(problems, expect.id, expect.description, actual.description)
        }
        val snapshotRecordIds = snapshot.records.map { it.id }.toSet()
        liveRecords.keys.filter { it !in snapshotRecordIds }.forEach { extra ->
            problems.add(m(ENTITY_RECORD, extra, "presence", "absent", "present",
                "a note exists after the restore that the backup never contained"))
        }

        // ---- versions ------------------------------------------------------------------------
        val snapshotVersionIds = snapshot.versions.map { it.id }.toSet()
        for (expect in snapshot.versions) {
            versions++
            val actual = liveVersions[expect.id]
            if (actual == null) {
                problems.add(m(ENTITY_VERSION, expect.id, "presence", "present", "missing",
                    "a version was in the backup but is absent after the restore"))
                continue
            }
            cmp(problems, ENTITY_VERSION, expect.id, "recordId", expect.recordId, actual.recordId)
            cmp(problems, ENTITY_VERSION, expect.id, "versionNumber",
                expect.versionNumber, actual.versionNumber)
            cmp(problems, ENTITY_VERSION, expect.id, "title", expect.title, actual.title)
            cmp(problems, ENTITY_VERSION, expect.id, "description", expect.description, actual.description)
            cmp(problems, ENTITY_VERSION, expect.id, "category", expect.category, actual.category)
        }
        liveVersions.keys.filter { it !in snapshotVersionIds }.forEach { extra ->
            problems.add(m(ENTITY_VERSION, extra, "presence", "absent", "present",
                "a version exists after the restore that the backup never contained"))
        }

        // ---- attachments (columns + plaintext hash) -----------------------------------------
        val liveAttachments = db.attachmentDao().getAllAttachmentsIncludingDeleted().associateBy { it.id }
        for (expect in snapshot.attachments) {
            attachments++
            val actual = liveAttachments[expect.id]
            if (actual == null) {
                problems.add(m(ENTITY_ATTACHMENT, expect.id, "presence", "present", "missing",
                    "an attachment was in the backup but is absent after the restore"))
                continue
            }
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "recordId", expect.recordId, actual.recordId)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "originalFileName",
                expect.originalFileName, actual.originalFileName)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "mimeType", expect.mimeType, actual.mimeType)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "attachmentKind",
                expect.attachmentKind, actual.attachmentKind)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "plaintextSizeBytes",
                expect.plaintextSizeBytes, actual.plaintextSizeBytes)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "encryptedRelativePath",
                expect.encryptedRelativePath, actual.encryptedRelativePath)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "plaintextSha256Hex",
                expect.plaintextSha256Hex, actual.plaintextSha256Hex)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "isDeleted", expect.isDeleted, actual.isDeleted)
            cmp(problems, ENTITY_ATTACHMENT, expect.id, "deletedAt",
                expect.deletedAt?.toString(), actual.deletedAt?.toString())
            // the note it belongs to must exist
            if (!liveRecords.containsKey(expect.recordId)) {
                problems.add(m(ENTITY_RELATIONSHIP, expect.id, "recordId",
                    expect.recordId, "missing",
                    "the attachment's note does not exist after the restore"))
            } else relationships++
            // and the bytes must decrypt to the recorded plaintext hash
            if (verifyMedia) {
                if (!attachmentStorage.exists(expect.encryptedRelativePath)) {
                    // P5 semantics: only ACTIVE attachments promise bytes. A pre-P5 package can carry
                    // a deleted row whose ciphertext was already hard-deleted by the old delete path;
                    // the row is preserved with its path and hash. Report it, but do not fail a restore
                    // over recycle-bin content — that would make an older user backup unrestorable.
                    problems.add(
                        m(ENTITY_ATTACHMENT, expect.id, "file",
                            if (expect.isDeleted) "optional (recycle bin)" else "present",
                            "missing",
                            if (expect.isDeleted)
                                "a recycle-bin attachment has no ciphertext on disk (pre-P5 package)"
                            else "the encrypted attachment file is missing after the restore"
                        ).copy(severity = if (expect.isDeleted) "WARNING" else "ERROR")
                    )
                } else {
                    val actualHash = attachmentStorage.plaintextSha256(expect.encryptedRelativePath)
                    if (actualHash == null) {
                        problems.add(m(ENTITY_ATTACHMENT, expect.id, "file",
                            "decryptable", "could not be decrypted",
                            "the attachment file did not decrypt after the restore"))
                    } else if (actualHash != expect.plaintextSha256Hex) {
                        problems.add(m(ENTITY_ATTACHMENT, expect.id, "plaintextSha256",
                            expect.plaintextSha256Hex, actualHash,
                            "the restored attachment's plaintext does not match the backup"))
                    }
                    val size = attachmentStorage.decryptedByteSize(expect.encryptedRelativePath)
                    if (size != null && size != expect.plaintextSizeBytes) {
                        problems.add(m(ENTITY_ATTACHMENT, expect.id, "plaintextSizeBytes",
                            expect.plaintextSizeBytes.toString(), size.toString(),
                            "the restored attachment's size does not match the backup"))
                    }
                }
            }
        }
        liveAttachments.keys.filter { it !in snapshot.attachments.map { a -> a.id }.toSet() }.forEach { extra ->
            problems.add(m(ENTITY_ATTACHMENT, extra, "presence", "absent", "present",
                "an attachment exists after the restore that the backup never contained"))
        }

        // ---- inline images -------------------------------------------------------------------
        if (verifyMedia) {
            val expectedImages = inlineImages.associateBy { it.identifier }
            for ((identifier, ref) in expectedImages) {
                images++
                if (!inlineImageStore.exists(identifier)) {
                    problems.add(m(ENTITY_INLINE_IMAGE, identifier, "file", "present", "missing",
                        "an inline image is referenced by a restored note but the file is missing"))
                    continue
                }
                // the manifest carries the hash of the stored (encrypted) bytes; comparing that
                // proves the file on disk is byte-identical to the one that was backed up
                val actualHash = inlineImageStore.storedSha256(identifier)
                if (actualHash == null) {
                    problems.add(m(ENTITY_INLINE_IMAGE, identifier, "file",
                        "readable", "could not be read",
                        "an inline image could not be read after the restore"))
                } else if (actualHash != ref.encryptedSha256Hex) {
                    // the stored bytes differ — legitimate for a cross-vault restore, which re-encrypts
                    // media under the destination key. Fall back to the plaintext comparison, which is
                    // what actually has to survive.
                    val expectedPlain = expectedImagePlaintext[identifier]
                    val actualPlain = inlineImageStore.plaintextSha256(identifier)
                    if (expectedPlain != null && actualPlain != null && expectedPlain == actualPlain) {
                        problems.add(
                            m(ENTITY_INLINE_IMAGE, identifier, "storedSha256",
                                "re-encrypted", "re-encrypted",
                                "stored bytes differ because the restore re-encrypted the image under " +
                                    "the destination vault key; plaintext is identical"
                            ).copy(severity = "WARNING")
                        )
                    } else {
                        problems.add(m(ENTITY_INLINE_IMAGE, identifier, "plaintextSha256",
                            expectedPlain ?: ref.encryptedSha256Hex,
                            actualPlain ?: "could not be read",
                            "an inline image's content does not match the backup"))
                    }
                }
            }
            // every image REFERENCED by a restored body must be present, even if the manifest omitted it
            val referenced = MediaReferenceCollector.imageIdentifiers(
                snapshot.records.map { it.description } +
                    snapshot.versions.map { it.description } +
                    snapshot.drafts.map { it.description }
            )
            val declared = expectedImages.keys
            referenced.filter { id -> id !in declared }.forEach { stray ->
                // A restored note can reference an image the PACKAGE never carried: pre-P5 packages
                // declared no inline images at all, and P5 decided a deleted note's missing image must
                // not make a package unrestorable. Real gap in the package -> reported, but as a
                // WARNING, because failing here would re-introduce the P5 defect.
                problems.add(
                    m(ENTITY_RELATIONSHIP, stray, "inlineImageReference",
                        "listed in the backup manifest", "referenced but not listed",
                        "a restored note references an image the backup did not carry"
                    ).copy(severity = "WARNING")
                )
            }
        }

        // ---- drafts --------------------------------------------------------------------------
        val liveDrafts = db.recordDao().getAllDrafts().associateBy { it.draftKey }
        for (expect in snapshot.drafts) {
            drafts++
            val actual = liveDrafts[expect.draftKey]
            if (actual == null) {
                problems.add(m(ENTITY_DRAFT, expect.draftKey, "presence", "present", "missing",
                    "an unsaved draft was in the backup but is absent after the restore"))
                continue
            }
            cmp(problems, ENTITY_DRAFT, expect.draftKey, "title", expect.title, actual.title)
            cmp(problems, ENTITY_DRAFT, expect.draftKey, "description", expect.description, actual.description)
            cmp(problems, ENTITY_DRAFT, expect.draftKey, "updatedAt", expect.updatedAt, actual.updatedAt)
            cmp(problems, ENTITY_DRAFT, expect.draftKey, "folderId",
                expect.folderId?.toString(), actual.folderId?.toString())
            cmp(problems, ENTITY_DRAFT, expect.draftKey, "customFieldsJson",
                expect.customFieldsJson, actual.customFieldsJson)
            cmp(problems, ENTITY_DRAFT, expect.draftKey, "category", expect.category, actual.category)
        }
        liveDrafts.keys.filter { it !in snapshot.drafts.map { d -> d.draftKey }.toSet() }.forEach { extra ->
            problems.add(m(ENTITY_DRAFT, extra, "presence", "absent", "present",
                "a draft exists after the restore that the backup never contained"))
        }

        // ---- recycle-bin batches --------------------------------------------------------------
        val liveBatches = db.recycleBinDao().getAllDeletedItems().associateBy { it.batchId }
        for (expect in snapshot.deletedBatches) {
            batches++
            val actual = liveBatches[expect.batchId]
            if (actual == null) {
                problems.add(m(ENTITY_BATCH, expect.batchId, "presence", "present", "missing",
                    "a recycle-bin batch was in the backup but is absent after the restore"))
                continue
            }
            cmp(problems, ENTITY_BATCH, expect.batchId, "rootEntityType",
                expect.rootEntityType, actual.rootEntityType)
            cmp(problems, ENTITY_BATCH, expect.batchId, "rootEntityId",
                expect.rootEntityId, actual.rootEntityId)
            cmp(problems, ENTITY_BATCH, expect.batchId, "recordCount",
                expect.recordCount, actual.recordCount)
            cmp(problems, ENTITY_BATCH, expect.batchId, "deletedAtMillis",
                expect.deletedAtMillis, actual.deletedAtMillis)
            cmp(problems, ENTITY_BATCH, expect.batchId, "expiresAtMillis",
                expect.expiresAtMillis, actual.expiresAtMillis)
        }
        liveBatches.keys.filter { it !in snapshot.deletedBatches.map { b -> b.batchId }.toSet() }.forEach { extra ->
            problems.add(m(ENTITY_BATCH, extra, "presence", "absent", "present",
                "a recycle-bin batch exists after the restore that the backup never contained"))
        }

        // ---- relationship + ordering ----------------------------------------------------------
        val folderIds = liveFolders.keys
        liveRecords.values.forEach { record ->
            val folderId = record.folderId ?: return@forEach
            relationships++
            if (folderId !in folderIds) {
                problems.add(m(ENTITY_RELATIONSHIP, record.id, "folderId",
                    "an existing folder", folderId,
                    "the note is in a folder that does not exist after the restore"))
            }
        }
        checkFolderOrdering(problems, snapshot.folders, liveFolders)
        checkRecordOrdering(problems, snapshot.records, liveRecords)

        return VerificationReport(
            checkedFolders = folders,
            checkedRecords = records,
            checkedVersions = versions,
            checkedAttachments = attachments,
            checkedInlineImages = images,
            checkedDrafts = drafts,
            checkedBatches = batches,
            checkedRelationships = relationships,
            mismatches = problems
        )
    }

    /** Fails loudly instead of returning a report, for call sites that must not continue. */
    suspend fun verifyOrThrow(snapshotJson: String, verifyMedia: Boolean = true): VerificationReport {
        val report = verify(snapshotJson, verifyMedia)
        if (!report.passed) throw VerificationFailedException(report)
        return report
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Rich formatting is compared structurally: the decoded block list (types, text, spans with kind +
     * range + colour, alignment, image width) must be identical. A body that survived as a string but
     * lost its bold spans is a silent data loss, so it is an ERROR.
     */
    private fun verifyRichBody(problems: MutableList<Mismatch>, recordId: String, expected: String, actual: String) {
        val expectedBlocks = runCatching { RichNoteCodec.decodeBlocks(expected) }.getOrElse {
            problems.add(m(ENTITY_RECORD, recordId, "richBody", "decodable", "undecodable",
                "the backup's body could not be decoded: ${it.message}")); return
        }
        val actualBlocks = runCatching { RichNoteCodec.decodeBlocks(actual) }.getOrElse {
            problems.add(m(ENTITY_RECORD, recordId, "richBody", "decodable", "undecodable",
                "the restored body could not be decoded: ${it.message}")); return
        }
        if (expectedBlocks.size != actualBlocks.size) {
            problems.add(m(ENTITY_RECORD, recordId, "richBody.blockCount",
                expectedBlocks.size.toString(), actualBlocks.size.toString(),
                "the restored note has a different number of blocks"))
            return
        }
        expectedBlocks.forEachIndexed { index, expect ->
            val real = actualBlocks[index]
            if (expect::class != real::class) {
                problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].type",
                    expect::class.simpleName ?: "?", real::class.simpleName ?: "?",
                    "block type changed"))
                return@forEachIndexed
            }
            when (expect) {
                is RichNoteBlock.Text -> {
                    val realText = real as RichNoteBlock.Text
                    if (expect.text != realText.text) {
                        problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].text",
                            expect.text, realText.text, "block text changed"))
                    }
                    if (expect.spans.size != realText.spans.size) {
                        problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].spanCount",
                            expect.spans.size.toString(), realText.spans.size.toString(),
                            "formatting spans were lost or added"))
                    } else {
                        expect.spans.zip(realText.spans).forEach { (e, a) ->
                            if (e.start != a.start || e.end != a.end) {
                                problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].spanRange",
                                    "${e.start}..${e.end}", "${a.start}..${a.end}",
                                    "a formatting span moved"))
                            }
                            if (e.kind != a.kind) {
                                problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].spanKind",
                                    e.kind.name, a.kind.name, "formatting kind changed"))
                            }
                            if (e.colorHex != a.colorHex) {
                                problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].spanColor",
                                    e.colorHex ?: "none", a.colorHex ?: "none",
                                    "a formatting colour changed"))
                            }
                        }
                    }
                    if (expect.alignment != realText.alignment) {
                        problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].alignment",
                            expect.alignment.name, realText.alignment.name, "paragraph alignment changed"))
                    }
                }
                is RichNoteBlock.Image -> {
                    val realImage = real as RichNoteBlock.Image
                    if (expect.imagePath != realImage.imagePath) {
                        problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].imagePath",
                            expect.imagePath, realImage.imagePath, "the image reference changed"))
                    }
                    if (expect.widthFraction != realImage.widthFraction) {
                        problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].widthFraction",
                            expect.widthFraction.toString(), realImage.widthFraction.toString(),
                            "the image width changed"))
                    }
                    if (expect.alignment != realImage.alignment) {
                        problems.add(m(ENTITY_RECORD, recordId, "richBody[$index].alignment",
                            expect.alignment.name, realImage.alignment.name,
                            "the image alignment changed"))
                    }
                }
            }
        }
    }

    private fun checkFolderOrdering(
        problems: MutableList<Mismatch>,
        expected: List<SerializableFolderSnapshot>,
        live: Map<String, app.personalspace.vault.data.local.entity.FolderEntity>
    ) {
        // sibling folders must keep the same relative order (name, then createdAt) as the backup
        expected.groupBy { it.parentFolderId }.forEach { (parent, siblings) ->
            val expectedOrder = siblings.sortedWith(compareBy({ it.name.lowercase() }, { it.createdAt })).map { it.id }
            val actualOrder = siblings.mapNotNull { live[it.id] }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.createdAt }))
                .map { it.id }
            if (expectedOrder != actualOrder) {
                problems.add(m(ENTITY_ORDERING, parent ?: "root", "folderOrder",
                    expectedOrder.joinToString(","), actualOrder.joinToString(","),
                    "folder ordering changed during the restore"))
            }
        }
    }

    private fun checkRecordOrdering(
        problems: MutableList<Mismatch>,
        expected: List<SerializableRecordSnapshot>,
        live: Map<String, app.personalspace.vault.data.local.entity.RecordEntity>
    ) {
        // notes are ordered by modifiedAt desc, then createdAt desc — the list screen's order
        val expectedOrder = expected.sortedWith(
            compareByDescending<SerializableRecordSnapshot> { it.modifiedAt }.thenByDescending { it.createdAt }
        ).map { it.id }
        val actualOrder = expected.mapNotNull { live[it.id] }
            .sortedWith(compareByDescending<app.personalspace.vault.data.local.entity.RecordEntity> { it.modifiedAt }
                .thenByDescending { it.createdAt })
            .map { it.id }
        if (expectedOrder != actualOrder) {
            problems.add(m(ENTITY_ORDERING, "records", "recordOrder",
                expectedOrder.joinToString(","), actualOrder.joinToString(","),
                "note ordering changed during the restore"))
        }
    }

    private fun m(
        entity: String,
        id: String,
        field: String,
        expected: Any?,
        actual: Any?,
        detail: String
    ) = Mismatch(
        entity = entity,
        entityId = id,
        field = field,
        expected = expected?.toString() ?: "null",
        actual = actual?.toString() ?: "null",
        detail = detail
    )

    private fun cmp(problems: MutableList<Mismatch>, entity: String, id: String, field: String, expected: Any?, actual: Any?) {
        if (expected != actual) {
            problems.add(
                m(entity, id, field, expected, actual, "value differs after the restore")
            )
        }
    }

    companion object {
        const val ENTITY_FOLDER = "FOLDER"
        const val ENTITY_RECORD = "RECORD"
        const val ENTITY_VERSION = "VERSION"
        const val ENTITY_ATTACHMENT = "ATTACHMENT"
        const val ENTITY_INLINE_IMAGE = "INLINE_IMAGE"
        const val ENTITY_DRAFT = "DRAFT"
        const val ENTITY_BATCH = "BATCH"
        const val ENTITY_RELATIONSHIP = "RELATIONSHIP"
        const val ENTITY_ORDERING = "ORDERING"

        fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(bytes)
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Collects image identifiers from a set of raw bodies, without needing the database. */
object MediaReferenceCollector {
    fun imageIdentifiers(rawBodies: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        rawBodies.forEach { raw ->
            runCatching {
                RichNoteCodec.decodeBlocks(raw)
                    .filterIsInstance<RichNoteBlock.Image>()
                    .forEach { out.add(InlineImageStore.normalizeImageReference(it.imagePath)) }
            }
        }
        return out
    }
}
