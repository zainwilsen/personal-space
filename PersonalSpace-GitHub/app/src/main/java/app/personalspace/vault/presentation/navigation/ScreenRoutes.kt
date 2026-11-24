package app.personalspace.vault.presentation.navigation

import android.net.Uri

object ScreenRoutes {
    const val LAUNCH = "launch"

    const val DASHBOARD = "dashboard"
    const val FOLDERS = "folders"
    const val FOLDER_DETAILS = "folder_details/{folderId}"
    const val ALL_RECORDS = "all_records"
    const val SEARCH = "search"
    const val RECORD_DETAILS = "record_details/{recordId}"
    const val RECORD_EDITOR_NEW = "record_editor_new?folderId={folderId}"
    const val RECORD_EDITOR_EDIT = "record_editor_edit/{recordId}"
    const val ATTACHMENT_VIEWER = "attachment_viewer/{attachmentId}"
    const val TIMELINE = "timeline"
    const val STATISTICS = "statistics"
    const val VERSION_HISTORY = "version_history/{recordId}"
    const val RECYCLE_BIN = "recycle_bin"

    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings_general"
    const val SETTINGS_BACKUP_SYNC = "settings_backup_sync"
    const val SETTINGS_RESTORE_BACKUP = "settings_restore_backup"
    const val SETTINGS_ABOUT = "settings_about"
    const val SETTINGS_QUARANTINE = "settings_quarantine"

    fun folderDetails(folderId: String): String = "folder_details/${Uri.encode(folderId)}"
    fun recordDetails(recordId: String): String = "record_details/${Uri.encode(recordId)}"
    fun recordEditorNew(folderId: String? = null): String =
        if (folderId != null) "record_editor_new?folderId=${Uri.encode(folderId)}" else "record_editor_new"
    fun recordEditorEdit(recordId: String): String = "record_editor_edit/${Uri.encode(recordId)}"
    fun attachmentViewer(attachmentId: String): String = "attachment_viewer/${Uri.encode(attachmentId)}"
    fun versionHistory(recordId: String): String = "version_history/${Uri.encode(recordId)}"
}
