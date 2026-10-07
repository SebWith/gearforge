# GearForge — Store Readiness

> Phase 5 (ACTION_PLAN points 29, 30, 32). Package `com.gearforge.geargenerator`.
>
> This document is the single checklist for preparing the Google Play listing and
> for tracking the future `targetSdk 36` migration. It complements
> [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) (ads/billing/UMP) and
> [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) (privacy + Data Safety source of truth).

## Release snapshot

| Field | Value |
|---|---|
| applicationId | `com.gearforge.geargenerator` |
| versionCode | `10` (bumped from 9 — the 2026-10-03 internal-test upload of 9 was rejected by Play as an already-used code from an earlier build; v7 added the In-App Review rating card, v8 fixed the missing runtime storage permission that broke every export on Android 7.0–9.0. Play rejects a reused versionCode, so every upload takes the next number) |
| versionName | `1.1` (the first release whose measurements, playback rate and HUD layout changed; 1.0 shipped with the storage-permission fix alone) |
| targetSdk | `36` (required for Play submission from Aug 2026) |
| minSdk | `24` |
| Signing | `android/release.keystore`, alias `gearforge`, RSA-2048, 10000 days |
| Signing config | `android/keystore.properties` (gitignored; optional in [`android/build.gradle`](android/build.gradle)) |

---

## Building the upload bundle

[`android/build.gradle`](android/build.gradle) deliberately refuses to run
`bundleRelease` / `assembleRelease` with Google's **test** AdMob IDs, so the real
IDs must be passed explicitly. [`tools/build-release-aab.ps1`](tools/build-release-aab.ps1)
validates inputs before Gradle starts and requires artifact verification afterward.
Set `$expectedVersionCode` and `$expectedVersionName` from the intended version in
`android/build.gradle`. Confirm the full `$trustedUploadCertSha256` from an independent
trusted source such as Play Console, never from the AAB being checked. JDK 17+, `py`,
and hash-pinned bundletool 1.18.2 are required; see the
[release guard](.github/skills/android-release-guard/SKILL.md).

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
    -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
  -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
  -ExpectedVersionCode $expectedVersionCode -ExpectedVersionName $expectedVersionName `
  -ExpectedCertificateSha256 $trustedUploadCertSha256
```

Output: `android/build/outputs/bundle/release/android-release.aab` (signed with
`android/keystore.properties`, which Gradle reads directly — never commit it).

Require `Verified AAB` and the unique JSON receipt printed by this build:

- The receipt's SHA-256 must match the exact current AAB.
- Parsed manifest metadata and DEX strings must match the full expected IDs.
- Package, version, certificate pin and signature coverage must pass.
- Checked manifest/DEX content must not contain `3940256099942544`.

The local verification rebuild of version 1.1 — most recently rebuilt and re-verified on
2026-10-03 as `versionCode 10` (receipt `android-release.aab.verified-7e22a108...json`, AAB SHA-256
`88916C71...EBFCD`; `versionCode 9` was rejected by Play the same day as already used) — is not a new Play
upload or release approval. Real purchases remain unverified and an API26 chooser
accessibility crash remains open. See the
[application audit](docs/application-audit-2026-09-24.md) for measured results and limits.

---

## 1. Google Play listing copy (EN)

**Title (≤ 30 chars):**

> GearForge - 3D Gear Generator

**Short description (≤ 80 chars):**

> Spur, planetary & bevel gears in 3D. Export STL, 3MF, STEP, IGES, DXF, SVG.

**Full description:**

**GearForge is a parametric gear generator and 3D gear designer for makers,
engineers and 3D-printing enthusiasts.** Design real, manufacturable gears on
your phone — no CAD experience required. Change a value, see the exact geometry,
then export a file you can print, cut or import into CAM.

**14 gear types**

Spur, helical, bevel, rack & pinion, planetary, worm pair, internal ring, hypoid,
cycloidal, harmonic drive, face gear, screw gear, compound and timing belt.

**Fully parametric**

