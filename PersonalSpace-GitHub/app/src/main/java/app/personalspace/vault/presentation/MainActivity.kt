package app.personalspace.vault.presentation

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.personalspace.vault.core.designsystem.PersonalSpaceTheme
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.backup.RestoreRecoveryCoordinator
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.navigation.PersonalSpaceNavHost
import app.personalspace.vault.presentation.recovery.RecoveryBlockedScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-Activity Jetpack Compose entry point optimized for POCO F7 (1280x2772 AMOLED):
 * - Edge-to-edge rendering with crisp dark status-bar icons on warm cream (#FDF6EC)
 * - Screenshots and normal recent-apps minimize preview explicitly enabled (no FLAG_SECURE)
 * - Immediately starts encrypted Vault + Dashboard preloading on Dispatchers.IO while the opening animation plays
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var sessionManager: VaultSessionManager
    @Inject lateinit var preferences: UserPreferencesDataSource
    @Inject lateinit var repository: PersonalSpaceRepository
    @Inject lateinit var restoreRecovery: RestoreRecoveryCoordinator

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

        // P3.1: PersonalSpaceApp.onCreate already ran startup recovery and recorded the verdict in
        // the process-wide RecoveryGate BEFORE this Activity existed. We read that verdict here; we
        // do not run recovery twice, because a second pass could race a running retry.
        val gateState = RecoveryGate.state()

        // Never auto-unlock on top of an unresolved, unverified recovery state. The gate would refuse
        // anyway (VaultSessionManager.ensureAutoUnlocked throws), but skipping avoids relying on an
        // exception for control flow.
        if (!gateState.blocksAccess) {
            sessionManager.tryEnsureAutoUnlocked()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { repository.preloadStartupData() }
            }
        }

        setContent {
            val settings by preferences.settingsFlow.collectAsStateWithLifecycle(
                initialValue = UserSettings()
            )

            PersonalSpaceTheme(preferReducedMotion = settings.reducedMotion) {
                // P3.1: when recovery is unresolved, the ONLY screen the app can render is the
                // recovery screen. The nav host is never composed, so no other code path gets a
                // chance to call ensureAutoUnlocked() or open the database.
                if (gateState.blocksAccess) {
                    RecoveryBlockedScreen(
                        coordinator = restoreRecovery,
                        state = gateState,
                        onRecovered = {
                            // Recovery verified -> gate cleared by the coordinator. Restart the
                            // Activity so the normal launch flow re-evaluates a clean gate.
                            recreate()
                        }
                    )
                } else {
                    PersonalSpaceNavHost(
                        sessionManager = sessionManager,
                        repository = repository
                    )
                }
            }
        }
    }
}
