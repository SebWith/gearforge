# Rebuild the debug APK and install it on the running emulator.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
# Exists because "verify the installed app, not the source" needs a one-command step, and
# because a stale APK on the device has already produced a wrong conclusion in this repo.
#
#   powershell -ExecutionPolicy Bypass -File tools\stress-rebuild.ps1 -Serial emulator-5556
param(
    [string]$Serial = "",
    [string]$Repo = "$PSScriptRoot\.."
)

# Discovered 2026-09-20: with both API levels attached, a bare `adb install` fails with
# "more than one device/emulator", so the whole two-device matrix was unrunnable through this
# script. ANDROID_SERIAL is honoured by every adb invocation including child processes, which is
# the same mechanism stress-app.ps1 uses - one place to name the device, not one per call.
if ($Serial) { $env:ANDROID_SERIAL = $Serial; Write-Host "targeting $Serial" }

$ErrorActionPreference = "Continue"
# The script must not depend on the caller's console state. With the console in another codepage the
# JVM hands the Kotlin compiler an @argfile that is read as ANSI, and the 'o' with diaeresis in this
# repository's path becomes "u00F6" - the build then fails with "source file or directory not found"
# for a path that plainly exists. Measured 2026-09-20; every new shell starts on the old codepage,
# so the fix belongs in the script that runs gradle, not in the habits of whoever calls it.
chcp 1252 | Out-Null
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$pkg = "com.gearforge.geargenerator"
$log = "$Repo\build\stress-rebuild.txt"
$apk = "$Repo\android\build\outputs\apk\debug\android-debug.apk"
$gradleLog = "$Repo\build\stress-rebuild-gradle.txt"

# The whole run goes to a file, and the last line is the verdict. The terminal only shows the
# final screenful of a run, so a script whose result is only printed is a script whose result
# gets guessed at - and a stale file read as fresh has already cost this repo a debugging hour.
Set-Content -Path $log -Value "stress-rebuild $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8
function Note([string]$line) {
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host "$stamp $line"
    # A log write must never change the outcome. Add-Content has failed here with a stream-read
    # error ("Det gick inte att lasa datastrommen") while the file was open in an editor, and an
    # unhandled failure inside Note used to abort a run whose verdict was already decided.
    try { Add-Content -Path $log -Value "$stamp $line" -Encoding UTF8 -ErrorAction Stop } catch { }
}

& "$Repo\gradlew.bat" :android:assembleDebug --console=plain *> $gradleLog
$exit = $LASTEXITCODE
$ok = (Select-String -Path $gradleLog -Pattern "BUILD SUCCESSFUL" -SimpleMatch -ErrorAction SilentlyContinue)
if (-not $ok) {
    Note "BUILD FAILED exit=$exit (see build\stress-rebuild-gradle.txt)"
    Select-String -Path $gradleLog -Pattern "^e: " -ErrorAction SilentlyContinue | Select-Object -First 10 |
        ForEach-Object { Note ("  " + $_.Line) }
    Note "VERDICT: FAILED (build)"
    exit 1
}

$f = Get-Item $apk
Note "build_exit=$exit apk_bytes=$($f.Length) apk_time=$($f.LastWriteTime)"

# "adb devices" saying "device" is NOT the same as the system being up: sys.boot_completed is.
# Installing into a guest that is still booting (or wedged) hangs with no output at all, which
# reads as a broken script rather than as a device that is not ready.
#
# Order matters: prove the device is ATTACHED first. Waiting 40 x 10 s on a serial that is not in
# `adb devices` at all burned seven minutes to learn what one command already knew (measured
# 2026-09-21, after a monkey run took the emulator process down).
if (-not $Serial) {
    # No serial given: with more than one device attached, every adb call below is ambiguous.
    $attached = (& $adb devices) -split "`n" | Select-String -Pattern "\sdevice$"
    if ($attached.Count -gt 1) {
        Note "VERDICT: FAILED ($($attached.Count) devices attached - pass -Serial)"
        exit 1
    }
}
$present = (& $adb devices) -split "`n" | Select-String -Pattern "$Serial\s"
if ($Serial -and -not $present) {
    Note "VERDICT: FAILED ($Serial is not attached - cold-restart it with tools\emulator-cold-restart.ps1 -Port 5554 -Avd mc-target)"
    exit 1
}

$booted = $false
for ($i = 1; $i -le 40; $i++) {
    $boot = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($boot -eq "1") { $booted = $true; Note "booted after $i checks"; break }
    Start-Sleep -Seconds 10
}
if (-not $booted) {
    # No output from the device at all, rather than a wrong value: the guest is unresponsive.
    # This is the state a wedged emulator is in, and the fix is a cold restart of that emulator
    # (kill the qemu process bound to its port, relaunch the AVD) - not a retry.
    Note "VERDICT: FAILED (device never reported sys.boot_completed=1 - cold-restart the emulator)"
    exit 1
}

$installOut = (& $adb install -r $f.FullName 2>&1 | Out-String).Trim()
Note ("install: " + ($installOut -replace "\s+", " "))
if ($installOut -notmatch "Success") {
    Note "VERDICT: FAILED (install)"
    exit 1
}
& $adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1

# Prove the device has the same bytes, not just a successful install message. The install message
# says the installer accepted the file; this says the bytes that are running are the bytes just
# built - which is the thing that matters when a conclusion is drawn from the screen.
#
# This check used to compare file sizes, and on 2026-09-22 that turned out to be both too weak and
# too strong at once. Too weak: two different builds can land on the same size, and the check said
# "same_as_disk=True" for a build that predated the one on disk by an hour and a half - which is
# exactly the stale-artifact trap the check was written for. Too strong: the 535-byte difference
# that prompted a theory about signature stripping turned out to be a real content difference
# (manifest, three dex files, a dozen resources), and a fresh install of the same APK is
# byte-identical on API 26 and API 36 both. Identity is a content question, so it is answered by
# hashing every entry (tools\apk_identity.py) and naming the entries that differ: only META-INF
# means the same build, a dex or a resource means a different one.
$remote = (& $adb shell pm path $pkg).Trim().Replace("package:", "")
if (-not $remote) {
    Note "VERDICT: FAILED (package not present on the device)"
    exit 1
}
$remoteSize = (& $adb shell stat -c %s $remote 2>$null).Trim()
$tag = if ($Serial) { $Serial } else { "device" }
$pulled = "$Repo\build\pulled-$tag.apk"
Note "device_path=$remote device_apk_bytes=$remoteSize disk_apk_bytes=$($f.Length)"
& $adb pull $remote $pulled 2>&1 | Out-Null
$identity = (& py "$Repo\tools\apk_identity.py" $f.FullName $pulled 2>&1 | Out-String)
$same = ($identity -match "APK IDENTITY: SAME BUILD")
($identity -split "`n") | ForEach-Object { if ($_.Trim()) { Note ("  identity: " + $_.Trim()) } }

# A second gearforge package next to the real one means UI evidence can come from the wrong
# build. The repo has been bitten by exactly that, so it is checked on every install.
$found = (& $adb shell pm list packages 2>$null) | Select-String -Pattern "gearforge" |
    ForEach-Object { $_.Line.Trim() }
Note ("gearforge_packages: " + (($found | Sort-Object) -join ", "))

if ($same) { Note "VERDICT: OK" } else { Note "VERDICT: FAILED (the installed build is not the build on disk)" }
if (-not $same) { exit 1 }
