# Code Review — Personal Space (Android / Kotlin)

**Branch:** `arena/4ad6ea3c-personal-space` · **Date:** 2026-11-26
**Scope:** `PersonalSpace-GitHub/app/src/main`, `app/src/test`, `build.gradle.kts`, `.github/workflows`
**Method:** static review (padh kar + cross-check). ⚠️ Is sandbox me JDK / Android SDK nahi hai aur Maven Central allowlist me nahi hai, isliye `./gradlew test` / `assembleRelease` **chalana possible nahi tha** — neeche ke findings code-reading par based hain, runtime proof nahi.

---

## Fix status (2026-11-26, isi branch par)

User ke request par **#1 aur #2 (Critical crypto / backup design) abhi postpone** hain — unhe chhua nahi gaya. Baaki fixes **final answer wali top-six numbering** ke hisaab se kiye gaye hain:

| Final answer # | Fix |
|---|---|
| #3 | Drive OAuth token expiry: `getValidSession()` + sabhi token call sites migrate; 401 par daily backup failure record |
| #4 | Restore sync rebaseline: stale outbox/conflicts/devices/metadata clear, restored rows ke liye UPSERT baseline queue, caches invalidate |
| #5 | Reminders cluster: recurring `MISSED` death fix + legacy revival, 1000-iteration cap / monthly drift, mirror fallback + stale prune, locked Complete no-op, UTC DatePicker, safe receivers, past-trigger guard |
| #6 | Drive `nextPageToken` pagination + `.psbundle` / `appliedBundleIds` retention |

Iske saath regression tests add kiye gaye hain (recurrence, missed/revival, restore rebaseline, pagination, token refresh, mirror). ⚠️ Toolchain na hone ki wajah se ye tests **abhi run nahi ho paaye**.

> **Numbering note:** neeche wali Quick-summary table me #3 alag hai (security settings dead wiring), #4 Drive, #5 sync, #6 restore. Agar aap us table ki #3–#6 bol rahe the to table #3 abhi is patch me include nahi hai; final answer wali mapping follow ki gayi hai.

---

## 0. Sabse pehle: Reminders wala aapka decision ✅ (bug nahi hai)

Aapne bola tha *"Reminders backup nahi kiya kyunki main nahi chahta wo backup ho"* — maine ise **intentional design** mana hai aur isse koi issue nahi nikala. Ulta, maine verify kiya ki ye decision code me **consistently** follow ho raha hai:

| Jagah | Kya mila |
|---|---|
| `data/backup/BackupPackageManager.kt:476-477` | `reminders = emptyList()`, `occurrences = emptyList()` — snapshot me reminders nahi jaate ✔ |
| `BackupPackageManager.kt:957-964, 1298` | Restore sirf drafts/versions/attachments/recycle-bin/timeline/records/folders clear karta hai; **reminders table ko chhoota tak nahi** → restore ke baad device ke apne reminders zinda rehte hain ✔ (yahi sahi behaviour hai) |
| `data/backup/DeepBackupVerifier.kt` | Reminders ko compare nahi karta → restore verification isliye fail nahi hoti ✔ |
| `BackupPackageManager.kt:422 computeVaultFingerprint()` | Reminders fingerprint me nahi → reminder badalne par roz ka Drive backup trigger nahi hota ✔ |
| `data/sync/MultiDeviceSyncEngine.kt` | Sync bhi reminders ko touch nahi karta ✔ |

**Ek chhota suggestion (bug nahi, maintainability):** `:476` ke paas koi comment nahi hai. `VaultSnapshotPayload` me abhi bhi `reminders` / `reminderOccurrences` fields hain aur `.map { ... }` dead code hai. Koi future dev isse "theek" karke `db.reminderDao()` laga dega aur aapka intent toot jayega. Isliye:

```kotlin
// INTENTIONAL: Reminders are device-local by design — never exported to backups or sync.
// Restore deliberately leaves the destination device's own reminders untouched.
val reminders = emptyList<ReminderEntity>()
```

