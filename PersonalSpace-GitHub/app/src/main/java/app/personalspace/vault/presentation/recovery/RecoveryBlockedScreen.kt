package app.personalspace.vault.presentation.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausOutlinedButton
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.data.backup.RestoreRecoveryCoordinator
import kotlinx.coroutines.launch
import java.io.File

/**
 * P3.1 — dedicated "Recovery Blocked" screen.
 *
 * Shown instead of the app whenever [RecoveryGate] is not CLEAR. It exists because P3 exposed
 * `blockingReason()` / `preservedFiles()` as data with no UI, so a blocked user saw either a crash
 * or (worse) a silently unlocked app.
 *
 * Deliberately offers exactly two actions, both non-destructive:
 *  1. **Retry recovery** — re-runs the verified recovery path.
 *  2. **Export / preserve recovery data** — copies the rollback copy to a user-visible folder and
 *     leaves the original untouched.
 *
 * There is intentionally **no** "Start empty", "Reset", "Ignore", or "Delete and continue" action.
 * Data can only leave this screen through a verified recovery.
 */
@Composable
fun RecoveryBlockedScreen(
    coordinator: RestoreRecoveryCoordinator,
    state: RecoveryGate.State,
    onRecovered: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var exported by remember { mutableStateOf<List<String>>(emptyList()) }

    BauhausScreenScaffold(title = "Recovery Needed", onBack = null) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 16.dp)
                .testTag("recovery_blocked_root"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BauhausSurfaceCard {
                Text(
                    "Your vault is protected but not accessible right now",
                    style = MaterialTheme.typography.titleLarge,
                    color = BauhausColors.Black
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A restore did not finish. Nothing has been deleted — your data and the " +
                        "recovery copy are still on this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BauhausColors.SecondaryText
                )
            }

            // ---- exact blocking reason ----
            BauhausSurfaceCard(elevated = true) {
                Text("Why access is blocked", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.reason ?: "Recovery did not finish.",
                    modifier = Modifier.testTag("recovery_blocked_reason"),
                    style = MaterialTheme.typography.labelSmall,
                    color = BauhausColors.Red
                )
            }

            // ---- preserved files ----
            BauhausSurfaceCard(elevated = true) {
                Text("Preserved recovery files", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                if (state.preservedFiles.isEmpty()) {
                    Text(
                        "No individual files were reported, but nothing was deleted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BauhausColors.SecondaryText
                    )
                } else {
                    Column(modifier = Modifier.testTag("recovery_preserved_files")) {
                        state.preservedFiles.take(25).forEach { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        if (state.preservedFiles.size > 25) {
                            Text(
                                "+ ${state.preservedFiles.size - 25} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                }
            }

            // ---- safe retry ----
            BauhausSurfaceCard(elevated = true) {
                Text("Try recovery again", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Recovery re-checks every preserved file against its stored hash. Your data " +
                        "stays blocked unless and until that check passes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BauhausColors.SecondaryText
                )
                Spacer(Modifier.height(12.dp))
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator(color = BauhausColors.Red) }
                } else {
                    BauhausPrimaryButton(
                        text = "Retry Recovery",
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                val result = coordinator.retryRecoveryForUi()
                                busy = false
                                message = result.message
                                if (result.succeeded) onRecovered()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recovery_retry_button")
                    )
                }
            }

            // ---- preserve / export ----
            BauhausSurfaceCard(elevated = true) {
                Text("Keep a safe copy", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Copies the preserved recovery data to an export folder so it can be kept " +
                        "outside the app. The original is not moved or deleted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = BauhausColors.SecondaryText
                )
                Spacer(Modifier.height(12.dp))
                BauhausOutlinedButton(
                    text = "Export Recovery Data",
                    onClick = {
                        val files: List<File> = coordinator.exportRecoveryData()
                        exported = files.map { it.absolutePath }
                        message = if (files.isEmpty()) {
                            "Nothing to export."
                        } else {
                            "Copied ${files.size} file(s) to ${files.first().parent}"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recovery_export_button")
                )
                if (exported.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.testTag("recovery_export_list")) {
                        exported.take(10).forEach {
                            Text(
                                it.substringAfterLast('/'),
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                }
            }

            message?.let { text ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(BauhausDimens.SmallCornerRadius))
                        .background(BauhausColors.Yellow.copy(alpha = 0.22f))
                        .border(
                            1.dp,
                            BauhausColors.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(BauhausDimens.SmallCornerRadius)
                        )
                        .padding(12.dp)
                        .testTag("recovery_message")
                ) {
                    Text(text, style = MaterialTheme.typography.labelSmall, color = BauhausColors.Black)
                }
            }

            // Explicit statement that no destructive option exists.
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recovery_no_destructive_note"),
                text = "There is no option to discard your vault from this screen. Access is only " +
                    "restored once recovery verifies successfully.",
                style = MaterialTheme.typography.labelSmall,
                color = BauhausColors.SecondaryText
            )

            Spacer(Modifier.height(8.dp))
            Text(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                text = "",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
