# GearForge ADB visual inspection: navigates the wizard for each gear type,
# opens the first preset in the 3D editor and captures a screenshot.
$ErrorActionPreference = 'Continue'
$adb = "C:\Users\sebbe\android-dev\android-sdk\platform-tools\adb.exe"
$outDir = (Get-Location).Path

function Quiet([string[]]$a) { & $adb @a *> $null }

function Capture([string[]]$a) { ((& $adb @a 2>$null) -join "`n") }

function Wait([int]$ms) { Start-Sleep -Milliseconds $ms }

function Get-UiXml() {
    $xml = ''
    for ($i = 0; $i -lt 6; $i++) {
        Quiet @('shell','uiautomator','dump','/sdcard/ui.xml')
        Wait 500
        $xml = Capture @('shell','cat','/sdcard/ui.xml')
        if ($xml -match '<hierarchy') { return $xml }
    }
    return $xml
}

function Save-Screenshot([string]$file) {
    Quiet @('shell','screencap','-p','/sdcard/gf_screen.png')
    Wait 300
    Quiet @('pull','/sdcard/gf_screen.png',$file)
}

function Tap-Text([string]$text) {
    $needle = $text -replace '&','&amp;' -replace '<','&lt;' -replace '>','&gt;'
    $xml = Get-UiXml
    $pat = '<node[^>]*?text="([^"]*' + [regex]::Escape($needle) + '[^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pat)
    if (-not $m.Success) {
        $pat2 = '<node[^>]*?content-desc="([^"]*' + [regex]::Escape($needle) + '[^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        $m = [regex]::Match($xml, $pat2)
    }
    if (-not $m.Success) {
        Write-Host "!! text not found: $text"
        return $false
    }
    $x = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    $y = [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2)
    Quiet @('shell','input','tap',("$x"),("$y"))
    Wait 900
    return $true
}

function Open-Type([string]$label, [bool]$more) {
    if ($more) {
        if (-not (Tap-Text 'More gear types')) { return $false }
        Wait 400
    }
    return (Tap-Text $label)
}

$types = @(
    @{ name='spur';          label='Spur gear';         preset='Fine precision';  more=$false },
    @{ name='helical';       label='Helical gear';      preset='Quiet mesh';      more=$false },
    @{ name='bevel';         label='Bevel gear';        preset='Miter 1:1';       more=$false },
    @{ name='rack';          label='Rack & pinion';     preset='Precision linear';more=$false },
    @{ name='planetary';     label='Planetary gear';    preset='3:1 reduction';   more=$false },
    @{ name='belt';          label='Timing belt';       preset='GT2 20:40';       more=$false },
    @{ name='internal_ring'; label='Internal ring gear';preset='Planetary ring';  more=$true  },
    @{ name='worm_pair';     label='Worm gear pair';    preset='30:1 reduction';  more=$true  }
)

Quiet @('shell','am','force-stop','com.gearforge.geargenerator')
Quiet @('shell','am','start','-n','com.gearforge.geargenerator/com.gearforge.app.MainActivity')
Wait 4000

foreach ($t in $types) {
    Write-Host "=== $($t.name) ==="
    if (-not (Tap-Text 'Create new gear')) { continue }
    Wait 600
    if (-not (Open-Type $t.label $t.more)) { continue }
    Wait 600
    if (-not (Tap-Text $t.preset)) { continue }
    Wait 2200
    $file = Join-Path $outDir ("inspect_" + $t.name + ".png")
    Save-Screenshot $file
    Write-Host "captured $file"
    Quiet @('shell','input','keyevent','KEYCODE_BACK')
    Wait 1000
}

Write-Host "DONE"
