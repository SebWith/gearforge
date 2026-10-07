# API 26 pass: install, grant the LEGACY storage permission, and prove the app is there.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
# API 26 matters because it takes a different export path: MediaStore.Downloads does not exist
# before API 29, so the app writes straight to public Downloads and needs a runtime
# WRITE_EXTERNAL_STORAGE grant. Granting it here is what lets the export lane run at all.
param(
    [string]$Serial = "emulator-5556",
    [string]$Repo = "$PSScriptRoot\.."
)

$env:ANDROID_SERIAL = $Serial
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$pkg = "com.gearforge.geargenerator"
$activity = "com.gearforge.geargenerator/com.gearforge.app.MainActivity"

Write-Host "=== target=$Serial ==="
& $adb shell getprop ro.build.version.sdk
& $adb shell getprop sys.boot_completed

Write-Host "=== install ==="
& $adb install -r "$Repo\android\build\outputs\apk\debug\android-debug.apk" | Select-Object -Last 1

Write-Host "=== grant legacy storage ==="
& $adb shell pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE 2>&1 | ForEach-Object { Write-Host $_ }

Write-Host "=== package ==="
& $adb shell pm path $pkg
& $adb shell dumpsys package $pkg | Select-String "versionCode" | Select-Object -First 1
& $adb shell dumpsys package $pkg | Select-String "WRITE_EXTERNAL_STORAGE" | Select-Object -First 3

Write-Host "=== start ==="
& $adb shell svc power stayon true | Out-Null
& $adb shell am start -n $activity | Out-Null
Start-Sleep -Seconds 8
& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1