...aur `insertSnapshotInto()` me bhi ek line likh do ki reminders jaan-bujh kar skip hain. (Agar kabhi format v3 me reminders add karne hon to `insertSnapshotInto` me insert/delete logic bhi chahiye, warna silently data loss hoga.)

---

## Quick summary

| # | Severity | Area | Ek line me |
|---|---|---|---|
| 1 | 🔴 Critical | Crypto / lock | VDK plain base64 me file me + `requireSubKeys()` lock ke baad bhi auto-unlock |
| 2 | 🔴 Critical (known trade-off) | Backup | `.psbackup` file **akeli** decrypt ho jati hai (password = public string + `vaultId`) |
| 3 | 🟠 High | Security settings | Auto-lock / lock-on-screen-off / FLAG_SECURE / biometric / master-password sab dead |
| 4 | 🟠 High | Drive | OAuth token expiry kabhi check/refresh nahi hota → 1 ghante baad sync + daily backup hamesha fail |
| 5 | 🟠 High | Sync | `.psbundle` + `appliedBundleIds` unbounded grow, Drive list me pagination nahi (1000 cap) |
| 6 | 🟠 High | Restore | Restore sync state reset nahi karta; stale pending ops upload ho sakte hain, restored rows `syncPending=false` |
| 7 | 🟠 High | Reminders | `MISSED` hone par recurring reminder hamesha ke liye mar jata hai |
| 8 | 🟠 High | Reminders | 1000-iteration cap → 1000+ din purana daily reminder **past** timestamp return karta hai |
| 9 | 🟡 Medium | Reminders | `SecurityException` fallback me mirror entry likhi hi nahi → reboot par alarm gayab |
| 10 | 🟡 Medium | Reminders | Locked vault me notification ka **Complete** button chupchap kuch nahi karta |
| 11 | 🟡 Medium | Reminders | Monthly recurrence drift (Jan 31 → Feb 28 → **Mar 28**, Mar 31 nahi) |
| 12 | 🟡 Medium | Reminders | DatePicker UTC-midnight → UTC−wale timezones me date 1 din peeche (IST me nahi dikhega) |
| 13 | 🟡 Medium | Backup gen | Corrupt `generations.json` chupchap khali index ban jata hai → saare known-good backups "bhool" jate hain |
| 14 | 🟡 Medium | Receivers | 3 BroadcastReceivers me coroutine exception handler nahi → exception par app crash |
| 15 | 🟡 Medium | UI | Dashboard "Pending Reminders" card / reminder tags / "Add Reminder" callbacks `{}` — no route |
| 16 | 🟡 Medium | Privacy | Decrypted viewer files lock par purge nahi hote (doc me likha hai, code me nahi) |
| 17 | 🟢 Low | Restore / backup | `previousMoved` unused, external-copy failure swallow, empty-file upload edge case |
| 18 | 🟢 Low | Build / CI / tests | arm64-only ABI, debug keystore "release", missing docs, 30 tests hardcode `/home/user/...` |

---

## 🔴 Critical

### 1. Vault Data Key plaintext me + lock bypass
`core/crypto/VaultKeyManager.kt:62, 79-140` · `core/security/VaultSessionManager.kt:132-145`

```kotlin
// VaultKeyManager.kt:113-118
val record = AutoVaultKeyRecord(vaultId = vaultId, vdkBase64 = VaultCryptoEngine.toBase64(vdk), ...)
tmp.writeText(json.encodeToString(record), Charsets.UTF_8)   // files/vault_keystore/auto_open_vdk.json
```

