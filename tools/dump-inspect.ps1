# What does the harness actually SEE right now? ASCII only, on purpose.
#
#   powershell -ExecutionPolicy Bypass -File tools\dump-inspect.ps1
#   powershell -ExecutionPolicy Bypass -File tools\dump-inspect.ps1 -Grep Consent
#
# Why this exists: a run reported every label NOTFOUND on a screen where the consent form was
# plainly visible in the screenshot (measured 2026-09-21). "The harness cannot see the screen" and
# "the screen does not have that label" are different facts, and the only way to tell them apart is
# to look at the dump itself. Everything is written to build\dump-inspect.txt, because the task
# terminal mangles or drops this kind of multi-line output.
param(
    [string]$Serial = "",
    [string]$Grep = "",
    [string]$Repo = "$PSScriptRoot\.."
)

# Without a serial every adb call is ambiguous the moment two devices are attached, and the
# failure reads as "the guest is broken" rather than "the harness was not told which guest".
if ($Serial) { $env:ANDROID_SERIAL = $Serial }

$adb = "C:\Android\sdk\platform-tools\adb.exe"
$out = "$Repo\build\dump-inspect.txt"
$local = "$Repo\build\dump-inspect.xml"
Set-Content -Path $out -Value "dump-inspect $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

function Say([string]$line) {
    Write-Host $line
    try { Add-Content -Path $out -Value $line -Encoding UTF8 -ErrorAction Stop } catch { }
}

Say "== devices =="
(& $adb devices) | ForEach-Object { if ($_.Trim()) { Say ("  " + $_.Trim()) } }
Say ("  ANDROID_SERIAL=" + $env:ANDROID_SERIAL)

Say "== raw uiautomator output =="
Remove-Item $local -Force -ErrorAction SilentlyContinue
& $adb shell rm -f /sdcard/dump-inspect.xml 2>$null | Out-Null
$raw = (& $adb shell uiautomator dump /sdcard/dump-inspect.xml 2>&1 | Out-String).Trim()
Say ("  " + ($raw -replace "\s+", " "))

if (-not $raw) {
    # Silence is a different failure from "null root node": the command produced no line at all,
    # which is what a stale uiautomator process holding the accessibility bridge looks like. A
    # killed run leaves one behind (this repo has paid for that three times), and while it lives
    # every dump in every other process answers nothing.
    Say "== no output at all: looking for a stale uiautomator holding the bridge =="
    $stale = (& $adb shell pgrep -f uiautomator 2>&1 | Out-String).Trim()
    Say ("  pgrep -f uiautomator -> " + ($stale -replace "\s+", " "))

    Say "  clearing it and retrying once"
    & $adb shell pkill -f uiautomator 2>$null | Out-Null
    Start-Sleep -Seconds 3
    Remove-Item $local -Force -ErrorAction SilentlyContinue
    & $adb shell rm -f /sdcard/dump-inspect.xml 2>$null | Out-Null
    $raw = (& $adb shell uiautomator dump /sdcard/dump-inspect.xml 2>&1 | Out-String).Trim()
    Say ("  retry -> " + ($raw -replace "\s+", " "))
}

& $adb pull /sdcard/dump-inspect.xml $local 2>$null | Out-Null

if (-not (Test-Path $local)) {
    Say "VERDICT: no dump file was produced - the harness cannot see anything right now"
    exit 1
}

$xml = Get-Content $local -Raw -Encoding UTF8
Say ("  bytes=" + $xml.Length + " nodes=" + [regex]::Matches($xml, "<node").Count)

Say "== windows present in the dump =="
# A dialog is a separate window. If the dump only holds the app's window, every label behind the
# dialog is invisible AND the dialog's own labels are invisible too - which is exactly the state
# that produced this tool.
foreach ($m in [regex]::Matches($xml, 'package="([^"]*)"')) { }
$pkgs = [regex]::Matches($xml, 'package="([^"]*)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
foreach ($p in $pkgs) { Say "  package=$p" }

Say "== every text and content-desc in the dump =="
$count = 0
foreach ($m in [regex]::Matches($xml, '(text|content-desc)="([^"]+)"')) {
    $value = $m.Groups[2].Value
    if ($Grep -and $value -notmatch [regex]::Escape($Grep)) { continue }
    Say ("  " + $m.Groups[1].Value + "=" + $value)
    $count++
}
Say "  entries=$count"

if ($Grep) {
    Say ("== " + $Grep + " found in dump: " + ($xml -match [regex]::Escape($Grep)))
}
