package app.personalspace.vault.presentation.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.personalspace.vault.BuildConfig
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.crypto.BiometricHardwareStatus
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausConfirmDialog
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausEmptyState
import app.personalspace.vault.core.designsystem.BauhausErrorBanner
import app.personalspace.vault.core.designsystem.BauhausOutlinedButton
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSectionHeader
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.designsystem.BauhausTagChip
import app.personalspace.vault.core.designsystem.BauhausTextField
import app.personalspace.vault.core.designsystem.BottomNavTab
import app.personalspace.vault.core.designsystem.PersonalSpaceLogo
import app.personalspace.vault.data.backup.SerializableRecordSnapshot
import app.personalspace.vault.domain.model.ConflictResolutionChoice
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.model.SyncConflictItem
import app.personalspace.vault.presentation.navigation.ScreenRoutes
import kotlinx.serialization.json.Json

@Composable
fun SettingsHomeScreen(
    viewModel: SettingsViewModel,
    onSelectBottomTab: (BottomNavTab) -> Unit,
    onNavigate: (String) -> Unit
) {
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()

    BauhausScreenScaffold(
        title = "Settings",
        activeTab = BottomNavTab.SETTINGS,
        onSelectTab = onSelectBottomTab
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = BauhausDimens.PageHorizontalPadding,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsNavCard(
                    icon = Icons.Outlined.Tune,
                    title = "General",
                    subtitle = "Note auto-save & notification preferences",
                    onClick = { onNavigate(ScreenRoutes.SETTINGS_GENERAL) }
                )
            }
            item {
                SettingsNavCard(
                    icon = Icons.Outlined.CloudSync,
                    title = "Backup & Sync",
                    subtitle = "Status: ${syncState.displayLabel}" +
                        if (conflicts.isNotEmpty()) " • ${conflicts.size} conflict(s)" else "",
                    onClick = { onNavigate(ScreenRoutes.SETTINGS_BACKUP_SYNC) }
                )
            }
            item {
                SettingsNavCard(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "Recycle Bin",
                    subtitle = "Restore or permanently delete deleted items",
                    onClick = { onNavigate(ScreenRoutes.RECYCLE_BIN) }
                )
            }
            item {
                SettingsNavCard(
                    icon = Icons.Outlined.FolderOff,
                    title = "Orphan Quarantine",
                    subtitle = "Inspect, export, restore or delete unreferenced files",
                    onClick = { onNavigate(ScreenRoutes.SETTINGS_QUARANTINE) }
                )
            }
            item {
                SettingsNavCard(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version ${BuildConfig.VERSION_NAME}",
                    onClick = { onNavigate(ScreenRoutes.SETTINGS_ABOUT) }
                )
            }

            item { Spacer(modifier = Modifier.height(36.dp)) }
        }
    }
}

@Composable
private fun SettingsNavCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    BauhausSurfaceCard(elevated = true, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = BauhausColors.Black)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = BauhausColors.SecondaryText
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = BauhausColors.SecondaryText
            )
        }
    }
}

