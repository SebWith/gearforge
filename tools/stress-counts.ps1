# Test counts and build outcome, read from the JUnit XML and the Gradle log.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
# The counts are the evidence: "BUILD SUCCESSFUL" alone does not prove a class still ran.
param(
    [string]$Repo = "$PSScriptRoot\.."
)

$xml = Get-ChildItem "$Repo\core\build\test-results\test\*.xml" -ErrorAction SilentlyContinue
$tests = 0; $fails = 0; $skipped = 0
foreach ($f in $xml) {
    [xml]$d = Get-Content $f.FullName
    $tests += [int]$d.testsuite.tests
    $fails += [int]$d.testsuite.failures + [int]$d.testsuite.errors
    $skipped += [int]$d.testsuite.skipped
}
Write-Host "core_test_files=$($xml.Count) tests=$tests failures=$fails skipped=$skipped"
if ($skipped -gt 0) { Write-Host "  WARNING: $skipped skipped test(s) - a skipped test is not a passing test" }

# The Android module's unit tests count too, and they are a different suite in a different
# directory. Leaving them out of this script is how "the tests are green" becomes a statement
# about half the tests.
$axml = Get-ChildItem "$Repo\android\build\test-results\testDebugUnitTest\*.xml" -ErrorAction SilentlyContinue
$atests = 0; $afails = 0; $askipped = 0
foreach ($f in $axml) {
    [xml]$d = Get-Content $f.FullName
    $atests += [int]$d.testsuite.tests
    $afails += [int]$d.testsuite.failures + [int]$d.testsuite.errors
    $askipped += [int]$d.testsuite.skipped
}
Write-Host "android_test_files=$($axml.Count) tests=$atests failures=$afails skipped=$askipped"

$log = "$Repo\build\stress-suite.txt"
if (Test-Path $log) {
    $lines = [System.IO.File]::ReadAllLines($log, [System.Text.Encoding]::Unicode)
    $build = $lines | Where-Object { $_ -match "BUILD SUCCESSFUL|BUILD FAILED" } | Select-Object -Last 1
    Write-Host "gradle: $build"
    $lines | Where-Object { $_ -match "MESH_BUILD_PERF" } | ForEach-Object { Write-Host $_ }
    $failed = $lines | Where-Object { $_ -match " FAILED$" } | Select-Object -First 10
    foreach ($f in $failed) { Write-Host "  FAILED: $f" }
}

$lint = "$Repo\android\build\reports\lint-results-debug.txt"
if (Test-Path $lint) {
    $e = (Select-String -Path $lint -Pattern ": Error:" -ErrorAction SilentlyContinue).Count
    $w = (Select-String -Path $lint -Pattern ": Warning:" -ErrorAction SilentlyContinue).Count
    Write-Host "lint_errors=$e lint_warnings=$w"
    # Per rule, so a change is visible as a rule and not just as a number. Counting the same
    # report two different ways is how an undercount becomes a phantom regression.
    Select-String -Path $lint -Pattern ": Warning:" -ErrorAction SilentlyContinue |
        ForEach-Object { ($_.Line -split "\[")[-1].TrimEnd("]") } |
        Group-Object | Sort-Object Count -Descending |
        ForEach-Object { Write-Host "  rule=$($_.Name) count=$($_.Count)" }
}
