# Helper for interactive emulator screenshot capture.
# Usage:  . tools/store-screenshots/capture.ps1
#         Tap 540 1804 ; Shot 02-types
param(
    [string]$CaptureDir = "$PSScriptRoot\_capture"
)

$script:Adb = "adb"
$script:CaptureDir = $CaptureDir
New-Item -ItemType Directory -Force -Path $script:CaptureDir | Out-Null

function Tap([int]$X, [int]$Y) {
    & $script:Adb shell input tap $X $Y | Out-Null
    Start-Sleep -Milliseconds 1200
    "tapped $X,$Y"
}

function Swipe([int]$X1, [int]$Y1, [int]$X2, [int]$Y2, [int]$Ms = 300) {
    & $script:Adb shell input swipe $X1 $Y1 $X2 $Y2 $Ms | Out-Null
    Start-Sleep -Milliseconds 1200
    "swiped $X1,$Y1 -> $X2,$Y2"
}

function Shot([string]$Name) {
    $remote = "/sdcard/shot.png"
    & $script:Adb shell screencap -p $remote | Out-Null
    $local = Join-Path $script:CaptureDir "$Name.png"
    & $script:Adb pull $remote $local 2>&1 | Out-Null
    if (Test-Path $local) {
        Add-Type -AssemblyName System.Drawing
        $img = [System.Drawing.Image]::FromFile($local)
        $dims = "$($img.Width)x$($img.Height)"
        $img.Dispose()
        "wrote $local ($dims)"
    } else {
        "FAILED $local"
    }
}

function Back() {
    & $script:Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 900
    "back"
}

function SetAppLocale([string]$Locale) {
    & $script:Adb shell cmd locale set-app-locales com.gearforge.app --user 0 --locales $Locale | Out-Null
    & $script:Adb shell am force-stop com.gearforge.app | Out-Null
    Start-Sleep -Milliseconds 600
    & $script:Adb shell am start -n com.gearforge.app/.MainActivity | Out-Null
    Start-Sleep -Milliseconds 6000
    "locale set to $Locale and app restarted"
}

function LogcatErrors() {
    & $script:Adb logcat -d -t 200 "*:E" 2>&1 | Select-String -Pattern "gearforge|AndroidRuntime|FATAL" | Select-Object -Last 25
}

"capture helpers loaded (dir: $script:CaptureDir)"
