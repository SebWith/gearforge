---
description: "Installera på emulator, navigera, fånga skärmdump och logcat-fel"
agent: agent
---

# Verifiera i emulatorn

Kör en interaktiv verifiering av en skärm eller ett flöde och leta efter runtime-fel.
Returnera antingen **VERIFIED_SUCCESS** eller hela felstacken.

## Förbered

```powershell
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
& $adb devices
& $adb shell getprop ro.build.version.sdk
```

Om ingen enhet är ansluten: starta en AVD (`Pixel7`, `mc-api26`, `mc-target`,
`EggHunt`) och vänta tills `sys.boot_completed` är 1.

## Bygg och installera

```powershell
.\gradlew.bat :android:assembleDebug --console=plain
& $adb install -r android\build\outputs\apk\debug\android-debug.apk
```

## Rensa loggen och starta

```powershell
& $adb logcat -c
& $adb shell am force-stop com.gearforge.geargenerator
& $adb shell am start -n com.gearforge.geargenerator/com.gearforge.app.MainActivity
```

Observera paketnamnet: `applicationId` är `com.gearforge.geargenerator` medan
`namespace` är `com.gearforge.app` — aktiviteten ligger i `app`-paketet.

## Navigera

Använd hjälpfunktionerna i `tools/store-screenshots/capture.ps1` (`. .\tools\store-screenshots\capture.ps1`)
som ger `Tap`, `Swipe`, `Shot`, `Back`, `SetAppLocale` och `LogcatErrors`.

```powershell
. .\tools\store-screenshots\capture.ps1
Tap 540 1804
Shot "verify-01"
```

## Fånga fel

```powershell
& $adb logcat -d -t 400 "*:E" | Select-String "gearforge|AndroidRuntime|FATAL|EGL|OpenGL|MediaStore|Billing|Ads"
```

Leta särskilt efter:

- `FATAL EXCEPTION` / `AndroidRuntime` — krasch, returnera hela stacken.
- `EGL_BAD_*`, `GL_INVALID_*`, `eglCreateWindowSurface failed` — GL-livscykeln.
- `RenderThread`- eller `Skipped N frames`-varningar — prestanda/ANR-risk.
- `Billing`-felkoder — köpflödet.
- `MediaStore`/`SecurityException` — exporten till Downloads.

## Verifiera minne (vid upprepade typbyten)

```powershell
& $adb shell dumpsys meminfo com.gearforge.geargenerator | Select-String "TOTAL|Native|EGL"
```

Byt kugghjulstyp 10 gånger och kör kommandot igen. Växer `Native Heap` eller
`EGL mtrack` obegränsat läcker GL-resurser (ACTION_PLAN punkt 21).

## Verifiera lokalisering

```powershell
SetAppLocale 'sv'
Shot "verify-sv-01"
```

Leta efter engelska ord i den svenska vyn.

## Döm

- **VERIFIED_SUCCESS** — appen startar, flödet fungerar, inga `FATAL`/`EGL`-fel i
  loggen, minnet stabilt.
- Annars: returnera exakt loggrad, stacktrace och vilken fil som troligen äger felet.
  Spekulera inte — citera loggen.
