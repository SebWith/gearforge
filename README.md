# GearForge - 3D Gear Generator

Parametrisk kugghjulsgenerator för Android. Designa, granska och exportera
3D-utskrivbara kugghjul — ingen CAD-erfarenhet krävs.

> **Status:** v1.1 är byggd och butiksklar. `versionCode 9`, `targetSdk 36`,
> signerad release via `tools/build-release-aab.ps1`. Se
> [`STORE_READINESS.md`](STORE_READINESS.md) för aktuell release-snapshot.

## Vad appen gör

| | |
|---|---|
| **Kugghjulstyper** | 14 — rakt, snedskuret, koniskt, kuggstång, planetväxel, snäckskruv, invändig ring, hypoid, cykloidal, harmonic drive, face gear, skruvhjul, sammansatt, kuggrem |
| **Exportformat** | 6 — STL, 3MF, STEP, IGES, SVG, DXF |
| **Språk** | Engelska och svenska |
| **Modell** | 3 gratisexporter, därefter engångsköp **Pro** eller en belöningsvideo |

Siffrorna är uppslagna i koden: `GearType` i
[`GearModel.kt`](core/src/main/java/com/gearforge/core/GearModel.kt),
`ExportManager.Format` i
[`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt),
`freeAdvancedExports` i
[`SettingsStore.kt`](android/src/main/java/com/gearforge/app/SettingsStore.kt).

## Moduler

| Modul | Innehåll | Testas |
|---|---|---|
| [`core/`](core) | Ren Kotlin: kugghjulsmatematik, mesh, de sex filskrivarna. **Ingen Android-import.** | 17 testfiler, ingen emulator behövs |
| [`android/`](android) | Compose-UI, egen libGDX OpenGL-renderare, AdMob, Billing, UMP, export till disk | `GizmoMathTest.kt` |

Att matematiken ligger i `core` är avsiktligt: den går att testa på JVM, vilket är
hela skälet till att testsviten kan vara så pass stor utan emulator.

## Bygga och testa

```powershell
.\gradlew.bat :core:test                 # snabbast — ingen emulator
.\gradlew.bat :core:test --tests "*SpurProfileTest*"
.\gradlew.bat :android:assembleDebug
.\gradlew.bat :android:lint
```

Kör alltid `:core:test` efter en ändring i `core/`. Sviten fångar
geometriregressioner som annars bara syns som en konstig modell i appen.

CI kör samma saker automatiskt — se
[`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml).

### Verktyg

```powershell
py tools/i18n_audit.py                       # EN/SV-paritet i I18n.kt
py tools/verify_export.py fil.stl            # struktur, triangelantal, bounding box
py tools/verify_export.py fil.stl --expect-diameter 22.0
py tools/store-screenshots/build_slides.py   # 14 skärmdumpar
py tools/store-assets/build_store_assets.py  # ikon + feature graphic
```

Använd `py`, inte `python` — `python` är en trasig Microsoft Store-alias i den här
miljön (exit 9009).

## Release

`android/build.gradle` **vägrar** bygga `bundleRelease`/`assembleRelease` med
Googles test-AdMob-ID:n. Det är avsiktligt: testannonser ger ingen intäkt och bryter
mot AdMob-policy.

Sätt `$expectedVersionCode` och `$expectedVersionName` från den avsedda versionen i
`android/build.gradle`, och bekräfta hela `$trustedUploadCertSha256` från en
oberoende betrodd källa (exempelvis Play Console), aldrig från den byggda AAB:n.

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
    -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
  -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
  -ExpectedVersionCode $expectedVersionCode -ExpectedVersionName $expectedVersionName `
  -ExpectedCertificateSha256 $trustedUploadCertSha256
```

JDK 17+, `py` och hashkontrollerad bundletool 1.18.2 krävs. Skriptet verifierar
exakta ID:n, paket, version, signatur och certifikat samt skriver ett hashbundet
JSON-kvitto. Kräv `Verified AAB` och kvittot från körningens `Receipt`-rad.
Detta ersätter inte köp- och enhetstester. Fullständig ritual i
[`.github/skills/android-release-guard/SKILL.md`](.github/skills/android-release-guard/SKILL.md).

`android/keystore.properties`, `android/release.keystore` och `.github-token` är
gitignorade hemligheter som aldrig ska committas eller loggas.

## Dokumentation

| Fil | Roll |
|---|---|
| [`ACTION_PLAN.md`](ACTION_PLAN.md) | 32 punkter i 5 faser — styrande implementationsspec |
| [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) | Sanning för AdMob-ID, Billing, UMP och gating |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Sanning för integritet och Data Safety |
| [`STORE_READINESS.md`](STORE_READINESS.md) | Butiksklarhet, listing-copy, claim-tabell |
| [`CHANGELOG.md`](CHANGELOG.md) | Keep a Changelog |
| [`docs/`](docs) | Designdokument |
| [`archive/`](archive) | Arkiverad tidigare iteration (MathWheelGame, Sweep Runner) |

## Agentanpassningar

Repot har inbyggd kontext för AI-assistenter i `.github/`:

- [`copilot-instructions.md`](.github/copilot-instructions.md) — projektets regler
  och modulkarta.
- `instructions/` — filspecifika regler för `core`, Compose-UI, exportskrivare och
  dokumentation.
- `skills/` — domänkunskap: kärngeometri, exportformat, i18n, release, butikstillgångar,
  emulatorverifiering, GL-renderaren.
- `agents/` — specialiserade agenter (geometri, release, emulator, butik, tillgänglighet).
- `prompts/` — `/release-aab`, `/verify-core`, `/verify-ui`, `/i18n-audit`,
  `/store-assets`, `/doc-sync`.
- `hooks/` — `gearforge-guard` kör typkontroll, i18n-paritet och hemlighetskontroll
  efter varje redigering.

## Licens

MIT — se [`LICENSE`](LICENSE).
