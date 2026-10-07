# Captures the Swedish Results panel: expands "Resultat" and scrolls it into view.
# NOTE: uses a RELATIVE output path on purpose - the workspace path contains
# non-ASCII characters ("överför skrivbord") which get mangled when passed to a
# child process on this machine's ANSI codepage.
$ErrorActionPreference = "Continue"
$adb = "C:\Android\Sdk\platform-tools\adb.exe"
$out = "tools\store-screenshots\_capture\sv-07-results.png"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb @Args | Out-Null
}

Invoke-Adb shell input tap 540 2205
Invoke-Adb shell "sleep 3"
Invoke-Adb shell input swipe 540 1800 540 500 400
Invoke-Adb shell input swipe 540 1800 540 700 400
Invoke-Adb shell "sleep 3; screencap -p /sdcard/shot.png"
& $adb pull /sdcard/shot.png $out 2>&1 | Select-Object -Last 1
