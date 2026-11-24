package app.personalspace.vault.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.personalspace.vault.core.security.RecoveryGate
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.local.dao.AttachmentDao
import app.personalspace.vault.data.local.dao.BackupDao
import app.personalspace.vault.data.local.dao.FolderDao
import app.personalspace.vault.data.local.dao.RecordDao
import app.personalspace.vault.data.local.dao.RecycleBinDao
import app.personalspace.vault.data.local.dao.ReminderDao
import app.personalspace.vault.data.local.dao.SyncDao
import app.personalspace.vault.data.local.dao.TimelineDao
import app.personalspace.vault.data.local.dao.VaultMetadataDao
import app.personalspace.vault.data.local.entity.AttachmentEntity
import app.personalspace.vault.data.local.entity.BackupMetadataEntity
import app.personalspace.vault.data.local.entity.ConflictRecordEntity
import app.personalspace.vault.data.local.entity.DeletedItemEntity
import app.personalspace.vault.data.local.entity.FolderEntity
import app.personalspace.vault.data.local.entity.RecordDraftEntity
import app.personalspace.vault.data.local.entity.RecordEntity
import app.personalspace.vault.data.local.entity.RecordFtsEntity
import app.personalspace.vault.data.local.entity.RecordVersionEntity
import app.personalspace.vault.data.local.entity.ReminderEntity
import app.personalspace.vault.data.local.entity.ReminderOccurrenceEntity
import app.personalspace.vault.data.local.entity.SyncDeviceEntity
import app.personalspace.vault.data.local.entity.SyncMetadataEntity
import app.personalspace.vault.data.local.entity.SyncOperationEntity
import app.personalspace.vault.data.local.entity.TimelineEventEntity
import app.personalspace.vault.data.local.entity.VaultMetadataEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Database(
    entities = [
        VaultMetadataEntity::class,
        FolderEntity::class,
        RecordEntity::class,
        RecordFtsEntity::class,
        RecordDraftEntity::class,
        RecordVersionEntity::class,
        AttachmentEntity::class,
        ReminderEntity::class,
        ReminderOccurrenceEntity::class,
        TimelineEventEntity::class,
        DeletedItemEntity::class,
        SyncDeviceEntity::class,
        SyncOperationEntity::class,
        SyncMetadataEntity::class,
        ConflictRecordEntity::class,
        BackupMetadataEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class PersonalSpaceDatabase : RoomDatabase() {
    abstract fun vaultMetadataDao(): VaultMetadataDao
    abstract fun folderDao(): FolderDao
    abstract fun recordDao(): RecordDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun timelineDao(): TimelineDao
    abstract fun recycleBinDao(): RecycleBinDao
    abstract fun syncDao(): SyncDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val DB_NAME = "personal_space_encrypted.db"

        /**
         * Explicit forward migration v1 -> v2 ensuring schema upgrades preserve user records
         * and rebuild FTS4 search indices safely.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO records_fts(records_fts) VALUES('rebuild')")
            }
        }
    }
}

/**
 * Dynamically opens and closes the SQLCipher-encrypted Room database bound to the active
 * VaultSessionManager 256-bit SQLCipher key. When the vault locks, the database connection
 * closes immediately so decrypted page caches do not linger.
 */
@Singleton
class VaultDatabaseHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: VaultSessionManager
) {
    private val lock = Any()
    private var activeDb: PersonalSpaceDatabase? = null
    private var activeVaultId: String? = null

    /**
     * Optional override used strictly in host-JVM Robolectric tests where native Android ARM/x86
     * libsqlcipher.so is not loaded on Linux JVM. Production Android runtime always uses SQLCipher.
     */
    var testOpenHelperFactoryOverride: ((ByteArray) -> SupportSQLiteOpenHelper.Factory?)? = null

    init {
        sessionManager.setOnVaultLockedListener {
            closeDatabase()
        }
    }

    fun getDatabase(): PersonalSpaceDatabase {
        // P3.1: recovery gate. Refusing here is what makes "no database read or write while
        // blocked" true at the root, instead of at every DAO call site.
        if (RecoveryGate.isBlocked()) {
            throw RecoveryGate.RecoveryBlockedException.fromGate()
        }
        synchronized(lock) {
            val subKeys = sessionManager.requireSubKeys()
            val existing = activeDb
            if (existing != null && existing.isOpen && activeVaultId == subKeys.vaultId) {
                return existing
            }
            existing?.close()

            val passphraseCopy = subKeys.sqlCipherDbKey.copyOf()
            val helperFactory: SupportSQLiteOpenHelper.Factory? =
                testOpenHelperFactoryOverride?.invoke(passphraseCopy) ?: run {
                    System.loadLibrary("sqlcipher")
                    SupportOpenHelperFactory(passphraseCopy)
                }

            val builder = Room.databaseBuilder(
                context,
                PersonalSpaceDatabase::class.java,
                PersonalSpaceDatabase.DB_NAME
            )
                .addMigrations(PersonalSpaceDatabase.MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })

            if (helperFactory != null) {
                builder.openHelperFactory(helperFactory)
            }

            val built = builder.build()
            activeDb = built
            activeVaultId = subKeys.vaultId
            return built
        }
    }

    fun closeDatabase() {
        synchronized(lock) {
            runCatching { activeDb?.close() }
            activeDb = null
            activeVaultId = null
        }
    }

    fun databaseFile(): File = context.getDatabasePath(PersonalSpaceDatabase.DB_NAME)

    /**
     * Runs PRAGMA integrity_check against the open encrypted database.
     */
    fun verifyDatabaseIntegrity(): Boolean {
        // P3.1: `getDatabase()` inside the runCatching below would swallow the gate exception and
        // report `false`, which reads like "database corrupt" instead of "access blocked". Fail
        // loudly and distinguishably instead.
        if (RecoveryGate.isBlocked()) {
            throw RecoveryGate.RecoveryBlockedException.fromGate()
        }
        return runCatching {
            val db = getDatabase().openHelper.writableDatabase
            db.query("PRAGMA integrity_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0).equals("ok", ignoreCase = true)
                } else {
                    false
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Securely deletes all local database files (main db, wal, shm) during atomic vault replacement.
     */
    fun wipeDatabaseFiles() {
        closeDatabase()
        val mainFile = databaseFile()
        listOf(
            mainFile,
            File(mainFile.path + "-wal"),
            File(mainFile.path + "-shm"),
            File(mainFile.path + "-journal")
        ).forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }
}