- Module or diametral pitch, tooth count and pressure angle.
- Addendum, dedendum, profile shift and backlash.
- Involute, cycloidal and straight tooth profiles, with per-tooth overrides.
- Hubs and bores: round, D-cut, double-D, keyway (DIN 6885), hex and square.
- Work in millimetres or inches.

**Live 3D preview**

Rotate and zoom the real mesh and inspect the exact teeth you are about to
export. Undo and redo every change.

**Proven presets**

Start from recommended 3:1, 5:1 and 7:1 planetary stages for gearmotors, robotics
and high-torque reducers — or tune every parameter yourself.

**Results as you type**

Pitch, outer, root and base diameters, centre distance, ratio, tooth dimensions,
weight, moment of inertia and effective backlash. Validation warns before a
physically impossible gear can be exported.

**Six export formats**

- **STL & 3MF** for 3D printing.
- **STEP & IGES** for CAD and CAM.
- **DXF** for laser cutting and CNC.
- **SVG** for 2D layout and documentation.

Files are saved straight to your Downloads folder with triangle count and
dimensions.

**Free to use**

Every install includes 3 free exports. Unlock unlimited exports and high-quality
mesh output with the one-time **Pro** purchase, or watch a short rewarded video
to earn an extra export. No account required.

GearForge respects your privacy: we never collect your name, email, location or
files, and your designs are never uploaded. Advertising (when shown) uses Google
AdMob with consent management for eligible regions.

---

## 2. Google Play listing copy (SV)

**Titel (≤ 30 tecken):**

> GearForge - 3D-kugghjul

**Kort beskrivning (≤ 80 tecken):**

> Rak- och planetkugghjul i 3D. Exportera STL, 3MF, STEP, IGES, DXF, SVG.

**Fullständig beskrivning:**

**GearForge är en parametrisk kugghjulsgenerator och 3D-kugghjulsdesigner för
makers, ingenjörer och 3D-utskrivare.** Rita riktiga, tillverkningsbara kugghjul
direkt i mobilen — inga CAD-kunskaper krävs. Ändra ett värde, se exakt geometri
och exportera en fil du kan skriva ut, skära eller importera i CAM.

**14 kugghjulstyper**

Rakt, snedskuret, koniskt, kuggstång & pinjong, planetväxel, snäckväxel, invändig
ring, hypoid, cykloidal, harmonic drive, face gear, skruvhjul, sammansatt och
kuggrem.

**Helt parametrisk**

- Modul eller diametral delning, kuggantal och tryckvinkel.
- Addendum, dedendum, profilförskjutning och glapp.
- Evolvent, cykloidal och rak kuggprofil, med överstyrning per kugg.
- Nav och borrhål: runt, D-snitt, dubbel-D, kilspår (DIN 6885), sexkant och fyrkant.
- Millimetre eller tum.

**Live 3D-förhandsvisning**

Rotera och zooma den riktiga meshen och granska exakt de kuggar du ska exportera.
Ångra och gör om varje ändring.

**Beprövade förinställningar**

Utgå från rekommenderade planetsteg på 3:1, 5:1 och 7:1 för växelmotorer, robotik
och högmoment-reducerare — eller ställ in alla parametrar själv.

**Resultat medan du skriver**

Delnings-, ytter-, fot- och basdiameter, axelavstånd, utväxling, kuggmått, vikt,
tröghetsmoment och effektivt spel. Validering varnar innan ett fysiskt omöjligt
kugghjul kan exporteras.

**Sex exportformat**

- **STL & 3MF** för 3D-utskrift.
- **STEP & IGES** för CAD och CAM.
- **DXF** för laserskärning och CNC.
- **SVG** för 2D-layout och dokumentation.

Filerna sparas direkt i mappen Hämtade filer med antal trianglar och mått.

**Gratis att använda**

Varje installation innehåller 3 gratisexporter. Lås upp obegränsade exporter och
högkvalitativ mesh med engångsköpet **Pro**, eller se en kort belöningsvideo för
att tjäna in en extra export. Inget konto krävs.

