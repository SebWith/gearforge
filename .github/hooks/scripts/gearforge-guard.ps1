# gearforge-guard.ps1
#
# PostToolUse hook for GearForge. Replaces the earlier auto-linter-guard.ps1, which
# was a no-op in this repo: it probed for `kotlinc` (not installed) and `python` (a
# broken Microsoft Store alias -> exit 9009). Neither touched Gradle, so no real
# check ever ran.
#
# This version runs the right tool for the stack:
#   - core/**/*.kt            -> Gradle compileKotlin (a real type check)
#   - core/src/test/**/*.kt   -> Gradle compileTestKotlin
#   - android/**/I18n.kt      -> EN/SV key parity (blocking)
#   - android/**/*.kt         -> hardcoded UI string scan (warning only)
#   - secret files            -> always blocked
#
# Exit codes: 0 = OK / nothing to do, 2 = blocking error.
#
# Turn it off temporarily:  $env:GEARFORGE_GUARD_OFF = '1'
#
# ===========================================================================
# TWO ENCODING TRAPS ALREADY PAID FOR. Do not undo either.
#
# 1. THIS FILE IS DELIBERATELY ASCII-ONLY. Do not add accents, arrows, em
#    dashes or symbols to it.
#
#    Windows PowerShell 5.1 reads a .ps1 without a BOM using the ANSI code page
#    (CP1252). A UTF-8 file then gets mangled: a check mark (U+2713, UTF-8
#    E2 9C 93) decodes to a typographic double quote (U+201C), and PowerShell
#    treats smart quotes as STRING DELIMITERS. The symptom is "Missing closing
#    '}'" reported for a file whose braces look perfectly balanced. The first
#    version of this script had 116 non-ASCII bytes which produced 9 stray
#    quote characters and would not parse at all.
#
#    If a pattern needs a non-ASCII character, use a .NET regex escape such as
#    \u00b0 (degree sign) instead of the literal character.
#
# 2. STANDARD INPUT IS READ AS RAW BYTES AND DECODED AS UTF-8.
#
#    [Console]::In.ReadToEnd() decodes stdin with the console input code page.
#    This repository lives at a path containing a non-ASCII character, so the
#    path in the tool payload arrived as "??verf??r". That broke the path regex
#    -- the '?' replacement character was excluded from the character class --
#    so the hook matched no file and exited 0 for EVERY edit. A guard that
#    silently passes everything is worse than no guard, because it looks like
#    it works.
#
#    Read-StdinText below reads the bytes and decodes them explicitly, with a
#    CP1252 mojibake repair as a fallback.
# ===========================================================================

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if ($env:GEARFORGE_GUARD_OFF -eq '1') { exit 0 }

function Write-Guard([string]$Message) {
    Write-Output "[gearforge-guard] $Message"
}

# --- Read hook input (JSON) from stdin, as bytes decoded as UTF-8 ------------
function Read-StdinText {
    try {
        $stdin = [Console]::OpenStandardInput()
        $ms = New-Object System.IO.MemoryStream
        $buffer = New-Object byte[] 8192
        while (($n = $stdin.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $ms.Write($buffer, 0, $n)
        }
        $bytes = $ms.ToArray()
        if ($bytes.Length -eq 0) { return '' }
        # Strip a UTF-8 BOM if present.
        if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            return [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
        }
        return [System.Text.Encoding]::UTF8.GetString($bytes)
    } catch {
        return ''
    }
}

# Repair text that was decoded with a single-byte code page but is really UTF-8.
# Only ever applied as a fallback, because it corrupts already-correct text.
function Repair-Mojibake([string]$Text) {
    try {
        $bytes = [System.Text.Encoding]::GetEncoding(1252).GetBytes($Text)
        return [System.Text.Encoding]::UTF8.GetString($bytes)
    } catch {
        return $Text
    }
}

$raw = Read-StdinText
if ([string]::IsNullOrWhiteSpace($raw)) {
    try { $raw = [Console]::In.ReadToEnd() } catch {}
}
if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }

# Schema-agnostic extraction of every string value in the JSON.
function Get-StringValues($obj) {
    $result = @()
    if ($null -eq $obj) { return $result }
    if ($obj -is [string]) { return @($obj) }
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

$parsed = $null
try { $parsed = $raw | ConvertFrom-Json } catch {}

# Only edit tools should trigger the check.
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

# --- Locate the edited file --------------------------------------------------
$strings = if ($null -ne $parsed) { @(Get-StringValues $parsed) } else { @($raw) }

# NOTE: '?' is deliberately NOT excluded here. With correctly decoded UTF-8 there
# is no '?', but mangled input produces them, and excluding '?' is exactly what
# made the first version match no file at all.
$extensions = 'kt|kts|properties|keystore|json'
$absPattern = "([A-Za-z]:\\[^\r\n`"<>|*]+\.($extensions))"
$relPattern = "(^|[\\/])([A-Za-z0-9_\-\.\\/ ]+\.($extensions))$"

# Accept a candidate only if it exists, trying the mojibake repair once.
function Resolve-Candidate([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $null }
    foreach ($cand in @($Candidate, (Repair-Mojibake $Candidate))) {
        if (Test-Path -LiteralPath $cand) {
            return (Resolve-Path -LiteralPath $cand).Path
        }
    }
    return $null
}

