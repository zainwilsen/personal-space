package app.personalspace.vault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.backup.RestoreRecovery
import app.personalspace.vault.data.backup.RestoreRecoveryCoordinator
import app.personalspace.vault.data.reminder.ReminderNotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point wiring:
 * - Hilt dependency injection + HiltWorkerFactory for WorkManager jobs
 * - Notification channel creation on app start
 * - P3.1: restore crash recovery **before** any auto-unlock, then the gate decides everything
 * - Automatic frictionless vault initialization & unlock (no login or lock screens)
 */
@HiltAndroidApp
class PersonalSpaceApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionManager: VaultSessionManager
    @Inject lateinit var notificationHelper: ReminderNotificationHelper
    @Inject lateinit var restoreRecovery: RestoreRecoveryCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationHelper.ensureChannelCreated()

        // P3.1 (requirement 2): recovery is evaluated here, in Application.onCreate, because this
        // runs BEFORE MainActivity — which is precisely where the P3 block was measured to be
        // bypassed (b1_bypass_requiresubkeys.txt). Nothing below touches the vault until the gate
        // has been decided.
        val outcome = restoreRecovery.runStartupRecovery()

        when (outcome) {
            is RestoreRecovery.Outcome.Blocked -> {
                // Gate is already BLOCKED by the coordinator. Do not unlock, do not throw: the app
                // must reach a screen that explains the situation instead of crashing.
                RecoveryGate.markBlocked(
                    reason = outcome.reason,
                    preservedFiles = outcome.preservedFiles,
                    retryableDirName = outcome.preservedFiles.firstOrNull()
                )
            }

            is RestoreRecovery.Outcome.Recovered,
            is RestoreRecovery.Outcome.Retried,
            is RestoreRecovery.Outcome.CommittedCleanup,
            is RestoreRecovery.Outcome.Clean -> {
                // Recovery completed and was hash-verified (Recovered), or there was nothing to do.
                if (!RecoveryGate.isBlocked()) {
                    RecoveryGate.markRecovered()
                }
            }
        }

        // Only reached when the gate is clear; tryEnsureAutoUnlocked swallows the gate exception so
        // a blocked process still starts and can render the recovery screen.
        sessionManager.tryEnsureAutoUnlocked()
    }
}
