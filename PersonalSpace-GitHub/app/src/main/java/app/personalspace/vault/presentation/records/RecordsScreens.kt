package app.personalspace.vault.presentation.records

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import java.time.Instant
import java.time.ZoneId
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausConfirmDialog
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausErrorBanner
import app.personalspace.vault.core.designsystem.BauhausOutlinedButton
import app.personalspace.vault.core.designsystem.BauhausPrimaryButton
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.designsystem.BauhausTagChip
import app.personalspace.vault.data.attachment.EncryptedAttachmentStorage
import app.personalspace.vault.data.backup.InlineImageStore
import app.personalspace.vault.domain.model.AttachmentItem
import app.personalspace.vault.domain.model.AttachmentKind
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.Folder
import app.personalspace.vault.domain.model.RecordDraft
import app.personalspace.vault.domain.model.RecordItem
import app.personalspace.vault.domain.model.RecurrenceType
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.RecordVersion
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.model.UserSettings
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.folders.MoveDestinationFolderDialog
import app.personalspace.vault.presentation.folders.RecordRowWithSmartMenuCard
import app.personalspace.vault.presentation.folders.RecordTitleDialog
import app.personalspace.vault.presentation.folders.BauhausMarkTickIndicator
import app.personalspace.vault.presentation.folders.CollapsibleInlineSearchBar
import app.personalspace.vault.presentation.folders.buildRecordPathText
import app.personalspace.vault.presentation.folders.buildFolderPathText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

private const val READING_PREFS_NAME = "ps_reading_ui"
private const val KEY_READING_LAYER_MODE = "reading_layer_mode"

private enum class ActiveColorDrawer {
    NONE,
    HIGHLIGHTER,
    INK_COLOR
}

@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val repository: PersonalSpaceRepository,
    private val attachmentStorage: EncryptedAttachmentStorage,
    val inlineImageStore: InlineImageStore
) : ViewModel() {

    val allRecords: StateFlow<List<RecordItem>> = repository.observeAllRecords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.latestCachedRecords() ?: emptyList()
        )

    val allFolders: StateFlow<List<Folder>> = repository.observeAllFolders()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.latestCachedFolders() ?: emptyList()
        )

    val categories: StateFlow<List<String>> = repository.observeDistinctCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<UserSettings> = repository.observeUserSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearFeedback() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun reportError(message: String) {
        _errorMessage.value = message
    }

    fun cachedRecord(recordId: String): RecordItem? = repository.latestCachedRecordById(recordId)
    fun observeRecord(recordId: String): Flow<RecordItem?> = repository.observeRecordById(recordId)
    fun observeAttachments(recordId: String): Flow<List<AttachmentItem>> =
        repository.observeAttachmentsForRecord(recordId)
    fun observeVersions(recordId: String): Flow<List<RecordVersion>> =
        repository.observeRecordVersions(recordId)

    fun createRecordTitleOnly(title: String, folderId: String? = null) {
        viewModelScope.launch {
            runCatching {
                repository.saveRecord(
                    existingRecordId = null,
                    title = title,
                    description = "",
                    folderId = folderId,
                    category = null,
                    customFields = emptyList()
                )
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

    suspend fun loadRecordForEdit(recordId: String): RecordItem? = repository.getRecordById(recordId)
    suspend fun loadDraft(draftKey: String): RecordDraft? = repository.getDraft(draftKey)
    suspend fun loadAttachment(attachmentId: String): AttachmentItem? =
        repository.getAttachmentById(attachmentId)
    suspend fun decryptAttachmentToTempFile(attachmentId: String): File =
        repository.prepareEphemeralViewerFile(attachmentId)

    fun getShareUri(file: File): Uri = attachmentStorage.getShareableUriForEphemeralFile(file)
    fun purgeEphemeralViewerFiles() = attachmentStorage.purgeAllEphemeralFiles()

    fun persistDraft(draft: RecordDraft) {
        viewModelScope.launch {
            runCatching { repository.saveDraft(draft) }
        }
    }

    fun discardDraft(draftKey: String) {
        viewModelScope.launch {
            runCatching { repository.clearDraft(draftKey) }
        }
    }

    fun saveRecord(
        existingRecordId: String?,
        title: String,
        description: String,
        folderId: String?,
        category: String?,
        customFields: List<CustomField>,
        pendingAttachmentUris: List<Uri>,
        coalesceVersion: Boolean = false,
        onSaved: (RecordItem) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                val saved = repository.saveRecord(
                    existingRecordId = existingRecordId,
                    title = title,
                    description = description,
                    folderId = folderId,
                    category = category,
                    customFields = customFields,
                    updateCurrentVersionInPlace = coalesceVersion
                )
                for (uri in pendingAttachmentUris) {
                    repository.importAttachmentFromUri(saved.id, uri)
                }
                saved
            }.onSuccess { saved ->
                onSaved(saved)
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "Could not save note."
            }
        }
    }

    fun scheduleNoteReminder(recordId: String, title: String, triggerAtMillis: Long) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveReminder(
                        existingReminderId = null,
                        title = title.ifBlank { "Note reminder" },
                        notes = "",
                        scheduledAtMillis = triggerAtMillis,
                        recurrence = RecurrenceType.ONCE,
                        priority = ReminderPriority.MEDIUM,
                        linkedRecordId = recordId
                    )
                }
            }.onFailure { reportError(it.message ?: "Reminder set nahi ho paya.") }
        }
    }

    /**
     * P6: removes the image block from the note being edited and asks the repository to delete the
     * file only if nothing else (another note, a recycle-bin note, an unsaved draft) still uses it.
     * Silently unlinking here used to break every other note that shared the image identifier, e.g.
     * the copy produced by duplicateRecord().
     */
    fun deleteInlineImage(imagePath: String, editingRecordId: String?) {
        viewModelScope.launch {
            runCatching { repository.deleteInlineImageIfUnreferenced(imagePath, editingRecordId) }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteAttachment(attachmentId: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            runCatching { repository.deleteAttachment(attachmentId) }
                .onSuccess { onDone?.invoke() }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun exportAttachmentToUri(context: Context, attachmentId: String, destUri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        repository.exportAttachmentToStream(attachmentId, out)
                    } ?: error("Cannot write to selected destination.")
                }
            }.onSuccess {
                _statusMessage.value = "Attachment exported successfully."
            }.onFailure {
                _errorMessage.value = it.message ?: "Failed to export attachment."
            }
        }
    }

    fun duplicateRecord(recordId: String, onDuplicated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.duplicateRecord(recordId) }
                .onSuccess { onDuplicated(it.id) }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun moveRecord(recordId: String, targetFolderId: String?) {
        viewModelScope.launch {
            runCatching { repository.moveRecord(recordId, targetFolderId) }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteRecordsToRecycleBinBatch(recordIds: Set<String>) {
        viewModelScope.launch {
            runCatching {
                recordIds.forEach { id -> repository.softDeleteRecord(id) }
            }.onFailure { _errorMessage.value = it.message }
        }
    }

    fun deleteRecordToRecycleBin(recordId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.softDeleteRecord(recordId) }
                .onSuccess { onDeleted() }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun restoreVersion(recordId: String, versionId: String, onRestored: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.restoreRecordVersion(recordId, versionId) }
                .onSuccess { onRestored() }
                .onFailure { _errorMessage.value = it.message }
        }
    }
}

