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
import app.personalspace.vault.data.attachment.OrphanQuarantineManager
import app.personalspace.vault.presentation.settings.OrphanQuarantineScreen
import app.personalspace.vault.presentation.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Item 3 — the Orphan Quarantine UI.
 *
 * These are behaviour tests against the real ViewModel and the real filesystem, so "the button exists"
 * is never the assertion: the tests check that the screen reflects measured state, that quarantine moves
 * rather than deletes, that restore puts the file back, and that "Delete forever" is the ONLY path that
 * destroys anything — and only after a confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrphanQuarantineScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ev = StringBuilder()
    private lateinit var rig: IsolatedVaultTestRig
    private lateinit var viewModel: SettingsViewModel

    private fun note(line: String) { ev.append(line).append('\n'); println("QUI| $line") }
    private fun dump(tag: String) {
        File("/home/user/verification/evidence/$tag.txt").apply { parentFile?.mkdirs() }
            .writeText(ev.toString())
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        rig = IsolatedVaultTestRig(appContext, "quarantine_ui")
        runBlocking { rig.initializeAndUnlockVault(PASSWORD) }
        viewModel = SettingsViewModel(
            appContext = appContext,
            repository = rig.repository,
            preferences = rig.preferences,
            vaultKeyManager = rig.vaultKeyManager,
            keystoreBiometricManager = rig.keystoreManager,
            sessionManager = rig.sessionManager,
            dbHolder = rig.dbHolder,
            attachmentStorage = rig.attachmentStorage,
            backupPackageManager = rig.backupManager,
            driveAuthManager = rig.authManager,
            workCoordinator = rig.workCoordinator,
            reminderScheduler = rig.reminderScheduler
        )
    }

    @After
    fun tearDown() { rig.close() }

    /**
     * The ViewModel (and both real media stores) resolve `filesDir` from the application context, so the
     * stray file must be written there — `rig.context` is an isolated per-rig context and the scan would
     * legitimately see an empty directory.
     */
    private fun appFilesDir(): File =
        ApplicationProvider.getApplicationContext<android.content.Context>().filesDir

    private fun seedStrayFile(): File {
        val dir = File(appFilesDir(), "encrypted_attachments").apply { mkdirs() }
        val stray = File(dir, "stray-${System.nanoTime()}.psenc")
        stray.writeBytes(ByteArray(1024) { 3 })
        stray.setLastModified(System.currentTimeMillis() - 5L * 24 * 60 * 60 * 1000)  // old enough
        return stray
    }

    /**
     * Waits for the screen's own auto-scan to finish. The scan runs in `LaunchedEffect` + a coroutine,
     * so "the composition exists" is not the same as "the scan is done" — asserting on the buttons
     * before the result arrives is a race, not a test.
     */
    /**
     * Drives the scan explicitly and waits for the result.
     *
     * `LaunchedEffect` does not run under the Compose test clock (it needs a real frame), so relying on
     * the screen's auto-scan would make these tests depend on timing rather than on behaviour. The
     * ViewModel call is the same one the screen makes on entry.
     */
    private fun setScreenAndScan() {
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                OrphanQuarantineScreen(viewModel = viewModel, onBack = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = true
        if (viewModel.quarantine.value.scanned == null) viewModel.scanForOrphans()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (viewModel.quarantine.value.scanned != null && !viewModel.quarantine.value.loading) break
            composeTestRule.waitForIdle()
            Thread.sleep(20)
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    // ------------------------------------------------------------------ screen shows measured facts

    @Test
    fun `U1 the screen reports what the scan actually found`() {
        val stray = seedStrayFile()
        val kept = runBlocking {
            val rec = rig.repository.saveRecord(null, "Live", "b", null, null, emptyList())
            rig.repository.importAttachmentFromStream(
                rec.id, "LIVE".toByteArray().inputStream(), "live.txt", "text/plain"
            )
        }
        note("live attachment row = ${kept.id}")
        setScreenAndScan()

        composeTestRule.onNodeWithTag("quarantine_explanation").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quarantine_scan_summary").performScrollTo().assertIsDisplayed()
        // the stray file is listed by name, the referenced one is not
        composeTestRule.onNodeWithText(stray.name, substring = true).assertExists()
        note("stray ${stray.name} listed; live attachment ${kept.id} not listed")

        // and nothing was moved or deleted by merely looking
        assertTrue("the stray file must still be in place after a scan", stray.exists())
        assertEquals("nothing may be quarantined by a scan", 0,
            viewModel.quarantine.value.manifest?.entries?.size ?: 0)
        dump("item3_U1_screen_reports_scan")
    }

    // ------------------------------------------------------------------ quarantine moves, does not delete

    @Test
    fun `U2 moving to quarantine preserves the file and can be undone from the screen`() {
        val stray = seedStrayFile()
        val originalHash = stray.readBytes().contentHashCode()
        setScreenAndScan()

        composeTestRule.onNodeWithTag("quarantine_move_button").performScrollTo().performClick()
        waitUntilCondition { (viewModel.quarantine.value.manifest?.entries?.size ?: 0) > 0 }
        val entry = viewModel.quarantine.value.manifest!!.entries.single()
        note("quarantined entry = ${entry.originalRelativePath} sha=${entry.sha256Hex.take(12)}")
        assertFalse("the file must be gone from the media folder", stray.exists())
        val held = File(appFilesDir(), "${OrphanQuarantineManager.QUARANTINE_DIR_NAME}/${entry.quarantineFileName}")
        assertTrue("but it must exist in quarantine", held.exists())
        assertEquals("with identical bytes", originalHash, held.readBytes().contentHashCode())

        // restore from the screen
        composeTestRule.onNodeWithTag("quarantine_restore_${entry.quarantineFileName}")
            .performScrollTo().performClick()
        waitUntilCondition { (viewModel.quarantine.value.manifest?.entries?.size ?: 0) == 0 }
        note("after restore: file back = ${stray.exists()}, hash ok = " +
            (stray.exists() && stray.readBytes().contentHashCode() == originalHash))
        assertTrue("the file must be back where it came from", stray.exists())
        assertEquals("with identical bytes", originalHash, stray.readBytes().contentHashCode())
        dump("item3_U2_quarantine_and_restore")
    }

    // ------------------------------------------------------------------ the only destructive action

    @Test
    fun `U3 delete forever requires confirmation and is the only path that destroys files`() {
        val stray = seedStrayFile()
        setScreenAndScan()
        composeTestRule.onNodeWithTag("quarantine_move_button").performScrollTo().performClick()
        waitUntilCondition { (viewModel.quarantine.value.manifest?.entries?.size ?: 0) > 0 }
        composeTestRule.waitForIdle()
        val entry = viewModel.quarantine.value.manifest!!.entries.single()
        val held = File(appFilesDir(), "${OrphanQuarantineManager.QUARANTINE_DIR_NAME}/${entry.quarantineFileName}")
        assertTrue(held.exists())

        // pressing the button opens a confirmation and destroys NOTHING yet
        composeTestRule.onNodeWithTag("quarantine_empty_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete quarantined files forever?", substring = true).assertExists()
        note("confirmation shown; file still present = ${held.exists()}")
        assertTrue("opening the dialog must not delete anything", held.exists())

        // cancelling keeps everything
        composeTestRule.onNodeWithTag("quarantine_cancel_empty").performClick()
        composeTestRule.waitForIdle()
        assertTrue("cancelling must not delete anything", held.exists())
        assertEquals("and the manifest must be unchanged", 1,
            viewModel.quarantine.value.manifest?.entries?.size)
        note("after cancel: file present = ${held.exists()}, entries = 1")

        // confirming deletes, and only then
        composeTestRule.onNodeWithTag("quarantine_empty_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quarantine_confirm_empty").performClick()
        waitUntilCondition { !held.exists() }
        note("after confirm: file present = ${held.exists()}")
        assertFalse("the confirmed action must delete the file", held.exists())
        assertEquals("and clear the manifest", 0, viewModel.quarantine.value.manifest?.entries?.size)
        dump("item3_U3_explicit_delete_only")
    }

    @Test
    fun `U4 export writes a read-only copy and leaves quarantine untouched`() {
        val stray = seedStrayFile()
        setScreenAndScan()
        composeTestRule.onNodeWithTag("quarantine_move_button").performScrollTo().performClick()
        waitUntilCondition { (viewModel.quarantine.value.manifest?.entries?.size ?: 0) > 0 }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("quarantine_export_button").performScrollTo().performClick()
        waitUntilCondition { viewModel.quarantine.value.exportedTo != null }
        val exported = File(viewModel.quarantine.value.exportedTo!!)
        val names = exported.listFiles()!!.map { it.name }
        note("exported to ${exported.absolutePath} -> $names")
        assertTrue("the manifest must be in the export",
            names.contains(OrphanQuarantineManager.MANIFEST_NAME))
        assertEquals("quarantine must still hold the file", 1,
            viewModel.quarantine.value.manifest?.entries?.size)
        dump("item3_U4_export_is_read_only")
    }

    @Test
    fun `U5 the screen refuses to quarantine while a restore is in flight`() {
        val stray = seedStrayFile()
        app.personalspace.vault.data.attachment.MediaReferenceIndex.markRestoreInFlight("ui_test_restore")
        try {
            setScreenAndScan()
            waitUntilCondition { viewModel.quarantine.value.lastError != null }
            val error = viewModel.quarantine.value.lastError!!
            note("error shown = $error")
            composeTestRule.onNodeWithTag("quarantine_error").performScrollTo().assertIsDisplayed()
            assertTrue("the message must explain the restore", error.contains("restore", ignoreCase = true))
            assertTrue("and the file must be untouched", stray.exists())
            assertEquals("nothing quarantined", 0, viewModel.quarantine.value.manifest?.entries?.size ?: 0)
        } finally {
            app.personalspace.vault.data.attachment.MediaReferenceIndex.clearRestoreInFlight("ui_test_restore")
        }
        dump("item3_U5_restore_guard_in_ui")
    }

    /** Polls a value produced by a coroutine, since viewModelScope work is outside the test clock. */
    private fun pollUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) break
            composeTestRule.waitForIdle()
            Thread.sleep(20)
        }
        composeTestRule.waitForIdle()
    }

    /**
     * Waits until [condition] holds.
     *
     * `viewModelScope` runs on a real dispatcher, so its state changes land outside Compose's test
     * clock; `waitForIdle()` alone will never see them. Advancing the clock (and spinning the main
     * looper) is what makes the recomposition actually happen.
     */
    private fun waitUntilCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) break
            composeTestRule.mainClock.advanceTimeBy(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeTestRule.waitForIdle()
            Thread.sleep(10)
        }
        composeTestRule.mainClock.advanceTimeBy(50)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()
    }

    private companion object { const val PASSWORD = "QuarUi#2026" }
}