* `auto_open_vdk.json` me **poora 256-bit Vault Data Key** sirf Base64 me pada hai. Base64 encryption nahi hai. Is file ko padhne wala (root, forensic image, koi bhi app-data access) SQLCipher DB key + attachment key + backup keys derive kar sakta hai — bina master password / biometric ke.
* `VaultSessionManager.requireSubKeys()` (`:141`): `if (vaultKeyManager.hasAutoOpenVault()) { ... _lockState.value = UNLOCKED; return autoKeys }` — yani `lockVault()` ke **turant baad** koi bhi DB access vault ko khud hi unlock kar deta hai. `PersonalSpaceNavHost.kt:61` aur `:103,108` har composition/launch par `ensureAutoUnlocked()` call karte hain.
* `createNewVault()` ka production me **koi caller nahi hai** (`grep` confirm). Default master password hardcode hai: `"personal-space-auto-vault"` (`:107`), Argon2 bhi weak `4096 KiB / 1 iteration` (`:106`).

**Asar:** "Encrypted vault / Master Password / Biometric / Auto-lock" sab decorative ban jate hain. Ye intentional "frictionless" design lagta hai (tests bhi `RecoveryBypassProbeTest.kt:62` me isse "production parity" bolte hain), par security claim ke saath match nahi karta.

**Fix (choose one):**
* VDK ko Android Keystore key se wrap karo (biometric/user-presence se gate), `auto_open_vdk.json` hatado; **ya**
* Auto-unlock ko explicit opt-in karo aur UI me clearly likho "is device par vault unlocked-by-default hai, encryption sirf OS sandbox par depend karta hai"; `requireSubKeys()` ko LOCKED state ka respect karne do, aur lock screen sach me chalao.

---

### 2. `.psbackup` file akeli decrypt ho jati hai (documented trade-off, phir bhi risk)
`data/backup/BackupPackageManager.kt:1711-1712, 1662-1705, 1083-1099` · `presentation/settings/SettingsScreens.kt:631-642`

```kotlin
private fun portablePassword(vaultId: String) = ("psbackup-portable:" + vaultId).toCharArray()
```

* `vaultId` **plaintext `manifest.json`** me hota hai. Salt/IV/ciphertext/config bhi manifest me. Isliye koi bhi aadmi jiske paas `.psbackup` file hai, wrapping key derive karke VDK → snapshot → saare attachments/images decrypt kar sakta hai, **bina kisi password ke**. `unlockCandidateKeys()` (`:1087`) exactly yahi karta hai jab `SettingsViewModel.kt:333` / `:362` restore bina credentials call kare.
* Ye **aapko pata hai** — UI me warning hai ("A .psbackup file can be opened without your Master Password…") aur `PortableWrapProbeTest.kt` / `PortableForgeProbeTest.kt` isse measure bhi karte hain. Isliye maine ise "hidden bug" nahi bola; par release notes / About screen jagah-jagah "encrypted backup" bolte hain, jo misleading hai.
* Extra: `BackupPackageManager.kt:397` me literal legacy secret `private val LEGACY_FIXED_RESTORE_PASSWORD = "Stark@9898".toCharArray()` — source me hardcoded password. Purane (pre-v1.1.7) backups isi se unlock hote hain, to wo bhi effectively public hain.

**Fix:** restore ke liye user se Master Password / Recovery Key / naya "backup password" maango aur `portableKeyWrap` ko usi se derive karo (ya Android Keystore se device-bound rakho aur cross-device restore me recovery phrase zaroori karo). Kam-se-kam legacy constant ko hata kar migration note likho. UI warning ko backup/export screen par bhi dikhana chahiye, sirf Settings ke andar nahi.

---

## 🟠 High

### 3. Security settings hain par kaam nahi karte (dead wiring)
`core/security/VaultSessionManager.kt:54, 152-180` · `presentation/MainActivity.kt:65` · `data/local/preferences/UserPreferencesDataSource.kt:24-27, 100-111` · `presentation/settings/SettingsViewModel.kt:503, 523-532, 551, 584, 612`