@Composable
fun AllRecordsScreen(
    viewModel: RecordsViewModel,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onCreateRecord: () -> Unit,
    onEditRecord: (String) -> Unit = {},
    onOpenVersionHistory: (String) -> Unit = {},
    onAddLinkedReminder: (String) -> Unit = {},
    onOpenDuplicatedRecord: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val records by viewModel.allRecords.collectAsStateWithLifecycle()
    val allFolders by viewModel.allFolders.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var sortBy by rememberSaveable { mutableStateOf(SortOption.RECENTLY_MODIFIED) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isMarkMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    var recordToRenameTitle by remember { mutableStateOf<RecordItem?>(null) }
    var recordToMove by remember { mutableStateOf<RecordItem?>(null) }
    var recordToDelete by remember { mutableStateOf<RecordItem?>(null) }
    var recordForPathInfo by remember { mutableStateOf<RecordItem?>(null) }

    val displayed = remember(records, selectedCategory, sortBy, searchQuery) {
        val query = searchQuery.trim().lowercase()
        val filtered = records.filter { rec ->
            val categoryOk = selectedCategory == null || rec.category.equals(selectedCategory, ignoreCase = true)
            val queryOk = query.isEmpty() ||
                rec.title.lowercase().contains(query) ||
                RichNoteCodec.extractPlainText(rec.description).lowercase().contains(query)
            categoryOk && queryOk
        }
        when (sortBy) {
            SortOption.RECENTLY_CREATED -> filtered.sortedByDescending { it.createdAt }
            SortOption.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortOption.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered.sortedByDescending { it.modifiedAt }
        }
    }

    BauhausScreenScaffold(
        title = "All Notes",
        titleStyle = MaterialTheme.typography.headlineMedium,
        subtitle = null,
        onBack = onBack,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSearchVisible) {
                    IconButton(
                        onClick = {
                            searchQuery = ""
                            focusManager.clearFocus()
                            isSearchVisible = false
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_all_records_close_search")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close search",
                            tint = BauhausColors.Black
                        )
                    }
                }

                if (isMarkMode) {
                    IconButton(
                        onClick = {
                            val allIds = displayed.map { it.id }.toSet()
                            selectedNoteIds = if (selectedNoteIds.containsAll(allIds) && allIds.isNotEmpty()) emptySet() else allIds
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_all_records_select_all")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DoneAll,
                            contentDescription = "Select all notes",
                            tint = if (selectedNoteIds.isNotEmpty()) BauhausColors.Black else BauhausColors.Divider
                        )
                    }
                    IconButton(
                        onClick = { if (selectedNoteIds.isNotEmpty()) showBatchDeleteDialog = true },
                        enabled = selectedNoteIds.isNotEmpty(),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_all_records_mark_delete")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete selected notes",
                            tint = if (selectedNoteIds.isNotEmpty()) BauhausColors.Red else BauhausColors.Divider
                        )
                    }
                    IconButton(
                        onClick = {
                            isMarkMode = false
                            selectedNoteIds = emptySet()
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_all_records_exit_mark")
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
                                .testTag("btn_all_records_menu")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = "All Notes menu",
                                tint = BauhausColors.Black
                            )
                        }

                        if (showHeaderMenu) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { showHeaderMenu = false },
                                containerColor = BauhausColors.CardSurface,
                                modifier = Modifier
                                    .width(228.dp)
                                    .testTag("all_records_dropdown_menu")
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
                                    modifier = Modifier.testTag("menu_all_records_search")
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
                                        selectedNoteIds = emptySet()
                                        isMarkMode = true
                                    },
                                    modifier = Modifier.testTag("menu_all_records_mark")
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
            }
        },
        floatingActionButton = {
            if (!isMarkMode) {
                FloatingActionButton(
                    onClick = onCreateRecord,
                    containerColor = BauhausColors.Red,
                    contentColor = BauhausColors.LightSurface,
                    modifier = Modifier.testTag("fab_all_records_create")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Create note")
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
                        placeholder = "Search notes",
                        onClose = {
                            searchQuery = ""
                            focusManager.clearFocus()
                            isSearchVisible = false
                        },
                        onDone = { focusManager.clearFocus() },
                        testTag = "input_search_all_records"
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = BauhausDimens.PageHorizontalPadding,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (categories.isNotEmpty() && !isMarkMode) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BauhausTagChip(
                                label = "All",
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null }
                            )
                            categories.forEach { cat ->
                                BauhausTagChip(
                                    label = cat,
                                    selected = selectedCategory == cat,
                                    accentColor = BauhausColors.Blue,
                                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat }
                                )
                            }
                        }
                    }
                }

                if (displayed.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(0.68f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No Notes",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = BauhausColors.SecondaryText
                            )
                        }
                    }
                } else if (isMarkMode) {
                    items(displayed, key = { "mark_${it.id}" }) { rec ->
                        val selected = rec.id in selectedNoteIds
                        val shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 82.dp)
                                .clip(shape)
                                .border(
                                    width = if (selected) 1.6.dp else 1.dp,
                                    color = if (selected) BauhausColors.Red else BauhausColors.Divider,
                                    shape = shape
                                )
                                .clickable {
                                    selectedNoteIds = if (selected) {
                                        selectedNoteIds - rec.id
                                    } else {
                                        selectedNoteIds + rec.id
                                    }
                                }
                                .testTag("record_mark_card_${rec.id}"),
                            color = BauhausColors.LightSurface,
                            shape = shape
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = BauhausColors.Blue,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = rec.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = BauhausColors.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                BauhausMarkTickIndicator(selected = selected)
                            }
                        }
                    }
                } else {
                    items(displayed, key = { it.id }) { rec ->
                        RecordRowWithSmartMenuCard(
                            record = rec,
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

                item { Spacer(modifier = Modifier.height(64.dp)) }
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
                    style = MaterialTheme.typography.headlineMedium
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

    recordToMove?.let { rec ->
        MoveDestinationFolderDialog(
            title = "Move '${DateFormatters.ellipsizeTitle(rec.title, 20)}'",
            allFolders = allFolders,
            onDismiss = { recordToMove = null },
            onSelectDestination = { destId ->
                viewModel.moveRecord(rec.id, destId)
                recordToMove = null
            }
        )
    }

    recordToDelete?.let { deleting ->
        BauhausConfirmDialog(
            title = "Delete",
            message = "Delete note '${DateFormatters.ellipsizeTitle(deleting.title, 22)}'?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { recordToDelete = null },
            onConfirm = {
                viewModel.deleteRecordToRecycleBin(deleting.id) {}
                recordToDelete = null
            }
        )
    }

    if (showBatchDeleteDialog && selectedNoteIds.isNotEmpty()) {
        val count = selectedNoteIds.size
        BauhausConfirmDialog(
            title = "Delete",
            message = if (count == 1) {
                val only = records.firstOrNull { it.id == selectedNoteIds.first() }
                "Delete note '${DateFormatters.ellipsizeTitle(only?.title ?: "selected", 20)}'?"
            } else {
                "Delete $count selected notes?"
            },
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { showBatchDeleteDialog = false },
            onConfirm = {
                val ids = selectedNoteIds
                showBatchDeleteDialog = false
                selectedNoteIds = emptySet()
                isMarkMode = false
                viewModel.deleteRecordsToRecycleBinBatch(ids)
            }
        )
    }
}

@Composable
fun RecordDetailScreen(
    recordId: String,
    viewModel: RecordsViewModel,
    onBack: () -> Unit,
    onEditRecord: (String) -> Unit,
    onOpenVersionHistory: (String) -> Unit,
    onOpenAttachment: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onAddLinkedReminder: (String) -> Unit,
    onOpenDuplicatedRecord: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences(READING_PREFS_NAME, Context.MODE_PRIVATE)
    }

    val initialCached = remember(recordId) { viewModel.cachedRecord(recordId) }
    val record by viewModel.observeRecord(recordId).collectAsStateWithLifecycle(initialValue = initialCached)
    val attachments by viewModel.observeAttachments(recordId).collectAsStateWithLifecycle(initialValue = emptyList())
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()

    var showShareMenu by remember { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var showNoteReminderDialog by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }
    var useLayerMode by remember {
        mutableStateOf(prefs.getBoolean(KEY_READING_LAYER_MODE, true))
    }

    fun setLayerModePreference(layered: Boolean) {
        useLayerMode = layered
        prefs.edit().putBoolean(KEY_READING_LAYER_MODE, layered).apply()
    }

    val cur = record
    val readingBlocks = remember(cur?.description) {
        parseRichReadingBlocks(cur?.description.orEmpty())
    }

    BauhausScreenScaffold(
        title = "",
        subtitle = null,
        onBack = onBack,
        actions = {
            if (cur != null) {
                Box {
                    IconButton(
                        onClick = { showNoteReminderDialog = true },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_add_note_reminder")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = "Add reminder",
                            tint = BauhausColors.Black
                        )
                    }
                    if (showNoteReminderDialog) {
                        NoteReminderDialog(
                            onDismiss = { showNoteReminderDialog = false },
                            onConfirm = { triggerAtMillis ->
                                viewModel.scheduleNoteReminder(cur.id, cur.title, triggerAtMillis)
                                showNoteReminderDialog = false
                            }
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { showShareMenu = true },
                        enabled = !isExportingPdf,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_share_note")
                    ) {
                        if (isExportingPdf) {
                            CircularProgressIndicator(
                                color = BauhausColors.Red,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = "Share note",
                                tint = BauhausColors.Black
                            )
                        }
                    }

                    if (showShareMenu) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { showShareMenu = false },
                            containerColor = BauhausColors.CardSurface,
                            modifier = Modifier
                                .width(204.dp)
                                .testTag("share_note_dropdown")
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Subject,
                                        contentDescription = null,
                                        tint = BauhausColors.Blue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Share as Text",
                                        fontWeight = FontWeight.Medium,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showShareMenu = false
                                    val cleanPlain = RichNoteCodec.extractPlainText(cur.description)
                                    val body = if (cleanPlain.isBlank()) cur.title else "${cur.title}\n\n$cleanPlain"
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, cur.title)
                                        putExtra(Intent.EXTRA_TEXT, body)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Note Text"))
                                    }
                                },
                                modifier = Modifier.testTag("share_note_option_text")
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.PictureAsPdf,
                                        contentDescription = null,
                                        tint = BauhausColors.Red,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Share as PDF",
                                        fontWeight = FontWeight.Medium,
                                        color = BauhausColors.Black
                                    )
                                },
                                onClick = {
                                    showShareMenu = false
                                    isExportingPdf = true
                                    scope.launch {
                                        runCatching {
                                            val pdfFile = NotePdfExporter.exportNoteToPdfFile(
                                                context = context,
                                                title = cur.title,
                                                markdownBody = cur.description,
                                                resolveImageFile = { ref ->
                                                    viewModel.inlineImageStore.resolveForDisplay(ref)
                                                }
                                            )
                                            val uri = viewModel.getShareUri(pdfFile)
                                            val pdfIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/pdf"
                                                putExtra(Intent.EXTRA_SUBJECT, cur.title)
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                clipData = ClipData.newRawUri(cur.title, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(pdfIntent, "Share Note PDF")
                                            )
                                        }.onFailure { err ->
                                            viewModel.reportError(err.message ?: "Failed to generate PDF.")
                                        }
                                        isExportingPdf = false
                                    }
                                },
                                modifier = Modifier.testTag("share_note_option_pdf")
                            )
                        }
                    }
                }

                Box {
                    IconButton(
                        onClick = { showThemeMenu = true },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_reading_theme")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = "Reading theme",
                            tint = BauhausColors.Black
                        )
                    }

                    if (showThemeMenu) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { showThemeMenu = false },
                            containerColor = BauhausColors.CardSurface,
                            modifier = Modifier
                                .width(208.dp)
                                .testTag("reading_theme_dropdown")
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Layers,
                                        contentDescription = null,
                                        tint = BauhausColors.Blue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = {
                                    Text(
                                        text = "With Layer",
                                        fontWeight = if (useLayerMode) FontWeight.SemiBold else FontWeight.Normal,
                                        color = BauhausColors.Black
                                    )
                                },
                                trailingIcon = if (useLayerMode) {
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
                                    setLayerModePreference(true)
                                    showThemeMenu = false
                                },
                                modifier = Modifier.testTag("reading_theme_option_layer")
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.LayersClear,
                                        contentDescription = null,
                                        tint = BauhausColors.Yellow,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Without Layer",
                                        fontWeight = if (!useLayerMode) FontWeight.SemiBold else FontWeight.Normal,
                                        color = BauhausColors.Black
                                    )
                                },
                                trailingIcon = if (!useLayerMode) {
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
                                    setLayerModePreference(false)
                                    showThemeMenu = false
                                },
                                modifier = Modifier.testTag("reading_theme_option_no_layer")
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { onEditRecord(cur.id) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_edit_record")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit note",
                        tint = BauhausColors.Black
                    )
                }
            }
        }
    ) { innerPadding ->
        if (cur == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Note not found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BauhausColors.SecondaryText
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .testTag("record_reading_scroll"),
                contentPadding = PaddingValues(
                    start = BauhausDimens.PageHorizontalPadding,
                    end = BauhausDimens.PageHorizontalPadding,
                    top = 6.dp,
                    bottom = 56.dp
                ),
                verticalArrangement = Arrangement.Top
            ) {
                errorMsg?.let { msg ->
                    item(key = "reading_err") {
                        Box(modifier = Modifier.padding(bottom = 12.dp)) {
                            BauhausErrorBanner(message = msg, onDismiss = viewModel::clearFeedback)
                        }
                    }
                }

                item(key = "reading_title") {
                    Text(
                        text = cur.title,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = BauhausColors.Black,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reading_mode_title")
                    )
                }

                item(key = "reading_divider") {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = BauhausColors.Black.copy(alpha = 0.11f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 18.dp)
                            .testTag("reading_title_divider")
                    )
                }

                if (readingBlocks.isEmpty()) {
                    item(key = "reading_empty") {
                        if (useLayerMode) {
                            BauhausSurfaceCard(
                                elevated = true,
                                modifier = Modifier.testTag("reading_text_layer")
                            ) {
                                Text(
                                    text = "No Notes yet",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp
                                    ),
                                    color = BauhausColors.SecondaryText
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("reading_text_plain")
                            ) {
                                Text(
                                    text = "No Notes yet",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 16.sp,
                                        lineHeight = 26.sp
                                    ),
                                    color = BauhausColors.SecondaryText
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = readingBlocks,
                        key = { idx, _ -> "rblk_$idx" },
                        contentType = { _, block -> block::class }
                    ) { idx, block ->
                        val isFirst = idx == 0
                        val isLast = idx == readingBlocks.lastIndex
                        if (useLayerMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .readingCardLayerSlice(
                                        isFirst = isFirst,
                                        isLast = isLast,
                                        fillColor = BauhausColors.LightSurface,
                                        borderColor = BauhausColors.Divider.copy(alpha = 0.55f),
                                        cornerRadius = BauhausDimens.CornerRadius
                                    )
                                    .padding(
                                        start = 18.dp,
                                        end = 18.dp,
                                        top = if (isFirst) 18.dp else 4.dp,
                                        bottom = if (isLast) 18.dp else 4.dp
                                    )
                                    .then(
                                        if (isFirst) Modifier.testTag("reading_text_layer") else Modifier
                                    )
                            ) {
                                ReadingBlockRow(block = block, imageStore = viewModel.inlineImageStore)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = if (isFirst) 2.dp else 4.dp,
                                        bottom = if (isLast) 12.dp else 4.dp
                                    )
                                    .then(
                                        if (isFirst) Modifier.testTag("reading_text_plain") else Modifier
                                    )
                            ) {
                                ReadingBlockRow(block = block, imageStore = viewModel.inlineImageStore)
                            }
                        }
                    }
                }

                if (attachments.isNotEmpty()) {
                    item(key = "reading_att_spacer") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    items(attachments, key = { "att_${it.id}" }) { att ->
                        Box(modifier = Modifier.padding(bottom = 8.dp)) {
                            BauhausSurfaceCard(
                                elevated = true,
                                onClick = { onOpenAttachment(att.id) }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AttachFile,
                                            contentDescription = null,
                                            tint = BauhausColors.Blue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = att.originalFileName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = BauhausColors.Blue,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = DateFormatters.formatBytes(att.plaintextSizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BauhausColors.SecondaryText
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.readingCardLayerSlice(
    isFirst: Boolean,
    isLast: Boolean,
    fillColor: Color,
    borderColor: Color,
    cornerRadius: Dp
): Modifier {
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(cornerRadius)
        isFirst -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
        isLast -> RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius)
        else -> RectangleShape
    }
    return this
        .clip(shape)
        .background(fillColor, shape)
        .drawWithContent {
            drawContent()
            val strokePx = 0.85.dp.toPx()
            val half = strokePx / 2f
            val rPx = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)
            val w = size.width
            val h = size.height

            when {
                isFirst && isLast -> {
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(half, half),
                        size = Size((w - strokePx).coerceAtLeast(0f), (h - strokePx).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius((rPx - half).coerceAtLeast(0f)),
                        style = Stroke(width = strokePx)
                    )
                }
                isFirst -> {
                    val path = Path().apply {
                        moveTo(half, h)
                        lineTo(half, rPx)
                        arcTo(
                            rect = Rect(half, half, rPx * 2f - half, rPx * 2f - half),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false
                        )
                        lineTo(w - rPx, half)
                        arcTo(
                            rect = Rect(w - rPx * 2f + half, half, w - half, rPx * 2f - half),
                            startAngleDegrees = 270f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false
                        )
                        lineTo(w - half, h)
                    }
                    drawPath(path = path, color = borderColor, style = Stroke(width = strokePx))
                }
                isLast -> {
                    val path = Path().apply {
                        moveTo(half, 0f)
                        lineTo(half, h - rPx)
                        arcTo(
                            rect = Rect(half, h - rPx * 2f + half, rPx * 2f - half, h - half),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = -90f,
                            forceMoveTo = false
                        )
                        lineTo(w - rPx, h - half)
                        arcTo(
                            rect = Rect(w - rPx * 2f + half, h - rPx * 2f + half, w - half, h - half),
                            startAngleDegrees = 90f,
                            sweepAngleDegrees = -90f,
                            forceMoveTo = false
                        )
                        lineTo(w - half, 0f)
                    }
                    drawPath(path = path, color = borderColor, style = Stroke(width = strokePx))
                }
                else -> {
                    drawLine(
                        color = borderColor,
                        start = Offset(half, 0f),
                        end = Offset(half, h),
                        strokeWidth = strokePx
                    )
                    drawLine(
                        color = borderColor,
                        start = Offset(w - half, 0f),
                        end = Offset(w - half, h),
                        strokeWidth = strokePx
                    )
                }
            }
        }
}

private data class EditorHistorySnapshot(
    val title: String,
    val body: TextFieldValue,
    val spans: List<RichSpan>
)

/**
 * Pure, distraction-free WYSIWYG Borderless Note & Article Editor:
 * - Zero raw markdown symbols (** or _ or ##) ever appear anywhere in the UI!
 * - Horizontally scrollable pro article toolbar below Title:
 *   Normal, H1, H2, H3, Bold, Italic, Underline, Strikethrough, Multi-Color Highlighter Brush,
 *   Multi-Color Ink, Bullet List, Numbered List, Checklist, Quote, Code, Divider, Timestamp, Clear Format
 * - Fresh blank editor guaranteed on every '+' new note creation (no stale title carry-over).
 */
@Composable
fun RecordEditorScreen(
    existingRecordId: String?,
    initialFolderId: String?,
    viewModel: RecordsViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenVersionHistory: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoSaveEnabled = settings.autoSaveNotes

    var initialized by remember { mutableStateOf(false) }
    var isSavingOrClosing by remember { mutableStateOf(false) }

    var title by remember { mutableStateOf("") }
    var bodyValue by remember { mutableStateOf(TextFieldValue("")) }
    var richSpans by remember { mutableStateOf<List<RichSpan>>(emptyList()) }
    var activeStyle by remember { mutableStateOf(ActiveTypingStyle()) }
    var openDrawer by remember { mutableStateOf(ActiveColorDrawer.NONE) }
    var showExtendedHighlighterPalette by remember { mutableStateOf(false) }
    var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    var selectedFolderId by remember { mutableStateOf(initialFolderId) }
    var preservedCategory by remember { mutableStateOf<String?>(null) }
    var preservedCustomFields by remember { mutableStateOf<List<CustomField>>(emptyList()) }

    val blocks = remember { mutableStateListOf<RichNoteBlock>() }
    var activeBlockId by remember { mutableStateOf<String?>(null) }
    var currentRecordId by remember { mutableStateOf(existingRecordId) }
    var imageSettingsTargetId by remember { mutableStateOf<String?>(null) }
    val editorScope = rememberCoroutineScope()

    val undoStack = remember { mutableStateListOf<EditorHistorySnapshot>() }
    val redoStack = remember { mutableStateListOf<EditorHistorySnapshot>() }
    var lastSnapshotTimestamp by remember { mutableLongStateOf(0L) }

    fun pushUndoCheckpoint(
        force: Boolean = false,
        nextTitle: String = title,
        nextBody: TextFieldValue = bodyValue,
        nextSpans: List<RichSpan> = richSpans
    ) {
        val currentSnap = EditorHistorySnapshot(title = title, body = bodyValue, spans = richSpans)
        if (currentSnap.title == nextTitle &&
            currentSnap.body.text == nextBody.text &&
            currentSnap.spans == nextSpans
        ) {
            return
        }
        val now = System.currentTimeMillis()
        val deltaChars = abs(nextBody.text.length - bodyValue.text.length) + abs(nextTitle.length - title.length)
        val wordBoundaryTyped = nextBody.text.endsWith(" ") ||
            nextBody.text.endsWith("\n") ||
            nextTitle.endsWith(" ")
        val shouldRecord = force ||
            undoStack.isEmpty() ||
            deltaChars >= 2 ||
            wordBoundaryTyped ||
            currentSnap.spans != nextSpans ||
            (now - lastSnapshotTimestamp) >= 420L

        if (shouldRecord) {
            val top = undoStack.lastOrNull()
            if (top == null || top.title != currentSnap.title || top.body.text != currentSnap.body.text || top.spans != currentSnap.spans) {
                undoStack.add(currentSnap)
                if (undoStack.size > 100) {
                    undoStack.removeAt(0)
                }
            }
            lastSnapshotTimestamp = now
        }
        redoStack.clear()
    }

    fun performUndo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(EditorHistorySnapshot(title = title, body = bodyValue, spans = richSpans))
        title = prev.title
        bodyValue = prev.body
        richSpans = prev.spans
        lastSnapshotTimestamp = 0L
    }

    fun performRedo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(EditorHistorySnapshot(title = title, body = bodyValue, spans = richSpans))
        title = next.title
        bodyValue = next.body
        richSpans = next.spans
        lastSnapshotTimestamp = 0L
    }

    fun flushActiveTextInto(target: MutableList<RichNoteBlock>) {
        val id = activeBlockId ?: return
        val idx = target.indexOfFirst { it.id == id }
        val current = target.getOrNull(idx)
        if (current is RichNoteBlock.Text) {
            target[idx] = current.copy(text = bodyValue.text, spans = richSpans)
        }
    }

    fun snapshotBlocks(): List<RichNoteBlock> {
        val copy = blocks.toMutableList()
        flushActiveTextInto(copy)
        if (copy.isEmpty()) {
            copy.add(RichNoteBlock.Text(text = bodyValue.text, spans = richSpans))
        }
        return RichNoteCodec.ensureSurroundingTextBlocks(copy)
    }

    fun resolvedTitle(): String = title.trim().ifBlank {
        bodyValue.text.lineSequence()
            .map { it.trim().removePrefix("• ").removePrefix("☐ ").removePrefix("☑ ").trim() }
            .firstOrNull { it.isNotEmpty() && it != "────────────────────" }
            ?.take(48)
            .orEmpty()
    }

    fun loadedBlocks(list: List<RichNoteBlock>) {
        blocks.clear()
        list.forEach { blocks.add(it) }
        val firstText = list.firstOrNull { it is RichNoteBlock.Text } as? RichNoteBlock.Text
        activeBlockId = firstText?.id
        bodyValue = TextFieldValue(firstText?.text.orEmpty(), TextRange(firstText?.text?.length ?: 0))
        richSpans = firstText?.spans ?: emptyList()
    }

    fun applyHeadingOrNormal(targetHeading: RichSpanKind?) {
        val (lineStart, lineEnd) = RichNoteCodec.lineBoundsAt(bodyValue.text, bodyValue.selection.min)
        val headingGroup = setOf(RichSpanKind.H1, RichSpanKind.H2, RichSpanKind.H3)
        if (lineEnd > lineStart) {
            val nextSpans = if (targetHeading == null) {
                richSpans.mapNotNull { sp ->
                    if (sp.kind !in headingGroup || sp.end <= lineStart || sp.start >= lineEnd) {
                        sp
                    } else {
                        if (sp.start < lineStart) sp.copy(end = lineStart)
                        else if (sp.end > lineEnd) sp.copy(start = lineEnd)
                        else null
                    }
                }.let { RichNoteCodec.normalizeSpans(it, bodyValue.text.length) }
            } else {
                RichNoteCodec.toggleSpanInRange(
                    spans = richSpans,
                    start = lineStart,
                    end = lineEnd,
                    kind = targetHeading,
                    exclusiveGroup = headingGroup,
                    textLength = bodyValue.text.length
                )
            }
            pushUndoCheckpoint(force = true, nextSpans = nextSpans)
            richSpans = nextSpans
        }
        activeStyle = activeStyle.copy(heading = if (activeStyle.heading == targetHeading) null else targetHeading)
    }

    fun applyInlineToggle(kind: RichSpanKind) {
        val selMin = bodyValue.selection.min
        val selMax = bodyValue.selection.max
        if (selMin < selMax) {
            val nextSpans = RichNoteCodec.toggleSpanInRange(
                spans = richSpans,
                start = selMin,
                end = selMax,
                kind = kind,
                textLength = bodyValue.text.length
            )
            pushUndoCheckpoint(force = true, nextSpans = nextSpans)
            richSpans = nextSpans
        } else {
            activeStyle = when (kind) {
                RichSpanKind.BOLD -> activeStyle.copy(bold = !activeStyle.bold)
                RichSpanKind.ITALIC -> activeStyle.copy(italic = !activeStyle.italic)
                RichSpanKind.UNDERLINE -> activeStyle.copy(underline = !activeStyle.underline)
                RichSpanKind.STRIKETHROUGH -> activeStyle.copy(strikethrough = !activeStyle.strikethrough)
                RichSpanKind.CODE -> activeStyle.copy(code = !activeStyle.code)
                RichSpanKind.QUOTE -> {
                    val (ls, le) = RichNoteCodec.lineBoundsAt(bodyValue.text, selMin)
                    if (le > ls) {
                        val nextSpans = RichNoteCodec.toggleSpanInRange(
                            spans = richSpans,
                            start = ls,
                            end = le,
                            kind = RichSpanKind.QUOTE,
                            textLength = bodyValue.text.length
                        )
                        pushUndoCheckpoint(force = true, nextSpans = nextSpans)
                        richSpans = nextSpans
                    }
                    activeStyle.copy(quote = !activeStyle.quote)
                }
                else -> activeStyle
            }
        }
    }

    fun applyHighlighterBrush(hex: String?) {
        val selMin = bodyValue.selection.min
        val selMax = bodyValue.selection.max
        val (lineStart, lineEnd) = RichNoteCodec.lineBoundsAt(bodyValue.text, selMin)

        val targetStart: Int
        val targetEnd: Int
        if (selMin < selMax) {
            targetStart = selMin
            targetEnd = selMax
        } else if (lineEnd > lineStart) {
            targetStart = lineStart
            targetEnd = lineEnd
        } else {
            targetStart = -1
            targetEnd = -1
        }

        if (targetStart >= 0 && targetEnd > targetStart) {
            val nextSpans = if (hex == null) {
                richSpans.mapNotNull { sp ->
                    if (sp.kind != RichSpanKind.HIGHLIGHT || sp.end <= targetStart || sp.start >= targetEnd) {
                        sp
                    } else {
                        if (sp.start < targetStart) sp.copy(end = targetStart)
                        else if (sp.end > targetEnd) sp.copy(start = targetEnd)
                        else null
                    }
                }.let { RichNoteCodec.normalizeSpans(it, bodyValue.text.length) }
            } else {
                RichNoteCodec.toggleSpanInRange(
                    spans = richSpans,
                    start = targetStart,
                    end = targetEnd,
                    kind = RichSpanKind.HIGHLIGHT,
                    colorHex = hex,
                    exclusiveGroup = setOf(RichSpanKind.HIGHLIGHT),
                    textLength = bodyValue.text.length
                )
            }
            pushUndoCheckpoint(force = true, nextSpans = nextSpans)
            richSpans = nextSpans
        }

        activeStyle = activeStyle.copy(
            highlightHex = if (hex != null && activeStyle.highlightHex == hex && targetStart == -1) null else hex
        )
    }

    fun applyInkColor(hex: String?) {
        val selMin = bodyValue.selection.min
        val selMax = bodyValue.selection.max
        if (selMin < selMax) {
            val nextSpans = if (hex == null || hex == "#101010") {
                richSpans.mapNotNull { sp ->
                    if (sp.kind != RichSpanKind.TEXT_COLOR || sp.end <= selMin || sp.start >= selMax) {
                        sp
                    } else {
                        if (sp.start < selMin) sp.copy(end = selMin)
                        else if (sp.end > selMax) sp.copy(start = selMax)
                        else null
                    }
                }.let { RichNoteCodec.normalizeSpans(it, bodyValue.text.length) }
            } else {
                RichNoteCodec.toggleSpanInRange(
                    spans = richSpans,
                    start = selMin,
                    end = selMax,
                    kind = RichSpanKind.TEXT_COLOR,
                    colorHex = hex,
                    exclusiveGroup = setOf(RichSpanKind.TEXT_COLOR),
                    textLength = bodyValue.text.length
                )
            }
            pushUndoCheckpoint(force = true, nextSpans = nextSpans)
            richSpans = nextSpans
        }
        activeStyle = activeStyle.copy(
            textColorHex = if (hex == "#101010") null else hex
        )
    }

    fun toggleLinePrefixClean(prefixKind: String) {
        val text = bodyValue.text
        val cursor = bodyValue.selection.min.coerceIn(0, text.length)
        val (ls, le) = RichNoteCodec.lineBoundsAt(text, cursor)
        val line = text.substring(ls, le)

        val existingPrefixLen = when {
            line.startsWith("• ") -> 2
            line.startsWith("☐ ") || line.startsWith("☑ ") -> 2
            else -> Regex("^\\d+\\. ").find(line)?.value?.length ?: 0
        }

        val desiredPrefix = when (prefixKind) {
            "BULLET" -> if (line.startsWith("• ")) "" else "• "
            "NUMBER" -> if (Regex("^\\d+\\. ").containsMatchIn(line)) "" else "1. "
            "CHECK" -> when {
                line.startsWith("☐ ") -> "☑ "
                line.startsWith("☑ ") -> ""
                else -> "☐ "
            }
            else -> ""
        }

        val strippedText = if (existingPrefixLen > 0) {
            text.removeRange(ls, ls + existingPrefixLen)
        } else {
            text
        }
        val spansAfterDelete = if (existingPrefixLen > 0) {
            RichNoteCodec.shiftSpansAfterDeletion(richSpans, ls, ls + existingPrefixLen)
        } else {
            richSpans
        }

        val finalText = if (desiredPrefix.isNotEmpty()) {
            strippedText.substring(0, ls) + desiredPrefix + strippedText.substring(ls)
        } else {
            strippedText
        }
        val finalSpans = if (desiredPrefix.isNotEmpty()) {
            RichNoteCodec.shiftSpansAfterInsertion(spansAfterDelete, ls, desiredPrefix.length)
        } else {
            spansAfterDelete
        }

        val newCursor = (cursor - existingPrefixLen + desiredPrefix.length).coerceIn(ls, finalText.length)
        val nextField = TextFieldValue(finalText, TextRange(newCursor))
        val normalized = RichNoteCodec.normalizeSpans(finalSpans, finalText.length)
        pushUndoCheckpoint(force = true, nextBody = nextField, nextSpans = normalized)
        bodyValue = nextField
        richSpans = normalized
    }

    fun insertSnippetAtCursor(snippet: String) {
        val s = bodyValue.selection.min.coerceIn(0, bodyValue.text.length)
        val e = bodyValue.selection.max.coerceIn(0, bodyValue.text.length)
        val afterDelete = if (e > s) RichNoteCodec.shiftSpansAfterDeletion(richSpans, s, e) else richSpans
        val nextText = bodyValue.text.replaceRange(s, e, snippet)
        val shifted = RichNoteCodec.shiftSpansAfterInsertion(afterDelete, s, snippet.length)
        val nextField = TextFieldValue(nextText, TextRange(s + snippet.length))
        val normalized = RichNoteCodec.normalizeSpans(shifted, nextText.length)
        pushUndoCheckpoint(force = true, nextBody = nextField, nextSpans = normalized)
        bodyValue = nextField
        richSpans = normalized
    }

    fun clearFormattingOnSelectionOrLine() {
        val selMin = bodyValue.selection.min
        val selMax = bodyValue.selection.max
        val (s, e) = if (selMin < selMax) {
            selMin to selMax
        } else {
            RichNoteCodec.lineBoundsAt(bodyValue.text, selMin)
        }
        val nextSpans = RichNoteCodec.clearAllFormattingInRange(richSpans, s, e, bodyValue.text.length)
        pushUndoCheckpoint(force = true, nextSpans = nextSpans)
        richSpans = nextSpans
        activeStyle = ActiveTypingStyle()
    }

    fun insertImageBlock(imageReference: String) {
        val text = bodyValue.text
        val cursor = bodyValue.selection.min.coerceIn(0, text.length)
        val before = text.substring(0, cursor)
        val after = text.substring(cursor)
        val beforeSpans = richSpans.mapNotNull { sp ->
            val e = minOf(sp.end, cursor)
            if (sp.start < e) sp.copy(end = e) else null
        }
        val afterSpans = richSpans.mapNotNull { sp ->
            val s = maxOf(sp.start, cursor)
            if (s < sp.end) sp.copy(start = s - cursor, end = sp.end - cursor) else null
        }
        val beforeBlock = RichNoteBlock.Text(
            text = before,
            spans = RichNoteCodec.normalizeSpans(beforeSpans, before.length)
        )
        val imageBlock = RichNoteBlock.Image(imagePath = imageReference, widthFraction = 1f)
        val afterBlock = RichNoteBlock.Text(
            text = after,
            spans = RichNoteCodec.normalizeSpans(afterSpans, after.length)
        )

        val idx = blocks.indexOfFirst { it.id == activeBlockId }
        if (idx >= 0) {
            blocks[idx] = beforeBlock
            blocks.add(idx + 1, imageBlock)
            blocks.add(idx + 2, afterBlock)
        } else {
            blocks.add(beforeBlock)
            blocks.add(imageBlock)
            blocks.add(afterBlock)
        }
        activeBlockId = afterBlock.id
        bodyValue = TextFieldValue(after, TextRange(0))
        richSpans = afterBlock.spans
        activeStyle = ActiveTypingStyle()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            editorScope.launch {
                val identifier = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        viewModel.inlineImageStore.importFromStream(input, uri.toString())
                    } ?: error("Cannot read the selected image.")
                }.getOrNull()
                val renderable = identifier?.let { viewModel.inlineImageStore.resolveForDisplay(it) }
                if (identifier != null && renderable != null) {
                    insertImageBlock(identifier)
                } else {
                    viewModel.reportError("Could not load the selected image.")
                }
            }
        }
    }

    fun performSave(navigateAfter: Boolean) {
        val encoded = RichNoteCodec.encodeBlocks(snapshotBlocks())
        val resolved = resolvedTitle()
        val hasContent = RichNoteCodec.hasMeaningfulContent(encoded) || resolved.isNotBlank()
        if (!hasContent) {
            if (navigateAfter) viewModel.reportError("Write a title or note before saving.")
            return
        }
        if (navigateAfter) isSavingOrClosing = true
        viewModel.saveRecord(
            existingRecordId = currentRecordId,
            title = resolved.ifBlank { "Untitled Note" },
            description = encoded,
            folderId = selectedFolderId,
            category = preservedCategory,
            customFields = preservedCustomFields,
            pendingAttachmentUris = emptyList(),
            coalesceVersion = !navigateAfter,
            onSaved = { saved ->
                currentRecordId = saved.id
                if (navigateAfter) {
                    onSaved(saved.id)
                }
            }
        )
    }

    fun handleSave() = performSave(navigateAfter = true)

    fun handleBack() {
        isSavingOrClosing = true
        if (autoSaveEnabled) {
            performSave(navigateAfter = false)
            if (existingRecordId == null) {
                viewModel.discardDraft("NEW_${initialFolderId ?: "ROOT"}")
            }
        } else if (existingRecordId == null) {
            viewModel.discardDraft("NEW_${initialFolderId ?: "ROOT"}")
        }
        onBack()
    }

    LaunchedEffect(existingRecordId) {
        if (!initialized) {
            if (existingRecordId != null) {
                val existing = viewModel.cachedRecord(existingRecordId)
                    ?: viewModel.loadRecordForEdit(existingRecordId)
                val draft = viewModel.loadDraft("EDIT_$existingRecordId")
                if (existing != null) {
                    selectedFolderId = existing.folderId
                    preservedCategory = existing.category
                    preservedCustomFields = existing.customFields
                    val rawDesc = if (draft != null && draft.updatedAt > existing.modifiedAt) {
                        title = draft.title
                        draft.description
                    } else {
                        title = existing.title
                        existing.description
                    }
                    loadedBlocks(RichNoteCodec.decodeBlocks(rawDesc))
                }
            } else {
                title = ""
                loadedBlocks(listOf(RichNoteBlock.Text()))
                selectedFolderId = initialFolderId
                viewModel.discardDraft("NEW_${initialFolderId ?: "ROOT"}")
            }
            initialized = true
        }
    }

    val autoSaveSignature = remember(title, bodyValue.text, richSpans, blocks.size) {
        title + "|" + bodyValue.text.length + "|" + richSpans.size + "|" + blocks.size
    }
    LaunchedEffect(autoSaveSignature, initialized, autoSaveEnabled, isSavingOrClosing) {
        if (initialized && autoSaveEnabled && !isSavingOrClosing) {
            val encoded = RichNoteCodec.encodeBlocks(snapshotBlocks())
            val hasContent = RichNoteCodec.hasMeaningfulContent(encoded) || title.isNotBlank()
            if (hasContent) {
                delay(1200)
                performSave(navigateAfter = false)
            }
        }
    }

    DisposableEffect(lifecycleOwner, autoSaveEnabled, initialized) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && autoSaveEnabled && initialized && !isSavingOrClosing) {
                performSave(navigateAfter = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(title, bodyValue.text, richSpans, initialized, isSavingOrClosing) {
        if (initialized && !isSavingOrClosing && existingRecordId != null) {
            delay(850)
            if (!isSavingOrClosing) {
                viewModel.persistDraft(
                    RecordDraft(
                        draftKey = "EDIT_$existingRecordId",
                        folderId = selectedFolderId,
                        title = title,
                        description = RichNoteCodec.encodeBlocks(snapshotBlocks()),
                        category = preservedCategory,
                        customFields = preservedCustomFields,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    val visualTransform = remember(richSpans) {
        RichNoteCodec.visualTransformation(richSpans, paintHighlightInline = false)
    }

    val currentBack by rememberUpdatedState(::handleBack)
    val currentUndo by rememberUpdatedState(::performUndo)
    val currentRedo by rememberUpdatedState(::performRedo)
    val currentSave by rememberUpdatedState(::handleSave)

    BauhausScreenScaffold(
        title = "",
        subtitle = null,
        onBack = { currentBack() },
        actions = {
            IconButton(
                onClick = { currentUndo() },
                enabled = undoStack.isNotEmpty(),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("btn_editor_undo")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = "Undo",
                    tint = if (undoStack.isNotEmpty()) BauhausColors.Black else BauhausColors.Divider
                )
            }
            IconButton(
                onClick = { currentRedo() },
                enabled = redoStack.isNotEmpty(),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("btn_editor_redo")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Redo,
                    contentDescription = "Redo",
                    tint = if (redoStack.isNotEmpty()) BauhausColors.Black else BauhausColors.Divider
                )
            }
            IconButton(
                onClick = { currentSave() },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("btn_save_record_top")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Save,
                    contentDescription = "Save note",
                    tint = BauhausColors.Red
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = BauhausDimens.PageHorizontalPadding)
        ) {
            errorMsg?.let { msg ->
                Box(modifier = Modifier.padding(bottom = 6.dp)) {
                    BauhausErrorBanner(message = msg, onDismiss = viewModel::clearFeedback)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("editor_scroll_root")
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { nextTitle ->
                        pushUndoCheckpoint(force = false, nextTitle = nextTitle, nextBody = bodyValue, nextSpans = richSpans)
                        title = nextTitle
                    },
                    textStyle = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 24.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = BauhausColors.Black
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next
                    ),
                    cursorBrush = SolidColor(BauhausColors.Red),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            if (title.isEmpty()) {
                                Text(
                                    text = "Title",
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontSize = 24.sp,
                                        lineHeight = 32.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = BauhausColors.SecondaryText.copy(alpha = 0.65f)
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editor_title_input")
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = BauhausColors.Black.copy(alpha = 0.09f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                )

                blocks.forEachIndexed { blockIndex, block ->
                    when (block) {
                        is RichNoteBlock.Text -> {
                            val isActive = block.id == activeBlockId
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp)
                                ) {
                                    BasicTextField(
                                        value = bodyValue,
                                        onValueChange = { incoming ->
                                            val (nextVal, nextSpans, nextActive) = RichNoteCodec.applyTextEdit(
                                                oldText = bodyValue.text,
                                                newValue = incoming,
                                                oldSpans = richSpans,
                                                activeStyle = activeStyle
                                            )
                                            pushUndoCheckpoint(
                                                force = false,
                                                nextTitle = title,
                                                nextBody = nextVal,
                                                nextSpans = nextSpans
                                            )
                                            bodyValue = nextVal
                                            richSpans = nextSpans
                                            activeStyle = nextActive
                                        },
                                        visualTransformation = visualTransform,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 16.sp,
                                            lineHeight = 26.sp,
                                            color = BauhausColors.Black,
                                            textAlign = TextAlign.Start
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Sentences
                                        ),
                                        cursorBrush = SolidColor(BauhausColors.Red),
                                        onTextLayout = { bodyLayout = it },
                                        decorationBox = { innerTextField ->
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .defaultMinSize(minHeight = if (blocks.size == 1) 420.dp else 72.dp)
                                            ) {
                                                if (bodyValue.text.isEmpty() && blocks.size == 1) {
                                                    Text(
                                                        text = "Start writing...",
                                                        style = MaterialTheme.typography.bodyLarge.copy(
                                                            fontSize = 16.sp,
                                                            lineHeight = 26.sp
                                                        ),
                                                        color = BauhausColors.SecondaryText.copy(alpha = 0.60f)
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .drawBehind {
                                                drawBalancedHighlightSpans(
                                                    layoutResult = bodyLayout,
                                                    textLength = bodyValue.text.length,
                                                    spans = richSpans
                                                )
                                            }
                                            .testTag("editor_body_input")
                                    )
                                }
                            } else {
                                StaticRichTextBlock(
                                    text = block.text,
                                    spans = block.spans,
                                    alignment = block.alignment,
                                    onClick = {
                                        flushActiveTextInto(blocks)
                                        activeBlockId = block.id
                                        bodyValue = TextFieldValue(block.text, TextRange(block.text.length))
                                        richSpans = block.spans
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }

                        is RichNoteBlock.Image -> {
                            ResizableNoteImageBlock(
                                imageBlock = block,
                                imageStore = viewModel.inlineImageStore,
                                onAlign = { align ->
                                    val idx = blocks.indexOfFirst { it.id == block.id }
                                    if (idx >= 0) {
                                        blocks[idx] = (blocks[idx] as RichNoteBlock.Image).copy(alignment = align)
                                    }
                                },
                                onResize = { fraction ->
                                    val idx = blocks.indexOfFirst { it.id == block.id }
                                    if (idx >= 0) {
                                        blocks[idx] = (blocks[idx] as RichNoteBlock.Image)
                                            .copy(widthFraction = fraction)
                                    }
                                },
                                onDelete = {
                                    val idx = blocks.indexOfFirst { it.id == block.id }
                                    if (idx >= 0) {
                                        val removedPath = block.imagePath
                                        blocks.removeAt(idx)
                                        viewModel.deleteInlineImage(removedPath, existingRecordId)
                                    }
                                },
                                onSettings = { imageSettingsTargetId = block.id }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
            }

            AnimatedVisibility(
                visible = openDrawer != ActiveColorDrawer.NONE,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp)
                        .testTag("editor_color_palette_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (openDrawer == ActiveColorDrawer.HIGHLIGHTER) {
                        Text(
                            text = "Highlight:",
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText
                        )
                        NoteHighlighterPalette.forEach { swatch ->
                            val selected = activeStyle.highlightHex == swatch.hex
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(swatch.color)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) BauhausColors.Black else BauhausColors.Divider,
                                        shape = CircleShape
                                    )
                                    .clickable { applyHighlighterBrush(swatch.hex) }
                                    .testTag("highlight_swatch_${swatch.name.lowercase().replace(" ", "_")}")
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showExtendedHighlighterPalette) BauhausColors.Black
                                    else BauhausColors.CardSurface
                                )
                                .border(1.dp, BauhausColors.Black, CircleShape)
                                .clickable { showExtendedHighlighterPalette = !showExtendedHighlighterPalette }
                                .testTag("highlight_swatch_more"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "More highlighter colors",
                                tint = if (showExtendedHighlighterPalette) BauhausColors.LightSurface else BauhausColors.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        if (showExtendedHighlighterPalette) {
                            Text(
                                text = "All:",
                                style = MaterialTheme.typography.labelMedium,
                                color = BauhausColors.SecondaryText
                            )
                            NoteExtendedHighlighterPalette.forEach { swatch ->
                                val selected = activeStyle.highlightHex == swatch.hex
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(swatch.color)
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) BauhausColors.Black else BauhausColors.Divider,
                                            shape = CircleShape
                                        )
                                        .clickable { applyHighlighterBrush(swatch.hex) }
                                        .testTag("highlight_swatch_ext_${swatch.name.lowercase().replace(" ", "_")}")
                                )
                            }
                        }

                        IconButton(
                            onClick = { applyHighlighterBrush(null) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("highlight_swatch_clear")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FormatColorReset,
                                contentDescription = "Remove highlight",
                                tint = BauhausColors.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (openDrawer == ActiveColorDrawer.INK_COLOR) {
                        Text(
                            text = "Color:",
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText
                        )
                        NoteInkColorPalette.forEach { swatch ->
                            val selected = (activeStyle.textColorHex ?: "#101010") == swatch.hex
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(swatch.color)
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) BauhausColors.Yellow else BauhausColors.Divider,
                                        shape = CircleShape
                                    )
                                    .clickable { applyInkColor(swatch.hex) }
                                    .testTag("ink_swatch_${swatch.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = BauhausColors.Black.copy(alpha = 0.09f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
                    .testTag("editor_style_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditorTextPillTool(
                    label = "Normal",
                    active = activeStyle.heading == null,
                    onClick = { applyHeadingOrNormal(null) },
                    testTag = "btn_style_normal"
                )
                EditorTextPillTool(
                    label = "H1",
                    active = activeStyle.heading == RichSpanKind.H1,
                    onClick = { applyHeadingOrNormal(RichSpanKind.H1) },
                    testTag = "btn_style_h1"
                )
                EditorTextPillTool(
                    label = "H2",
                    active = activeStyle.heading == RichSpanKind.H2,
                    onClick = { applyHeadingOrNormal(RichSpanKind.H2) },
                    testTag = "btn_style_h2"
                )
                EditorTextPillTool(
                    label = "H3",
                    active = activeStyle.heading == RichSpanKind.H3,
                    onClick = { applyHeadingOrNormal(RichSpanKind.H3) },
                    testTag = "btn_style_h3"
                )

                ToolbarVerticalTick()

                EditorIconTool(
                    icon = Icons.Outlined.FormatBold,
                    contentDescription = "Bold",
                    active = activeStyle.bold,
                    onClick = { applyInlineToggle(RichSpanKind.BOLD) },
                    testTag = "btn_style_bold"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatItalic,
                    contentDescription = "Italic",
                    active = activeStyle.italic,
                    onClick = { applyInlineToggle(RichSpanKind.ITALIC) },
                    testTag = "btn_style_italic"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatUnderlined,
                    contentDescription = "Underline",
                    active = activeStyle.underline,
                    onClick = { applyInlineToggle(RichSpanKind.UNDERLINE) },
                    testTag = "btn_style_underline"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatStrikethrough,
                    contentDescription = "Strikethrough",
                    active = activeStyle.strikethrough,
                    onClick = { applyInlineToggle(RichSpanKind.STRIKETHROUGH) },
                    testTag = "btn_style_strike"
                )

                ToolbarVerticalTick()

                val currentBlockAlignment = blocks.firstOrNull { it.id == activeBlockId }?.let { active ->
                    when (active) {
                        is RichNoteBlock.Text -> paragraphAlignmentAt(
                            text = bodyValue.text,
                            position = bodyValue.selection.min,
                            spans = richSpans
                        )
                        is RichNoteBlock.Image -> active.alignment
                    }
                } ?: BlockAlignment.LEFT
                val applyCurrentBlockAlignment: (BlockAlignment) -> Unit = { align ->
                    val activeIndex = blocks.indexOfFirst { it.id == activeBlockId }
                    val currentBlock = blocks.getOrNull(activeIndex)
                    if (currentBlock is RichNoteBlock.Image) {
                        blocks[activeIndex] = currentBlock.copy(alignment = align)
                    } else if (currentBlock is RichNoteBlock.Text) {
                        val nextSpans = applyParagraphAlignment(
                            text = bodyValue.text,
                            spans = richSpans,
                            position = bodyValue.selection.min,
                            alignment = align
                        )
                        pushUndoCheckpoint(
                            force = true,
                            nextTitle = title,
                            nextBody = bodyValue,
                            nextSpans = nextSpans
                        )
                        blocks[activeIndex] = currentBlock.copy(spans = nextSpans)
                        richSpans = nextSpans
                    }
                }
                EditorIconTool(
                    icon = Icons.Outlined.FormatAlignLeft,
                    contentDescription = "Align left",
                    active = currentBlockAlignment == BlockAlignment.LEFT,
                    onClick = { applyCurrentBlockAlignment(BlockAlignment.LEFT) },
                    testTag = "btn_align_left"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatAlignCenter,
                    contentDescription = "Align center",
                    active = currentBlockAlignment == BlockAlignment.CENTER,
                    onClick = { applyCurrentBlockAlignment(BlockAlignment.CENTER) },
                    testTag = "btn_align_center"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatAlignRight,
                    contentDescription = "Align right",
                    active = currentBlockAlignment == BlockAlignment.RIGHT,
                    onClick = { applyCurrentBlockAlignment(BlockAlignment.RIGHT) },
                    testTag = "btn_align_right"
                )

                ToolbarVerticalTick()

                EditorIconTool(
                    icon = Icons.Outlined.Brush,
                    contentDescription = "Highlighter brush",
                    active = openDrawer == ActiveColorDrawer.HIGHLIGHTER || activeStyle.highlightHex != null,
                    accentDotColor = RichNoteCodec.parseHexColorOrNull(activeStyle.highlightHex),
                    onClick = {
                        showExtendedHighlighterPalette = false
                        openDrawer = if (openDrawer == ActiveColorDrawer.HIGHLIGHTER) {
                            ActiveColorDrawer.NONE
                        } else {
                            ActiveColorDrawer.HIGHLIGHTER
                        }
                    },
                    testTag = "btn_style_highlighter"
                )

                EditorIconTool(
                    icon = Icons.Outlined.FormatColorText,
                    contentDescription = "Text color",
                    active = openDrawer == ActiveColorDrawer.INK_COLOR || activeStyle.textColorHex != null,
                    accentDotColor = RichNoteCodec.parseHexColorOrNull(activeStyle.textColorHex),
                    onClick = {
                        openDrawer = if (openDrawer == ActiveColorDrawer.INK_COLOR) {
                            ActiveColorDrawer.NONE
                        } else {
                            ActiveColorDrawer.INK_COLOR
                        }
                    },
                    testTag = "btn_style_text_color"
                )

                EditorIconTool(
                    icon = Icons.Outlined.Image,
                    contentDescription = "Insert image",
                    active = false,
                    onClick = { imagePickerLauncher.launch("image/*") },
                    testTag = "btn_style_image"
                )

                ToolbarVerticalTick()

                EditorIconTool(
                    icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                    contentDescription = "Bullet list",
                    active = false,
                    onClick = { toggleLinePrefixClean("BULLET") },
                    testTag = "btn_style_bullet"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatListNumbered,
                    contentDescription = "Numbered list",
                    active = false,
                    onClick = { toggleLinePrefixClean("NUMBER") },
                    testTag = "btn_style_numbered"
                )
                EditorIconTool(
                    icon = Icons.Outlined.CheckBox,
                    contentDescription = "Checklist",
                    active = false,
                    onClick = { toggleLinePrefixClean("CHECK") },
                    testTag = "btn_style_checklist"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatQuote,
                    contentDescription = "Quote",
                    active = activeStyle.quote,
                    onClick = { applyInlineToggle(RichSpanKind.QUOTE) },
                    testTag = "btn_style_quote"
                )
                EditorIconTool(
                    icon = Icons.Outlined.Code,
                    contentDescription = "Code",
                    active = activeStyle.code,
                    onClick = { applyInlineToggle(RichSpanKind.CODE) },
                    testTag = "btn_style_code"
                )
                EditorIconTool(
                    icon = Icons.Outlined.HorizontalRule,
                    contentDescription = "Section divider",
                    active = false,
                    onClick = {
                        val prefix = if (bodyValue.text.isEmpty() || bodyValue.text.endsWith("\n")) "" else "\n"
                        insertSnippetAtCursor("${prefix}────────────────────\n")
                    },
                    testTag = "btn_style_divider"
                )
                EditorIconTool(
                    icon = Icons.Outlined.Schedule,
                    contentDescription = "Insert timestamp",
                    active = false,
                    onClick = {
                        insertSnippetAtCursor(DateFormatters.formatDateTime(System.currentTimeMillis()))
                    },
                    testTag = "btn_style_timestamp"
                )
                EditorIconTool(
                    icon = Icons.Outlined.FormatClear,
                    contentDescription = "Clear formatting",
                    active = false,
                    onClick = ::clearFormattingOnSelectionOrLine,
                    testTag = "btn_style_clear"
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
        }
    }

    imageSettingsTargetId?.let { targetId ->
        val target = blocks.firstOrNull { it.id == targetId } as? RichNoteBlock.Image
        if (target != null) {
            ImageSizeDialog(
                imagePath = target.imagePath,
                imageStore = viewModel.inlineImageStore,
                widthFraction = target.widthFraction,
                onChangeWidth = { fraction ->
                    val idx = blocks.indexOfFirst { it.id == targetId }
                    if (idx >= 0) {
                        blocks[idx] = (blocks[idx] as RichNoteBlock.Image).copy(widthFraction = fraction)
                    }
                },
                onDelete = {
                    val idx = blocks.indexOfFirst { it.id == targetId }
                    if (idx >= 0) blocks.removeAt(idx)
                    viewModel.deleteInlineImage(target.imagePath, existingRecordId)
                    imageSettingsTargetId = null
                },
                onDismiss = { imageSettingsTargetId = null }
            )
        } else {
            imageSettingsTargetId = null
        }
    }
}

private fun BlockAlignment.toTextAlign(): TextAlign = when (this) {
    BlockAlignment.LEFT -> TextAlign.Start
    BlockAlignment.CENTER -> TextAlign.Center
    BlockAlignment.RIGHT -> TextAlign.End
}

@Composable
private fun StaticRichTextBlock(
    text: String,
    spans: List<RichSpan>,
    alignment: BlockAlignment = BlockAlignment.LEFT,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(text, spans) {
        RichNoteCodec.buildAnnotatedString(text, spans, paintHighlightInline = false)
    }
    if (text.isEmpty()) {
        Box(
            modifier = modifier
                .defaultMinSize(minHeight = 26.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
        )
    } else {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 26.sp,
                color = BauhausColors.Black,
                textAlign = alignment.toTextAlign()
            ),
            onTextLayout = { layout = it },
            modifier = modifier
                .clickable(onClick = onClick)
                .drawBehind {
                    drawBalancedHighlightSpans(
                        layoutResult = layout,
                        textLength = text.length,
                        spans = spans
                    )
                }
                .padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun ResizableNoteImageBlock(
    imageBlock: RichNoteBlock.Image,
    imageStore: InlineImageStore,
    onAlign: (BlockAlignment) -> Unit,
    onResize: (Float) -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit
) {
    val bitmapState by produceState<Bitmap?>(initialValue = null, key1 = imageBlock.imagePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = imageStore.resolveForDisplay(imageBlock.imagePath)
                if (file != null) decodeSampledBitmap(file, maxDim = 1600) else null
            }.getOrNull()
        }
    }
    val bitmap = bitmapState
    var confirmDelete by remember { mutableStateOf(false) }
    if (bitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BauhausColors.CardSurface)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = BauhausColors.SecondaryText)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Image not available", style = MaterialTheme.typography.bodyMedium, color = BauhausColors.SecondaryText)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Remove", color = BauhausColors.Red) }
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("editor_image_block_${imageBlock.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = when (imageBlock.alignment) {
                BlockAlignment.LEFT -> Arrangement.Start
                BlockAlignment.CENTER -> Arrangement.Center
                BlockAlignment.RIGHT -> Arrangement.End
            }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Note image",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth(imageBlock.widthFraction.coerceIn(0.25f, 1.0f))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onSettings)
                    .testTag("editor_image_aligned_" + imageBlock.alignment.name)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${(imageBlock.widthFraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = BauhausColors.SecondaryText
            )
            Spacer(modifier = Modifier.weight(1f))
            listOf(0.4f, 0.6f, 0.8f, 1.0f).forEach { fraction ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (abs(imageBlock.widthFraction - fraction) < 0.01f) BauhausColors.Black
                            else BauhausColors.CardSurface
                        )
                        .clickable { onResize(fraction) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("image_size_${(fraction * 100).toInt()}_${imageBlock.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "S",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (abs(imageBlock.widthFraction - fraction) < 0.01f) BauhausColors.LightSurface else BauhausColors.Black
                    )
                }
            }
            listOf(
                BlockAlignment.LEFT to "L",
                BlockAlignment.CENTER to "C",
                BlockAlignment.RIGHT to "R"
            ).forEach { (align, label) ->
                val selected = imageBlock.alignment == align
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) BauhausColors.Black else BauhausColors.CardSurface)
                        .clickable { onAlign(align) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("image_align_${align.name}_${imageBlock.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) BauhausColors.LightSurface else BauhausColors.Black
                    )
                }
            }
            IconButton(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .size(32.dp)
                    .testTag("image_delete_${imageBlock.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove image",
                    tint = BauhausColors.Red,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = BauhausColors.CardSurface,
                title = { Text("Image hatayein?", style = MaterialTheme.typography.headlineMedium) },
                text = {
                    Text(
                        "Ye image note se hamesha ke liye hat jayegi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BauhausColors.SecondaryText
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; onDelete() }) {
                        Text("Remove", color = BauhausColors.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text("Cancel", color = BauhausColors.Black)
                    }
                }
            )
        }
}

@Composable
private fun ImageSizeDialog(
    imagePath: String,
    imageStore: InlineImageStore,
    widthFraction: Float,
    onChangeWidth: (Float) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = imagePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = imageStore.resolveForDisplay(imagePath)
                if (file != null) decodeSampledBitmap(file, maxDim = 1200) else null
            }.getOrNull()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            color = BauhausColors.CardSurface,
            shape = RoundedCornerShape(BauhausDimens.CornerRadius)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Image Size", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(10.dp))
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth(widthFraction)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Text(
                    text = "Width: ${(widthFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = BauhausColors.SecondaryText
                )
                Slider(
                    value = widthFraction,
                    onValueChange = onChangeWidth,
                    valueRange = 0.25f..1f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = BauhausColors.Red,
                        activeTrackColor = BauhausColors.Red,
                        inactiveTrackColor = BauhausColors.Divider
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BauhausOutlinedButton(
                        text = "Remove",
                        leadingIcon = Icons.Outlined.Delete,
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f)
                    )
                    BauhausPrimaryButton(
                        text = "Done",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    containerColor = BauhausColors.CardSurface,
                    title = { Text("Image hatayein?", style = MaterialTheme.typography.headlineMedium) },
                    text = {
                        Text(
                            "Ye image note se hamesha ke liye hat jayegi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BauhausColors.SecondaryText
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { confirmDelete = false; onDelete() }) {
                            Text("Remove", color = BauhausColors.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) {
                            Text("Cancel", color = BauhausColors.Black)
                        }
                    }
                )
            }
        }
    }
}
@Composable
private fun ToolbarVerticalTick() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(20.dp)
            .background(BauhausColors.Divider)
    )
}

@Composable
private fun EditorTextPillTool(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) BauhausColors.Black else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = if (active) BauhausColors.LightSurface else BauhausColors.Black
        )
    }
}

