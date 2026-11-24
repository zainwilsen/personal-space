package app.personalspace.vault.presentation.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.core.designsystem.BauhausActivityBarChart
import app.personalspace.vault.core.designsystem.BauhausBarPoint
import app.personalspace.vault.core.designsystem.BauhausCardArcCorner
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.BauhausDimens
import app.personalspace.vault.core.designsystem.BauhausScreenScaffold
import app.personalspace.vault.core.designsystem.BauhausSectionHeader
import app.personalspace.vault.core.designsystem.BauhausSummaryCard
import app.personalspace.vault.core.designsystem.BauhausSurfaceCard
import app.personalspace.vault.core.designsystem.BottomNavTab
import app.personalspace.vault.domain.model.DailyActivityMetric
import app.personalspace.vault.domain.model.DashboardOverview
import app.personalspace.vault.domain.model.ReminderItem
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.SyncHealthState
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.folders.RecordTitleDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private fun defaultSevenDayZeroSeries(): List<DailyActivityMetric> {
    val today = LocalDate.now()
    return (6 downTo 0).map { offset ->
        val d = today.minusDays(offset.toLong())
        DailyActivityMetric(
            dateLabel = DateFormatters.formatShortDay(d),
            fullDateLabel = DateFormatters.formatDayMonth(d),
            epochDay = d.toEpochDay(),
            createdCount = 0,
            modifiedCount = 0,
            foldersCreatedCount = 0,
            remindersCount = 0
        )
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: PersonalSpaceRepository
) : ViewModel() {

    val overview: StateFlow<DashboardOverview> = repository.observeDashboardOverview()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.latestCachedDashboardOverview() ?: DashboardOverview(
                totalRecords = 0,
                totalFolders = 0,
                pendingReminders = 0,
                syncState = SyncHealthState.NOT_CONNECTED,
                lastBackupMillis = null,
                recentDailyActivity = defaultSevenDayZeroSeries(),
                recentRecords = emptyList(),
                upcomingReminders = emptyList(),
                recentTimeline = emptyList(),
                unresolvedConflicts = 0
            )
        )

    fun refreshMissedReminders() {
        viewModelScope.launch {
            repository.refreshMissedReminders()
        }
    }

    fun createRecordTitleOnly(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.saveRecord(
                    existingRecordId = null,
                    title = title,
                    description = "",
                    folderId = null,
                    category = null,
                    customFields = emptyList()
                )
            }.onSuccess { created ->
                onCreated(created.id)
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onSelectBottomTab: (BottomNavTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAllRecords: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onCreateRecord: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenReminders: () -> Unit,
    onOpenReminder: (String) -> Unit,
    onOpenBackupSync: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenConflicts: () -> Unit,
    referenceNowMillis: Long? = null
) {
    val state by viewModel.overview.collectAsStateWithLifecycle()
    var selectedBarIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val barTappedThisGesture = remember { mutableStateOf(false) }

    val liveNowMillis by produceState(initialValue = referenceNowMillis ?: System.currentTimeMillis(), referenceNowMillis) {
        if (referenceNowMillis != null) {
            value = referenceNowMillis
        } else {
            while (true) {
                value = System.currentTimeMillis()
                delay(30_000L)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshMissedReminders()
    }

    val chartPoints = if (state.recentDailyActivity.isEmpty()) {
        defaultSevenDayZeroSeries()
    } else {
        state.recentDailyActivity
    }.map {
        BauhausBarPoint(
            label = it.dateLabel,
            count = it.totalCount,
            fullDateLabel = it.fullDateLabel,
            recordsAdded = it.createdCount,
            foldersCreated = it.foldersCreatedCount,
            remindersCount = it.remindersCount
        )
    }

    val currentSelectedIndex by androidx.compose.runtime.rememberUpdatedState(selectedBarIndex)
    val tapAnywhereToDismissModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            barTappedThisGesture.value = false
            val startPos = down.position
            var movedBeyondSlop = false
            var upChange: PointerInputChange? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                    movedBeyondSlop = true
                }
                if (!change.pressed) {
                    upChange = change
                    break
                }
            }
            if (currentSelectedIndex != null && upChange != null && !movedBeyondSlop && !barTappedThisGesture.value) {
                selectedBarIndex = null
            }
            barTappedThisGesture.value = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(tapAnywhereToDismissModifier)
            .testTag("dashboard_root_container")
    ) {
        BauhausScreenScaffold(
            title = "Dashboard",
            activeTab = BottomNavTab.HOME,
            onSelectTab = onSelectBottomTab,
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onCreateRecord,
                    containerColor = BauhausColors.Red,
                    contentColor = BauhausColors.LightSurface,
                    modifier = Modifier.testTag("dashboard_fab_new_record")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Create new note")
                }
            }
        ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("dashboard_scroll_list"),
            contentPadding = PaddingValues(
                start = BauhausDimens.PageHorizontalPadding,
                end = BauhausDimens.PageHorizontalPadding,
                top = 8.dp,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(BauhausDimens.SectionSpacing)
        ) {
            if (state.unresolvedConflicts > 0) {
                item(key = "conflict_alert") {
                    BauhausSurfaceCard(
                        elevated = true,
                        onClick = onOpenConflicts
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = BauhausColors.Red
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${state.unresolvedConflicts} Synchronization Conflict(s)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = BauhausColors.Red
                                )
                                Text(
                                    text = "Both versions are safely preserved. Tap to review and merge.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BauhausColors.SecondaryText
                                )
                            }
                        }
                    }
                }
            }

            item(key = "summary_grid") {
                Column(verticalArrangement = Arrangement.spacedBy(BauhausDimens.CardSpacing)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BauhausDimens.CardSpacing)
                    ) {
                        BauhausSummaryCard(
                            icon = Icons.Outlined.Description,
                            label = "Total Notes",
                            value = state.totalRecords.toString(),
                            accentColor = BauhausColors.Red,
                            arcCorner = BauhausCardArcCorner.TOP_RIGHT,
                            onClick = onOpenAllRecords,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("card_total_records")
                        )
                        BauhausSummaryCard(
                            icon = Icons.Outlined.Folder,
                            label = "Total Folders",
                            value = state.totalFolders.toString(),
                            accentColor = BauhausColors.Yellow,
                            arcCorner = BauhausCardArcCorner.BOTTOM_RIGHT,
                            onClick = onOpenFolders,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("card_total_folders")
                        )
                    }

                    BauhausSummaryCard(
                        icon = Icons.Outlined.Notifications,
                        label = "Pending Reminders",
                        value = state.pendingReminders.toString(),
                        accentColor = BauhausColors.Blue,
                        arcCorner = BauhausCardArcCorner.BOTTOM_RIGHT,
                        onClick = onOpenReminders,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("card_pending_reminders")
                    )
                }
            }

            item(key = "activity_chart") {
                val cardShape = RoundedCornerShape(BauhausDimens.CornerRadius)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, BauhausColors.Divider), cardShape)
                        .clip(cardShape)
                        .testTag("card_recent_activities"),
                    color = BauhausColors.CardSurface,
                    shape = cardShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent Activities",
                                style = MaterialTheme.typography.titleLarge,
                                color = BauhausColors.Black
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BauhausColors.LightSurface)
                                    .border(1.dp, BauhausColors.Divider, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Last 7 Days",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = BauhausColors.SecondaryText
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        BauhausActivityBarChart(
                            points = chartPoints,
                            selectedIndex = selectedBarIndex,
                            onSelectBar = { tappedIdx ->
                                barTappedThisGesture.value = true
                                selectedBarIndex = if (selectedBarIndex == tappedIdx) null else tappedIdx
                            }
                        )

                        val activePoint = selectedBarIndex?.let { chartPoints.getOrNull(it) }
                        AnimatedVisibility(
                            visible = activePoint != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            activePoint?.let { point ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(BauhausColors.LightSurface)
                                        .border(1.dp, BauhausColors.Divider, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                        .testTag("selected_activity_breakdown")
                                ) {
                                    Text(
                                        text = point.fullDateLabel,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = BauhausColors.Black
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = BauhausColors.Divider)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ActivityMetricPill(
                                            dotColor = BauhausColors.Red,
                                            label = "Notes",
                                            count = point.recordsAdded
                                        )
                                        ActivityMetricPill(
                                            dotColor = BauhausColors.Yellow,
                                            label = "Folders",
                                            count = point.foldersCreated
                                        )
                                        ActivityMetricPill(
                                            dotColor = BauhausColors.Blue,
                                            label = "Reminders",
                                            count = point.remindersCount
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.upcomingReminders.isNotEmpty()) {
                item(key = "upcoming_reminders_tags") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("upcoming_reminders_tag_section"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BauhausSectionHeader(title = "Upcoming Reminders")
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            state.upcomingReminders.forEachIndexed { index, reminder ->
                                BauhausReminderLineTag(
                                    reminder = reminder,
                                    colorIndex = index,
                                    nowMillis = liveNowMillis,
                                    onClick = { onOpenReminder(reminder.id) }
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

@Composable
private fun ActivityMetricPill(
    dotColor: Color,
    label: String,
    count: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = BauhausColors.Black
        )
    }
}

@Composable
private fun BauhausReminderLineTag(
    reminder: ReminderItem,
    colorIndex: Int,
    nowMillis: Long,
    onClick: () -> Unit
) {
    val accent = when (reminder.priority) {
        ReminderPriority.HIGH -> BauhausColors.Red
        ReminderPriority.MEDIUM -> BauhausColors.Blue
        ReminderPriority.LOW -> if (colorIndex % 2 == 0) BauhausColors.Yellow else BauhausColors.Blue
    }
    val remainingText = DateFormatters.formatReminderRemaining(
        targetMillis = reminder.effectiveTriggerMillis,
        nowMillis = nowMillis
    )
    val isNoteLinked = !reminder.linkedRecordId.isNullOrBlank()
    val iconTint = if (accent == BauhausColors.Yellow) BauhausColors.Black else accent
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .background(BauhausColors.CardSurface)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("reminder_tag_${reminder.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(BauhausColors.LightSurface)
                    .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isNoteLinked) Icons.Outlined.Description else Icons.Outlined.Alarm,
                    contentDescription = if (isNoteLinked) "Note reminder" else "Alarm reminder",
                    tint = iconTint,
                    modifier = Modifier
                        .size(18.dp)
                        .testTag(
                            if (isNoteLinked) "reminder_note_icon_${reminder.id}"
                            else "reminder_alarm_icon_${reminder.id}"
                        )
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = DateFormatters.ellipsizeTitle(reminder.title, 24),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BauhausColors.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(BauhausColors.LightSurface)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = remainingText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BauhausColors.Black,
                maxLines = 1
            )
        }
    }
}