* `onAppBackgrounded()`, `onAppForegrounded()`, `onScreenTurnedOff()` ke **koi caller hi nahi** (`grep` across `app/src/main` → zero). Matlab `autoLockSeconds` / `lockOnScreenOff` settings ka koi effect nahi.
* `MainActivity.kt:65` hamesha `window.clearFlags(FLAG_SECURE)` karta hai; `secureTaskSwitcher` preference ko kahin apply nahi kiya gaya → Recents/screenshot me vault content dikh sakta hai.
* `SettingsScreens.kt` (General/Backup screens) me auto-lock, secure-task-switcher, master-password, recovery-key, biometric toggles **dikhte hi nahi**; `SettingsViewModel` ke corresponding methods orphan hain.
* `setOnVaultLockedListener()` ek single callback slot hai (`:54`) — abhi sirf `VaultDatabaseHolder` use karta hai. Koi doosra listener set hua to DB-close-on-lock chala jayega.

**Fix:** `ProcessLifecycleOwner` + `ACTION_SCREEN_OFF` se session manager wire karo; `FLAG_SECURE` ko `settings.secureTaskSwitcher` se bind karo; listener ko list banao; jo settings UI me nahi hain unhe ya to wire karo ya code/prefs se hata do.

---

### 4. Drive access token kabhi expire/refresh nahi hota
`data/sync/GoogleDriveSyncService.kt:163-170, 103` · `data/sync/SyncWorkers.kt:73-76` · `MultiDeviceSyncEngine.kt:120-127`

`persistAccessToken()` `expiresAtMillis = now + 55 * 60_000L` likhta hai, par **poore codebase me `expiresAtMillis` ko koi padhta hi nahi** (sirf declaration + assignment). `isConnected()` sirf `accessToken.isNotBlank()` check karta hai. Google OAuth access token ~1 ghante me expire hota hai.

**Asar:** connect ke ~1 ghante baad `ScheduledDailyBackupWorker` aur `IncrementalSyncWorker` har baar HTTP 401 → `DriveAuthExpiredException` → 3 retries → `Result.failure()`. UI "Connected" dikhata rahega jab tak user manually Settings me jaa kar disconnect/reconnect na kare. Roz ka auto-backup practically kabhi nahi chalega.

**Fix:** `getCurrentSession()` me `expiresAtMillis <= now + 5min` par `Identity.getAuthorizationClient(...).authorize()` se silent re-authorize karo (ya refresh token flow), aur 401 aane par token clear + re-auth trigger karo. `isConnected()` ko bhi expiry-aware banao.

---

### 5. Sync history unbounded + Drive listing paginated nahi
`data/sync/MultiDeviceSyncEngine.kt:162, 216, 307, 325` · `GoogleDriveSyncService.kt:294, 324`

* Har sync ek **naya immutable** `psbundle_*.psbundle` upload karta hai (`:278`). Koi bhi code `deleteFile()` se purane bundles nahi hatata — `deleteFile` ka ek hi caller hai `pruneDriveBackups()` jo sirf `.psbackup` files ke liye hai. `appDataFolder` quota aur file count forever badhte jayenge.
* `appliedBundleIdsJson` (`:325`) me har bundle ID forever store hoti hai, aur har sync par poori list rewrite hoti hai. Har sync `listAppDataFiles()` se **saare** historical bundles download karta hai → O(N) network + CPU.
* `listAppDataFiles()` `pageSize=1000` aur `listFilesInFolder()` `pageSize=200` — **`nextPageToken` handling nahi hai**. 1000 se zyada bundles hone par purane events chupchap truncate ho jayenge; naya device poora history nahi banaa payega.

**Fix:** `nextPageToken` loop; bundles ko timestamp/cutoff ke hisaab se delete karo (e.g. 30 din ya "sab devices ne apply kar liya" ke baad); `appliedBundleIds` ko bounded rakhho (sirf recent N ya last-applied watermark) aur sync state me `lastAppliedCreatedTime` rakho.

---

### 6. Restore sync state ko reset nahi karta → doosre devices par stale/data-loss
`data/backup/BackupPackageManager.kt:957-964, 1298-1370`

