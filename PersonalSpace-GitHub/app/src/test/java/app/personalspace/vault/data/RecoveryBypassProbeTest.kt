package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.data.backup.RestoreRecovery
import app.personalspace.vault.data.backup.RestoreSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P3 VERIFICATION PROBE (read-only investigation, no production change).
 *
 * Question: does P3's "blocked" state actually prevent database writes and auto-unlock, or can the
 * app reach the database through a path that never asks the recovery coordinator?
 *
 * Each probe deliberately goes around MainActivity to test the *guard*, not the UI ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryBypassProbeTest {

    @org.junit.Before
    fun resetGateBeforeEach() {
        // the gate is process-wide by design, so it MUST be reset between test cases;
        // a leaked BLOCKED state is exactly what makes a later test look like a bypass
        app.personalspace.vault.core.security.RecoveryGate.resetForTesting()
    }

    @org.junit.After
    fun resetGateAfterEach() {
        app.personalspace.vault.core.security.RecoveryGate.resetForTesting()
    }

    private val ev = StringBuilder()
    private fun note(l: String) { ev.append(l).append('\n'); println("BYPASS| $l") }
    private fun dump(t: String) {
        File("/home/user/verification/evidence/$t.txt").apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    /** Builds a vault, takes a real rollback copy, then destroys the copy so recovery must BLOCK. */
    private fun makeBlockedVault(rig: IsolatedVaultTestRig, tag: String) = runBlocking {
        rig.initializeAndUnlockVault("Bypass#2026")
        rig.repository.saveRecord(null, "Existing", "user data", null, null, emptyList())
        val journal = RestoreJournal.begin(rig.context, "b-$tag", 1L)
        RestoreSnapshot.take(
            dbHolder = rig.dbHolder,
            filesDir = rig.context.filesDir,
            rollbackDir = journal.rollbackDir,
            attachmentsDir = rig.attachmentStorage.encryptedDir,
            imagesDir = rig.inlineImageStore.imagesDir
        )
        journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
        journal.rollbackDir.deleteRecursively()   // copy gone => recovery must block

        // PRODUCTION PARITY: on a real device the first launch writes auto_open_vdk.json, which is
        // what makes every later launch auto-unlock.
        writeAutoOpenVaultFile(rig)
        rig.sessionManager.lockVault()
        rig.dbHolder.closeDatabase()

        // PRODUCTION ORDER (P3.1): the gate is raised by Application.onCreate BEFORE anything is
        // allowed to unlock. Raising it here reproduces that ordering exactly.
        app.personalspace.vault.core.security.RecoveryGate.markBlocked(
            reason = "Restore was interrupted during phase SWAPPING_ATTACHMENTS and its rollback " +
                "copy is missing. No file was deleted; the vault is unchanged.",
            preservedFiles = listOf(journal.rollbackDir.name),
            retryableDirName = null
        )
    }

    /**
     * Models the on-device state: auto_open_vdk.json contains the vault data key, which is what
     * VaultKeyManager.ensureAutoUnlockedVault() reads to unlock without any user input.
     */
    private fun writeAutoOpenVaultFile(rig: IsolatedVaultTestRig) {
        // must be called while the session is still unlocked
        val subKeys = requireNotNull(rig.sessionManager.currentSubKeysOrNull()) {
            "probe needs an unlocked session to model the auto-open key file"
        }
        val dir = File(rig.context.filesDir, "vault_keystore").apply { mkdirs() }
        val record = """{"vaultId":"${subKeys.vaultId}","vdkBase64":"${app.personalspace.vault.core.crypto.VaultCryptoEngine.toBase64(subKeys.vaultDataKey)}","createdAtMillis":1}"""
        File(dir, "auto_open_vdk.json").writeText(record)
        println("BYPASS| auto_open_vdk.json written (production parity), vaultId=${subKeys.vaultId}")
    }

    /**
     * Runs startup recovery EXACTLY as production does: onAppStart, then publish the verdict to the
     * process-wide RecoveryGate (this mirrors PersonalSpaceApp.onCreate after P3.1).
     */
    private fun recover(rig: IsolatedVaultTestRig): RestoreRecovery.Outcome =
        RestoreRecovery.onAppStart(
            filesDir = rig.context.filesDir,
            dbDir = rig.dbHolder.databaseFile().parentFile!!,
            attachmentsDir = rig.attachmentStorage.encryptedDir,
            imagesDir = rig.inlineImageStore.imagesDir,
            closeDatabase = { rig.dbHolder.closeDatabase() }
        ).also { outcome ->
            when (outcome) {
                is RestoreRecovery.Outcome.Blocked -> app.personalspace.vault.core.security.RecoveryGate.markBlocked(
                    reason = outcome.reason,
                    preservedFiles = outcome.preservedFiles,
                    retryableDirName = outcome.retryableDirName
                )
                else -> app.personalspace.vault.core.security.RecoveryGate.markRecovered()
            }
        }

    // ---------------------------------------------------------------- B1

    @Test
    fun `B1 requireSubKeys auto-unlocks and opens the database even when recovery is blocked`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "bypass_b1")
        try {
            makeBlockedVault(rig, "b1")
            val outcome = recover(rig)
            note("recovery outcome = $outcome")
            note("is Blocked = ${outcome is RestoreRecovery.Outcome.Blocked}")

            // Nothing in the app calls the coordinator here — this is what PersonalSpaceApp and
            // NavHost do on every launch.
            note("session state BEFORE touching requireSubKeys = ${rig.sessionManager.lockState.value}")
            val keys = runCatching { rig.sessionManager.requireSubKeys() }
            note("requireSubKeys() succeeded = ${keys.isSuccess}")
            org.junit.Assert.assertFalse("P3.1: requireSubKeys must fail", keys.isSuccess)
            note("session state AFTER requireSubKeys = ${rig.sessionManager.lockState.value}")

            val dbWritable = runCatching {
                rig.repository.saveRecord(null, "WRITTEN WHILE BLOCKED", "should not happen", null, null, emptyList())
            }
            note("DB WRITE while blocked succeeded = ${dbWritable.isSuccess}")
            org.junit.Assert.assertFalse("P3.1: no write may succeed while blocked", dbWritable.isSuccess)

            // Inspect the stored rows only after releasing the gate, because even a READ is refused
            // while blocked (that is the point of the gate).
            app.personalspace.vault.core.security.RecoveryGate.markRecovered()
            rig.sessionManager.tryEnsureAutoUnlocked()
            val titles = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            note("notes after gate cleared = $titles")
            org.junit.Assert.assertFalse("no blocked write may have leaked", titles.contains("WRITTEN WHILE BLOCKED"))

            note("")
            note("P3.1 RESULT: requireSubKeys() now throws RecoveryBlockedException while blocked,")
            note("             and the database write is refused at VaultDatabaseHolder.getDatabase().")
            note("             (Before P3.1 this same probe measured 'DB WRITE while blocked succeeded = true'.)")
            dump("b1_bypass_requiresubkeys")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- B2

    @Test
    fun `B2 ensureAutoUnlocked unlocks while blocked`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "bypass_b2")
        try {
            makeBlockedVault(rig, "b2")
            val outcome = runCatching { recover(rig) }
            note("recover() ok = ${outcome.isSuccess} (${outcome.exceptionOrNull()?.javaClass?.simpleName})")
            note("state before = ${rig.sessionManager.lockState.value}")
            val unlock = runCatching { rig.sessionManager.ensureAutoUnlocked() }
            note("ensureAutoUnlocked threw = ${unlock.exceptionOrNull()?.javaClass?.simpleName}")
            note("state after ensureAutoUnlocked = ${rig.sessionManager.lockState.value}")
            val write = runCatching {
                rig.repository.saveRecord(null, "AFTER AUTO UNLOCK", "x", null, null, emptyList())
            }
            note("write succeeded = ${write.isSuccess}")
            org.junit.Assert.assertFalse("P3.1: write must fail after blocked auto-unlock", write.isSuccess)
            app.personalspace.vault.core.security.RecoveryGate.markRecovered()
            dump("b2_bypass_autounlock")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- B3

    @Test
    fun `B3 starting a second restore is blocked but reading and writing is not`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "bypass_b3")
        try {
            makeBlockedVault(rig, "b3")
            val outcome = runCatching { recover(rig) }
            note("recover() ok = ${outcome.isSuccess}")

            // (a) building a backup opens the database, so it must be refused while blocked
            val backupWhileBlocked = runCatching {
                rig.backupManager.createFullBackupFile(
                    app.personalspace.vault.domain.model.BackupKind.FULL_MANUAL, recordHistoryInDb = false
                ).file
            }
            note("backup creation while blocked = ${backupWhileBlocked.isSuccess}")
            org.junit.Assert.assertFalse(
                "P3.1: backup creation opens the DB and must be refused", backupWhileBlocked.isSuccess
            )

            // (b) build one legitimately with the gate clear, then re-block and prove restore is refused
            app.personalspace.vault.core.security.RecoveryGate.markRecovered()
            rig.sessionManager.tryEnsureAutoUnlocked()
            val legit = rig.backupManager.createFullBackupFile(
                app.personalspace.vault.domain.model.BackupKind.FULL_MANUAL, recordHistoryInDb = false
            ).file
            app.personalspace.vault.core.security.RecoveryGate.markBlocked(
                reason = "re-blocked for the restore attempt", preservedFiles = listOf("x")
            )
            val restore = runCatching {
                rig.backupManager.restoreFromBackupFile(legit, "Bypass#2026".toCharArray())
            }
            note("RESTORE while blocked = ${restore.isSuccess}")
            org.junit.Assert.assertFalse("P3.1: restore must be refused", restore.isSuccess)
            note("=> P2 RestoreGuard blocks new restores; P3.1 RecoveryGate blocks ordinary access too")
            dump("b3_restore_blocked_but_not_access")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- B4

    @Test
    fun `B4 a database opened before recovery can silently resurface a stale handle`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "bypass_b4")
        try {
            makeBlockedVault(rig, "b4")
            note("outcome = " + recover(rig))
            note("db open helper present = " + File(rig.dbHolder.databaseFile().parentFile, "person_space_encrypted.db".let { "personal_space_encrypted.db" }).exists())
            val open = runCatching { rig.dbHolder.getDatabase() }
            note("getDatabase() while blocked succeeded = ${open.isSuccess}")
            org.junit.Assert.assertFalse("P3.1: getDatabase must refuse", open.isSuccess)
            note("")
            note("P3.1 RESULT: the block IS now enforced at VaultDatabaseHolder.getDatabase(), so the")
            note("             caller cannot reopen the database while recovery is unresolved.")
            dump("b4_no_enforcement_at_dbholder")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- B5

    @Test
    fun `B5 process restart does not re-run or persist the blocked state`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "bypass_b5")
        try {
            makeBlockedVault(rig, "b5")
            val o1 = runCatching { recover(rig) }
            note("first run outcome ok = ${o1.isSuccess} (${o1.exceptionOrNull()?.javaClass?.simpleName})")
            // ---- simulate a cold start, in the same order Application.onCreate uses ----
            // (1) memory is gone -> gate resets to CLEAR
            app.personalspace.vault.core.security.RecoveryGate.resetForTesting()
            note("after process death, gate = ${app.personalspace.vault.core.security.RecoveryGate.state().phase}")
            note("journal still on disk = " + (RestoreJournal.read(rig.context) != null))

            rig.sessionManager.lockVault()
            rig.sessionManager.refreshInitializationState()
            note("after simulated restart, session state = ${rig.sessionManager.lockState.value}")

            // (2) Application.onCreate re-runs recovery BEFORE any unlock, rebuilding the gate from disk
            val o2 = runCatching { recover(rig) }
            note("startup recovery on cold start ok = ${o2.isSuccess}")
            note("gate after cold-start recovery = ${app.personalspace.vault.core.security.RecoveryGate.state().phase}")

            // (3) only now may anything try to unlock
            val unlockAttempt = runCatching { rig.sessionManager.ensureAutoUnlocked() }
            note("after restart ensureAutoUnlocked threw = ${unlockAttempt.exceptionOrNull()?.javaClass?.simpleName}")
            note("lock state after failed unlock = ${rig.sessionManager.lockState.value}")
            org.junit.Assert.assertTrue("P3.1: restart must not unlock", unlockAttempt.isFailure)

            note("=> the journal is persisted, and recovery re-derived the block on cold start")
            note("")
            note("P3.1 RESULT: the gate lives in RecoveryGate (process-wide) and is re-established from")
            note("             the on-disk journal by Application.onCreate, so a restart does not bypass it.")
            dump("b5_blocked_state_not_process_wide")
        } finally { rig.close() }
    }
}
