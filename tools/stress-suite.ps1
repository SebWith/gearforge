# Full static suite: both test suites, lint, and the repository audits, with one verdict.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
#   powershell -ExecutionPolicy Bypass -File tools\stress-suite.ps1
#
# Why it covers more than it used to: "SUITE GREEN" meant core tests plus lint, so a run could be
# green while the Android unit tests were red and while a hardcoded string had crept back into the UI.
# Those were run by hand afterwards, and a gate nobody runs is not a gate. Everything checkable
# without a device belongs here; the device lanes stay in stress-app.ps1, one action each.
#
# Counts come from the JUnit XML, never from "it looked green": a suite that silently stops
# running a class still prints BUILD SUCCESSFUL, and a skipped test is not a passing test.
param(
    [string]$Repo = ""
)

if (-not $Repo) { $Repo = Split-Path -Parent $PSScriptRoot }

$log = "$Repo\build\stress-suite.txt"
$summary = "$Repo\build\suite-summary.txt"

function Say([string]$line) {
    Write-Host $line
    try { Add-Content -Path $summary -Value $line -Encoding UTF8 -ErrorAction Stop } catch { }
}

Set-Content -Path $summary -Value "static suite $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -Encoding UTF8

# chcp 1252 first: with the console in another codepage the JVM hands the Kotlin compiler an @argfile
# that is read as ANSI, and the 'o' with diaeresis in this repository's path becomes "u00F6" - the
# build then fails with "source file or directory not found" for a path that plainly exists.
chcp 1252 | Out-Null
# Two invocations rather than one, on purpose. "--rerun" is a task option, so it binds to whichever
# task the command line associates it with; writing ":core:test --rerun :android:testDebugUnitTest"
# invites Gradle to attach it to a task that does not have it, and the geometry suite would then be
# skipped as up to date while the XML counts stayed green from a previous run - a gate that passes
# because nothing ran. Separate calls remove the ambiguity at the cost of one extra JVM start.
& "$Repo\gradlew.bat" :core:test --rerun --console=plain *> $log
$exit = $LASTEXITCODE
& "$Repo\gradlew.bat" :android:testDebugUnitTest :android:lint --console=plain *>> $log
if ($LASTEXITCODE -ne 0) { $exit = $LASTEXITCODE }
$ok = (Select-String -Path $log -Pattern "BUILD SUCCESSFUL" -SimpleMatch -ErrorAction SilentlyContinue).Count
Say "gradle_exit=$exit build_successful=$ok"

function CountSuite([string]$dir, [string]$label) {
    $xml = Get-ChildItem $dir -ErrorAction SilentlyContinue
    $tests = 0; $fails = 0; $skipped = 0
    foreach ($f in $xml) {
        [xml]$d = Get-Content $f.FullName
        $tests += [int]$d.testsuite.tests
        $fails += [int]$d.testsuite.failures + [int]$d.testsuite.errors
        $skipped += [int]$d.testsuite.skipped
    }
    Say "${label}_files=$($xml.Count) tests=$tests failures=$fails skipped=$skipped"
    if ($skipped -gt 0) { Say "  WARNING: $skipped skipped test(s) - a skipped test is not a passing test" }
    return @($tests, $fails)
}

$core = CountSuite "$Repo\core\build\test-results\test\*.xml" "core"
$android = CountSuite "$Repo\android\build\test-results\testDebugUnitTest\*.xml" "android"

$lintErr = 0
$lintReport = "$Repo\android\build\reports\lint-results-debug.txt"
if (Test-Path $lintReport) {
    $lintErr = (Select-String -Path $lintReport -Pattern ": Error:" -ErrorAction SilentlyContinue).Count
    $lintWarn = (Select-String -Path $lintReport -Pattern ": Warning:" -ErrorAction SilentlyContinue).Count
    Say "lint_errors=$lintErr lint_warnings=$lintWarn"
    # Per rule, so a change is visible as a rule and not just as a number. Counting the same report
    # two different ways is how an undercount becomes a phantom regression.
    Select-String -Path $lintReport -Pattern ": Warning:" -ErrorAction SilentlyContinue |
        ForEach-Object { ($_.Line -split "\[")[-1].TrimEnd("]") } |
        Group-Object | Sort-Object Count -Descending |
        ForEach-Object { Say "  rule=$($_.Name) count=$($_.Count)" }
} else {
    Say "lint report missing: $lintReport"
}

# The repository audits. Each prints a verdict line; only the last line is kept, so a report that
# grows by a paragraph does not silently change what "green" means.
$audits = @(
    @{ name = "i18n";           script = "tools\i18n_audit.py" },
    @{ name = "literals";       script = "tools\check_hardcoded_strings.py" },
    @{ name = "persistence";    script = "tools\check_persistence.py" },
    @{ name = "customizations"; script = "tools\validate_customizations.py" }
)
$auditBad = 0
foreach ($a in $audits) {
    $out = & py "$Repo\$($a.script)" 2>&1 | Out-String
    $last = (($out -split "`n") | Where-Object { $_.Trim() } | Select-Object -Last 1)
    Say "audit_$($a.name): $($last.Trim())"
    if ($LASTEXITCODE -ne 0) { $auditBad++ }
}

# The PowerShell layer itself: a syntax error in a lane script means that lane silently does nothing.
$psOut = & powershell -NoProfile -ExecutionPolicy Bypass -File "$Repo\tools\check-ps1.ps1" 2>&1 | Out-String
$psLast = (($psOut -split "`n") | Where-Object { $_.Trim() } | Select-Object -Last 1)
Say "audit_ps1: $($psLast.Trim())"
$psBad = if ($psOut -match "problems=(\d+)") { [int]$Matches[1] } else { 1 }

$totalFails = [int]$core[1] + [int]$android[1] + $auditBad + $psBad
if ($totalFails -gt 0 -or $exit -ne 0 -or $lintErr -gt 0) {
    Say "VERDICT: STATIC SUITE NOT GREEN (failures=$totalFails gradle_exit=$exit lint_errors=$lintErr)"
    exit 1
}
Say "VERDICT: STATIC SUITE GREEN"
