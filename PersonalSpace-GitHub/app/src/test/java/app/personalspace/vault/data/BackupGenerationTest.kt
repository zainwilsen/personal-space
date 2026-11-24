package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.backup.BackupGenerationManager
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
 * P7 — retained backup generations.
 *
 * Every assertion here is about behaviour a user can lose data over:
 * verify-before-promote, never deleting the last good backup, corrupt generations not counting, and
 * each retained generation being able to restore on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupGenerationTest {

    private val ev = StringBuilder()
    private lateinit var rig: IsolatedVaultTestRig
    private lateinit var manager: BackupGenerationManager
    private var clock = 1_700_000_000_000L

    private fun note(line: String) { ev.append(line).append('\n'); println("P7| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    @Before
    fun setUp() = runBlocking {
        rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p7_gen")
        rig.initializeAndUnlockVault(PASSWORD)
        manager = BackupGenerationManager(
            generationsDir = File(rig.context.filesDir, "local_backups/generations"),
            now = { clock }
        )
    }

    @After
    fun tearDown() { rig.close() }

    /**
     * Real verifier: a generation is only valid if the package opens with the same checks a restore
     * uses (inspection + manifest HMAC + key unwrap).
     */
    private fun realVerifier() = BackupGenerationManager.BackupVerifier { file ->
        // the same pre-restore check a real restore performs: open the zip, check entries,
        // verify the manifest HMAC and that the portable key unwrap is structurally sound
        runCatching {
            // throws when the zip is malformed, an entry is missing, the manifest HMAC does not
            // match, or the key wrap cannot be parsed
            runBlocking { rig.backupManager.inspectAndValidatePackageStructure(file) }
            null
        }.getOrElse { "inspection failed: ${it.message}" }
    }

    private fun makeBackup(name: String): File = runBlocking {
        clock += 60_000
        val out = rig.backupManager.createFullBackupFile(
            BackupKind.FULL_MANUAL,
            targetFile = File(rig.context.cacheDir, name),
            recordHistoryInDb = false
        )
        out.file
    }

    // ---------------------------------------------------------------- 3 generations, each restorable

    @Test
    fun `P7-G1 three generations are retained and each one restores on its own`() = runBlocking {
        // three different vault states, so the generations are genuinely different packages
        val first = rig.repository.saveRecord(null, "Gen One", "alpha", null, null, emptyList())
        val g1 = makeBackup("g1.psbackup")
        rig.repository.saveRecord(null, "Gen Two", "beta", null, null, emptyList())
        val g2 = makeBackup("g2.psbackup")
        rig.repository.saveRecord(null, "Gen Three", "gamma", null, null, emptyList())
        val g3 = makeBackup("g3.psbackup")

        val promotedIds = listOf(
            promoteOrFail(g1, "G1"), promoteOrFail(g2, "G2"), promoteOrFail(g3, "G3")
        )
        assertEquals("three generations must be retained", 3, manager.retained().size)
        note("retained = " + manager.retained().map { it.fileName })
        note("latest good = ${manager.latestKnownGood()?.id}")

        // trim with keep=3 must keep exactly those three
        val trimmed = manager.trim(keep = 3)
        note("trim: retained=${trimmed.retainedIds.size} removed=${trimmed.removedIds.size} " +
            "blocked=${trimmed.blockedReason}")
        assertNull("trim must not be blocked", trimmed.blockedReason)
        assertEquals("nothing may be removed when keeping 3 of 3", 0, trimmed.removedIds.size)
        assertEquals(3, trimmed.retainedIds.size)

        // EACH generation must independently restore the state it captured
        val expectations = listOf(
            Triple(manager.retained().first { it.id == promotedIds[2] }.fileName, "Gen Three", 3),
            Triple(manager.retained().first { it.id == promotedIds[1] }.fileName, "Gen Two", 2),
            Triple(manager.retained().first { it.id == promotedIds[0] }.fileName, "Gen One", 1)
        )
        for ((fileName, expectedNewest, expectedCount) in expectations) {
            // The vault is cleared by the restore itself, so the note set observed afterwards is
            // exactly what that generation carries — proving each generation is self-contained rather
            // than merely additive. The retained file is copied out first because a restore consumes
            // the file it is given; the RETAINED generation must itself stay intact.
            val retainedFile = File(manager.generationsDir, fileName)
            val packageFile = File(rig.context.cacheDir, "restore_$fileName")
            retainedFile.copyTo(packageFile, overwrite = true)
            val result = rig.backupManager.restoreFromBackupFile(packageFile, PASSWORD.toCharArray())
            val titles = rig.dbHolder.getDatabase().recordDao()
                .getAllRecordsIncludingDeleted().map { it.title }.sorted()
            note("restored $fileName -> notes=${result.notes} titles=$titles")
            assertEquals("every generation must restore its own note count", expectedCount, result.notes)
            assertTrue("the note added in that generation must be present", titles.contains(expectedNewest))
            assertEquals("this generation's own note set must be exactly what it captured",
                expectedCount, titles.size)
            assertTrue("the retained generation file must survive a restore from it",
                retainedFile.exists())
            assertEquals("and must be byte-identical to what was retained",
                manager.sha256(retainedFile),
                manager.retained().first { it.fileName == fileName }.sha256Hex)
        }
        note("=> all 3 generations restored independently")
        dump("p7_G1_three_generations")
    }

    @Test
    fun `P7-G2 the canonical PersonalSpace_Backup placeholder keeps its name and path`() = runBlocking {
        makeBackup("c1.psbackup")
        val canonical = rig.backupManager.singleLocalBackupFile
        note("canonical path = ${canonical.absolutePath}")
        note("canonical name = ${canonical.name}")
        assertEquals("the canonical file name must not change",
            "PersonalSpace_Backup.psbackup", canonical.name)
        assertTrue("it must live directly in local_backups",
            canonical.parentFile!!.name == "local_backups")
        assertEquals("the generation folder must be a sibling, not a replacement",
            "generations", BackupGenerationManager.GENERATIONS_DIR_NAME)
        dump("p7_G2_canonical_preserved")
    }

    // ---------------------------------------------------------------- verify before promote

    @Test
    fun `P7-G3 a candidate that fails verification is never promoted and is quarantined`() = runBlocking {
        val good = makeBackup("good.psbackup")
        val goodBytes = good.readBytes()          // promotion MOVES the candidate, so keep a copy
        val goodHash = manager.sha256(good)
        val recoveredId = promoteOrFail(good, "good")

        // a file with the right name but corrupted content
        val bad = File(rig.context.cacheDir, "bad.psbackup")
        bad.writeBytes(goodBytes)
        // flip a byte deep inside so the size stays the same
        val bytes = bad.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        bad.writeBytes(bytes)

        val result = manager.promote(
            candidate = bad,
            expectedSha256 = goodHash,               // claimed hash of the GOOD file
            kind = "FULL_MANUAL",
            verifier = realVerifier()
        )
        note("promotion of the corrupted candidate: promoted=${result.promoted} reason=${result.failureReason}")
        assertFalse("a corrupted candidate must not be promoted", result.promoted)
        assertTrue("the reason must be explicit", result.failureReason!!.contains("hash mismatch"))
        assertEquals("only the good generation may be retained", 1, manager.retained().size)
        assertEquals(recoveredId, manager.retained().single().id)
        assertEquals("the corrupt file must be quarantined", 1, manager.readIndex().rejected.size)
        assertTrue("the rejected file must physically exist in rejected/",
            manager.rejectedDir.listFiles()!!.any { it.name.endsWith("bad.psbackup") })
        assertFalse("it must no longer be in the cache dir as a candidate", bad.exists())
        dump("p7_G3_verify_before_promote")
    }

    @Test
    fun `P7-G4 a candidate whose bytes match but which does not open is rejected by the verifier`() =
        runBlocking {
            val good = makeBackup("real.psbackup")
            val hash = manager.sha256(good)
            val result = manager.promote(
                candidate = good,
                expectedSha256 = hash,
                kind = "FULL_MANUAL",
                verifier = { "manifest HMAC mismatch (injected)" }
            )
            note("verifier rejected: promoted=${result.promoted} reason=${result.failureReason}")
            assertFalse("the verifier decides, not the hash alone", result.promoted)
            assertTrue(result.failureReason!!.contains("manifest HMAC"))
            assertEquals("nothing may be retained", 0, manager.retained().size)
            dump("p7_G4_verifier_authority")
        }

    // ---------------------------------------------------------------- retention safety

    @Test
    fun `P7-G5 rotation never removes the last good backup and never goes below three`() = runBlocking {
        repeat(3) { i -> promoteOrFail(makeBackup("r$i.psbackup"), "r$i") }
        assertEquals(3, manager.retained().size)

        val refusedLow = manager.trim(keep = 1)
        note("keep=1 -> blocked: ${refusedLow.blockedReason}, removed=${refusedLow.removedIds.size}")
        assertNotNull("trim below the minimum must be refused", refusedLow.blockedReason)
        assertEquals("nothing may be removed", 0, refusedLow.removedIds.size)
        assertEquals("all three must survive", 3, manager.retained().size)

        // add a fourth then trim to 3: exactly the oldest is removed, newest kept
        val newestId = promoteOrFail(makeBackup("r3.psbackup"), "r3")
        val trimmed = manager.trim(keep = 3)
        note("after 4 -> trim(3): removed=${trimmed.removedIds.size}, retained=${trimmed.retainedIds}")
        assertEquals("exactly one removed", 1, trimmed.removedIds.size)
        assertEquals("three retained", 3, trimmed.retainedIds.size)
        assertTrue("the newest generation must still be retained",
            trimmed.retainedIds.contains(newestId))
        assertEquals("the last-known-good pointer must remain valid",
            newestId, manager.latestKnownGood()!!.id)
        dump("p7_G5_rotation_safety")
    }

    @Test
    fun `P7-G6 a corrupt generation is excluded from retention and blocks rotation until acknowledged`() =
        runBlocking {
            val ids = (0 until 3).map { i -> promoteOrFail(makeBackup("c$i.psbackup"), "c$i") }
            // corrupt one generation's bytes on disk behind the index's back
            val victim = manager.retained().last()
            val victimFile = File(manager.generationsDir, victim.fileName)
            val bytes = victimFile.readBytes()
            bytes[10] = (bytes[10] + 7).toByte()
            victimFile.writeBytes(bytes)
            note("corrupted on disk: ${victim.fileName}")

            val failures = manager.verifyAll(realVerifier())
            note("verifyAll failures = $failures")
            assertEquals("exactly one generation must fail", 1, failures.size)
            assertEquals("it must be reported with a reason", true, failures[victim.id]!!.isNotEmpty())
            assertEquals("only two may remain retained", 2, manager.retained().size)
            assertEquals("the corrupt one must be quarantined", 1, manager.readIndex().rejected.size)
            assertFalse("and must not be in the retained list",
                manager.retained().any { it.id == victim.id })

            val blocked = manager.trim(keep = 3)
            note("rotation while a corrupt generation is unacknowledged -> ${blocked.blockedReason}")
            assertNotNull("rotation must block on an unacknowledged corrupt generation",
                blocked.blockedReason)

            val acked = manager.acknowledgeRejected()
            note("acknowledged = $acked")
            assertEquals(1, acked)
            val after = manager.trim(keep = 3)
            note("after acknowledgement -> blocked=${after.blockedReason}")
            assertNull("rotation must be allowed once acknowledged", after.blockedReason)
            assertEquals("the corrupt file must never count as a retained generation",
                2, manager.retained().size)
            dump("p7_G6_corrupt_quarantined")
        }

    @Test
    fun `P7-G7 verifyAll reports a deleted generation and the unknown-file scan is honest`() =
        runBlocking {
            val ids = (0 until 2).map { i -> promoteOrFail(makeBackup("d$i.psbackup"), "d$i") }
            val victim = manager.retained().first()   // newest
            val survivor = manager.retained().last()  // oldest, a different generation
            assertTrue("the two generations must be distinct", victim.id != survivor.id)
            File(manager.generationsDir, victim.fileName).delete()
            val failures = manager.verifyAll(realVerifier())
            note("missing file failure = $failures")
            assertEquals(1, failures.size)
            assertTrue(failures[victim.id]!!.contains("missing"))
            assertEquals("the survivor must remain", 1, manager.retained().size)
            assertEquals("the untouched generation must be the one that survives",
                survivor.id, manager.retained().single().id)

            // a stray file that the index does not know about is reported, not silently adopted
            File(manager.generationsDir, "stray-copy.psbackup").writeBytes(ByteArray(32))
            val untracked = manager.untrackedFiles()
            note("untracked = ${untracked.map { it.name }}")
            assertEquals(listOf("stray-copy.psbackup"), untracked.map { it.name })
            dump("p7_G7_missing_and_untracked")
        }

    @Test
    fun `P7-G8 generation rotation is independent of Google Drive retention`() = runBlocking {
        repeat(3) { i -> promoteOrFail(makeBackup("i$i.psbackup"), "i$i") }
        val prefsBefore = rig.context.getSharedPreferences("ps_backup_state", android.content.Context.MODE_PRIVATE).all.toMap()
        manager.trim(keep = 3)
        val prefsAfter = rig.context.getSharedPreferences("ps_backup_state", android.content.Context.MODE_PRIVATE).all.toMap()
        note("backup-state prefs before = ${prefsBefore.keys}")
        note("backup-state prefs after  = ${prefsAfter.keys}")
        assertEquals("local rotation must not touch cloud retention state", prefsBefore, prefsAfter)
        // and the class has no Drive dependency at all: it only ever sees a directory
        note("BackupGenerationManager has no Drive/Auth/Preferences dependency by construction")
        dump("p7_G8_rotation_independent_of_drive")
    }

    private suspend fun promoteOrFail(file: File, label: String): String {
        clock += 1_000
        val result = manager.promote(
            candidate = file,
            expectedSha256 = manager.sha256(file),
            kind = "FULL_MANUAL",
            verifier = realVerifier(),
            note = label
        )
        note("promote $label -> promoted=${result.promoted} reason=${result.failureReason}")
        assertTrue("promotion of a healthy package must succeed: ${result.failureReason}", result.promoted)
        return result.generation!!.id
    }

    private companion object { const val PASSWORD = "P7Gen#2026" }
}