@Composable
private fun EditorIconTool(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    accentDotColor: Color? = null,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) BauhausColors.CardSurface else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) BauhausColors.Red else BauhausColors.Black,
            modifier = Modifier.size(20.dp)
        )
        if (accentDotColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentDotColor)
                    .border(0.75.dp, BauhausColors.Black, CircleShape)
            )
        }
    }
}

@Composable
fun VersionHistoryScreen(
    recordId: String,
    viewModel: RecordsViewModel,
    onBack: () -> Unit
) {
    val initialCached = remember(recordId) { viewModel.cachedRecord(recordId) }
    val record by viewModel.observeRecord(recordId).collectAsStateWithLifecycle(initialValue = initialCached)
    val versions by viewModel.observeVersions(recordId).collectAsStateWithLifecycle(initialValue = emptyList())
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()
    var versionToRestore by remember { mutableStateOf<RecordVersion?>(null) }
    var expandedVersionId by remember { mutableStateOf<String?>(null) }

    BauhausScreenScaffold(
        title = "History",
        titleStyle = MaterialTheme.typography.headlineMedium,
        subtitle = null,
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("history_screen_root"),
            contentPadding = PaddingValues(
                horizontal = BauhausDimens.PageHorizontalPadding,
                vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            errorMsg?.let { msg ->
                item(key = "history_err") {
                    BauhausErrorBanner(message = msg, onDismiss = viewModel::clearFeedback)
                }
            }

            record?.let { cur ->
                item(key = "vh_timestamps") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                            .testTag("version_history_record_dates")
                    ) {
                        Text(
                            text = cur.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = BauhausColors.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Created  ${DateFormatters.formatDateTime(cur.createdAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText
                        )
                        Text(
                            text = "Last modified  ${DateFormatters.formatDateTime(cur.modifiedAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = BauhausColors.SecondaryText
                        )
                    }
                }
            }

            item(key = "history_list_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = BauhausColors.Black
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(BauhausColors.CardSurface)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${versions.size} Versions",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = BauhausColors.Black,
                            modifier = Modifier.testTag("history_version_count")
                        )
                    }
                }
            }

            items(versions, key = { it.id }) { ver ->
                val isLatest = versions.firstOrNull()?.id == ver.id
                val expanded = expandedVersionId == ver.id
                val shape = RoundedCornerShape(BauhausDimens.SmallCornerRadius)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(BauhausColors.LightSurface)
                        .border(
                            width = if (isLatest) 1.4.dp else 1.dp,
                            color = if (isLatest) BauhausColors.Blue.copy(alpha = 0.55f) else BauhausColors.Divider,
                            shape = shape
                        )
                        .clickable { expandedVersionId = if (expanded) null else ver.id }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("history_version_card_${ver.versionNumber}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "v${ver.versionNumber}  •  ${DateFormatters.formatDateTime(ver.createdAt)}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = BauhausColors.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = DateFormatters.ellipsizeTitle(ver.title, 26),
                                style = MaterialTheme.typography.labelSmall,
                                color = BauhausColors.SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isLatest) {
                            BauhausTagChip(label = "Current", selected = true, accentColor = BauhausColors.Blue)
                        } else {
                            IconButton(
                                onClick = { versionToRestore = ver },
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                    .testTag("btn_history_restore_${ver.versionNumber}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = "Restore v${ver.versionNumber}",
                                    tint = BauhausColors.Blue
                                )
                            }
                        }
                    }

                    if (expanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = BauhausColors.Divider)
                        Spacer(modifier = Modifier.height(10.dp))
                        RichTextRenderedContent(markdown = ver.description, imageStore = viewModel.inlineImageStore)
                        if (!isLatest) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BauhausPrimaryButton(
                                text = "Restore Version v${ver.versionNumber}",
                                leadingIcon = Icons.Outlined.Restore,
                                onClick = { versionToRestore = ver },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    versionToRestore?.let { ver ->
        BauhausConfirmDialog(
            title = "Restore Version v${ver.versionNumber}?",
            message = "This version will be applied to your note and become the newest version. Your current version stays safe in History, so nothing is lost.",
            confirmLabel = "Restore",
            onDismiss = { versionToRestore = null },
            onConfirm = {
                viewModel.restoreVersion(recordId, ver.id) {
                    versionToRestore = null
                    onBack()
                }
            }
        )
    }
}
@Composable
fun AttachmentViewerScreen(
    attachmentId: String,
    viewModel: RecordsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()
    val errorMsg by viewModel.errorMessage.collectAsStateWithLifecycle()

    var attachment by remember { mutableStateOf<AttachmentItem?>(null) }
    var tempPlainFile by remember { mutableStateOf<File?>(null) }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var pdfPageIndex by remember { mutableIntStateOf(0) }
    var textPreview by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isPlayingAudio by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val mediaPlayerRef = remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { mediaPlayerRef.value?.release() }
            viewModel.purgeEphemeralViewerFiles()
        }
    }

    LaunchedEffect(attachmentId, pdfPageIndex) {
        isLoading = true
        loadError = null
        runCatching {
            val att = attachment ?: viewModel.loadAttachment(attachmentId)
                ?: error("Attachment metadata not found.")
            attachment = att
            val file = tempPlainFile ?: viewModel.decryptAttachmentToTempFile(attachmentId).also {
                tempPlainFile = it
            }

            withContext(Dispatchers.IO) {
                when (att.kind) {
                    AttachmentKind.IMAGE -> {
                        if (imageBitmap == null) {
                            imageBitmap = decodeSampledBitmap(file, maxDim = 1440)
                        }
                    }
                    AttachmentKind.PDF -> {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                pdfPageCount = renderer.pageCount
                                if (renderer.pageCount > 0) {
                                    val idx = pdfPageIndex.coerceIn(0, renderer.pageCount - 1)
                                    renderer.openPage(idx).use { page ->
                                        val bmp = Bitmap.createBitmap(
                                            page.width.coerceAtLeast(1),
                                            page.height.coerceAtLeast(1),
                                            Bitmap.Config.ARGB_8888
                                        )
                                        bmp.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        pdfPageBitmap = bmp
                                    }
                                }
                            }
                        }
                    }
                    AttachmentKind.DOCUMENT, AttachmentKind.OTHER -> {
                        if (att.mimeType.startsWith("text/") || att.plaintextSizeBytes <= 128 * 1024L) {
                            textPreview = runCatching {
                                file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText().take(8_000) }
                            }.getOrNull()
                        }
                    }
                    AttachmentKind.AUDIO -> Unit
                }
            }
        }.onFailure { err ->
            loadError = err.message ?: "Failed to verify and decrypt attachment."
        }
        isLoading = false
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        if (destUri != null) {
            viewModel.exportAttachmentToUri(context, attachmentId, destUri)
        }
    }

    BauhausScreenScaffold(
        title = attachment?.originalFileName ?: "Attachment",
        subtitle = attachment?.let { "${it.kind.name}  •  SHA-256 Verified" },
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete attachment", tint = BauhausColors.Red)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BauhausDimens.PageHorizontalPadding, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            loadError?.let { BauhausErrorBanner(message = it) }
            errorMsg?.let { BauhausErrorBanner(message = it, onDismiss = viewModel::clearFeedback) }
            statusMsg?.let {
                BauhausSurfaceCard { Text(it, style = MaterialTheme.typography.bodyMedium, color = BauhausColors.Blue) }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BauhausColors.Red)
                }
            } else {
                imageBitmap?.let { bmp ->
                    BauhausSurfaceCard(elevated = true) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = attachment?.originalFileName,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                pdfPageBitmap?.let { bmp ->
                    BauhausSurfaceCard(elevated = true) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "PDF page ${pdfPageIndex + 1}",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pdfPageCount > 1) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BauhausOutlinedButton(
                                    text = "Previous",
                                    enabled = pdfPageIndex > 0,
                                    onClick = { pdfPageIndex-- }
                                )
                                Text("Page ${pdfPageIndex + 1} / $pdfPageCount", style = MaterialTheme.typography.labelLarge)
                                BauhausOutlinedButton(
                                    text = "Next",
                                    enabled = pdfPageIndex < pdfPageCount - 1,
                                    onClick = { pdfPageIndex++ }
                                )
                            }
                        }
                    }
                }

                if (attachment?.kind == AttachmentKind.AUDIO && tempPlainFile != null) {
                    BauhausSurfaceCard(elevated = true) {
                        Text("Encrypted Audio Attachment", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(10.dp))
                        BauhausPrimaryButton(
                            text = if (isPlayingAudio) "Pause Playback" else "Play Audio",
                            leadingIcon = if (isPlayingAudio) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            onClick = {
                                val mp = mediaPlayerRef.value ?: MediaPlayer().apply {
                                    setDataSource(tempPlainFile!!.absolutePath)
                                    prepare()
                                    setOnCompletionListener { isPlayingAudio = false }
                                    mediaPlayerRef.value = this
                                }
                                if (mp.isPlaying) {
                                    mp.pause()
                                    isPlayingAudio = false
                                } else {
                                    mp.start()
                                    isPlayingAudio = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                textPreview?.takeIf { it.isNotBlank() }?.let { txt ->
                    BauhausSurfaceCard(elevated = true) {
                        Text(txt, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                attachment?.let { att ->
                    BauhausSurfaceCard {
                        Text("Cryptographic & File Metadata", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("MIME: ${att.mimeType}", style = MaterialTheme.typography.labelMedium)
                        Text("Original Size: ${DateFormatters.formatBytes(att.plaintextSizeBytes)}", style = MaterialTheme.typography.labelMedium)
                        Text("Encrypted Size: ${DateFormatters.formatBytes(att.encryptedSizeBytes)}", style = MaterialTheme.typography.labelMedium)
                        Text("SHA-256: ${att.plaintextSha256Hex.take(24)}…", style = MaterialTheme.typography.labelSmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BauhausPrimaryButton(
                            text = "Save Copy",
                            leadingIcon = Icons.Outlined.Download,
                            onClick = { saveCopyLauncher.launch(att.originalFileName) },
                            modifier = Modifier.weight(1f)
                        )
                        BauhausOutlinedButton(
                            text = "Share / Open",
                            leadingIcon = Icons.Outlined.Share,
                            onClick = {
                                scope.launch {
                                    val f = tempPlainFile ?: viewModel.decryptAttachmentToTempFile(att.id)
                                    val shareUri = viewModel.getShareUri(f)
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(shareUri, att.mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, att.originalFileName))
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        BauhausConfirmDialog(
            title = "Delete",
            message = "Delete attachment '${attachment?.originalFileName ?: "selected"}'?",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteAttachment(attachmentId, onBack)
            }
        )
    }
}

private sealed interface ReadingBlock {
    data class RichLine(
        val text: AnnotatedString,
        val isQuote: Boolean = false,
        val spans: List<RichSpan> = emptyList(),
        val plainLength: Int = 0,
        val alignment: BlockAlignment = BlockAlignment.LEFT
    ) : ReadingBlock
    data object SectionRule : ReadingBlock
    data object BlankSpacer : ReadingBlock
    data class NoteImage(
        val path: String,
        val widthFraction: Float = 1f,
        val alignment: BlockAlignment = BlockAlignment.LEFT
    ) : ReadingBlock
}

private fun parseRichReadingBlocks(rawStored: String): List<ReadingBlock> {
    if (rawStored.isBlank()) return emptyList()
    val blocks = RichNoteCodec.decodeBlocks(rawStored)
    val result = ArrayList<ReadingBlock>()

    blocks.forEach { block ->
        when (block) {
            is RichNoteBlock.Image -> {
                result.add(ReadingBlock.NoteImage(block.imagePath, block.widthFraction, block.alignment))
                result.add(ReadingBlock.BlankSpacer)
            }
            is RichNoteBlock.Text -> {
                if (block.text.isBlank()) return@forEach
                val spans = block.spans
                val annotated = RichNoteCodec.buildAnnotatedString(
                    block.text,
                    spans,
                    paintHighlightInline = false
                )
                var cursor = 0
                val lines = block.text.split('\n')
                lines.forEachIndexed { idx, line ->
                    val ls = cursor
                    val le = ls + line.length
                    cursor = le + if (idx < lines.lastIndex) 1 else 0

                    when {
                        line.isBlank() -> result.add(ReadingBlock.BlankSpacer)
                        line.trim() == "────────────────────" -> result.add(ReadingBlock.SectionRule)
                        else -> {
                            val sub = annotated.subSequence(ls, le)
                            val hasQuote = spans.any {
                                it.kind == RichSpanKind.QUOTE && it.start < le && it.end > ls
                            }
                            val lineSpans = spans.mapNotNull { sp ->
                                val st = maxOf(sp.start, ls)
                                val en = minOf(sp.end, le)
                                if (st < en) sp.copy(start = st - ls, end = en - ls) else null
                            }
                            result.add(
                                ReadingBlock.RichLine(
                                    text = sub,
                                    isQuote = hasQuote,
                                    spans = lineSpans,
                                    plainLength = line.length,
                                    alignment = paragraphAlignmentAt(block.text, ls, spans)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
    return result
}

@Composable
private fun ReadingBlockRow(block: ReadingBlock, imageStore: InlineImageStore) {
    val bodyReadingStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 26.sp,
        color = BauhausColors.Black
    )
    when (block) {
        is ReadingBlock.BlankSpacer -> Spacer(modifier = Modifier.height(6.dp))
        is ReadingBlock.NoteImage -> {
            val imageBitmapState by produceState<Bitmap?>(initialValue = null, key1 = block.path) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = imageStore.resolveForDisplay(block.path)
                        if (file != null) decodeSampledBitmap(file, maxDim = 1600) else null
                    }.getOrNull()
                }
            }
            val imageBitmap = imageBitmapState
            if (imageBitmap != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = when (block.alignment) {
                        BlockAlignment.LEFT -> Arrangement.Start
                        BlockAlignment.CENTER -> Arrangement.Center
                        BlockAlignment.RIGHT -> Arrangement.End
                    }
                ) {
                    Image(
                        bitmap = imageBitmap.asImageBitmap(),
                        contentDescription = "Note image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth(block.widthFraction.coerceIn(0.25f, 1.0f))
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
        }
        is ReadingBlock.SectionRule -> HorizontalDivider(
            thickness = 1.dp,
            color = BauhausColors.Divider,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        is ReadingBlock.RichLine -> {
            var lineLayout by remember(block) { mutableStateOf<TextLayoutResult?>(null) }
            val balancedMod = Modifier.drawBehind {
                drawBalancedHighlightSpans(
                    layoutResult = lineLayout,
                    textLength = block.plainLength,
                    spans = block.spans
                )
            }
            if (block.isQuote) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(24.dp)
                            .background(BauhausColors.Blue, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = block.text,
                        style = bodyReadingStyle.copy(textAlign = block.alignment.toTextAlign()),
                        onTextLayout = { lineLayout = it },
                        modifier = Modifier
                            .weight(1f)
                            .then(balancedMod)
                    )
                }
            } else {
                Text(
                    text = block.text,
                    style = bodyReadingStyle.copy(textAlign = block.alignment.toTextAlign()),
                    onTextLayout = { lineLayout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(balancedMod)
                )
            }
        }
    }
}

@Composable
fun RichTextRenderedContent(markdown: String, imageStore: InlineImageStore) {
    val blocks = remember(markdown) { parseRichReadingBlocks(markdown) }
    if (blocks.isEmpty()) {
        Text(
            text = "No Notes yet",
            style = MaterialTheme.typography.bodyMedium,
            color = BauhausColors.SecondaryText
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            ReadingBlockRow(block = block, imageStore = imageStore)
        }
    }
}

private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var inSampleSize = 1
    while (bounds.outWidth / inSampleSize > maxDim || bounds.outHeight / inSampleSize > maxDim) {
        inSampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteReminderDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val dateState = rememberDatePickerState()
    val timeState = rememberTimePickerState(is24Hour = true)
    var showTimePicker by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val selectedMillis = dateState.selectedDateMillis ?: System.currentTimeMillis()
    val selectedDate = Instant.ofEpochMilli(selectedMillis).atZone(zone).toLocalDate()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BauhausColors.CardSurface,
        title = { Text("Reminder", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 430.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ReminderChoiceChip(
                        label = DateFormatters.formatDate(selectedMillis),
                        selected = !showTimePicker,
                        onClick = { showTimePicker = false },
                        modifier = Modifier.weight(1f),
                        testTag = "reminder_date_chip"
                    )
                    ReminderChoiceChip(
                        label = "%02d:%02d".format(timeState.hour, timeState.minute),
                        selected = showTimePicker,
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                        testTag = "reminder_time_chip"
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (showTimePicker) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TimePicker(state = timeState)
                    }
                } else {
                    DatePicker(
                        state = dateState,
                        showModeToggle = false,
                        title = null,
                        headline = null
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val triggerAt = selectedDate
                        .atTime(timeState.hour, timeState.minute)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                    onConfirm(triggerAt)
                }
            ) { Text("Set", color = BauhausColors.Red) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = BauhausColors.Black) }
        }
    )
}

@Composable
private fun ReminderChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BauhausColors.Black else BauhausColors.LightSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) BauhausColors.LightSurface else BauhausColors.Black
        )
    }
}

