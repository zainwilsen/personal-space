package app.personalspace.vault.data.local.preferences

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "personal_space_preferences"
)

@Singleton
class UserPreferencesDataSource(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.userPrefsDataStore)

    private object Keys {
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
        val AUTO_SAVE_NOTES = booleanPreferencesKey("auto_save_notes")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val DEFAULT_SNOOZE_MIN = intPreferencesKey("default_snooze_minutes")
        val AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
        val LOCK_ON_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        val SECURE_TASK_SWITCHER = booleanPreferencesKey("secure_task_switcher")
        val AUTO_INCREMENTAL_SYNC = booleanPreferencesKey("auto_incremental_sync")
        val DAILY_BACKUP_ENABLED = booleanPreferencesKey("daily_backup_enabled")
        val DAILY_BACKUP_HOUR = intPreferencesKey("daily_backup_hour_24")
        val DAILY_BACKUP_MINUTE = intPreferencesKey("daily_backup_minute")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
    }

    val settingsFlow: Flow<UserSettings> = dataStore.data.map { prefs ->
        val sortStr = prefs[Keys.DEFAULT_SORT] ?: SortOption.RECENTLY_MODIFIED.name
        val sort = SortOption.entries.firstOrNull { it.name == sortStr } ?: SortOption.RECENTLY_MODIFIED
        UserSettings(
            defaultSortOrder = sort,
            autoSaveNotes = prefs[Keys.AUTO_SAVE_NOTES] ?: true,
            reducedMotion = prefs[Keys.REDUCED_MOTION] ?: false,
            defaultSnoozeMinutes = prefs[Keys.DEFAULT_SNOOZE_MIN] ?: 10,
            autoLockSeconds = prefs[Keys.AUTO_LOCK_SECONDS] ?: 300,
            lockOnScreenOff = prefs[Keys.LOCK_ON_SCREEN_OFF] ?: false,
            secureTaskSwitcher = prefs[Keys.SECURE_TASK_SWITCHER] ?: false,
            autoIncrementalSync = prefs[Keys.AUTO_INCREMENTAL_SYNC] ?: true,
            dailyBackupEnabled = prefs[Keys.DAILY_BACKUP_ENABLED] ?: false,
            dailyBackupHour24 = prefs[Keys.DAILY_BACKUP_HOUR] ?: 2,
            dailyBackupMinute = prefs[Keys.DAILY_BACKUP_MINUTE] ?: 0,
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: defaultDeviceName()
        )
    }

    suspend fun getOrCreateDeviceIdentity(): Pair<String, String> {
        val current = dataStore.data.first()
        val existingId = current[Keys.DEVICE_ID]
        val existingName = current[Keys.DEVICE_NAME]
        if (!existingId.isNullOrBlank() && !existingName.isNullOrBlank()) {
            return existingId to existingName
        }
        val newId = existingId?.takeIf { it.isNotBlank() } ?: IdGenerator.newUuid()
        val newName = existingName?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        dataStore.edit { prefs ->
            prefs[Keys.DEVICE_ID] = newId
            prefs[Keys.DEVICE_NAME] = newName
        }
        return newId to newName
    }

    suspend fun updateDefaultSort(sort: SortOption) {
        dataStore.edit { it[Keys.DEFAULT_SORT] = sort.name }
    }

    suspend fun updateAutoSaveNotes(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_SAVE_NOTES] = enabled }
    }

    suspend fun updateReducedMotion(enabled: Boolean) {
        dataStore.edit { it[Keys.REDUCED_MOTION] = enabled }
    }

    suspend fun updateDefaultSnoozeMinutes(minutes: Int) {
        dataStore.edit { it[Keys.DEFAULT_SNOOZE_MIN] = minutes.coerceIn(1, 240) }
    }

    suspend fun updateAutoLockSeconds(seconds: Int) {
        dataStore.edit { it[Keys.AUTO_LOCK_SECONDS] = seconds.coerceAtLeast(0) }
    }

    suspend fun updateLockOnScreenOff(enabled: Boolean) {
        dataStore.edit { it[Keys.LOCK_ON_SCREEN_OFF] = enabled }
    }

    suspend fun updateSecureTaskSwitcher(enabled: Boolean) {
        dataStore.edit { it[Keys.SECURE_TASK_SWITCHER] = enabled }
    }

    suspend fun updateAutoIncrementalSync(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_INCREMENTAL_SYNC] = enabled }
    }

    suspend fun updateDailyBackupSchedule(enabled: Boolean, hour24: Int, minute: Int) {
        dataStore.edit {
            it[Keys.DAILY_BACKUP_ENABLED] = enabled
            it[Keys.DAILY_BACKUP_HOUR] = hour24.coerceIn(0, 23)
            it[Keys.DAILY_BACKUP_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    private fun defaultDeviceName(): String {
        val mfr = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }.orEmpty()
        val model = Build.MODEL.orEmpty()
        val combined = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model".trim()
        return combined.ifBlank { "Android Device" }
    }
}
