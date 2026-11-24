package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile
import javax.crypto.AEADBadTagException

/**
 * PROOF for requirement #8: quantify the portableKeyWrap trade-off.
 * The claim to test: anyone holding ONLY the .psbackup file (no password, no recovery key)
 * can recover the vault data key, because the wrapping password is derived from the vaultId
 * that the manifest itself stores in plaintext.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableWrapProbeTest {
    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("PORT| $s") }
    private fun dump(t: String) { File("/home/user/verification/evidence/" + t + ".txt").apply { parentFile?.mkdirs() }.writeText(ev.toString()) }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `G1 file-only attacker recovers vault data key and notes`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "port_g1")
        try {
            rig.initializeAndUnlockVault("Portable#2026")
            rig.repository.saveRecord(null, "TopSecret", "salary is 42", null, null, emptyList())
            val pkg = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file
            note("package = ${pkg.name}  size=${pkg.length()}")

            // ===== ATTACKER: only the file, nothing else =====
            val m = ZipFile(pkg).use { z ->
                json.parseToJsonElement(z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString()).jsonObject
            }
            val vaultId = m["vaultId"]!!.jsonPrimitive.content
            val wrap = m["portableKeyWrap"]!!.jsonObject
            note("vaultId is stored in plaintext in manifest.json = $vaultId")
            note("portable wrap present = true (salt/iv/ciphertext all in manifest)")

            val salt = VaultCryptoEngine.fromBase64(wrap["saltBase64"]!!.jsonPrimitive.content)
            val iv = VaultCryptoEngine.fromBase64(wrap["ivBase64"]!!.jsonPrimitive.content)
            val ct = VaultCryptoEngine.fromBase64(wrap["ciphertextBase64"]!!.jsonPrimitive.content)
            val argon = wrap["argon2Config"]!!.jsonObject
            val cfg = app.personalspace.vault.core.crypto.Argon2KdfConfig(
                memoryKiB = argon["memoryKiB"]!!.jsonPrimitive.content.toInt(),
                iterations = argon["iterations"]!!.jsonPrimitive.content.toInt(),
                parallelism = argon["parallelism"]!!.jsonPrimitive.content.toInt()
            )
            note("argon2 config used to wrap = $cfg")

            val t0 = System.nanoTime()
            val wrappingKey = VaultCryptoEngine.derivePasswordWrappingKey(
                ("psbackup-portable:" + vaultId).toCharArray(), salt, cfg
            )
            val vdk = runCatching {
                VaultCryptoEngine.decryptAesGcm(wrappingKey, iv, ct, ("psbackup-portable-aad:" + vaultId).toByteArray())
            }
            wrappingKey.fill(0)
            val ms = (System.nanoTime() - t0) / 1_000_000
            note("unwrap WITHOUT any user secret: success=${vdk.isSuccess} time=${ms}ms")
            val dataKey = vdk.getOrThrow()
            note("recovered vaultDataKey bytes=${dataKey.size}")

            // now derive subkeys and decrypt the snapshot with a completely fresh rig context (no session)
            val subkeys = VaultCryptoEngine.deriveSubKeys(dataKey, vaultId)
            val snapBytes = ZipFile(pkg).use { z -> z.getInputStream(z.getEntry("vault_snapshot.enc")).readBytes() }
            val snapIv = VaultCryptoEngine.fromBase64(m["snapshotIvBase64"]!!.jsonPrimitive.content)
            val plain = VaultCryptoEngine.decryptAesGcm(subkeys.backupPayloadKey, snapIv, snapBytes, ("PersonalSpace/v1/backup/" + m["backupId"]!!.jsonPrimitive.content + "/" + vaultId).toByteArray())
            note("snapshot decrypted = true  plaintextBytes=${plain.size}")

            // and the HMAC can be forged/verified too because backupManifestKey is derived
            val plainText = plain.decodeToString()
            note("")
            note("RESULT: attacker with ONLY the .psbackup file:")
            note("  - recovered vaultDataKey            = true")
            note("  - decrypted vault_snapshot.enc       = true")
            note("  - read note titles in plaintext      = ${plainText.contains("TopSecret")}")
            note("  - read note bodies in plaintext      = ${plainText.contains("salary is 42")}")
            note("  - needs Master Password / recovery key = false")
            note("CONFIDENTIALITY IMPACT: the package is effectively UNENCRYPTED for anyone who has the file.")
            note("RECOVERY BENEFIT: cross-device restore works with zero user secrets (no password prompt).")
            dump("portG1_file_only_attack")
        } finally { rig.close() }
    }

}
