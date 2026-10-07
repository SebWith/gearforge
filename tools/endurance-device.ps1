param(
    [ValidateSet('Probe', 'Run')][string]$Mode = 'Probe',
    # Either console port may host the mc-target AVD; the assertion below still refuses
    # to measure any other AVD/API level, so the original intent is preserved.
    [ValidateSet('emulator-5554', 'emulator-5556')][string]$Serial = 'emulator-5554',
    [ValidateRange(30, 120)][int]$Minutes = 30,
    [string]$ExpectedSha256 = '83011bc3b33842763308a3c494356b7b962612aeada33f01b5a2fd7cfb0c6243',
    [string]$OutputDirectory = '',
    [Nullable[bool]]$SharedHostReleaseBuild = $null
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
$package = 'com.gearforge.geargenerator'
$component = "$package/com.gearforge.app.MainActivity"
$runId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + $Mode.ToLowerInvariant()
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repo "build\audit\endurance-$Serial-$runId"
}
$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
$utf8 = New-Object System.Text.UTF8Encoding($false)
$commandLog = Join-Path $OutputDirectory 'commands.jsonl'
$script:uiSequence = 0
$script:assertions = 0
$script:initialPid = ''
$script:restarts = 0
$script:rounds = 0
$script:homeResumes = 0
$script:nextSample = 0.0
$script:samples = [Collections.Generic.List[object]]::new()
$script:profileDirty = $false
$script:panelOpen = $false
$script:logger = $null
$script:logStream = $null
$script:logCopy = $null
$script:logErrors = $null
$script:originalModule = ''
$script:clock = [Diagnostics.Stopwatch]::new()
$lock = [IO.File]::Open((Join-Path $repo "build\endurance-$Serial.lock"), 'OpenOrCreate', 'ReadWrite', 'None')

function Save-Text([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $OutputDirectory $Name), $Text, $utf8)
}

function Note([string]$Text) {
    $line = [DateTime]::UtcNow.ToString('o') + ' ' + $Text
    [IO.File]::AppendAllText((Join-Path $OutputDirectory 'run.log'), $line + "`n", $utf8)
    Write-Host $line
}

function Invoke-Adb([string[]]$Arguments) {
    $allArguments = @('-s', $Serial) + $Arguments
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.Arguments = ($allArguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = $utf8
    $startInfo.StandardErrorEncoding = $utf8
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $started = [DateTime]::UtcNow
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) {
            $process.Kill()
            throw "ADB command exceeded 60 seconds: $($Arguments -join ' ')"
        }
        $text = $stdout.Result
        $errorText = $stderr.Result
        $timer.Stop()
        $record = [ordered]@{
            Utc = $started.ToString('o'); Seconds = $timer.Elapsed.TotalSeconds
            Arguments = $allArguments; ExitCode = $process.ExitCode; Error = $errorText.Trim()
        }
        [IO.File]::AppendAllText($commandLog, (($record | ConvertTo-Json -Compress) + "`n"), $utf8)
        if ($process.ExitCode -ne 0) { throw "ADB exit $($process.ExitCode): $errorText $text" }
        return $text.Trim()
    } finally { $process.Dispose() }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
    $script:assertions++
}

function Assert-Pid {
    $current = Invoke-Adb @('shell', 'pidof', $package)
    if ($current -ne $script:initialPid) {
        $script:restarts++
        throw "Process continuity failed: initial=$script:initialPid current=$current"
    }
}

