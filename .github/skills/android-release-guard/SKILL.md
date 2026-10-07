---
name: android-release-guard
description: "Bygga, verifiera och felsöka en signerad Play-release (AAB) för GearForge. Use when: bygga bundleRelease eller assembleRelease; bumpa versionCode; verifiera en AAB före uppladdning; byta AdMob-ID; hantera keystore, R8/ProGuard eller signering; migrera targetSdk. Trigger words: release, AAB, bundle, signering, keystore, versionCode, ProGuard, R8, AdMob-ID, Play Console, uppladdning."
---

# Release-guard — signerad AAB för Play

Repot har en avsiktlig spärr: `android/build.gradle` **kastar ett undantag** om
`bundleRelease`/`assembleRelease` körs med Googles test-AdMob-ID:n. Testannonser ger
ingen intäkt och bryter mot AdMob-policy. Spärren får inte tas bort eller kringgås.

## 1. Pre-flight — kontrollera innan du bygger

| Kontroll | Var | Krav |
|---|---|---|
| `android/keystore.properties` finns | filsystemet | Annars blir AAB:n osignerad och går inte att ladda upp |
| `android/release.keystore` finns | filsystemet | Se `STORE_READINESS.md` (alias `gearforge`, RSA-2048) |
| `versionCode` är **ny** | `android/build.gradle` → `defaultConfig` | Play avvisar ett återanvänt värde. Detta har redan hänt en gång — se `CHANGELOG.md` |
| `versionName` uppdaterad | samma | |
| `allowBackup="false"` | `AndroidManifest.xml` `<application>` | ACTION_PLAN punkt 29 |
| `usesCleartextTraffic="false"` | samma | ACTION_PLAN punkt 29 |
| `minifyEnabled`/`shrinkResources` = `true` | `android/build.gradle` → `buildTypes.release` | ACTION_PLAN punkt 19 |
| `targetSdk 36` | `defaultConfig` | Ändra inte utan att gå igenom `STORE_READINESS.md` avsnitt 5 |
| Produktions-ID:n | `MONETIZATION_CONFIG.md` avsnitt 1 | App ID `ca-app-pub-6154121627229543~9677913532`, rewarded `ca-app-pub-6154121627229543/4517387519` |

Kontrollera före bygget:

```powershell
$root='c:\Users\sebbe\Desktop\överför skrivbord\AndroidGame-Gears'
Test-Path (Join-Path $root 'android\keystore.properties')
Test-Path (Join-Path $root 'android\release.keystore')
Select-String -Path (Join-Path $root 'android\build.gradle') -Pattern 'versionCode|versionName|targetSdk'
Select-String -Path (Join-Path $root 'android\src\main\AndroidManifest.xml') -Pattern 'allowBackup|usesCleartextTraffic'
Get-Content (Join-Path $root 'CHANGELOG.md') -TotalCount 20
```

## 2. Bumpa versionCode

`versionCode` får **aldrig** återanvändas. Läs aktuellt värde ur `android/build.gradle`,
höj med 1 och dokumentera i `CHANGELOG.md` varför (t.ex. "v8 lägger till X").
Historiken i `STORE_READINESS.md`s release snapshot ska uppdateras i samma ändring.

## 3. Bygg

Sätt `$expectedVersionCode` och `$expectedVersionName` från den avsedda versionen i
`android/build.gradle`. Bekräfta hela `$trustedUploadCertSha256` (64 hextecken) från
en oberoende betrodd källa, exempelvis Play Console. Läs inte pinnen från AAB:n som
ska kontrolleras. Ett förkortat historiskt fingeravtryck duger inte.

Kräver JDK 17+, `py` och Googles `bundletool-all-1.18.2.jar` i `build/tools/`
(annan sökväg via `-Bundletool`). Verifieraren kontrollerar den publicerade SHA-256
som finns i `tools/verify_aab.py`; ändra inte pinnen för att godta en annan fil.

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
    -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
    -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
    -ExpectedVersionCode $expectedVersionCode -ExpectedVersionName $expectedVersionName `
    -ExpectedCertificateSha256 $trustedUploadCertSha256
```

Skriptet validerar formatet på båda ID:na (tilde vs snedstreck), kontrollerar att
utgivar-ID:t är identiskt, vägrar test-ID:n, kontrollerar att keystore finns och
**verifierar sedan artefakten**. Det läser aldrig nyckeln eller något lösenord.

Utdata: `android/build/outputs/bundle/release/android-release.aab`

**Fällan med långa utskrifter:** terminalen visar bara de sista raderna, så ett
bygge som ser ut att "stanna tidigt" kör ofta fortfarande. Verifiera med
`Get-Item` på utdatafilen i stället för att anta att det misslyckades.

**Fällan med pipad Gradle-utdata:** `gradlew ... | ForEach-Object` hänger för alltid
när en kvarstående daemon ärver pipens handtag. Skriv till fil via
`cmd /d /c "gradlew.bat ... > logg 2>&1"` eller sätt `GRADLE_OPTS=-Dorg.gradle.daemon=false`.

### R8-versionen — bevisa den, anta den inte

