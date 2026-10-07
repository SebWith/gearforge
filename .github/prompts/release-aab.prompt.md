---
description: "Bygg en signerad Play-AAB med release-guarden och verifiera artefakten"
agent: agent
---

# Release-AAB

Följ `.github/skills/android-release-guard/SKILL.md` steg för steg. Bygg inte förrän
pre-flight är grön, och påstå inte att releasen är klar förrän AAB:n är verifierad.

## 1. Pre-flight

```powershell
$root='c:\Users\sebbe\Desktop\överför skrivbord\AndroidGame-Gears'
Test-Path (Join-Path $root 'android\keystore.properties')
Test-Path (Join-Path $root 'android\release.keystore')
Select-String -Path (Join-Path $root 'android\build.gradle') -Pattern 'versionCode|versionName|targetSdk'
Select-String -Path (Join-Path $root 'android\src\main\AndroidManifest.xml') -Pattern 'allowBackup|usesCleartextTraffic'
```

Rapportera värdena och fäll om något saknas.

## 2. versionCode

Läs aktuellt värde, höj med 1, och verifiera mot `CHANGELOG.md` att värdet inte
använts tidigare. Play avvisar ett återanvänt `versionCode`.

## 3. Bygg

Sätt `$expectedVersionCode` och `$expectedVersionName` från steg 2. Bekräfta hela
`$trustedUploadCertSha256` från en oberoende betrodd källa (exempelvis Play Console),
inte från AAB:n. JDK 17+, `py` och hashkontrollerad bundletool 1.18.2 krävs.

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
    -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
    -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
    -ExpectedVersionCode $expectedVersionCode -ExpectedVersionName $expectedVersionName `
    -ExpectedCertificateSha256 $trustedUploadCertSha256
```

Invänta avslutat bygge och obligatorisk verifiering. En existerande utdatafil
bevisar inte att den aktuella körningen lyckades.

## 4. Verifiera artefakten

Använd exakt `android/build/outputs/bundle/release/android-release.aab` och det
unika kvittot från körningens `Receipt`-rad. Kräv `Verified AAB`, kvittostatus
`verified` och överensstämmande SHA-256, paket, version, fullständiga AdMob-ID:n och
betrott certifikat. Signaturen måste täcka samtliga payload-poster. Skriptet
kontrollerar även att testutgivaren saknas. Välj inte en gammal fil utifrån namn
eller senaste ändringstid i en rekursiv sökning.

## 5. Döm

Rapportera sökväg, storlek, tidsstämpel, hash, version, certifikat och kvitto.
Om någon kontroll fallerar, rapportera exakt vilken och ladda inte upp.
Skilj artefaktkontroller från riktiga köp och tester på en R8-minifierad enhetsbyggnad.

Uppdatera `STORE_READINESS.md`s release snapshot och `CHANGELOG.md` i samma ändring.