function Assert-Identity([string]$Label) {
    Assert-True ((Invoke-Adb @('get-state')) -eq 'device') 'Device online'
    Assert-True ((Invoke-Adb @('emu', 'avd', 'name')) -match '^mc-target\b') 'AVD mc-target'
    Assert-True ((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -eq '36') 'API 36'
    Assert-True ((Invoke-Adb @('shell', 'getprop', 'sys.boot_completed')) -eq '1') 'Boot complete'
    $apk = Get-Item (Join-Path $repo 'android\build\outputs\apk\debug\android-debug.apk')
    $localHash = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $installedPath = (Invoke-Adb @('shell', 'pm', 'path', $package)).Replace('package:', '')
    $deviceHash = (Invoke-Adb @('shell', 'sha256sum', $installedPath)).Split(' ')[0]
    $deviceStat = Invoke-Adb @('shell', 'stat', '-c', '%s:%Y', $installedPath)
    $packageInfo = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
    $identity = [ordered]@{
        Utc = [DateTime]::UtcNow.ToString('o'); Serial = $Serial; Api = 36; Avd = 'mc-target'
        LocalApk = $apk.FullName; LocalBytes = $apk.Length; LocalTimeUtc = $apk.LastWriteTimeUtc.ToString('o')
        LocalSha256 = $localHash; InstalledPath = $installedPath; InstalledSha256 = $deviceHash
        InstalledSizeAndEpoch = $deviceStat; Debuggable = ($packageInfo -match '\bDEBUGGABLE\b')
    }
    Save-Text "identity-$Label.json" ($identity | ConvertTo-Json)
    Assert-True ($localHash -eq $ExpectedSha256 -and $deviceHash -eq $ExpectedSha256) 'Exact requested APK identity'
    Assert-True $identity.Debuggable 'Debuggable APK'
    Note "identity=$deviceHash apkBytes=$($apk.Length) apkUtc=$($apk.LastWriteTimeUtc.ToString('o'))"
}

function Capture-Ui([string]$Label) {
    $script:uiSequence++
    $name = '{0:D5}-{1}' -f $script:uiSequence, $Label
    $remote = "/sdcard/gf-endurance-$runId-$name.xml"
    $result = Invoke-Adb @('shell', 'uiautomator', 'dump', $remote)
    Assert-True ($result -match 'dumped to:') "UI observed: $name"
    $null = Invoke-Adb @('pull', $remote, (Join-Path $OutputDirectory "$name.xml"))
    [xml]$document = [IO.File]::ReadAllText((Join-Path $OutputDirectory "$name.xml"), $utf8)
    Assert-True ($null -ne $document.SelectSingleNode('/hierarchy/node')) "Nonempty hierarchy: $name"
    $nodes = @($document.SelectNodes('//node'))
    $lines = $nodes | Where-Object { $_.GetAttribute('text') -or $_.GetAttribute('content-desc') } | ForEach-Object {
        '{0} | {1} | {2} | {3}' -f $_.GetAttribute('text'), $_.GetAttribute('content-desc'), $_.GetAttribute('bounds'), $_.GetAttribute('class')
    }
    Save-Text "$name-nodes.txt" ($lines -join "`n")
    return ,$document
}

function Find-Node([xml]$Document, [string]$Label) {
    $matches = @($Document.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('text') -eq $Label -or $_.GetAttribute('content-desc') -eq $Label
    })
    Assert-True ($matches.Count -eq 1) "Exactly one observed node: $Label (found $($matches.Count))"
    return $matches[0]
}

