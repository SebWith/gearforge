# Pre-flight: prove the device is running the artifact that is on disk, before any lane runs.
# Reads only; writes build\preflight.txt.
$ErrorActionPreference = "Continue"
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$root = Split-Path -Parent $PSScriptRoot
$out = New-Object System.Collections.Generic.List[string]
function L($s) { Write-Host $s; $out.Add($s) }

$apk = "$root\android\build\outputs\apk\debug\android-debug.apk"
$apkItem = Get-Item -LiteralPath $apk
L "apk path=$apk"
L "apk mtime=$($apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) bytes=$($apkItem.Length)"

foreach ($s in @(& $adb devices | Select-String "emulator-\d+\s+device")) {
    $serial = ($s.ToString() -split "\s+")[0]
    L "=== $serial ==="
    $api = (& $adb -s $serial shell getprop ro.build.version.sdk).Trim()
    $rel = (& $adb -s $serial shell getprop ro.build.version.release).Trim()
    $abi = (& $adb -s $serial shell getprop ro.product.cpu.abi).Trim()
    $density = (& $adb -s $serial shell wm density).Trim()
    $size = (& $adb -s $serial shell wm size).Trim()
    L "  api=$api release=$rel abi=$abi"
    L "  $density | $size"
    $pkg = (& $adb -s $serial shell pm list packages | Select-String -Pattern "gearforge")
    L "  packages: $(($pkg | ForEach-Object { $_.Line.Trim() }) -join ', ')"
    $path = (& $adb -s $serial shell pm path com.gearforge.geargenerator).Trim()
    L "  path=$path"
    if ($path -match "^package:(.+)$") {
        $dev = $Matches[1]
        $devSize = (& $adb -s $serial shell ls -l $dev | Out-String).Trim()
        L "  device ls: $devSize"
    }
    $ver = (& $adb -s $serial shell dumpsys package com.gearforge.geargenerator | Select-String -Pattern "versionCode|versionName" | Select-Object -First 2)
    L "  $(($ver | ForEach-Object { $_.Line.Trim() }) -join ' | ')"
}
$out | Out-File -LiteralPath "$root\build\preflight.txt" -Width 220 -Encoding ascii
