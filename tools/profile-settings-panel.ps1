param(
    [ValidateSet('Snapshot', 'Tap', 'Swipe', 'Back', 'Measure', 'Analyze', 'Trace')]
    [string]$Action = 'Snapshot',
    [string]$Label = 'snapshot',
    [string]$OutputDirectory = 'build/audit/panel-performance-20260924',
    [string]$Adb = 'C:\Android\sdk\platform-tools\adb.exe',
    [string]$Serial = 'emulator-5554',
    [string]$Target = '',
    [string]$Expected = '',
    [int]$X = 0, [int]$Y = 0, [int]$EndX = 0, [int]$EndY = 0,
    [switch]$Sample
)

$ErrorActionPreference = 'Stop'
$package = 'com.gearforge.geargenerator'
$null = New-Item -ItemType Directory -Force $OutputDirectory
$output = (Resolve-Path $OutputDirectory).Path
if ($Label -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Label must be a filename-safe identifier.' }
$prefix = Join-Path $output $Label

function Invoke-Adb([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $result = & $Adb -s $Serial @Arguments 2>&1 | ForEach-Object { "$_" }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exitCode -ne 0) { throw "adb exit ${exitCode}: $Arguments`n$result" }
    return $result
}

function Read-Ui {
    $null = Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/gf-panel.xml')
    $xmlText = (Invoke-Adb @('shell', 'cat', '/sdcard/gf-panel.xml')) -join "`n"
    return [xml]$xmlText
}

function Save-Snapshot {
    $ui = Read-Ui
    for ($attempt = 0; $Expected -and $attempt -lt 4; $attempt++) {
        $found = $ui.SelectNodes('//node') | Where-Object {
            $_.text -eq $Expected -or $_.'content-desc' -eq $Expected
        }
        if ($found) { break }
        $ui = Read-Ui
    }
    $ui.Save("$prefix.xml")
    $null = Invoke-Adb @('shell', 'screencap', '-p', '/sdcard/gf-panel.png')
    $null = Invoke-Adb @('pull', '/sdcard/gf-panel.png', "$prefix.png")
    $ui.SelectNodes('//node') | Where-Object { $_.text -or $_.'content-desc' } |
        ForEach-Object { '{0} | {1} | {2}' -f $_.text, $_.'content-desc', $_.bounds } |
        Tee-Object -FilePath "$prefix-nodes.txt"
    if ($Expected -and -not ($ui.SelectNodes('//node') | Where-Object {
        $_.text -eq $Expected -or $_.'content-desc' -eq $Expected
    })) { throw "Expected UI node did not appear: $Expected" }
}

function Invoke-TargetTap([xml]$Ui, [string]$Name) {
    $node = $Ui.SelectNodes('//node') | Where-Object {
        $_.text -eq $Name -or $_.'content-desc' -eq $Name
    } | Select-Object -First 1
    if ($null -eq $node) { throw "UI target not found: $Name" }
    $bounds = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    $tapX = [int](($bounds[0] + $bounds[2]) / 2)
    $tapY = [int](($bounds[1] + $bounds[3]) / 2)
    $null = Invoke-Adb @('shell', 'input', 'tap', "$tapX", "$tapY")
}

function Get-FrameSummary {
    $lines = Get-Content "$prefix-gfx.txt"
    $header = $lines | Where-Object { $_ -match '^Flags,.*IntendedVsync,' } | Select-Object -First 1
    if (-not $header) { throw 'No gfxinfo frame timeline found.' }
    $captureTime = [long]([regex]::Match(($lines -join "`n"), 'Uptime: (\d+)').Groups[1].Value) * 1000000
    $resetTime = [long]([regex]::Match(($lines -join "`n"), 'Stats since: (\d+)ns').Groups[1].Value)
    if ($captureTime -le $resetTime) { throw 'Invalid capture/reset clock interval.' }
    $rawFrames = @($lines | Where-Object { $_ -match '^[01],\d+,' } |
        ConvertFrom-Csv -Header $header.TrimEnd(',').Split(','))
    $valid = @($rawFrames | Where-Object {
        [long]$_.IntendedVsync -ge $resetTime -and
        [long]$_.FrameCompleted -gt [long]$_.IntendedVsync -and
        [long]$_.FrameCompleted -le $captureTime
    })
    $rawFrames | Where-Object { $_ -notin $valid } | Export-Csv "$prefix-excluded-frames.csv" -NoTypeInformation
    $frames = @($valid | ForEach-Object {
            [pscustomobject]@{
                IntendedVsync = $_.IntendedVsync
                TotalMs = ([long]$_.FrameCompleted - [long]$_.IntendedVsync) / 1e6
                TraversalToDrawMs = ([long]$_.DrawStart - [long]$_.PerformTraversalsStart) / 1e6
            }
        })
    if ($frames.Count -eq 0) { throw 'No completed frames captured.' }
    $frames | Export-Csv "$prefix-frames.csv" -NoTypeInformation
    $summary = [pscustomobject]@{
        Label = $Label
        Frames = $frames.Count
        ExcludedFrames = $rawFrames.Count - $frames.Count
        MaxFrameMs = ($frames.TotalMs | Measure-Object -Maximum).Maximum
        MaxTraversalToDrawMs = ($frames.TraversalToDrawMs | Measure-Object -Maximum).Maximum
        FramesOver50Ms = @($frames | Where-Object TotalMs -gt 50).Count
        Sampled = [bool]$Sample
    }
    $summary | ConvertTo-Json | Set-Content "$prefix-summary.json" -Encoding UTF8
    $summary | Format-List
}

function Get-TraceSummary {
    $bytes = [IO.File]::ReadAllBytes("$prefix.trace")
    $text = [Text.Encoding]::GetEncoding(28591).GetString($bytes)
    $binaryStart = $text.IndexOf("*end`n") + 5
    if ($binaryStart -lt 5) { throw 'Missing ART trace header terminator.' }
    $header = $text.Substring(0, $binaryStart)
    if ($header -notmatch 'clock=dual' -or $header -match 'data-file-overflow=true') {
        throw 'Expected a complete dual-clock sampling trace.'
    }
    if ([Text.Encoding]::ASCII.GetString($bytes, $binaryStart, 4) -ne 'SLOW' -or
        [BitConverter]::ToUInt16($bytes, $binaryStart + 4) -ne 3) { throw 'Expected ART binary version 3.' }
    $recordSize = [BitConverter]::ToUInt16($bytes, $binaryStart + 16)
    if ($recordSize -ne 14) { throw "Unexpected dual-clock record size: $recordSize" }
    $mainThread = [int]([regex]::Match($header, '(?m)^(\d+)\tmain\r?$').Groups[1].Value)
    if ($mainThread -eq 0) { throw 'Main thread missing from trace.' }
    $methods = @{}
    foreach ($line in $header.Split("`n")) {
        if ($line -match '^(0x[0-9a-f]+)\t([^\t]+)\t([^\t]+)') {
            $methods[[Convert]::ToInt32($Matches[1].Substring(2), 16)] = "$($Matches[2]).$($Matches[3])"
        }
    }
    $inclusive = @{}
    $exclusive = @{}
    $categories = [ordered]@{
        NumberRow = 'ControlsKt.*NumberRow'
        OutlinedTextField = 'OutlinedTextField'
        TextLayout = 'TextDelegate|Paragraph|TextMeasurer|TextLayout'
        SettingsPanel = 'GearWorkspaceKt.*SettingsPanel'
        LazyList = 'LazyList|LazyLayout'
        GearSpec = 'com.gearforge.core.GearSpec'
        Mesh = 'com.gearforge.core.(GearBuilder|Loft|MeshOps)'
    }
    $categoryCpu = @{}
    $stack = [Collections.Generic.List[int]]::new()
    $previousCpu = $null
    $totalCpu = 0L
    $records = 0
    $offset = $binaryStart + [BitConverter]::ToUInt16($bytes, $binaryStart + 6)
    for (; $offset + $recordSize -le $bytes.Length; $offset += $recordSize) {
        if ([BitConverter]::ToUInt16($bytes, $offset) -ne $mainThread) { continue }
        $methodAction = [BitConverter]::ToUInt32($bytes, $offset + 2)
        $method = [int]($methodAction -band 4294967292)
        $operation = $methodAction -band 3
        $cpu = [long][BitConverter]::ToUInt32($bytes, $offset + 6)
        if ($null -ne $previousCpu) {
            $delta = $cpu - $previousCpu
            if ($delta -lt 0) { throw 'Nonmonotonic main-thread CPU timestamps.' }
            $totalCpu += $delta
            if ($delta -gt 0 -and $stack.Count -gt 0) {
                $seen = [Collections.Generic.HashSet[int]]::new()
                foreach ($entry in $stack) {
                    if ($seen.Add($entry)) { $inclusive[$entry] += $delta }
                }
                $exclusive[$stack[$stack.Count - 1]] += $delta
                $names = ($stack | ForEach-Object { $methods[$_] }) -join '|'
                foreach ($category in $categories.Keys) {
                    if ($names -match $categories[$category]) { $categoryCpu[$category] += $delta }
                }
            }
        }
        if ($operation -eq 0) { $stack.Add($method) }
        elseif ($operation -in 1, 2) {
            if ($stack.Count -eq 0 -or $stack[$stack.Count - 1] -ne $method) {
                throw "Unbalanced main-thread sample stack at byte $offset."
            }
            $stack.RemoveAt($stack.Count - 1)
        } else { throw "Unexpected ART event $operation" }
        $previousCpu = $cpu
        $records++
    }
    $rows = @($inclusive.Keys | ForEach-Object {
        [pscustomobject]@{ Method = $methods[$_]; InclusiveCpuMs = $inclusive[$_] / 1000.0; ExclusiveCpuMs = $exclusive[$_] / 1000.0 }
    } | Sort-Object InclusiveCpuMs -Descending)
    $rows | Export-Csv "$prefix-methods.csv" -NoTypeInformation
    $summary = [ordered]@{ Label = $Label; MainRecords = $records; MainCpuMs = $totalCpu / 1000.0 }
    foreach ($category in $categories.Keys) { $summary["${category}CpuMs"] = $categoryCpu[$category] / 1000.0 }
    $summary | ConvertTo-Json | Tee-Object -FilePath "$prefix-attribution.json"
    $rows | Sort-Object ExclusiveCpuMs -Descending | Select-Object -First 15 | Format-Table -AutoSize
}

switch ($Action) {
    'Snapshot' { Save-Snapshot }
    'Tap' {
        if ($Target) { Invoke-TargetTap (Read-Ui) $Target }
        else { $null = Invoke-Adb @('shell', 'input', 'tap', "$X", "$Y") }
        Save-Snapshot
    }
    'Swipe' {
        $null = Invoke-Adb @('shell', 'input', 'swipe', "$X", "$Y", "$EndX", "$EndY", '400')
        Save-Snapshot
    }
    'Back' {
        $null = Invoke-Adb @('shell', 'input', 'keyevent', '4')
        Save-Snapshot
    }
    'Measure' {
        if (Test-Path "$prefix-summary.json") { throw 'Use a new label for each measurement.' }
        $before = Read-Ui
        $before.Save("$prefix-before.xml")
        $null = Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'reset')
        $null = Invoke-Adb @('logcat', '-c')
        $profiling = $false
        try {
            if ($Sample) {
                $start = Invoke-Adb @('shell', 'am', 'profile', 'start', '--sampling', '1000', '--clock-type', 'dual', '--profiler-output-version', '3', $package, "/data/local/tmp/$Label.trace")
                $start | Set-Content "$prefix-profile-start.txt"
                if (($start -join ' ') -match 'Error|Exception|failed') { throw ($start -join ' ') }
                $profiling = $true
                $before = Read-Ui
            }
            if (-not $Target) { $Target = 'Parameters' }
            if (-not $Expected) { $Expected = 'Geometry' }
            Invoke-TargetTap $before $Target
            Save-Snapshot
        } finally {
            if ($profiling) {
                Invoke-Adb @('shell', 'am', 'profile', 'stop', $package) | Set-Content "$prefix-profile-stop.txt"
                $null = Invoke-Adb @('pull', "/data/local/tmp/$Label.trace", "$prefix.trace")
            }
            Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'framestats') | Set-Content "$prefix-gfx.txt" -Encoding UTF8
            Invoke-Adb @('logcat', '-d', '-v', 'threadtime') | Set-Content "$prefix-logcat.txt" -Encoding UTF8
        }
        Get-FrameSummary
    }
    'Analyze' { Get-FrameSummary }
    'Trace' { Get-TraceSummary }
}