function Tap-Node($Node) {
    $bounds = [regex]::Match($Node.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    Assert-True $bounds.Success 'Tap has observed bounds'
    $positionX = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
    $positionY = [int](([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2)
    Assert-True ($positionX -gt 0 -and $positionX -lt 1080 -and $positionY -gt 0 -and $positionY -lt 2280) 'Tap within measured viewport'
    $null = Invoke-Adb @('shell', 'input', 'tap', "$positionX", "$positionY")
}

function Assert-Workspace([xml]$Document) {
    # The gear-type header became a content-desc in the 2026-10 a11y pass
    # ("Gear type: Spur gear"); the workspace check follows that label.
    foreach ($label in @('Gear type: Spur gear', 'View', 'Parameters', 'Export', 'Camera navigation')) {
        $node = Find-Node $Document $label
        Assert-True ($node.GetAttribute('package') -eq $package) "Workspace package: $label"
    }
    Assert-Pid
}

function Shot([string]$Label) {
    $remote = "/sdcard/gf-endurance-$runId-$Label.png"
    $null = Invoke-Adb @('shell', 'screencap', '-p', $remote)
    $null = Invoke-Adb @('pull', $remote, (Join-Path $OutputDirectory "$Label.png"))
    Assert-True ((Get-Item (Join-Path $OutputDirectory "$Label.png")).Length -gt 1000) 'Screenshot bytes captured'
}

function Assert-Selected([xml]$Document, [string]$Label) {
    $node = Find-Node $Document $Label
    $selected = $false
    while ($null -ne $node -and $node.Name -eq 'node') {
        if ($node.GetAttribute('selected') -eq 'true' -or $node.GetAttribute('checked') -eq 'true') { $selected = $true; break }
        $node = $node.ParentNode
    }
    Assert-True $selected "Observed selected profile: $Label"
}

function Read-Module([xml]$Document) {
    # The 2026-10 a11y pass gave the row label AND the seek bar the desc
    # "Module (mm)", so a label-based lookup is ambiguous. The visible text
    # input (EditText) is the module field and must be exactly one.
    $fields = @($Document.SelectNodes('//node[@class="android.widget.EditText"]'))
    Assert-True ($fields.Count -eq 1) 'Exactly one visible module input'
    Assert-True ($fields[0].GetAttribute('content-desc') -eq 'Module (mm)') 'The visible input is the module field'
    return $fields[0].GetAttribute('text')
}

function Capture-Sample {
    Assert-Pid
    $sampleStart = [DateTime]::UtcNow
    $elapsed = $script:clock.Elapsed.TotalSeconds
    $number = $script:samples.Count
    $memory = Invoke-Adb @('shell', 'dumpsys', 'meminfo', $script:initialPid)
    Save-Text ('sample-{0:D2}-meminfo.txt' -f $number) $memory
    $native = [regex]::Match($memory, '(?m)^\s*Native Heap:\s+(\d+)')
    $total = [regex]::Match($memory, 'TOTAL PSS:\s+(\d+)')
    $table = [regex]::Match($memory, '(?m)^\s*Native Heap\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)')
    Assert-True ($native.Success -and $total.Success -and $table.Success) 'All memory fields observed'
    $fdText = Invoke-Adb @('shell', 'run-as', $package, 'ls', "/proc/$script:initialPid/fd")
    Save-Text ('sample-{0:D2}-fd.txt' -f $number) $fdText
    $descriptors = @($fdText -split '\r?\n')
    Assert-True ($descriptors.Count -gt 0 -and @($descriptors | Where-Object { $_ -notmatch '^\d+$' }).Count -eq 0) 'FD count observed, not permission-denied or empty'
    Assert-Pid
    $sample = [pscustomobject][ordered]@{
        Index = $number; Utc = $sampleStart.ToString('o'); ElapsedSeconds = [math]::Round($elapsed, 3)
        Round = $script:rounds; Pid = $script:initialPid
        NativeHeapKb = [int]$native.Groups[1].Value; NativePssKb = [int]$table.Groups[1].Value
        NativeAllocatedKb = [int]$table.Groups[7].Value; TotalPssKb = [int]$total.Groups[1].Value
        Fd = $descriptors.Count; MeasurementSeconds = [math]::Round(([DateTime]::UtcNow - $sampleStart).TotalSeconds, 3)
    }
    $script:samples.Add($sample)
    $script:samples | Export-Csv (Join-Path $OutputDirectory 'samples.csv') -Encoding UTF8 -NoTypeInformation
    $script:nextSample = ([math]::Floor($script:clock.Elapsed.TotalSeconds / 60) + 1) * 60
    Note ("SAMPLE index={0} elapsed={1:F1}s round={2} nativeKb={3} pssKb={4} fd={5}" -f $number, $elapsed, $script:rounds, $sample.NativeHeapKb, $sample.TotalPssKb, $sample.Fd)
}

function Start-LogCapture {
    Save-Text 'logcat-before.txt' (Invoke-Adb @('logcat', '-d', '-v', 'threadtime'))
    $null = Invoke-Adb @('logcat', '-c')
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.Arguments = "-s $Serial logcat -v threadtime"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $script:logger = [Diagnostics.Process]::new()
    $script:logger.StartInfo = $startInfo
    $script:logStream = [IO.File]::Open((Join-Path $OutputDirectory 'logcat-continuous.txt'), 'CreateNew', 'Write', 'Read')
    $null = $script:logger.Start()
    $script:logCopy = $script:logger.StandardOutput.BaseStream.CopyToAsync($script:logStream)
    $script:logErrors = $script:logger.StandardError.ReadToEndAsync()
    $null = Invoke-Adb @('shell', 'log', '-t', 'GF_ENDURANCE', "START_$runId")
}

function Stop-LogCapture {
    if ($null -ne $script:logger) {
        if (-not $script:logger.HasExited) { $script:logger.Kill() }
        $script:logger.WaitForExit()
        $script:logCopy.GetAwaiter().GetResult()
        Save-Text 'logcat-stderr.txt' $script:logErrors.Result
        $script:logStream.Dispose()
        $script:logger.Dispose()
        $script:logger = $null
    }
}

function Workload-Round([xml]$Workspace, [bool]$Resume) {
    Assert-Workspace $Workspace
    $null = Invoke-Adb @('shell', 'input', 'swipe', '430', '800', '780', '980', '1200')
    $orbitUi = Capture-Ui 'orbit'
    Assert-Workspace $orbitUi
    if ($script:rounds -eq 0) { Shot 'first-orbit' }
    Tap-Node (Find-Node $orbitUi 'Parameters')
    $script:panelOpen = $true
    $panelUi = Capture-Ui 'panel-open'
    $null = Find-Node $panelUi 'Geometry'
    # The 2026-10 panel design opens with the Geometry section expanded (rows are
    # lazily composed, so sections below the fold are absent from the tree). If a
    # future or remembered state opens it collapsed, expand it explicitly first.
    $involuteNodes = @($panelUi.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -eq 'Involute' -or $_.GetAttribute('content-desc') -eq 'Involute' })
    if ($involuteNodes.Count -eq 0) {
        Tap-Node (Find-Node $panelUi 'Geometry')
        Start-Sleep -Milliseconds 900
        $panelUi = Capture-Ui 'geometry-expanded'
    }
    Assert-Selected $panelUi 'Involute'
    $module = Read-Module $panelUi
    if (-not $script:originalModule) { $script:originalModule = $module }
    Assert-True ($module -eq $script:originalModule) 'Original module unchanged'
    Tap-Node (Find-Node $panelUi 'Cycloid')
    $script:profileDirty = $true
    $changedUi = Capture-Ui 'profile-cycloid'
    Assert-Selected $changedUi 'Cycloid'
    Assert-Pid
    Tap-Node (Find-Node $changedUi 'Involute')
    $restoredUi = Capture-Ui 'profile-restored'
    Assert-Selected $restoredUi 'Involute'
    Assert-True ((Read-Module $restoredUi) -eq $script:originalModule) 'Module restored/unchanged'
    $script:profileDirty = $false
    Tap-Node (Find-Node $restoredUi 'Geometry')
    $collapsedUi = $null
    for ($collapseAttempt = 0; $collapseAttempt -lt 8; $collapseAttempt++) {
        $candidate = Capture-Ui ('geometry-collapsed' + $(if ($collapseAttempt -eq 0) { '' } else { $collapseAttempt }))
        if (@($candidate.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -eq 'Material' }).Count -ge 1) { $collapsedUi = $candidate; break }
        Start-Sleep -Milliseconds 700
    }
    Assert-True ($null -ne $collapsedUi) 'Collapsed panel shows the Material section row'
    Assert-True (@($collapsedUi.SelectNodes('//node') | Where-Object { $_.GetAttribute('text') -eq 'Involute' -or $_.GetAttribute('content-desc') -eq 'Involute' }).Count -eq 0) 'Collapsed section hides the tooth profile chips'
    Tap-Node (Find-Node $collapsedUi 'Close sheet')
    $script:panelOpen = $false
    $workspaceUi = Capture-Ui 'panel-closed'
    Assert-Workspace $workspaceUi
    $null = Invoke-Adb @('shell', 'input', 'swipe', '780', '980', '430', '800', '1200')
    $workspaceUi = Capture-Ui 'orbit-return'
    Assert-Workspace $workspaceUi
    if ($Resume) {
        $null = Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        $homeUi = Capture-Ui 'home'
        Assert-True ($homeUi.SelectNodes('//node[contains(@package,"launcher")]').Count -gt 0) 'Launcher positively observed after Home'
        Assert-Pid
        Save-Text ('resume-{0:D3}.txt' -f $script:homeResumes) (Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $component))
        $workspaceUi = Capture-Ui 'resumed'
        Assert-Workspace $workspaceUi
        $script:homeResumes++
    }
    $script:rounds++
    Note "ROUND complete=$script:rounds elapsed=$([math]::Round($script:clock.Elapsed.TotalSeconds, 1))s pid=$script:initialPid profile=Involute module=$script:originalModule homeResumes=$script:homeResumes"
    return ,$workspaceUi
}

