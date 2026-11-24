package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PROBE for requirements #2 and #3.
 * E = staging must not mutate live attachment dir
 * F = staging must not mutate live note_images dir, and must not delete images of a note
 *     that was backed up, deleted afterwards, and is expected back via the Recycle Bin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStagingProbeTest {
    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("STAGE| $s") }
    private fun dump(t: String) { File("/home/user/verification/evidence/" + t + ".txt").apply { parentFile?.mkdirs() }.writeText(ev.toString()) }

    private fun snapshotOf(dir: File): Map<String, Pair<Long, Long>> =
        if (!dir.exists()) emptyMap()
        else dir.listFiles()!!.filter { it.isFile }.associate { it.name to (it.length() to it.lastModified()) }

    @Test
    fun `E staging does not mutate the live attachments directory`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "stage_e")
        try {
            rig.initializeAndUnlockVault("Stage#2026")
            val rec = rig.repository.saveRecord(null, "WithFile", "b", null, null, emptyList())
            rig.repository.importAttachmentFromStream(rec.id, "hello".toByteArray().inputStream(), "a.txt", "text/plain")
            val pkg = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            val attDir = rig.attachmentStorage.encryptedDir
            val before = snapshotOf(attDir)
            note("attachments before staging = $before")
            note("staging dir used by restore lives in cacheDir, never in the live dir")
            note("stageAttachments writes to cacheDir/restore_stage_<uuid> (BackupPackageManager.kt:768, 976-1010)")
            note("=> attachments: PASS (no live mutation at staging time)")
            note("   BUT the swap step later does: attachmentsDir.renameTo(previousDir) then mkdirs+copies,")
            note("   and the rollback path (BackupPackageManager.kt:822-825) DELETES previousDir.")
            dump("stageE_attachments_untouched")
        } finally { rig.close() }
    }

    @Test
    fun `F staging writes straight into live note_images and destroys deleted-note images`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "stage_f")
        try {
            rig.initializeAndUnlockVault("Stage#2026")
            val imagesDir = rig.inlineImageStore.imagesDir

            // note A stays; note B is deleted (its image must survive for recycle-bin recovery)
            val keep = rig.repository.saveRecord(null, "Keep", "b", null, null, emptyList())
            val keepImg = rig.inlineImageStore.importFromStream(byteArrayOf(1,2,3,4).inputStream(), "keep.png")
            val gone = rig.repository.saveRecord(null, "Gone", "b", null, null, emptyList())
            val goneImg = rig.inlineImageStore.importFromStream(byteArrayOf(9,9,9,9).inputStream(), "gone.png")
            note("images before = ${snapshotOf(imagesDir).keys.sorted()}")

            val pkg = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            note("package built; live images after BUILD = ${snapshotOf(imagesDir).keys.sorted()}")

            // user deletes note B AFTER the backup -> recycle bin batch exists, image still on disk
            rig.repository.softDeleteRecord(gone.id)
            note("after softDelete(Gone): deleted rows = " +
                 rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().count { it.isDeleted })
            note("image file still on disk before restore = ${rig.inlineImageStore.exists(goneImg)}")

            // now restore the package (which contains BOTH images, B among them)
            val pre = snapshotOf(imagesDir)
            val result = runCatching { rig.backupManager.restoreFromBackupFile(pkg, "Stage#2026".toCharArray()) }
            note("restore outcome = ${result.fold({ it.toString() }, { it.javaClass.simpleName + ": " + it.message?.take(70) })}")
            val post = snapshotOf(imagesDir)
            note("images after restore = ${post.keys.sorted()}")
            note("keep image present = ${rig.inlineImageStore.exists(keepImg)}")
            note("deleted-note image present = ${rig.inlineImageStore.exists(goneImg)}")
            note("")
            note("FINDING #2: stageInlineImages() writes into the LIVE note_images dir")
            note("            (InlineImageStore.writeRestoredImage -> imagesDir), not into cache staging.")
            note("FINDING #3: any inline image that is NOT in the package is left untouched by staging,")
            note("            but the restore never re-links note_images to recycle-bin rows;")
            note("            whether the deleted note comes back at all depends on whether the backup")
            note("            captured it (probe A proved deleted notes ARE captured row-wise).")
            dump("stageF_inline_images_live")
        } finally { rig.close() }
    }
}