Restore transaction ye clear karta hai: drafts, versions, attachments, deleted batches, timeline, records, folders. **Ye clear nahi karta:** `sync_operations`, `sync_metadata`, `conflict_records`, `sync_devices`, `backup_metadata`. Aur restored rows `syncPending = false` ke saath insert hoti hain (`:1306, :1319, :1344`).

**Asar:** restore se pehle ka outbox (purane vault ke pending UPSERT/TOMBSTONE ops) zinda rehta hai. Agla sync unhe Drive par upload karega — ho sakta hai abhi-abhi restore kiye gaye record ka purana tombstone broadcast ho jaye aur doosre device par data delete ho jaye. Doosri taraf restored content kabhi push nahi hoga kyunki `syncPending=false`. `backup_metadata` bhi purane vault ki history dikhati rahegi.

**Fix:** restore transaction me `syncDao()` ka outbox + appliedBundleIds + unresolved conflicts clear karo, `lastSyncedAtMillis = null`, aur restored rows ko `syncPending = true` + naye ops queue karo (ya sync ko explicitly "full re-baseline" mode me daalo). Kam-se-kam ek comment/test add karo jo ye contract freeze kare.

---

### 7. Recurring reminder ek missed alarm ke baad hamesha ke liye band
`data/repository/PersonalSpaceRepositoryImpl.kt:1667-1700` · `data/reminder/ReminderSystem.kt:337-360`

`refreshMissedReminders()` (dashboard khulne par `preloadStartupData()` se call) 60 sec se zyada overdue `PENDING`/`SNOOZED` reminder ko `MISSED` mark kar deta hai — **chahe wo DAILY/WEEKLY/MONTHLY ho**. Recurrence sirf `completeReminder()` / notification "Complete" action me advance hoti hai. `MISSED` hone ke baad na koi naya alarm schedule hota hai, na mirror entry clean hoti hai, na `ReminderAlarmReceiver` usse sambhalta hai (wo sirf notification post karta hai).

**Asar:** phone off/reboot ya ek missed notification = roz ka reminder permanently dead. `scheduleReminder()` bhi `MISSED` dekh kar `cancelReminder()` kar deta hai (`:125-128`).

**Fix:** missed handling me bhi `computeNextOccurrenceMillis()` se agla trigger nikal kar `PENDING` + `scheduleReminder()` karo (occurrence `MISSED` log karke), ya "missed occurrences skip karo, par future occurrences zinda rakho".

---

### 8. Recurrence calculator 1000 iterations par past timestamp return karta hai
`data/reminder/ReminderSystem.kt:52-71`

```kotlin
var safety = 0
do {
    cursor = when (recurrence) { DAILY -> cursor.plusDays(1); ... }
    safety++
} while (cursor.toInstant().toEpochMilli() <= nowMillis && safety < 1000)
return cursor.toInstant().toEpochMilli()
```

1000 din (~2.7 saal) se purana DAILY reminder: loop cap pe ruk jata hai aur **abhi bhi past** ka timestamp return karta hai. `AlarmManager` past trigger par turant fire karta hai; user "Complete" dabaye to phir se past timestamp → infinite-ish loop. WEEKLY to ~19 saal me cap pakdega, MONTHLY ~83 saal me.

**Fix:** arithmetic se jump karo (`Period.between(...).toTotalMonths()`, `ChronoUnit.DAYS.between(...)`) aur cap ke badle `while (cursor <= now)` with a sane assertion, ya cap hit hone par `now + 1 min` par reschedule + occurrence log. Test `ReminderRecurrenceAndDateFormatTest.kt` abhi sirf 1-step case cover karta hai — 3-saal-puraana case add karo.

---

## 🟡 Medium

### 9. `SecurityException` fallback mirror entry likhna bhool jata hai
`data/reminder/ReminderSystem.kt:136-155`