function Metric-Summary([string]$Property) {
    $values = @($script:samples | ForEach-Object { [double]$_.$Property })
    $tail = @($script:samples | Select-Object -Last 10)
    $meanTime = ($tail | ForEach-Object { $_.ElapsedSeconds / 60 } | Measure-Object -Average).Average
    $meanValue = ($tail | ForEach-Object { [double]$_.$Property } | Measure-Object -Average).Average
    $numerator = 0.0
    $denominator = 0.0
    $monotone = $true
    for ($index = 0; $index -lt $tail.Count; $index++) {
        $timeOffset = $tail[$index].ElapsedSeconds / 60 - $meanTime
        $numerator += $timeOffset * ([double]$tail[$index].$Property - $meanValue)
        $denominator += $timeOffset * $timeOffset
        if ($index -gt 0 -and [double]$tail[$index].$Property -lt [double]$tail[$index - 1].$Property) { $monotone = $false }
    }
    return [ordered]@{
        First = $values[0]; Last = $values[-1]; Drift = $values[-1] - $values[0]
        Min = ($values | Measure-Object -Minimum).Minimum; Max = ($values | Measure-Object -Maximum).Maximum
        TailSamples = $tail.Count; TailDrift = [double]$tail[-1].$Property - [double]$tail[0].$Property
        TailSlopePerMinute = $(if ($denominator -gt 0) { $numerator / $denominator } else { $null })
        TailNondecreasing = $monotone
    }
}