@Composable
fun GeneralSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val s by viewModel.settings.collectAsStateWithLifecycle()

    fun checkNotificationsEnabled(): Boolean {
        return viewModel.reminderScheduler.hasNotificationPermission() &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    var notifEnabled by remember { mutableStateOf(checkNotificationsEnabled()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifEnabled = checkNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifEnabled = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!notifEnabled) {
            openAppNotificationSettings()
        }
    }

    val bauhausSwitchColors = SwitchDefaults.colors(
        checkedThumbColor = BauhausColors.LightSurface,
        checkedTrackColor = BauhausColors.Red,
        uncheckedThumbColor = BauhausColors.SecondaryText,
        uncheckedTrackColor = BauhausColors.Background,
        uncheckedBorderColor = BauhausColors.Divider
    )

    BauhausScreenScaffold(
        title = "General",
        titleStyle = MaterialTheme.typography.headlineMedium,
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 16.dp)
                .navigationBarsPadding()
                .testTag("general_settings_root"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BauhausSurfaceCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BauhausColors.CardSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = null,
                                tint = BauhausColors.Red,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Save",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = BauhausColors.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Automatically save notes while writing and when closing.",
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = s.autoSaveNotes,
                        onCheckedChange = viewModel::setAutoSaveNotes,
                        colors = bauhausSwitchColors,
                        modifier = Modifier.testTag("switch_auto_save_notes")
                    )
                }
            }

            BauhausSurfaceCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BauhausColors.CardSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = if (notifEnabled) BauhausColors.Blue else BauhausColors.Red,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notifications",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = BauhausColors.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (notifEnabled) {
                                    "Enabled for alarms and scheduled reminders."
                                } else {
                                    "Enable notifications so scheduled alarms can alert you."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (notifEnabled) BauhausColors.Blue else BauhausColors.SecondaryText
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = notifEnabled,
                        onCheckedChange = { wantEnable ->
                            if (wantEnable && !notifEnabled) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !viewModel.reminderScheduler.hasNotificationPermission()
                                ) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openAppNotificationSettings()
                                }
                            } else {
                                openAppNotificationSettings()
                            }
                        },
                        colors = bauhausSwitchColors,
                        modifier = Modifier.testTag("switch_general_notifications")
                    )
                }

                if (!notifEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    BauhausPrimaryButton(
                        text = "Enable Notifications",
                        leadingIcon = Icons.Outlined.Notifications,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !viewModel.reminderScheduler.hasNotificationPermission()
                            ) {
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openAppNotificationSettings()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_general_enable_notifications")
                    )
                }
            }

            BauhausSurfaceCard(elevated = true) {
                Text(
                    text = "Default Note Sorting",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SortOption.RECENTLY_MODIFIED,
                        SortOption.RECENTLY_CREATED,
                        SortOption.TITLE_ASC,
                        SortOption.TITLE_DESC
                    ).forEach { opt ->
                        BauhausTagChip(
                            label = opt.label,
                            selected = s.defaultSortOrder == opt,
                            onClick = { viewModel.setDefaultSort(opt) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            BauhausSurfaceCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reduced Motion",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Minimizes launch choreography and screen transition motion.",
                            style = MaterialTheme.typography.labelSmall,
                            color = BauhausColors.SecondaryText
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = s.reducedMotion,
                        onCheckedChange = viewModel::setReducedMotion,
                        colors = bauhausSwitchColors
                    )
                }
            }
        }
    }
}





