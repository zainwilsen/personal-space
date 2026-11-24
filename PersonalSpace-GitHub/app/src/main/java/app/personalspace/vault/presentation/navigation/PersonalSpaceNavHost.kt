package app.personalspace.vault.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.personalspace.vault.core.designsystem.BottomNavTab
import app.personalspace.vault.core.designsystem.LocalReducedMotion
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.domain.repository.PersonalSpaceRepository
import app.personalspace.vault.presentation.dashboard.DashboardScreen
import app.personalspace.vault.presentation.dashboard.DashboardViewModel
import app.personalspace.vault.presentation.folders.FolderDetailsScreen
import app.personalspace.vault.presentation.folders.FoldersScreen
import app.personalspace.vault.presentation.folders.FoldersViewModel
import app.personalspace.vault.presentation.launch.LaunchAnimationScreen
import app.personalspace.vault.presentation.records.AllRecordsScreen
import app.personalspace.vault.presentation.records.AttachmentViewerScreen
import app.personalspace.vault.presentation.records.RecordDetailScreen
import app.personalspace.vault.presentation.records.RecordEditorScreen
import app.personalspace.vault.presentation.records.RecordsViewModel
import app.personalspace.vault.presentation.records.VersionHistoryScreen
import app.personalspace.vault.presentation.search.SearchScreen
import app.personalspace.vault.presentation.search.SearchViewModel
import app.personalspace.vault.presentation.settings.AboutScreen
import app.personalspace.vault.presentation.settings.BackupSyncScreen
import app.personalspace.vault.presentation.settings.RestoreBackupScreen
import app.personalspace.vault.presentation.settings.GeneralSettingsScreen
import app.personalspace.vault.presentation.settings.OrphanQuarantineScreen
import app.personalspace.vault.presentation.settings.SettingsHomeScreen
import app.personalspace.vault.presentation.settings.SettingsViewModel
import app.personalspace.vault.presentation.timeline.RecycleBinScreen
import app.personalspace.vault.presentation.timeline.RecycleBinViewModel
import app.personalspace.vault.presentation.timeline.StatisticsScreen
import app.personalspace.vault.presentation.timeline.StatisticsViewModel
import app.personalspace.vault.presentation.timeline.TimelineScreen
import app.personalspace.vault.presentation.timeline.TimelineViewModel

