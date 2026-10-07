# Add a UTF-8 BOM to a script that contains non-ASCII bytes. ASCII only, on purpose.
#
#   powershell -ExecutionPolicy Bypass -File tools\add-utf8-bom.ps1 -Path tools\build-release-aab.ps1
#
# Why: PowerShell 5.1 reads a BOM-less .ps1 as CP1252, so a UTF-8 non-ASCII character in a string
# literal becomes two CP1252 characters. The file still parses and still works - the damage is in the
# text the operator reads. Measured 2026-09-21 on tools\build-release-aab.ps1, whose message is
# SWEDISH FOR "EXPECTED": the console printed the word as twelve characters instead of nine, with
# two mojibake pairs in it. (Written out here in words rather than characters: this script is
# ASCII-only, and quoting the broken bytes would put the same defect in the file that fixes it.)
#
# The BOM is the minimal fix that keeps the Swedish text intact: check-ps1.ps1 already treats a BOM'd
# file as read-as-UTF-8 and stops flagging it, and PowerShell then decodes the literals correctly.
# Rewriting the messages in ASCII would work too, at the cost of the language they are written in.
#
# This is a byte-level change (a text editor cannot add a BOM), so it is done here rather than by an
# edit tool - and it is verified by parsing the file afterwards.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Repo = ""
)

# The repo root is derived in the BODY, not in a parameter default: "$PSScriptRoot\.." in a default
# evaluated to "\.." when this script was invoked through the task shell, and the script then
# reported a missing file it was standing next to. Same pattern build-release-aab.ps1 uses.
if (-not $Repo) { $Repo = Split-Path -Parent $PSScriptRoot }

$full = Join-Path $Repo $Path
if (-not (Test-Path $full)) {
    Write-Host "FAILED: $full does not exist (repo=$Repo path=$Path)"
    exit 1
}

$bytes = [System.IO.File]::ReadAllBytes($full)
$high = 0
foreach ($b in $bytes) { if ($b -gt 127) { $high++ } }
$hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)

Write-Host "file=$Path bytes=$($bytes.Length) high_bytes=$high has_bom=$hasBom"

if ($high -eq 0) {
    Write-Host "nothing to do: the file is pure ASCII, so no BOM is needed"
    exit 0
}
if ($hasBom) {
    Write-Host "nothing to do: the BOM is already there and the file is read as UTF-8"
    exit 0
}

$new = [byte[]]::new($bytes.Length + 3)
[Array]::Copy([byte[]](0xEF, 0xBB, 0xBF), 0, $new, 0, 3)
[Array]::Copy($bytes, 0, $new, 3, $bytes.Length)
[System.IO.File]::WriteAllBytes($full, $new)

$check = [System.IO.File]::ReadAllBytes($full)
Write-Host "wrote BOM: bytes=$($check.Length) first3=$($check[0]),$($check[1]),$($check[2])"

# Proof that the file is still a valid script: the same parser check-ps1.ps1 uses.
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($full, [ref]$null, [ref]$errors) | Out-Null
$errCount = if ($errors) { $errors.Count } else { 0 }
Write-Host "parse_errors=$errCount"
if ($errCount -gt 0) {
    $errors | Select-Object -First 3 | ForEach-Object { Write-Host "  $($_.Message)" }
    Write-Host "VERDICT: FAILED (the file no longer parses)"
    exit 1
}
Write-Host "VERDICT: OK"
