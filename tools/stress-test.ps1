# Run a filtered core test and print the counts from the JUnit XML.
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252.
#
# Exists because a filtered run needs its counts read from the XML: "BUILD SUCCESSFUL" alone
# does not tell you whether the test you were aiming at actually ran.
param(
    [string]$Filter = "*",
    [string]$Repo = "$PSScriptRoot\.."
)

$ErrorActionPreference = "Continue"
$log = "$Repo\build\stress-test.txt"

& "$Repo\gradlew.bat" :core:test --tests $Filter --console=plain *> $log
$exit = $LASTEXITCODE

$xml = Get-ChildItem "$Repo\core\build\test-results\test\*.xml" -ErrorAction SilentlyContinue
$tests = 0; $fails = 0; $skipped = 0
$names = @()
foreach ($f in $xml) {
    [xml]$d = Get-Content $f.FullName
    $tests += [int]$d.testsuite.tests
    $fails += [int]$d.testsuite.failures + [int]$d.testsuite.errors
    $skipped += [int]$d.testsuite.skipped
    $names += $d.testsuite.name
}
Write-Host "filter=$Filter exit=$exit classes=$($xml.Count) tests=$tests failures=$fails skipped=$skipped"
Write-Host "classes: $($names -join ', ')"

if ($fails -gt 0 -or $exit -ne 0) {
    Select-String -Path $log -Pattern "FAILED|AssertionError|expected|^e: " -ErrorAction SilentlyContinue |
        Select-Object -First 20 | ForEach-Object { Write-Host $_.Line }
    exit 1
}
Write-Host "TEST GREEN"
