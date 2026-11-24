package app.personalspace.vault.data.attachment

import app.personalspace.vault.data.backup.InlineImageStore
import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * P6 — reference counting for every file the vault keeps outside the database.
 *
 * WHY THIS EXISTS
 *
 * The database is the authority for which media file is still needed:
 *   * `attachments.encryptedRelativePath` → `files/encrypted_attachments/<attachmentId>.psenc`
 *   * `RichNoteBlock.Image.imagePath` inside `records.description` / `record_drafts.description`
 *     → `files/note_images/<identifier>.psenc`
 *
 * Both sets are read from **rows of any deletion state**: an attachment that is only soft-deleted
 * still owns its ciphertext, and the image of a note that sits in the recycle bin must not be
 * removed (P5 proved those images must survive, be backed up and be restorable).
 *
 * THE RULE
 *
 * A file may be unlinked only when (a) no entity references it, and (b) the decision was taken while
 * holding [withMediaLock] — the same lock every path takes that publishes a new reference. That is
 * what makes "check, then delete" safe: a reference cannot appear between the check and the unlink.
 *
 * WHY A [Mutex] AND NOT A `ReentrantReadWriteLock`
 *
 * Measured while writing these tests: a `ReentrantReadWriteLock` is **thread-owned**, and the code
 * inside the critical section suspends (Room reads). When the coroutine resumes on a different
 * thread, unlocking throws `IllegalMonitorStateException` — the lock would silently stop protecting
 * anything. A coroutine [Mutex] is owned by the coroutine, not the thread, so it survives suspension
 * and keeps the invariant. Only the small in-memory flags stay non-suspending.
 */
object MediaReferenceIndex {

    /** Serialises reference *changes* against reference *decisions* (coroutine-safe). */
    private val mediaLock = Mutex()

    /** Held while a restore/recovery installs references, so deletion decisions wait for it. */
    private val restoreLock = Mutex()

    suspend fun <T> withMediaLock(block: suspend () -> T): T = mediaLock.withLock { block() }

    suspend fun <T> withRestoreLock(block: suspend () -> T): T = restoreLock.withLock { block() }

    // ---------------------------------------------------------------- reference snapshots

    /**
     * Every attachment path currently referenced, by ANY row (active or soft-deleted).
     * Compared by file name, because that is how the storage layer resolves a path.
     */
    suspend fun referencedAttachmentFileNames(db: PersonalSpaceDatabase): Set<String> =
        db.attachmentDao().getAllAttachmentsIncludingDeleted()
            .map { File(it.encryptedRelativePath).name }
            .toSet()

    /** Attachment row ids that point at [fileName] (any deletion state) — the survivors' claim. */
    suspend fun attachmentRowsClaiming(db: PersonalSpaceDatabase, fileName: String): List<String> =
        db.attachmentDao().getAllAttachmentsIncludingDeleted()
            .filter { File(it.encryptedRelativePath).name == fileName }
            .map { it.id }

    /** One entity that still needs an inline image. */
    data class ImageHolder(
        /** record id, or the draft key for an unsaved draft */
        val ownerId: String,
        /** human readable, for the message shown to the user */
        val label: String,
        val isDraft: Boolean
    )

    /**
     * Every inline-image identifier referenced by any record body **or draft body**, in any
     * deletion state. Drafts matter: the editor autosaves the in-progress body, which can reference
     * an image the note itself no longer references.
     */
    suspend fun referencedImageIdentifiers(db: PersonalSpaceDatabase): Set<String> {
        val out = LinkedHashSet<String>()
        fun collect(rawBody: String) {
            runCatching {
                RichNoteCodec.decodeBlocks(rawBody)
                    .filterIsInstance<RichNoteBlock.Image>()
                    .forEach { out.add(InlineImageStore.normalizeImageReference(it.imagePath)) }
            }
        }
        db.recordDao().getAllRecordsIncludingDeleted().forEach { collect(it.description) }
        db.recordDao().getAllDrafts().forEach { collect(it.description) }
        return out
    }

    /** All entities that still reference [identifier], with ids so the caller can exclude one. */
    suspend fun imageHolders(db: PersonalSpaceDatabase, identifier: String): List<ImageHolder> {
        val holders = ArrayList<ImageHolder>()
        fun references(rawBody: String): Boolean = runCatching {
            RichNoteCodec.decodeBlocks(rawBody)
                .filterIsInstance<RichNoteBlock.Image>()
                .any { InlineImageStore.normalizeImageReference(it.imagePath) == identifier }
        }.getOrDefault(false)

        db.recordDao().getAllRecordsIncludingDeleted().forEach { record ->
            if (references(record.description)) {
                holders.add(
                    ImageHolder(
                        ownerId = record.id,
                        label = if (record.isDeleted) "recycle-bin note \"${record.title}\""
                        else "note \"${record.title}\"",
                        isDraft = false
                    )
                )
            }
        }
        db.recordDao().getAllDrafts().forEach { draft ->
            if (references(draft.description)) {
                holders.add(
                    ImageHolder(
                        ownerId = draft.draftKey,
                        label = "unsaved draft \"${draft.title}\"",
                        isDraft = true
                    )
                )
            }
        }
        return holders
    }

    /**
     * Draft keys that hold unsaved editor state for [recordId]. The editor uses the convention
     * `"EDIT_<recordId>"` (see the note editor), so this is a lookup rather than a guess.
     */
    suspend fun draftKeysForRecord(db: PersonalSpaceDatabase, recordId: String): List<String> =
        db.recordDao().getAllDrafts()
            .filter { it.draftKey == "EDIT_$recordId" }
            .map { it.draftKey }

    // ---------------------------------------------------------------- restore-in-flight flag

    /**
     * Set while a restore/recovery is known to be in flight. Permanent deletion refuses instead of
     * racing the staging swap, which installs references the deletion cannot see.
     */
    private val restoreInFlight = ConcurrentHashMap.newKeySet<String>()

    fun markRestoreInFlight(token: String) { restoreInFlight.add(token) }

    fun clearRestoreInFlight(token: String) { restoreInFlight.remove(token) }

    fun isRestoreInFlight(): Boolean = restoreInFlight.isNotEmpty()
}
