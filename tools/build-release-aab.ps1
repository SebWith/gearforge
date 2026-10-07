<#
.SYNOPSIS
    Bygger den signerade, uppladdningsbara Play-AAB:n för GearForge.

.DESCRIPTION
    android/build.gradle vägrar medvetet bygga en release med Googles test-AdMob-ID:n,
    så de riktiga ID:na måste skickas in här. Signeringen sköts av Gradle via
    android/keystore.properties — det här skriptet läser aldrig nyckeln eller något
    lösenord.

    Skriptet validerar formatet före bygget, så ett felklistrat ID ger ett tydligt
    svenskt felmeddelande i stället för ett kraschat Gradle-bygge.

.PARAMETER AdmobAppId
    AdMob App ID, format ca-app-pub-<16 siffror>~<10 siffror>.

.PARAMETER AdmobRewardedUnitId
    AdMob Ad unit ID för den belönade videon, format ca-app-pub-<16 siffror>/<10 siffror>.

.PARAMETER ExpectedCertificateSha256
    Full 64-hex SHA-256 of the independently trusted upload certificate.
    Obtain/confirm it from a trusted source such as Play Console, not this AAB.

.PARAMETER ExpectedVersionCode
    Required versionCode to verify in the bundle's own manifest.

.PARAMETER ExpectedVersionName
    Required versionName to verify in the bundle's own manifest.

.PARAMETER Bundletool
    Path to Google's bundletool-all-1.18.2.jar. Defaults to build/tools/.
    verify_aab.py pins its published SHA-256. JDK 17+ and py are required.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
        -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
        -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
        -ExpectedVersionCode 9 -ExpectedVersionName '1.1' `
        -ExpectedCertificateSha256 '<CONFIRMED-TRUSTED-UPLOAD-CERTIFICATE-SHA256>'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AdmobAppId,
    [Parameter(Mandatory = $true)][string]$AdmobRewardedUnitId,
    [string]$ExpectedCertificateSha256,
    [int]$ExpectedVersionCode,
    [string]$ExpectedVersionName,
    [string]$Bundletool
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $root 'android'
$aabPath = Join-Path $moduleDir 'build\outputs\bundle\release\android-release.aab'
$verifier = Join-Path $PSScriptRoot 'verify_aab.py'
$ExpectedPackage = 'com.gearforge.geargenerator'

function Fail($message) {
    # `throw` rather than `exit`: the script is often invoked with & from an
    # interactive session, where `exit` would close the user's shell.
    throw $message
}

# --- 1. Validera inmatningen innan Gradle startas ------------------------------
$appIdPattern = '^ca-app-pub-\d{16}~\d{10}$'
$unitIdPattern = '^ca-app-pub-\d{16}/\d{10}$'

if ($AdmobAppId -notmatch $appIdPattern) {
    Fail "AdmobAppId har fel format.`n  Fick:      $AdmobAppId`n  Förväntat: ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY (tilde, inte snedstreck)"
}
if ($AdmobRewardedUnitId -notmatch $unitIdPattern) {
    Fail "AdmobRewardedUnitId har fel format.`n  Fick:      $AdmobRewardedUnitId`n  Förväntat: ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY (snedstreck, inte tilde)"
}
if ($AdmobAppId -like 'ca-app-pub-3940256099942544*' -or $AdmobRewardedUnitId -like 'ca-app-pub-3940256099942544*') {
    Fail 'Googles test-AdMob-ID får inte användas i en produktionsrelease — testannonser ger ingen intäkt och strider mot AdMob-policy.'
}

$publisherFromApp = ($AdmobAppId -split '~')[0]
$publisherFromUnit = ($AdmobRewardedUnitId -split '/')[0]
if ($publisherFromApp -ne $publisherFromUnit) {
    Fail "De två ID:na kommer från olika AdMob-konton:`n  App ID:  $publisherFromApp`n  Enhet:   $publisherFromUnit`nBåda måste ha samma utgivar-ID."
}

