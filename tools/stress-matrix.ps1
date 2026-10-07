# Run a set of device lanes back to back, one at a time, and summarise the verdicts.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
#   powershell -ExecutionPolicy Bypass -File tools\stress-matrix.ps1 -Serial emulator-5554 -Lanes monkey,races,faults
#   powershell -ExecutionPolicy Bypass -File tools\stress-matrix.ps1 -Serial emulator-5556 -Lanes walkthrough,killtest -Minutes 30
#
# Why a runner exists: the lanes must run ONE AT A TIME (two drivers share the uiautomator bridge
# and both then produce wrong verdicts - measured 2026-09-21), each lane must start from a known
# device state, and every lane's output has to be read from a file rather than from a terminal that
# only shows the last screenful. Doing that by hand, twelve times, is where the mistakes happen:
# a lane that is skipped, a lane started twice, a result read from the previous run's file.
#
# The summary is per lane and counts check[PASS] / check[FAIL] / check[UNOBSERVED], which is the
# only honest way to read a lane: a lane with zero assertions has not run, however much it printed.
param(
    [string]$Serial = "",
    [string]$Lanes = "",
    [int]$Minutes = 30,
    [string]$Repo = "$PSScriptRoot\.."
)

$ErrorActionPreference = "Continue"
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$summary = "$Repo\build\matrix-summary.txt"
$lanesToRun = if ($Lanes) { $Lanes -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ } } else { @() }
if ($lanesToRun.Count -eq 0) { Write-Host "no lanes given (-Lanes a,b,c)"; exit 2 }
if (-not $Serial) { Write-Host "no -Serial given: the lanes refuse to run against an ambiguous device"; exit 2 }

Set-Content -Path $summary -Value "matrix $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') serial=$Serial" -Encoding UTF8
function Say([string]$line) {
    Write-Host $line
    try { Add-Content -Path $summary -Value $line -Encoding UTF8 -ErrorAction Stop } catch { }
}

# Free the bridge before the first lane. A leftover uiautomator from a killed run holds it, and a
# lane that cannot read the screen reports FAILs that look like app defects.
& powershell -NoProfile -ExecutionPolicy Bypass -File "$Repo\tools\harness-kill.ps1" -Serial $Serial *> "$Repo\build\matrix-kill.txt"

$results = @()
foreach ($lane in $lanesToRun) {
    $log = "$Repo\build\matrix-$lane.txt"
    Remove-Item -LiteralPath $log -ErrorAction SilentlyContinue
    $started = Get-Date
    Say "=== lane $lane ($($started.ToString('HH:mm:ss'))) ==="
    & powershell -NoProfile -ExecutionPolicy Bypass -File "$Repo\tools\stress-app.ps1" -Action $lane -Serial $Serial -Minutes $Minutes *> $log
    $exit = $LASTEXITCODE
    $text = if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Raw } else { "" }
    $pass = ([regex]::Matches($text, "check\[PASS\]")).Count
    $fail = ([regex]::Matches($text, "check\[FAIL\]")).Count
    $unobs = ([regex]::Matches($text, "check\[UNOBSERVED\]")).Count
    $aborted = ($text -match "LANE ABORTED")
    $minutes = [int]((Get-Date) - $started).TotalMinutes
    # Aborted is not the same as failed: an aborted lane never measured anything, and calling it a
    # failure would hide the reason (a stale build, an ambiguous device) inside a generic red.
    $verdict = if ($aborted) { "ABORTED" } elseif ($fail -gt 0 -or $unobs -gt 0) { "FAILED" } elseif ($pass -eq 0) { "NO ASSERTS" } elseif ($exit -ne 0) { "NONZERO EXIT" } else { "OK" }
    Say "lane=$lane verdict=$verdict exit=$exit pass=$pass fail=$fail unobserved=$unobs minutes=$minutes"
    foreach ($line in ($text -split "`n" | Where-Object { $_ -match "check\[FAIL\]|check\[UNOBSERVED\]|VERDICT:|REFUSING" })) {
        Say ("  " + $line.Trim())
    }
    $results += [pscustomobject]@{ lane = $lane; verdict = $verdict; pass = $pass; fail = $fail; unobserved = $unobs; exit = $exit }
}

$bad = @($results | Where-Object { $_.verdict -ne "OK" })
Say "matrix total lanes=$($results.Count) ok=$(@($results | Where-Object { $_.verdict -eq 'OK' }).Count) not_ok=$($bad.Count)"
Say ("VERDICT: MATRIX GREEN" + $(if ($bad.Count -eq 0) { "" } else { " - " + (($bad | ForEach-Object { "$($_.lane)=$($_.verdict)" }) -join ", ") }))
if ($bad.Count -gt 0) { exit 1 }
