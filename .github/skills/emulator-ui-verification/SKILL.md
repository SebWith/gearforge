---
name: emulator-ui-verification
description: "Verifiera GearForge i emulatorn via adb och fånga runtime-fel. Use when: verifiera ett just genomfört mikrosteg eller en kodändring; installera APK och navigera; fånga krascher, GL-fel eller minnesläckor; kontrollera lokalisering i appen; ta skärmdump för granskning. Returnerar VERIFIED_SUCCESS eller hela felstacken."
---

# Emulatorverifiering (Android)

Android-versionen av verifieringsloopen. Ersätter den Flutter-orienterade varianten
(`flutter analyze`/hot reload gäller inte detta repo).

## Miljö — proba alltid först

```powershell
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
& $adb version          # probe: finns adb?
& $adb devices          # probe: finns en enhet?
```

- `adb` ligger på `C:\Android\sdk\platform-tools\adb.exe` och finns **inte** i PATH.
- `local.properties` pekar på en annan SDK (`C:/Users/sebbe/android-dev/android-sdk`).
  Gradle använder den; `adb` använder den andra. Det är avsiktligt men förvirrande.
- AVD:er: `Pixel7`, `mc-api26`, `mc-target`, `EggHunt`.
- Använd `py`, aldrig `python` (trasig Store-alias, exit 9009).
- Kedja med `;` i PowerShell, aldrig `&&`.
- Repo-sökvägen innehåller `ö` — citera absoluta sökvägar.

## Paketnamnet — fällan som ser ut som en appkrasch

```
applicationId = com.gearforge.geargenerator     # används av adb, Play, MediaStore
namespace     = com.gearforge.app               # används av aktiviteten
```

```powershell
# Rätt
& $adb shell am start -n com.gearforge.geargenerator/com.gearforge.app.MainActivity
# Fel — startar inget, ser ut som ett appfel
& $adb shell am start -n com.gearforge.geargenerator/.MainActivity
```

## Loopen

```powershell
# 1. Bygg och installera
.\gradlew.bat :android:assembleDebug --console=plain
& $adb install -r android\build\outputs\apk\debug\android-debug.apk

# 2. Rensa loggen FÖRE flödet (annars fångar du gamla fel)
& $adb logcat -c
& $adb shell am force-stop com.gearforge.geargenerator
& $adb shell am start -n com.gearforge.geargenerator/com.gearforge.app.MainActivity

# 3. Navigera
. .\tools\store-screenshots\capture.ps1
Tap 540 1804
Shot "verify-01"

# 4. Fånga fel
& $adb logcat -d -t 400 "*:E" | Select-String "gearforge|AndroidRuntime|FATAL|EGL|OpenGL|MediaStore|Billing|Ads|review"
```

Steg 2 är viktigt: `adb logcat -c` **före** flödet. Utan det fångar du fel från
tidigare körningar och rapporterar falska positiva.

`capture.ps1` ger `Tap`, `Swipe`, `Shot`, `Back`, `SetAppLocale`, `LogcatErrors`.
Använd dem i stället för egna adb-anrop — de innehåller rätt väntetider (UI:t
behöver ~1,2 s mellan tryck).

## Felläsning

| Mönster i loggen | Betyder | Äger |
|---|---|---|
| `FATAL EXCEPTION`, `AndroidRuntime` | Krasch | Returnera hela stacken |
| `EGL_BAD_*`, `GL_INVALID_*`, `eglCreateWindowSurface failed` | GL-livscykeln (punkt 6) | `GearGLView.kt` |
| `Skipped N frames`, `ANR in` | Blockering av huvudtråden (punkt 18) | `GearWorkspace.kt` |
| `MediaStore`, `SecurityException` | Exporten till Downloads (punkt 2) | `ExportManager.kt` |
| `Billing`-felkod | Köpflödet (punkt 27) | `BillingManager.kt` |
| `Consent`, `Ads` | UMP/annonser (punkt 31) | `ConsentManager.kt`, `AdManager.kt` |

## Minne — GL-läckor syns bara över tid

```powershell
& $adb shell dumpsys meminfo com.gearforge.geargenerator | Select-String "TOTAL|Native|EGL"
```

Byt kugghjulstyp 10 gånger, kör kommandot igen. `Native Heap` och `EGL mtrack` ska
vara stabilt över tid; obegränsad tillväxt betyder att VBO:er inte frigörs
(ACTION_PLAN punkt 21).

## Lokalisering

```powershell
SetAppLocale 'sv'    # sätter app-locale, startar om, väntar 6 s
Shot "verify-sv"
SetAppLocale 'en'
```

Fungerar per-app på API 33+. Granska skärmdumpen — leta efter engelska ord i den
svenska vyn.

## Render on demand (punkt 20)

Vid stillastående vy ska inga frames ritas:

```powershell
& $adb logcat -c
# rör inte skärmen i 10 sekunder
& $adb logcat -d | Select-String "requestRender|onDrawFrame"
```

Kontinuerlig aktivitet utan input betyder att en loop ritar utan förändring.

## Dom

- **VERIFIED_SUCCESS** — appen startar, flödet genomfört, inga `FATAL`/`EGL`-fel,
  minne stabilt, skärmdump granskad.
- **FEL** — loggrad, stacktrace, trolig ägare.

Rapportera aldrig grönt utan att ha citerat loggen.
