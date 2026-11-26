package app.personalspace.vault.data.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.security.EncryptedTokenStore
import app.personalspace.vault.core.security.StoredDriveAuthSession
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class DriveAuthExpiredException(message: String = "Google Drive authorization has expired. Please reconnect in Settings.") : IOException(message)

@Serializable
data class RemoteDriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long = 0L,
    val createdTime: String? = null
)

sealed interface DriveAuthOutcome {
    data class Authorized(val accessToken: String) : DriveAuthOutcome
    data class ConsentResolutionRequired(val pendingIntent: PendingIntent) : DriveAuthOutcome
    data class ConfigurationOrError(val message: String) : DriveAuthOutcome
}

interface DriveRemoteDataSource {
    suspend fun ensureAppVaultFolder(accessToken: String): String
    suspend fun listFilesInFolder(accessToken: String, folderId: String, namePrefix: String? = null): List<RemoteDriveFile>
    /** Uploads/replaces a file inside the hidden `appDataFolder` (never visible in My Drive). */
    suspend fun uploadOrReplaceInAppData(
        accessToken: String,
        remoteName: String,
        localCiphertextFile: File
    ): RemoteDriveFile
    /** Lists files from the hidden `appDataFolder` only. */
    suspend fun listAppDataFiles(accessToken: String, namePrefix: String? = null): List<RemoteDriveFile>
    suspend fun uploadEncryptedFileResumable(
        accessToken: String,
        folderId: String,
        remoteName: String,
        localCiphertextFile: File,
        mimeType: String = "application/octet-stream"
    ): RemoteDriveFile
    suspend fun downloadEncryptedFile(
        accessToken: String,
        fileId: String,
        destinationFile: File
    )
    suspend fun deleteFile(accessToken: String, fileId: String)
    suspend fun uploadOrReplaceFile(
        accessToken: String,
        folderId: String,
        remoteName: String,
        localCiphertextFile: File
    ): RemoteDriveFile
}

/**
 * Manages Google Identity AuthorizationClient strictly with `https://www.googleapis.com/auth/drive.file`
 * scope — never requesting broad Drive access, and never faking a Drive connection.
 */
