package app.personalspace.vault.presentation

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.hilt.navigation.compose.hiltViewModel
import app.personalspace.vault.core.designsystem.PersonalSpaceTheme
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.core.setup.InitialSetupManager
import app.personalspace.vault.core.setup.InitialSetupState
import app.personalspace.vault.data.backup.RestoreRecoveryCoordinator
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.navigation.PersonalSpaceNavHost
import app.personalspace.vault.presentation.recovery.RecoveryBlockedScreen
import app.personalspace.vault.presentation.settings.RestoreBackupScreen
import app.personalspace.vault.presentation.settings.SettingsViewModel
import app.personalspace.vault.presentation.setup.InitialSetupScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Single-Activity Jetpack Compose entry point.
 *
 * A fresh install first asks whether to create a new space or restore an existing one.
 * Existing installations with a local vault continue directly to the Dashboard.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var sessionManager: VaultSessionManager
    @Inject lateinit var preferences: UserPreferencesDataSource
    @Inject lateinit var repository: PersonalSpaceRepository
    @Inject lateinit var restoreRecovery: RestoreRecoveryCoordinator
    @Inject lateinit var initialSetupManager: InitialSetupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Startup recovery has already run in PersonalSpaceApp, before this Activity was created.
        val gateState = RecoveryGate.state()
        val initialState = initialSetupManager.getLaunchState()

        // Do not provision a vault on a fresh install before the user chooses New or Existing.
        // A pending restore is resumed after process death by opening its temporary destination vault.
        val sessionReadyAtLaunch = if (
            !gateState.blocksAccess && initialState != InitialSetupState.CHOOSE
        ) {
            sessionManager.tryEnsureAutoUnlocked()
        } else {
            false
        }

        // Returning users keep the existing direct-to-dashboard startup behavior.
        if (sessionReadyAtLaunch && initialState == InitialSetupState.COMPLETE) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { repository.preloadStartupData() }
            }
        }

        setContent {
            val settings by preferences.settingsFlow.collectAsStateWithLifecycle(
                initialValue = UserSettings()
            )

            var setupState by remember { mutableStateOf(initialState) }
            var sessionReady by remember { mutableStateOf(sessionReadyAtLaunch) }
            var preparing by remember { mutableStateOf(false) }
            var setupError by remember {
                mutableStateOf(
                    if (
                        !gateState.blocksAccess &&
                        initialState != InitialSetupState.CHOOSE &&
                        !sessionReadyAtLaunch
                    ) {
                        "Existing local data was found, but its vault could not be opened. No data has been reset."
                    } else {
                        null
                    }
                )
            }

            BackHandler(
                enabled = !gateState.blocksAccess && setupState == InitialSetupState.RESTORE_PENDING
            ) {
                initialSetupManager.markChoiceRequired()
                setupState = InitialSetupState.CHOOSE
                setupError = null
            }

            PersonalSpaceTheme(preferReducedMotion = settings.reducedMotion) {
                when {
                    gateState.blocksAccess -> {
                        // An unresolved restore rollback is still a hard gate. The setup page must
                        // never bypass or hide the recovery screen.
                        RecoveryBlockedScreen(
                            coordinator = restoreRecovery,
                            state = gateState,
                            onRecovered = { recreate() }
                        )
                    }

                    setupState == InitialSetupState.CHOOSE -> {
                        InitialSetupScreen(
                            isBusy = preparing,
                            errorMessage = setupError,
                            actionsEnabled = true,
                            onCreateNewSpace = {
                                if (!preparing) {
                                    preparing = true
                                    setupError = null
                                    lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                check(!initialSetupManager.hasDatabaseWithoutKey()) {
                                                    "An encrypted local database exists but its key is missing. Personal Space will not overwrite it."
                                                }
                                                sessionManager.ensureAutoUnlocked()
                                                repository.preloadStartupData()
                                            }
                                        }
                                        if (result.isSuccess) {
                                            sessionReady = true
                                            initialSetupManager.markCompleted()
                                            setupState = InitialSetupState.COMPLETE
                                        } else {
                                            setupError = result.exceptionOrNull()?.message
                                                ?: "A new space could not be created."
                                        }
                                        preparing = false
                                    }
                                }
                            },
                            onRestoreExistingSpace = {
                                if (!preparing) {
                                    preparing = true
                                    setupError = null
                                    // Persist this before creating the temporary local destination.
                                    // If Android kills the process, the app resumes at Restore.
                                    initialSetupManager.markRestorePending()
                                    lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                check(!initialSetupManager.hasDatabaseWithoutKey()) {
                                                    "An encrypted local database exists but its key is missing. It cannot safely be replaced."
                                                }
                                                sessionManager.ensureAutoUnlocked()
                                            }
                                        }
                                        if (result.isSuccess) {
                                            sessionReady = true
                                            setupState = InitialSetupState.RESTORE_PENDING
                                        } else {
                                            initialSetupManager.markChoiceRequired()
                                            setupError = result.exceptionOrNull()?.message
                                                ?: "Restore setup could not be prepared."
                                        }
                                        preparing = false
                                    }
                                }
                            }
                        )
                    }

                    setupState == InitialSetupState.RESTORE_PENDING && sessionReady -> {
                        RestoreBackupScreen(
                            viewModel = hiltViewModel<SettingsViewModel>(),
                            onBack = {
                                initialSetupManager.markChoiceRequired()
                                setupState = InitialSetupState.CHOOSE
                                setupError = null
                            },
                            onRestored = {
                                initialSetupManager.markCompleted()
                                setupState = InitialSetupState.COMPLETE
                                setupError = null
                            }
                        )
                    }

                    setupState == InitialSetupState.COMPLETE && sessionReady -> {
                        PersonalSpaceNavHost(
                            sessionManager = sessionManager,
                            repository = repository
                        )
                    }

                    else -> {
                        // A missing or unreadable local key must not silently create a replacement
                        // vault over an existing encrypted database.
                        InitialSetupScreen(
                            isBusy = false,
                            errorMessage = setupError
                                ?: "The existing local space could not be opened. Its files have been left untouched.",
                            actionsEnabled = false,
                            onCreateNewSpace = {},
                            onRestoreExistingSpace = {}
                        )
                    }
                }
            }
        }
    }
}
