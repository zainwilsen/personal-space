package app.personalspace.vault.presentation.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.designsystem.BauhausActivityBarChart
import app.personalspace.vault.core.designsystem.BauhausBarPoint
import app.personalspace.vault.core.designsystem.BauhausCircularProgress
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausConfirmDialog
import app.personalspace.vault.core.designsystem.BauhausCornerAccent
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausEmptyState
import app.personalspace.vault.core.designsystem.BauhausFolderIcon
import app.personalspace.vault.core.designsystem.BauhausNoteFileIcon
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSectionHeader
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.designsystem.BottomNavTab
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.domain.model.DeletedVaultItem
import app.personalspace.vault.domain.model.SyncHealthState
import app.personalspace.vault.domain.model.TimelineEvent
import app.personalspace.vault.domain.model.VaultStatisticsOverview
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.folders.BauhausMarkTickIndicator
import app.personalspace.vault.presentation.folders.CollapsibleInlineSearchBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class TimelineViewModel @Inject constructor(
    repository: PersonalSpaceRepository
) : ViewModel() {
    val events: StateFlow<List<TimelineEvent>> = repository.observeTimelineEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenFolder: (String) -> Unit
) {
    val events by viewModel.events.collectAsStateWithLifecycle()

    BauhausScreenScaffold(
        title = "Timeline",
        subtitle = "Chronological Vault Activity",
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = BauhausDimens.PageHorizontalPadding,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (events.isEmpty()) {
                item {
                    BauhausEmptyState(
                        title = "No Timeline Activity Yet",
                        message = "Note creations, updates, restorations, and completed reminders appear chronologically here."
                    )
                }
            } else {
                items(events, key = { it.id }) { ev ->
                    val nodeColor = when (ev.accentColor) {
                        "RED" -> BauhausColors.Red
                        "BLUE" -> BauhausColors.Blue
                        "YELLOW" -> BauhausColors.Yellow
                        else -> BauhausColors.Black
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 6.dp, end = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(nodeColor)
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.5.dp)
                                    .height(46.dp)
                                    .background(BauhausColors.Divider)
                            )
                        }

                        BauhausSurfaceCard(
                            elevated = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (ev.entityType) {
                                    "RECORD" -> ev.entityId?.let(onOpenRecord)
                                    "FOLDER" -> ev.entityId?.let(onOpenFolder)
                                }
                            }
                        ) {
                            Text(ev.displayTitle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${ev.eventType.label}  •  ${DateFormatters.formatDateTime(ev.timestampMillis)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}


@HiltViewModel
class StatisticsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    repository: PersonalSpaceRepository
) : ViewModel() {

    private val _noteImagesBytes = kotlinx.coroutines.flow.MutableStateFlow(computeNoteImagesBytes())
    val noteImagesBytes: kotlinx.coroutines.flow.StateFlow<Long> = _noteImagesBytes

    private fun computeNoteImagesBytes(): Long {
        val dir = java.io.File(appContext.filesDir, "note_images")
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    val stats: StateFlow<VaultStatisticsOverview> = repository.observeVaultStatistics()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            VaultStatisticsOverview(
                totalRecords = 0,
                totalFolders = 0,
                totalAttachments = 0,
                databaseBytes = 0L,
                encryptedAttachmentsBytes = 0L,
                localBackupsBytes = 0L,
                completedRemindersCount = 0,
                pendingRemindersCount = 0,
                missedRemindersCount = 0,
                dailyCreationSeries = emptyList(),
                syncState = SyncHealthState.NOT_CONNECTED,
                lastSyncedMillis = null,
                lastBackupMillis = null
            )
        )
}

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    onSelectBottomTab: (BottomNavTab) -> Unit
) {
    val s by viewModel.stats.collectAsStateWithLifecycle()
    val noteImagesBytes by viewModel.noteImagesBytes.collectAsStateWithLifecycle()
    val totalVaultBytes = s.databaseBytes + s.encryptedAttachmentsBytes + s.localBackupsBytes + noteImagesBytes

    BauhausScreenScaffold(
        title = "Statistics",
        activeTab = BottomNavTab.STATISTICS,
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
            verticalArrangement = Arrangement.spacedBy(BauhausDimens.SectionSpacing)
        ) {
            item {
                BauhausSurfaceCard {
                    Text("Storage", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        DateFormatters.formatBytes(totalVaultBytes),
                        style = MaterialTheme.typography.displaySmall,
                        color = BauhausColors.Black
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = BauhausColors.Divider)
                    Spacer(modifier = Modifier.height(6.dp))
                    StatValueRow(
                        label = "Database",
                        value = DateFormatters.formatBytes(s.databaseBytes)
                    )
                    StatValueRow(
                        label = "Images",
                        value = DateFormatters.formatBytes(noteImagesBytes)
                    )
                    StatValueRow(
                        label = "Attachments (" + s.totalAttachments + ")",
                        value = DateFormatters.formatBytes(s.encryptedAttachmentsBytes)
                    )
                    StatValueRow(
                        label = "Backups",
                        value = DateFormatters.formatBytes(s.localBackupsBytes)
                    )
                }
            }

            item {
                BauhausSurfaceCard {
                    Text("Backup & Sync", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = BauhausColors.Divider)
                    Spacer(modifier = Modifier.height(6.dp))
                    StatValueRow(label = "Sync", value = s.syncState.displayLabel)
                    StatValueRow(
                        label = "Last synced",
                        value = s.lastSyncedMillis?.let { DateFormatters.formatDateTime(it) } ?: "—"
                    )
                    StatValueRow(
                        label = "Last backup",
                        value = s.lastBackupMillis?.let { DateFormatters.formatDateTime(it) } ?: "—"
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun StatValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = BauhausColors.SecondaryText
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = BauhausColors.Black
        )
    }
}



@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val repository: PersonalSpaceRepository
) : ViewModel() {

    val deletedItems: StateFlow<List<DeletedVaultItem>> = repository.observeDeletedItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restoreBatch(batchId: String) {
        viewModelScope.launch { repository.restoreDeletedBatch(batchId) }
    }

    fun restoreBatches(batchIds: Set<String>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            batchIds.forEach { id ->
                runCatching { repository.restoreDeletedBatch(id) }
            }
            onDone()
        }
    }

    fun deletePermanently(batchId: String) {
        viewModelScope.launch { repository.permanentlyDeleteBatch(batchId) }
    }

    fun deleteBatchesPermanently(batchIds: Set<String>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            batchIds.forEach { id ->
                runCatching { repository.permanentlyDeleteBatch(id) }
            }
            onDone()
        }
    }

    fun emptyAll() {
        viewModelScope.launch { repository.emptyRecycleBin() }
    }
}

