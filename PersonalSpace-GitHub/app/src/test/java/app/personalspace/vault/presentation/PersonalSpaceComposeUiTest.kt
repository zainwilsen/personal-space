package app.personalspace.vault.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.BuildConfig
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.core.designsystem.PersonalSpaceTheme
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.domain.model.RecurrenceType
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.presentation.dashboard.DashboardScreen
import app.personalspace.vault.presentation.dashboard.DashboardViewModel
import app.personalspace.vault.presentation.folders.FolderDetailsScreen
import app.personalspace.vault.presentation.folders.FoldersScreen
import app.personalspace.vault.presentation.folders.FoldersViewModel
import app.personalspace.vault.presentation.launch.LaunchAnimationScreen
import app.personalspace.vault.presentation.records.AllRecordsScreen
import app.personalspace.vault.presentation.records.NoteHighlighterPalette
import app.personalspace.vault.presentation.records.NotePdfExporter
import app.personalspace.vault.presentation.records.RecordDetailScreen
import app.personalspace.vault.presentation.records.RecordEditorScreen
import app.personalspace.vault.presentation.records.RecordsViewModel
import app.personalspace.vault.presentation.records.BlockAlignment
import app.personalspace.vault.presentation.records.RichNoteCodec
import app.personalspace.vault.presentation.records.paragraphAlignmentAt
import app.personalspace.vault.presentation.records.RichNoteBlock
import app.personalspace.vault.presentation.records.RichSpan
import app.personalspace.vault.presentation.records.RichSpanKind
import app.personalspace.vault.presentation.records.VersionHistoryScreen
import app.personalspace.vault.presentation.settings.AboutScreen
import app.personalspace.vault.presentation.settings.BackupSyncScreen
import app.personalspace.vault.presentation.settings.SettingsViewModel
import app.personalspace.vault.presentation.timeline.RecycleBinScreen
import app.personalspace.vault.presentation.timeline.RecycleBinViewModel
import kotlinx.coroutines.flow.first
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-440dpi")
class PersonalSpaceComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var rig: IsolatedVaultTestRig

    @Before
    fun setUp() {
        rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "compose_ui")
    }

    @After
    fun tearDown() {
        rig.close()
    }

    @Test
    fun `Minimal Bauhaus palette matches exact mandatory hex color tokens`() {
        assertEquals(0xFFFDF6EC.toInt(), BauhausColors.Background.value.shr(32).toInt())
        assertEquals(0xFFE94528.toInt(), BauhausColors.Red.value.shr(32).toInt())
        assertEquals(0xFFFEBD03.toInt(), BauhausColors.Yellow.value.shr(32).toInt())
        assertEquals(0xFF25559A.toInt(), BauhausColors.Blue.value.shr(32).toInt())
        assertEquals(0xFF101010.toInt(), BauhausColors.Black.value.shr(32).toInt())
        assertEquals(0xFFF6EFE5.toInt(), BauhausColors.CardSurface.value.shr(32).toInt())
        assertEquals(0xFF77716A.toInt(), BauhausColors.SecondaryText.value.shr(32).toInt())
        assertEquals(0xFFE2D9CD.toInt(), BauhausColors.Divider.value.shr(32).toInt())
        assertEquals(0xFFFFFDF9.toInt(), BauhausColors.LightSurface.value.shr(32).toInt())
    }

    @Test
    fun `Reminder countdown formatter formats sub-24h hours-minutes and multi-day days-hours`() {
        val base = 1_700_000_000_000L
        val twoHoursThirtyMins = base + (2 * 3_600_000L) + (30 * 60_000L)
        assertEquals("2 hours 30 minutes", DateFormatters.formatReminderRemaining(twoHoursThirtyMins, base))

        val twoDaysFiveHours = base + (2 * 24 * 3_600_000L) + (5 * 3_600_000L) + (10 * 60_000L)
        assertEquals("2 Days 5 hours", DateFormatters.formatReminderRemaining(twoDaysFiveHours, base))
    }

    @Test
    fun `Zero-login auto unlock provisions vault silently and screenshot restriction is disabled by default`() {
        rig.sessionManager.ensureAutoUnlocked()
        assertEquals(VaultLockState.UNLOCKED, rig.sessionManager.lockState.value)
        assertTrue(rig.vaultKeyManager.hasAutoOpenVault())

        val defaults = UserSettings()
        assertFalse(defaults.secureTaskSwitcher)
        assertFalse(defaults.lockOnScreenOff)
    }

    @Test
    fun `RichNoteCodec stores WYSIWYG spans without markdown symbols and migrates legacy markdown cleanly`() {
        // Legacy markdown note migrates automatically with zero ** or _ visible
        val legacy = "## Heading Two\nHello **Bold** and _Italic_ world"
        val migrated = RichNoteCodec.decode(legacy)
        assertFalse(migrated.plainText.contains("**"))
        assertFalse(migrated.plainText.contains("_"))
        assertEquals("Heading Two\nHello Bold and Italic world", migrated.plainText)
        assertTrue(migrated.spans.any { it.kind == RichSpanKind.H2 })
        assertTrue(migrated.spans.any { it.kind == RichSpanKind.BOLD })
        assertTrue(migrated.spans.any { it.kind == RichSpanKind.ITALIC })

        // Applying highlighter brush + bold + heading encodes/decodes round-trip with 1:1 offset mapping
        val tfv = TextFieldValue("Article Title\nHighlighted important line", selection = TextRange(14, 40))
        val yellowHex = NoteHighlighterPalette[0].hex
        val highlighted = RichNoteCodec.toggleSpanInRange(
            spans = emptyList(),
            start = 14,
            end = 40,
            kind = RichSpanKind.HIGHLIGHT,
            colorHex = yellowHex,
            textLength = tfv.text.length
        )
        val headed = RichNoteCodec.toggleSpanInRange(
            spans = highlighted,
            start = 0,
            end = 13,
            kind = RichSpanKind.H1,
            exclusiveGroup = setOf(RichSpanKind.H1, RichSpanKind.H2, RichSpanKind.H3),
            textLength = tfv.text.length
        )
        val encoded = RichNoteCodec.encode(tfv.text, headed)

        val decoded = RichNoteCodec.decode(encoded)
        assertEquals("Article Title\nHighlighted important line", decoded.plainText)
        assertEquals("Article Title\nHighlighted important line", RichNoteCodec.extractPlainText(encoded))
        assertTrue(decoded.spans.any { it.kind == RichSpanKind.HIGHLIGHT && it.colorHex == yellowHex })
        assertTrue(decoded.spans.any { it.kind == RichSpanKind.H1 })
    }

    @Test
    fun `Dashboard shows Recent Activities breakdown and tapping anywhere on screen dismisses candle popup`() {
        rig.sessionManager.ensureAutoUnlocked()
        val now = rig.clock.nowMillis()
        runBlocking {
            val f = rig.repository.createFolder("Docs", parentFolderId = null, color = FolderAccentColor.YELLOW)
            rig.repository.saveRecord(
                existingRecordId = null,
                title = "Passport Info",
                description = "Details",
                folderId = f.id,
                category = null,
                customFields = emptyList()
            )
            rig.repository.saveReminder(
                existingReminderId = null,
                title = "Check Visa",
                notes = "",
                scheduledAtMillis = now + (2 * 3_600_000L) + (30 * 60_000L),
                recurrence = RecurrenceType.ONCE,
                priority = ReminderPriority.HIGH,
                linkedRecordId = null
            )
            rig.repository.preloadStartupData()
        }

        val viewModel = DashboardViewModel(rig.repository)
        var createNoteDirectCalled = false
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                DashboardScreen(
                    viewModel = viewModel,
                    onSelectBottomTab = {},
                    onOpenSearch = {},
                    onOpenAllRecords = {},
                    onOpenRecord = {},
                    onCreateRecord = { createNoteDirectCalled = true },
                    onOpenFolders = {},
                    onOpenFolder = {},
                    onOpenReminders = {},
                    onOpenReminder = {},
                    onOpenBackupSync = {},
                    onOpenTimeline = {},
                    onOpenConflicts = {},
                    referenceNowMillis = now
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Total Notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Last 7 Days").assertIsDisplayed()

        // Tap candle 6 -> breakdown appears
        composeTestRule.onNodeWithTag("activity_bar_6").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("selected_activity_breakdown").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notes: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Folders: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reminders: 1").assertIsDisplayed()

        // Tap elsewhere on screen (e.g., top header "Dashboard") -> breakdown dismisses immediately!
        composeTestRule.onNodeWithText("Dashboard").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("selected_activity_breakdown").assertDoesNotExist()

        // Tapping + goes straight to Note Editor without any Title popup
        composeTestRule.onNodeWithTag("dashboard_fab_new_record").performClick()
        composeTestRule.waitForIdle()
        assertTrue(createNoteDirectCalled)
    }

    @Test
    fun `File Manager supports Mark multi-select folder batch Delete with concise Delete dialog and Recycle Bin soft-delete`() {
        rig.sessionManager.ensureAutoUnlocked()
        val (folderA, folderB) = runBlocking {
            val f1 = rig.repository.createFolder("Projects", parentFolderId = null, color = FolderAccentColor.RED)
            val f2 = rig.repository.createFolder("Archive", parentFolderId = null, color = FolderAccentColor.BLUE)
            rig.repository.saveRecord(
                existingRecordId = null,
                title = "Roadmap",
                description = "Body",
                folderId = f1.id,
                category = null,
                customFields = emptyList()
            )
            rig.repository.preloadStartupData()
            f1 to f2
        }
        val viewModel = FoldersViewModel(rig.repository)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                FoldersScreen(
                    viewModel = viewModel,
                    onSelectBottomTab = {},
                    onOpenFolder = {},
                    onOpenAllRecords = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Projects").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 notes").assertIsDisplayed()

        composeTestRule.onNodeWithTag("file_manager_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_option_mark_folders").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("folder_card_${folderA.id}").performClick()
        composeTestRule.onNodeWithTag("folder_card_${folderB.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2 Selected").assertIsDisplayed()

        composeTestRule.onNodeWithTag("btn_mark_delete_folders").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete 2 selected folders?").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Delete")[1].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            composeTestRule.onAllNodesWithText("No Folders").fetchSemanticsNodes().isNotEmpty()
        }
        val recycleItems = runBlocking { rig.repository.observeDeletedItems().first() }
        assertEquals(2, recycleItems.size)
    }

    @Test
    fun `FolderDetailsScreen Note card shows ONLY Title without body snippet while preserving card size`() {
        rig.sessionManager.ensureAutoUnlocked()
        val (folder, savedNote) = runBlocking {
            val f = rig.repository.createFolder("Study Vault", parentFolderId = null, color = FolderAccentColor.YELLOW)
            val r = rig.repository.saveRecord(
                existingRecordId = null,
                title = "System Architecture",
                description = "## Core Idea\nClean modular layers with zero lock-in",
                folderId = f.id,
                category = null,
                customFields = emptyList()
            )
            rig.repository.preloadStartupData()
            f to r
        }
        val viewModel = FoldersViewModel(rig.repository)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                FolderDetailsScreen(
                    folderId = folder.id,
                    viewModel = viewModel,
                    onBack = {},
                    onOpenSubfolder = {},
                    onOpenRecord = {},
                    onCreateRecordInFolder = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Note card displays File icon + ONLY Title (no inner body snippet!) while keeping ~68dp card height
        val cardBounds = composeTestRule.onNodeWithTag("record_card_${savedNote.id}").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Note card should preserve generous height", cardBounds.bottom - cardBounds.top >= 64.dp)

        composeTestRule.onNodeWithTag("bauhaus_note_file_icon", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("System Architecture").assertIsDisplayed()
        composeTestRule.onNodeWithTag("note_snippet_${savedNote.id}", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Core Idea • Clean modular layers with zero lock-in").assertDoesNotExist()
    }

    @Test
    fun `RecordDetailScreen shows No Notes yet when body is empty and exports clean rich-span PDF`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rig.sessionManager.ensureAutoUnlocked()
        val emptyBodyNote = runBlocking {
            val f = rig.repository.createFolder("Essays", parentFolderId = null, color = FolderAccentColor.BLUE)
            val rec = rig.repository.saveRecord(
                existingRecordId = null,
                title = "Title Only Note",
                description = "",
                folderId = f.id,
                category = null,
                customFields = emptyList()
            )
            rig.repository.preloadStartupData()
            rec
        }
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecordDetailScreen(
                    recordId = emptyBodyNote.id,
                    viewModel = viewModel,
                    onBack = {},
                    onEditRecord = {},
                    onOpenVersionHistory = {},
                    onOpenAttachment = {},
                    onOpenFolder = {},
                    onAddLinkedReminder = {},
                    onOpenDuplicatedRecord = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Empty body shows ONLY "No Notes yet" (not the old long sentence)
        composeTestRule.onNodeWithText("No Notes yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("No content yet. Tap the pencil icon above to start writing.").assertDoesNotExist()

        // Toggle to Plain (Without Layer) mode -> still shows "No Notes yet"
        composeTestRule.onNodeWithTag("btn_reading_theme").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reading_theme_option_no_layer").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No Notes yet").assertIsDisplayed()

        // Verify NotePdfExporter handles RichNoteCodec documents cleanly
        val richEncoded = RichNoteCodec.encode(
            "Highlighted Research\nKey finding",
            listOf(
                app.personalspace.vault.presentation.records.RichSpan(0, 20, RichSpanKind.H1),
                app.personalspace.vault.presentation.records.RichSpan(21, 32, RichSpanKind.HIGHLIGHT, "#FFF176")
            )
        )
        val richPdf = runBlocking {
            NotePdfExporter.exportNoteToPdfFile(context, "Research Note", richEncoded)
        }
        assertTrue(richPdf.exists() && richPdf.length() > 100L)
    }

    @Test
    fun `RecordEditorScreen starts completely blank on second + creation and applies WYSIWYG styles without asterisks`() {
        rig.sessionManager.ensureAutoUnlocked()
        val folder = runBlocking {
            rig.repository.createFolder("Articles", parentFolderId = null, color = FolderAccentColor.RED)
        }
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)
        var editorInstance by mutableStateOf(1)
        var firstSavedId: String? = null

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                if (editorInstance == 1) {
                    RecordEditorScreen(
                        existingRecordId = null,
                        initialFolderId = folder.id,
                        viewModel = viewModel,
                        onBack = {},
                        onSaved = { firstSavedId = it }
                    )
                } else {
                    RecordEditorScreen(
                        existingRecordId = null,
                        initialFolderId = folder.id,
                        viewModel = viewModel,
                        onBack = {},
                        onSaved = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Write Note #1 with Bold + Highlighter Brush — verify ZERO ** or _ characters appear!
        composeTestRule.onNodeWithTag("editor_title_input").performTextReplacement("First Folder Note")
        composeTestRule.onNodeWithTag("btn_style_bold").performClick()
        composeTestRule.onNodeWithTag("btn_style_highlighter").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor_color_palette_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_swatch_mint").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("editor_body_input").performTextReplacement("Clean WYSIWYG sentence")
        composeTestRule.waitForIdle()

        // No raw markdown characters in editor UI
        composeTestRule.onNodeWithText("Clean WYSIWYG sentence").assertIsDisplayed()
        composeTestRule.onNodeWithText("**Clean WYSIWYG sentence**").assertDoesNotExist()

        // Save Note #1
        composeTestRule.onNodeWithTag("btn_save_record_top").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            ShadowLooper.idleMainLooper()
            firstSavedId != null
        }

        val savedDoc = RichNoteCodec.decode(runBlocking { rig.repository.getRecordById(firstSavedId!!)!!.description })
        assertEquals("Clean WYSIWYG sentence", savedDoc.plainText)
        assertTrue(savedDoc.spans.any { it.kind == RichSpanKind.BOLD })
        assertTrue(savedDoc.spans.any { it.kind == RichSpanKind.HIGHLIGHT && it.colorHex == "#B9F6CA" })

        // Now simulate going back to the folder and tapping + AGAIN (editorInstance = 2):
        // Title MUST NOT pre-fill "First Folder Note"!
        editorInstance = 2
        composeTestRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("First Folder Note").assertDoesNotExist()
        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test
    fun `editor center alignment is applied and survives save plus reopen`() {
        rig.sessionManager.ensureAutoUnlocked()
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)
        var savedId: String? = null

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecordEditorScreen(
                    existingRecordId = null,
                    initialFolderId = null,
                    viewModel = viewModel,
                    onBack = {},
                    onSaved = { savedId = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor_body_input").performTextReplacement("Centered sentence")
        composeTestRule.onNodeWithTag("btn_align_center").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_save_record_top").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            ShadowLooper.idleMainLooper()
            savedId != null
        }

        val storedDescription = runBlocking { rig.repository.getRecordById(savedId!!)!!.description }
        val blocks = RichNoteCodec.decodeBlocks(storedDescription)
        val textBlock = blocks.filterIsInstance<RichNoteBlock.Text>().first { it.text.contains("Centered sentence") }
        assertEquals(
            BlockAlignment.CENTER,
            paragraphAlignmentAt(textBlock.text, 0, textBlock.spans)
        )
    }

    @Test
    fun `alignment is paragraph level and the next paragraph stays left`() {
        rig.sessionManager.ensureAutoUnlocked()
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)
        var savedId: String? = null

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecordEditorScreen(
                    existingRecordId = null,
                    initialFolderId = null,
                    viewModel = viewModel,
                    onBack = {},
                    onSaved = { savedId = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("editor_body_input").performTextReplacement("Line one")
        composeTestRule.onNodeWithTag("btn_align_center").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor_body_input").performTextInput("\nLine two")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_save_record_top").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            ShadowLooper.idleMainLooper()
            savedId != null
        }

        val storedDescription = runBlocking { rig.repository.getRecordById(savedId!!)!!.description }
        val textBlock = RichNoteCodec.decodeBlocks(storedDescription)
            .filterIsInstance<RichNoteBlock.Text>()
            .first { it.text.contains("Line one") }
        assertEquals(
            "Only the first paragraph stays centered",
            BlockAlignment.CENTER,
            paragraphAlignmentAt(textBlock.text, 0, textBlock.spans)
        )
        assertEquals(
            "The paragraph typed after Enter must stay left",
            BlockAlignment.LEFT,
            paragraphAlignmentAt(textBlock.text, textBlock.text.indexOf("Line two"), textBlock.spans)
        )
        assertEquals(
            "Alignment must not split the note into extra blocks",
            1,
            RichNoteCodec.decodeBlocks(storedDescription).count { it is RichNoteBlock.Text }
        )
    }

    @Test
    fun `Backup Sync screen shows WhatsApp-style statuses and Google account actions`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = SettingsViewModel(
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

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                BackupSyncScreen(viewModel = viewModel, onBack = {}, onNavigate = {})
            }
        }
        composeTestRule.waitForIdle()

        // Not connected state
        composeTestRule.onNodeWithTag("cloud_backup_status_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect Google Account").assertIsDisplayed()

        // Connect -> connected card shows account + Backup Now + restore + disconnect
        composeTestRule.runOnIdle {
            runBlocking { rig.authManager.persistAccessToken("token-ui", "zain.own@gmail.com") }
            viewModel.refreshCloudState()
        }
        composeTestRule.waitForIdle()
        // P6 (measured flake, one occurrence in a full-suite run, never in isolation): right after the
        // connect transition the Compose frame that lays out the account card can still be in flight
        // when waitForIdle() returns, so the "Disconnect" node exists in the unmerged tree but the
        // scroll action cannot resolve it yet. Wait for the state explicitly instead of racing it.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodes(hasText("Disconnect", substring = true) or hasTestTag("cloud_backup_status_text"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // MEASURED, not assumed: removing the P2 sensitivity notice entirely still leaves
        // "Disconnect" below the fold on this viewport, so this off-screen behaviour is PRE-EXISTING
        // and not caused by the warning card. The notice was nevertheless moved below the Google
        // Drive card so it adds no pressure on small screens. Scroll-then-assert is therefore the
        // correct assertion here (existence is asserted; on-screen visibility is viewport-dependent).
        composeTestRule.onNodeWithText("Disconnect").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Backup Now").performScrollTo().assertExists()
        composeTestRule.onNodeWithText("Restore from Google Drive").performScrollTo().assertExists()

        // Disconnect -> back to Not Connected
        composeTestRule.onNodeWithText("Disconnect").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Connect Google Account").assertIsDisplayed()
    }

    @Test
    fun `Backup Sync screen warns that exported backup files must be treated as sensitive`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val viewModel = SettingsViewModel(
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

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                BackupSyncScreen(viewModel = viewModel, onBack = {}, onNavigate = {})
            }
        }
        composeTestRule.waitForIdle()

        // P2 / correction #3: the portable key wrap makes a .psbackup readable by anyone who has
        // the file, so the export affordance must carry an explicit warning.
        composeTestRule.onNodeWithTag("backup_sensitivity_warning").assertIsDisplayed()
        composeTestRule.onNodeWithText("Treat backup files as sensitive").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "A .psbackup file can be opened without your Master Password by anyone who gets the file. " +
                "Keep it private, do not share it on public chats, and delete copies you no longer need."
        ).assertIsDisplayed()
    }

    @Test
    fun `AboutScreen shows H2 About header Version without Code and unlayered by ZAIN with Red Heart`() {
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                AboutScreen(onBack = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("Application Info").assertDoesNotExist()
        composeTestRule.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Version ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})").assertDoesNotExist()
        composeTestRule.onNodeWithText("Privacy & Architecture Guarantees").assertDoesNotExist()
        composeTestRule.onNodeWithTag("about_red_heart").assertIsDisplayed()
        composeTestRule.onNodeWithText("ZAIN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Crafted with").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zain's first successfully completed application, developed between November 14 and November 18, 2026. A small beginning and a meaningful milestone in his journey.").assertIsDisplayed()
    }

    @Test
    fun `RecycleBinScreen shows unlayered empty state Forever retention long-press popup and 3-line Search Mark Delete All menu`() {
        rig.sessionManager.ensureAutoUnlocked()
        val viewModel = RecycleBinViewModel(rig.repository)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecycleBinScreen(
                    viewModel = viewModel,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Empty state: H2 header without "30-Day Automatic Retention", unlayered "Recycle Bin is Empty"
        composeTestRule.onNodeWithText("Recycle Bin").assertIsDisplayed()
        composeTestRule.onNodeWithText("30-Day Automatic Retention").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recycle_empty_unlayered").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recycle Bin is Empty").assertIsDisplayed()

        // Soft-delete two notes so Recycle Bin has items
        runBlocking {
            val n1 = rig.repository.saveRecord(null, "Alpha Draft", "Body 1", null, null, emptyList())
            val n2 = rig.repository.saveRecord(null, "Beta Draft", "Body 2", null, null, emptyList())
            rig.repository.softDeleteRecord(n1.id)
            rig.repository.softDeleteRecord(n2.id)
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            ShadowLooper.idleMainLooper()
            composeTestRule.onAllNodesWithText("Alpha Draft").fetchSemanticsNodes().isNotEmpty()
        }

        // Item card shows ONLY Title (no "NOTE: " prefix), "Forever" (no "Auto-expires:"), and NO inline buttons
        composeTestRule.onNodeWithText("Alpha Draft").assertIsDisplayed()
        composeTestRule.onNodeWithText("NOTE: Alpha Draft").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("Forever")[0].assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore").assertDoesNotExist()

        // Long-press on Alpha Draft card -> opens popup showing Restore & Delete Forever
        val deletedList = runBlocking { rig.repository.observeDeletedItems().first() }
        val alphaBatch = deletedList.first { it.displayTitle == "Alpha Draft" }
        composeTestRule.onNodeWithTag("recycle_card_${alphaBatch.batchId}").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recycle_menu_restore_${alphaBatch.batchId}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recycle_menu_delete_forever_${alphaBatch.batchId}").assertIsDisplayed()

        // Restore Alpha Draft via long-press popup
        composeTestRule.onNodeWithTag("recycle_menu_restore_${alphaBatch.batchId}").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            ShadowLooper.idleMainLooper()
            composeTestRule.onAllNodesWithText("Alpha Draft").fetchSemanticsNodes().isEmpty()
        }

        // Open 3-line header menu -> verify Search, Mark, and Delete All exist -> enter Mark mode & delete Beta Draft
        composeTestRule.onNodeWithTag("btn_recycle_header_menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_recycle_search").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_recycle_delete_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_recycle_mark").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val betaBatch = deletedList.first { it.displayTitle == "Beta Draft" }
        composeTestRule.onNodeWithTag("recycle_card_${betaBatch.batchId}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1 Selected").assertIsDisplayed()

        composeTestRule.onNodeWithTag("btn_recycle_delete_marked").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Delete")[1].performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            ShadowLooper.idleMainLooper()
            composeTestRule.onAllNodesWithText("Recycle Bin is Empty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `VersionHistoryScreen displays note Created and Modified dates and version timestamps`() {
        rig.sessionManager.ensureAutoUnlocked()
        val savedNote = runBlocking {
            val rec = rig.repository.saveRecord(
                existingRecordId = null,
                title = "Journal Entry",
                description = "Initial draft",
                folderId = null,
                category = null,
                customFields = emptyList()
            )
            rig.repository.preloadStartupData()
            rec
        }
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                VersionHistoryScreen(
                    recordId = savedNote.id,
                    viewModel = viewModel,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("version_history_record_dates").assertIsDisplayed()
        composeTestRule.onNodeWithText("Created  ${DateFormatters.formatDateTime(savedNote.createdAt)}").assertIsDisplayed()
        assertTrue(
            "History title must be shown",
            composeTestRule.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty()
        )
        composeTestRule.waitUntil(timeoutMillis = 3_000L) {
            composeTestRule.onAllNodesWithText("v1  •  ${DateFormatters.formatDateTime(savedNote.createdAt)}").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `LaunchAnimationScreen preloads vault and Dashboard data during 2_5s opening animation`() {
        rig.sessionManager.ensureAutoUnlocked()
        runBlocking {
            val f = rig.repository.createFolder("Preloaded Folder", parentFolderId = null, color = FolderAccentColor.BLUE)
            rig.repository.saveRecord(
                existingRecordId = null,
                title = "Preloaded Note",
                description = "Ready before Dashboard opens",
                folderId = f.id,
                category = null,
                customFields = emptyList()
            )
        }

        var finished = false
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = false) {
                LaunchAnimationScreen(
                    preloadTask = { rig.repository.preloadStartupData() },
                    onFinished = { finished = true }
                )
            }
        }

        composeTestRule.mainClock.advanceTimeBy(900L)
        assertFalse("Expected launch animation to still be playing smoothly at 900ms", finished)
        composeTestRule.onNodeWithContentDescription("Personal Space Bauhaus emblem")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(240.dp)

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.mainClock.advanceTimeBy(2400L)
        composeTestRule.waitForIdle()
        assertTrue("Expected launch animation to complete smoothly after full choreography", finished)
    }

    @Test
    fun `Reminder default time is exactly one minute after now and stays changeable`() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = java.time.LocalDateTime.of(2026, 11, 15, 9, 0, 30)
            .atZone(zone).toInstant().toEpochMilli()

        val plusOneMinute = DateFormatters.nextMinuteEpochMillis(nowMillis = now, zoneId = zone)
        val resolvedTime = java.time.Instant.ofEpochMilli(plusOneMinute).atZone(zone)
        assertEquals(9, resolvedTime.hour)
        assertEquals(1, resolvedTime.minute)

        // While tapping a specific date, that DATE is kept and only the time becomes (now + 1 minute)
        val targetDate = java.time.LocalDate.of(2026, 12, 24)
        val onDate = DateFormatters.nextMinuteEpochMillis(
            nowMillis = now,
            zoneId = zone,
            targetDate = targetDate
        )
        val resolved = java.time.Instant.ofEpochMilli(onDate).atZone(zone)
        assertEquals(targetDate, resolved.toLocalDate())
        assertEquals(9, resolved.hour)
        assertEquals(1, resolved.minute)
    }

    @Test
    fun `Long titles are shortened with ellipsis for paths and alarm rows`() {
        val longTitle = "A very long note title that definitely does not fit inside a path breadcrumb"
        val shortened = DateFormatters.ellipsizeTitle(longTitle, 20)
        assertTrue(shortened.endsWith("..."))
        assertEquals(23, shortened.length)
        assertEquals("Short note", DateFormatters.ellipsizeTitle("Short note", 20))
    }

    @Test
    fun `RichNoteCodec round-trips embedded images with resizable width and keeps plain text clean`() {
        val tempImage = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "codec_img_${System.currentTimeMillis()}.img"
        ).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val encoded = RichNoteCodec.encodeBlocks(
            listOf(
                RichNoteBlock.Text(text = "Before image", spans = listOf(RichSpan(0, 6, RichSpanKind.BOLD))),
                RichNoteBlock.Image(imagePath = tempImage.absolutePath, widthFraction = 0.6f),
                RichNoteBlock.Text(text = "After image")
            )
        )

        assertTrue(RichNoteCodec.hasMeaningfulContent(encoded))
        assertEquals("Before image\nAfter image", RichNoteCodec.extractPlainText(encoded))

        val decoded = RichNoteCodec.decodeBlocks(encoded)
        val image = decoded.filterIsInstance<RichNoteBlock.Image>().first()
        assertEquals(tempImage.absolutePath, image.imagePath)
        assertEquals(0.6f, image.widthFraction)
        assertEquals(2, decoded.filterIsInstance<RichNoteBlock.Text>().size)

        assertFalse(RichNoteCodec.hasMeaningfulContent(RichNoteCodec.encodeBlocks(listOf(RichNoteBlock.Text()))))
        tempImage.delete()
    }

    @Test
    fun `Editor exposes image insert tool and multi-color highlighter picker above the keyboard toolbar`() {
        rig.sessionManager.ensureAutoUnlocked()
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                RecordEditorScreen(
                    existingRecordId = null,
                    initialFolderId = null,
                    viewModel = viewModel,
                    onBack = {},
                    onSaved = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_style_highlighter").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("highlight_swatch_yellow").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("highlight_swatch_more").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("highlight_swatch_ext_lime").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_style_image").performScrollTo().assertIsDisplayed()

        // Title lives INSIDE the scrolling content (so it scrolls away) and the toolbar sits below it
        composeTestRule.onNodeWithTag("editor_title_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_scroll_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_style_bar").assertIsDisplayed()
    }

    @Test
    fun `Auto Save defaults to enabled and persists its setting for the editor`() {
        val defaults = runBlocking { rig.repository.observeUserSettings().first() }
        assertTrue(defaults.autoSaveNotes)

        runBlocking { rig.preferences.updateAutoSaveNotes(false) }
        val updated = runBlocking { rig.repository.observeUserSettings().first() }
        assertFalse(updated.autoSaveNotes)

        runBlocking { rig.preferences.updateAutoSaveNotes(true) }
        assertTrue(runBlocking { rig.repository.observeUserSettings().first() }.autoSaveNotes)
    }

    @Test
    fun `All Notes screen uses H2 header with three-line Search Mark and Sort menu`() {
        rig.sessionManager.ensureAutoUnlocked()
        runBlocking {
            rig.repository.saveRecord(
                existingRecordId = null,
                title = "All Notes Header Note",
                description = "",
                folderId = null,
                category = null,
                customFields = emptyList()
            )
            rig.repository.preloadStartupData()
        }
        val viewModel = RecordsViewModel(rig.repository, rig.attachmentStorage, rig.inlineImageStore)

        composeTestRule.setContent {
            PersonalSpaceTheme(preferReducedMotion = true) {
                AllRecordsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenRecord = {},
                    onCreateRecord = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("All Notes").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_all_records_menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_all_records_search").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("input_search_all_records").assertIsDisplayed()
    }

        @Test
    fun `General settings restores Auto Save and Notifications toggles with clean UI`() {
        val source = File("src/main/java/app/personalspace/vault/presentation/settings/SettingsScreens.kt")
        val text = source.readText()
        assertTrue(text.contains("switch_auto_save_notes"))
        assertTrue(text.contains("switch_general_notifications"))
        assertTrue(text.contains("ScreenRoutes.SETTINGS_GENERAL"))
        assertTrue(text.contains("Auto Save"))
    }
}