`upsertMirrorEntry()` `try` ke andar `setExact...` ke **baad** hai. `catch (_: SecurityException)` me fallback alarm to set ho jata hai par `return false` se pehle mirror update nahi hota. Exact-alarm permission revoke hone wale device par alarm reboot ke baad `rescheduleFromMirrorOnBoot()` me nahi milega → reminder chupchap gayab. Usi catch me `am.setAndAllowWhileIdle()` bhi try ke bahar se throw ho sakta hai. Mirror file me purane entries kabhi prune nahi hote (`:165-177` sirf `now - 60s` filter karta hai, file se remove nahi karta) → file dheere-dheere badhti jayegi.

**Fix:** `schedule()` success hone par (chahe exact ho ya fallback) hamesha `upsertMirrorEntry()` call karo; boot par stale entries `removeMirrorEntry()` se hatao.

### 10. Locked vault me notification ka "Complete" no-op hai
`data/reminder/ReminderSystem.kt:385-405`

```kotlin
if (sessionManager.lockState.value != UNLOCKED) {
    if (action == ACTION_SNOOZE) { /* reschedule via mirror */ }
    return@launch          // ACTION_COMPLETE: kuch nahi
}
```

`postReminderNotification()` locked state me bhi Complete + Snooze actions add karta hai (`:306-307`). User Complete dabata hai → notification dismiss (`:381`), DB untouched, alarm/mirror untouched, koi feedback nahi. Lagta hai complete ho gaya, actually kuch nahi hua.

**Fix:** locked hone par Complete action mat dikhao, ya usse "Unlock to complete" intent se app kholo, ya encrypted/pending queue me action store karke unlock par apply karo.

### 11. MONTHLY recurrence day-of-month drift karta hai
`data/reminder/ReminderSystem.kt:62-69`

`cursor.plusMonths(1)` repeated: Jan 31 → Feb 28 → **Mar 28** → Apr 28… (Mar 31 / May 31 kabhi nahi aata). Class ka doc "Jan 31 -> Feb 28/29" bolta hai par anchor day preserve nahi hota. DST ke liye `plusDays` sahi hai.

**Fix:** original scheduled date ka `dayOfMonth` yaad rakhho aur har month `YearMonth.atDay(min(day, lengthOfMonth()))` se compute karo. Test me 2-3 mahine ka chain add karo.

### 12. DatePicker UTC-midnight → negative-offset timezones me date 1 din peeche
`presentation/records/RecordsScreens.kt:3751-3752, 3797-3804`

```kotlin
val selectedMillis = dateState.selectedDateMillis ?: System.currentTimeMillis()
val selectedDate = Instant.ofEpochMilli(selectedMillis).atZone(zone).toLocalDate()
```

Material3 `DatePickerState.selectedDateMillis` **UTC midnight** return karta hai. `atZone(ZoneId.systemDefault())` karne par UTC−5 jaisi jagah `26 Nov 19:00` local = **pichhla din**. Aap Ranchi (IST, +5:30) me hain to yahan 05:30 same day hota hai aur bug nahi dikhega — par US/Europe users ka reminder ek din pehle set ho jayega.

**Fix:** `Instant.ofEpochMilli(selectedMillis).atZone(ZoneOffset.UTC).toLocalDate()` (ya `LocalDate.ofEpochDay(selectedMillis / 86_400_000L)`), phir `selectedDate.atTime(hour, minute).atZone(zone)`.

### 13. Corrupt `generations.json` = saare known-good backups chupchap bhool jana
`data/backup/BackupGenerationManager.kt:96-99`

```kotlin
fun readIndex(): GenerationIndex = runCatching { ... }.getOrElse { GenerationIndex() }
```

Ek truncated/corrupt index file (crash ke douran likha gaya) → empty index → `retained()` empty, `latestKnownGood()` null, `trim()` "no verified generation exists; refusing to rotate", aur restore UI me generations gayab. Disk par `.psbackup` files to hain, par index unhe nahi janta. `untrackedFiles()` helper hai par koi automatic rebuild nahi karta.

