package app.personalspace.vault.core.security

import java.util.concurrent.atomic.AtomicReference

/**
 * P3.1 — process-wide recovery gate.
 *
 * P3 enforced its block with a single `if` inside `MainActivity.onCreate`. That was measured to be
 * bypassable: `PersonalSpaceApp.onCreate`, `PersonalSpaceNavHost`, `requireSubKeys()` and
 * `VaultDatabaseHolder.getDatabase()` all unlocked or opened the database regardless
 * (evidence: `b1_bypass_requiresubkeys.txt` … `b5_blocked_state_not_process_wide.txt`).
 *
 * This object is the single source of truth every one of those paths consults. It holds **no
 * secrets** and performs **no I/O** — it only records the outcome of the last startup recovery.
 *
 * Safety contract:
 *  - While [Phase.BLOCKED], [VaultSessionManager] cannot unlock or hand out keys, and
 *    [app.personalspace.vault.data.local.db.VaultDatabaseHolder] cannot open the database.
 *  - The gate is only released by [markRecovered], which P3.1 calls **after** recovery has been
 *    hash-verified (never on a failed or partial attempt).
 *  - There is deliberately **no "open anyway" / "start empty" transition.** The only way out is a
 *    verified recovery.
 */
object RecoveryGate {

    enum class Phase {
        /** Nothing to resolve; vault access is allowed. */
        CLEAR,

        /**
         * An unfinished restore was found but recovery has not run yet in this process.
         * Treated exactly like [BLOCKED] for access purposes.
         */
        PENDING,

        /**
         * Recovery data is missing, corrupt, or recovery failed verification.
         * Vault access is refused until a retry succeeds.
         */
        BLOCKED
    }

    /** Snapshot of the gate, safe to render in UI. Carries no key material. */
    data class State(
        val phase: Phase,
        val reason: String? = null,
        val preservedFiles: List<String> = emptyList(),
        /** Non-null when a rollback copy is on disk and could be retried. */
        val retryableDirName: String? = null
    ) {
        val blocksAccess: Boolean get() = phase != Phase.CLEAR
    }

    private val current = AtomicReference(State(Phase.CLEAR))

    fun state(): State = current.get()

    fun isBlocked(): Boolean = current.get().blocksAccess

    /** Records that an unfinished restore exists and has not been resolved yet. */
    fun markPending(
        reason: String,
        preservedFiles: List<String> = emptyList(),
        retryableDirName: String? = null
    ) {
        current.set(State(Phase.PENDING, reason, preservedFiles, retryableDirName))
    }

    /**
     * Records a blocking condition. Once blocked, only [markRecovered] clears it — a later call to
     * [markClear] is ignored, so no code path can downgrade a block by accident.
     */
    fun markBlocked(
        reason: String,
        preservedFiles: List<String> = emptyList(),
        retryableDirName: String? = null
    ) {
        current.set(State(Phase.BLOCKED, reason, preservedFiles, retryableDirName))
    }

    /**
     * Releases the gate. **Must only be called after recovery has been hash-verified.**
     * Deliberately named so a call site reads as an assertion of success.
     */
    fun markRecovered() {
        current.set(State(Phase.CLEAR))
    }

    /** No-op if currently blocked; keeps the block sticky. */
    fun markClearIfNotBlocked() {
        if (!isBlocked()) current.set(State(Phase.CLEAR))
    }

    /** Test seam: resets the gate between test cases. Never called from production code. */
    internal fun resetForTesting() {
        current.set(State(Phase.CLEAR))
    }

    /**
     * Thrown when vault access is attempted while recovery is unresolved.
     * Every enforcement point throws this exact type so callers/tests can assert on it.
     */
    class RecoveryBlockedException(message: String) : IllegalStateException(message) {
        companion object {
            fun fromGate(): RecoveryBlockedException {
                val s = RecoveryGate.state()
                val detail = s.reason ?: "A previous restore did not finish."
                return RecoveryBlockedException(
                    "Vault access is blocked until recovery is resolved. $detail"
                )
            }
        }
    }
}