if ($ExpectedCertificateSha256 -notmatch '\A[0-9A-Fa-f]{64}\z') {
    Fail 'ExpectedCertificateSha256 is required: provide a separately trusted full 64-hex upload-certificate digest. An observed AAB certificate is not a trust source.'
}
if ($ExpectedVersionCode -le 0 -or [string]::IsNullOrWhiteSpace($ExpectedVersionName)) {
    Fail 'ExpectedVersionCode and ExpectedVersionName are required.'
}
if ([string]::IsNullOrWhiteSpace($Bundletool)) {
    $Bundletool = Join-Path $root 'build\tools\bundletool-all-1.18.2.jar'
}
if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) { Fail 'verify_aab.py is missing.' }
if (-not (Test-Path -LiteralPath $Bundletool -PathType Leaf)) {
    Fail 'bundletool-all-1.18.2.jar is missing. See verify_aab.py for its official download URL.'
}
foreach ($tool in @('py', 'java', 'jarsigner')) { Get-Command $tool -ErrorAction Stop | Out-Null }
& py --version
if ($LASTEXITCODE -ne 0) { Fail 'Python is unavailable.' }
$verificationArgs = @(
    '--bundletool', $Bundletool,
    '--expected-app-id', $AdmobAppId, '--expected-rewarded-id', $AdmobRewardedUnitId,
    '--expected-package', $ExpectedPackage, '--expected-version-code', "$ExpectedVersionCode",
    '--expected-version-name', $ExpectedVersionName,
    '--expectedCertificateSha256', $ExpectedCertificateSha256
)

# --- 2. Signering måste vara på plats innan vi slösar tid på ett bygge ---------
$keystoreProps = Join-Path $moduleDir 'keystore.properties'
$keystoreFile = Join-Path $moduleDir 'release.keystore'
if (-not (Test-Path $keystoreProps)) {
    Fail "android/keystore.properties saknas. Utan den blir AAB:n osignerad och går inte att ladda upp.`nSkapa filen med nycklarna storeFile, storePassword, keyAlias, keyPassword."
}
if (-not (Test-Path $keystoreFile)) {
    Fail "android/release.keystore saknas. Återställ den, eller gör en ny upload-nyckel i Play Console om du använder Play App Signing."
}

# --- 3. Bygg ----------------------------------------------------------------
'AdMob App ID:  ' + $AdmobAppId
'AdMob enhet:   ' + $AdmobRewardedUnitId
'Bygger signerad release-bundle...'

if (Test-Path -LiteralPath $aabPath) {
    Move-Item -LiteralPath $aabPath -Destination "$aabPath.previous" -Force
}
$buildStartedUtc = [DateTime]::UtcNow
& (Join-Path $root 'gradlew.bat') -p $root :android:bundleRelease --console=plain `
    "-PadmobAppId=$AdmobAppId" "-PadmobRewardedUnitId=$AdmobRewardedUnitId"
if ($LASTEXITCODE -ne 0) { Fail "Gradle-bygget misslyckades (exit $LASTEXITCODE)." }

# --- 4. Verifiera artefakten ------------------------------------------------
if (-not (Test-Path -LiteralPath $aabPath -PathType Leaf)) {
    Fail "Build did not produce the required artifact: $aabPath"
}
$aab = Get-Item -LiteralPath $aabPath
if ($aab.Length -eq 0 -or $aab.LastWriteTimeUtc -lt $buildStartedUtc.AddSeconds(-2)) {
    Fail 'Release artifact is empty or predates this build.'
}
$artifactHash = (Get-FileHash -LiteralPath $aabPath -Algorithm SHA256).Hash
$receiptPath = "$aabPath.verified-$([Guid]::NewGuid().ToString('N')).json"
$LASTEXITCODE = $null
& py $verifier $aabPath @verificationArgs --expected-sha256 $artifactHash --receipt $receiptPath
if ($LASTEXITCODE -ne 0) { Fail "AAB verification failed (exit $LASTEXITCODE)." }
if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) {
    Fail 'Verifier did not produce a verification receipt.'
}
$receipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
if ($receipt.status -cne 'verified' -or $receipt.aab -ne $aabPath -or
    $receipt.sha256 -ne $artifactHash -or $receipt.certificateSha256 -ne $ExpectedCertificateSha256 -or
    $receipt.package -cne $ExpectedPackage -or $receipt.versionCode -ne $ExpectedVersionCode -or
    $receipt.versionName -cne $ExpectedVersionName -or $receipt.appId -cne $AdmobAppId -or
    $receipt.rewardedId -cne $AdmobRewardedUnitId -or $receipt.signedEntries -le 0) {
    Fail 'Verification receipt does not match this release.'
}
if ((Get-FileHash -LiteralPath $aabPath -Algorithm SHA256).Hash -ne $artifactHash) {
    Fail 'Release artifact changed during verification.'
}

"Verified AAB: $aabPath"
"SHA-256: $artifactHash"
"Receipt: $receiptPath"
'Local artifact checks passed. Real Play testing and upload approval remain separate gates.'