Kotlin 2.4 kräver R8 ≥ 9.1.29 ([kotlin-support](https://developer.android.com/build/kotlin-support)),
men AGP 8.13.2 bundlar R8 8.13.19. `settings.gradle` → `pluginManagement.buildscript`
lägger därför `com.android.tools:r8:9.1.56` på settings-klassvägen (R8:s dokumenterade
override); den laddas före kopian i AGP:s `builder.jar`. Kontrollera efter varje
release-bygge vilken R8 som faktiskt körde:

```powershell
Get-Content android\build\outputs\mapping\release\mapping.txt -TotalCount 2   # # compiler_version: 9.1.56
```

`classes.dex` i AAB/APK bär dessutom markören `~~R8{...,"version":"9.1.56"}`.
`mapping.txt` är över 50 MB — läs huvudet med `-TotalCount`, inte i editorn.
Ta bort overriden först när AGP självt bundlar R8 ≥ 9.1.29; sänk den aldrig.

## 4. Verifiera AAB:n — obligatoriskt

Byggskriptet kör `tools/verify_aab.py` obligatoriskt mot exakt
`android/build/outputs/bundle/release/android-release.aab`. Kräv avslutad körning,
`Verified AAB` och det unika JSON-kvittot från `Receipt`-raden. Kvittot ligger bredvid
AAB:n med namnet `android-release.aab.verified-<unik-id>.json` och ska ha status
`verified`. Kontrollera att dess hash matchar filen som faktiskt ska laddas upp.
En gammal AAB eller ett gammalt kvitto är inte bevis för den aktuella körningen.

Verifieraren avkodar manifestet med hashkontrollerad bundletool och läser DEX:ens
strängtabell. Den jämför hela App ID:t, rewarded-enheten, paketet och versionen;
utgivar-ID eller delsträng räcker inte. Den verifierar också signaturintegritet,
samtliga payload-posters signering och det oberoende bekräftade certifikatet.

Fristående användning kräver samma förväntade värden; se
`py tools/verify_aab.py --help`. Använd dessutom `--expected-sha256` och ett nytt
`--receipt` för hashbundet bevis. Förekomst av en DEX-sträng bevisar inte vilket ID
runtime-koden använder. Riktiga integrationstester i steg 5 krävs separat.

**Fällan med `bundle_manifest`:** `android/build/intermediates/bundle_manifest/release/...`
kan ligga kvar från en gammal AGP-körning (mätt 2026-09-23: en fil från 29 augusti med
`versionCode="2"`). Den aktuella manifesten som bundlen paketerar är
`android/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/`
`AndroidManifest.xml` — kontrollera dess tidsstämpel, inte bara sökvägen.

### Signatur

`apksigner` kontrollerar APK, inte AAB. AAB-verifieraren använder JDK:s signaturkontroll
och kräver att varje payload-post har exakt en giltig signerare med förväntad full
SHA-256. Ett självsignerat certifikat kan godtas med denna oberoende pinning; svälj
inte signaturfel eller osignerade poster som allmänna varningar.

| Kontroll | Förväntat |
|---|---|
| Kvittostatus och SHA-256 | `verified`, exakt aktuell AAB-hash |
| Paket, version och fullständiga AdMob-ID:n | Exakt angivna förväntningar |
| Testutgivare i kontrollerat manifest/DEX | Saknas |
| Signatur och certifikat | Alla payload-poster täckta, oberoende bekräftad SHA-256 |
| `versionCode` för ny Play-uppladdning | Oanvänt värde |

## 5. Verifiera på enhet innan uppladdning

R8 med `shrinkResources` kan strippa något som bara används via reflektion. Installera
release-APK:n och kontrollera att appen startar och att de fyra integrationspunkterna
fungerar:

```powershell
.\gradlew.bat :android:assembleRelease -PadmobAppId=... -PadmobRewardedUnitId=...
adb install -r android\build\outputs\apk\release\android-release.apk
adb shell am start -n com.gearforge.geargenerator/com.gearforge.app.MainActivity
adb logcat -d -t 200 "*:E" | Select-String "gearforge|AndroidRuntime|FATAL"
```

Kontrollera: **UMP-samtycke** visas före annonser i EES, **Billing** köp/ack/restore,
**Compose** renderar utan layoutregression, **GL-viewporten** ritar och exporter
fungerar.

## 6. Om något går fel

| Fel | Åtgärd |
|---|---|
| `Release build requires real AdMob IDs` | ID:na skickades inte in, eller är test-ID:n |
| AAB:n är osignerad | `keystore.properties` saknas eller är felformad |
| Play avvisar versionCode | Värdet har använts — höj igen och bygg om |
| Krasch direkt vid start i release men inte debug | R8 strippade något — lägg keep-regel i `android/proguard-rules.pro` |
| `mapping.txt` visar `compiler_version: 8.13.19` | R8-overriden i `settings.gradle` saknas eller laddades inte — Kotlin 2.4-metadata kräver R8 ≥ 9.1.29 |
| Lint ger falsk `Recycle` på `stream.use { }` | AGP 8.13.2:s lint har en Kotlin 2.2.20-frontend som inte läser Kotlin 2.4-metadata; `gradle.properties` kör därför lint 9.4.1 (`android.experimental.lint.version`). Ta inte bort den så länge AGP:s egen lint är äldre |
| `ClassNotFoundException` i testworkern | Någon har tvingat `-Dfile.encoding=UTF-8` — se `gradle.properties` |

## 7. Efter uppladdning

- Uppdatera `STORE_READINESS.md`s release snapshot (`versionCode`, `versionName`).
- Uppdatera claim-tabellen om en ny funktion tillkommit.
- Lägg en `CHANGELOG.md`-post under rätt version.
- Kontrollera att `MONETIZATION_CONFIG.md` avsnitt 1 fortfarande stämmer.
