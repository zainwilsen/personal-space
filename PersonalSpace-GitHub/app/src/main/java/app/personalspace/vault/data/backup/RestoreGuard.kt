package app.personalspace.vault.data.backup

import android.content.Context
import java.io.File

/**
 * P2 / correction #2 — the guard that makes "discard the existing vault" impossible while a
 * recovery journal or rollback copy still exists.
 *
 * Rules enforced here (and tested in `RestoreGuardTest`):
 *
 * 1. If a restore journal exists and has **not** reached [RestorePhase.COMMITTED] or
 *    [RestorePhase.ROLLED_BACK], no new restore may start.
 * 2. If a rollback copy exists that has not been committed, it must **never** be deleted by a
 *    cleanup path, and no code path may offer to "start empty".
 * 3. The rollback copy is only ever removed after the state it protects has been confirmed
 *    committed (or confirmed rolled back).
 *
 * This class does not delete anything. It only reports and blocks.
 */
internal object RestoreGuard {

    /** What the caller may safely do next. */
    enum class State {
        /** No journal and no rollback copies: normal operation. */
        CLEAR,

        /** A restore is in progress or was interrupted; resolve it first. */
        RECOVERY_PENDING,

        /** A previous rollback failed; user data may be half-swapped. Blocking. */
        RECOVERY_FAILED
    }

    fun state(context: Context): State {
        val journal = RestoreJournal.read(context)
        if (journal != null) {
            return when (journal.phase) {
                RestorePhase.ROLLBACK_FAILED -> State.RECOVERY_FAILED
                RestorePhase.COMMITTED, RestorePhase.ROLLED_BACK -> State.CLEAR
                else -> State.RECOVERY_PENDING
            }
        }
        // Journal missing but a rollback copy is on disk: this can only mean the journal file was
        // lost, never that the copy is disposable. Treat as pending, never as "clear".
        return if (unresolvedRollbackDirs(context).isNotEmpty()) State.RECOVERY_PENDING else State.CLEAR
    }

    fun unresolvedRollbackDirs(context: Context): List<File> =
        unresolvedRollbackDirs(context.filesDir)

    /** P3: file-based variant so startup recovery needs no Context. */
    fun unresolvedRollbackDirs(filesDir: File): List<File> =
        filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(RestoreJournal.ROLLBACK_DIR_PREFIX) }
            ?: emptyList()

    /**
     * @throws RecoveryPendingException if the vault must not be modified right now.
     */
    fun requireClear(context: Context) {
        val unresolved = unresolvedRollbackDirs(context)
        when (state(context)) {
            State.CLEAR -> if (unresolved.isNotEmpty()) {
                throw RecoveryPendingException(
                    "A pre-restore copy (" + unresolved.first().name + ") is still on disk. " +
                        "It must be resolved before the vault can be changed."
                )
            }
            State.RECOVERY_PENDING -> throw RecoveryPendingException(
                "A previous restore did not finish. Resolve it before starting another restore — " +
                    "your existing data is preserved and no option will discard it."
            )
            State.RECOVERY_FAILED -> throw RecoveryPendingException(
                "A previous restore failed and could not be rolled back automatically. Your " +
                    "pre-restore copy is kept at " + unresolved.firstOrNull()?.absolutePath +
                    ". Nothing was deleted."
            )
        }
    }

    fun isClear(context: Context): Boolean = state(context) == State.CLEAR

    /**
     * The ONLY permitted way to drop a rollback copy: caller must pass the phase that justifies it.
     * Anything other than COMMITTED or ROLLED_BACK is refused, so no "cleanup" can discard a copy
     * that still describes recoverable user data.
     */
    fun mayDeleteRollbackCopy(phase: RestorePhase): Boolean =
        phase == RestorePhase.COMMITTED || phase == RestorePhase.ROLLED_BACK
}

/**
 * Thrown when a vault-modifying operation is attempted while recovery is unresolved.
 * Restore entry points surface this as a blocking message; they never offer to discard data.
 */
class RecoveryPendingException(message: String) : IllegalStateException(message)
