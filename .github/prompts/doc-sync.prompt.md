---
description: "Kontrollera att dokumentationens påståenden stämmer med bygget"
agent: agent
---

# Dokumentsynk

Repot har en regel: **påståenden i dokumentation ska vara verifierade mot bygget.**
Den här körningen kontrollerar att det fortfarande gäller.

## Slå upp varje sifferuppgift

Läs filen — gissa inte.

| Påstående | Källa som måste stämma |
|---|---|
| Antal kugghjulstyper (14) | `core/src/main/java/com/gearforge/core/GearModel.kt` → `enum class GearType` |
| Antal exportformat (6) | `android/src/main/java/com/gearforge/app/ExportManager.kt` → `enum class Format` |
| Gratisexporter (3) | `android/.../SettingsStore.kt` → `freeAdvancedExports` |
| Pro = obegränsat + hög kvalitet | `android/.../GearWorkspace.kt` → `doExport`/`highQuality` |
| UMP före annonser | `android/.../ConsentManager.kt` och `MainActivity.kt` |
| In-App Review efter export | `android/.../InAppReview.kt` → `maybeRequestAfterExport()` |
| Betygsknapp öppnar Play | `android/.../InAppReview.kt` → `openStoreListing()` |
| `versionCode`/`versionName` | `android/build.gradle` → `defaultConfig` |
| `targetSdk`/`minSdk` | samma |

## Leta efter påståenden utan täckning

Gå igenom `STORE_READINESS.md`s claim-tabell och verifiera att varje rad pekar på en
symbol som faktiskt finns. Ta bort eller flytta rader som inte längre stämmer.

Kontrollera särskilt att dessa **inte** påstås finnas — de är inte byggda:

- onboarding eller genomgång för nya användare
- feedbackformulär
- community-funktion

## Kontrollera sifferparitet mellan dokument

Samma siffra ska vara identisk i `STORE_READINESS.md`, `MONETIZATION_CONFIG.md`,
`CHANGELOG.md` och butikscopyn. Sök efter avvikelser:

```powershell
Select-String -Path '*.md' -Pattern '\b(1[0-9]|[0-9]) (gear types|kugghjulstyper|export formats|exportformat)\b'
Select-String -Path '*.md' -Pattern 'versionCode'
```

## Kontrollera att kodreferenser inte ruttnat

Länkar i dokumenten pekar på filer och radnummer. Verifiera att filerna finns:

```powershell
Select-String -Path 'STORE_READINESS.md','MONETIZATION_CONFIG.md' -Pattern '\]\(([^)]+\.(kt|gradle|xml))' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
```

## Rapportera

En tabell: påstående, källa, verifierat värde, status (OK / avvikelse). Föreslå
exakt textändring för varje avvikelse — ändra inte dokumenten utan att visa vad som
ändras och varför.