**Fix:** parse failure par purane index ko `.corrupt` bana kar preserve karo, `untrackedFiles()` se on-disk generations ko verify karke index rebuild karo, aur user ko ek warning do. `writeIndex()` ko atomic (`tmp` + rename + fsync) banao — `promote()` me to hai, `writeIndex()` me nahi.

*Side note:* `trim()` ka doc (`:206-215`) kehta hai "refuses when fewer than MIN_RETAINED would remain / newest would be removed", par implementation `verified.take(maxOf(keep, MIN_RETAINED))` 1–2 verified generation ke saath bhi aage badh jati hai. Doc ya logic dono me se ek theek karo. Aur `:230-236` me delete fail hone par "nothing else was removed" return hota hai, jabki loop pehle hi kuch files delete kar chuka hota hai aur index write nahi hua → index dangling references.

### 14. BroadcastReceivers me uncaught coroutine exception → crash
`data/reminder/ReminderSystem.kt:337, 385, 523`

Teeno receivers `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ... }` me `try/finally` rakhte hain par **koi `catch` / `CoroutineExceptionHandler` nahi**. Andar `dbHolder.getDatabase()` (RecoveryGate blocked → throw), `ReminderPriority.valueOf(updated.priority)` (`:466, :502` — corrupt/unknown DB value par `IllegalArgumentException`), ya SQLCipher error → exception thread ke uncaught handler tak pahunch kar process crash karegi. Iske alava `entries.firstOrNull { ... } ?: DEFAULT` ka pattern jagah-jagah hai par `valueOf` do jagah inconsistent hai.

**Fix:** `SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> ... }`, `valueOf` ko safe parser se badlo, aur `finally { pendingResult.finish() }` ke saath ek `catch` me graceful notification/error log rakho.

### 15. Reminders UI aadha wired hai
`presentation/navigation/PersonalSpaceNavHost.kt:135-136, 168, 182, 212` · `ScreenRoutes.kt`

```kotlin
onOpenReminders = {}, onOpenReminder = {}, onAddLinkedReminder = {}, ...
```

`ScreenRoutes` me koi reminders list/detail route hi nahi. `DashboardScreen.kt` ka "Pending Reminders" card (`:329-336`) aur "Upcoming Reminders" tags (`:457-462`) clickable hain par kuch nahi khulte; `FoldersScreens.kt:1692` ka "Add Reminder" menu item bhi no-op. Note banana possible hai (`RecordsScreens.kt:352` `scheduleNoteReminder` + `NoteReminderDialog`), par **list/view/manage karne ka koi screen nahi** — to user apne reminder ko complete/snooze/delete UI se nahi kar sakta (sirf notification actions se).

**Fix:** ya to reminders list + detail route bana kar callbacks wire karo, ya dead cards/callbacks hata do taaki UI jhootha na lage.

### 16. Decrypted viewer files lock par purge nahi hote
`data/attachment/EncryptedAttachmentStorage.kt:36-40, 128-135, 187-203` · `SettingsViewModel.kt:819` · `RecordsScreens.kt:304, 3314`

Class doc bolta hai *"ephemeral … purged on lock and startup"*. Reality: `purgeAllEphemeralFiles()` sirf (a) manual Settings button aur (b) attachment viewer ke dispose par call hota hai. `VaultSessionManager` ka lock callback sirf `VaultDatabaseHolder` (DB close) se juda hai; lock par plaintext cache clean nahi hota. `decryptToEphemeralCacheFile()` 15-min stale purge karta hai, to plaintext copy 15 min tak `cacheDir/ephemeral_share/` me padhi reh sakti hai.

**Fix:** lock callback ko multi-listener bana kar `purgeAllEphemeralFiles()` bhi register karo + app `onCreate` me bhi purge karo.

---

## 🟢 Low / cleanup