$file = $null
foreach ($s in $strings) {
    if ($s -match $absPattern) {
        $file = Resolve-Candidate $Matches[1]
        if ($file) { break }
    }
}
if (-not $file) {
    foreach ($s in $strings) {
        if ($s -match $relPattern) {
            $file = Resolve-Candidate $Matches[2]
            if ($file) { break }
        }
    }
}
if (-not $file) { exit 0 }

# --- Repo root: walk up until settings.gradle is found ----------------------
$root = Split-Path -Parent $file
while ($root -and -not (Test-Path -LiteralPath (Join-Path $root 'settings.gradle'))) {
    $parent = Split-Path -Parent $root
    if ($parent -eq $root -or [string]::IsNullOrEmpty($parent)) { $root = $null; break }
    $root = $parent
}
if (-not $root -or -not (Test-Path -LiteralPath (Join-Path $root 'gradlew.bat'))) { exit 0 }

$rel = $file.Substring($root.Length).TrimStart('\', '/') -replace '/', '\'
$name = Split-Path -Leaf $file

# ============================================================================
# 1. Secrets -- always block
# ============================================================================
$secretNames = @('keystore.properties', 'release.keystore', '.github-token')
if ($secretNames -contains $name -or $rel -like 'android\*keystore*') {
    Write-Guard "BLOCKED: $rel is a secret and must never be edited by an agent."
    Write-Guard 'Keys and passwords are handled by the user, not by automated edits.'
    exit 2
}

# ============================================================================
# 2. Debounce -- avoid starting Gradle repeatedly within one edit sweep
# ============================================================================
$stampFile = Join-Path $env:TEMP 'gearforge-guard-last-ok.txt'
function Test-RecentOk {
    if (-not (Test-Path -LiteralPath $stampFile)) { return $false }
    try {
        $last = [datetime]::Parse((Get-Content -LiteralPath $stampFile -Raw).Trim())
        return ((Get-Date) - $last).TotalSeconds -lt 25
    } catch { return $false }
}
function Set-RecentOk {
    try { Set-Content -LiteralPath $stampFile -Value (Get-Date).ToString('o') -Encoding ASCII } catch {}
}

# ============================================================================
# 3. I18n.kt -- EN/SV parity (blocking)
# ============================================================================
if ($name -eq 'I18n.kt') {
    $pyProbe = Get-Command py -ErrorAction SilentlyContinue
    if (-not $pyProbe) { exit 0 }
    & $pyProbe.Source --version 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { exit 0 }

    $audit = Join-Path $root 'tools\i18n_audit.py'
    if (-not (Test-Path -LiteralPath $audit)) { exit 0 }

    $output = (& $pyProbe.Source $audit 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        Write-Guard 'I18n parity failure - keys missing or placeholders mismatched:'
        Write-Output $output
        Write-Guard 'Every key must exist in BOTH the en and sv maps, with the same {0}/{1} placeholders.'
        exit 2
    }
    exit 0
}

# ============================================================================
# 4. Kotlin under core/ -- real type check through Gradle
# ============================================================================
if ($rel -like 'core\*.kt') {
    if (Test-RecentOk) { exit 0 }

    $task = if ($rel -like 'core\src\test\*') { ':core:compileTestKotlin' } else { ':core:compileKotlin' }
    $gradlew = Join-Path $root 'gradlew.bat'

    $output = (& $gradlew -p $root $task --console=plain -q 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        $lines = @(($output -split "`r?`n") | Where-Object { $_ -match '^\s*e:\s|error:|FAILED|Execution failed' })
        if ($lines.Count -gt 0) { $output = ($lines -join "`n") }
        Write-Guard "$task failed after editing $rel :"
        Write-Output $output
        exit 2
    }
    Set-RecentOk
    exit 0
}

# ============================================================================
# 5. Compose files under android/ -- hardcoded UI strings (warning, not block)
# ============================================================================
if ($rel -like 'android\*.kt' -and $name -ne 'I18n.kt') {
    $content = Get-Content -LiteralPath $file -Raw
    if (-not $content) { exit 0 }

    # Text("...") with a string literal -- but not Text(I18n.t(...)) or Text(variable).
    $found = [regex]::Matches($content, 'Text\(\s*"([^"]{2,})"')
    $hardcoded = @($found | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -notmatch '\$' } |
        Where-Object { $_ -notmatch '\\u[0-9A-Fa-f]{4}' } |
        Where-Object { $_ -notmatch '^[\s\d\W]*$' } |
        Where-Object { $_ -notmatch '^(mm|in|\u00b0|%|\u2715|\u2713|\+)' })

    if ($hardcoded.Count -gt 0) {
        # Single-quoted strings only: PowerShell does not escape with backslash, so a
        # backslash-quote inside a double-quoted string would terminate it early.
        $preview = ($hardcoded | Select-Object -First 5) -join '", "'
        Write-Guard ('Warning: hardcoded UI string in ' + $name + ' -> "' + $preview + '"')
        Write-Guard 'User-visible text must go through I18n.t(lang, "key") with keys in both en and sv.'
        # Warning, not a block: literals can be legitimate (units, symbols, test ids).
        # No debounce stamp is set here -- that belongs to the Gradle check for core.
        exit 0
    }
    exit 0
}

exit 0
