package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.backup.RecoveryPendingException
import app.personalspace.vault.data.backup.RestoreGuard
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P2 / correction #2 — it must be impossible to discard an existing vault while a recovery journal
 * or rollback copy exists, and no path may offer "start empty".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreGuardTest {

    private val ev = StringBuilder()
    private fun note(line: String) { ev.append(line).append('\n'); println("GUARD| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    @Test
    fun `G1 a clean vault reports CLEAR and allows restore`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "guard_g1")
        try {
            rig.initializeAndUnlockVault("Guard#2026")
            note("state = " + RestoreGuard.state(rig.context))
            note("isClear = " + RestoreGuard.isClear(rig.context))
            note("unresolved rollback dirs = " + RestoreGuard.unresolvedRollbackDirs(rig.context).map { it.name })
            assertEquals(RestoreGuard.State.CLEAR, RestoreGuard.state(rig.context))
            assertTrue(RestoreGuard.isClear(rig.context))
            note("=> restore allowed on a clean vault")
            dump("p2_G1_guard_clear")
        } finally { rig.close() }
    }

    @Test
    fun `G2 an unfinished journal BLOCKS a new restore before anything is read`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "guard_g2")
        try {
            rig.initializeAndUnlockVault("Guard#2026")
            rig.repository.saveRecord(null, "Existing", "must survive", null, null, emptyList())
            val pkg = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            // simulate a restore that died mid-swap
            val journal = RestoreJournal.begin(rig.context, "crashed-backup", 1L)
            journal.advance(RestorePhase.SNAPSHOT_TAKEN)
            journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
            File(journal.rollbackDir, "database").mkdirs()

            note("state = " + RestoreGuard.state(rig.context))
            note("journal phase on disk = " + RestoreJournal.read(rig.context)?.phase)

            val blocked = runCatching { rig.backupManager.restoreFromBackupFile(pkg, "Guard#2026".toCharArray()) }
            val ex = blocked.exceptionOrNull()
            note("new restore blocked = ${ex is RecoveryPendingException}")
            note("message = " + ex?.message)

            val notes = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            note("existing notes still present = $notes")
            note("rollback dir still on disk = " + journal.rollbackDir.exists())
            note("journal still on disk = " + (RestoreJournal.read(rig.context) != null))

            assertTrue("a new restore must be refused", ex is RecoveryPendingException)
            assertEquals(RestoreGuard.State.RECOVERY_PENDING, RestoreGuard.state(rig.context))
            assertTrue("existing user data must still be there", notes.contains("Existing"))
            assertTrue("rollback copy must never be deleted by the guard", journal.rollbackDir.exists())
            assertNotNull("journal must stay on disk", RestoreJournal.read(rig.context))
            dump("p2_G2_unfinished_journal_blocks")
        } finally { rig.close() }
    }

    @Test
    fun `G3 a failed rollback reports RECOVERY_FAILED and preserves everything`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "guard_g3")
        try {
            rig.initializeAndUnlockVault("Guard#2026")
            rig.repository.saveRecord(null, "Precious", "do not delete", null, null, emptyList())
            val journal = RestoreJournal.begin(rig.context, "failed-backup", 1L)
            journal.advance(RestorePhase.ROLLBACK_FAILED)

            note("state = " + RestoreGuard.state(rig.context))
            val ex = runCatching { RestoreGuard.requireClear(rig.context) }.exceptionOrNull()
            note("requireClear threw = " + ex?.javaClass?.simpleName)
            note("message = " + ex?.message)
            note("rollback copy preserved = " + journal.rollbackDir.exists())
            note("note rows still readable = " +
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title })

            assertEquals(RestoreGuard.State.RECOVERY_FAILED, RestoreGuard.state(rig.context))
            assertTrue(ex is RecoveryPendingException)
            assertTrue(journal.rollbackDir.exists())
            assertTrue(
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().any { it.title == "Precious" }
            )
            dump("p2_G3_recovery_failed_preserved")
        } finally { rig.close() }
    }

    @Test
    fun `G4 a rollback copy without a journal is never treated as disposable`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "guard_g4")
        try {
            rig.initializeAndUnlockVault("Guard#2026")
            val orphan = File(rig.context.filesDir, RestoreJournal.ROLLBACK_DIR_PREFIX + "orphan").apply { mkdirs() }
            File(orphan, "database").mkdirs()
            File(orphan, "database/personal_space_encrypted.db").writeBytes(ByteArray(16))

            note("journal present = " + (RestoreJournal.read(rig.context) != null))
            note("rollback dirs found = " + RestoreGuard.unresolvedRollbackDirs(rig.context).map { it.name })
            note("state = " + RestoreGuard.state(rig.context))
            note("isClear = " + RestoreGuard.isClear(rig.context))

            assertEquals(RestoreGuard.State.RECOVERY_PENDING, RestoreGuard.state(rig.context))
            assertFalse(RestoreGuard.isClear(rig.context))
            assertTrue("the orphan copy must be preserved", orphan.exists())
            dump("p2_G4_orphan_copy_not_disposable")
        } finally { rig.close() }
    }

    @Test
    fun `G5 rollback copies may only be deleted for COMMITTED or ROLLED_BACK`() {
        val allowed = RestorePhase.values().filter { RestoreGuard.mayDeleteRollbackCopy(it) }
        val refused = RestorePhase.values().filterNot { RestoreGuard.mayDeleteRollbackCopy(it) }
        note("phases that permit deletion = $allowed")
        note("phases that REFUSE deletion = $refused")
        note("=> 'start empty' / cleanup can never drop a copy for PREPARED..ROLLBACK_FAILED")
        assertEquals(listOf(RestorePhase.COMMITTED, RestorePhase.ROLLED_BACK), allowed)
        listOf(
            RestorePhase.PREPARED,
            RestorePhase.SNAPSHOT_TAKEN,
            RestorePhase.SWAPPING_ATTACHMENTS,
            RestorePhase.SWAPPING_IMAGES,
            RestorePhase.DB_TXN,
            RestorePhase.VERIFIED,
            RestorePhase.ROLLBACK_FAILED
        ).forEach { assertFalse("must refuse deletion for $it", RestoreGuard.mayDeleteRollbackCopy(it)) }
        dump("p2_G5_deletion_policy")
    }
}
