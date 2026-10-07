# Check every PowerShell script in tools/ for the two traps this repo has already paid for:
#   * a BOM-less .ps1 is read as CP1252 by PowerShell 5.1, so a stray high byte turns into
#     mojibake - and U+201C in particular is treated as a STRING DELIMITER, which produces
#     "Missing closing '}'" in a file whose braces are balanced;
#   * a syntax error means the script silently does nothing useful when a task calls it.
# ASCII only, on purpose.
param(
    [string]$Path = "$PSScriptRoot"
)

$files = Get-ChildItem "$Path\*.ps1" | Sort-Object Name
$bad = 0

foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    # A UTF-8 BOM makes PowerShell read the file as UTF-8, so high bytes are then harmless.
    # Flagging them anyway would have cried wolf on build-release-aab.ps1, which works.
    # The trap is only a BOM-LESS file with high bytes.
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    $high = 0
    foreach ($b in $bytes) { if ($b -gt 127) { $high++ } }

    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$errors) | Out-Null
    $errCount = if ($errors) { $errors.Count } else { 0 }

    $flag = ""
    if ($high -gt 0 -and -not $hasBom) { $flag += " NON-ASCII($high)"; $bad++ }
    if ($errCount -gt 0) { $flag += " SYNTAX_ERRORS($errCount)"; $bad++ }

    if ($flag) {
        Write-Host "$($f.Name):$flag"
        if ($errors) { $errors | Select-Object -First 3 | ForEach-Object { Write-Host "    $($_.Message)" } }
    } else {
        $note = if ($hasBom -and $high -gt 0) { " (BOM, $high high bytes - read as UTF-8)" } else { "" }
        Write-Host "$($f.Name): OK (bytes=$($bytes.Length))$note"
    }
}

Write-Host "checked=$($files.Count) problems=$bad"
if ($bad -gt 0) { exit 1 }
