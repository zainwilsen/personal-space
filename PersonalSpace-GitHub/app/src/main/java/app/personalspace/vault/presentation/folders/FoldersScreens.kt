package app.personalspace.vault.presentation.folders

import android.content.Intent
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausConfirmDialog
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausErrorBanner
import app.personalspace.vault.core.designsystem.BauhausFolderIcon
import app.personalspace.vault.core.designsystem.BauhausNoteFileIcon
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSectionHeader
import app.personalspace.vault.core.designsystem.BauhausTextField
import app.personalspace.vault.core.designsystem.BottomNavTab
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.domain.model.Folder
import app.personalspace.vault.domain.model.RecordItem
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.records.RichNoteCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

fun buildFolderPathText(folderId: String?, allFolders: List<Folder>): String {
    if (folderId == null) return "File Manager (Unfiled)"
    val byId = allFolders.associateBy { it.id }
    val parts = ArrayList<String>()
    var cur = byId[folderId]
    val visited = HashSet<String>()
    while (cur != null && visited.add(cur.id)) {
        parts.add(DateFormatters.ellipsizeTitle(cur.name, 20))
        cur = cur.parentFolderId?.let { byId[it] }
    }
    parts.reverse()
    return if (parts.isEmpty()) "File Manager" else "File Manager / " + parts.joinToString(" / ")
}

fun buildRecordPathText(folderId: String?, recordTitle: String, allFolders: List<Folder>): String {
    return "${buildFolderPathText(folderId, allFolders)} / ${DateFormatters.ellipsizeTitle(recordTitle, 20)}"
}