try {
    Note "mode=$Mode output=$OutputDirectory sharedHostReleaseBuild=$(if ($null -eq $SharedHostReleaseBuild) { 'unknown' } else { $SharedHostReleaseBuild }) hostBenchmark=false"
    Note (Invoke-Adb @('version'))
    Assert-Identity 'before'
    $script:initialPid = Invoke-Adb @('shell', 'pidof', $package)
    Assert-True ($script:initialPid -match '^\d+$') 'Single existing app PID'
    Note "initialPid=$script:initialPid"
    $fdText = Invoke-Adb @('shell', 'run-as', $package, 'ls', "/proc/$script:initialPid/fd")
    $fdRows = @($fdText -split '\r?\n')
    Assert-True ($fdRows.Count -gt 0 -and @($fdRows | Where-Object { $_ -notmatch '^\d+$' }).Count -eq 0) 'FD measurement observed with run-as'
    Save-Text 'fd-probe.txt' $fdText
    Save-Text 'meminfo-probe.txt' (Invoke-Adb @('shell', 'dumpsys', 'meminfo', $script:initialPid))
    Assert-True ((Invoke-Adb @('shell', 'wm', 'size')) -eq 'Physical size: 1080x2340') 'Probed screen dimensions'
    $initialUi = Capture-Ui 'initial'
    if ($initialUi.SelectNodes('//node[@content-desc="Close sheet"]').Count -eq 1) {
        Tap-Node (Find-Node $initialUi 'Close sheet')
        $initialUi = Capture-Ui 'initial-view'
    }
    Assert-Workspace $initialUi
    Shot 'initial'
    if ($Mode -eq 'Probe') {
        $script:clock.Start()
        $finalUi = Workload-Round $initialUi $true
        Capture-Sample
        $script:clock.Stop()
        Shot 'probe-final'
        Note "PROBE COMPLETE assertions=$script:assertions fd=$($script:samples[0].Fd); one round only, not an endurance run"
    } else {
        Start-LogCapture
        Save-Text 'gfxinfo-before.txt' (Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package))
        Save-Text 'gfxinfo-reset.txt' (Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'reset'))
        $startedUtc = [DateTime]::UtcNow
        $script:clock.Start()
        Note "WORKLOAD START utc=$($startedUtc.ToString('o')) targetMinutes=$Minutes"
        Capture-Sample
        $workspaceUi = $initialUi
        while ($script:clock.Elapsed.TotalSeconds -lt $Minutes * 60) {
            Assert-True (-not $script:logger.HasExited) 'Continuous logcat capture still active'
            $workspaceUi = Workload-Round $workspaceUi (($script:rounds % 5) -eq 4)
            if ($script:clock.Elapsed.TotalSeconds -ge $script:nextSample) { Capture-Sample }
            if (($script:rounds % 10) -eq 0) { Shot ('round-{0:D3}' -f $script:rounds) }
        }
        $script:clock.Stop()
        $endedUtc = [DateTime]::UtcNow
        Assert-True ($script:clock.Elapsed.TotalSeconds -ge 1800) 'At least 30 actual minutes of workload'
        Assert-True ($script:samples.Count -ge 30) 'At least 30 timestamped memory/fd samples'
        Assert-True (($script:samples[-1].ElapsedSeconds - $script:samples[0].ElapsedSeconds) -ge 1740) 'Samples span at least 29 minutes'
        Assert-Workspace (Capture-Ui 'final-workspace')
        Shot 'final'
        Assert-Identity 'after'
        Save-Text 'gfxinfo-after.txt' (Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package))
        Save-Text 'gfxinfo-framestats-after.txt' (Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'framestats'))
        $null = Invoke-Adb @('shell', 'log', '-t', 'GF_ENDURANCE', "END_$runId")
        Save-Text 'logcat-after.txt' (Invoke-Adb @('logcat', '-d', '-v', 'threadtime'))
        Stop-LogCapture
        $logs = [IO.File]::ReadAllText((Join-Path $OutputDirectory 'logcat-continuous.txt'), $utf8)
        Assert-True ($logs.Contains("START_$runId") -and $logs.Contains("END_$runId")) 'Continuous log contains both run markers'
        $lines = $logs -split '\r?\n'
        $appLines = @($lines | Where-Object { $_ -match "\s$script:initialPid\s" -or $_ -match [regex]::Escape($package) })
        $fatal = @($lines | Where-Object { $_ -match 'FATAL EXCEPTION' })
        $anr = @($lines | Where-Object { $_ -match 'ANR in ' })
        $gl = @($lines | Where-Object { $_ -match 'EGL_BAD_|GL_INVALID_|eglCreateWindowSurface failed|EGL_[A-Z_]+.*(error|fail)' })
        Save-Text 'error-matches.txt' ((@($fatal) + @($anr) + @($gl)) -join "`n")
        $summary = [ordered]@{
            Status = 'WORKLOAD_COMPLETED'; StartedUtc = $startedUtc.ToString('o'); EndedUtc = $endedUtc.ToString('o')
            StopwatchSeconds = $script:clock.Elapsed.TotalSeconds; WallSeconds = ($endedUtc - $startedUtc).TotalSeconds
            Rounds = $script:rounds; Samples = $script:samples.Count; HomeResumes = $script:homeResumes
            InitialPid = $script:initialPid; ProcessRestarts = $script:restarts; XmlCaptures = $script:uiSequence; AssertionsPassed = $script:assertions
            ProfileRestored = (-not $script:profileDirty); OriginalModule = $script:originalModule; PanelClosed = (-not $script:panelOpen)
            NativeHeapKb = Metric-Summary 'NativeHeapKb'; NativePssKb = Metric-Summary 'NativePssKb'
            NativeAllocatedKb = Metric-Summary 'NativeAllocatedKb'; TotalPssKb = Metric-Summary 'TotalPssKb'; Fd = Metric-Summary 'Fd'
            GlobalFatalCount = $fatal.Count; GlobalAnrCount = $anr.Count; GlobalGlErrorCount = $gl.Count
            AppFatalCount = @($appLines | Where-Object { $_ -match 'FATAL EXCEPTION' }).Count
            AppAnrCount = @($appLines | Where-Object { $_ -match 'ANR in ' }).Count
            AppGlErrorCount = @($appLines | Where-Object { $_ -match 'EGL_BAD_|GL_INVALID_|eglCreateWindowSurface failed|EGL_[A-Z_]+.*(error|fail)' }).Count
            SharedHostReleaseBuild = $SharedHostReleaseBuild; HostBenchmark = $false; LeakFreeClaim = $false
            Scope = 'API36 only; no Gradle/install/clear/export/purchase/config-save; profile toggled and restored; view/geometry restored'
        }
        Save-Text 'summary.json' ($summary | ConvertTo-Json -Depth 8)
        Note "WORKLOAD COMPLETED seconds=$($script:clock.Elapsed.TotalSeconds) rounds=$script:rounds samples=$($script:samples.Count) restarts=$script:restarts fatal=$($fatal.Count) anr=$($anr.Count) gl=$($gl.Count)"
        Write-Host ($summary | ConvertTo-Json -Depth 8)
    }
} catch {
    Save-Text 'failure.txt' ($_ | Out-String)
    Note "FAILED: $($_.Exception.Message)"
    if ($script:profileDirty -or $script:panelOpen) {
        try {
            Assert-Pid
            $recoveryUi = Capture-Ui 'failure-state'
            if ($script:profileDirty) {
                Tap-Node (Find-Node $recoveryUi 'Involute')
                $recoveryUi = Capture-Ui 'failure-profile-restored'
                Assert-Selected $recoveryUi 'Involute'
                $script:profileDirty = $false
            }
            if ($recoveryUi.SelectNodes('//node[@content-desc="Collapse section"]').Count -eq 1) {
                Tap-Node (Find-Node $recoveryUi 'Geometry')
                $recoveryUi = Capture-Ui 'failure-geometry-restored'
            }
            Tap-Node (Find-Node $recoveryUi 'Close sheet')
            Assert-Workspace (Capture-Ui 'failure-view-restored')
            $script:panelOpen = $false
            Note 'Restored original profile and closed panel after failure'
        } catch { Save-Text 'restoration-failure.txt' ($_ | Out-String) }
    }
    throw
} finally {
    Stop-LogCapture
    $lock.Dispose()
}