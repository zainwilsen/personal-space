package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.FakeSharedDriveCloud
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.data.sync.DriveAuthExpiredException
import app.personalspace.vault.data.sync.DriveAuthOutcome
import app.personalspace.vault.data.sync.GoogleDriveAuthManager
import app.personalspace.vault.data.sync.OkHttpDriveRemoteDataSource
import app.personalspace.vault.domain.model.ConflictKind
import app.personalspace.vault.domain.model.ConflictResolutionChoice
import app.personalspace.vault.domain.model.SyncHealthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSyncAndConflictIntegrationTest {

    private lateinit var sharedCloud: FakeSharedDriveCloud
    private lateinit var deviceA: IsolatedVaultTestRig
    private lateinit var deviceB: IsolatedVaultTestRig

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        sharedCloud = FakeSharedDriveCloud()
        deviceA = IsolatedVaultTestRig(appContext, "device_A", remoteDrive = sharedCloud)
        deviceB = IsolatedVaultTestRig(appContext, "device_B", remoteDrive = sharedCloud)
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    @Test
    fun `Drive file listings follow nextPageToken in both appDataFolder and legacy folder queries`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpDriveRemoteDataSource().apply {
                baseApiUrl = server.url("").toString().trimEnd('/')
            }

            // Page 1 of appDataFolder -> page 2. The old implementation returned only page 1 and
            // silently lost every sync bundle after the first 1000 results.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"files":[{"id":"app_page1","name":"psbundle_a.psbundle","size":"1"}],"nextPageToken":"APP_PAGE_2"}"""
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"files":[{"id":"app_page2","name":"psbundle_b.psbundle","size":"2"}]}"""
                )
            )
            val appDataFiles = client.listAppDataFiles("token", "psbundle_")
            assertEquals(listOf("app_page1", "app_page2"), appDataFiles.map { it.id })

            val firstAppDataRequest = server.takeRequest()
            assertTrue(firstAppDataRequest.path!!.contains("spaces=appDataFolder"))
            assertTrue(firstAppDataRequest.path!!.contains("nextPageToken"))
            val secondAppDataRequest = server.takeRequest()
            assertTrue(secondAppDataRequest.path!!.contains("pageToken=APP_PAGE_2"))

            // Legacy visible-folder listing has its own URL builder; cover that branch too.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"files":[{"id":"folder_page1","name":"psbundle_c.psbundle","size":"3"}],"nextPageToken":"FOLDER_PAGE_2"}"""
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"files":[{"id":"folder_page2","name":"psbundle_d.psbundle","size":"4"}]}"""
                )
            )
            val folderFiles = client.listFilesInFolder("token", "legacy-folder", "psbundle_")
            assertEquals(listOf("folder_page1", "folder_page2"), folderFiles.map { it.id })
            server.takeRequest()
            val secondFolderRequest = server.takeRequest()
            assertTrue(secondFolderRequest.path!!.contains("pageToken=FOLDER_PAGE_2"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `getValidSession keeps a near-expiry session available when silent reauthorization needs consent`() = runBlocking {
        deviceA.initializeAndUnlockVault()
        deviceA.authManager.persistAccessToken("short-lived-token", "user@example.com")
        // Simulate Play Services requiring fresh UI consent (no interactive OAuth in a JVM test).
        deviceA.authManager.silentAuthorizationOverride = {
            DriveAuthOutcome.ConfigurationOrError("Fresh consent required")
        }
        val fresh = deviceA.authManager.getValidSession()
        assertNotNull(fresh)
        assertEquals("short-lived-token", fresh!!.accessToken)

        // After the TTL, Google Play Services may require fresh UI consent in this headless test.
        // getValidSession() must NOT report "not connected": the ensuing API call should surface a
        // real 401/reconnect error rather than hiding a broken daily backup.
        deviceA.clock.advanceBy(GoogleDriveAuthManager.SESSION_TTL_MILLIS + 1L)
        val nearExpiry = deviceA.authManager.getValidSession()
        assertNotNull(nearExpiry)
        assertEquals("short-lived-token", nearExpiry!!.accessToken)

        // Once silent authorization succeeds, call sites receive the newly persisted token.
        deviceA.clock.advanceBy(10L * 60_000L)
        deviceA.authManager.silentAuthorizationOverride = {
            deviceA.authManager.persistAccessToken("refreshed-token", "user@example.com")
            DriveAuthOutcome.Authorized("refreshed-token")
        }
        val refreshed = deviceA.authManager.getValidSession()
        assertNotNull(refreshed)
        assertEquals("refreshed-token", refreshed!!.accessToken)
    }

    @Test
    fun `OkHttpDriveRemoteDataSource performs multi-chunk HTTP 308 resumable upload download and surfaces HTTP 401 expiry against MockWebServer`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpDriveRemoteDataSource().apply {
                baseApiUrl = server.url("").toString().trimEnd('/')
                chunkSizeBytes = 1024 // 1 KiB chunks to exercise multi-chunk 308 -> 200 protocol
            }

            // 1. Ensure vault folder: list returns empty -> POST creates folder
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder_xyz","name":"PersonalSpace_EncryptedVault"}"""))
            val folderId = client.ensureAppVaultFolder("valid-oauth-token")
            assertEquals("folder_xyz", folderId)

            // 2. Resumable upload of 2,500-byte encrypted payload (3 chunks: 1024 + 1024 + 452)
            val payload = ByteArray(2500) { (it % 253).toByte() }
            val tempUpload = File(deviceA.rootDir, "bundle.psbundle").apply { writeBytes(payload) }

            val sessionUri = server.url("/upload/session/abc123").toString()
            server.enqueue(MockResponse().setResponseCode(200).addHeader("Location", sessionUri))
            server.enqueue(MockResponse().setResponseCode(308).addHeader("Range", "bytes=0-1023"))
            server.enqueue(MockResponse().setResponseCode(308).addHeader("Range", "bytes=0-2047"))
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"id":"file_bundle_1","name":"bundle.psbundle","size":"2500"}""")
            )

            val uploaded = client.uploadEncryptedFileResumable(
                accessToken = "valid-oauth-token",
                folderId = folderId,
                remoteName = "bundle.psbundle",
                localCiphertextFile = tempUpload
            )
            assertEquals("file_bundle_1", uploaded.id)
            assertEquals(2500L, uploaded.sizeBytes)

            // 3. Streaming download verification
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))
            val downloadedFile = File(deviceA.rootDir, "downloaded_bundle.psbundle")
            client.downloadEncryptedFile("valid-oauth-token", "file_bundle_1", downloadedFile)
            assertArrayEquals(payload, downloadedFile.readBytes())

            // 4. HTTP 401 surfaces DriveAuthExpiredException (never faking success!)
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}"""))
            try {
                client.listFilesInFolder("expired-token", folderId)
                fail("Expected DriveAuthExpiredException on HTTP 401")
            } catch (_: DriveAuthExpiredException) {
                // Expected
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `appDataFolder upload creates once then PATCH replaces and never exposes a visible folder`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpDriveRemoteDataSource().apply {
                baseApiUrl = server.url("").toString().trimEnd('/')
                chunkSizeBytes = 1024 * 1024 // single PUT chunk keeps the request order deterministic
            }
            val payload = ByteArray(1500) { (it % 97).toByte() }
            val file = File(deviceA.rootDir, "PersonalSpace_Backup.psbackup").apply { writeBytes(payload) }

            // 1. First upload: appDataFolder list is empty -> resumable init with parents=["appDataFolder"]
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
            val sessionUri = server.url("/upload/appdata/session").toString()
            server.enqueue(MockResponse().setResponseCode(200).addHeader("Location", sessionUri))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"appdata_1","name":"PersonalSpace_Backup.psbackup","size":"1500"}"""))
            val first = client.uploadOrReplaceInAppData("token", "PersonalSpace_Backup.psbackup", file)
            assertEquals("appdata_1", first.id)

            val listRequest = server.takeRequest()
            assertTrue(listRequest.path!!.contains("spaces=appDataFolder"))
            // Regression (HTTP 400 "Invalid Value", location "q"): `spaces` is a separate request
            // parameter and is NOT a valid term inside `q`. Drive rejects the entire request when it
            // appears there, which is what broke appDataFolder listing in the field.
            val listQuery = java.net.URLDecoder.decode(listRequest.path!!, "UTF-8")
            val qParam = listQuery.substringAfter("q=", "").substringBefore("&")
            assertFalse(
                "the Drive query must not contain a spaces= term: $qParam",
                qParam.contains("spaces=")
            )
            val initRequest = server.takeRequest()
            val initBody = initRequest.body.readUtf8()
            assertTrue(initBody.contains("\"parents\":[\"appDataFolder\"]"))
            assertEquals("POST", initRequest.method)

            // First upload issues exactly one resumable PUT chunk
            val chunkRequest = server.takeRequest()
            assertEquals("PUT", chunkRequest.method)

            // 2. Second upload: existing file found -> PATCH media replaces the SAME file id
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"files":[{"id":"appdata_1","name":"PersonalSpace_Backup.psbackup","size":"1500","createdTime":"2026-11-16T09:00:00Z"}]}"""
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"id":"appdata_1","name":"PersonalSpace_Backup.psbackup","size":"1500"}""")
            )
            val second = client.uploadOrReplaceInAppData("token", "PersonalSpace_Backup.psbackup", file)
            assertEquals("appdata_1", second.id)

            val replaceListRequest = server.takeRequest()
            assertTrue(replaceListRequest.path!!.contains("spaces=appDataFolder"))
            val replaceQuery = java.net.URLDecoder.decode(replaceListRequest.path!!, "UTF-8")
            assertFalse(replaceQuery.substringAfter("q=", "").substringBefore("&").contains("spaces="))
            val patchRequest = server.takeRequest()
            assertEquals("PATCH", patchRequest.method)
            assertTrue(patchRequest.path!!.contains("/files/appdata_1"))
            assertTrue(patchRequest.path!!.contains("uploadType=media"))

            // 3. Expired authorization surfaces as DriveAuthExpiredException, never a fake success
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}"""))
            try {
                client.listAppDataFiles("expired")
                fail("Expected DriveAuthExpiredException")
            } catch (_: DriveAuthExpiredException) {
                // expected
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `MultiDeviceSyncEngine syncs encrypted bundles cleanly preserves CONCURRENT_EDIT and DELETE_VS_EDIT conflicts without data loss and resolves KEEP_BOTH`() = runBlocking {
        val password = "SharedVaultPassword#2026"
        val initA = deviceA.initializeAndUnlockVault(password)
        deviceB.joinExistingVaultEnvelope(initA.bundle, password)

        // Connect both devices to shared fake Drive cloud
        deviceA.authManager.persistAccessToken("token-device-a", "user@example.com")
        deviceB.authManager.persistAccessToken("token-device-b", "user@example.com")

        // 1. Device A creates a record and syncs -> ciphertext uploaded to Drive
        val recA = deviceA.repository.saveRecord(
            existingRecordId = null,
            title = "Passport — Primary",
            description = "Issued in Patna, expires 2034",
            folderId = null,
            category = "Identity",
            customFields = emptyList()
        )
        val reportA1 = deviceA.repository.runSyncNow()
        assertEquals(SyncHealthState.SYNCED, reportA1.finalState)
        assertTrue(reportA1.uploadedOperations >= 1)

        // Verify zero plaintext leakage inside uploaded `.psbundle` on Drive
        val rawCloudBundles = sharedCloud.rawStoredBytesByName(".psbundle")
        assertTrue(rawCloudBundles.isNotEmpty())
        assertFalse(String(rawCloudBundles.first(), Charsets.UTF_8).contains("Issued in Patna"))

        // 2. Device B runs sync -> receives and decrypts record cleanly
        val reportB1 = deviceB.repository.runSyncNow()
        assertEquals(SyncHealthState.SYNCED, reportB1.finalState)
        val recOnB = deviceB.repository.getRecordById(recA.id)
        assertEquals("Passport — Primary", recOnB?.title)

        // 3. CONCURRENT EDIT CONFLICT:
        // Both Device A and Device B independently edit `recA` offline before syncing!
        deviceA.clock.advanceBy(10_000L)
        deviceA.repository.saveRecord(
            existingRecordId = recA.id,
            title = "Passport — Edited on POCO F7 (Device A)",
            description = "Visa page endorsement A",
            folderId = null,
            category = "Identity",
            customFields = emptyList()
        )
        deviceA.repository.runSyncNow() // Device A uploads its change bundle first

        deviceB.clock.advanceBy(20_000L)
        deviceB.repository.saveRecord(
            existingRecordId = recA.id,
            title = "Passport — Edited on Tablet (Device B)",
            description = "Visa page endorsement B",
            folderId = null,
            category = "Identity",
            customFields = emptyList()
        )

        // Device B now syncs: detects local `syncPending == true` vs remote change -> CONCURRENT_EDIT!
        val conflictReportB = deviceB.repository.runSyncNow()
        assertEquals(SyncHealthState.ATTENTION_REQUIRED, conflictReportB.finalState)
        assertEquals(1, conflictReportB.conflictsDetected)

        val conflictsOnB = deviceB.repository.observeUnresolvedConflicts().first()
        assertEquals(1, conflictsOnB.size)
        assertEquals(ConflictKind.CONCURRENT_EDIT, conflictsOnB.first().conflictKind)

        // Resolve using KEEP_BOTH -> preserves Device B's record AND creates a `(Conflict Copy)` record for Device A's version!
        deviceB.repository.resolveConflict(
            conflictId = conflictsOnB.first().conflictId,
            choice = ConflictResolutionChoice.KEEP_BOTH
        )
        assertTrue(deviceB.repository.observeUnresolvedConflicts().first().isEmpty())
        val allRecordsAfterKeepBoth = deviceB.repository.observeAllRecords().first()
        assertEquals(2, allRecordsAfterKeepBoth.size)
        assertTrue(allRecordsAfterKeepBoth.any { it.title.contains("Device B") })
        assertTrue(allRecordsAfterKeepBoth.any { it.title.contains("Remote Copy") })

        // 4. DELETE_VS_EDIT CONFLICT:
        // Device B deletes original record and pushes tombstone while Device A edits it offline!
        deviceB.repository.softDeleteRecord(recA.id)
        deviceB.repository.runSyncNow()

        deviceA.clock.advanceBy(30_000L)
        deviceA.repository.saveRecord(
            existingRecordId = recA.id,
            title = "Passport — Critical Renewal Note on Device A",
            description = "Must NOT be silently deleted by remote tombstone!",
            folderId = null,
            category = "Identity",
            customFields = emptyList()
        )
        val delVsEditReport = deviceA.repository.runSyncNow()
        assertEquals(SyncHealthState.ATTENTION_REQUIRED, delVsEditReport.finalState)
        val conflictsOnA = deviceA.repository.observeUnresolvedConflicts().first()
        assertEquals(1, conflictsOnA.size)
        assertEquals(ConflictKind.DELETE_VS_EDIT, conflictsOnA.first().conflictKind)
        // Edited record on Device A is preserved safely!
        assertEquals("Passport — Critical Renewal Note on Device A", deviceA.repository.getRecordById(recA.id)?.title)
    }
}