@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSyncScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val localInfo by viewModel.localBackupInfo.collectAsStateWithLifecycle()
    val driveBackups by viewModel.driveBackups.collectAsStateWithLifecycle()
    val msg by viewModel.message.collectAsStateWithLifecycle()
    val err by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val cloud by viewModel.cloudBackup.collectAsStateWithLifecycle()

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) viewModel.handleDriveConsentResult(res.data)
    }

    val onDriveConsentRequest: (android.app.PendingIntent) -> Unit = { pi ->
        consentLauncher.launch(IntentSenderRequest.Builder(pi).build())
    }

    var showScheduleTimePicker by remember { mutableStateOf(false) }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) viewModel.saveBackupCopyToUri(uri) }

    LaunchedEffect(Unit) {
        viewModel.refreshLocalBackupInfo()
        viewModel.refreshCloudState()
    }

    BauhausScreenScaffold(title = "Backup & Sync", onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            err?.let { BauhausErrorBanner(message = it, onDismiss = viewModel::clearFeedback) }
            msg?.let { BauhausSurfaceCard { Text(it, color = BauhausColors.Blue) } }

            BauhausSurfaceCard {
                Text("Local Backup", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (localInfo.exists) {
                        DateFormatters.formatDateTime(localInfo.modifiedMillis) + "  ·  " +
                            DateFormatters.formatBytes(localInfo.sizeBytes)
                    } else {
                        "No backup yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = BauhausColors.SecondaryText.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator(color = BauhausColors.Red) }
                } else {
                    BauhausPrimaryButton(
                        text = "Backup",
                        onClick = viewModel::createLocalBackupNow,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                BauhausOutlinedButton(
                    text = "Export Backup",
                    onClick = { saveCopyLauncher.launch("PersonalSpace_Backup.psbackup") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            BauhausSurfaceCard(elevated = true) {
                Text("Google Drive Backup", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(6.dp))

                CloudStatusRow(status = cloud.status, connected = cloud.connected)
                Spacer(modifier = Modifier.height(8.dp))

                if (cloud.connected) {
                    AccountRow(email = cloud.accountEmail, onDisconnect = viewModel::disconnectDrive)
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = BauhausColors.Divider.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(10.dp))
                    BackupDetailRow(
                        label = "Last Backup",
                        value = if (cloud.lastBackupMillis > 0L) {
                            DateFormatters.formatDateTime(cloud.lastBackupMillis)
                        } else {
                            "No backup yet"
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BackupDetailRow(
                        label = "Size",
                        value = if (cloud.lastBackupSizeBytes > 0L) {
                            DateFormatters.formatBytes(cloud.lastBackupSizeBytes)
                        } else {
                            "—"
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BauhausPrimaryButton(
                        text = if (busy) "Please wait…" else "Backup Now",
                        onClick = { if (!busy) viewModel.createDriveBackupNow() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BauhausOutlinedButton(
                        text = "Restore from Google Drive",
                        onClick = {
                            val latest = driveBackups?.firstOrNull()
                            if (latest != null) {
                                onNavigate(ScreenRoutes.SETTINGS_RESTORE_BACKUP)
                            } else {
                                viewModel.loadDriveBackups()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "Apne Google account se connect karo — backup usi account ki hidden app storage me jayega.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BauhausColors.SecondaryText
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    BauhausPrimaryButton(
                        text = "Connect Google Account",
                        onClick = { viewModel.connectDrive { pi -> onDriveConsentRequest(pi) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // P2 / correction #3 (layout revised): the portable key wrap means a .psbackup file is
            // readable by anyone who obtains it (measured: vault data key recovered from the file
            // alone in 6 ms — verification/evidence/portG1_file_only_attack.txt). The notice sits
            // BELOW the Google Drive card so the Drive controls stay reachable without scrolling.
            WarningNotice(
                tag = "backup_sensitivity_warning",
                title = "Treat backup files as sensitive",
                body = "A .psbackup file can be opened without your Master Password by anyone " +
                    "who gets the file. Keep it private, do not share it on public chats, and " +
                    "delete copies you no longer need."
            )

            BauhausSurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto Backup", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Daily Backup (Drive)",
                            style = MaterialTheme.typography.labelSmall,
                            color = BauhausColors.SecondaryText.copy(alpha = 0.75f)
                        )
                    }
                    Switch(
                        checked = s.dailyBackupEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDailyBackupSchedule(enabled, s.dailyBackupHour24, s.dailyBackupMinute)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                BauhausOutlinedButton(
                    text = "Backup Time  %02d:%02d".format(s.dailyBackupHour24, s.dailyBackupMinute),
                    onClick = { showScheduleTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsNavCard(
                icon = Icons.Outlined.Backup,
                title = "Restore Backup",
                subtitle = null,
                onClick = { onNavigate(ScreenRoutes.SETTINGS_RESTORE_BACKUP) }
            )

                    }
    }

    if (showScheduleTimePicker) {
        val tp = rememberTimePickerState(
            initialHour = s.dailyBackupHour24,
            initialMinute = s.dailyBackupMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showScheduleTimePicker = false },
            containerColor = BauhausColors.CardSurface,
            title = { Text("Auto Backup Time", style = MaterialTheme.typography.headlineMedium) },
            text = { TimePicker(state = tp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setDailyBackupSchedule(true, tp.hour, tp.minute)
                        showScheduleTimePicker = false
                    }
                ) { Text("Save", color = BauhausColors.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showScheduleTimePicker = false }) {
                    Text("Cancel", color = BauhausColors.Black)
                }
            }
        )
    }
}

@Composable
private fun CloudStatusRow(status: BackupStatus, connected: Boolean) {
    val color = when (status) {
        BackupStatus.BACKUP_FAILED, BackupStatus.RESTORE_FAILED -> BauhausColors.Red
        BackupStatus.BACKING_UP, BackupStatus.RESTORE_IN_PROGRESS -> BauhausColors.Blue
        BackupStatus.BACKUP_COMPLETED, BackupStatus.RESTORE_COMPLETED, BackupStatus.UP_TO_DATE -> BauhausColors.Blue
        else -> BauhausColors.SecondaryText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cloud_backup_status_row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) color else BauhausColors.SecondaryText.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.testTag("cloud_backup_status_text")
        )
    }
}

@Composable
private fun AccountRow(email: String?, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountCircle,
            contentDescription = null,
            tint = BauhausColors.Black,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = email?.takeIf { it.isNotBlank() } ?: "Google account connected",
            style = MaterialTheme.typography.bodyMedium,
            color = BauhausColors.Black,
            modifier = Modifier
                .weight(1f)
                .testTag("cloud_backup_account")
        )
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", style = MaterialTheme.typography.labelMedium, color = BauhausColors.Red)
        }
    }
}

@Composable
private fun BackupDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = BauhausColors.SecondaryText)
        Text(value, style = MaterialTheme.typography.labelMedium, color = BauhausColors.Black)
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BauhausScreenScaffold(
        title = "About",
        titleStyle = MaterialTheme.typography.headlineMedium,
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 38.dp)
                .navigationBarsPadding()
                .testTag("about_screen_root"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            PersonalSpaceLogo(emblemSize = 84.dp, showWordmark = true)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelLarge,
                color = BauhausColors.SecondaryText,
                modifier = Modifier.testTag("about_version_text")
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Zain's first successfully completed application, developed between November 14 and November 18, 2026. A small beginning and a meaningful milestone in his journey.",
                style = MaterialTheme.typography.bodyMedium,
                color = BauhausColors.SecondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .testTag("about_tagline_text")
            )

            Spacer(modifier = Modifier.height(25.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.testTag("about_signature_row")
            ) {
                Text(
                    text = "Crafted with",
                    style = MaterialTheme.typography.labelLarge,
                    color = BauhausColors.SecondaryText
                )
                Spacer(modifier = Modifier.width(6.dp))
                BauhausRedHeartShape(
                    size = 18.dp,
                    modifier = Modifier.testTag("about_red_heart")
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "ZAIN",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    ),
                    color = BauhausColors.Black,
                    modifier = Modifier.testTag("about_by_zain")
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BauhausRedHeartShape(
    size: Dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.width(size).height(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.90f)
            cubicTo(
                w * 0.12f, h * 0.64f,
                w * 0.02f, h * 0.34f,
                w * 0.25f, h * 0.16f
            )
            cubicTo(
                w * 0.39f, h * 0.06f,
                w * 0.50f, h * 0.18f,
                w * 0.50f, h * 0.28f
            )
            cubicTo(
                w * 0.50f, h * 0.18f,
                w * 0.61f, h * 0.06f,
                w * 0.75f, h * 0.16f
            )
            cubicTo(
                w * 0.98f, h * 0.34f,
                w * 0.88f, h * 0.64f,
                w * 0.50f, h * 0.90f
            )
            close()
        }
        drawPath(path = path, color = BauhausColors.Red)
    }
}

@Composable
fun RestoreBackupScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onRestored: () -> Unit
) {
    val msg by viewModel.message.collectAsStateWithLifecycle()
    val err by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val driveBackups by viewModel.driveBackups.collectAsStateWithLifecycle()
    val driveConnected by viewModel.driveConnected.collectAsStateWithLifecycle()
    val isLoadingDriveBackups by viewModel.isLoadingDriveBackups.collectAsStateWithLifecycle()

    var showConfirm by remember { mutableStateOf(false) }
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDriveFileId by remember { mutableStateOf<String?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleDriveConsentResult(result.data)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingFileUri = uri
            showConfirm = true
        }
    }

    LaunchedEffect(Unit) {
        if (driveConnected) viewModel.loadDriveBackups()
    }

    BauhausScreenScaffold(
        title = "Restore Backup",
        titleStyle = MaterialTheme.typography.headlineSmall,
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            err?.let { BauhausErrorBanner(message = it, onDismiss = viewModel::clearFeedback) }
            msg?.let { BauhausSurfaceCard { Text(it, color = BauhausColors.Blue) } }

            BauhausSurfaceCard(elevated = true) {
                Text("LOCAL", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator(color = BauhausColors.Red) }
                } else {
                    BauhausPrimaryButton(
                        text = "Restore From Local Backup",
                        onClick = { showConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                BauhausOutlinedButton(
                    text = "Choose Backup File",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            BauhausSurfaceCard {
                Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                if (driveConnected) {
                    if (isLoadingDriveBackups) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = BauhausColors.Red,
                                strokeWidth = 2.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    BauhausOutlinedButton(
                        text = if (!driveBackups.isNullOrEmpty()) {
                            "Choose Latest Drive Backup"
                        } else {
                            "Refresh Drive Backups"
                        },
                        onClick = {
                            val latestId = driveBackups?.firstOrNull()?.fileId
                            if (latestId != null) {
                                pendingFileUri = null
                                pendingDriveFileId = latestId
                                showConfirm = true
                            } else {
                                viewModel.loadDriveBackups()
                            }
                        },
                        enabled = !busy && !isLoadingDriveBackups,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    BauhausPrimaryButton(
                        text = "Connect Google Account",
                        onClick = {
                            viewModel.connectDrive { pendingIntent ->
                                consentLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent).build()
                                )
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
                pendingFileUri = null
                pendingDriveFileId = null
            },
            containerColor = BauhausColors.CardSurface,
            title = { Text("Replace current space?", style = MaterialTheme.typography.headlineMedium) },
            text = {
                Text(
                    "Selected backup current local data ko replace karega. Successful restore ke baad " +
                        "old local data alag se recover nahi kiya ja sakega. Continue karne se pehle " +
                        "zaroori data ka separate backup rakh lo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.SecondaryText
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        val uri = pendingFileUri
                        val driveFileId = pendingDriveFileId
                        pendingFileUri = null
                        pendingDriveFileId = null
                        when {
                            driveFileId != null -> viewModel.restoreFromDriveBackup(driveFileId, onRestored)
                            uri != null -> viewModel.restoreFromLocalUri(uri, onRestored)
                            else -> viewModel.restoreFromLatestLocalBackup(onRestored)
                        }
                    }
                ) { Text("Restore", color = BauhausColors.Red) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        pendingFileUri = null
                        pendingDriveFileId = null
                    }
                ) {
                    Text("Cancel", color = BauhausColors.Black)
                }
            }
        )
    }
}

/**
 * P2: inline warning card. Deliberately local to the settings screen so the wording ships with the
 * screen that exports the file, and so no new module or dependency is introduced.
 */
@Composable
private fun WarningNotice(tag: String, title: String, body: String) {
    val colors = BauhausColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clip(RoundedCornerShape(BauhausDimens.SmallCornerRadius))
            .background(colors.Yellow.copy(alpha = 0.22f))
            .border(
                width = 1.dp,
                color = colors.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius)
            )
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = colors.Black
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall,
            color = colors.SecondaryText
        )
    }
}