GearForge respekterar din integritet: vi samlar aldrig in namn, e-post, plats
eller filer, och dina konstruktioner laddas aldrig upp. Annonsering (när den
visas) använder Google AdMob med samtyckeshantering i tillämpliga regioner.

---

## 3. Store asset checklist

> All store graphics are generated by two scripts, so they can be rebuilt
> deterministically instead of edited by hand:
>
> ```
> py tools/store-screenshots/build_slides.py    # 7 EN + 7 SV annotated screenshots
> py tools/store-assets/build_store_assets.py   # 512 px app icon + feature graphic
> ```
>
> `build_slides.py` crops the Android bars out of the raw emulator captures from
> `tools/store-screenshots/_capture`, lays the copy over the app's own hero
> background and renders at exactly 1080 × 1920 with headless Edge, then forces
> 24-bit RGB. Every headline claim is checked against the app source.

| Asset | Required | Recommended size / spec | Status |
|---|---|---|---|
| App icon (store listing) | Yes | **512 × 512 px**, 32-bit PNG, ≤ 1 MB | **Done** — [`store-assets/app-icon-512.png`](store-assets/app-icon-512.png). Play needs 512 px but the largest launcher mipmap is only 192 px, so the icon is re-rendered from the adaptive sources (white background + `ic_launcher_foreground`) rather than upscaled |
| Feature graphic | Yes | **1024 × 500 px**, JPG or 24-bit PNG (no alpha), ≤ 1 MB | **Done** — [`store-assets/feature-graphic.png`](store-assets/feature-graphic.png), flattened to 24-bit RGB (the original had an alpha channel, which Play rejects) |
| Phone screenshots | Yes (min 2, max 8) | **1080 × 1920 px**, JPG/PNG 24-bit no alpha; min 320 px, max 3840 px per side | **Done** — 7 EN in [`store-assets/final/en`](store-assets/final/en) + 7 SV in [`store-assets/final/sv`](store-assets/final/sv); index in [`store-assets/final/manifest.csv`](store-assets/final/manifest.csv) |
| Tablet 7″ / 10″ screenshots | Optional | 16:9 or 9:16, same constraints | Not produced — the phone set is the required one |
| Promo video | Optional | YouTube URL, 30 s – 2 min | Optional |
| TV banner | Optional | 1280 × 720 px | Optional |

Screenshot set (Play allows at most 8 per language):

| # | EN | SV |
|---|---|---|
| 1 | Design the gear. Print the gear. | Designa kugghjulet. Skriv ut det. |
| 2 | Spur, helical, bevel, planetary and more | Rak, sned, konisk, planetväxel & fler |
| 3 | Start from a proven reduction ratio | Börja från en beprövad utväxling |
| 4 | Inspect the exact teeth you are going to print | Granska kuggen du ska skriva ut |
| 5 | Module, teeth, pressure angle | Modul, kuggantal, tryckvinkel |
| 6 | Every value, calculated instantly | Varje värde räknas ut direkt |
| 7 | STL · 3MF · STEP IGES · SVG · DXF | STL · 3MF · STEP IGES · SVG · DXF |

Screenshots were captured on an API 36 emulator in **English** and **Swedish**
(the app is fully localized via [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt))
using the app's own in-app language setting, so each language shows the real
localized UI.

The current set was re-captured and rebuilt on **2026-10-03** from the frozen,
device-verified build (versionCode 9 / 1.1, debug APK `CD226EEC…`) so it reflects
the 2026-10 parameter-panel and accessibility rework; the raw captures live in
`tools/store-screenshots/_capture/` (EN `01-launch.png` … `14-export.png`,
SV `sv-01-landing.png` … `sv-06-export.png`) and were produced by
`build/audit/preupload-20261002/capture-all.ps1` against a freshly cleared app
(3 free exports visible in the export slide).