@Composable
fun PersonalSpaceNavHost(
    sessionManager: VaultSessionManager,
    repository: PersonalSpaceRepository? = null,
    navController: NavHostController = rememberNavController()
) {
    remember(sessionManager) {
        sessionManager.ensureAutoUnlocked()
        true
    }

    val reducedMotion = LocalReducedMotion.current
    val navDuration = if (reducedMotion) 0 else 240

    val startDest = remember {
        if (!sessionManager.hasPlayedColdLaunchAnimation) {
            ScreenRoutes.LAUNCH
        } else {
            ScreenRoutes.DASHBOARD
        }
    }

    fun selectBottomTab(tab: BottomNavTab) {
        navController.navigate(tab.route) {
            popUpTo(ScreenRoutes.DASHBOARD) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun navigateToDashboardAfterUnlock() {
        navController.navigate(ScreenRoutes.DASHBOARD) {
            popUpTo(0) { inclusive = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDest,
        enterTransition = { fadeIn(animationSpec = tween(navDuration)) },
        exitTransition = { fadeOut(animationSpec = tween(navDuration)) },
        popEnterTransition = { fadeIn(animationSpec = tween(navDuration)) },
        popExitTransition = { fadeOut(animationSpec = tween(navDuration)) }
    ) {
        composable(ScreenRoutes.LAUNCH) {
            LaunchAnimationScreen(
                preloadTask = {
                    sessionManager.ensureAutoUnlocked()
                    repository?.preloadStartupData()
                },
                onFinished = {
                    sessionManager.hasPlayedColdLaunchAnimation = true
                    sessionManager.ensureAutoUnlocked()
                    navController.navigate(ScreenRoutes.DASHBOARD) {
                        popUpTo(ScreenRoutes.LAUNCH) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = ScreenRoutes.DASHBOARD,
            enterTransition = {
                fadeIn(tween(navDuration)) + scaleIn(
                    initialScale = if (reducedMotion) 1f else 0.985f,
                    animationSpec = tween(navDuration)
                )
            }
        ) {
            val vm: DashboardViewModel = hiltViewModel()
            DashboardScreen(
                viewModel = vm,
                onSelectBottomTab = ::selectBottomTab,
                onOpenSearch = { navController.navigate(ScreenRoutes.SEARCH) },
                onOpenAllRecords = { navController.navigate(ScreenRoutes.ALL_RECORDS) },
                onOpenRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) },
                onCreateRecord = { navController.navigate(ScreenRoutes.recordEditorNew()) },
                onOpenFolders = { selectBottomTab(BottomNavTab.FOLDERS) },
                onOpenFolder = { navController.navigate(ScreenRoutes.folderDetails(it)) },
                onOpenReminders = {},
                onOpenReminder = {},
                onOpenBackupSync = { navController.navigate(ScreenRoutes.SETTINGS_BACKUP_SYNC) },
                onOpenTimeline = { navController.navigate(ScreenRoutes.TIMELINE) },
                onOpenConflicts = {}
            )
        }

        composable(ScreenRoutes.FOLDERS) {
            val vm: FoldersViewModel = hiltViewModel()
            FoldersScreen(
                viewModel = vm,
                onSelectBottomTab = ::selectBottomTab,
                onOpenFolder = { navController.navigate(ScreenRoutes.folderDetails(it)) },
                onOpenAllRecords = { navController.navigate(ScreenRoutes.ALL_RECORDS) }
            )
        }

        composable(
            route = ScreenRoutes.FOLDER_DETAILS,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { entry ->
            val folderId = entry.arguments?.getString("folderId").orEmpty()
            val vm: FoldersViewModel = hiltViewModel()
            FolderDetailsScreen(
                folderId = folderId,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSubfolder = { navController.navigate(ScreenRoutes.folderDetails(it)) },
                onOpenRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) },
                onCreateRecordInFolder = { navController.navigate(ScreenRoutes.recordEditorNew(it)) },
                onEditRecord = { navController.navigate(ScreenRoutes.recordEditorEdit(it)) },
                onOpenVersionHistory = { navController.navigate(ScreenRoutes.versionHistory(it)) },
                onAddLinkedReminder = {},
                onOpenDuplicatedRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) }
            )
        }

        composable(ScreenRoutes.ALL_RECORDS) {
            val vm: RecordsViewModel = hiltViewModel()
            AllRecordsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) },
                onCreateRecord = { navController.navigate(ScreenRoutes.recordEditorNew()) },
                onEditRecord = { navController.navigate(ScreenRoutes.recordEditorEdit(it)) },
                onOpenVersionHistory = { navController.navigate(ScreenRoutes.versionHistory(it)) },
                onAddLinkedReminder = {},
                onOpenDuplicatedRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) }
            )
        }

        composable(ScreenRoutes.SEARCH) {
            val vm: SearchViewModel = hiltViewModel()
            SearchScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) },
                onOpenFolder = { navController.navigate(ScreenRoutes.folderDetails(it)) },
                onOpenAttachment = { navController.navigate(ScreenRoutes.attachmentViewer(it)) }
            )
        }

        composable(
            route = ScreenRoutes.RECORD_DETAILS,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            val vm: RecordsViewModel = hiltViewModel()
            RecordDetailScreen(
                recordId = recordId,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onEditRecord = { navController.navigate(ScreenRoutes.recordEditorEdit(it)) },
                onOpenVersionHistory = { navController.navigate(ScreenRoutes.versionHistory(it)) },
                onOpenAttachment = { navController.navigate(ScreenRoutes.attachmentViewer(it)) },
                onOpenFolder = { navController.navigate(ScreenRoutes.folderDetails(it)) },
                onAddLinkedReminder = {},
                onOpenDuplicatedRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) }
            )
        }

        composable(
            route = ScreenRoutes.RECORD_EDITOR_NEW,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType; nullable = true; defaultValue = null })
        ) { entry ->
            val folderId = entry.arguments?.getString("folderId")
            val vm: RecordsViewModel = hiltViewModel()
            RecordEditorScreen(
                existingRecordId = null,
                initialFolderId = folderId,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSaved = { savedId ->
                    navController.navigate(ScreenRoutes.recordDetails(savedId)) {
                        popUpTo(ScreenRoutes.RECORD_EDITOR_NEW) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = ScreenRoutes.RECORD_EDITOR_EDIT,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            val vm: RecordsViewModel = hiltViewModel()
            RecordEditorScreen(
                existingRecordId = recordId,
                initialFolderId = null,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenVersionHistory = { navController.navigate(ScreenRoutes.versionHistory(it)) },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route = ScreenRoutes.VERSION_HISTORY,
            arguments = listOf(navArgument("recordId") { type = NavType.StringType })
        ) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            val vm: RecordsViewModel = hiltViewModel()
            VersionHistoryScreen(
                recordId = recordId,
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = ScreenRoutes.ATTACHMENT_VIEWER,
            arguments = listOf(navArgument("attachmentId") { type = NavType.StringType })
        ) { entry ->
            val attId = entry.arguments?.getString("attachmentId").orEmpty()
            val vm: RecordsViewModel = hiltViewModel()
            AttachmentViewerScreen(
                attachmentId = attId,
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(ScreenRoutes.TIMELINE) {
            val vm: TimelineViewModel = hiltViewModel()
            TimelineScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenRecord = { navController.navigate(ScreenRoutes.recordDetails(it)) },
                onOpenFolder = { navController.navigate(ScreenRoutes.folderDetails(it)) }
            )
        }

        composable(ScreenRoutes.STATISTICS) {
            val vm: StatisticsViewModel = hiltViewModel()
            StatisticsScreen(
                viewModel = vm,
                onSelectBottomTab = ::selectBottomTab
            )
        }

        composable(ScreenRoutes.RECYCLE_BIN) {
            val vm: RecycleBinViewModel = hiltViewModel()
            RecycleBinScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(ScreenRoutes.SETTINGS) {
            val vm: SettingsViewModel = hiltViewModel()
            SettingsHomeScreen(
                viewModel = vm,
                onSelectBottomTab = ::selectBottomTab,
                onNavigate = { navController.navigate(it) }
            )
        }
        composable(ScreenRoutes.SETTINGS_GENERAL) {
            GeneralSettingsScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
        }
        composable(ScreenRoutes.SETTINGS_BACKUP_SYNC) {
            BackupSyncScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onNavigate = { navController.navigate(it) }
            )
        }
        composable(ScreenRoutes.SETTINGS_RESTORE_BACKUP) {
            RestoreBackupScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onRestored = ::navigateToDashboardAfterUnlock
            )
        }
        composable(ScreenRoutes.SETTINGS_ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(ScreenRoutes.SETTINGS_QUARANTINE) {
            OrphanQuarantineScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
