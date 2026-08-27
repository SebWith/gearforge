# auto-linter-guard.ps1
# PostToolUse-hook: kör relevant statisk analys direkt efter att agenten skapat/modifierat en kodfil.
# Exit-koder: 0 = OK (eller inget att göra), 2 = blockerande fel (linterfel -> agenten tvingas åtgärda).

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Läs hook-input (JSON) från stdin
$raw = ''
try { $raw = [Console]::In.ReadToEnd() } catch {}
if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }

# Schema-oberoende extraktion av alla strängvärden ur JSON:en
function Get-StringValues($obj) {
    $result = @()
    if ($null -eq $obj) { return $result }
    if ($obj -is [string]) { $result += $obj; return $result }
    if ($obj -is [System.Collections.IDictionary]) {
        foreach ($k in @($obj.Keys)) { $result += Get-StringValues $obj[$k] }
        return $result
    }
    if ($obj -is [System.Collections.IEnumerable]) {
        foreach ($item in @($obj)) { $result += Get-StringValues $item }
        return $result
    }
    foreach ($p in @($obj.PSObject.Properties)) { $result += Get-StringValues $p.Value }
    return $result
}

function Test-Tool {
    param([string]$Name, [string[]]$ProbeArgs)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) { return $false }
    & $cmd.Source @ProbeArgs 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

$parsed = $null
try { $parsed = $raw | ConvertFrom-Json } catch {}

# Endast edit-verktyg ska trigga linter
$editTools = @('replace_string_in_file', 'multi_replace_string_in_file', 'create_file', 'edit_notebook_file')
$toolName = $null
if ($null -ne $parsed) {
    $toolName = $parsed.tool_name
    if (-not $toolName) { $toolName = $parsed.toolName }
}
if ($toolName) {
    if ($editTools -notcontains $toolName) { exit 0 }
} elseif ($raw -notmatch 'replace_string_in_file|multi_replace_string_in_file|create_file|edit_notebook_file') {
    exit 0
}

$strings = if ($null -ne $parsed) { @(Get-StringValues $parsed) } else { @($raw) }

# Hitta redigerad kodfil (absolut Windows-sökväg först)
$file = $null
foreach ($s in $strings) {
    if ($s -match '([A-Za-z]:\\[^"''`<>\|\?\*]+\.(?:dart|kt|kts|cpp|cc|cxx|hpp|py))') {
        $candidate = $Matches[1]
        if (Test-Path -LiteralPath $candidate) { $file = $candidate; break }
    }
}
# Fallback: relativ sökväg
if (-not $file) {
    foreach ($s in $strings) {
        if ($s -match '(^|[\\/])([A-Za-z0-9_\-\.\\/ ]+\.(?:dart|kt|kts|cpp|cc|cxx|hpp|py))$') {
            $candidate = $Matches[2]
            if (Test-Path -LiteralPath $candidate) { $file = $candidate; break }
        }
    }
}
if (-not $file) { exit 0 }

$ext = [System.IO.Path]::GetExtension($file).ToLowerInvariant()
$output = ''
$failed = $false

switch ($ext) {
    '.dart' {
        if (Test-Tool 'dart' @('--version')) {
            $output = (& dart analyze $file 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) { $failed = $true }
        } elseif (Test-Tool 'flutter' @('--version')) {
            $output = (& flutter analyze $file 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) { $failed = $true }
        } else { exit 0 }
    }
    '.kt' {
        if (Test-Tool 'kotlinc' @('-version')) {
            $out = (& kotlinc $file 2>&1 | Out-String)
            # Flagga endast syntaxfel, inte olösta referenser (vilket kräver full classpath)
            $syntax = @(($out -split "`r?`n") | Where-Object { $_ -match 'error:\s*(expecting|syntax|unterminated|incomplete)' })
            if ($syntax.Count -gt 0) { $failed = $true; $output = ($syntax -join "`n") }
        } else { exit 0 }
    }
    '.py' {
        if (Test-Tool 'python' @('-c','import sys')) {
            $output = (& python -m py_compile $file 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) { $failed = $true }
        } else { exit 0 }
    }
    '.cpp' {
        if (Test-Tool 'g++' @('--version')) {
            $output = (& g++ -fsyntax-only $file 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) { $failed = $true }
        } elseif (Test-Tool 'clang++' @('--version')) {
            $output = (& clang++ -fsyntax-only $file 2>&1 | Out-String)
            if ($LASTEXITCODE -ne 0) { $failed = $true }
        } else { exit 0 }
    }
    default { exit 0 }
}

if ($failed) {
    Write-Output ("[AUTO-LINTER-GUARD] Linterfel i $file :`n$output")
    exit 2
}
exit 0
