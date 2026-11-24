package app.personalspace.vault.core.setup

import android.content.Context
import app.personalspace.vault.core.crypto.VaultKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class InitialSetupState {
    CHOOSE,
    RESTORE_PENDING,
    COMPLETE
}

/**
 * Persists the one-time first-run choice.
 *
 * Older installations that already have a vault/database are treated as COMPLETE, so an app
 * update never interrupts an existing user's normal startup. A fresh install starts at CHOOSE.
 */
@Singleton
class InitialSetupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultKeyManager: VaultKeyManager
) {
    private val preferences
        get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getLaunchState(): InitialSetupState {
        val stored = preferences.getString(STATE_KEY, null)
        if (stored != null) {
            InitialSetupState.values().firstOrNull { it.name == stored }?.let { return it }
        }

        // Migration behavior: a vault/database that predates this preference belongs to an
        // existing installation. Never show onboarding over that user's local data.
        val hasExistingLocalSpace =
            vaultKeyManager.isVaultInitialized() ||
                context.getDatabasePath(DATABASE_NAME).exists()

        val initial = if (hasExistingLocalSpace) {
            InitialSetupState.COMPLETE
        } else {
            InitialSetupState.CHOOSE
        }
        preferences.edit().putString(STATE_KEY, initial.name).commit()
        return initial
    }

    fun markRestorePending() {
        persist(InitialSetupState.RESTORE_PENDING)
    }

    fun markChoiceRequired() {
        persist(InitialSetupState.CHOOSE)
    }

    fun markCompleted() {
        persist(InitialSetupState.COMPLETE)
    }

    /**
     * A database without its local encryption key must never be overwritten by a newly provisioned
     * empty vault. The caller shows a blocking explanation instead of creating a replacement.
     */
    fun hasDatabaseWithoutKey(): Boolean =
        context.getDatabasePath(DATABASE_NAME).exists() && !vaultKeyManager.isVaultInitialized()

    private fun persist(state: InitialSetupState) {
        check(preferences.edit().putString(STATE_KEY, state.name).commit()) {
            "Initial setup state could not be saved."
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "ps_initial_setup"
        const val STATE_KEY = "state"
        const val DATABASE_NAME = "personal_space_encrypted.db"
    }
}
