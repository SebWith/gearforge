---
applyTo: "**/*.md"
---

# Dokumentation, butiksmaterial och påståenden

Repot har tre dokument som fungerar som *sanning* för var sitt område. De får inte
glida ifrån koden.

| Dokument | Äger |
|---|---|
| `MONETIZATION_CONFIG.md` | AdMob-ID, Billing, UMP, gating-regler, strategibeslut |
| `PRIVACY_POLICY.md` | Integritet, Data Safety-svar |
| `STORE_READINESS.md` | Listing-copy, butikstillgångar, claim-tabell, targetSdk-plan |

## Regeln om verifierade påståenden

`STORE_READINESS.md` har en tabell **"Claims verified against the build"**. Varje
påstående där ska peka på en fil och ett symbolnamn som faktiskt finns.

Innan du skriver eller ändrar ett påstående:

1. Öppna filen som påståendet refererar till.
2. Verifiera att symbolen finns och gör det som påstås.
3. Skriv in var det är verifierat.

**Skriv aldrig ett påstående om en funktion som inte finns i koden.** Detta har
varit en aktiv risk: feedbackformuläret och community-funktionen finns inte byggda
och får inte nämnas. Onboarding-genomgången var den tredje posten på den listan och
är nu **byggd** — fyra tips vid första körningen, i `Tips.kt`, med `tipsStep` i
`SettingsStore` och "Visa tipsen igen" i Settings. Den får nämnas, och den ska
verifieras med `tools\stress-app.ps1 -Action walkthrough` innan den påstås fungera.
Se avsnitt 6 i `STORE_READINESS.md`.

Siffror slås upp, inte gissas:

| Fakta | Slå upp i |
|---|---|
| Antal kugghjulstyper | `core/.../GearModel.kt` → `enum class GearType` (14) |
| Antal exportformat | `android/.../ExportManager.kt` → `enum class Format` (6) |
| Antal gratisexporter | `android/.../SettingsStore.kt` → `freeAdvancedExports` (3) |
| `versionCode`/`versionName` | `android/build.gradle` → `defaultConfig` |
| `targetSdk`/`minSdk` | `android/build.gradle` → `defaultConfig` |

## Varumärke

- Appen heter **`GearForge - 3D Gear Generator`**. Ett ord.
- Skriv aldrig "Gear Forge", "Gear-Forge" eller "Gearforge" i något dokument,
  någon sträng eller någon fil.
- Titeln i `res/values/strings.xml` (`app_name`) och i `I18n.kt` ska vara identiska.

## Språkparitet EN/SV

- All copy finns i två versioner: engelska och svenska. Ändrar du den ena måste den
  andra uppdateras i samma ändring.
- Svenska texter skrivs på svenska — inte maskinöversatt engelska.
- Aktiva verb och "du"-form: "Ändra ett värde, se exakt geometri".

## Play-tillgångar (`store-assets/`)

Genereras av skript, redigeras aldrig för hand:

```powershell
py tools/store-screenshots/build_slides.py     # 14 skärmdumpar + manifest.csv
py tools/store-assets/build_store_assets.py    # 512 px ikon + feature graphic
```

Krav som Play faktiskt avvisar på:

- **Ikon:** 512 × 512 px, 32-bitars PNG, ≤ 1 MB. Största launcher-mipmapen är bara
  192 px — ikonen renderas därför om från de adaptiva källorna, den skalas inte upp.
- **Feature graphic:** 1024 × 500 px, **ingen alpha-kanal**, ≤ 1 MB.
- **Skärmdumpar:** 1080 × 1920 px, 24-bitars RGB utan alpha, max 8 per språk.
- Ändrar `build_slides.py` bilden måste `normalise()` fortfarande tvinga 24-bitars
  RGB — annars smyger en alpha-kanal tillbaka in.

**Fällan med `--only`:** `py tools/store-screenshots/build_slides.py --only en`
skriver **om** `manifest.csv` med bara de raderna. Kör alltid hela körningen innan
du committar manifestet.

**Crop-sömmen:** `Slide.offset` flyttar beskärningsfönstret nedåt. Landar gränsen
mitt i en textrad ser bilden skuren ut (hände `sv-06`). Använd
`py tools/store-screenshots/pick_offset.py <fil>` för att hitta en ren söm.

## CHANGELOG

- Följer [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) med sektionerna
  `Added` / `Changed` / `Fixed` / `Removed`.
- Ny version ⇒ ny `## [x.y] - YYYY-MM-DD`-rubrik. Skriv vad som ändrats för
  användaren, inte vilka filer som rörts.
- `versionCode`-historiken spåras här eftersom Play aldrig accepterar ett
  återanvänt värde.

## Skrivstil i repots dokument

- Dokumenten är på engelska (utom `ACTION_PLAN.md` och
  `lanseringsplan-forbattringar.md` som är på svenska). Håll dig till filens språk.
- Konkreta siffror och filsökvägar i stället för "flera" och "vissa".
- Använd tabeller för checklistor — de är skannbara och svårare att ljuga i.
