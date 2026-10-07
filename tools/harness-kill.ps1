# Stop stray harness runs and free the uiautomator bridge. ASCII only, on purpose.
#
#   powershell -ExecutionPolicy Bypass -File tools\harness-kill.ps1
#   powershell -ExecutionPolicy Bypass -File tools\harness-kill.ps1 -Serial emulator-5554
#
# Why this exists: a stress run that is blocked (a dialog nobody dismissed, a device that stopped
# answering) keeps polling for minutes and holds the accessibility bridge, so every OTHER probe
# answers nothing at all - which reads as "the device is broken" when the truth is "something else
# is holding the door". Measured 2026-09-21: a run left in flight made uiautomator return no output
# whatsoever, and the two facts were indistinguishable until the stray run was stopped.
#
# It also matters for evidence: two drivers on one device produce garbage verdicts, not slow ones.
param(
    [string]$Serial = "",
    [switch]$KeepDevice,
    [string]$Repo = "$PSScriptRoot\.."
)

$adb = "C:\Android\sdk\platform-tools\adb.exe"
$out = "$Repo\build\harness-kill.txt"
Set-Content -Path $out -Value "harness-kill $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

function Say([string]$line) {
    Write-Host $line
    try { Add-Content -Path $out -Value $line -Encoding UTF8 -ErrorAction Stop } catch { }
}

# Only processes whose command line names the harness are stopped. A broad "stop all powershell"
# would take the editor with it.
#
# Two exclusions, both learned the hard way (2026-09-21): this script must not match ITSELF, and it
# must not match the shell that invoked it. Chaining `harness-kill.ps1; stress-app.ps1 -Action x`
# in one PowerShell command puts "stress-app.ps1" in the parent's command line, so the first
# version killed its own parent and the chained run died with exit code -1.
Say "== stray harness processes =="
$stray = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq "powershell.exe" } |
    Where-Object { $_.ProcessId -ne $PID } |
    Where-Object { $_.CommandLine -notmatch "harness-kill" } |
    Where-Object { $_.CommandLine -match "stress-app\.ps1|stress-suite\.ps1|stress-test\.ps1|verify-viewport\.ps1" }
if (-not $stray) {
    Say "  none"
} else {
    foreach ($p in $stray) {
        $what = if ($p.CommandLine -match "-Action\s+(\S+)") { $Matches[1] } else { "?" }
        Say "  stopping pid=$($p.ProcessId) action=$what"
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
}

if ($KeepDevice) {
    Say "VERDICT: stray processes stopped, device left alone (-KeepDevice)"
    exit 0
}

Say "== freeing the accessibility bridge =="
if ($Serial) { $env:ANDROID_SERIAL = $Serial; Say "  targeting $Serial" }
$stale = (& $adb shell pgrep -f uiautomator 2>&1 | Out-String).Trim()
Say ("  pgrep -f uiautomator -> " + (($stale -replace "\s+", " ") -replace "^$", "none"))
& $adb shell pkill -f uiautomator 2>$null | Out-Null
Start-Sleep -Seconds 3

Say "== proof that the bridge answers again =="
Remove-Item "$Repo\build\gf-ui.xml" -Force -ErrorAction SilentlyContinue
& $adb shell rm -f /sdcard/gf-ui.xml 2>$null | Out-Null
$raw = (& $adb shell uiautomator dump /sdcard/gf-ui.xml 2>&1 | Out-String).Trim()
Say ("  uiautomator dump -> " + (($raw -replace "\s+", " ") -replace "^$", "(no output)"))
& $adb pull /sdcard/gf-ui.xml "$Repo\build\gf-ui.xml" 2>$null | Out-Null
if (Test-Path "$Repo\build\gf-ui.xml") {
    $xml = Get-Content "$Repo\build\gf-ui.xml" -Raw -Encoding UTF8
    Say ("  nodes=" + [regex]::Matches($xml, "<node").Count)
    Say "VERDICT: OK (the bridge answers)"
} else {
    Say "VERDICT: FAILED (still no dump - the bridge is held by something else, or the guest is wedged)"
    exit 1
}