@Singleton
class GoogleDriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedTokenStore: EncryptedTokenStore,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        // Backups live in the hidden appDataFolder; drive.file keeps legacy project files readable.
        val REQUESTED_SCOPES = listOf(DRIVE_APPDATA_SCOPE, "https://www.googleapis.com/auth/drive.file")

        /** Google access tokens are short-lived; stay deliberately under the usual 1-hour lifetime. */
        const val SESSION_TTL_MILLIS = 55L * 60_000L
        /** Refresh before the deadline so a long sync or upload never races the token expiry. */
        private const val TOKEN_REFRESH_MARGIN_MILLIS = 5L * 60_000L
        /** Do not hammer Play Services when a silent re-authorization needs fresh user consent. */
        private const val SILENT_REFRESH_COOLDOWN_MILLIS = 5L * 60_000L
    }

    @Volatile
    private var lastSilentRefreshAttemptMillis: Long = 0L

    /**
     * Test seam for the Google Play Services authorization call. Null in production; JVM tests can
     * substitute a consent/error outcome without starting an interactive OAuth flow.
     */
    @Volatile
    var silentAuthorizationOverride: (suspend () -> DriveAuthOutcome)? = null

    fun getCurrentSession(): StoredDriveAuthSession? = encryptedTokenStore.readSession()

    fun isConnected(): Boolean = getCurrentSession()?.accessToken?.isNotBlank() == true

    /**
     * Returns a session that is usable for an API call, silently re-authorizing when the cached
     * access token is about to expire.
     *
     * `expiresAtMillis` used to be written but never read: after ~1 hour every sync and the daily
     * backup worker kept sending a dead token, got HTTP 401, and required a manual reconnect in
     * Settings. Every caller that needs an access token must use this instead of [getCurrentSession].
     *
     * If Google requires fresh user consent (or Play Services is unavailable), the cached session is
     * returned as-is on purpose: the following request then surfaces a real
     * [DriveAuthExpiredException] with a clear "reconnect" message, instead of a silent fake
     * "not connected" that hides a broken backup.
     */
    suspend fun getValidSession(): StoredDriveAuthSession? {
        val session = getCurrentSession() ?: return null
        if (session.accessToken.isBlank()) return null
        val now = timeProvider.nowMillis()
        if (session.expiresAtMillis > now + TOKEN_REFRESH_MARGIN_MILLIS) return session
        val sinceLastAttempt = now - lastSilentRefreshAttemptMillis
        if (lastSilentRefreshAttemptMillis > 0L &&
            sinceLastAttempt in 0L until SILENT_REFRESH_COOLDOWN_MILLIS
        ) return session

        lastSilentRefreshAttemptMillis = now
        val outcome = silentAuthorizationOverride?.invoke() ?: requestDriveAuthorization()
        return when (outcome) {
            is DriveAuthOutcome.Authorized -> getCurrentSession() ?: session
            is DriveAuthOutcome.ConsentResolutionRequired,
            is DriveAuthOutcome.ConfigurationOrError -> session
        }
    }

    suspend fun requestDriveAuthorization(): DriveAuthOutcome {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(REQUESTED_SCOPES.map { Scope(it) })
                .build()
            val result: AuthorizationResult = Identity.getAuthorizationClient(context)
                .authorize(request)
                .await()

            if (result.hasResolution()) {
                val pi = result.pendingIntent
                if (pi != null) {
                    DriveAuthOutcome.ConsentResolutionRequired(pi)
                } else {
                    DriveAuthOutcome.ConfigurationOrError("Google Drive consent requires resolution, but no intent was returned.")
                }
            } else {
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                    persistAccessToken(token)
                    DriveAuthOutcome.Authorized(token)
                } else {
                    DriveAuthOutcome.ConfigurationOrError(
                        "Google OAuth did not return an access token. Verify that an Android OAuth 2.0 Client ID matching package '${context.packageName}' and your signing SHA-1 is configured in Google Cloud Console (see GOOGLE_DRIVE_SETUP.md)."
                    )
                }
            }
        } catch (e: Exception) {
            DriveAuthOutcome.ConfigurationOrError(
                "Google Drive authorization unavailable (${e.localizedMessage ?: e.javaClass.simpleName}). Configure an Android OAuth 2.0 Client ID in Google Cloud Console as documented in GOOGLE_DRIVE_SETUP.md."
            )
        }
    }

    fun handleConsentResult(data: Intent?): DriveAuthOutcome {
        if (data == null) {
            return DriveAuthOutcome.ConfigurationOrError("Google Drive authorization was cancelled.")
        }
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                persistAccessToken(token)
                DriveAuthOutcome.Authorized(token)
            } else {
                DriveAuthOutcome.ConfigurationOrError("Authorization completed without an access token.")
            }
        } catch (e: Exception) {
            DriveAuthOutcome.ConfigurationOrError("Authorization failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    fun persistAccessToken(
        accessToken: String,
        accountEmail: String? = null,
        driveFolderId: String? = encryptedTokenStore.readSession()?.driveFolderId
    ) {
        val now = timeProvider.nowMillis()
        encryptedTokenStore.saveSession(
            StoredDriveAuthSession(
                accessToken = accessToken,
                accountEmail = accountEmail ?: encryptedTokenStore.readSession()?.accountEmail,
                driveFolderId = driveFolderId,
                obtainedAtMillis = now,
                expiresAtMillis = now + SESSION_TTL_MILLIS
            )
        )
    }

    fun updateDriveFolderId(folderId: String) {
        val cur = encryptedTokenStore.readSession() ?: return
        encryptedTokenStore.saveSession(cur.copy(driveFolderId = folderId))
    }

    fun disconnect() {
        encryptedTokenStore.clearSession()
    }
}

/**
 * Production Google Drive REST API v3 client using OkHttp with:
 * - Resumable 256 KiB chunked uploads (`uploadType=resumable`) & interrupted-upload recovery
 * - Streaming downloads (`alt=media`)
 * - Exponential backoff retry on transient 5xx / IOExceptions
 * - Explicit HTTP 401 detection (`DriveAuthExpiredException`)
 */
@Singleton
class OkHttpDriveRemoteDataSource @Inject constructor() : DriveRemoteDataSource {

    var baseApiUrl: String = "https://www.googleapis.com"
    var chunkSizeBytes: Int = 256 * 1024 // 256 KiB Drive resumable quantum

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    companion object {
        const val VAULT_FOLDER_NAME = "PersonalSpace_EncryptedVault"
        const val APP_DATA_SPACE = "appDataFolder"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        /** Hard stop for a malicious/looping `nextPageToken` (100 pages x 1000 files). */
        private const val MAX_LIST_PAGES = 100
    }

    override suspend fun ensureAppVaultFolder(accessToken: String): String = withContext(Dispatchers.IO) {
        val query = "name = '$VAULT_FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val listUrl = "$baseApiUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id, name)")
            .build()

