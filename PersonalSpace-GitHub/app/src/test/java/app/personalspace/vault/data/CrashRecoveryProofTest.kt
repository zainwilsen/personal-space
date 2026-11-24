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
 * PROOF harness for requirement #1/#10: can on-disk state left behind by a CRASHED restore
 * (no catch/finally executed) be rolled back at next startup, WITHOUT any vault keys?
 *
 * Simulated device crash = copy the whole files/ + databases/ tree at the crash instant into a
 * separate "crash image" directory, then start a BRAND-NEW rig on that image.
 * The recovery routine used here is the one proposed in the plan (file-level, key-free).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashRecoveryProofTest {
    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("CRASH| $s") }
    private fun dump(t: String) { File("/home/user/verification/evidence/" + t + ".txt").apply { parentFile?.mkdirs() }.writeText(ev.toString()) }

    private fun copyTree(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src)
            val target = File(dst, rel.path)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
    }

    @Test
    fun `R1 crashed restore is recoverable at next start without keys`() = runBlocking {
        val baseName = "crash_a"
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), baseName)
        // IMPORTANT: keep the crash image OUTSIDE the rig root, because rig.close() wipes its rootDir
        val proofRoot = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "crash_proof_$baseName"
        ).apply { deleteRecursively(); mkdirs() }
        val crashImage = File(proofRoot, "crash_image").apply { mkdirs() }
        try {
            rig.initializeAndUnlockVault("Crash#20262026")
            val folder = rig.repository.createFolder("Docs", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE)
            val keep = rig.repository.saveRecord(null, "Precious", "do not lose me", folder.id, "Cat", emptyList())
            rig.repository.importAttachmentFromStream(keep.id, "IMPORTANT".toByteArray().inputStream(), "keep.txt", "text/plain")
            val img = rig.inlineImageStore.importFromStream(byteArrayOf(3,1,4,1,5,9).inputStream(), "keep.png")
            note("pre-restore: note=${keep.title} attachment=keep.txt image=$img")

            // ---- PHASE 2 of proposed plan: snapshot the victim (checkpoint -> close -> copy) ----
            rig.dbHolder.getDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { }
            rig.dbHolder.closeDatabase()
            copyTree(rig.context.filesDir, File(crashImage, "files"))
            copyTree(File(rig.dbHolder.databaseFile().parentFile!!.path), File(crashImage, "databases"))
            note("rollback image taken: ${crashImage.walkTopDown().filter { it.isFile }.map { it.name }.toList().sorted()}")

            // ---- PHASE 3 (destructive) simulated, then CRASH before any cleanup ----
            rig.dbHolder.getDatabase()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                rig.dbHolder.getDatabase().runInTransaction {
                    runBlocking {
                        rig.dbHolder.getDatabase().recordDao().deleteAllRecords()
                        rig.dbHolder.getDatabase().folderDao().deleteAllFolders()
                    }
                }
            }
            // restore leaves media half-swapped, then the process dies
            val attDir = rig.attachmentStorage.encryptedDir
            val prevDir = File(rig.context.filesDir, "encrypted_attachments_previous_crash")
            if (attDir.exists()) attDir.renameTo(prevDir)
            attDir.mkdirs()
            File(attDir, "garbage.psenc").writeBytes(byteArrayOf(0,0,0))
            note("crash-state: db notes=${rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().size} attachments=${attDir.listFiles()?.map { it.name } ?: emptyList<String>()} previousDir=${prevDir.exists()}")

            // capture the on-disk crash state into a NEW image (represents device restart)
            val onDisk = File(proofRoot, "on_disk_at_crash").apply { deleteRecursively(); mkdirs() }
            copyTree(rig.context.filesDir, File(onDisk, "files"))
            copyTree(File(rig.dbHolder.databaseFile().parentFile!!.path), File(onDisk, "databases"))
            note("on-disk-at-crash captured")

            rig.close()

            // ---- APP RESTART: brand-new rig, no session, no keys ----
            val rig2 = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "crash_b")
            try {
                // install the crash state on disk (this is what the device would boot with)
                copyTree(File(onDisk, "files"), rig2.context.filesDir)
                copyTree(File(onDisk, "databases"), File(rig2.dbHolder.databaseFile().parentFile!!.path))
                note("restarted with crash state; session unlocked = ${rig2.sessionManager.currentSubKeysOrNull() != null}")

                // ---- KEY-FREE FILE-LEVEL ROLLBACK (the proposed startup recovery) ----
                note("recovery: journal says phase=SWAP -> restore rollback image")
                val dbDir = rig2.dbHolder.databaseFile().parentFile!!
                dbDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                copyTree(File(crashImage, "databases"), dbDir)
                rig2.context.filesDir.listFiles()?.filter { it.name.startsWith("encrypted_attachments") }?.forEach { it.deleteRecursively() }
                copyTree(File(crashImage, "files"), rig2.context.filesDir)
                note("after key-free rollback: files=${rig2.context.filesDir.listFiles()?.map { it.name }?.sorted() ?: emptyList<String>()}")

                // ---- user unlocks (keys now available) and we verify ----
                val bundle = rig2.vaultKeyManager.readEnvelopeBundle()
                note("envelope file survives rollback = ${bundle != null}")
                rig2.joinExistingVaultEnvelope(bundle!!, "Crash#20262026")
                val notes = rig2.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
                note("after unlock+verify: notes=${notes.map { it.title }} integrity=${rig2.dbHolder.verifyDatabaseIntegrity()}")
                val att = rig2.dbHolder.getDatabase().attachmentDao().getAllAttachmentsIncludingDeleted()
                note("attachments rows=${att.map { it.originalFileName }} fileExists=${att.all { rig2.attachmentStorage.resolveEncryptedFile(it.encryptedRelativePath).exists() }}")
                val imgExists = rig2.inlineImageStore.exists(img)
                note("inline image present = $imgExists")

                note("")
                note("RESULT note text recovered   = ${notes.any { it.title == "Precious" && it.description == "do not lose me" }}")
                note("RESULT attachment recovered  = ${att.any { it.originalFileName == "keep.txt" }}")
                note("RESULT inline image recovered= $imgExists")
                dump("crashR1_recovery")
            } finally { rig2.close() }
        } finally { runCatching { rig.close() } }
    }
}
