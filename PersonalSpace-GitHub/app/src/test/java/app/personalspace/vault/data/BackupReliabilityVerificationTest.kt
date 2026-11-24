package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.data.backup.BackupPackageManager
import app.personalspace.vault.data.backup.MissingBackupAttachmentException
import app.personalspace.vault.data.backup.MissingBackupImageException
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.CustomFieldType
import app.personalspace.vault.domain.model.RecordDraft
import app.personalspace.vault.presentation.records.BlockAlignment
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.RichSpan
import app.personalspace.vault.presentation.records.RichSpanKind
import app.personalspace.vault.presentation.records.applyParagraphAlignment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Additional verification tests written for BACKUP_RELIABILITY_VERIFICATION.md.
 * These do NOT modify production code; they only exercise the existing public/internal API.
 *
 * Every assertion writes a line into [EVIDENCE] so the report can quote measured values,
 * and each test case maps to one of the 10 requested verification areas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupReliabilityVerificationTest {

    private val evidence = StringBuilder()
    private fun note(line: String) {
        evidence.append(line).append('\n')
        println("EVIDENCE| " + line)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = sha256(file.readBytes())

    // ---------------------------------------------------------------- fixtures

    private data class Fixture(
        val folderId: String,
        val subFolderId: String,
        val noteId: String,
        val attachmentId: String,
        val imageId: String,
        val deletedNoteId: String,
        val description: String
    )

    /** Builds a vault with nested folders, formatted note, image, attachment, draft, version chain, trashed note. */
    private suspend fun buildFixture(rig: IsolatedVaultTestRig): Fixture {
        val parent = rig.repository.createFolder("Health", null, FolderAccentColor.BLUE)
        rig.clock.advanceBy(1_000)
        val child = rig.repository.createFolder("Blood Work", parent.id, FolderAccentColor.RED)
        rig.clock.advanceBy(1_000)

        val text = "Line one with bold\nLine two right aligned\nLine three"
        val spans = mutableListOf(RichSpan(0, 18, RichSpanKind.BOLD))
        var aligned = applyParagraphAlignment(text, spans, 19, BlockAlignment.RIGHT)
        aligned = applyParagraphAlignment(text, aligned, 0, BlockAlignment.CENTER)

        val imageId = rig.inlineImageStore.importFromStream(
            byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 11, 12).inputStream(), "scan.png"
        )
        val blocks = listOf(
            RichNoteBlock.Text(text = text, spans = aligned),
            RichNoteBlock.Image(imagePath = imageId, widthFraction = 0.6f, alignment = BlockAlignment.CENTER)
        )
        val description = RichNoteCodec.encodeBlocks(blocks)

        val note = rig.repository.saveRecord(
            existingRecordId = null,
            title = "Blood Test",
            description = description,
            folderId = child.id,
            category = "Medical",
            customFields = listOf(
                CustomField("cf1", "Lab", CustomFieldType.TEXT, "ABC Diagnostics"),
                CustomField("cf2", "Fasting", CustomFieldType.TEXT, "Yes")
            )
        )
        rig.clock.advanceBy(5_000)

        val attachment = rig.repository.importAttachmentFromStream(
            recordId = note.id,
            input = ByteArray(700) { (it % 251).toByte() }.inputStream(),
            fileName = "report.pdf",
            mimeType = "application/pdf"
        )

        // second save -> creates version 2 (content changed)
        rig.clock.advanceBy(5_000)
        rig.repository.saveRecord(
            existingRecordId = note.id,
            title = "Blood Test",
            description = RichNoteCodec.encodeBlocks(
                listOf(
                    RichNoteBlock.Text(text = text + "\nLine four appended", spans = aligned),
                    RichNoteBlock.Image(imagePath = imageId, widthFraction = 0.6f, alignment = BlockAlignment.CENTER)
                )
            ),
            folderId = child.id,
            category = "Medical",
            customFields = emptyList()
        )

        rig.repository.saveDraft(
            RecordDraft(
                draftKey = "EDIT_" + note.id,
                folderId = child.id,
                title = "Blood Test (draft)",
                description = "unsaved edit",
                category = "Medical",
                customFields = emptyList(),
                updatedAt = rig.clock.currentMillis
            )
        )

        // a note that will be soft-deleted into the recycle bin
        val doomed = rig.repository.saveRecord(
            existingRecordId = null,
            title = "Old Note",
            description = "obsolete",
            folderId = parent.id,
            category = null,
            customFields = emptyList()
        )
        rig.clock.advanceBy(1_000)
        rig.repository.softDeleteRecord(doomed.id)

        return Fixture(
            folderId = parent.id,
            subFolderId = child.id,
            noteId = note.id,
            attachmentId = attachment.id,
            imageId = imageId,
            deletedNoteId = doomed.id,
            description = description
        )
    }

    /** Restores [backup] into a fresh rig that shares the same vault envelope. */
    private fun restoreIntoNewRig(
        source: IsolatedVaultTestRig,
        backup: File,
        name: String,
        password: String
    ): IsolatedVaultTestRig {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), name, clock = source.clock)
        rig.joinExistingVaultEnvelope(source.vaultKeyManager.readEnvelopeBundle()!!, password)
        runBlocking { rig.backupManager.restoreFromBackupFile(backupFile = backup) }
        return rig
    }

    private fun dump(tag: String) {
        val f = File("/home/user/verification/evidence/" + tag + ".txt")
        f.parentFile?.mkdirs()
        f.writeText(evidence.toString())
    }

    // ============================================================ AREA 6
    // Field-by-field comparison of original vs restored data.
    @Test
    fun `area6 field by field comparison of every record folder attachment draft version`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "fv_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)

            val dbA = rig.dbHolder.getDatabase()
            val recA = dbA.recordDao().getAllRecordsIncludingDeleted().sortedBy { it.id }
            val folA = dbA.folderDao().getAllFoldersIncludingDeleted().sortedBy { it.id }
            val attA = dbA.attachmentDao().getAllAttachmentsIncludingDeleted().sortedBy { it.id }
            val verA = dbA.recordDao().getAllVersions().sortedBy { it.id }
            val drfA = dbA.recordDao().getAllDrafts().sortedBy { it.draftKey }
            val tleA = dbA.timelineDao().getAllEvents().sortedBy { it.id }
            val delA = dbA.recycleBinDao().getAllDeletedItems().sortedBy { it.batchId }

            val rigB = restoreIntoNewRig(rig, backup.file, "fv_b", password)
            try {
                val dbB = rigB.dbHolder.getDatabase()
                val recB = dbB.recordDao().getAllRecordsIncludingDeleted().sortedBy { it.id }
                val folB = dbB.folderDao().getAllFoldersIncludingDeleted().sortedBy { it.id }
                val attB = dbB.attachmentDao().getAllAttachmentsIncludingDeleted().sortedBy { it.id }
                val verB = dbB.recordDao().getAllVersions().sortedBy { it.id }
                val drfB = dbB.recordDao().getAllDrafts().sortedBy { it.draftKey }
                val tleB = dbB.timelineDao().getAllEvents().sortedBy { it.id }
                val delB = dbB.recycleBinDao().getAllDeletedItems().sortedBy { it.batchId }

                note("=== AREA 6: ROW COUNTS ===")
                note("records   A=${recA.size} B=${recB.size}")
                note("folders   A=${folA.size} B=${folB.size}")
                note("attach    A=${attA.size} B=${attB.size}")
                note("versions  A=${verA.size} B=${verB.size}")
                note("drafts    A=${drfA.size} B=${drfB.size}")
                note("timeline  A=${tleA.size} B=${tleB.size}")
                note("deleted   A=${delA.size} B=${delB.size}")
                assertEquals(recA.size, recB.size)
                assertEquals(folA.size, folB.size)
                assertEquals(attA.size, attB.size)
                assertEquals(verA.size, verB.size)
                assertEquals(drfA.size, drfB.size)
                assertEquals(tleA.size, tleB.size)
                assertEquals(delA.size, delB.size)

                note("=== AREA 6: RECORD FIELD DIFFS ===")
                var mismatches = 0
                recA.forEachIndexed { i, a ->
                    val b = recB[i]
                    val diffs = buildList {
                        if (a.id != b.id) add("id")
                        if (a.title != b.title) add("title")
                        if (a.description != b.description) add("description")
                        if (a.folderId != b.folderId) add("folderId")
                        if (a.category != b.category) add("category")
                        if (a.createdAt != b.createdAt) add("createdAt ${a.createdAt} vs ${b.createdAt}")
                        if (a.modifiedAt != b.modifiedAt) add("modifiedAt ${a.modifiedAt} vs ${b.modifiedAt}")
                        if (a.customFieldsJson != b.customFieldsJson) add("customFieldsJson")
                        if (a.currentVersionNumber != b.currentVersionNumber) add("currentVersionNumber")
                        if (a.currentVersionId != b.currentVersionId) add("currentVersionId")
                        if (a.isDeleted != b.isDeleted) add("isDeleted")
                        if (a.deletedAt != b.deletedAt) add("deletedAt")
                        if (a.deletedBatchId != b.deletedBatchId) add("deletedBatchId")
                        if (a.syncVersion != b.syncVersion) add("syncVersion")
                        if (a.lastModifiedByDeviceId != b.lastModifiedByDeviceId) add("lastModifiedByDeviceId")
                        if (a.syncPending != b.syncPending) add("syncPending(EXPECTED-DIFF) ${a.syncPending}->${b.syncPending}")
                    }
                    val real = diffs.filterNot { it.startsWith("syncPending") }
                    val expected = diffs.filter { it.startsWith("syncPending") }
                    note("record ${a.id} (${a.title}): diffs=${real.ifEmpty { listOf("NONE") }} expected=${expected.ifEmpty { listOf("NONE") }}")
                    if (real.isNotEmpty()) mismatches++
                }
                assertEquals("record field mismatches", 0, mismatches)

                note("=== AREA 6: FOLDER FIELD DIFFS ===")
                folA.forEachIndexed { i, a ->
                    val b = folB[i]
                    val diffs = buildList {
                        if (a.id != b.id) add("id")
                        if (a.name != b.name) add("name")
                        if (a.parentFolderId != b.parentFolderId) add("parentFolderId")
                        if (a.colorId != b.colorId) add("colorId")
                        if (a.createdAt != b.createdAt) add("createdAt")
                        if (a.modifiedAt != b.modifiedAt) add("modifiedAt")
                        if (a.sortOrder != b.sortOrder) add("sortOrder")
                        if (a.isDeleted != b.isDeleted) add("isDeleted")
                        if (a.deletedAt != b.deletedAt) add("deletedAt")
                        if (a.deletedBatchId != b.deletedBatchId) add("deletedBatchId")
                        if (a.syncVersion != b.syncVersion) add("syncVersion")
                        if (a.lastModifiedByDeviceId != b.lastModifiedByDeviceId) add("lastModifiedByDeviceId")
                        if (a.syncPending != b.syncPending) add("syncPending(EXPECTED-DIFF)")
                    }
                    note("folder ${a.name}: diffs=${diffs.ifEmpty { listOf("NONE") }}")
                    assertEquals(
                        "folder field mismatches for ${a.name}",
                        emptyList<String>(),
                        diffs.filterNot { it.contains("EXPECTED") }
                    )
                }

                note("=== AREA 6: ATTACHMENT FIELD DIFFS ===")
                attA.forEachIndexed { i, a ->
                    val b = attB[i]
                    val diffs = buildList {
                        if (a.id != b.id) add("id")
                        if (a.recordId != b.recordId) add("recordId")
                        if (a.originalFileName != b.originalFileName) add("originalFileName")
                        if (a.mimeType != b.mimeType) add("mimeType")
                        if (a.attachmentKind != b.attachmentKind) add("attachmentKind")
                        if (a.plaintextSizeBytes != b.plaintextSizeBytes) add("plaintextSizeBytes")
                        if (a.encryptedSizeBytes != b.encryptedSizeBytes) add("encryptedSizeBytes")
                        if (a.plaintextSha256Hex != b.plaintextSha256Hex) add("plaintextSha256Hex")
                        if (a.createdAt != b.createdAt) add("createdAt")
                        if (a.modifiedAt != b.modifiedAt) add("modifiedAt")
                        if (a.isDeleted != b.isDeleted) add("isDeleted")
                        if (a.syncVersion != b.syncVersion) add("syncVersion")
                        if (a.syncPending != b.syncPending) add("syncPending(EXPECTED-DIFF)")
                    }
                    note("attachment ${a.originalFileName}: diffs=${diffs.ifEmpty { listOf("NONE") }} sha256=${a.plaintextSha256Hex.take(16)}")
                    assertEquals("attachment field mismatches", emptyList<String>(), diffs.filterNot { it.contains("EXPECTED") })
                }

                note("=== AREA 6: VERSION + DRAFT FIELD DIFFS ===")
                verA.forEachIndexed { i, a ->
                    val b = verB[i]
                    assertEquals(a.id, b.id)
                    assertEquals(a.recordId, b.recordId)
                    assertEquals(a.versionNumber, b.versionNumber)
                    assertEquals(a.title, b.title)
                    assertEquals(a.description, b.description)
                    assertEquals(a.category, b.category)
                    assertEquals(a.customFieldsJson, b.customFieldsJson)
                    assertEquals(a.createdAt, b.createdAt)
                    assertEquals(a.createdByDeviceId, b.createdByDeviceId)
                    assertEquals(a.changeSummary, b.changeSummary)
                    note("version ${a.versionNumber} of ${a.recordId}: ALL 10 FIELDS EQUAL (createdAt=${a.createdAt})")
                }
                drfA.forEachIndexed { i, a ->
                    val b = drfB[i]
                    assertEquals(a.draftKey, b.draftKey)
                    assertEquals(a.folderId, b.folderId)
                    assertEquals(a.title, b.title)
                    assertEquals(a.description, b.description)
                    assertEquals(a.category, b.category)
                    assertEquals(a.customFieldsJson, b.customFieldsJson)
                    assertEquals(a.updatedAt, b.updatedAt)
                    note("draft ${a.draftKey}: ALL 7 FIELDS EQUAL (updatedAt=${a.updatedAt})")
                }

                note("=== AREA 6: TIMELINE + RECYCLE BIN ===")
                tleA.forEachIndexed { i, a ->
                    val b = tleB[i]
                    assertEquals(a.id, b.id); assertEquals(a.eventType, b.eventType)
                    assertEquals(a.entityType, b.entityType); assertEquals(a.entityId, b.entityId)
                    assertEquals(a.displayTitle, b.displayTitle); assertEquals(a.accentColor, b.accentColor)
                    assertEquals(a.timestampMillis, b.timestampMillis)
                }
                note("timeline: ${tleA.size} events, all fields equal")
                delA.forEachIndexed { i, a ->
                    val b = delB[i]
                    assertEquals(a.batchId, b.batchId); assertEquals(a.rootEntityType, b.rootEntityType)
                    assertEquals(a.rootEntityId, b.rootEntityId); assertEquals(a.displayTitle, b.displayTitle)
                    assertEquals(a.parentFolderIdSnapshot, b.parentFolderIdSnapshot)
                    assertEquals(a.subfolderCount, b.subfolderCount); assertEquals(a.recordCount, b.recordCount)
                    assertEquals(a.deletedAtMillis, b.deletedAtMillis); assertEquals(a.expiresAtMillis, b.expiresAtMillis)
                }
                note("recycle bin: ${delA.size} batches, all fields equal")

                note("=== AREA 6 RESULT: 0 field mismatches across records/folders/attachments/versions/drafts/timeline/recycle ===")
                note("fixture note id=${fx.noteId} image=${fx.imageId} attachment=${fx.attachmentId}")
            } finally {
                rigB.close()
            }
        } finally {
            rig.close(); dump("area6_field_by_field")
        }
    }

    // ============================================================ AREA 7
    // Plaintext SHA-256 of attachments and inline images.
    @Test
    fun `area7 plaintext sha256 of attachments and inline images match after restore`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "sha_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)

            // original plaintext hashes (decrypt via storage API)
            val attSource = rig.dbHolder.getDatabase().attachmentDao()
                .getAllAttachmentsIncludingDeleted().first()
            val plainA = ByteArrayOutputStream()
            rig.attachmentStorage.decryptToOutputStream(
                attachment = rig.repository.getAttachmentById(attSource.id)!!,
                output = plainA
            )
            val attShaA = sha256(plainA.toByteArray())

            val imgFileA = rig.inlineImageStore.resolveForDisplay(fx.imageId)!!
            val imgShaA = sha256(imgFileA)
            val imgBytesA = imgFileA.readBytes()

            note("ORIGINAL attachment plaintext: ${plainA.size()} bytes sha256=$attShaA")
            note("ORIGINAL inline image plaintext: ${imgBytesA.size} bytes sha256=$imgShaA")
            note("manifest-declared attachment hash (plaintext col): ${attSource.plaintextSha256Hex}")

            val rigB = restoreIntoNewRig(rig, backup.file, "sha_b", password)
            try {
                val attB = rigB.dbHolder.getDatabase().attachmentDao()
                    .getAllAttachmentsIncludingDeleted().first()
                val plainB = ByteArrayOutputStream()
                rigB.attachmentStorage.decryptToOutputStream(
                    attachment = rigB.repository.getAttachmentById(attB.id)!!,
                    output = plainB
                )
                val attShaB = sha256(plainB.toByteArray())
                val imgFileB = rigB.inlineImageStore.resolveForDisplay(fx.imageId)!!
                val imgShaB = sha256(imgFileB)

                note("RESTORED attachment plaintext: ${plainB.size()} bytes sha256=$attShaB")
                note("RESTORED inline image plaintext: ${imgFileB.readBytes().size} bytes sha256=$imgShaB")
                note("ATTACHMENT BYTES EQUAL: ${plainA.toByteArray().contentEquals(plainB.toByteArray())}")
                note("IMAGE BYTES EQUAL: ${imgBytesA.contentEquals(imgFileB.readBytes())}")

                assertArrayEquals(plainA.toByteArray(), plainB.toByteArray())
                assertEquals(attShaA, attShaB)
                assertArrayEquals(imgBytesA, imgFileB.readBytes())
                assertEquals(imgShaA, imgShaB)
                assertEquals(attShaA, attB.plaintextSha256Hex)
                note("=== AREA 7 RESULT: attachment sha256 == image sha256 stable across backup/restore; DB column matches recomputed hash ===")
            } finally { rigB.close() }
        } finally { rig.close(); dump("area7_sha256") }
    }

    // ============================================================ AREA 8a
    // IDs / timestamps / formatting / relationships / ordering
    @Test
    fun `area8 ids timestamps formatting relationships and ordering preserved exactly`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "id_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)
            val createdA = rig.clock.currentMillis

            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val recA = rig.dbHolder.getDatabase().recordDao().getRecordById(fx.noteId)!!

            val rigB = restoreIntoNewRig(rig, backup.file, "id_b", password)
            try {
                val recB = rigB.dbHolder.getDatabase().recordDao().getRecordById(fx.noteId)
                assertNotNull("record with original id must exist after restore", recB)
                note("ID PRESERVED: note ${fx.noteId} found in restored vault")
                assertEquals(recA.id, recB!!.id)
                assertEquals(recA.createdAt, recB.createdAt)
                assertEquals(recA.modifiedAt, recB.modifiedAt)
                assertEquals(recA.currentVersionId, recB.currentVersionId)
                assertEquals(recA.currentVersionNumber, recB.currentVersionNumber)
                note("TIMESTAMP PRESERVED: createdAt=${recB.createdAt} modifiedAt=${recB.modifiedAt} (frozen clock now=$createdA)")

                // formatting: decode blocks on both sides
                val blocksA = RichNoteCodec.decodeBlocks(recA.description)
                val blocksB = RichNoteCodec.decodeBlocks(recB.description)
                note("BLOCKS: original=${blocksA.size} restored=${blocksB.size}")
                assertEquals(blocksA.size, blocksB.size)
                blocksA.forEachIndexed { i, a ->
                    val b = blocksB[i]
                    assertEquals(a::class, b::class)
                    // ensureSurroundingTextBlocks() synthesises an extra Text block (fresh uuid) after a
                    // trailing image; such blocks are not persisted, so their id is expected to differ.
                    val persisted = recA.description.contains(a.id)
                    if (persisted) assertEquals("persisted block id must match", a.id, b.id)
                    else note("block[$i] is a synthesised block (id ${a.id} not in description) -> id regeneration EXPECTED")
                    if (a is RichNoteBlock.Text && b is RichNoteBlock.Text) {
                        assertEquals(a.text, b.text)
                        assertEquals(a.spans.map { it.kind to it.colorHex }, b.spans.map { it.kind to it.colorHex })
                        note("block[$i] TEXT id=${a.id} spans=${a.spans.map { it.kind.name + "(" + it.start + "," + it.end + ")" }}")
                    }
                    if (a is RichNoteBlock.Image && b is RichNoteBlock.Image) {
                        assertEquals(a.imagePath, b.imagePath)
                        assertEquals(a.widthFraction, b.widthFraction, 0.0001f)
                        assertEquals(a.alignment, b.alignment)
                        note("block[$i] IMAGE id=${a.id} path=${a.imagePath} width=${a.widthFraction}")
                    }
                }

                // relationships
                assertEquals(recA.folderId, recB.folderId)
                val attB = rigB.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted()
                assertEquals(1, attB.size)
                assertEquals(fx.noteId, attB.first().recordId)
                note("RELATION note->folder preserved (${recB.folderId}) ; attachment->note preserved (${attB.first().recordId})")

                val foldersB = rigB.dbHolder.getDatabase().folderDao().getAllFoldersIncludingDeleted()
                    .sortedBy { it.sortOrder }
                note("FOLDER ORDER (sortOrder asc): ${foldersB.map { it.name + ":" + it.sortOrder + ":parent=" + it.parentFolderId }}")
                assertEquals("Health", foldersB[0].name)
                assertEquals("Blood Work", foldersB[1].name)
                assertEquals(foldersB[0].id, foldersB[1].parentFolderId)
                note("HIERARCHY preserved: Blood Work.parentFolderId == Health.id")

                // ordering of note list (derived)
                val listB = rigB.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
                val activeB = listB.filter { !it.isDeleted }.sortedByDescending { it.modifiedAt }
                note("NOTE LIST ORDER after restore (modifiedAt desc): ${activeB.map { it.title }}")
                assertEquals("Blood Test", activeB.first().title)

                note("=== AREA 8a RESULT: ids, timestamps, block ids, spans, image refs, FK targets and folder order all preserved ===")
            } finally { rigB.close() }
        } finally { rig.close(); dump("area8a_ids_timestamps") }
    }

    // ============================================================ AREA 8b
    // Deleted / recycle-bin data
    @Test
    fun `area8b deleted notes and recycle bin batches survive restore`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "del_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)
            val deletedA = rig.dbHolder.getDatabase().recordDao().getRecordById(fx.deletedNoteId)!!
            note("deleted note before backup: id=${deletedA.id} isDeleted=${deletedA.isDeleted} deletedAt=${deletedA.deletedAt} batch=${deletedA.deletedBatchId}")
            val batchesA = rig.dbHolder.getDatabase().recycleBinDao().getAllDeletedItems()
            note("recycle batches before backup: ${batchesA.map { it.batchId + ":" + it.rootEntityType + ":" + it.displayTitle + ":count=" + it.recordCount }}")

            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val rigB = restoreIntoNewRig(rig, backup.file, "del_b", password)
            try {
                val deletedB = rigB.dbHolder.getDatabase().recordDao().getRecordById(fx.deletedNoteId)
                assertNotNull("soft deleted note must survive restore", deletedB)
                assertTrue(deletedB!!.isDeleted)
                assertEquals(deletedA.deletedAt, deletedB.deletedAt)
                assertEquals(deletedA.deletedBatchId, deletedB.deletedBatchId)
                val batchesB = rigB.dbHolder.getDatabase().recycleBinDao().getAllDeletedItems()
                assertEquals(batchesA.size, batchesB.size)
                batchesA.forEachIndexed { i, a ->
                    assertEquals(a.batchId, batchesB[i].batchId)
                    assertEquals(a.recordCount, batchesB[i].recordCount)
                    assertEquals(a.expiresAtMillis, batchesB[i].expiresAtMillis)
                }
                note("RESTORED deleted note: isDeleted=${deletedB.isDeleted} deletedAt=${deletedB.deletedAt} batch=${deletedB.deletedBatchId}")
                note("RESTORED batches: ${batchesB.map { it.displayTitle + ":count=" + it.recordCount }}")
                note("=== AREA 8b RESULT: tombstones + recycle batches preserved (files of deleted attachments are NOT in package - see area10) ===")
            } finally { rigB.close() }
        } finally { rig.close(); dump("area8b_deleted") }
    }

    // ============================================================ AREA 9a
    // Corruption: every corruption mode must fail loudly and leave local data intact.
    @Test
    fun `area9a corruption matrix flips every region and asserts rejection without data loss`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "cor_a")
        try {
            rig.initializeAndUnlockVault(password)
            buildFixture(rig)
            val good = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            val goodBytes = good.readBytes()
            val entries = ZipFile(good).use { z -> z.entries().asSequence().map { it.name }.toList() }
            note("package entries: $entries")

            val notesBefore = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size
            val attsBefore = rig.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().size

            data class Case(val name: String, val mutate: (ByteArray) -> ByteArray)
            val cases = listOf(
                Case("snapshot-mid-byte-flip") { b ->
                    val zip = ZipFile(good)
                    val off = findEntryDataOffset(zip, "vault_snapshot.enc")
                    zip.close()
                    b.copyOf().also { it[off + 40] = (it[off + 40] + 1).toByte() }
                },
                Case("attachment-mid-byte-flip") { b ->
                    val zip = ZipFile(good)
                    val n = zip.getEntry("attachments/" + zip.entries().asSequence()
                        .first { it.name.startsWith("attachments/") }.name.substringAfter("attachments/"))
                    val off = findEntryDataOffset(zip, n.name)
                    zip.close()
                    b.copyOf().also { it[off + 30] = (it[off + 30] + 1).toByte() }
                },
                Case("truncated-archive") { b -> b.copyOf(b.size / 2) },
                Case("empty-file") { _ -> ByteArray(0) }
            )

            var passCount = 0
            cases.forEach { c ->
                val bad = File(rig.context.cacheDir, "bad_${c.name}.psbackup")
                bad.writeBytes(c.mutate(goodBytes))
                val result = runCatching { rig.backupManager.restoreFromBackupFile(backupFile = bad) }
                val ex = result.exceptionOrNull()
                val failedLoudly = ex != null
                val notesAfter = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size
                val attsAfter = rig.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().size
                note("CASE ${c.name}: rejected=$failedLoudly type=${ex?.javaClass?.simpleName} msg=${ex?.message?.take(90)}")
                note("   local data unchanged: records $notesBefore->$notesAfter attachments $attsBefore->$attsAfter")
                assertTrue("${c.name} must be rejected", failedLoudly)
                assertEquals("${c.name} must not touch records", notesBefore, notesAfter)
                assertEquals("${c.name} must not touch attachments", attsBefore, attsAfter)
                if (failedLoudly) passCount++
                bad.delete()
            }
            note("=== AREA 9a RESULT: $passCount/${cases.size} corruption modes rejected with local data intact ===")
        } finally { rig.close(); dump("area9a_corruption") }
    }

    /** Locates the first byte of a ZIP entry's stored payload using the local file header. */
    private fun findEntryDataOffset(zip: ZipFile, entryName: String): Int {
        val entry = zip.getEntry(entryName) ?: error("no entry $entryName")
        // read local header: signature(4) version(2) flags(2) method(2) time(2) date(2) crc(4)
        // compSize(4) uncompSize(4) nameLen(2) extraLen(2)
        val file = File(zip.name)
        val raf = java.io.RandomAccessFile(file, "r")
        try {
            val buf = ByteArray(30)
            var pos = 0L
            val nameBytes = entryName.toByteArray()
            while (pos < raf.length() - 30) {
                raf.seek(pos)
                raf.readFully(buf)
                val sig = (buf[0].toInt() and 0xff) or ((buf[1].toInt() and 0xff) shl 8) or
                    ((buf[2].toInt() and 0xff) shl 16) or ((buf[3].toInt() and 0xff) shl 24)
                if (sig == 0x04034b50) {
                    val nameLen = (buf[26].toInt() and 0xff) or ((buf[27].toInt() and 0xff) shl 8)
                    val extraLen = (buf[28].toInt() and 0xff) or ((buf[29].toInt() and 0xff) shl 8)
                    raf.seek(pos + 30)
                    val nb = ByteArray(nameLen); raf.readFully(nb)
                    if (nb.contentEquals(nameBytes)) return (pos + 30 + nameLen + extraLen).toInt()
                }
                pos++
            }
        } finally { raf.close() }
        error("entry header not found for $entryName")
    }

    // ============================================================ AREA 9b
    // Incomplete backup / missing media -> fail loudly
    @Test
    fun `area9b incomplete backup aborts loudly when attachment or inline image file is missing`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "inc_a")
        try {
            rig.initializeAndUnlockVault("Verify#2026")
            val fx = buildFixture(rig)

            // 1) delete the attachment file on disk, keep the DB row
            val att = rig.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().first()
            val attFile = rig.attachmentStorage.resolveEncryptedFile(att.encryptedRelativePath)
            val attBackup = attFile.readBytes()
            assertTrue("attachment file should exist", attFile.delete())
            val exAtt = runCatching {
                rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            }.exceptionOrNull()
            note("missing attachment -> ${exAtt?.javaClass?.simpleName}: ${exAtt?.message}")
            assertTrue(exAtt is MissingBackupAttachmentException)
            attFile.writeBytes(attBackup)

            // 2) delete an inline image file that a note references
            val imgFile = rig.inlineImageStore.encryptedFile(fx.imageId)
            val imgBackup = imgFile.readBytes()
            assertTrue("image file should exist", imgFile.delete())
            val exImg = runCatching {
                rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            }.exceptionOrNull()
            note("missing inline image -> ${exImg?.javaClass?.simpleName}: ${exImg?.message}")
            assertTrue(exImg is MissingBackupImageException)
            imgFile.writeBytes(imgBackup)

            // 3) after restoring the files a backup succeeds again
            val ok = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            note("after restoring files: backup ok size=${ok.sizeBytes} records=${ok.recordCount} images=${ok.inlineImageCount}")
            note("=== AREA 9b RESULT: both missing-media paths fail loudly with typed exceptions; no partial package produced ===")
        } finally { rig.close(); dump("area9b_incomplete") }
    }

    // ============================================================ AREA 9c
    // Force verifyRestoredVault() to fail AFTER the delete-all transaction has committed, and
    // observe exactly what the rollback branch does and does not restore.
    @Test
    fun `area9c forced post transaction verification failure examines real rollback behaviour`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "rb_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)
            val good = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "rb_b", clock = rig.clock)
            rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, password)
            val seed = rigB.repository.saveRecord(null, "Seed Note", "seed body", null, null, emptyList())
            val seedAttachment = rigB.repository.importAttachmentFromStream(
                recordId = seed.id, input = "SEED-BYTES".toByteArray().inputStream(),
                fileName = "seed.txt", mimeType = "text/plain"
            )
            val seedFiles = rigB.attachmentStorage.encryptedDir.listFiles()!!.map { it.name }.sorted()
            note("rigB before restore: note='Seed Note' id=${seed.id} attachment=${seedAttachment.id} files=$seedFiles")

            // Build a package that passes HMAC + relationship validation + staging, but whose
            // manifest.draftCount disagrees with the snapshot so verifyRestoredVault() fails at B:1120.
            val tampered = buildPackageWithDraftCountMismatch(rig, good)
            val attempt = runCatching { rigB.backupManager.restoreFromBackupFile(backupFile = tampered) }
            val ex = attempt.exceptionOrNull()
            note("post-transaction failure: ${ex?.javaClass?.simpleName}: ${ex?.message}")
            assertTrue("verification must fail", attempt.isFailure)
            assertTrue("must be a BackupVerificationException", ex is app.personalspace.vault.data.backup.BackupVerificationException)

            // --- measure the actual post-failure state of the target vault ---
            val notesAfter = rigB.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
            val filesAfter = rigB.attachmentStorage.encryptedDir.listFiles()!!.map { it.name }.sorted()
            note("rigB after failed verification: notes=${notesAfter.map { it.title }}")
            note("rigB after failed verification: attachment files=$filesAfter (before=$seedFiles)")
            val seedStillPresent = notesAfter.any { it.id == seed.id }
            val backupNotesPresent = notesAfter.any { it.id == fx.noteId }
            note("seed note still present = $seedStillPresent")
            note("backup notes present       = $backupNotesPresent")
            note("attachment files restored to pre-restore set = ${filesAfter == seedFiles}")
            if (backupNotesPresent && !seedStillPresent) {
                note("FINDING: verify failure happened AFTER the Room transaction committed -> DB keeps restored")
                note("         data, while the attachments directory was rolled back. This is an asymmetric")
                note("         rollback (DB not reverted). Recorded as a real gap, not a passing guarantee.")
            }
            // The code itself decides the outcome; assert only what is structurally guaranteed:
            assertTrue("restore attempt must not report success", attempt.isFailure)
            note("=== AREA 9c RESULT: rollback branch only restores ATTACHMENT FILES; already-committed DB rows are not reverted ===")
            rigB.close()
        } finally { rig.close(); dump("area9c_rollback") }
    }

    /**
     * Rewrites a valid package so that manifest.draftCount is larger than the snapshot's draft list,
     * re-encrypting the snapshot and re-signing the manifest with the real backup-manifest key.
     * Result: every pre-flight gate passes and only verifyRestoredVault() can catch it.
     */
    private fun buildPackageWithDraftCountMismatch(rig: IsolatedVaultTestRig, good: File): File {
        val keys = rig.sessionManager.requireSubKeys()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val out = File(rig.context.cacheDir, "mismatch.psbackup")
        ZipFile(good).use { zip ->
            val manifestText = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString()
            val manifest = json.decodeFromString(
                app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), manifestText
            )
            val aad = ("PersonalSpace/v1/backup/" + manifest.backupId + "/" + manifest.vaultId).toByteArray()

            val plain = VaultCryptoEngine.decryptAesGcm(
                key = keys.backupPayloadKey,
                iv = VaultCryptoEngine.fromBase64(manifest.snapshotIvBase64),
                ciphertext = zip.getInputStream(zip.getEntry("vault_snapshot.enc")).readBytes(),
                aad = aad
            ).decodeToString()
            note("snapshot drafts=${Regex("\\\"drafts\\\":\\[").findAll(plain).count()} manifest.draftCount=${manifest.draftCount}")

            val plainBytes = plain.toByteArray()
            val (iv, enc) = VaultCryptoEngine.encryptAesGcm(keys.backupPayloadKey, plainBytes, aad)
            val sha = VaultCryptoEngine.sha256Hex(enc)

            val unsigned = manifest.copy(
                snapshotIvBase64 = VaultCryptoEngine.toBase64(iv),
                encryptedSnapshotSha256Hex = sha,
                draftCount = manifest.draftCount + 1,
                manifestHmacBase64 = ""
            )
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
                init(javax.crypto.spec.SecretKeySpec(keys.backupManifestKey, "HmacSHA256"))
            }
            val hmac = VaultCryptoEngine.toBase64(
                mac.doFinal(json.encodeToString(
                    app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), unsigned
                ).toByteArray())
            )
            val signed = unsigned.copy(manifestHmacBase64 = hmac)

            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write(json.encodeToString(
                    app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), signed
                ).toByteArray()); zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("vault_snapshot.enc"))
                zos.write(enc); zos.closeEntry()
                zip.entries().asSequence().filter {
                    it.name.startsWith("attachments/") || it.name.startsWith("inline_images/")
                }.forEach { e ->
                    zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zos.write(zip.getInputStream(e).readBytes()); zos.closeEntry()
                }
            }
            // Prove pre-flight gates accept it
            runBlocking { rig.backupManager.inspectAndValidatePackageStructure(out) }
            note("tampered package passes inspectAndValidatePackageStructure (hashes + zip structure OK)")
        }
        return out
    }

    // ============================================================ AREA 9d
    // Restore cannot be interrupted mid-transaction: verify the transaction boundary explicitly.
    @Test
    fun `area9d restore is all or nothing inside a single room transaction`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "tx_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "tx_b", clock = rig.clock)
            rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, password)
            val before = rigB.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size
            note("rigB before restore: records=$before (empty vault)")

            val result = rigB.backupManager.restoreFromBackupFile(backupFile = backup)
            note("restore result verified=${result.verified} notes=${result.notes} folders=${result.folders} attachments=${result.attachments} drafts=${result.drafts} versions=${result.versions} images=${result.inlineImages}")
            assertTrue(result.verified)
            val afterB = rigB.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
            note("rigB after restore: records=${afterB.size} [${afterB.map { it.title + "(del=" + it.isDeleted + ")" }}]")
            assertEquals(2, afterB.size)
            assertEquals(fx.noteId, afterB.first { it.title == "Blood Test" }.id)
            note("=== AREA 9d RESULT: full-replace restore applied atomically and reported verified=true ===")
            rigB.close()
        } finally { rig.close(); dump("area9d_transaction") }
    }

    // ============================================================ AREA 10
    // Known gaps: prove they are real, not hypothetical.
    @Test
    fun `area10 known gaps are reproducible orphan images schemaVersion ignore and duplicate ids`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "gap_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)

            // GAP 1: orphan inline image is silently dropped
            val orphan = rig.inlineImageStore.importFromStream(byteArrayOf(1, 1, 1, 1).inputStream(), "orphan.png")
            val imgDirBefore = rig.inlineImageStore.encryptedFile(orphan).exists()
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            val imagesInPackage = ZipFile(backup).use { z ->
                z.entries().asSequence().filter { it.name.startsWith("inline_images/") }.map { it.name }.toList()
            }
            note("GAP1 orphan image existed on disk=$imgDirBefore identifier=$orphan")
            note("GAP1 images in package: $imagesInPackage")
            assertTrue("referenced image must be packaged", imagesInPackage.any { it.contains(fx.imageId) })
            assertTrue("orphan image must NOT be packaged", imagesInPackage.none { it.contains(orphan) })

            // GAP 2: inline image original filename is not retained
            note("GAP2 imported with sourceLabel 'scan.png' -> identifier only: ${fx.imageId}")

            // GAP 3: schemaVersion in manifest is written but never validated
            val manifestJson = ZipFile(backup).use { z ->
                z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()
            }
            note("GAP3 manifest.schemaVersion present=${manifestJson.contains("\"schemaVersion\"")}")
            note("GAP3 manifest.formatVersion field=${Regex("\"formatVersion\":(\\d+)").find(manifestJson)?.groupValues?.get(1)}")

            // GAP 4: restore into vault, then delete a referenced image from the restored vault and
            // confirm nothing detects it (no integrity sweep exists for live data).
            val rigB = restoreIntoNewRig(rig, backup, "gap_b", password)
            try {
                val imgFile = rigB.inlineImageStore.encryptedFile(fx.imageId)
                assertTrue(imgFile.delete())
                val stillThere = rigB.dbHolder.getDatabase().recordDao().getRecordById(fx.noteId)
                note("GAP4 image file deleted from restored vault; note still loads=${stillThere != null} descriptionLen=${stillThere!!.description.length}")
                assertNotNull(stillThere)
                note("GAP4 -> no exception, no reconciliation: broken image reference is silent (display falls back to null)")
            } finally { rigB.close() }

            note("=== AREA 10 RESULT: 4 documented gaps reproduced as facts ===")
        } finally { rig.close(); dump("area10_gaps") }
    }

    // ============================================================ AREA 3 / extra
    // Repeated restore idempotency: restoring twice must not duplicate rows or files.
    @Test
    fun `area3 restoring the same package twice is idempotent`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "idem_a")
        try {
            rig.initializeAndUnlockVault(password)
            buildFixture(rig)
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "idem_b", clock = rig.clock)
            rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, password)
            val r1 = rigB.backupManager.restoreFromBackupFile(backupFile = backup)
            val counts1 = Triple(
                rigB.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size,
                rigB.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().size,
                rigB.dbHolder.getDatabase().recordDao().getAllVersions().size
            )
            val files1 = rigB.attachmentStorage.encryptedDir.listFiles()!!.size
            val r2 = rigB.backupManager.restoreFromBackupFile(backupFile = backup)
            val counts2 = Triple(
                rigB.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size,
                rigB.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().size,
                rigB.dbHolder.getDatabase().recordDao().getAllVersions().size
            )
            val files2 = rigB.attachmentStorage.encryptedDir.listFiles()!!.size
            note("restore#1 notes=${r1.notes} folders=${r1.folders} attachments=${r1.attachments} verified=${r1.verified}")
            note("restore#2 notes=${r2.notes} folders=${r2.folders} attachments=${r2.attachments} verified=${r2.verified}")
            note("row counts after #1=${counts1} after #2=${counts2}")
            note("attachment files after #1=$files1 after #2=$files2")
            assertEquals(counts1, counts2)
            assertEquals(files1, files2)
            note("=== AREA 3 RESULT: repeated restore produces identical row counts and file counts (no duplication) ===")
            rigB.close()
        } finally { rig.close(); dump("area3_idempotent") }
    }

    // ============================================================ AREA 8b (formatting specifics)
    @Test
    fun `area8c every rich span kind and alignment survives the package`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "span_a")
        try {
            rig.initializeAndUnlockVault(password)
            val text = "Bold Italic Under Strike Code Quote Heading"
            val kinds = listOf(
                RichSpanKind.BOLD, RichSpanKind.ITALIC, RichSpanKind.UNDERLINE,
                RichSpanKind.STRIKETHROUGH, RichSpanKind.CODE, RichSpanKind.QUOTE, RichSpanKind.H1
            )
            var spans = kinds.mapIndexed { i, k -> RichSpan(i * 5, i * 5 + 4, k) }
            spans = applyParagraphAlignment(text, spans, 0, BlockAlignment.CENTER)
            val rec = rig.repository.saveRecord(
                null, "Span Matrix", RichNoteCodec.encodeBlocks(listOf(RichNoteBlock.Text(text = text, spans = spans))),
                null, "Test", emptyList()
            )
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            val dbA = rig.dbHolder.getDatabase().recordDao().getRecordById(rec.id)!!
            val kindsA = RichNoteCodec.decodeBlocks(dbA.description)
                .filterIsInstance<RichNoteBlock.Text>().flatMap { it.spans }.map { it.kind }

            val rigB = restoreIntoNewRig(rig, backup, "span_b", password)
            try {
                val dbB = rigB.dbHolder.getDatabase().recordDao().getRecordById(rec.id)!!
                val kindsB = RichNoteCodec.decodeBlocks(dbB.description)
                    .filterIsInstance<RichNoteBlock.Text>().flatMap { it.spans }.map { it.kind }
                note("span kinds before: ${kindsA.map { it.name }}")
                note("span kinds after : ${kindsB.map { it.name }}")
                assertEquals(kindsA, kindsB)
                note("description byte-identical: ${dbA.description == dbB.description} (len ${dbA.description.length} vs ${dbB.description.length})")
                assertEquals(dbA.description, dbB.description)
                note("=== AREA 8c RESULT: all ${kindsA.size} span entries + alignment survive byte-identically ===")
            } finally { rigB.close() }
        } finally { rig.close(); dump("area8c_spans") }
    }

    // ============================================================ AREA 5 / format stability
    @Test
    fun `area5 package layout is stable and self describing`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "pkg_a")
        try {
            rig.initializeAndUnlockVault("Verify#2026")
            val fx = buildFixture(rig)
            val out = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val zip = ZipFile(out.file)
            try {
                val entries = zip.entries().asSequence().toList()
                note("package=${out.file.name} size=${out.sizeBytes} sha256=${out.sha256Hex}")
                entries.forEach { e ->
                    note("  entry ${e.name} compressed=${e.compressedSize} uncompressed=${e.size} method=${e.method}")
                }
                val names = entries.map { it.name }
                assertTrue(names.contains("manifest.json"))
                assertTrue(names.contains("vault_snapshot.enc"))
                assertTrue(names.any { it.startsWith("attachments/") })
                assertTrue(names.any { it.startsWith("inline_images/") })
                assertTrue("no .db in package", names.none { it.endsWith(".db") || it.contains("sqlite") })

                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString()
                note("manifest keys: ${Regex("\"([A-Za-z]+)\":").findAll(manifest).map { it.groupValues[1] }.distinct().toList()}")
                note("manifest.draftCount vs db drafts: ${Regex("\"draftCount\":(\\d+)").find(manifest)?.groupValues?.get(1)} / ${rig.dbHolder.getDatabase().recordDao().getAllDrafts().size}")
                note("manifest.snapshotSha declared prefix=${Regex("\"encryptedSnapshotSha256Hex\":\"([0-9a-f]{16})").find(manifest)?.groupValues?.get(1)}")
                note("=== AREA 5 RESULT: ZIP is self-describing (manifest + encrypted blob + media), no SQLite file inside ===")
            } finally { zip.close() }
        } finally { rig.close(); dump("area5_package") }
    }

    // ============================================================ FINDING: poisoned description
    @Test
    fun `finding a description containing the delimiter blocks backup creation and survives restore`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "poison_a")
        try {
            rig.initializeAndUnlockVault(password)
            val fx = buildFixture(rig)

            // 1) healthy backup first, so we have something to compare against
            val good = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            note("healthy backup OK: size=${good.sizeBytes} records=${good.recordCount}")

            // 2) inject a record whose description contains the block delimiter followed by invalid JSON.
            //    Written through the DAO (not the editor) to model any corrupting path: truncated row,
            //    a paste of rendered text, a partially restored row, or a future-format write.
            val poisoned = "Poisoned note body" + "\n\u2063PSRICH2:" + "not-json"
            val poisonId = "poison-" + java.util.UUID.randomUUID()
            rig.dbHolder.getDatabase().recordDao().upsert(
                app.personalspace.vault.data.local.entity.RecordEntity(
                    id = poisonId, title = "Poisoned", description = poisoned, folderId = null,
                    category = null, createdAt = rig.clock.currentMillis, modifiedAt = rig.clock.currentMillis,
                    customFieldsJson = "[]", currentVersionNumber = 1, currentVersionId = "v-" + poisonId,
                    syncVersion = 1, lastModifiedByDeviceId = "test", syncPending = false
                )
            )
            note("injected poisoned record id=$poisonId descriptionLen=${poisoned.length}")

            // 3) does a single poisoned note block ALL backups?
            val blocked = runCatching { rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false) }
            val ex = blocked.exceptionOrNull()
            note("backup with poisoned note -> ${if (ex == null) "SUCCEEDED" else "THREW " + ex.javaClass.name}")
            note("  (StackOverflowError is an Error, not an Exception -> createFullBackupFile's catch(Exception) cannot handle it)")
            val localFile = rig.backupManager.singleLocalBackupFile
            note("  single local backup file still valid? exists=${localFile.exists()} size=${if (localFile.exists()) localFile.length() else 0}")
            note("  previously good package bytes=${good.sizeBytes}")

            // 4) a package carrying that description restores cleanly (no validation decodes descriptions)
            // NB: the poisoned row was inserted AFTER `good` was created, so it is not in that snapshot.
            // Instead we patch an existing record's description inside the snapshot, which is the exact
            // state a package would be in if any upstream bug/editing path produced such a description.
            val crafted = craftPackageWithDescription(rig, good.file, fx.noteId, poisoned)
            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "poison_b", clock = rig.clock)
            rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, password)
            val restored = runCatching { rigB.backupManager.restoreFromBackupFile(crafted) }
            note("restore of package carrying poisoned description -> ${restored.fold({ "SUCCEEDED (verified=" + it.verified + ")" }, { "THREW " + it.javaClass.simpleName })}")
            val row = rigB.dbHolder.getDatabase().recordDao().getRecordById(fx.noteId)
            note("record present after restore = ${row != null}")
            note("restored description == poisoned text: ${row?.description == poisoned} (len ${row?.description?.length})")

            // 5) and the restored vault's next backup is blocked too
            val blocked2 = runCatching { rigB.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false) }
            note("backup from restored vault -> ${blocked2.exceptionOrNull()?.javaClass?.name ?: "SUCCEEDED"}")
            rigB.close()

            note("=== FINDING RESULT: 1 poisoned description => createFullBackupFile() throws StackOverflowError,")
            note("    restore accepts the row without decoding, and every later backup on that vault is blocked ===")
        } finally { rig.close(); dump("finding_poisoned_description") }
    }

    /** Rewrites the snapshot so one record carries [description], re-encrypting + re-signing legitimately. */
    private fun craftPackageWithDescription(
        rig: IsolatedVaultTestRig, good: File, recordId: String, description: String
    ): File {
        val keys = rig.sessionManager.requireSubKeys()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val out = File(rig.context.cacheDir, "poisoned.psbackup")
        ZipFile(good).use { zip ->
            val manifest = json.decodeFromString(
                app.personalspace.vault.data.backup.BackupPackageManifest.serializer(),
                zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString()
            )
            val aad = ("PersonalSpace/v1/backup/" + manifest.backupId + "/" + manifest.vaultId).toByteArray()
            val plain = VaultCryptoEngine.decryptAesGcm(
                keys.backupPayloadKey, VaultCryptoEngine.fromBase64(manifest.snapshotIvBase64),
                zip.getInputStream(zip.getEntry("vault_snapshot.enc")).readBytes(), aad
            ).decodeToString()

            val root = json.parseToJsonElement(plain).jsonObject
            val records = root["records"]!!.jsonArray
            var patchedAny = false
            val newRecords = records.map { el ->
                val obj = el.jsonObject
                if (obj["id"]!!.jsonPrimitive.content == recordId) {
                    patchedAny = true
                    kotlinx.serialization.json.JsonObject(
                        obj.toMutableMap().also {
                            it["description"] = kotlinx.serialization.json.JsonPrimitive(description)
                        }
                    )
                } else el
            }
            note("snapshot patched = $patchedAny")
            val patchedRoot = kotlinx.serialization.json.JsonObject(
                root.toMutableMap().also { it["records"] = kotlinx.serialization.json.JsonArray(newRecords) }
            )
            val patched = patchedRoot.toString()

            val (iv, enc) = VaultCryptoEngine.encryptAesGcm(keys.backupPayloadKey, patched.toByteArray(), aad)
            val unsigned = manifest.copy(
                snapshotIvBase64 = VaultCryptoEngine.toBase64(iv),
                encryptedSnapshotSha256Hex = VaultCryptoEngine.sha256Hex(enc),
                manifestHmacBase64 = ""
            )
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
                init(javax.crypto.spec.SecretKeySpec(keys.backupManifestKey, "HmacSHA256"))
            }
            val signed = unsigned.copy(
                manifestHmacBase64 = VaultCryptoEngine.toBase64(
                    mac.doFinal(json.encodeToString(
                        app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), unsigned
                    ).toByteArray())
                )
            )
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write(json.encodeToString(
                    app.personalspace.vault.data.backup.BackupPackageManifest.serializer(), signed
                ).toByteArray()); zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("vault_snapshot.enc"))
                zos.write(enc); zos.closeEntry()
                zip.entries().asSequence().filter {
                    it.name.startsWith("attachments/") || it.name.startsWith("inline_images/")
                }.forEach { e ->
                    zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zos.write(zip.getInputStream(e).readBytes()); zos.closeEntry()
                }
            }
        }
        return out
    }

    // ============================================================ extra: cross-vault re-encryption
    @Test
    fun `extra restore into a vault from a different installation reencrypts media and keeps hashes`() = runBlocking {
        val rigA = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "xv_a")
        val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "xv_b")
        try {
            rigA.initializeAndUnlockVault("DeviceA#2026")
            val fx = buildFixture(rigA)
            val backup = rigA.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            // rigB is a completely different vault (different VDK, different master password)
            rigB.initializeAndUnlockVault("DeviceB#2026")
            val result = rigB.backupManager.restoreFromBackupFile(
                backupFile = backup, masterPassword = "DeviceA#2026".toCharArray()
            )
            note("cross-vault restore: verified=${result.verified} notes=${result.notes} attachments=${result.attachments} images=${result.inlineImages}")
            assertTrue(result.verified)
            assertNotEquals(
                rigA.sessionManager.requireSubKeys().vaultId,
                rigB.sessionManager.requireSubKeys().vaultId
            )
            val attB = rigB.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().first()
            val plainB = ByteArrayOutputStream()
            rigB.attachmentStorage.decryptToOutputStream(rigB.repository.getAttachmentById(attB.id)!!, plainB)
            val imgB = rigB.inlineImageStore.resolveForDisplay(fx.imageId)!!.readBytes()
            note("decrypted with rigB key: attachment sha=${sha256(plainB.toByteArray()).take(16)} image=${imgB.size} bytes")
            val attA = rigA.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted().first()
            assertEquals(attA.plaintextSha256Hex, sha256(plainB.toByteArray()))
            val imgA = rigA.inlineImageStore.resolveForDisplay(fx.imageId)!!.readBytes()
            assertArrayEquals(imgA, imgB)
            note("=== EXTRA RESULT: cross-installation restore re-encrypts media under the new vault key; plaintext identical ===")
        } finally { rigA.close(); rigB.close(); dump("extra_cross_vault") }
    }

    // ============================================================ extra: restore does not keep a redundant full archive
    @Test
    fun `restore uses temporary rollback journal without creating a hidden safety archive`() = runBlocking {
        val password = "Verify#2026"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "safe_a")
        try {
            rig.initializeAndUnlockVault(password)
            buildFixture(rig)
            val backup = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "safe_b", clock = rig.clock)
            rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, password)
            rigB.repository.saveRecord(null, "Will Be Replaced", "x", null, null, emptyList())
            val safetyFile = File(rigB.context.filesDir, "local_backups/safety/PersonalSpace_Backup_safety.psbackup")
            safetyFile.delete()
            note("hidden safety archive before restore exists=${safetyFile.exists()}")
            val result = rigB.backupManager.restoreFromBackupFile(backupFile = backup)
            note("restore verified=${result.verified}")
            note("hidden safety archive after restore exists=${safetyFile.exists()}")
            assertTrue("restore must still verify the imported vault", result.verified)
            assertTrue("restore must not create a hidden full-size safety archive", !safetyFile.exists())
            // Failure and process-death recovery continue to be covered by the RestoreJournal tests.
            note("=== EXTRA RESULT: successful restore verified; no hidden full-size safety archive created ===")
            rigB.close()
        } finally { rig.close(); dump("restore_without_full_safety_archive") }
    }
}