> The raw captures in [`store-assets`](store-assets) root (`1-landing.png` …
> `5-export.png`) are the old, unannotated drafts the tester report criticised.
> Upload from `store-assets/final/` instead.

---

## 4. Privacy policy & Data Safety

- **Privacy-policy source of truth:** [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).
  It must be **hosted at a public URL** and that URL entered in
  **Google Play Console → App content → Privacy policy** before release.
- **Data Safety form:** complete Play Console → App content → Data safety so the
  answers match [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md). The authoritative answer key
  (per data type, purpose, sharing and the Google Mobile Ads SDK disclosure) is
  [`build/audit/preupload-20261002/PLAY_CONSOLE_FORM_GUIDE.md`](build/audit/preupload-20261002/PLAY_CONSOLE_FORM_GUIDE.md).
  Summary of the ad-SDK data that must be declared as collected and shared (per
  [Google's own Play data disclosure for the ads SDK](https://developers.google.com/admob/android/privacy/play-data-disclosure),
  read 2026-10-02): IP address (may estimate general location), user product
  interactions (app launch, taps, video views), diagnostic information (launch time,
  hang rate, energy usage) and device/account identifiers (ad ID, app set ID) — all
  encrypted in transit. The app itself collects no personal data and hosts no server.
- **Purchase history** is processed by Google Play Billing (not the app itself).
- **Target audience / UAC (Play Console only):** see
  [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) section 5 — declare target
  audience, confirm the families policy stance, and decide/declare User Choice
  Billing (UAC) for EEA/UK users.
- **AdMob production IDs:** the release bundle is built with the real App ID and
  rewarded unit ID — Google's test IDs cannot ship, because
  [`android/build.gradle`](android/build.gradle) throws on them. See
  "Building the upload bundle" above and keep
  [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) section 1 in sync.

### Consent form language (UMP) — device locale, not the in-app language

The consent form is rendered by Google's UMP SDK, **not** by GearForge. The SDK chooses
the form language from the **device locale**, matched against the languages configured on
the consent message in the AdMob console. There is no UMP API to force a locale, and
[`ConsentManager.kt`](android/src/main/java/com/gearforge/app/ConsentManager.kt) passes no
locale hint (`ConsentRequestParameters` carries only the under-age tag). GearForge's own
EN/SV switcher therefore **cannot** influence the form.

Observed on the `mc-target` emulator (system language English, in-app language Swedish):
the consent form appeared in English over a Swedish app — the first screen a new user
sees can differ in language from the app behind it.

2026-10-02 update: on Android 13+ the in-app switch now also sets the **per-app locale**
(`LocaleManager` via `MainActivity.applyAppLocale`), which changes the locale the app's
own configuration reports. This *may* make UMP follow the in-app choice on those devices
(the SDK still reads the app locale, and there is no API to force it). The emulator
observation above predates that change, so re-verify the consent form language on an
API 33+ device in both switch directions before claiming the form matches the app
language. Below API 33 there is no per-app locale and the original note stands.

**Action required in Play Console / AdMob (not a code change):** add **both English and
Swedish** to the consent message under *Privacy & messaging → GDPR/IDFA message*. A
device whose locale is Swedish falls back to the message's default language if Swedish is
not configured on it. Re-verify on an emulator or device whose **system** language is
Swedish, and do not promise a Swedish consent form until that test passes.

---

## 5. targetSdk 36 upgrade path (ACTION_PLAN point 32)

**Status:** complete in the toolchain — `targetSdk 36` is in effect (see
[`android/build.gradle`](android/build.gradle)) and predictive back is implemented
(`android:enableOnBackInvokedCallback="true"`; note that for `targetSdk` 33+ the
system enables it by default). Step 3 below was corrected 2026-10-02: an earlier
revision of this document said the attribute was absent, which was wrong. The back
transitions were re-verified on the API 36 emulator as part of the 2026-10-02 final
pass; see the audit ledger for the evidence.

**Deadline:** Google Play requires new apps and updates to target **Android 16
(API level 36)** from **August 2026** (new apps) — updates to existing apps must
follow on the same schedule. Plan the migration before the deadline.

**Migration steps:**

1. **Toolchain first** — bump `compileSdk` and `targetSdk` to `36` in
   [`android/build.gradle`](android/build.gradle) and update the Android Gradle
   Plugin, Kotlin, and Compose BOM to versions that officially support API 36.
2. **Edge-to-edge** — API 35 already enforces edge-to-edge for `targetSdk 35+`;
   verify insets handling in Compose (`enableEdgeToEdge`, `WindowInsets`) and the
   libGDX `TextureView` surface stays clear of system bars.
3. **Predictive back** — **implemented.** `android:enableOnBackInvokedCallback="true"`
   is declared on `<application>` in
   [`AndroidManifest.xml`](android/src/main/AndroidManifest.xml) (the `UnusedAttribute`
   lint suppression on the element exists because the API-gated attributes are "unused"
   below minSdk 24; it is intended). With `targetSdk 33+` the platform enables predictive
   back by default regardless of the attribute, and `BackHandler` in `MainActivity` and
   the wizard drives every back transition. The API 36 back transitions (editor -> landing,
   wizard step-back) were re-verified on the emulator on 2026-10-02; the audit ledger
   records the result.
4. **Behavioral changes** — review the API 35→36 behavior changes that affect
   this app (job scheduling, foreground-service restrictions, notification
   permissions — none are used, but confirm).
5. **Re-test the Phase 1 surface** (the risky integration points):
   - **UMP consent** — consent form still shows before ads on an EEA device and
     respects the user choice.
   - **Billing** — Pro purchase, acknowledge, restore, and PENDING handling still
     work; no Billing library compatibility warnings at API 36.
   - **Compose** — no rendering/layout regressions after the BOM/AGP bump.
   - **R8/release** — rebuild the signed release with shrinking enabled and verify
     the app boots (no stripped `BuildConfig`/reflection issues).
6. **Version bump** — increment `versionCode` (and `versionName` as appropriate)
   for the targetSdk-36 release.

**Rollback plan:** if API 36 blocks release, revert `targetSdk` to `35` and ship,
then fix and retry — do not ship with a half-migrated toolchain.

---

## 6. Tester-report follow-up

The closed-test report listed four opportunities. All four are addressed.

| # | Opportunity | Status |
|---|---|---|
| 1 | ASO optimisation of the description | **Done** — sections 1–2 above add keyword-led titles, the 14 gear types, all six export formats and skimmable bullet lists |
| 2 | Enhanced Play Store screenshots | **Done** — 7 annotated EN + 7 annotated SV screenshots, each with a feature headline, a supporting line and a real device capture |
| 3 | Dynamic walkthrough for new users | **Done** — four first-run tips across the wizard and the editor ([`Tips.kt`](android/src/main/java/com/gearforge/app/Tips.kt), shown from `GearWizard.kt` and `GearWorkspace.kt`), the position persisted in `SettingsStore.tipsStep`, and a "Show tips again" row in Settings |
| 4 | "Rate your app" button / In-App Review | **Done in versionCode 7** — the Play In-App Review card is requested once per install after a successful export ([`InAppReview.kt`](android/src/main/java/com/gearforge/app/InAppReview.kt)), plus a "Rate this app" row in Settings that opens the Play listing |

### Claims verified against the build

Use only these when answering Play's production-application questions:

| Claim | Where it is verified |
|---|---|
| 14 gear types | [`GearModel.kt`](core/src/main/java/com/gearforge/core/GearModel.kt) — `enum class GearType` |
| 6 export formats: STL, 3MF, STEP, IGES, SVG, DXF | [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt) — `enum class Format` |
| Nominal 45-degree exterior chamfer at free hub ends | [`HubBuilder.kt`](core/src/main/java/com/gearforge/core/HubBuilder.kt) - `build`, `chamferLimit`; [`Loft.kt`](core/src/main/java/com/gearforge/core/Loft.kt) - `loftWithOuterChamfer`; mesh dimensions/topology covered by `GearAdvancedTest`. Hub and gear remain separate closed touching bodies. |
| Unique retained share copies outside the evictable cache | [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt) - `createShareCopy`, `SHARE_RETENTION_MILLIS`: private files, seven-day age cleanup on a later share. URI grants, uninstall and data clear still limit recipient access; actual recipient delivery is not certified. |
| Saves to Downloads with triangle count and dimensions | The export dialog shows file name, triangle count, size and "Free exports left" |
| 3 free exports, then Pro or one rewarded video | [`SettingsStore.kt`](android/src/main/java/com/gearforge/app/SettingsStore.kt) — `freeAdvancedExports` defaults to 3; [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt) — `launchExport()` |
| Pro = unlimited exports + high-quality mesh | `GearWorkspace.kt` — `isPro -> doExport(consumeFree = false)`, and `highQuality = isPro && settings.highQuality` |
| UMP consent before ads | `ConsentManager.kt`, requested from `MainActivity` |
| Privacy-options entry point when regulators require it | [`ConsentManager.kt`](android/src/main/java/com/gearforge/app/ConsentManager.kt) — `privacyOptionsRequired()` (UMP `PrivacyOptionsRequirementStatus.REQUIRED`) and `showPrivacyOptions()`; the row is shown from `SettingsDialog` only when required; I18n key `privacy_options` exists in both catalogues |
| Per-app language (EN/SV) on Android 13+ | [`locales_config.xml`](android/src/main/res/xml/locales_config.xml) + `android:localeConfig` in the manifest; `MainActivity.applyAppLocale` (`LocaleManager`) on both language switches. Below API 33 the in-app switch drives every string through `I18n` |
| Undo/redo, presets and validation | Toolbar undo/redo, `PresetLibraryPage`, `GearSpec.validate` error gate in `startExport()` |
| In-App Review card after a successful export | `InAppReview.maybeRequestAfterExport()`, called at the end of `ExportSheet.doExport()`; one-shot flag `SettingsStore.reviewRequested` |
| "Rate this app" opens the Play listing | `InAppReview.openStoreListing()`, wired to the Settings dialog; falls back to the web listing when the Play Store is absent |
| A first-run walkthrough, four tips, re-showable from Settings | [`Tips.kt`](android/src/main/java/com/gearforge/app/Tips.kt) — the ordered state machine `WIZARD_TYPE` … `DONE`, drawn from `GearWizard.kt` and `GearWorkspace.kt`; `SettingsStore.tipsStep`; `SettingsDialog(onShowTipsAgain = …)`; verified end to end in the emulator with `tools/stress-app.ps1 -Action walkthrough` on API 26 and API 36 |
| Print bed at true scale, labelled with its size and grid | [`BedOverlay.kt`](android/src/main/java/com/gearforge/app/BedOverlay.kt) — `bed_label`, `bed_grid`, millimetre ruler; `GearGLView.bedSizeMm` |
| Playback speed 0.125×–1× of the base rate, persisted | `PLAYBACK_SPEEDS` (halvings slowest first, `1×` = one revolution per second = the base and the default) in [`SettingsStore.kt`](android/src/main/java/com/gearforge/app/SettingsStore.kt); `GearGLView.playbackScale`; `PlaybackClock` |
| Back gesture inside the wizard walks one step | [`GearWizard.kt`](android/src/main/java/com/gearforge/app/GearWizard.kt) — `WizardFlow.backFrom` + `BackHandler`; `WizardFlowTest` |
| Android 12+ device transfer and cloud backup both exclude app data | `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml`, declared on `<application>` |
| Version code of the bundle this snapshot describes | `10` — `android/build.gradle` `defaultConfig.versionCode`, and the release snapshot at the top of this file (9 was rejected as already used on 2026-10-03; the bundle content is otherwise identical) |

**Do not claim** a feedback form or a community feature — neither exists in the current
build. The onboarding walkthrough that used to be listed here does exist now; it is claimed
above.
