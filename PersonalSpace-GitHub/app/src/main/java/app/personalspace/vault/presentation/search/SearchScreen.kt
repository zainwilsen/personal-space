package app.personalspace.vault.presentation.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausEmptyState
import app.personalspace.vault.core.designsystem.BauhausFolderIcon
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSectionHeader
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.designsystem.BauhausTagChip
import app.personalspace.vault.core.designsystem.BauhausTextField
import app.personalspace.vault.domain.model.AttachmentKind
import app.personalspace.vault.domain.model.Folder
import app.personalspace.vault.domain.model.SearchFilters
import app.personalspace.vault.domain.model.SearchResultsBundle
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PersonalSpaceRepository,
    private val timeProvider: TimeProvider
) : ViewModel() {

    val folders: StateFlow<List<Folder>> = repository.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<String>> = repository.observeDistinctCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _results = MutableStateFlow(
        SearchResultsBundle(emptyList(), emptyList(), emptyList())
    )
    val results: StateFlow<SearchResultsBundle> = _results.asStateFlow()

    fun executeSearch(
        query: String,
        folderId: String?,
        category: String?,
        attachmentKind: AttachmentKind?,
        daysBackFilter: Int?,
        sortBy: SortOption
    ) {
        viewModelScope.launch {
            val fromMillis = daysBackFilter?.let { days ->
                timeProvider.nowMillis() - days.toLong() * 24L * 60L * 60L * 1000L
            }
            _results.value = repository.searchVault(
                SearchFilters(
                    query = query,
                    folderId = folderId,
                    category = category,
                    attachmentKind = attachmentKind,
                    fromDateMillis = fromMillis,
                    sortBy = sortBy
                )
            )
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenAttachment: (String) -> Unit
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val bundle by viewModel.results.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var selectedFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAttachmentKind by rememberSaveable { mutableStateOf<AttachmentKind?>(null) }
    var selectedDaysBack by rememberSaveable { mutableStateOf<Int?>(null) }
    var sortBy by rememberSaveable { mutableStateOf(SortOption.RELEVANCE) }

    LaunchedEffect(query, selectedFolderId, selectedCategory, selectedAttachmentKind, selectedDaysBack, sortBy) {
        delay(120L)
        viewModel.executeSearch(
            query = query,
            folderId = selectedFolderId,
            category = selectedCategory,
            attachmentKind = selectedAttachmentKind,
            daysBackFilter = selectedDaysBack,
            sortBy = sortBy
        )
    }

    BauhausScreenScaffold(
        title = "Search",
        subtitle = "Offline FTS4 & Metadata Search",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showFilters = !showFilters }) {
                Icon(
                    imageVector = Icons.Outlined.FilterList,
                    contentDescription = "Toggle search filters",
                    tint = if (showFilters) BauhausColors.Red else BauhausColors.Black
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("search_screen_list"),
            contentPadding = PaddingValues(
                horizontal = BauhausDimens.PageHorizontalPadding,
                vertical = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                BauhausTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search titles, content, folders, categories, files…",
                    modifier = Modifier.testTag("global_search_input")
                )
            }

            if (showFilters) {
                item {
                    BauhausSurfaceCard {
                        Text("Sort By", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                SortOption.RELEVANCE,
                                SortOption.RECENTLY_MODIFIED,
                                SortOption.RECENTLY_CREATED,
                                SortOption.TITLE_ASC
                            ).forEach { opt ->
                                BauhausTagChip(
                                    label = opt.label,
                                    selected = sortBy == opt,
                                    onClick = { sortBy = opt }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Date Range", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(null to "Any time", 7 to "Last 7d", 30 to "Last 30d", 90 to "Last 90d", 365 to "Last year")
                                .forEach { (days, lbl) ->
                                    BauhausTagChip(
                                        label = lbl,
                                        selected = selectedDaysBack == days,
                                        accentColor = BauhausColors.Blue,
                                        onClick = { selectedDaysBack = days }
                                    )
                                }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Attachment Type", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BauhausTagChip(
                                label = "Any",
                                selected = selectedAttachmentKind == null,
                                accentColor = BauhausColors.Yellow,
                                onClick = { selectedAttachmentKind = null }
                            )
                            AttachmentKind.entries.forEach { kind ->
                                BauhausTagChip(
                                    label = kind.name,
                                    selected = selectedAttachmentKind == kind,
                                    accentColor = BauhausColors.Yellow,
                                    onClick = { selectedAttachmentKind = if (selectedAttachmentKind == kind) null else kind }
                                )
                            }
                        }

                        if (folders.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Folder", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BauhausTagChip(
                                    label = "All Folders",
                                    selected = selectedFolderId == null,
                                    accentColor = BauhausColors.Black,
                                    onClick = { selectedFolderId = null }
                                )
                                folders.forEach { f ->
                                    BauhausTagChip(
                                        label = f.name,
                                        selected = selectedFolderId == f.id,
                                        accentColor = BauhausColors.Black,
                                        onClick = { selectedFolderId = if (selectedFolderId == f.id) null else f.id }
                                    )
                                }
                            }
                        }

                        if (categories.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Category", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
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
                }
            }

            if (bundle.matchingFolders.isNotEmpty()) {
                item { BauhausSectionHeader(title = "Folders (${bundle.matchingFolders.size})") }
                items(bundle.matchingFolders, key = { "sf_${it.id}" }) { folder ->
                    BauhausSurfaceCard(
                        elevated = true,
                        onClick = { onOpenFolder(folder.id) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BauhausFolderIcon(accent = folder.color, size = 26.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(folder.name, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            item { BauhausSectionHeader(title = "Notes (${bundle.records.size})") }

            if (bundle.records.isEmpty()) {
                item {
                    BauhausEmptyState(
                        title = "No Matching Notes",
                        message = "Try adjusting your search keywords or active filters."
                    )
                }
            } else {
                items(bundle.records, key = { "sr_${it.id}" }) { rec ->
                    BauhausSurfaceCard(
                        elevated = true,
                        onClick = { onOpenRecord(rec.id) }
                    ) {
                        Text(rec.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${rec.folderName ?: "Unfiled"}  •  ${DateFormatters.formatDateTime(rec.modifiedAt)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (bundle.matchingAttachments.isNotEmpty()) {
                item { BauhausSectionHeader(title = "Attachments (${bundle.matchingAttachments.size})") }
                items(bundle.matchingAttachments, key = { "sa_${it.id}" }) { att ->
                    BauhausSurfaceCard(
                        elevated = true,
                        onClick = { onOpenAttachment(att.id) }
                    ) {
                        Text(att.originalFileName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${att.kind.name}  •  ${DateFormatters.formatBytes(att.plaintextSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(48.dp)) }
        }
    }
}