@Composable
fun RecycleBinScreen(
    viewModel: RecycleBinViewModel,
    onBack: () -> Unit
) {
    val items by viewModel.deletedItems.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var showHeaderMenu by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }

    var isMarkMode by rememberSaveable { mutableStateOf(false) }
    var selectedBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var batchToDestroy by remember { mutableStateOf<DeletedVaultItem?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var confirmEmptyAll by remember { mutableStateOf(false) }

    val displayedItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) {
            items
        } else {
            val q = searchQuery.trim()
            items.filter { it.displayTitle.contains(q, ignoreCase = true) }
        }
    }

    BackHandler(enabled = isMarkMode) {
        isMarkMode = false
        selectedBatchIds = emptySet()
    }

    BauhausScreenScaffold(
        title = if (isMarkMode) "${selectedBatchIds.size} Selected" else "Recycle Bin",
        titleStyle = MaterialTheme.typography.headlineMedium,
        onBack = if (isMarkMode) {
            {
                isMarkMode = false
                selectedBatchIds = emptySet()
            }
        } else {
            onBack
        },
        actions = {
            if (isMarkMode) {
                IconButton(
                    onClick = {
                        selectedBatchIds = if (selectedBatchIds.size == displayedItems.size && displayedItems.isNotEmpty()) {
                            emptySet()
                        } else {
                            displayedItems.map { it.batchId }.toSet()
                        }
                    },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .testTag("btn_recycle_select_all")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DoneAll,
                        contentDescription = "Select all",
                        tint = BauhausColors.Black
                    )
                }
                IconButton(
                    onClick = {
                        if (selectedBatchIds.isNotEmpty()) {
                            viewModel.restoreBatches(selectedBatchIds) {
                                isMarkMode = false
                                selectedBatchIds = emptySet()
                            }
                        }
                    },
                    enabled = selectedBatchIds.isNotEmpty(),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .testTag("btn_recycle_restore_marked")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Restore,
                        contentDescription = "Restore selected",
                        tint = if (selectedBatchIds.isNotEmpty()) BauhausColors.Blue else BauhausColors.SecondaryText
                    )
                }
                IconButton(
                    onClick = {
                        if (selectedBatchIds.isNotEmpty()) {
                            showBatchDeleteDialog = true
                        }
                    },
                    enabled = selectedBatchIds.isNotEmpty(),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .testTag("btn_recycle_delete_marked")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete selected",
                        tint = if (selectedBatchIds.isNotEmpty()) BauhausColors.Red else BauhausColors.SecondaryText
                    )
                }
                IconButton(
                    onClick = {
                        isMarkMode = false
                        selectedBatchIds = emptySet()
                    },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .testTag("btn_recycle_cancel_mark")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Exit mark mode",
                        tint = BauhausColors.Black
                    )
                }
            } else {
                Box {
                    IconButton(
                        onClick = { showHeaderMenu = true },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_recycle_header_menu")
                    ) {
                        Box {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = "Recycle Bin menu",
                                tint = BauhausColors.Black
                            )
                            if (searchQuery.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .clip(CircleShape)
                                        .background(BauhausColors.Red)
                                )
                            }
                        }
                    }

                    if (showHeaderMenu) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { showHeaderMenu = false },
                            containerColor = BauhausColors.CardSurface,
                            modifier = Modifier
                                .width(228.dp)
                                .testTag("recycle_header_menu")
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = null,
                                        tint = BauhausColors.Blue
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Search",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    isSearchVisible = true
                                },
                                modifier = Modifier.testTag("menu_recycle_search")
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = BauhausColors.Red
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Mark",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    isMarkMode = true
                                    selectedBatchIds = emptySet()
                                },
                                modifier = Modifier.testTag("menu_recycle_mark")
                            )
                            HorizontalDivider(color = BauhausColors.Divider)
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteSweep,
                                        contentDescription = null,
                                        tint = if (items.isNotEmpty()) BauhausColors.Red else BauhausColors.SecondaryText
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Delete All",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (items.isNotEmpty()) BauhausColors.Red else BauhausColors.SecondaryText
                                    )
                                },
                                enabled = items.isNotEmpty(),
                                onClick = {
                                    showHeaderMenu = false
                                    confirmEmptyAll = true
                                },
                                modifier = Modifier.testTag("menu_recycle_delete_all")
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(
                visible = isSearchVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = BauhausDimens.PageHorizontalPadding,
                            vertical = 6.dp
                        )
                ) {
                    CollapsibleInlineSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search Recycle Bin",
                        onClose = {
                            searchQuery = ""
                            focusManager.clearFocus()
                            isSearchVisible = false
                        },
                        onDone = { focusManager.clearFocus() },
                        testTag = "input_recycle_search"
                    )
                }
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("recycle_empty_unlayered"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(BauhausColors.CardSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = BauhausColors.SecondaryText,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Recycle Bin is Empty",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = BauhausColors.SecondaryText
                        )
                    }
                }
            } else if (displayedItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Matching Items",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = BauhausColors.SecondaryText
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("recycle_items_list"),
                    contentPadding = PaddingValues(
                        start = BauhausDimens.PageHorizontalPadding,
                        end = BauhausDimens.PageHorizontalPadding,
                        top = 10.dp,
                        bottom = 48.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayedItems, key = { it.batchId }) { item ->
                        RecycleBinItemCard(
                            item = item,
                            isMarkMode = isMarkMode,
                            isSelected = item.batchId in selectedBatchIds,
                            onToggleSelect = {
                                selectedBatchIds = if (item.batchId in selectedBatchIds) {
                                    selectedBatchIds - item.batchId
                                } else {
                                    selectedBatchIds + item.batchId
                                }
                            },
                            onRestore = { viewModel.restoreBatch(item.batchId) },
                            onDeleteForever = { batchToDestroy = item }
                        )
                    }
                }
            }
        }
    }

    batchToDestroy?.let { target ->
        BauhausConfirmDialog(
            title = "Delete",
            message = "Permanently delete '${target.displayTitle}'?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { batchToDestroy = null },
            onConfirm = {
                viewModel.deletePermanently(target.batchId)
                batchToDestroy = null
            }
        )
    }

    if (showBatchDeleteDialog && selectedBatchIds.isNotEmpty()) {
        val count = selectedBatchIds.size
        val delMsg = if (count == 1) {
            val single = items.firstOrNull { it.batchId == selectedBatchIds.first() }
            "Permanently delete '${single?.displayTitle ?: "selected item"}'?"
        } else {
            "Permanently delete $count selected items?"
        }
        BauhausConfirmDialog(
            title = "Delete",
            message = delMsg,
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { showBatchDeleteDialog = false },
            onConfirm = {
                val ids = selectedBatchIds
                showBatchDeleteDialog = false
                viewModel.deleteBatchesPermanently(ids) {
                    isMarkMode = false
                    selectedBatchIds = emptySet()
                }
            }
        )
    }

    if (confirmEmptyAll) {
        BauhausConfirmDialog(
            title = "Delete All",
            message = "Permanently delete all ${items.size} items in Recycle Bin?",
            confirmLabel = "Delete All",
            destructive = true,
            onDismiss = { confirmEmptyAll = false },
            onConfirm = {
                viewModel.emptyAll()
                confirmEmptyAll = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleBinItemCard(
    item: DeletedVaultItem,
    isMarkMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(BauhausDimens.CornerRadius)
    val borderColor = if (isMarkMode && isSelected) BauhausColors.Red else BauhausColors.Divider
    val borderWidth = if (isMarkMode && isSelected) 1.5.dp else 1.dp

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 82.dp)
                .clip(shape)
                .border(BorderStroke(borderWidth, borderColor), shape)
                .combinedClickable(
                    onClick = {
                        if (isMarkMode) {
                            onToggleSelect()
                        }
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isMarkMode) {
                            onToggleSelect()
                        } else {
                            menuOpen = true
                        }
                    }
                )
                .testTag("recycle_card_${item.batchId}"),
            color = BauhausColors.LightSurface,
            shape = shape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 82.dp)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.rootEntityType == "FOLDER") {
                    BauhausFolderIcon(accent = FolderAccentColor.YELLOW, size = 40.dp)
                } else {
                    BauhausNoteFileIcon(size = 40.dp)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = BauhausColors.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("recycle_title_${item.batchId}")
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = DateFormatters.formatDateTime(item.deletedAtMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = BauhausColors.SecondaryText
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = BauhausColors.SecondaryText
                        )
                        Text(
                            text = "Forever",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = BauhausColors.Blue,
                            modifier = Modifier.testTag("recycle_retention_${item.batchId}")
                        )
                    }
                }

                if (isMarkMode) {
                    Spacer(modifier = Modifier.width(12.dp))
                    BauhausMarkTickIndicator(
                        selected = isSelected,
                        modifier = Modifier.testTag("recycle_checkbox_${item.batchId}")
                    )
                }
            }
        }

        if (menuOpen) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuOpen = false },
                containerColor = BauhausColors.CardSurface,
                modifier = Modifier
                    .width(196.dp)
                    .testTag("recycle_item_menu_${item.batchId}")
            ) {
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Restore,
                            contentDescription = null,
                            tint = BauhausColors.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    text = { Text("Restore", style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        menuOpen = false
                        onRestore()
                    },
                    modifier = Modifier.testTag("recycle_menu_restore_${item.batchId}")
                )
                HorizontalDivider(color = BauhausColors.Divider)
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            tint = BauhausColors.Red,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    text = {
                        Text(
                            text = "Delete Forever",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BauhausColors.Red
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDeleteForever()
                    },
                    modifier = Modifier.testTag("recycle_menu_delete_forever_${item.batchId}")
                )
            }
        }
    }
}
