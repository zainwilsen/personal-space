package app.personalspace.vault.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSectionHeader
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import java.io.File

/**
 * Item 3 — user-facing Orphan Quarantine screen.
 *
 * The screen is built around one promise: **nothing here deletes anything except the "Delete forever"
 * action, and that action is behind an explicit confirmation.** Detection is a read-only scan, moving
 * files to quarantine is reversible, and every quarantined file can be inspected, exported and put back.
 *
 * Test tags are stable so the behaviour is covered by UI tests rather than by reading the code.
 */
@Composable
fun OrphanQuarantineScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.quarantine.collectAsStateWithLifecycle()
    var showEmptyConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scanForOrphans()
    }

    BauhausScreenScaffold(title = "Orphan Quarantine", onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Files that no note, draft or attachment refers to. Nothing is deleted here: " +
                    "files are moved aside and can be restored, exported or left alone indefinitely.",
                style = MaterialTheme.typography.bodyMedium,
                color = BauhausColors.SecondaryText,
                modifier = Modifier.testTag("quarantine_explanation")
            )

            if (state.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("quarantine_loading"),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }

            state.lastMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.Black,
                    modifier = Modifier.testTag("quarantine_message")
                )
            }
            state.lastError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.Red,
                    modifier = Modifier.testTag("quarantine_error")
                )
            }

            // ---------------- inspection ----------------
            BauhausSectionHeader(title = "Inspection")
            val scan = state.scanned
            BauhausSurfaceCard(elevated = false) {
                Column(modifier = Modifier.fillMaxWidth().testTag("quarantine_scan_summary")) {
                    if (scan == null) {
                        Text(
                            text = "No scan yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BauhausColors.SecondaryText
                        )
                    } else {
                        Text(
                            text = "${scan.attachmentFilesSeen} attachment file(s), " +
                                "${scan.imageFilesSeen} image file(s) on disk",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${scan.referencedAttachmentNames} attachment reference(s), " +
                                "${scan.referencedImageNames} image reference(s) live in the vault",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BauhausColors.SecondaryText
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (scan.candidates.isEmpty()) {
                                "No unreferenced files found."
                            } else {
                                "${scan.candidates.size} unreferenced file(s):"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        scan.candidates.take(20).forEach { candidate ->
                            Text(
                                text = "• ${candidate.fileName} — ${candidate.sizeBytes} B, " +
                                    "${candidate.ageMillis / 3_600_000L} h old",
                                style = MaterialTheme.typography.bodySmall,
                                color = BauhausColors.SecondaryText
                            )
                        }
                        if (scan.recentFilesSkipped.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${scan.recentFilesSkipped.size} recent file(s) skipped — " +
                                    "too new to judge safely (an import or restore may be writing them).",
                                style = MaterialTheme.typography.bodySmall,
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BauhausPrimaryButton(
                    text = "Scan again",
                    onClick = { viewModel.scanForOrphans() },
                    modifier = Modifier.testTag("quarantine_scan_button"),
                    enabled = !state.loading,
                    leadingIcon = Icons.Outlined.Search
                )
                if ((scan?.candidates?.size ?: 0) > 0) {
                    BauhausPrimaryButton(
                        text = "Move to quarantine",
                        onClick = { viewModel.quarantineScannedOrphans() },
                        modifier = Modifier.testTag("quarantine_move_button"),
                        enabled = !state.loading
                    )
                }
            }

            // ---------------- quarantine contents ----------------
            BauhausSectionHeader(title = "In quarantine")
            val manifest = state.manifest
            BauhausSurfaceCard(elevated = false) {
                Column(modifier = Modifier.fillMaxWidth().testTag("quarantine_manifest")) {
                    val entries = manifest?.entries.orEmpty()
                    if (entries.isEmpty()) {
                        Text(
                            text = "Quarantine is empty.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BauhausColors.SecondaryText
                        )
                    } else {
                        Text(
                            text = "${entries.size} file(s) held. Each entry keeps its original path, " +
                                "size, sha256 and timestamp.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        entries.forEach { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .testTag("quarantine_entry_${entry.quarantineFileName}")
                            ) {
                                Text(entry.originalRelativePath, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${entry.sizeBytes} B · sha256 ${entry.sha256Hex.take(16)}… · " +
                                        entry.kind,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BauhausColors.SecondaryText
                                )
                                TextButton(
                                    onClick = { viewModel.restoreQuarantinedFile(entry.quarantineFileName) },
                                    enabled = !state.loading,
                                    modifier = Modifier.testTag(
                                        "quarantine_restore_${entry.quarantineFileName}"
                                    )
                                ) {
                                    androidx.compose.material3.Icon(
                                        Icons.Outlined.Restore, contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Restore to vault")
                                }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BauhausPrimaryButton(
                    text = "Export copy",
                    onClick = {
                        // exported into the app's own external files dir so no storage permission is
                        // needed; the path is shown to the user
                        viewModel.exportQuarantine(viewModel.quarantineExportRoot())
                    },
                    modifier = Modifier.testTag("quarantine_export_button"),
                    enabled = !state.loading && manifest?.entries?.isNotEmpty() == true,
                    leadingIcon = Icons.Outlined.FileDownload
                )
                if ((manifest?.entries?.size ?: 0) > 0) {
                    BauhausPrimaryButton(
                        text = "Delete forever",
                        onClick = { showEmptyConfirmation = true },
                        modifier = Modifier.testTag("quarantine_empty_button"),
                        enabled = !state.loading,
                        leadingIcon = Icons.Outlined.DeleteForever
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Quarantined files are never deleted automatically and never expire. " +
                    "They are not part of a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = BauhausColors.SecondaryText,
                textAlign = TextAlign.Start,
                modifier = Modifier.testTag("quarantine_policy_note")
            )
        }
    }

    if (showEmptyConfirmation) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirmation = false },
            title = { Text("Delete quarantined files forever?") },
            text = {
                Text(
                    "This permanently deletes ${state.manifest?.entries?.size ?: 0} file(s) that nothing " +
                        "in the vault refers to. It cannot be undone. Export a copy first if you might " +
                        "want them later."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyConfirmation = false
                        viewModel.emptyQuarantine()
                    },
                    modifier = Modifier.testTag("quarantine_confirm_empty")
                ) { Text("Delete forever", color = BauhausColors.Red) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEmptyConfirmation = false },
                    modifier = Modifier.testTag("quarantine_cancel_empty")
                ) { Text("Cancel") }
            }
        )
    }
}
