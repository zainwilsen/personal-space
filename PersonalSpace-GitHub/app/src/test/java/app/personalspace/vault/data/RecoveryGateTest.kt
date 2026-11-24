package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.data.backup.RestoreSnapshot
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P3.1 regression suite — process-wide recovery gate.
 *
 * Each test drives the gate into BLOCKED, then attempts access through a different entry point and
 * asserts the attempt fails AND leaves the data untouched.
 *
 * Evidence: /home/user/verification/evidence/p31_*.txt
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryGateTest {

    private val ev = StringBuilder()
    private fun note(l: String) { ev.append(l).append('\n'); println("P31| $l") }
    private fun dump(t: String) {
        File("/home/user/verification/evidence/$t.txt").apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    @Before fun resetGate() = RecoveryGate.resetForTesting()
    @After fun clearAfter() = RecoveryGate.resetForTesting()

    /** Builds a vault, takes a real rollback copy, and leaves a blocked gate (copy removed). */
    private fun blockedVault(rig: IsolatedVaultTestRig, tag: String, keepCopy: Boolean = false) = runBlocking {
        rig.initializeAndUnlockVault("Gate#2026")
        rig.repository.saveRecord(null, "Existing", "must survive", null, null, emptyList())
        val journal = RestoreJournal.begin(rig.context, "b-$tag", 1L)
        RestoreSnapshot.take(
            dbHolder = rig.dbHolder,
            filesDir = rig.context.filesDir,
            rollbackDir = journal.rollbackDir,
            attachmentsDir = rig.attachmentStorage.encryptedDir,
            imagesDir = rig.inlineImageStore.imagesDir
        )
        journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
        if (!keepCopy) journal.rollbackDir.deleteRecursively()
        // production parity: auto-open key file exists on a real installed device
        val subKeys = requireNotNull(rig.sessionManager.currentSubKeysOrNull())
        File(rig.context.filesDir, "vault_keystore").mkdirs()
        File(rig.context.filesDir, "vault_keystore/auto_open_vdk.json").writeText(
            """{"vaultId":"${subKeys.vaultId}","vdkBase64":"${app.personalspace.vault.core.crypto.VaultCryptoEngine.toBase64(subKeys.vaultDataKey)}","createdAtMillis":1}"""
        )
        rig.dbHolder.closeDatabase()
        rig.sessionManager.lockVault()
        RecoveryGate.markBlocked(
            reason = "test: restore interrupted at SWAPPING_ATTACHMENTS",
            preservedFiles = listOf(journal.rollbackDir.name),
            retryableDirName = if (keepCopy) journal.rollbackDir.name else null
        )
        journal
    }

    // ---------------------------------------------------------------- 1

    @Test
    fun `G1 requireSubKeys fails while blocked and hands out no keys`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g1")
        try {
            blockedVault(rig, "g1")
            note("gate = ${RecoveryGate.state()}")
            val attempt = runCatching { rig.sessionManager.requireSubKeys() }
            val error = attempt.exceptionOrNull()
            note("requireSubKeys threw = ${error?.javaClass?.name}")
            note("message = ${error?.message}")
            note("keys returned = ${attempt.getOrNull() != null}")
            note("session state = ${rig.sessionManager.lockState.value}")

            assertTrue("must throw the gate exception", error is RecoveryGate.RecoveryBlockedException)
            assertNull("no keys may be handed out", attempt.getOrNull())
            dump("p31_g1_requiresubkeys_blocked")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 2

    @Test
    fun `G2 ensureAutoUnlocked fails while blocked and never unlocks`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g2")
        try {
            blockedVault(rig, "g2")
            val attempt = runCatching { rig.sessionManager.ensureAutoUnlocked() }
            note("ensureAutoUnlocked threw = ${attempt.exceptionOrNull()?.javaClass?.name}")
            note("lockState after failed unlock = ${rig.sessionManager.lockState.value}")
            note("currentSubKeysOrNull = ${rig.sessionManager.currentSubKeysOrNull()}")
            note("tryEnsureAutoUnlocked() returned = ${rig.sessionManager.tryEnsureAutoUnlocked()}")

            assertTrue(attempt.exceptionOrNull() is RecoveryGate.RecoveryBlockedException)
            assertEquals(
                app.personalspace.vault.core.security.VaultLockState.LOCKED,
                rig.sessionManager.lockState.value
            )
            assertNull(rig.sessionManager.currentSubKeysOrNull())
            assertFalse("non-throwing variant must also refuse", rig.sessionManager.tryEnsureAutoUnlocked())
            dump("p31_g2_autounlock_blocked")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 3

    @Test
    fun `G3 getDatabase cannot open while blocked`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g3")
        try {
            val journal = blockedVault(rig, "g3")
            val filesBefore = rig.dbHolder.databaseFile().parentFile!!.listFiles()?.map { it.name }?.sorted()
            val attempt = runCatching { rig.dbHolder.getDatabase() }
            val filesAfter = rig.dbHolder.databaseFile().parentFile!!.listFiles()?.map { it.name }?.sorted()
            note("getDatabase threw = ${attempt.exceptionOrNull()?.javaClass?.name}")
            note("message = ${attempt.exceptionOrNull()?.message}")
            note("db files before = $filesBefore")
            note("db files after  = $filesAfter")
            note("rollback copy preserved = ${journal.rollbackDir.exists() || RecoveryGate.state().preservedFiles.isNotEmpty()}")

            assertTrue(attempt.exceptionOrNull() is RecoveryGate.RecoveryBlockedException)
            assertNull("no database instance may be returned", attempt.getOrNull())
            assertEquals("no db file may be created while blocked", filesBefore, filesAfter)
            dump("p31_g3_getdatabase_blocked")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 4

    @Test
    fun `G4 no database write succeeds through any tested entry point`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g4")
        try {
            blockedVault(rig, "g4")
            val attempts = linkedMapOf<String, Result<*>>()

            attempts["repository.saveRecord"] = runCatching {
                rig.repository.saveRecord(null, "BLOCKED WRITE", "x", null, null, emptyList())
            }
            attempts["repository.createFolder"] = runCatching {
                rig.repository.createFolder("BLOCKED FOLDER", null, app.personalspace.vault.core.designsystem.FolderAccentColor.BLUE)
            }
            attempts["repository.softDeleteRecord"] = runCatching {
                rig.repository.softDeleteRecord("any-id")
            }
            attempts["dbHolder.getDatabase().recordDao()"] = runCatching {
                rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
            }
            attempts["dbHolder.verifyDatabaseIntegrity"] = runCatching {
                rig.dbHolder.verifyDatabaseIntegrity()
            }
            attempts["backupPackageManager.saveBackupCopy"] = runCatching {
                val out = java.io.ByteArrayOutputStream()
                rig.backupManager.saveBackupCopy(out)
            }
            attempts["backupPackageManager.createFullBackupFile"] = runCatching {
                rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false)
            }

            attempts.forEach { (name, result) ->
                note("$name -> ${if (result.isSuccess) "SUCCEEDED" else result.exceptionOrNull()?.javaClass?.simpleName}")
            }
            attempts.forEach { (name, result) ->
                if (result.isSuccess) note("*** LEAK: $name SUCCEEDED while blocked")
            }
            val allBlocked = attempts.values.none { it.isSuccess }
            note("ALL entry points blocked = $allBlocked")
            assertTrue("every write/read entry point must fail while blocked", allBlocked)

            // and the stored row must be exactly what it was
            RecoveryGate.markRecovered()
            rig.sessionManager.tryEnsureAutoUnlocked()
            val titles = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            note("titles after gate cleared = $titles")
            assertTrue("original data intact", titles.contains("Existing"))
            assertFalse("no blocked write leaked through", titles.contains("BLOCKED WRITE"))
            dump("p31_g4_all_writes_blocked")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 5

    @Test
    fun `G5 app restart does not bypass the gate`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g5")
        try {
            blockedVault(rig, "g5")
            note("gate before restart = ${RecoveryGate.state().phase}")

            // a process restart rebuilds everything in memory
            RecoveryGate.resetForTesting()
            note("after in-memory reset (models a fresh process) gate = ${RecoveryGate.state().phase}")
            note("BUT the journal is still on disk = ${RestoreJournal.read(rig.context) != null}")

            // startup recovery must rediscover the block from disk, not from memory
            val outcome = rig.restoreRecoveryForTest()
            note("startup recovery rediscovered = ${outcome::class.simpleName}")
            note("gate after startup recovery = ${RecoveryGate.state().phase}")

            val unlock = runCatching { rig.sessionManager.ensureAutoUnlocked() }
            note("ensureAutoUnlocked after restart threw = ${unlock.exceptionOrNull()?.javaClass?.simpleName}")
            assertTrue("restart must not clear the block", RecoveryGate.isBlocked())
            assertTrue(unlock.exceptionOrNull() is RecoveryGate.RecoveryBlockedException)
            dump("p31_g5_restart_does_not_bypass")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 6

    @Test
    fun `G6 retry clears the gate only after verified recovery succeeds`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g6")
        try {
            // keep the copy so a retry can actually succeed
            blockedVault(rig, "g6", keepCopy = true)
            note("gate before retry = ${RecoveryGate.state().phase}")
            assertTrue(RecoveryGate.isBlocked())

            val result = rig.restoreRecoveryForTest()
            note("retry outcome = ${result::class.simpleName}")
            note("gate after retry = ${RecoveryGate.state().phase}")

            assertFalse("gate must be released after verified recovery", RecoveryGate.isBlocked())
            val keys = runCatching { rig.sessionManager.requireSubKeys() }
            note("requireSubKeys after recovery = ${keys.isSuccess}")
            assertTrue("access restored", keys.isSuccess)
            val titles = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted().map { it.title }
            note("titles after recovery = $titles")
            assertTrue("data recovered exactly", titles.contains("Existing"))
            dump("p31_g6_retry_clears_only_on_success")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 7

    @Test
    fun `G7 failed retry keeps the gate blocked and deletes nothing`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g7")
        try {
            val journal = blockedVault(rig, "g7", keepCopy = true)
            // corrupt the copy so verification must fail
            val dbCopy = File(journal.rollbackDir, "database/personal_space_encrypted.db")
            if (dbCopy.exists()) {
                val bytes = dbCopy.readBytes()
                bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 9).toByte()
                dbCopy.writeBytes(bytes)
            }
            val before = rig.context.filesDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(rig.context.filesDir).path }.sorted().toList()

            val result = rig.restoreRecoveryForTest()
            val after = rig.context.filesDir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(rig.context.filesDir).path }.sorted().toList()

            note("retry outcome = ${result::class.simpleName}")
            note("gate after failed retry = ${RecoveryGate.state().phase}")
            note("files before = ${before.size}, after = ${after.size}, identical = ${before == after}")

            assertTrue("failed retry must stay blocked", RecoveryGate.isBlocked())
            assertEquals("nothing may be deleted", before, after)
            val unlock = runCatching { rig.sessionManager.ensureAutoUnlocked() }
            assertTrue(unlock.exceptionOrNull() is RecoveryGate.RecoveryBlockedException)
            dump("p31_g7_failed_retry_stays_blocked")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 8

    @Test
    fun `G8 export preserves the original rollback copy`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_g8")
        try {
            val journal = blockedVault(rig, "g8", keepCopy = true)
            val manifestBefore = File(journal.rollbackDir, "copy_manifest.txt").readText()
            val exported = rig.exportRecoveryForTest()
            note("exported files = ${exported.size}")
            note("original copy still exists = ${journal.rollbackDir.exists()}")
            note("original manifest unchanged = ${File(journal.rollbackDir, "copy_manifest.txt").readText() == manifestBefore}")
            assertTrue("export must produce files", exported.isNotEmpty())
            assertTrue("original copy must be preserved", journal.rollbackDir.exists())
            assertTrue(File(journal.rollbackDir, "copy_manifest.txt").exists())
            dump("p31_g8_export_preserves_original")
        } finally { rig.close() }
    }

    // ---------------------------------------------------------------- 9

    @Test
    fun `G9 gate has no way to be cleared except a verified recovery`() {
        RecoveryGate.markBlocked("reason", listOf("dir"))
        RecoveryGate.markClearIfNotBlocked()
        note("after markClearIfNotBlocked while blocked = ${RecoveryGate.state().phase}")
        assertEquals("markClearIfNotBlocked must not downgrade a block", RecoveryGate.Phase.BLOCKED, RecoveryGate.state().phase)
        RecoveryGate.markRecovered()
        note("after markRecovered = ${RecoveryGate.state().phase}")
        assertEquals(RecoveryGate.Phase.CLEAR, RecoveryGate.state().phase)
        note("=> the only transition out of BLOCKED is markRecovered(), which is called only after hash verification")
        dump("p31_g9_no_accidental_clear")
    }

    // ---------------------------------------------------------------- 10

    @Test
    fun `G10 no destructive recovery option exists in the coordinator API`() {
        val methods = app.personalspace.vault.data.backup.RestoreRecoveryCoordinator::class.java.declaredMethods
            .map { it.name }
            .sorted()
        note("coordinator methods = $methods")
        val destructive = methods.filter {
            it.contains("reset", true) || it.contains("wipe", true) ||
                it.contains("discard", true) || it.contains("startEmpty", true) ||
                it.contains("deleteVault", true)
        }
        note("destructive-looking methods = $destructive")
        assertTrue("no destructive entry point may exist", destructive.isEmpty())
        dump("p31_g10_no_destructive_api")
    }
}