| # | File | Issue |
|---|---|---|
| 17a | `BackupPackageManager.kt:912` | `val previousMoved = attachmentsDir.exists() && attachmentsDir.renameTo(previousDir)` — variable kabhi check nahi hota. Rename fail hone par purane encrypted files live dir me reh jate hain (orphans) aur restore phir bhi "verified" keh deta hai. |
| 17b | `BackupPackageManager.kt:703-704` | `runCatching { publishToExternalStorage(...) }` / `rememberBackupFingerprint(...)` failure chupcha dete hain — user ko external copy mila ya nahi, pata nahi. |
| 17c | `GoogleDriveSyncService.kt:393-435` | 0-byte file ka resumable upload: agar server 308 de to `if (totalLength == 0L) break` loop tod deta hai aur `"Resumable upload terminated without final confirmation."` throw. PUT requests me `Authorization` header bhi nahi (session URI usually kaafi hai, par Google recommend karta hai). |
| 17d | `PersonalSpaceRepositoryImpl.kt:1520` | `saveReminder()` past `scheduledAtMillis` accept kar leta hai → alarm turant fire. Dialog/VM me `require(trigger > now)` chahiye. |
| 17e | `BackupPackageManager.kt:1740-1752` | `orderFoldersTopologically()` cycle me depth 128 par ruk jata hai; `validateSnapshotRelationships()` cycle detect nahi karta. `PRAGMA foreign_keys=ON` ke saath crafted backup restore ko rollback karwa sakta hai. |
| 18a | `app/build.gradle.kts:37` | `abiFilters += listOf("arm64-v8a")` → x86_64 emulator aur 32-bit devices par app install nahi hogi; `androidTest` standard CI emulator par nahi chal sakti. APK size ke liye samajh aata hai, par trade-off document karo. |
| 18b | `app/build.gradle.kts:50-58, 93` | "Release" APK debug keystore (`android`/`androiddebugkey`) se sign, `abortOnError = false` + `checkReleaseBuilds = false` lint ko chupcha dete hain. |
| 18c | Code + workflow | `GOOGLE_DRIVE_SETUP.md` aur `GITHUB_ACTIONS_SETUP.md` ka zikr hai (`build.gradle.kts:52`, `GoogleDriveSyncService.kt:128,134`, workflow `:10,:77`) par repo me **ek bhi `.md` file nahi hai** (`git ls-files` = 132, 0 markdown). Broken docs links. |
| 18d | `app/src/test/**` (30 files) | Evidence `/home/user/verification/evidence/...` hardcoded; CI isliye `sudo mkdir -p /home/user/verification/evidence` karta hai (workflow `:102-105`). Kisi aur machine / Windows / CI me tests `FileNotFoundException` se fail honge. `SampleBackupDumpTest.kt:69,175` to **decrypted snapshot plaintext** `/home/user/audit_sample_dump.txt` me likhta hai. |
| 18e | `PersonalSpaceRepositoryImpl.kt:2240` / `SettingsViewModel.kt:503` | Restore/lock ke baad repository ke `@Volatile cachedOverview / cachedFoldersList / cachedRecordsById` invalidate nahi hote; `latestCached*()` callers ko purana data mil sakta hai. |

---

## Suggested fix order (agar main karna shuru karun)

1. **#4 (token expiry)** — sabse zyada user-visible: roz ka backup + sync abhi kaam nahi kar rahe.
2. **#1 + #3** — decide karo: "frictionless auto-unlock" rakho (to claims/docs simplify karo) ya sach me lock/password lagao. Beech ka rasta sabse bura hai.
3. **#6 (restore → sync reset)** — data-loss risk doosre devices par.
4. **#7 + #8 + #9 + #10** — reminders cluster (recurrence, mirror, locked Complete). Chhote patches, bade behaviour fixes.
5. **#2** — backup encryption ka design decision (agar trade-off rakho to warning About/Export par bhi le jao; legacy constant hatao).
6. **#5** — sync retention + pagination (long-term).
7. **#13, #18d** — test/build hygiene.

> **Note:** Maine koi code change nahi kiya — ye sirf review hai. Chahein to upar se koi bhi subset pick karke main fix + tests bana ke de sakta hoon. Reminders backup wali policy ko main chhedunga nahi. 🙌
