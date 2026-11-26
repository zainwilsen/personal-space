package app.personalspace.vault.data

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.designsystem.FolderAccentColor
import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import app.personalspace.vault.data.local.entity.ReminderEntity
import app.personalspace.vault.data.repository.PersonalSpaceRepositoryImpl
import app.personalspace.vault.domain.model.AttachmentKind
import app.personalspace.vault.domain.model.CustomField
import app.personalspace.vault.domain.model.CustomFieldType
import app.personalspace.vault.domain.model.RecordDraft
import app.personalspace.vault.domain.model.RecurrenceType
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.ReminderStatus
import app.personalspace.vault.domain.model.SearchFilters
import app.personalspace.vault.domain.model.SortOption
import app.personalspace.vault.domain.model.SyncHealthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryAndDatabaseIntegrationTest {

    private lateinit var rig: IsolatedVaultTestRig

    @Before
    fun setUp() {
        rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "repo_integration")
        rig.initializeAndUnlockVault()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    @Test
    fun `freshly initialized vault emits genuine zero metrics on Dashboard and Statistics with zero dummy data`() = runBlocking {
        val dash = rig.repository.observeDashboardOverview().first()
        assertEquals(0, dash.totalRecords)
        assertEquals(0, dash.totalFolders)
        assertEquals(0, dash.pendingReminders)
        assertEquals(SyncHealthState.NOT_CONNECTED, dash.syncState)
        assertNull(dash.lastBackupMillis)
        assertEquals(7, dash.recentDailyActivity.size)
        assertTrue(dash.recentDailyActivity.all { it.totalCount == 0 })
        assertTrue(dash.recentRecords.isEmpty())
        assertTrue(dash.upcomingReminders.isEmpty())

        val stats = rig.repository.observeVaultStatistics().first()
        assertEquals(0, stats.totalRecords)
        assertEquals(0, stats.totalFolders)
        assertEquals(0, stats.totalAttachments)
        assertEquals(0L, stats.encryptedAttachmentsBytes)
        assertEquals(0, stats.completedRemindersCount)
        assertEquals(0, stats.pendingRemindersCount)
        assertEquals(0, stats.missedRemindersCount)
    }

    @Test
    fun `multi-level folder hierarchy supports arbitrary nesting breadcrumbs and blocks self or descendant cycles`() = runBlocking {
        val root = rig.repository.createFolder("Legal & Identity", null, FolderAccentColor.RED)
        val sub1 = rig.repository.createFolder("Passports", root.id, FolderAccentColor.BLUE)
        val sub2 = rig.repository.createFolder("2026 Renewals", sub1.id, FolderAccentColor.YELLOW)

        val crumbs = rig.repository.getFolderBreadcrumbs(sub2.id)
        assertEquals(listOf("Legal & Identity", "Passports", "2026 Renewals"), crumbs.map { it.name })

        // Self-parenting cycle check
        try {
            rig.repository.moveFolder(sub1.id, sub1.id)
            fail("Expected IllegalArgumentException when moving folder into itself")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // Ancestor-into-descendant cycle check (moving root into sub2)
        try {
            rig.repository.moveFolder(root.id, sub2.id)
            fail("Expected IllegalArgumentException when moving ancestor folder into its own descendant")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `record drafts version history deduplication and non-destructive restore work end-to-end`() = runBlocking {
        // 1. Save crash-safe draft
        val draft = RecordDraft(
            draftKey = "NEW_RECORD",
            folderId = null,
            title = "Draft Insurance Policy",
            description = "**Policy** notes in progress",
            category = "Finance",
            customFields = listOf(CustomField("cf1", "Policy No", CustomFieldType.TEXT, "POL-8891")),
            updatedAt = rig.clock.nowMillis()
        )
        rig.repository.saveDraft(draft)
        assertEquals("Draft Insurance Policy", rig.repository.getDraft("NEW_RECORD")?.title)

        // 2. Commit v1 record -> clears NEW_RECORD draft automatically
        val v1Rec = rig.repository.saveRecord(
            existingRecordId = null,
            title = "Health Insurance Policy",
            description = "**Coverage**: Family Floater",
            folderId = null,
            category = "Finance",
            customFields = listOf(
                CustomField("cf1", "Policy No", CustomFieldType.TEXT, "POL-8891"),
                CustomField("cf2", "Sum Insured", CustomFieldType.NUMBER, "1500000"),
                CustomField("cf3", "Active", CustomFieldType.BOOLEAN, "true")
            )
        )
        assertNull(rig.repository.getDraft("NEW_RECORD"))
        assertEquals(1, v1Rec.currentVersionNumber)

        // 3. Re-saving identical content does NOT create noisy duplicate version
        rig.clock.advanceBy(5_000L)
        val unchanged = rig.repository.saveRecord(
            existingRecordId = v1Rec.id,
            title = v1Rec.title,
            description = v1Rec.description,
            folderId = v1Rec.folderId,
            category = v1Rec.category,
            customFields = v1Rec.customFields
        )
        assertEquals(1, unchanged.currentVersionNumber)
        assertEquals(1, rig.repository.observeRecordVersions(v1Rec.id).first().size)

        // 4. Meaningful edit increments to v2
        rig.clock.advanceBy(60_000L)
        val v2Rec = rig.repository.saveRecord(
            existingRecordId = v1Rec.id,
            title = "Health Insurance Policy (Updated)",
            description = "**Coverage**: Super Top-Up Added",
            folderId = null,
            category = "Finance",
            customFields = v1Rec.customFields
        )
        assertEquals(2, v2Rec.currentVersionNumber)
        val versionsAfterV2 = rig.repository.observeRecordVersions(v1Rec.id).first()
        assertEquals(2, versionsAfterV2.size)

        // 5. Non-destructive restore of v1 creates v3 while preserving v1 and v2 intact
        val v1Snapshot = versionsAfterV2.first { it.versionNumber == 1 }
        rig.clock.advanceBy(60_000L)
        val restoredV3 = rig.repository.restoreRecordVersion(v1Rec.id, v1Snapshot.id)
        assertEquals(3, restoredV3.currentVersionNumber)
        assertEquals("Health Insurance Policy", restoredV3.title)
        assertEquals("**Coverage**: Family Floater", restoredV3.description)

        val allVersions = rig.repository.observeRecordVersions(v1Rec.id).first()
        assertEquals(3, allVersions.size)
        assertEquals(listOf(3, 2, 1), allVersions.map { it.versionNumber })
    }

    @Test
    fun `encrypted attachment import offline FTS4 search and filters find records across title custom fields and attachment names`() = runBlocking {
        val medicalFolder = rig.repository.createFolder("Medical Vault", null, FolderAccentColor.BLUE)
        val recA = rig.repository.saveRecord(
            existingRecordId = null,
            title = "Cardiology Consultation",
            description = "Annual ECG and lipid profile normal.",
            folderId = medicalFolder.id,
            category = "Health",
            customFields = listOf(CustomField("f1", "Hospital", CustomFieldType.TEXT, "Apollo Clinic"))
        )
        val pdfBytes = "%PDF-1.7 Confidential ECG Report Payload 2026".encodeToByteArray()
        val att = rig.repository.importAttachmentFromStream(
            recordId = recA.id,
            input = ByteArrayInputStream(pdfBytes),
            fileName = "apollo_ecg_report_2026.pdf",
            mimeType = "application/pdf"
        )
        assertEquals(AttachmentKind.PDF, att.kind)

        // Verify ciphertext file on disk does NOT contain raw PDF header
        val encryptedOnDisk = File(rig.attachmentStorage.encryptedDir, att.encryptedRelativePath).readBytes()
        assertFalse(String(encryptedOnDisk, Charsets.ISO_8859_1).contains("%PDF-1.7"))

        // Verify streaming decrypt matches exact PDF bytes
        val out = ByteArrayOutputStream()
        rig.repository.exportAttachmentToStream(att.id, out)
        assertArrayEquals(pdfBytes, out.toByteArray())

        // Create second record in root
        rig.repository.saveRecord(
            existingRecordId = null,
            title = "Property Tax Receipt",
            description = "Municipal receipt paid via UPI",
            folderId = null,
            category = "Property",
            customFields = emptyList()
        )

        // Search by FTS token in description ("lipid")
        val ftsHit = rig.repository.searchVault(SearchFilters(query = "lipid"))
        assertEquals(listOf(recA.id), ftsHit.records.map { it.id })

        // Search by custom field value ("Apollo")
        val cfHit = rig.repository.searchVault(SearchFilters(query = "Apollo"))
        assertEquals(listOf(recA.id), cfHit.records.map { it.id })

        // Search by attachment filename ("ecg_report") + PDF filter
        val attHit = rig.repository.searchVault(
            SearchFilters(query = "ecg_report", attachmentKind = AttachmentKind.PDF, sortBy = SortOption.TITLE_ASC)
        )
        assertEquals(1, attHit.matchingAttachments.size)
        assertEquals(listOf(recA.id), attHit.records.map { it.id })

        // Filter strictly by category = "Property"
        val propHit = rig.repository.searchVault(SearchFilters(category = "Property"))
        assertEquals(listOf("Property Tax Receipt"), propHit.records.map { it.title })
    }

    @Test
    fun `recurring reminders log completed occurrences advance schedule and detect missed reminders`() = runBlocking {
        val scheduledAt = rig.clock.nowMillis() + 3_600_000L
        val (dailyRem, _) = rig.repository.saveReminder(
            existingReminderId = null,
            title = "Take Vitamin D3",
            notes = "After breakfast",
            scheduledAtMillis = scheduledAt,
            recurrence = RecurrenceType.DAILY,
            priority = ReminderPriority.HIGH,
            linkedRecordId = null
        )
        assertEquals(ReminderStatus.PENDING, dailyRem.status)

        // Complete today's occurrence -> logs COMPLETED occurrence & schedules tomorrow
        rig.repository.completeReminder(dailyRem.id)
        val updated = rig.repository.getReminderById(dailyRem.id)!!
        assertEquals(ReminderStatus.PENDING, updated.status)
        assertTrue(updated.scheduledAtMillis > scheduledAt)

        val occurrences = rig.repository.observeReminderOccurrences().first()
        assertEquals(1, occurrences.size)
        assertEquals(ReminderStatus.COMPLETED, occurrences.first().outcome)

        // Create a one-time reminder and advance clock past its schedule -> marked MISSED
        val (onceRem, _) = rig.repository.saveReminder(
            existingReminderId = null,
            title = "Renew Passport Appointment",
            notes = "",
            scheduledAtMillis = rig.clock.nowMillis() + 600_000L,
            recurrence = RecurrenceType.ONCE,
            priority = ReminderPriority.HIGH,
            linkedRecordId = null
        )
        rig.clock.advanceBy(3_600_000L) // +1 hour past due
        rig.repository.refreshMissedReminders()
        assertEquals(ReminderStatus.MISSED, rig.repository.getReminderById(onceRem.id)?.status)
    }

    @Test
    fun `missed recurring reminder logs MISSED but keeps the next future occurrence alive`() = runBlocking {
        val scheduledAt = rig.clock.nowMillis() + 600_000L
        val (dailyRem, _) = rig.repository.saveReminder(
            existingReminderId = null,
            title = "Daily medicine",
            notes = "After breakfast",
            scheduledAtMillis = scheduledAt,
            recurrence = RecurrenceType.DAILY,
            priority = ReminderPriority.HIGH,
            linkedRecordId = null
        )

        // Phone was off / alarm was missed; refresh used to mark the whole SERIES MISSED forever.
        rig.clock.advanceBy(3_600_000L)
        rig.repository.refreshMissedReminders()

        val updated = rig.repository.getReminderById(dailyRem.id)!!
        assertEquals(ReminderStatus.PENDING, updated.status)
        assertTrue(updated.scheduledAtMillis > rig.clock.nowMillis())
        val occurrences = rig.repository.observeReminderOccurrences().first()
        assertEquals(1, occurrences.size)
        assertEquals(ReminderStatus.MISSED, occurrences.first().outcome)

        // Idempotent: the future trigger must not be marked missed again on the next dashboard open.
        rig.repository.refreshMissedReminders()
        assertEquals(1, rig.repository.observeReminderOccurrences().first().size)
    }

    @Test
    fun `legacy MISSED recurring rows are revived instead of staying dead after upgrade`() = runBlocking {
        val now = rig.clock.nowMillis()
        val oldTrigger = now - 2L * 24L * 3_600_000L
        rig.dbHolder.getDatabase().reminderDao().upsert(
            ReminderEntity(
                id = "legacy-dead-daily",
                title = "Legacy daily",
                notes = "",
                scheduledAtMillis = oldTrigger,
                recurrenceType = RecurrenceType.DAILY.name,
                priority = ReminderPriority.MEDIUM.name,
                status = ReminderStatus.MISSED.name,
                snoozedUntilMillis = null,
                linkedRecordId = null,
                createdAt = oldTrigger,
                modifiedAt = oldTrigger,
                isDeleted = false,
                syncVersion = 1L,
                lastModifiedByDeviceId = "old-device",
                syncPending = false
            )
        )

        rig.repository.refreshMissedReminders()

        val revived = rig.repository.getReminderById("legacy-dead-daily")!!
        assertEquals(ReminderStatus.PENDING, revived.status)
        assertTrue(revived.scheduledAtMillis > now)
        // The old MISSED outcome was already logged by the previous release; do not duplicate it.
        assertEquals(0, rig.repository.observeReminderOccurrences().first().size)
    }

    @Test
    fun `saveReminder rejects a past trigger instead of firing an immediate alarm`() = runBlocking {
        try {
            rig.repository.saveReminder(
                existingReminderId = null,
                title = "Already late",
                notes = "",
                scheduledAtMillis = rig.clock.nowMillis() - 1_000L,
                recurrence = RecurrenceType.ONCE,
                priority = ReminderPriority.MEDIUM,
                linkedRecordId = null
            )
            fail("Expected IllegalArgumentException for a past reminder trigger")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("future", ignoreCase = true))
        }
    }

    @Test
    fun `30-day Recycle Bin soft-deletes full folder hierarchy restores subtree intact and purges expired batches`() = runBlocking {
        val parent = rig.repository.createFolder("Projects", null, FolderAccentColor.RED)
        val child = rig.repository.createFolder("Architecture", parent.id, FolderAccentColor.YELLOW)
        val rec = rig.repository.saveRecord(
            existingRecordId = null,
            title = "Bauhaus Blueprint",
            description = "Grid system notes",
            folderId = child.id,
            category = "Design",
            customFields = emptyList()
        )
        val att = rig.repository.importAttachmentFromStream(
            recordId = rec.id,
            input = ByteArrayInputStream("SVG_PAYLOAD".encodeToByteArray()),
            fileName = "grid.txt",
            mimeType = "text/plain"
        )
        val encryptedFile = File(rig.attachmentStorage.encryptedDir, att.encryptedRelativePath)
        assertTrue(encryptedFile.exists())

        // Soft-delete root folder -> entire subtree moves to Recycle Bin
        rig.repository.softDeleteFolder(parent.id)
        assertTrue(rig.repository.observeAllFolders().first().isEmpty())
        assertTrue(rig.repository.observeAllRecords().first().isEmpty())

        val binItems = rig.repository.observeDeletedItems().first()
        assertEquals(1, binItems.size)
        val batch = binItems.first()
        assertEquals("Projects", batch.displayTitle)

        // Restore batch -> parent folder, child folder, and record all return!
        rig.repository.restoreDeletedBatch(batch.batchId)
        assertEquals(2, rig.repository.observeAllFolders().first().size)
        assertEquals(1, rig.repository.observeAllRecords().first().size)
        assertEquals(child.id, rig.repository.getRecordById(rec.id)?.folderId)
        assertTrue(encryptedFile.exists())

        // Soft-delete again and advance clock > 30 days -> auto-expiry permanently wipes record + encrypted file
        rig.repository.softDeleteFolder(parent.id)
        rig.clock.advanceBy(PersonalSpaceRepositoryImpl.RECYCLE_RETENTION_MILLIS + 60_000L)
        rig.repository.purgeExpiredRecycleBinItems()

        assertTrue(rig.repository.observeDeletedItems().first().isEmpty())
        assertNull(rig.repository.getRecordById(rec.id))
        assertFalse(encryptedFile.exists())
    }

    @Test
    fun `Room MIGRATION_1_2 executes cleanly and preserves FTS4 virtual table integrity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val migDbFile = File(rig.rootDir, "migration_test.db")
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `records` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `folderId` TEXT,
                        `category` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `modifiedAt` INTEGER NOT NULL,
                        `customFieldsJson` TEXT NOT NULL,
                        `currentVersionNumber` INTEGER NOT NULL,
                        `currentVersionId` TEXT NOT NULL,
                        `isDeleted` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        `deletedBatchId` TEXT,
                        `syncVersion` INTEGER NOT NULL,
                        `lastModifiedByDeviceId` TEXT NOT NULL,
                        `syncPending` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `records_fts` USING FTS4(`title`, `description`, `category`, `customFieldsJson`, content=`records`)"
                )
                db.execSQL(
                    "INSERT INTO `records` VALUES('r1','Migrated Title','Migrated Body','f1','Docs',100,100,'[]',1,'v1',0,NULL,NULL,1,'dev1',0)"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migDbFile.absolutePath)
                .callback(callback)
                .build()
        )
        val sqlDb = helper.writableDatabase
        PersonalSpaceDatabase.MIGRATION_1_2.migrate(sqlDb)

        sqlDb.query("SELECT title FROM records_fts WHERE records_fts MATCH 'Migrated*'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Migrated Title", cursor.getString(0))
        }
        helper.close()
    }

    @Test
    fun `note keeps only the current version and one previous version`() = runBlocking {
        rig.sessionManager.ensureAutoUnlocked()
        val created = rig.repository.saveRecord(
            existingRecordId = null,
            title = "Version cap note",
            description = "body v1",
            folderId = null,
            category = null,
            customFields = emptyList()
        )
        repeat(5) { round ->
            rig.repository.saveRecord(
                existingRecordId = created.id,
                title = "Version cap note",
                description = "body v" + (round + 2),
                folderId = null,
                category = null,
                customFields = emptyList()
            )
        }
        val versions = rig.repository.observeRecordVersions(created.id).first()
        assertTrue("Versions must be capped, found " + versions.size, versions.size <= 2)
    }
}
