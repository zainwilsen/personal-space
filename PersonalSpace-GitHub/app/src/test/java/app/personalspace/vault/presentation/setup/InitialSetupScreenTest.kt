package app.personalspace.vault.presentation.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.personalspace.vault.core.designsystem.PersonalSpaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-440dpi")
class InitialSetupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun firstLaunchShowsOnlyCenteredBrandAndTwoSetupActions() {
        var createTapped = false
        var restoreTapped = false

        composeTestRule.setContent {
            PersonalSpaceTheme {
                InitialSetupScreen(
                    isBusy = false,
                    errorMessage = null,
                    actionsEnabled = true,
                    onCreateNewSpace = { createTapped = true },
                    onRestoreExistingSpace = { restoreTapped = true }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Personal Space Bauhaus emblem").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal Space").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create New").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore Existing").assertIsDisplayed()

        composeTestRule.onNodeWithText("Create New").performClick()
        composeTestRule.onNodeWithText("Restore Existing").performClick()
        assertTrue(createTapped)
        assertTrue(restoreTapped)
    }
}
