# One reliable report of the emulator situation. ASCII only, on purpose.
#
#   powershell -ExecutionPolicy Bypass -File tools\emulator-status.ps1
#
# Why this exists: every diagnosis in this session needed the same four facts, and the terminal
# could not be trusted to show them. `Get-Process ... | Format-Table` printed a header followed by
# ELEVEN BLANK ROWS in the task terminal, which read as "no emulators are running" while two were -
# and that wrong conclusion sent a whole restart cycle down the wrong path. Everything here is
# written to build\emulator-status.txt as well, so the next reader gets the file and not a screen.
#
# The report answers, in order:
#   1. which serials adb sees (and whether they are "device" or "offline"),
#   2. which qemu/emulator processes exist, with the -avd and -port each was started with,
#   3. which console ports are listening and which pid owns each,
#   4. which AVD lock files are lying around (a crashed emulator leaves them, and the next start
#      then dies with "Running multiple emulators with the same AVD"),
#   5. how much memory the host has, and how much of it the JVM processes are holding.
param(
    [string]$Repo = "$PSScriptRoot\.."
)

$adb = "C:\Android\sdk\platform-tools\adb.exe"
$out = "$Repo\build\emulator-status.txt"

function Say([string]$line) {
    Write-Host $line
    try { Add-Content -Path $out -Value $line -Encoding UTF8 -ErrorAction Stop } catch { }
}

Set-Content -Path $out -Value "emulator status $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

Say "== adb devices =="
(& $adb devices) | ForEach-Object { if ($_.Trim()) { Say ("  " + $_.Trim()) } }

Say "== emulator processes =="
$procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq "qemu-system-x86_64.exe" -or $_.Name -eq "emulator.exe" }
if (-not $procs) {
    Say "  none"
} else {
    foreach ($p in $procs) {
        $avd = if ($p.CommandLine -match "-avd\s+(\S+)") { $Matches[1] } else { "?" }
        $port = if ($p.CommandLine -match "-port\s+(\d+)") { $Matches[1] } else { "?" }
        Say "  pid=$($p.ProcessId) name=$($p.Name) avd=$avd port=$port parent=$($p.ParentProcessId)"
    }
}

Say "== listening console ports =="
$found = $false
foreach ($line in (netstat -ano -p tcp)) {
    if ($line -match "127\.0\.0\.1:(5554|5556)\s" -and $line -match "LISTENING") {
        Say ("  " + ($line -replace "\s+", " ").Trim())
        $found = $true
    }
}
if (-not $found) { Say "  none" }

Say "== AVD lock files =="
$avdRoot = Join-Path $env:USERPROFILE ".android\avd"
$locks = Get-ChildItem -Path $avdRoot -Recurse -Force -Filter "*.lock" -ErrorAction SilentlyContinue
if (-not $locks) {
    Say "  none"
} else {
    foreach ($l in $locks) { Say "  $($l.FullName) ($($l.LastWriteTime))" }
}

Say "== memory =="
$os = Get-CimInstance Win32_OperatingSystem
Say "  host_total_MB=$([int]($os.TotalVisibleMemorySize / 1024)) host_free_MB=$([int]($os.FreePhysicalMemory / 1024))"
$java = Get-Process java -ErrorAction SilentlyContinue
if ($java) {
    $sum = ($java | Measure-Object -Property WorkingSet64 -Sum).Sum
    Say "  jvm_processes=$($java.Count) jvm_working_set_MB=$([int]($sum / 1MB))"
    Say "  (before device work: .\gradlew.bat --stop released 4.2 GB of a 9.5 GB JVM footprint on 2026-09-21)"
} else {
    Say "  jvm_processes=0"
}
