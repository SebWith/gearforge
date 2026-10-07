# Cold-restart ONE emulator, identified by the console port it listens on.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
#   powershell -ExecutionPolicy Bypass -File tools\emulator-cold-restart.ps1 -Port 5554
#   powershell -ExecutionPolicy Bypass -File tools\emulator-cold-restart.ps1 -Port 5554 -Avd mc-target
#
# Why by port and not by name: `Stop-Process -Name qemu-system-x86_64` kills EVERY emulator on
# the machine, so a two-API-level matrix loses the healthy device as well. The console port
# (5554/5556) is the only identifier that maps unambiguously to one instance: `netstat -ano`
# gives the owning pid directly, and that is what gets stopped.
#
# When this is needed: `adb devices` says "device" but `adb shell` hangs, and `adb install`
# produces no output at all. `adb reconnect offline` does not help - the bridge is fine, the
# guest is wedged. Measured 2026-09-20 (a plain `adb install` wedged emulator-5554 while
# emulator-5556 stayed healthy throughout).
#
# -Avd is for the case where the emulator is ALREADY GONE (measured 2026-09-21: a monkey run
# took the whole qemu process down, leaving nothing on the port to read the AVD from). There is
# no way to derive it then, and guessing would start the right port with the wrong system image
# - so the script asks instead of assuming.
param(
    [int]$Port = 5554,
    [string]$Avd = "",
    [string]$Repo = "$PSScriptRoot\.."
)

$ErrorActionPreference = "Continue"
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$emulator = "C:\Android\sdk\emulator\emulator.exe"
$log = "$Repo\build\emulator-cold-restart.txt"
Set-Content -Path $log -Value "cold-restart port=$Port $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

function Note([string]$line) {
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host "$stamp $line"
    Add-Content -Path $log -Value "$stamp $line" -Encoding UTF8
}

# Which AVD serves this port? Read it from the running process, never assumed: guessing the AVD
# name would start the right port with the wrong system image, and every later measurement would
# be of the wrong API level.
$avd = $Avd
$pids = @()
$conn = netstat -ano -p tcp | Select-String -Pattern ":$Port\s" | Select-Object -First 1
if ($conn -and $conn.Line -match "\s(\d+)\s*$") { $pids += [int]$Matches[1] }
foreach ($p in @($pids)) {
    $cmd = (Get-CimInstance Win32_Process -Filter "ProcessId = $p" -ErrorAction SilentlyContinue).CommandLine
    if (-not $avd -and $cmd -and $cmd -match "-avd\s+(\S+)") { $avd = $Matches[1] }
    # The qemu child is what holds the console port; its launcher is the other half of the pair.
    $parent = (Get-CimInstance Win32_Process -Filter "ProcessId = $p" -ErrorAction SilentlyContinue).ParentProcessId
    if ($parent) { $pids += [int]$parent }
}
$pids = $pids | Select-Object -Unique
Note "port=$Port avd='$avd' pids=$($pids -join ',')"

if (-not $avd) {
    Note "VERDICT: FAILED (nothing is listening on port $Port, so the AVD cannot be read from it)"
    Note "  pass -Avd <name> explicitly, e.g. -Avd mc-target for port 5554, -Avd mc-api26 for 5556"
    exit 1
}

# A crashed or half-started emulator leaves its AVD claimed, and the next start then dies with
#   FATAL | Running multiple emulators with the same AVD is an experimental feature.
# even though nothing serves the console port (measured 2026-09-21, right after a monkey run took
# the emulator down; the leftover pair also survived with NO listening port, so `adb devices` did
# not list it and the host looked empty while the AVD stayed claimed).
#
# The rule: a process that carries `-avd <this avd>` but whose port is silent is not an emulator
# worth keeping - it cannot be talked to, it cannot boot further, and it blocks every other start.
# It is stopped. A process that DOES hold the port is a live instance and is left alone.
$avdProcs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq "qemu-system-x86_64.exe" -or $_.Name -eq "emulator.exe" } |
    Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($avd))(\s|$)" }

if ($pids.Count -eq 0) {
    foreach ($p in $avdProcs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        Note "stopped a process holding '$avd' with no console port on $Port (pid $($p.ProcessId))"
    }
    if ($avdProcs) { Start-Sleep -Seconds 6 }

    $avdDir = Join-Path $env:USERPROFILE ".android\avd\$avd.avd"
    if (Test-Path $avdDir) {
        $locks = Get-ChildItem -Path $avdDir -Recurse -Force -Filter "*.lock" -ErrorAction SilentlyContinue
        foreach ($l in $locks) {
            Remove-Item $l.FullName -Recurse -Force -ErrorAction SilentlyContinue
            Note "cleared stale AVD lock: $($l.FullName)"
        }
        if (-not $locks) { Note "no stale AVD lock found in $avdDir" }
    } else {
        Note "AVD directory not found: $avdDir"
    }
}

foreach ($p in $pids) {
    Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
    Note "stopped pid $p"
}
Start-Sleep -Seconds 6

# `-gpu swiftshader` is not decoration, and the exact spelling matters.
#
# Measured 2026-09-21, after the monkey run took the emulator down, the same AVD refused to boot.
# Its own captured log said why:
#
#   Critical: Failed to load opengl32sw (module not found)      <- no software OpenGL in the SDK
#   Warning:  Software OpenGL failed. Falling back to system OpenGL.
#   Storing crashdata ... Showing crashdialog to get consent    <- crashes, then waits for a human
#   emuglConfig_init: gpu_mode_requested: auto                  <- the -gpu flag did NOT take
#
# The flag did not take because `swiftshader_indirect` is a legacy name that this emulator no
# longer accepts: `emulator.exe -help-gpu` (version 37.1.11) lists exactly auto, host, software,
# lavapipe, swiftshader, swangle. An unrecognised value is ignored silently, the emulator goes back
# to `auto`, picks host OpenGL, and crashes on this machine's driver. The repository's older task
# entries pass `swiftshader_indirect`, which is why a restart could look like it was configured for
# software rendering and still crash.
#
# The emulator's own output is captured because an emulator that dies must explain itself: without
# this file none of the evidence above would have survived.
$emuLog = "$Repo\build\emulator-$Port.txt"
Start-Process -FilePath $emulator `
    -ArgumentList @("-avd", $avd, "-port", "$Port", "-no-boot-anim", "-no-snapshot-save",
                    "-gpu", "swiftshader", "-no-metrics") `
    -RedirectStandardOutput $emuLog `
    -RedirectStandardError "$Repo\build\emulator-$Port.err.txt"
Note "started '$avd' on port $Port with -gpu swiftshader (output: build\emulator-$Port.txt)"

# Wait for the guest, not for the bridge. adb reports "device" long before the system is up, and
# an install into a still-booting guest hangs silently.
$serial = "emulator-$Port"
$booted = $false
for ($i = 1; $i -le 40; $i++) {
    Start-Sleep -Seconds 10
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($boot -eq "1") { $booted = $true; Note "booted after $($i * 10)s"; break }
}
if (-not $booted) {
    Note "VERDICT: FAILED ($serial never reported sys.boot_completed=1)"
    exit 1
}
Note "sdk=$(& $adb -s $serial shell getprop ro.build.version.sdk)"

# The screen must stay awake, or every later UI check measures a black screen.
& $adb -s $serial shell svc power stayon true | Out-Null
& $adb -s $serial shell settings put system screen_off_timeout 2147483647 | Out-Null
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null

Note "VERDICT: OK"
