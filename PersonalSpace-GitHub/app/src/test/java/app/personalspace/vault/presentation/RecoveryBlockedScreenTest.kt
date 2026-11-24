package app.personalspace.vault.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.designsystem.PersonalSpaceTheme
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.data.backup.RestoreJournal
import app.personalspace.vault.data.backup.RestorePhase
import app.personalspace.vault.data.backup.RestoreSnapshot
import app.personalspace.vault.presentation.recovery.RecoveryBlockedScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** P3.1 — the dedicated Recovery Blocked screen: exact reason, preserved files, two safe actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryBlockedScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    private lateinit var rig: IsolatedVaultTestRig
    private val ev = StringBuilder()
    private fun note(l: String) { ev.append(l).append('\n'); println("UI31| $l") }
    private fun dump(t: String) {
        File("/home/user/verification/evidence/$t.txt").apply { parentFile?.mkdirs() }.writeText(ev.toString())
    }

    @Before
    fun setUp() {
        RecoveryGate.resetForTesting()
        rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "p31_ui")
    }

    @After
    fun tearDown() {
        RecoveryGate.resetForTesting()
        rig.close()
    }

    private fun makeBlocked(keepCopy: Boolean): RestoreJournal = runBlocking {
        rig.initializeAndUnlockVault("UiPass#2026")
        rig.repository.saveRecord(null, "Precious", "body", null, null, emptyList())
        val journal = RestoreJournal.begin(rig.context, "ui-blocked", 1L)
        RestoreSnapshot.take(
            dbHolder = rig.dbHolder,
            filesDir = rig.context.filesDir,
            rollbackDir = journal.rollbackDir,
            attachmentsDir = rig.attachmentStorage.encryptedDir,
            imagesDir = rig.inlineImageStore.imagesDir
        )
        journal.advance(RestorePhase.SWAPPING_ATTACHMENTS)
        if (!keepCopy) journal.rollbackDir.deleteRecursively()
        rig.dbHolder.closeDatabase()
        rig.sessionManager.lockVault()
        RecoveryGate.markBlocked(
            reason = "Restore was interrupted during phase SWAPPING_ATTACHMENTS and its rollback " +
                "copy is missing. No file was deleted; the vault is unchanged.",
            preservedFiles = listOf("restore_rollback_ui", "encrypted_attachments/half_written.psenc"),
            retryableDirName = if (keepCopy) journal.rollbackDir.name else null
        )
        journal
    }

    @Test
    fun `U1 screen shows the exact blocking reason and preserved files`() {
        makeBlocked(keepCopy = false)
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecoveryBlockedScreen(
                    coordinator = rig.recoveryCoordinator,
                    state = RecoveryGate.state(),
                    onRecovered = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_blocked_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recovery_blocked_reason").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Restore was interrupted during phase SWAPPING_ATTACHMENTS and its rollback copy is " +
                "missing. No file was deleted; the vault is unchanged."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("recovery_preserved_files").assertIsDisplayed()
        composeTestRule.onNodeWithText("restore_rollback_ui").assertExists()
        note("exact reason rendered = true")
        note("preserved files rendered = true")
        dump("p31_u1_screen_reason_and_files")
    }

    @Test
    fun `U2 screen offers Retry and Export and no destructive option`() {
        makeBlocked(keepCopy = true)
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecoveryBlockedScreen(
                    coordinator = rig.recoveryCoordinator,
                    state = RecoveryGate.state(),
                    onRecovered = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_retry_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("recovery_export_button").performScrollTo().assertIsDisplayed()

        // no destructive affordance anywhere
        listOf("Start Empty", "Start empty", "Reset", "Discard", "Delete", "Ignore", "Skip").forEach { word ->
            val exists = runCatching { composeTestRule.onNodeWithText(word, substring = true).assertExists() }
            note("destructive word '$word' present = ${exists.isSuccess}")
            assertTrue("no '$word' option may exist", exists.isFailure)
        }
        composeTestRule.onNodeWithTag("recovery_no_destructive_note").assertExists()
        note("destructive option absent = true ; explicit note present = true")
        dump("p31_u2_no_destructive_option")
    }

    @Test
    fun `U3 export action copies data and leaves the original in place`() {
        val journal = makeBlocked(keepCopy = true)
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecoveryBlockedScreen(
                    coordinator = rig.recoveryCoordinator,
                    state = RecoveryGate.state(),
                    onRecovered = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_export_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_export_list").assertExists()
        note("original rollback copy still exists = ${journal.rollbackDir.exists()}")
        note("gate still blocked after export = ${RecoveryGate.isBlocked()}")
        assertTrue("export must not remove the original", journal.rollbackDir.exists())
        assertTrue("export must not clear the gate", RecoveryGate.isBlocked())
        dump("p31_u3_export_safe")
    }

    @Test
    fun `U4 retry on a corrupt copy keeps the gate blocked and reports the reason`() {
        val journal = makeBlocked(keepCopy = true)
        val dbCopy = File(journal.rollbackDir, "database/personal_space_encrypted.db")
        if (dbCopy.exists()) {
            val bytes = dbCopy.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 5).toByte()
            dbCopy.writeBytes(bytes)
        }
        var recovered = false
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecoveryBlockedScreen(
                    coordinator = rig.recoveryCoordinator,
                    state = RecoveryGate.state(),
                    onRecovered = { recovered = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_retry_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_message").assertExists()
        note("onRecovered called = $recovered")
        note("gate after failed retry = ${RecoveryGate.state().phase}")
        note("original copy still on disk = ${journal.rollbackDir.exists()}")
        assertEquals("gate must stay blocked", RecoveryGate.Phase.BLOCKED, RecoveryGate.state().phase)
        assertTrue("callback must not fire on failure", !recovered)
        assertTrue("copy preserved", journal.rollbackDir.exists())
        dump("p31_u4_retry_failure_stays_blocked")
    }

    @Test
    fun `U5 successful retry clears the gate and fires onRecovered`() {
        makeBlocked(keepCopy = true)
        var recovered = false
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecoveryBlockedScreen(
                    coordinator = rig.recoveryCoordinator,
                    state = RecoveryGate.state(),
                    onRecovered = { recovered = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recovery_retry_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        note("onRecovered called = $recovered")
        note("gate after successful retry = ${RecoveryGate.state().phase}")
        assertTrue("callback must fire on verified recovery", recovered)
        assertTrue("gate must be clear", !RecoveryGate.isBlocked())
        dump("p31_u5_retry_success_clears_gate")
    }
}
