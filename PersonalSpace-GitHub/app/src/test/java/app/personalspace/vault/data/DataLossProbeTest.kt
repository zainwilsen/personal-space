package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.domain.model.BackupKind
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichNoteCodec
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataLossProbeTest {
    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("PROBE| $s") }
    private fun dump(t: String) { File("/home/user/verification/evidence/" + t + ".txt").apply { parentFile?.mkdirs() }.writeText(ev.toString()) }

    @Test
    fun `probe A - note in recycle bin loses its inline images from the package`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dl_a")
        try {
            rig.initializeAndUnlockVault("ProbePass#2026")
            val folder = rig.repository.createFolder("F", null, FolderAccentColor.BLUE)
            val img = rig.inlineImageStore.importFromStream(byteArrayOf(1,2,3,4,5).inputStream(), "a.png")
            val desc = RichNoteCodec.encodeBlocks(listOf(
                RichNoteBlock.Text(text = "keep me"),
                RichNoteBlock.Image(imagePath = img, widthFraction = 1f)
            ))
            val rec = rig.repository.saveRecord(null, "Has Image", desc, folder.id, null, emptyList())
            note("created note=${rec.id} image=${img} imageOnDisk=${rig.inlineImageStore.exists(img)}")

            // user deletes the note -> it goes to the Recycle Bin (soft delete, recoverable 30 days)
            rig.clock.advanceBy(1000)
            rig.repository.softDeleteRecord(rec.id)
            val afterDelete = rig.dbHolder.getDatabase().recordDao().getRecordById(rec.id)!!
            note("after soft delete: isDeleted=${afterDelete.isDeleted} imageOnDiskStill=${rig.inlineImageStore.exists(img)}")

            val out = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val images = ZipFile(out.file).use { z -> z.entries().asSequence().filter { it.name.startsWith("inline_images/") }.map { it.name }.toList() }
            note("images inside package: $images")
            note("image ${img} packaged = ${images.any { it.contains(img) }}")

            // restore into a fresh install
            val rigB = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dl_b", clock = rig.clock)
            rigB.joinExistingVaultEnvelope(rig.vaultKeyManager.readEnvelopeBundle()!!, "ProbePass#2026")
            rigB.backupManager.restoreFromBackupFile(out.file)
            val r2 = rigB.dbHolder.getDatabase().recordDao().getRecordById(rec.id)
            note("restored note present=${r2 != null} isDeleted=${r2?.isDeleted}")
            note("restored image on disk = ${rigB.inlineImageStore.exists(img)}")
            // user restores from recycle bin
            val batch = rigB.dbHolder.getDatabase().recycleBinDao().getAllDeletedItems().firstOrNull()
            note("recycle batch present=${batch != null}")
            if (batch != null) {
                rigB.repository.restoreDeletedBatch(batch.batchId)
                val r3 = rigB.dbHolder.getDatabase().recordDao().getRecordById(rec.id)!!
                note("after un-delete: isDeleted=${r3.isDeleted}")
                note("after un-delete: image on disk = ${rigB.inlineImageStore.exists(img)}  <-- FALSE means PERMANENT DATA LOSS")
            }
            dump("probeA_deleted_note_images")
            rigB.close()
        } finally { rig.close() }
    }

    @Test
    fun `probe C - a failed backup still overwrites the single local backup file`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dl_d")
        try {
            rig.initializeAndUnlockVault("ProbePass#2026")
            rig.repository.saveRecord(null, "Good", "body", null, null, emptyList())
            val good = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val local = rig.backupManager.singleLocalBackupFile
            val goodSha = good.sha256Hex
            note("good backup: size=${good.sizeBytes} sha=${goodSha.take(16)}")
            note("local file exists=${local.exists()} size=${local.length()}")

            // now make the NEXT backup fail AFTER it has already renamed over the live file:
            // inject a record whose description breaks decodeBlocks (the confirmed recursion bug)
            rig.clock.advanceBy(1000)
            rig.repository.saveRecord(null, "New Note", "body2", null, null, emptyList())
            val badId = "bad-" + java.util.UUID.randomUUID()
            rig.dbHolder.getDatabase().recordDao().upsert(
                app.personalspace.vault.data.local.entity.RecordEntity(
                    id = badId, title = "Bad", description = "x" + "\n\u2063PSRICH2:" + "oops",
                    folderId = null, category = null,
                    createdAt = rig.clock.currentMillis, modifiedAt = rig.clock.currentMillis,
                    customFieldsJson = "[]", currentVersionNumber = 1, currentVersionId = "v-" + badId,
                    syncVersion = 1, lastModifiedByDeviceId = "t", syncPending = false
                )
            )
            val attempt = runCatching { rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false) }
            note("second backup -> ${attempt.exceptionOrNull()?.javaClass?.name ?: "SUCCEEDED"}")
            note("local file after failure: exists=${local.exists()} size=${local.length()}")
            val nowSha = if (local.exists()) app.personalspace.vault.core.crypto.VaultCryptoEngine.sha256Hex(local.readBytes()) else "n/a"
            note("local file sha now=${nowSha.take(16)} (was ${goodSha.take(16)})")
            note("last-known-good backup still intact = ${nowSha == goodSha}")
            // Can the surviving file still be restored?
            val stillValid = runCatching { rig.backupManager.inspectAndValidatePackageStructure(local) }
            note("surviving local file still validates = ${stillValid.isSuccess}")
            dump("probeC_failed_backup_overwrites")
        } finally { rig.close() }
    }

    @Test
    fun `probe D - interrupted backup leaves stale tmp files and no cleanup`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dl_e")
        try {
            rig.initializeAndUnlockVault("ProbePass#2026")
            rig.repository.saveRecord(null, "A", "b", null, null, emptyList())
            // simulate a crash mid-backup: a leftover .tmp of the exact naming scheme createFullBackupFile uses
            val dir = rig.backupManager.localBackupsDir
            val stale = File(dir, "PersonalSpace_Backup.psbackup.11111111-2222-3333-4444-555555555555.tmp")
            stale.writeBytes(ByteArray(4096) { 7 })
            note("stale tmp created: ${stale.name} size=${stale.length()}")
            rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            val leftover = dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.map { it.name + ":" + it.length() } ?: emptyList()
            note("after a successful backup, leftover .tmp files = $leftover")
            note("=> no cleanup routine exists for abandoned .tmp files (grep: no sweep over localBackupsDir)")
            dump("probeD_stale_tmp")
        } finally { rig.close() }
    }

    @Test
    fun `probe B - local backup file is replaced before validation`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "dl_c")
        try {
            rig.initializeAndUnlockVault("ProbePass#2026")
            val n = rig.repository.saveRecord(null, "V1", "body", null, null, emptyList())
            val first = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            note("backup#1 file=${first.file.name} size=${first.sizeBytes} sha=${first.sha256Hex.take(16)}")
            val local = rig.backupManager.singleLocalBackupFile
            note("singleLocalBackupFile path=${local.absolutePath} exists=${local.exists()}")
            val shaAfter1 = first.sha256Hex

            rig.clock.advanceBy(1000)
            rig.repository.saveRecord(null, "V2", "body2", null, null, emptyList())
            val second = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            note("backup#2 size=${second.sizeBytes} sha=${second.sha256Hex.take(16)}")
            note("first backup bytes still recoverable? sha changed=${shaAfter1 != second.sha256Hex}")
            val files = rig.backupManager.localBackupsDir.listFiles()?.map { it.name } ?: emptyList()
            note("files in local_backups dir: $files")
            note("generations kept = ${files.count { it.endsWith(".psbackup") && !it.contains("safety") }}")
            dump("probeB_backup_history")
        } finally { rig.close() }
    }
}
