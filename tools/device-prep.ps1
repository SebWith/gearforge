# Wait for a device to finish booting, then install the debug APK and start the app.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
# Establishes the precondition every device test assumes. "adb devices" saying "device" is not
# the same as the system being up: sys.boot_completed is.
param(
    [string]$Serial = "emulator-5554",
    [string]$Repo = "$PSScriptRoot\.."
)

$env:ANDROID_SERIAL = $Serial
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$pkg = "com.gearforge.geargenerator"
$activity = "com.gearforge.geargenerator/com.gearforge.app.MainActivity"

Write-Host "=== waiting for $Serial to boot ==="
$booted = $false
for ($i = 1; $i -le 60; $i++) {
    $state = (& $adb get-state 2>$null)
    $boot = (& $adb shell getprop sys.boot_completed 2>$null)
    if ($state -match "device" -and $boot -match "1") { $booted = $true; Write-Host "booted after $i checks"; break }
    Start-Sleep -Seconds 5
}
if (-not $booted) { Write-Host "NOT BOOTED"; exit 1 }

Write-Host "sdk=$(& $adb shell getprop ro.build.version.sdk)"
& $adb shell svc power stayon true | Out-Null
& $adb shell settings put system screen_off_timeout 2147483647 | Out-Null

Write-Host "=== install ==="
& $adb install -r "$Repo\android\build\outputs\apk\debug\android-debug.apk" | Select-Object -Last 1
& $adb shell pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE 2>&1 | Out-Null

Write-Host "=== start ==="
& $adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 3
& $adb shell am start -n $activity | Out-Null
Start-Sleep -Seconds 8
& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1
Write-Host "PREPARED $Serial"