private fun excludeSubtrees(allFolders: List<Folder>, excludedRootIds: Set<String>): List<Folder> {
    if (excludedRootIds.isEmpty()) return allFolders
    val byParent = allFolders.groupBy { it.parentFolderId }
    val blocked = HashSet<String>(excludedRootIds)
    val queue = ArrayDeque<String>(excludedRootIds)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        byParent[current]?.forEach { child ->
            if (blocked.add(child.id)) {
                queue.addLast(child.id)
            }
        }
    }
    return allFolders.filter { it.id !in blocked }
}

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val repository: PersonalSpaceRepository
) : ViewModel() {

    val allFolders: StateFlow<List<Folder>> = repository.observeAllFolders()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.latestCachedFolders() ?: emptyList()
        )

    val allRecords: StateFlow<List<RecordItem>> = repository.observeAllRecords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.latestCachedRecords() ?: emptyList()
        )

    private val _breadcrumbs = MutableStateFlow<List<Folder>>(emptyList())
    val breadcrumbs: StateFlow<List<Folder>> = _breadcrumbs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun loadBreadcrumbs(folderId: String?) {
        viewModelScope.launch {
            _breadcrumbs.value = repository.getFolderBreadcrumbs(folderId)
        }
    }

    fun createFolder(name: String, parentFolderId: String?, color: FolderAccentColor) {
        viewModelScope.launch {
            runCatching {
                repository.createFolder(name, parentFolderId, color)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun renameFolder(folderId: String, newName: String, color: FolderAccentColor) {
        viewModelScope.launch {
            runCatching {
                repository.renameFolder(folderId, newName, color)
                loadBreadcrumbs(folderId)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun moveFolder(folderId: String, newParentId: String?) {
        viewModelScope.launch {
            runCatching {
                repository.moveFolder(folderId, newParentId)
                loadBreadcrumbs(folderId)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun moveFoldersBatch(folderIds: Set<String>, newParentId: String?, onDone: (() -> Unit)? = null) {
        if (folderIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                folderIds.forEach { fid ->
                    if (fid != newParentId) {
                        repository.moveFolder(fid, newParentId)
                    }
                }
            }.onSuccess {
                onDone?.invoke()
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteFolder(folderId: String, onDeleted: (() -> Unit)? = null) {
        viewModelScope.launch {
            runCatching {
                repository.softDeleteFolder(folderId)
                onDeleted?.invoke()
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteFoldersBatch(folderIds: Set<String>, onDone: (() -> Unit)? = null) {
        if (folderIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val byId = allFolders.value.associateBy { it.id }
                val topSelected = folderIds.filter { id ->
                    var parentId = byId[id]?.parentFolderId
                    var ancestorAlsoSelected = false
                    val guard = HashSet<String>()
                    while (parentId != null && guard.add(parentId)) {
                        if (parentId in folderIds) {
                            ancestorAlsoSelected = true
                            break
                        }
                        parentId = byId[parentId]?.parentFolderId
                    }
                    !ancestorAlsoSelected
                }
                topSelected.forEach { id ->
                    repository.softDeleteFolder(id)
                }
            }.onSuccess {
                onDone?.invoke()
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun renameRecordTitle(recordId: String, newTitle: String) {
        viewModelScope.launch {
            runCatching {
                repository.renameRecordTitle(recordId, newTitle)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun moveRecord(recordId: String, targetFolderId: String?) {
        viewModelScope.launch {
            runCatching {
                repository.moveRecord(recordId, targetFolderId)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun moveRecordsBatch(recordIds: Set<String>, targetFolderId: String?, onDone: (() -> Unit)? = null) {
        if (recordIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                recordIds.forEach { rid ->
                    repository.moveRecord(rid, targetFolderId)
                }
            }.onSuccess {
                onDone?.invoke()
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun duplicateRecord(recordId: String, onDuplicated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.duplicateRecord(recordId)
            }.onSuccess {
                onDuplicated(it.id)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            runCatching {
                repository.softDeleteRecord(recordId)
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteRecordsBatch(recordIds: Set<String>, onDone: (() -> Unit)? = null) {
        if (recordIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                recordIds.forEach { rid ->
                    repository.softDeleteRecord(rid)
                }
            }.onSuccess {
                onDone?.invoke()
            }.onFailure { _errorMessage.value = it.message }
        }
    }
}

@Composable
fun FoldersScreen(
    viewModel: FoldersViewModel,
    onSelectBottomTab: (BottomNavTab) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenAllRecords: () -> Unit
) {
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf(SortOption.TITLE_ASC) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    var isMarkMode by rememberSaveable { mutableStateOf(false) }
    var selectedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    var folderToEdit by remember { mutableStateOf<Folder?>(null) }
    var folderToMove by remember { mutableStateOf<Folder?>(null) }
    var folderForDetails by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    val displayedFolders = remember(allFolders, searchQuery, sortBy) {
        val base = if (searchQuery.isBlank()) {
            allFolders.filter { it.parentFolderId == null }
        } else {
            allFolders.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
        sortFolders(base, sortBy)
    }

    BackHandler(enabled = isMarkMode) {
        isMarkMode = false
        selectedFolderIds = emptySet()
    }

    fun toggleFolderSelection(id: String) {
        selectedFolderIds = if (id in selectedFolderIds) {
            selectedFolderIds - id
        } else {
            selectedFolderIds + id
        }
    }

    BauhausScreenScaffold(
        title = if (isMarkMode) "${selectedFolderIds.size} Selected" else "File Manager",
        titleStyle = if (isMarkMode) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
        subtitle = null,
        onBack = if (isMarkMode) {
            {
                isMarkMode = false
                selectedFolderIds = emptySet()
            }
        } else null,
        activeTab = BottomNavTab.FOLDERS,
        onSelectTab = onSelectBottomTab,
        actions = {
            if (isMarkMode) {
                IconButton(
                    onClick = {
                        val allIds = displayedFolders.map { it.id }.toSet()
                        selectedFolderIds = if (selectedFolderIds.containsAll(allIds) && allIds.isNotEmpty()) {
                            emptySet()
                        } else {
                            allIds
                        }
                    },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_mark_select_all_folders")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DoneAll,
                        contentDescription = "Select all folders",
                        tint = BauhausColors.Blue
                    )
                }
                IconButton(
                    onClick = { if (selectedFolderIds.isNotEmpty()) showBatchMoveDialog = true },
                    enabled = selectedFolderIds.isNotEmpty(),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_mark_move_folders")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                        contentDescription = "Move selected folders",
                        tint = if (selectedFolderIds.isNotEmpty()) BauhausColors.Black else BauhausColors.Divider
                    )
                }
                IconButton(
                    onClick = { if (selectedFolderIds.isNotEmpty()) showBatchDeleteDialog = true },
                    enabled = selectedFolderIds.isNotEmpty(),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_mark_delete_folders")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete selected folders",
                        tint = if (selectedFolderIds.isNotEmpty()) BauhausColors.Red else BauhausColors.Divider
                    )
                }
            } else {
                Box {
                    IconButton(
                        onClick = { showHeaderMenu = true },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("file_manager_menu_button")
                    ) {
                        Box {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = "File Manager menu",
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
                                .testTag("file_manager_dropdown_menu")
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
                                modifier = Modifier.testTag("menu_option_search_folders")
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
                                    selectedFolderIds = emptySet()
                                    isMarkMode = true
                                },
                                modifier = Modifier.testTag("menu_option_mark_folders")
                            )

                            HorizontalDivider(color = BauhausColors.Divider)

                            Text(
                                text = "Sort By",
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                            )

                            listOf(
                                SortOption.TITLE_ASC,
                                SortOption.TITLE_DESC,
                                SortOption.RECENTLY_MODIFIED,
                                SortOption.RECENTLY_CREATED
                            ).forEach { opt ->
                                val active = sortBy == opt
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = opt.label,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            color = BauhausColors.Black
                                        )
                                    },
                                    trailingIcon = if (active) {
                                        {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(BauhausColors.Red)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        sortBy = opt
                                        showHeaderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isMarkMode) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = BauhausColors.Red,
                    contentColor = BauhausColors.LightSurface,
                    modifier = Modifier.testTag("fab_create_folder")
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "Create folder")
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
                        placeholder = "Search folders",
                        onClose = {
                            searchQuery = ""
                            focusManager.clearFocus()
                            isSearchVisible = false
                        },
                        onDone = { focusManager.clearFocus() },
                        testTag = "input_search_folders"
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("folders_list"),
                contentPadding = PaddingValues(
                    start = BauhausDimens.PageHorizontalPadding,
                    end = BauhausDimens.PageHorizontalPadding,
                    top = 8.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                errorMsg?.let { msg ->
                    item {
                        BauhausErrorBanner(message = msg, onDismiss = viewModel::clearError)
                    }
                }

                if (displayedFolders.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(0.68f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "No Folders" else "No Matching Folders",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                } else {
                    items(displayedFolders, key = { it.id }) { folder ->
                        FolderListRowCard(
                            folder = folder,
                            isMarkMode = isMarkMode,
                            isSelected = folder.id in selectedFolderIds,
                            onToggleSelect = { toggleFolderSelection(folder.id) },
                            onOpen = { onOpenFolder(folder.id) },
                            onRename = { folderToEdit = folder },
                            onMove = { folderToMove = folder },
                            onDetails = { folderForDetails = folder },
                            onDelete = { folderToDelete = folder }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        FolderEditorDialog(
            title = "Create Folder",
            initialName = "",
            initialColor = FolderAccentColor.YELLOW,
            confirmLabel = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, color ->
                viewModel.createFolder(name, parentFolderId = null, color = color)
                showCreateDialog = false
            }
        )
    }

    folderToEdit?.let { editing ->
        FolderEditorDialog(
            title = "Rename Folder",
            initialName = editing.name,
            initialColor = editing.color,
            confirmLabel = "Save",
            onDismiss = { folderToEdit = null },
            onConfirm = { name, color ->
                viewModel.renameFolder(editing.id, name, color)
                folderToEdit = null
            }
        )
    }

    folderToMove?.let { moving ->
        MoveDestinationFolderDialog(
            title = "Move '${moving.name}'",
            allFolders = excludeSubtrees(allFolders, setOf(moving.id)),
            onDismiss = { folderToMove = null },
            onSelectDestination = { newParentId ->
                viewModel.moveFolder(moving.id, newParentId)
                folderToMove = null
            }
        )
    }

    if (showBatchMoveDialog && selectedFolderIds.isNotEmpty()) {
        MoveDestinationFolderDialog(
            title = "Move ${selectedFolderIds.size} Selected",
            allFolders = excludeSubtrees(allFolders, selectedFolderIds),
            onDismiss = { showBatchMoveDialog = false },
            onSelectDestination = { newParentId ->
                val toMove = selectedFolderIds
                showBatchMoveDialog = false
                viewModel.moveFoldersBatch(toMove, newParentId) {
                    isMarkMode = false
                    selectedFolderIds = emptySet()
                }
            }
        )
    }

    if (showBatchDeleteDialog && selectedFolderIds.isNotEmpty()) {
        val singleSelected = if (selectedFolderIds.size == 1) {
            allFolders.firstOrNull { it.id == selectedFolderIds.first() }
        } else null
        val delMessage = if (singleSelected != null) {
            "Delete folder '${singleSelected.name}'?"
        } else {
            "Delete ${selectedFolderIds.size} selected folders?"
        }
        BauhausConfirmDialog(
            title = "Delete",
            message = delMessage,
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { showBatchDeleteDialog = false },
            onConfirm = {
                val toDelete = selectedFolderIds
                showBatchDeleteDialog = false
                viewModel.deleteFoldersBatch(toDelete) {
                    isMarkMode = false
                    selectedFolderIds = emptySet()
                }
            }
        )
    }

    folderForDetails?.let { detailFolder ->
        FolderDetailsInfoDialog(
            folder = detailFolder,
            allFolders = allFolders,
            onDismiss = { folderForDetails = null }
        )
    }

    folderToDelete?.let { deleting ->
        BauhausConfirmDialog(
            title = "Delete",
            message = "Delete folder '${deleting.name}'?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { folderToDelete = null },
            onConfirm = {
                viewModel.deleteFolder(deleting.id)
                folderToDelete = null
            }
        )
    }
}

@Composable
fun FolderDetailsScreen(
    folderId: String,
    viewModel: FoldersViewModel,
    onBack: () -> Unit,
    onOpenSubfolder: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    onCreateRecordInFolder: (String) -> Unit = {},
    onEditRecord: (String) -> Unit = {},
    onOpenVersionHistory: (String) -> Unit = {},
    onAddLinkedReminder: (String) -> Unit = {},
    onOpenDuplicatedRecord: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    val allRecords by viewModel.allRecords.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(folderId, allFolders) {
        viewModel.loadBreadcrumbs(folderId)
    }

    val currentFolder = allFolders.firstOrNull { it.id == folderId }
    val currentFolderPath = remember(folderId, allFolders) {
        buildFolderPathText(folderId, allFolders)
    }

    var searchInFolder by rememberSaveable { mutableStateOf("") }
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf(SortOption.RECENTLY_MODIFIED) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showTotalCountsDialog by remember { mutableStateOf(false) }
    var showFolderPathDialog by remember { mutableStateOf(false) }
    var showCreateSubfolderDialog by remember { mutableStateOf(false) }

    var isMarkMode by rememberSaveable { mutableStateOf(false) }
    var selectedSubfolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchMoveInFolderDialog by remember { mutableStateOf(false) }
    var showBatchDeleteInFolderDialog by remember { mutableStateOf(false) }

    var subfolderToEdit by remember { mutableStateOf<Folder?>(null) }
    var subfolderToMove by remember { mutableStateOf<Folder?>(null) }
    var subfolderForDetails by remember { mutableStateOf<Folder?>(null) }
    var subfolderToDelete by remember { mutableStateOf<Folder?>(null) }

    var recordToRenameTitle by remember { mutableStateOf<RecordItem?>(null) }
    var recordToMove by remember { mutableStateOf<RecordItem?>(null) }
    var recordToDelete by remember { mutableStateOf<RecordItem?>(null) }
    var recordForPathInfo by remember { mutableStateOf<RecordItem?>(null) }

    val totalSubfolderCount = remember(allFolders, folderId) {
        allFolders.count { it.parentFolderId == folderId }
    }
    val totalNoteCount = remember(allRecords, folderId) {
        allRecords.count { it.folderId == folderId }
    }

    val subfolders = remember(allFolders, folderId, searchInFolder, sortBy) {
        val raw = allFolders.filter {
            it.parentFolderId == folderId &&
                (searchInFolder.isBlank() || it.name.contains(searchInFolder.trim(), ignoreCase = true))
        }
        sortFolders(raw, sortBy)
    }

    val recordsInFolder = remember(allRecords, folderId, searchInFolder, sortBy) {
        val raw = allRecords.filter {
            it.folderId == folderId &&
                (searchInFolder.isBlank() ||
                    it.title.contains(searchInFolder.trim(), ignoreCase = true) ||
                    RichNoteCodec.extractPlainText(it.description).contains(searchInFolder.trim(), ignoreCase = true))
        }
        when (sortBy) {
            SortOption.TITLE_ASC -> raw.sortedBy { it.title.lowercase() }
            SortOption.TITLE_DESC -> raw.sortedByDescending { it.title.lowercase() }
            SortOption.RECENTLY_CREATED -> raw.sortedByDescending { it.createdAt }
            else -> raw.sortedByDescending { it.modifiedAt }
        }
    }

    val totalMarkedInFolder = selectedSubfolderIds.size + selectedNoteIds.size

    BackHandler(enabled = isMarkMode) {
        isMarkMode = false
        selectedSubfolderIds = emptySet()
        selectedNoteIds = emptySet()
    }

    BauhausScreenScaffold(
        title = if (isMarkMode) "$totalMarkedInFolder Selected" else (currentFolder?.name ?: "Folder"),
        titleStyle = MaterialTheme.typography.headlineMedium,
        subtitle = null,
        onBack = if (isMarkMode) {
            {
                isMarkMode = false
                selectedSubfolderIds = emptySet()
                selectedNoteIds = emptySet()
            }
        } else onBack,
        actions = {
            if (isMarkMode) {
                IconButton(
                    onClick = {
                        val allSubIds = subfolders.map { it.id }.toSet()
                        val allRecIds = recordsInFolder.map { it.id }.toSet()
                        val allChecked = selectedSubfolderIds.containsAll(allSubIds) &&
                            selectedNoteIds.containsAll(allRecIds) &&
                            (allSubIds.isNotEmpty() || allRecIds.isNotEmpty())
                        if (allChecked) {
                            selectedSubfolderIds = emptySet()
                            selectedNoteIds = emptySet()
                        } else {
                            selectedSubfolderIds = allSubIds
                            selectedNoteIds = allRecIds
                        }
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(Icons.Outlined.DoneAll, contentDescription = "Select all", tint = BauhausColors.Blue)
                }
                IconButton(
                    onClick = { if (totalMarkedInFolder > 0) showBatchMoveInFolderDialog = true },
                    enabled = totalMarkedInFolder > 0,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DriveFileMove,
                        contentDescription = "Move selected",
                        tint = if (totalMarkedInFolder > 0) BauhausColors.Black else BauhausColors.Divider
                    )
                }
                IconButton(
                    onClick = { if (totalMarkedInFolder > 0) showBatchDeleteInFolderDialog = true },
                    enabled = totalMarkedInFolder > 0,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete selected",
                        tint = if (totalMarkedInFolder > 0) BauhausColors.Red else BauhausColors.Divider
                    )
                }
            } else {
                Box {
                    IconButton(
                        onClick = { showHeaderMenu = true },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("folder_details_menu_button")
                    ) {
                        Box {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = "Folder menu",
                                tint = BauhausColors.Black
                            )
                            if (searchInFolder.isNotBlank()) {
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
                                .width(236.dp)
                                .testTag("folder_details_dropdown_menu")
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
                                modifier = Modifier.testTag("menu_option_search_in_folder")
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
                                    selectedSubfolderIds = emptySet()
                                    selectedNoteIds = emptySet()
                                    isMarkMode = true
                                },
                                modifier = Modifier.testTag("menu_option_mark_in_folder")
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = BauhausColors.Yellow
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Path",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    showFolderPathDialog = true
                                },
                                modifier = Modifier.testTag("menu_option_folder_path")
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = BauhausColors.Blue
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Total Folder Notes",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    showTotalCountsDialog = true
                                },
                                modifier = Modifier.testTag("menu_option_total_folder_record")
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = null,
                                        tint = BauhausColors.Red
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Add Note",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    onCreateRecordInFolder(folderId)
                                },
                                modifier = Modifier.testTag("menu_action_add_record")
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.CreateNewFolder,
                                        contentDescription = null,
                                        tint = BauhausColors.Blue
                                    )
                                },
                                text = {
                                    Text(
                                        text = "New Subfolder",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showHeaderMenu = false
                                    showCreateSubfolderDialog = true
                                },
                                modifier = Modifier.testTag("menu_action_new_subfolder")
                            )

                            HorizontalDivider(color = BauhausColors.Divider)

                            Text(
                                text = "Sort By",
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                            )

                            listOf(
                                SortOption.RECENTLY_MODIFIED,
                                SortOption.RECENTLY_CREATED,
                                SortOption.TITLE_ASC,
                                SortOption.TITLE_DESC
                            ).forEach { opt ->
                                val active = sortBy == opt
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = opt.label,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            color = BauhausColors.Black
                                        )
                                    },
                                    trailingIcon = if (active) {
                                        {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(BauhausColors.Red)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        sortBy = opt
                                        showHeaderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isMarkMode) {
                FloatingActionButton(
                    onClick = { onCreateRecordInFolder(folderId) },
                    containerColor = BauhausColors.Red,
                    contentColor = BauhausColors.LightSurface,
                    modifier = Modifier.testTag("fab_create_record_in_folder")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Create note in folder")
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
                        value = searchInFolder,
                        onValueChange = { searchInFolder = it },
                        placeholder = "Search in ${currentFolder?.name ?: "folder"}",
                        onClose = {
                            searchInFolder = ""
                            focusManager.clearFocus()
                            isSearchVisible = false
                        },
                        onDone = { focusManager.clearFocus() },
                        testTag = "input_search_inside_folder"
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BauhausDimens.PageHorizontalPadding,
                    end = BauhausDimens.PageHorizontalPadding,
                    top = 8.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                errorMsg?.let { msg ->
                    item { BauhausErrorBanner(message = msg, onDismiss = viewModel::clearError) }
                }

                if (subfolders.isNotEmpty()) {
                    item {
                        BauhausSectionHeader(title = "Subfolders")
                    }
                    items(subfolders, key = { "sub_${it.id}" }) { sub ->
                        FolderListRowCard(
                            folder = sub,
                            isMarkMode = isMarkMode,
                            isSelected = sub.id in selectedSubfolderIds,
                            onToggleSelect = {
                                selectedSubfolderIds = if (sub.id in selectedSubfolderIds) {
                                    selectedSubfolderIds - sub.id
                                } else {
                                    selectedSubfolderIds + sub.id
                                }
                            },
                            onOpen = { onOpenSubfolder(sub.id) },
                            onRename = { subfolderToEdit = sub },
                            onMove = { subfolderToMove = sub },
                            onDetails = { subfolderForDetails = sub },
                            onDelete = { subfolderToDelete = sub }
                        )
                    }
                }

                item {
                    BauhausSectionHeader(title = "Notes")
                }

                if (recordsInFolder.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(if (subfolders.isEmpty()) 0.62f else 0.36f)
                                .testTag("folder_empty_no_records"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No Notes",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                } else {
                    items(recordsInFolder, key = { "frec_${it.id}" }) { rec ->
                        RecordRowWithSmartMenuCard(
                            record = rec,
                            isMarkMode = isMarkMode,
                            isSelected = rec.id in selectedNoteIds,
                            onToggleSelect = {
                                selectedNoteIds = if (rec.id in selectedNoteIds) {
                                    selectedNoteIds - rec.id
                                } else {
                                    selectedNoteIds + rec.id
                                }
                            },
                            onOpenRecord = { onOpenRecord(rec.id) },
                            onEditTitle = { recordToRenameTitle = rec },
                            onShowPath = { recordForPathInfo = rec },
                            onVersionHistory = { onOpenVersionHistory(rec.id) },
                            onMove = { recordToMove = rec },
                            onDuplicate = { viewModel.duplicateRecord(rec.id, onOpenDuplicatedRecord) },
                            onShare = {
                                val cleanBody = RichNoteCodec.extractPlainText(rec.description)
                                val shareText = if (cleanBody.isBlank()) rec.title else "${rec.title}\n\n$cleanBody"
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, rec.title)
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Note"))
                            },
                            onDelete = { recordToDelete = rec }
                        )
                    }
                }
            }
        }
    }

    recordToRenameTitle?.let { renaming ->
        RecordTitleDialog(
            title = "Rename Note",
            initialRecordTitle = renaming.title,
            confirmLabel = "Save",
            onDismiss = { recordToRenameTitle = null },
            onConfirm = { newTitle ->
                viewModel.renameRecordTitle(renaming.id, newTitle)
                recordToRenameTitle = null
            }
        )
    }

    if (showFolderPathDialog) {
        AlertDialog(
            onDismissRequest = { showFolderPathDialog = false },
            containerColor = BauhausColors.CardSurface,
            title = {
                Text(
                    text = "Path",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = currentFolderPath,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = BauhausColors.Black,
                    modifier = Modifier.testTag("dialog_folder_path")
                )
            },
            confirmButton = {
                TextButton(onClick = { showFolderPathDialog = false }) {
                    Text("Close", color = BauhausColors.Black, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    recordForPathInfo?.let { rec ->
        val recPath = remember(rec.folderId, allFolders, rec.title) {
            buildRecordPathText(rec.folderId, rec.title, allFolders)
        }
        AlertDialog(
            onDismissRequest = { recordForPathInfo = null },
            containerColor = BauhausColors.CardSurface,
            title = {
                Text(
                    text = "Path",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = recPath,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = BauhausColors.Black,
                    modifier = Modifier.testTag("dialog_record_path_info")
                )
            },
            confirmButton = {
                TextButton(onClick = { recordForPathInfo = null }) {
                    Text("Close", color = BauhausColors.Black, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showTotalCountsDialog) {
        AlertDialog(
            onDismissRequest = { showTotalCountsDialog = false },
            containerColor = BauhausColors.CardSurface,
            title = {
                Text(
                    text = "Total Folder Notes",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("dialog_total_folder_record"),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = totalSubfolderCount.toString(),
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            color = BauhausColors.Blue
                        )
                        Text(
                            text = "Subfolders",
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = totalNoteCount.toString(),
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            color = BauhausColors.Red
                        )
                        Text(
                            text = "Notes",
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTotalCountsDialog = false }) {
                    Text("Close", color = BauhausColors.Black, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showCreateSubfolderDialog) {
        FolderEditorDialog(
            title = "Create Subfolder",
            initialName = "",
            initialColor = FolderAccentColor.BLUE,
            confirmLabel = "Create",
            onDismiss = { showCreateSubfolderDialog = false },
            onConfirm = { name, color ->
                viewModel.createFolder(name, parentFolderId = folderId, color = color)
                showCreateSubfolderDialog = false
            }
        )
    }

    subfolderToEdit?.let { editing ->
        FolderEditorDialog(
            title = "Rename Subfolder",
            initialName = editing.name,
            initialColor = editing.color,
            confirmLabel = "Save",
            onDismiss = { subfolderToEdit = null },
            onConfirm = { name, color ->
                viewModel.renameFolder(editing.id, name, color)
                subfolderToEdit = null
            }
        )
    }

    subfolderToMove?.let { moving ->
        MoveDestinationFolderDialog(
            title = "Move '${moving.name}'",
            allFolders = excludeSubtrees(allFolders, setOf(moving.id)),
            onDismiss = { subfolderToMove = null },
            onSelectDestination = { newParentId ->
                viewModel.moveFolder(moving.id, newParentId)
                subfolderToMove = null
            }
        )
    }

    subfolderForDetails?.let { detailSub ->
        FolderDetailsInfoDialog(
            folder = detailSub,
            allFolders = allFolders,
            onDismiss = { subfolderForDetails = null }
        )
    }

    subfolderToDelete?.let { deleting ->
        BauhausConfirmDialog(
            title = "Delete",
            message = "Delete folder '${deleting.name}'?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { subfolderToDelete = null },
            onConfirm = {
                viewModel.deleteFolder(deleting.id)
                subfolderToDelete = null
            }
        )
    }

    recordToMove?.let { rec ->
        MoveDestinationFolderDialog(
            title = "Move '${DateFormatters.ellipsizeTitle(rec.title, 20)}'",
            allFolders = allFolders,
            onDismiss = { recordToMove = null },
            onSelectDestination = { destFolderId ->
                viewModel.moveRecord(rec.id, destFolderId)
                recordToMove = null
            }
        )
    }

    recordToDelete?.let { deletingRec ->
        BauhausConfirmDialog(
            title = "Delete",
            message = "Delete note '${DateFormatters.ellipsizeTitle(deletingRec.title, 22)}'?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { recordToDelete = null },
            onConfirm = {
                viewModel.deleteRecord(deletingRec.id)
                recordToDelete = null
            }
        )
    }

    if (showBatchMoveInFolderDialog && totalMarkedInFolder > 0) {
        MoveDestinationFolderDialog(
            title = "Move $totalMarkedInFolder Selected",
            allFolders = excludeSubtrees(allFolders, selectedSubfolderIds),
            onDismiss = { showBatchMoveInFolderDialog = false },
            onSelectDestination = { destId ->
                val subs = selectedSubfolderIds
                val notes = selectedNoteIds
                showBatchMoveInFolderDialog = false
                viewModel.moveFoldersBatch(subs, destId)
                viewModel.moveRecordsBatch(notes, destId) {
                    isMarkMode = false
                    selectedSubfolderIds = emptySet()
                    selectedNoteIds = emptySet()
                }
                if (notes.isEmpty()) {
                    isMarkMode = false
                    selectedSubfolderIds = emptySet()
                }
            }
        )
    }

    if (showBatchDeleteInFolderDialog && totalMarkedInFolder > 0) {
        val msg = when {
            selectedSubfolderIds.size == 1 && selectedNoteIds.isEmpty() -> {
                val sub = allFolders.firstOrNull { it.id == selectedSubfolderIds.first() }
                "Delete folder '${sub?.name ?: "selected"}'?"
            }
            selectedNoteIds.size == 1 && selectedSubfolderIds.isEmpty() -> {
                val rec = allRecords.firstOrNull { it.id == selectedNoteIds.first() }
                "Delete note '${rec?.title ?: "selected"}'?"
            }
            else -> "Delete $totalMarkedInFolder selected items?"
        }
        BauhausConfirmDialog(
            title = "Delete",
            message = msg,
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { showBatchDeleteInFolderDialog = false },
            onConfirm = {
                val subs = selectedSubfolderIds
                val notes = selectedNoteIds
                showBatchDeleteInFolderDialog = false
                viewModel.deleteFoldersBatch(subs)
                viewModel.deleteRecordsBatch(notes)
                isMarkMode = false
                selectedSubfolderIds = emptySet()
                selectedNoteIds = emptySet()
            }
        )
    }
}

/**
 * Reusable Note card row matching FolderListRowCard visual polish:
 * - Left: Geometric Minimal Bauhaus File / Note icon (34.dp)
 * - Middle: Note Title + small 1-line preview snippet of the note's inner text
 * - Single tap opens the Note in Reading Mode (or toggles selection in Mark mode)
 * - Long press opens the compact Smart Menu (Edit Title, Path, Version History, Move, Duplicate, Add Reminder, Share, Delete)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordRowWithSmartMenuCard(
    record: RecordItem,
    onOpenRecord: () -> Unit,
    onEditTitle: () -> Unit,
    onShowPath: () -> Unit,
    onVersionHistory: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onAddReminder: (() -> Unit)? = null,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isMarkMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
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
                        if (isMarkMode) onToggleSelect() else onOpenRecord()
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
                .testTag("record_card_${record.id}"),
            color = BauhausColors.LightSurface,
            shape = shape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 82.dp)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BauhausNoteFileIcon(size = 40.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = BauhausColors.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isMarkMode) {
                    Spacer(modifier = Modifier.width(12.dp))
                    BauhausMarkTickIndicator(
                        selected = isSelected,
                        modifier = Modifier.testTag("record_checkbox_${record.id}")
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
                    .width(210.dp)
                    .testTag("record_smart_menu_${record.id}")
            ) {
                val itemPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                val itemHeight = 36.dp

                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Edit", style = MaterialTheme.typography.bodyMedium) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onEditTitle()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_edit_${record.id}")
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = BauhausColors.Yellow, modifier = Modifier.size(17.dp)) },
                    text = { Text("Path", style = MaterialTheme.typography.bodyMedium) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onShowPath()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_path_${record.id}")
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, tint = BauhausColors.Blue, modifier = Modifier.size(17.dp)) },
                    text = { Text("History", style = MaterialTheme.typography.bodyMedium) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onVersionHistory()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_history_${record.id}")
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Move to Folder", style = MaterialTheme.typography.bodyMedium) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onMove()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_move_${record.id}")
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Duplicate", style = MaterialTheme.typography.bodyMedium) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_duplicate_${record.id}")
                )
                onAddReminder?.let { addReminderAction ->
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(17.dp)) },
                        text = { Text("Add Reminder", style = MaterialTheme.typography.bodyMedium) },
                        contentPadding = itemPadding,
                        onClick = {
                            menuOpen = false
                            addReminderAction()
                        },
                        modifier = Modifier
                            .height(itemHeight)
                            .testTag("record_menu_reminder_${record.id}")
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(17.dp)) },
                    text = { Text("Share Text", style = MaterialTheme.typography.bodyMedium) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onShare()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_share_${record.id}")
                )
                HorizontalDivider(color = BauhausColors.Divider)
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = BauhausColors.Red, modifier = Modifier.size(17.dp)) },
                    text = { Text("Delete", style = MaterialTheme.typography.bodyMedium, color = BauhausColors.Red) },
                    contentPadding = itemPadding,
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    modifier = Modifier
                        .height(itemHeight)
                        .testTag("record_menu_delete_${record.id}")
                )
            }
        }
    }
}

@Composable
fun BauhausMarkTickIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) BauhausColors.Red else BauhausColors.Background)
            .border(
                width = 1.5.dp,
                color = if (selected) BauhausColors.Red else BauhausColors.SecondaryText,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = BauhausColors.LightSurface,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
fun CollapsibleInlineSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
    onDone: () -> Unit,
    testTag: String
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = BauhausColors.SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = BauhausColors.Black,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close search",
                    tint = BauhausColors.Black,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BauhausColors.Black,
            unfocusedBorderColor = BauhausColors.Divider,
            focusedContainerColor = BauhausColors.LightSurface,
            unfocusedContainerColor = BauhausColors.LightSurface,
            cursorColor = BauhausColors.Red
        ),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = BauhausColors.Black),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag(testTag)
    )
}

/**
 * Folder row card:
 * - No right-side 3-dots icon; long-press opens the folder menu (Rename / Color, Move Folder, Details, Delete)
 * - In Mark mode, shows crisp selection tick indicator so user can bulk Move or Delete folders
 * - Subtitle displays "X notes" (no "(s)") and never exposes create date on the normal screen
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListRowCard(
    folder: Folder,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    isMarkMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
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
                .clip(shape)
                .border(BorderStroke(borderWidth, borderColor), shape)
                .combinedClickable(
                    onClick = {
                        if (isMarkMode) onToggleSelect() else onOpen()
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
                .testTag("folder_card_${folder.id}"),
            color = BauhausColors.LightSurface,
            shape = shape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 82.dp)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BauhausFolderIcon(accent = folder.color, size = 40.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("${folder.recordCount} notes")
                            if (folder.subfolderCount > 0) {
                                append("  •  ${folder.subfolderCount} subfolders")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BauhausColors.SecondaryText
                    )
                }
                if (isMarkMode) {
                    Spacer(modifier = Modifier.width(12.dp))
                    BauhausMarkTickIndicator(
                        selected = isSelected,
                        modifier = Modifier.testTag("folder_checkbox_${folder.id}")
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
                    .width(206.dp)
                    .testTag("folder_context_menu_${folder.id}")
            ) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    text = { Text("Rename / Color") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                    modifier = Modifier.testTag("folder_menu_rename_${folder.id}")
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) },
                    text = { Text("Move Folder") },
                    onClick = {
                        menuOpen = false
                        onMove()
                    },
                    modifier = Modifier.testTag("folder_menu_move_${folder.id}")
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = BauhausColors.Blue) },
                    text = { Text("Details") },
                    onClick = {
                        menuOpen = false
                        onDetails()
                    },
                    modifier = Modifier.testTag("folder_menu_details_${folder.id}")
                )
                HorizontalDivider(color = BauhausColors.Divider)
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = BauhausColors.Red) },
                    text = { Text("Delete", color = BauhausColors.Red) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("folder_menu_delete_${folder.id}")
                )
            }
        }
    }
}

@Composable
fun FolderDetailsInfoDialog(
    folder: Folder,
    allFolders: List<Folder>,
    onDismiss: () -> Unit
) {
    val folderPath = remember(folder.id, allFolders) {
        buildFolderPathText(folder.id, allFolders)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BauhausColors.CardSurface,
        title = {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("dialog_folder_details")
            ) {
                Text(
                    text = "Created: ${DateFormatters.formatDateTime(folder.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BauhausColors.Black
                )
                Text(
                    text = "Modified: ${DateFormatters.formatDateTime(folder.modifiedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.SecondaryText
                )
                HorizontalDivider(color = BauhausColors.Divider)
                Text(
                    text = "Path: $folderPath",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BauhausColors.Black
                )
                Text(
                    text = "Notes: ${folder.recordCount} notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = BauhausColors.SecondaryText
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = BauhausColors.Black, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

/**
 * Clean Title-only popup dialog used when renaming a Note's title from its long-press menu.
 */
@Composable
fun RecordTitleDialog(
    title: String,
    initialRecordTitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var recordTitle by rememberSaveable { mutableStateOf(initialRecordTitle) }
    val dialogShape = RoundedCornerShape(22.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .border(BorderStroke(1.dp, BauhausColors.Divider), dialogShape)
                .testTag("dialog_record_title"),
            shape = dialogShape,
            color = BauhausColors.LightSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = BauhausColors.Black
                )

                BauhausTextField(
                    value = recordTitle,
                    onValueChange = { recordTitle = it },
                    label = "Title",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (recordTitle.isNotBlank()) {
                                onConfirm(recordTitle.trim())
                            }
                        }
                    ),
                    modifier = Modifier.testTag("dialog_record_title_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Cancel", color = BauhausColors.Black)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    BauhausPrimaryButton(
                        text = confirmLabel,
                        enabled = recordTitle.isNotBlank(),
                        onClick = { onConfirm(recordTitle.trim()) },
                        modifier = Modifier.testTag("dialog_record_title_confirm_btn")
                    )
                }
            }
        }
    }
}

@Composable
fun FolderEditorDialog(
    title: String,
    initialName: String,
    initialColor: FolderAccentColor,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, FolderAccentColor) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableStateOf(initialColor) }
    val dialogShape = RoundedCornerShape(22.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .border(BorderStroke(1.dp, BauhausColors.Divider), dialogShape)
                .testTag("dialog_folder_editor"),
            shape = dialogShape,
            color = BauhausColors.LightSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = BauhausColors.Black
                )
                BauhausTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Folder Name",
                    modifier = Modifier.testTag("dialog_folder_name_input")
                )
                Text("Folder Accent Color", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FolderAccentColor.entries.forEach { option ->
                        val selected = option == color
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(option.toComposeColor())
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) BauhausColors.Black else BauhausColors.Divider,
                                    shape = CircleShape
                                )
                                .clickable { color = option },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(BauhausColors.LightSurface)
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Cancel", color = BauhausColors.Black)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    BauhausPrimaryButton(
                        text = confirmLabel,
                        enabled = name.isNotBlank(),
                        onClick = { onConfirm(name.trim(), color) },
                        modifier = Modifier.testTag("dialog_folder_confirm_btn")
                    )
                }
            }
        }
    }
}

@Composable
fun MoveDestinationFolderDialog(
    title: String,
    allFolders: List<Folder>,
    onDismiss: () -> Unit,
    onSelectDestination: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BauhausColors.CardSurface,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Select destination:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { onSelectDestination(null) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Top-Level Root (No Parent)", style = MaterialTheme.typography.bodyLarge)
                }
                allFolders.take(25).forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { onSelectDestination(candidate.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BauhausFolderIcon(accent = candidate.color, size = 22.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = BauhausColors.Black) }
        }
    )
}

private fun sortFolders(folders: List<Folder>, sortBy: SortOption): List<Folder> =
    when (sortBy) {
        SortOption.TITLE_DESC -> folders.sortedByDescending { it.name.lowercase() }
        SortOption.RECENTLY_MODIFIED -> folders.sortedByDescending { it.modifiedAt }
        SortOption.RECENTLY_CREATED -> folders.sortedByDescending { it.createdAt }
        else -> folders.sortedBy { it.name.lowercase() }
    }
