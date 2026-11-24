package app.personalspace.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.personalspace.vault.core.common.SystemTimeProvider
import app.personalspace.vault.core.crypto.Argon2KdfConfig
import app.personalspace.vault.core.crypto.KeystoreBiometricManager
import app.personalspace.vault.core.crypto.VaultKeyManager
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.entity.RecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Physical Android Device / Emulator Instrumented Test Suite (`./gradlew connectedDebugAndroidTest`).
 * Verifies native Android `libsqlcipher.so` page-level database encryption on ARM64 / x86_64 Android OS
 * so raw database files on disk contain zero cleartext SQLite headers or record strings.
 */
@RunWith(AndroidJUnit4::class)
class PersonalSpaceInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        File(context.filesDir, "vault_keystore").deleteRecursively()
        context.deleteDatabase("personal_space_encrypted.db")
    }

    @Test
    fun sqlCipherDatabaseEncryptsAllPagesOnAndroidDeviceWithZeroPlaintextLeakage() = runBlocking {
        val time = SystemTimeProvider()
        val keyManager = VaultKeyManager(context, time, KeystoreBiometricManager(context))
        val session = VaultSessionManager(keyManager, time)
        val dbHolder = VaultDatabaseHolder(context, session)

        val init = keyManager.createNewVault(
            masterPassword = "DeviceSqlCipherTest#2026".toCharArray(),
            argon2Config = Argon2KdfConfig(memoryKiB = 16384, iterations = 2, parallelism = 1)
        )
        session.unlockSession(init.subKeys)

        val db = dbHolder.getDatabase()
        db.recordDao().upsert(
            RecordEntity(
                id = "rec-sqlcipher-1",
                title = "TopSecretBiometricRecord",
                description = "UltraSensitivePayload994411",
                folderId = null,
                category = "Security",
                createdAt = 1_763_184_000_000L,
                modifiedAt = 1_763_184_000_000L,
                customFieldsJson = "[]",
                currentVersionNumber = 1,
                currentVersionId = "ver-1",
                syncVersion = 1L,
                lastModifiedByDeviceId = "poco-f7"
            )
        )
        assertTrue(dbHolder.verifyDatabaseIntegrity())
        assertEquals("TopSecretBiometricRecord", db.recordDao().getRecordById("rec-sqlcipher-1")?.title)
        dbHolder.closeDatabase()

        val rawDbBytes = dbHolder.databaseFile().readBytes()
        val rawText = String(rawDbBytes, Charsets.ISO_8859_1)
        assertFalse("Encrypted SQLCipher file must not start with SQLite format 3", rawText.startsWith("SQLite format 3"))
        assertFalse("Encrypted SQLCipher file must not contain cleartext title", rawText.contains("TopSecretBiometricRecord"))
        assertFalse("Encrypted SQLCipher file must not contain cleartext body", rawText.contains("UltraSensitivePayload994411"))
    }
}