        val listReq = Request.Builder()
            .url(listUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val bodyText = executeWithRetry(listReq)
        val root = json.parseToJsonElement(bodyText).jsonObject
        val files = root["files"]?.jsonArray
        if (!files.isNullOrEmpty()) {
            return@withContext files[0].jsonObject["id"]!!.jsonPrimitive.content
        }

        // Create dedicated application folder
        val createMeta = """{"name":"$VAULT_FOLDER_NAME","mimeType":"$FOLDER_MIME"}"""
        val createReq = Request.Builder()
            .url("$baseApiUrl/drive/v3/files?fields=id,name")
            .header("Authorization", "Bearer $accessToken")
            .post(createMeta.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val createdText = executeWithRetry(createReq)
        json.parseToJsonElement(createdText).jsonObject["id"]!!.jsonPrimitive.content
    }

    override suspend fun uploadOrReplaceInAppData(
        accessToken: String,
        remoteName: String,
        localCiphertextFile: File
    ): RemoteDriveFile = withContext(Dispatchers.IO) {
        val existing = listAppDataFiles(accessToken).firstOrNull { it.name == remoteName }
        if (existing == null) {
            val initReq = Request.Builder()
                .url("$baseApiUrl/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,createdTime")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Type", "application/octet-stream")
                .post(
                    """{"name":"$remoteName","parents":["${APP_DATA_SPACE}"]}"""
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()
            val location = client.newCall(initReq).execute().use { resp ->
                if (resp.code == 401) throw DriveAuthExpiredException()
                if (!resp.isSuccessful) throw IOException("Drive resumable init failed (HTTP ${resp.code})")
                resp.header("Location") ?: throw IOException("Drive resumable session returned no Location")
            }
            return@withContext uploadResumableToSession(accessToken, location, remoteName, localCiphertextFile, "application/octet-stream")
        }
        val updateReq = Request.Builder()
            .url("$baseApiUrl/upload/drive/v3/files/${existing.id}?uploadType=media&fields=id,name,size,createdTime")
            .header("Authorization", "Bearer $accessToken")
            .patch(localCiphertextFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val text = executeWithRetry(updateReq)
        val obj = json.parseToJsonElement(text).jsonObject
        RemoteDriveFile(
            id = obj["id"]!!.jsonPrimitive.content,
            name = obj["name"]?.jsonPrimitive?.content ?: remoteName,
            sizeBytes = localCiphertextFile.length(),
            createdTime = obj["createdTime"]?.jsonPrimitive?.content
        )
    }

    override suspend fun listAppDataFiles(
        accessToken: String,
        namePrefix: String?
    ): List<RemoteDriveFile> = withContext(Dispatchers.IO) {
        val prefixClause = if (!namePrefix.isNullOrBlank()) {
            " and name contains '${escapeDriveQueryLiteral(namePrefix)}'"
        } else ""
        // `spaces` is a separate request parameter, NOT a valid term inside `q`. Putting it in the
        // query string makes Drive reject the whole request with HTTP 400 "Invalid Value"
        // (location: "q"), which is what broke appDataFolder listing.
        val query = "trashed = false$prefixClause"
        listDriveFilesPaged(accessToken) { pageToken ->
            "$baseApiUrl/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("spaces", APP_DATA_SPACE)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "files(id, name, size, createdTime),nextPageToken")
                .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                .build()
        }
    }

    override suspend fun listFilesInFolder(
        accessToken: String,
        folderId: String,
        namePrefix: String?
    ): List<RemoteDriveFile> = withContext(Dispatchers.IO) {
        val prefixClause = if (!namePrefix.isNullOrBlank()) {
            " and name contains '${escapeDriveQueryLiteral(namePrefix)}'"
        } else ""
        val query = "'$folderId' in parents and trashed = false$prefixClause"
        listDriveFilesPaged(accessToken) { pageToken ->
            "$baseApiUrl/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("orderBy", "createdTime asc")
                .addQueryParameter("fields", "files(id, name, size, createdTime),nextPageToken")
                .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                .build()
        }
    }

    /**
     * Drive caps one `files.list` response at `pageSize` and returns `nextPageToken` for the rest.
     * The old implementation read only the first page, so after 1000 appDataFolder bundles (or 200
     * legacy visible-folder files) older sync events silently vanished for new devices. Follow the
     * token until Drive says there are no more pages.
     */
    private suspend fun listDriveFilesPaged(
        accessToken: String,
        urlFactory: (pageToken: String?) -> HttpUrl
    ): List<RemoteDriveFile> {
        val result = ArrayList<RemoteDriveFile>()
        var pageToken: String? = null
        var pages = 0
        do {
            val req = Request.Builder()
                .url(urlFactory(pageToken))
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val root = json.parseToJsonElement(executeWithRetry(req)).jsonObject
            root["files"]?.jsonArray?.forEach { elem ->
                val obj = elem.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.content ?: return@forEach
                result.add(
                    RemoteDriveFile(
                        id = id,
                        name = name,
                        sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        createdTime = obj["createdTime"]?.jsonPrimitive?.content
                    )
                )
            }
            pageToken = root["nextPageToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < MAX_LIST_PAGES)
        if (pageToken != null) {
            // Never return a truncated list as if it were complete; surface the anomaly instead.
            throw IOException("Drive file listing exceeded $MAX_LIST_PAGES pages; refusing to silently truncate sync history.")
        }
        return result
    }

    /** Drive `q` literals are single-quoted; escape backslashes and quotes from caller input. */
    private fun escapeDriveQueryLiteral(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    override suspend fun uploadEncryptedFileResumable(
        accessToken: String,
        folderId: String,
        remoteName: String,
        localCiphertextFile: File,
        mimeType: String
    ): RemoteDriveFile = withContext(Dispatchers.IO) {
        val totalLength = localCiphertextFile.length()
        val metadataJson = """{"name":"$remoteName","parents":["$folderId"]}"""

        val initReq = Request.Builder()
            .url("$baseApiUrl/upload/drive/v3/files?uploadType=resumable&fields=id,name,size,createdTime")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", totalLength.toString())
            .post(metadataJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val sessionUri = client.newCall(initReq).execute().use { resp ->
            if (resp.code == 401) throw DriveAuthExpiredException()
            if (!resp.isSuccessful) {
                throw IOException("Failed to initiate Drive resumable upload (HTTP ${resp.code})")
            }
            resp.header("Location")
                ?: throw IOException("Drive resumable upload response missing Location header")
        }
        uploadResumableToSession(accessToken, sessionUri, remoteName, localCiphertextFile, mimeType)
    }

    /**
     * Streams an already-created resumable session in fixed-size chunks with HTTP 308 resume,
     * committed-offset recovery and explicit HTTP 401 detection. Shared by the visible-folder and
     * appDataFolder upload paths so both keep identical retry semantics.
     */
    private suspend fun uploadResumableToSession(
        accessToken: String,
        sessionUri: String,
        remoteName: String,
        localCiphertextFile: File,
        mimeType: String
    ): RemoteDriveFile {
        val totalLength = localCiphertextFile.length()
        var offset = 0L
        var attempts = 0
        RandomAccessFile(localCiphertextFile, "r").use { raf ->
            while (offset < totalLength || totalLength == 0L) {
                try {
                    val remaining = (totalLength - offset).coerceAtMost(chunkSizeBytes.toLong()).toInt()
                    val chunk = ByteArray(remaining)
                    if (remaining > 0) {
                        raf.seek(offset)
                        raf.readFully(chunk)
                    }

                    val contentRange = if (totalLength == 0L) {
                        "bytes */0"
                    } else {
                        "bytes $offset-${offset + remaining - 1}/$totalLength"
                    }

                    val putReq = Request.Builder()
                        .url(sessionUri)
                        .header("Content-Range", contentRange)
                        .put(chunk.toRequestBody(mimeType.toMediaType()))
                        .build()

                    client.newCall(putReq).execute().use { resp ->
                        when (resp.code) {
                            200, 201 -> {
                                val respText = resp.body?.string().orEmpty()
                                val obj = json.parseToJsonElement(respText).jsonObject
                                return RemoteDriveFile(
                                    id = obj["id"]!!.jsonPrimitive.content,
                                    name = obj["name"]?.jsonPrimitive?.content ?: remoteName,
                                    sizeBytes = totalLength,
                                    createdTime = obj["createdTime"]?.jsonPrimitive?.content
                                )
                            }
                            308 -> {
                                val rangeHeader = resp.header("Range")
                                offset = parseCommittedByteEnd(rangeHeader)?.plus(1L) ?: (offset + remaining)
                                attempts = 0
                            }
                            401 -> throw DriveAuthExpiredException()
                            else -> throw IOException("Unexpected upload chunk status HTTP ${resp.code}")
                        }
                    }
                    if (totalLength == 0L) break
                } catch (authEx: DriveAuthExpiredException) {
                    throw authEx
                } catch (ioEx: IOException) {
                    attempts++
                    if (attempts > 3) throw IOException("Interrupted upload failed after retries: ${ioEx.message}", ioEx)
                    delay(250L * (1L shl attempts))
                    offset = queryServerCommittedOffset(sessionUri, totalLength) ?: offset
                }
            }
        }
        throw IOException("Resumable upload terminated without final confirmation.")
    }

    override suspend fun downloadEncryptedFile(
        accessToken: String,
        fileId: String,
        destinationFile: File
    ) = withContext(Dispatchers.IO) {
        val tmp = File(destinationFile.parentFile ?: destinationFile.absoluteFile.parentFile, "${destinationFile.name}.part")
        var attempt = 0
        while (true) {
            try {
                val req = Request.Builder()
                    .url("$baseApiUrl/drive/v3/files/$fileId?alt=media")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.code == 401) throw DriveAuthExpiredException()
                    if (!resp.isSuccessful) {
                        throw IOException("Drive download failed (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IOException("Empty response body during Drive download")
                    body.byteStream().buffered().use { input ->
                        FileOutputStream(tmp).buffered().use { out ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                }
                if (!tmp.renameTo(destinationFile)) {
                    tmp.copyTo(destinationFile, overwrite = true)
                    tmp.delete()
                }
                return@withContext
            } catch (authEx: DriveAuthExpiredException) {
                tmp.delete()
                throw authEx
            } catch (e: IOException) {
                tmp.delete()
                attempt++
                if (attempt > 3) throw IOException("Interrupted download failed after retries: ${e.message}", e)
                delay(250L * (1L shl attempt))
            }
        }
    }

    override suspend fun uploadOrReplaceFile(
        accessToken: String,
        folderId: String,
        remoteName: String,
        localCiphertextFile: File
    ): RemoteDriveFile = withContext(Dispatchers.IO) {
        val existing = listFilesInFolder(accessToken, folderId)
            .sortedByDescending { it.createdTime.orEmpty() }
            .firstOrNull { it.name == remoteName }

        if (existing == null) {
            uploadEncryptedFileResumable(accessToken, folderId, remoteName, localCiphertextFile)
        } else {
            val updateReq = Request.Builder()
                .url("$baseApiUrl/upload/drive/v3/files/${existing.id}?uploadType=media&fields=id,name,size,createdTime")
                .header("Authorization", "Bearer $accessToken")
                .patch(
                    localCiphertextFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val text = executeWithRetry(updateReq)
            val obj = json.parseToJsonElement(text).jsonObject
            RemoteDriveFile(
                id = obj["id"]!!.jsonPrimitive.content,
                name = obj["name"]?.jsonPrimitive?.content ?: remoteName,
                sizeBytes = localCiphertextFile.length(),
                createdTime = obj["createdTime"]?.jsonPrimitive?.content
            )
        }
    }

    override suspend fun deleteFile(accessToken: String, fileId: String): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseApiUrl/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        val resp = client.newCall(req).execute()
        resp.use {
            if (it.code == 401) throw DriveAuthExpiredException()
            if (it.code == 404) return@withContext
            if (!it.isSuccessful) throw IOException("Drive delete HTTP ${it.code}")
        }
    }

    private suspend fun executeWithRetry(request: Request): String {
        var attempt = 0
        while (true) {
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 401) throw DriveAuthExpiredException()
                    if (resp.code in 500..599) {
                        throw IOException("Drive server error HTTP ${resp.code}")
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("Drive API HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
                    }
                    return resp.body?.string().orEmpty()
                }
            } catch (authEx: DriveAuthExpiredException) {
                throw authEx
            } catch (e: IOException) {
                attempt++
                if (attempt > 3) throw e
                delay(250L * (1L shl attempt))
            }
        }
    }

    private fun queryServerCommittedOffset(sessionUri: String, totalLength: Long): Long? {
        return runCatching {
            val probe = Request.Builder()
                .url(sessionUri)
                .header("Content-Range", "bytes */$totalLength")
                .put(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(probe).execute().use { resp ->
                if (resp.code == 308) {
                    parseCommittedByteEnd(resp.header("Range"))?.plus(1L) ?: 0L
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun parseCommittedByteEnd(rangeHeader: String?): Long? {
        if (rangeHeader.isNullOrBlank()) return null
        val dashIdx = rangeHeader.lastIndexOf('-')
        if (dashIdx == -1) return null
        return rangeHeader.substring(dashIdx + 1).trim().toLongOrNull()
    }
}
