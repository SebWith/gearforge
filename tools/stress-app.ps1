# GearForge stress harness.
#
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252, and a stray
# high byte in a string literal has already cost this project a debugging session.
#
# Why text-based tapping: the export sheet GROWS when a format is selected (an extra
# "Size:" row appears), which moves every chip up by ~23 px. A coordinate measured on
# one screenshot hits the WRONG chip on the next. Measured fact, 2026-09-20.
# So: dump the UI hierarchy and tap the centre of the node that carries the label.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\stress-app.ps1 -Action formats
#   powershell -ExecutionPolicy Bypass -File tools\stress-app.ps1 -Action formats -Formats 3MF,SVG
#   powershell -ExecutionPolicy Bypass -File tools\stress-app.ps1 -Action gate
#   powershell -ExecutionPolicy Bypass -File tools\stress-app.ps1 -Action shot -Name x
param(
    [string]$Action = "shot",
    [string]$Formats = "STL,3MF,STEP,IGES,SVG,DXF",
    [string]$Name = "shot",
    [int]$Minutes = 30,
    [string]$Serial = "",
    [string]$Repo = "$PSScriptRoot\.."
)

# Target one device when several are attached. ANDROID_SERIAL is honoured by every adb
# invocation, including child processes, so the whole harness becomes device-parameterisable
# without threading -s through sixty call sites.
if ($Serial) { $env:ANDROID_SERIAL = $Serial; Write-Host "targeting $Serial" }

$ErrorActionPreference = "Continue"
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$pkg = "com.gearforge.geargenerator"
$activity = "com.gearforge.geargenerator/com.gearforge.app.MainActivity"
$out = "$Repo\build\stress-harness.txt"

function Note([string]$line) {
    $stamp = Get-Date -Format "HH:mm:ss"
    Write-Host "$stamp $line"
    # A log write must never change the outcome - see the same guard in stress-rebuild.ps1.
    try { Add-Content -Path $out -Value "$stamp $line" -Encoding UTF8 -ErrorAction Stop } catch { }
}

function Shot([string]$name) {
    & $adb shell screencap -p "/sdcard/$name.png" | Out-Null
    & $adb pull "/sdcard/$name.png" "$Repo\build\$name.png" 2>$null | Out-Null
    if (Test-Path "$Repo\build\$name.png") {
        $len = (Get-Item "$Repo\build\$name.png").Length
        Note "shot=$name bytes=$len"
    }
}

# Keeps the emulator's screen awake. Without this, a UI-driven test measures a black screen:
# uiautomator answers "null root node returned by UiTestAutomationBridge", taps land nowhere,
# and the RESUMED activity is the launcher while the app process is alive - which reads like
# an app that refuses to come to the foreground. Cost three aborted runs before it was noticed.
function KeepAwake {
    & $adb shell svc power stayon true | Out-Null
    & $adb shell settings put system screen_off_timeout 2147483647 | Out-Null
    $w = (& $adb shell dumpsys power | Select-String "mWakefulness=" | Select-Object -First 1)
    if ($w -and $w.Line -notmatch "Awake") {
        & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Seconds 2
        & $adb shell input keyevent 82 | Out-Null
        Start-Sleep -Seconds 2
        Note "keepawake: screen was asleep - woke it up"
    } else {
        Note "keepawake: screen already awake"
    }
}

function UiDump {
    # Retries and clears a stale uiautomator process. A dump that fails answers
    # "ERROR: null root node returned by UiTestAutomationBridge" and leaves the pulled file
    # missing, which used to surface as a null reference instead of as "the dump failed".
    # A killed run leaves its uiautomator process holding the accessibility bridge.
    #
    # Both copies of the dump are DELETED before each attempt, and that is the point: the device
    # file survives a failed `uiautomator dump`, so the following `adb pull` happily copies the
    # PREVIOUS screen and the check reads it as the current one. Measured 2026-09-21: "Show tips
    # again" was reported absent for 23 s in a row while it was on screen, and the next dump found
    # it without any scrolling - the failing probes were serving the workspace's hierarchy from
    # before the dialog opened. A stale artefact read as fresh is the oldest trap in this repo;
    # this is the same trap inside the harness.
    for ($i = 1; $i -le 3; $i++) {
        Remove-Item "$Repo\build\gf-ui.xml" -Force -ErrorAction SilentlyContinue
        & $adb shell rm -f /sdcard/gf-ui.xml 2>$null | Out-Null
        & $adb shell uiautomator dump /sdcard/gf-ui.xml 2>$null | Out-Null
        & $adb pull /sdcard/gf-ui.xml "$Repo\build\gf-ui.xml" 2>$null | Out-Null
        if (Test-Path "$Repo\build\gf-ui.xml") {
            $xml = Get-Content "$Repo\build\gf-ui.xml" -Raw -Encoding UTF8
            if ($xml -and $xml.Contains("<hierarchy")) { $script:dumpOk = $true; return $xml }
        }
        Note "uidump: attempt $i produced no hierarchy - clearing stale uiautomator"
        & $adb shell pkill -f uiautomator 2>$null | Out-Null
        Start-Sleep -Seconds 2
    }
    Note "uidump: FAILED after 3 attempts"
    $script:dumpOk = $false
    return ""
}

# uiautomator writes the hierarchy as XML, so a label containing an entity character arrives
# escaped: the gear type "Rack & pinion" is text="Rack &amp; pinion". Matching the raw label then
# fails forever - and it fails SILENTLY, as "the label is absent", which is the worst shape a harness
# bug can take. Measured 2026-09-21: the type-menu map located 13 of 14 items and the missing one was
# this, not the menu state I first blamed.
function XmlEscape([string]$text) {
    if ($null -eq $text) { return "" }
    return $text.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace('"', "&quot;")
}

# The reverse, for reporting what the dump actually holds in human-readable form.
function XmlUnescape([string]$text) {
    if ($null -eq $text) { return "" }
    return $text.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", '"').Replace("&amp;", "&")
}

# Returns $true when a node with the given label was found and tapped.
function TapText([string]$label) {
    $xml = UiDump
    if (-not $xml) { Note "taptext='$label' NO DUMP"; return $false }
    $esc = [regex]::Escape((XmlEscape $label))
    $m = [regex]::Match($xml, 'text="' + $esc + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) {
        $m = [regex]::Match($xml, 'content-desc="' + $esc + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    }
    if (-not $m.Success) { Note "taptext='$label' NOTFOUND"; return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $x $y | Out-Null
    Note "taptext='$label' x=$x y=$y"
    return $true
}

function Tap([int]$x, [int]$y) { & $adb shell input tap $x $y | Out-Null }

# Press the system back gesture/button. The predictive-back contract cannot be verified by
# looking for a Back arrow in the UI: on API 33+ the gesture is the system's, so the only
# honest test is to perform it and prove which screen answers.
function SysBack {
    & $adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 1400
}

# Whether the LAST UiDump produced a hierarchy. Everything that asks "is this on screen?" has to
# consult it, because a failed dump and an empty screen look identical in the XML: both arrive as
# "". Reading those as "the label is absent" is how an unobserved state becomes a green check -
# measured 2026-09-21, the log holds bursts of "uidump: FAILED after 3 attempts" and every check
# running inside those bursts reported absence. Absence is a fact; not having looked is not.
$script:dumpOk = $false

# Text/description presence, without tapping. Used for evidence: a check that passes because a
# string is on screen is a fact; "the screenshot looked right" is not.
#
# Three-valued on purpose: $true = observed, $false = observed absent, $null = could not observe.
function HasText([string]$label) {
    $xml = UiDump
    if (-not $xml -or -not $script:dumpOk) { return $null }
    $e = [regex]::Escape((XmlEscape $label))
    return ($xml -match ('text="' + $e + '"') -or $xml -match ('content-desc="' + $e + '"'))
}

# $true when the label appeared, $null when it was never successfully looked for.
function WaitText([string]$label, [int]$seconds = 30) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $observations = 0
    while ((Get-Date) -lt $deadline) {
        $seen = HasText $label
        if ($seen -eq $true) { return $true }
        if ($seen -eq $false) { $observations++ }
        Start-Sleep -Milliseconds 800
    }
    if ($observations -eq 0) { return $null }
    return $false
}

# $true as soon as the label is observed absent, $false if it stayed, $null if never observed.
function WaitGone([string]$label, [int]$seconds = 15) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $observations = 0
    while ((Get-Date) -lt $deadline) {
        $seen = HasText $label
        if ($seen -eq $false) { return $true }
        if ($seen -eq $true) { $observations++ }
        Start-Sleep -Milliseconds 800
    }
    if ($observations -eq 0) { return $null }
    return $false
}

# $true only when the label was observed ABSENT on every successful observation across the window.
# This is the primitive for "it must not be there at all" (a tip that may not come back after a
# restart, a screen that may not appear after a back gesture). WaitGone answers "is it gone now";
# this one answers "did it ever show up".
function NeverSeen([string]$label, [int]$seconds = 5) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $observations = 0
    while ((Get-Date) -lt $deadline) {
        $seen = HasText $label
        if ($seen -eq $true) { return $false }
        if ($seen -eq $false) { $observations++ }
        Start-Sleep -Milliseconds 700
    }
    if ($observations -eq 0) { return $null }
    return $true
}

# Records a machine-checkable verdict and counts failures, so a run has one number at the end
# instead of a folder of screenshots someone has to interpret.
#
# $null (could not observe) counts as a failure, not as a pass. A claim made from a state nobody
# managed to look at is not evidence, and the previous version - a [bool] parameter with a failed
# dump arriving as $false - turned every unobserved window into a green line.
function Check([string]$what, $observation) {
    if ($observation -eq $true) {
        Note "check[PASS] $what"
    } elseif ($observation -eq $false) {
        Note "check[FAIL] $what"
        $script:fails++
    } else {
        Note "check[UNOBSERVED] $what - the screen could not be read, so nothing is claimed"
        $script:fails++
    }
}

# Screen height, read from the device rather than assumed: a tap at a y beyond it lands nowhere,
# and "the button does nothing" is then reported as an app defect.
$script:scrH = 0
function ScreenHeight {
    if ($script:scrH -eq 0) {
        $line = (& $adb shell wm size | Select-String -Pattern "(\d+)x(\d+)" | Select-Object -First 1).Line
        if ($line -match "(\d+)x(\d+)") { $script:scrH = [int]$Matches[2] }
        if ($script:scrH -eq 0) { $script:scrH = 2400 }
        Note "screenheight=$($script:scrH)"
    }
    return $script:scrH
}

# Taps a label ONLY when its centre is inside the window. A node can exist in the hierarchy while
# sitting below the fold - Compose renders every child of a verticalScroll - and tapping the
# recorded centre then taps outside the window, which reads as "the setting does nothing".
#
# Known limit: this checks the window, not the scroll container. Measured 2026-09-21, a dump once
# reported a scrolled-out row with a y that was inside the window, and the tap that followed landed
# on whatever was drawn there - it still produced the expected state change, so the pass was real,
# but a check must not rely on that. Anything below a fold goes through TapScrolling, and the
# caller has to verify the outcome rather than trust the tap.
function TapTextOnScreen([string]$label) {
    $xml = UiDump
    if (-not $xml) { return $false }
    $e = [regex]::Escape((XmlEscape $label))
    $m = [regex]::Match($xml, 'text="' + $e + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) {
        $m = [regex]::Match($xml, 'content-desc="' + $e + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    }
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    $h = ScreenHeight
    if ($y -le 0 -or $y -ge $h) { return $false }
    & $adb shell input tap $x $y | Out-Null
    Note "taponscreen='$label' x=$x y=$y"
    return $true
}

# Taps a label that may need scrolling into view first. Used for settings rows that live below
# the fold of a long dialog.
#
# Every swipe is LOGGED, and that is not cosmetic. Without it, "reached after 7454ms" looked like a
# single successful lookup of a row that no dump had ever contained - which sent a whole debugging
# session after a check that was working correctly. The screenshot showed the truth: the dialog was
# scrolled, the row was at y=1680, and the swipes that put it there were simply not in the log.
# The swipe count is also the answer to "how reachable is this row really?".
function TapScrolling([string]$label, [int]$attempts = 8) {
    if (TapTextOnScreen $label) { Start-Sleep -Milliseconds 900; return $true }
    $h = ScreenHeight
    $x = [int](540)
    for ($i = 1; $i -le $attempts; $i++) {
        & $adb shell input swipe $x ([int]($h * 0.70)) $x ([int]($h * 0.35)) 400 | Out-Null
        Start-Sleep -Milliseconds 900
        if (TapTextOnScreen $label) {
            Note "tapscrolling: '$label' required $i swipe(s) to reach and was tapped"
            Start-Sleep -Milliseconds 900
            return $true
        }
        Note "tapscrolling: '$label' still below the fold after $i swipe(s)"
    }
    Note "tapscrolling: '$label' never became tappable in $attempts scrolls"
    return $false
}

# How many numeric labels are laid out beyond the screen edge. At font scale 2.0 the HUD can grow
# past the right edge, which is invisible in a screenshot taken at 1.0 and is the failure mode a
# fixed-size box hides: a clipped label looks like a short label.
function HudLabelsOutsideScreen {
    $xml = UiDump
    if (-not $xml -or -not $script:dumpOk) { return -1 }
    $w = (& $adb shell wm size | Select-String -Pattern "(\d+)x(\d+)" | Select-Object -First 1).Line
    $scrW = 1080
    if ($w -match "(\d+)x(\d+)") { $scrW = [int]$Matches[1] }
    $out = 0
    foreach ($node in [regex]::Matches($xml, '<node[^>]*>')) {
        $t = [regex]::Match($node.Value, 'text="([^"]*)"')
        if (-not $t.Success) { continue }
        $text = $t.Groups[1].Value
        if ($text -notmatch "\d" -or $text -notmatch " ") { continue }
        $b = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $right = [int]$b.Groups[3].Value
        if ($right -gt $scrW) { $out++; Note "  outside: '$text' right=$right screen=$scrW" }
    }
    return $out
}

# The text in the first editable field of the settings panel, which is the module field. Used after a
# unit round trip to prove the panel still renders a value instead of an empty box.
function ModuleFieldText {
    $xml = UiDump
    if (-not $xml -or -not $script:dumpOk) { return $null }
    $m = [regex]::Match($xml, '<node[^>]*class="android.widget.EditText"[^>]*text="([^"]*)"')
    if (-not $m.Success) {
        $m = [regex]::Match($xml, 'text="([^"]*)"[^>]*class="android.widget.EditText"')
    }
    if (-not $m.Success) { return $null }
    return $m.Groups[1].Value
}

function DownloadListing {
    return (& $adb shell ls /sdcard/Download/ 2>$null) | Where-Object { $_ -match "gear_" }
}

function LogcatErrors([string]$tag) {
    # NOTE: uiautomator itself logs "D AndroidRuntime: >>>>>> START com.android.commands.uiautomator"
    # on every dump. Matching bare "AndroidRuntime" therefore ALWAYS reports a crash that is
    # our own instrumentation. Match real crashes only. (Cost: one false positive, 2026-09-20.)
    $found = & $adb logcat -d -t 600 | Select-String -Pattern "FATAL EXCEPTION| E AndroidRuntime|ANR in |EGL_BAD|GL_INVALID|geargenerator.*Exception" |
        Select-Object -Last 20
    if ($found) {
        Note "logcat($tag) ERRORS:"
        foreach ($f in $found) { Note ("  " + $f.Line) }
        # Returned so a lane can turn the log into a VERDICT. Printing the errors and drawing no
        # conclusion from them is how a lane becomes decoration; the count is what a Check can use.
        return @($found).Count
    }
    Note "logcat($tag) clean: no FATAL/ANR/EGL_BAD/GL_INVALID in last 600 lines"
    return 0
}

# Which information rows the export sheet currently shows, plus the y of the DXF chip.
# The chip position is the number that matters: if it moves while the user is reaching for
# it, the sheet is a moving target. Machine-checkable evidence, not a screenshot to squint at.
function ProbeLine {
    $xml = UiDump
    if (-not $xml) { return "NO DUMP" }
    $rows = @()
    foreach ($label in @("File:", "Triangles:", "Size:", "Free exports left:", "used all your free exports", "Watch ad to download", "Download")) {
        if ($xml -match [regex]::Escape((XmlEscape $label))) { $rows += $label }
    }
    $m = [regex]::Match($xml, 'text="DXF"[^>]*?bounds="\[\d+,(\d+)\]')
    $dxfY = if ($m.Success) { [int]$m.Groups[1].Value } else { -1 }
    return "dxfY=$dxfY rows=$($rows -join ' | ')"
}

# Which information rows the export sheet currently shows. Machine-checkable evidence,
# so the probe does not depend on someone reading a screenshot.
function InfoRows {
    $xml = UiDump
    $rows = @()
    if ($xml) {
        foreach ($label in @("File:", "Triangles:", "Size:", "Free exports left:", "used all your free exports", "Watch ad to download", "Download")) {
            if ($xml -match [regex]::Escape((XmlEscape $label))) { $rows += $label }
        }
    }
    return ($rows -join " | ")
}

function OpenExportSheet {
    if (-not (TapText "Export")) { return $false }
    Start-Sleep -Seconds 3
    return $true
}

# Export one format. Returns the file name the app actually created, or $null.
function ExportFormat([string]$fmt) {
    $before = DownloadListing
    if (-not (OpenExportSheet)) { return $null }
    if (-not (TapText $fmt)) { & $adb shell input keyevent 4 | Out-Null; return $null }
    Start-Sleep -Seconds 2
    if (-not (TapText "Download")) {
        Shot "gate-$fmt"
        Note "export=$fmt BLOCKED (no Download button - gate or paywall showing)"
        & $adb shell input keyevent 4 | Out-Null
        return $null
    }
    Start-Sleep -Seconds 7
    $after = DownloadListing
    $new = $after | Where-Object { $before -notcontains $_ } | Select-Object -Last 1
    if (-not $new) {
        Shot "nofile-$fmt"
        Note "export=$fmt NO NEW FILE"
        return $null
    }
    Note "export=$fmt file=$new"
    return $new.Trim()
}

function PullAndVerify([string]$remote, [string]$tag) {
    # Keep the real extension. Pulling to "stress-export-STEP" (no suffix) made
    # verify_export.py skip the format-specific checks and report "okand filandelse" - the
    # file was still checked, but only for basic structure.
    $ext = [System.IO.Path]::GetExtension($remote)
    $tmp = "$Repo\build\stress-export-$tag$ext"
    & $adb pull "/sdcard/Download/$remote" $tmp 2>$null | Out-Null
    if (-not (Test-Path $tmp)) { Note "verify=$tag PULL FAILED"; return }
    $res = & py "$Repo\tools\verify_export.py" $tmp 2>&1
    # Select-String is CASE-INSENSITIVE by default, so counting lines that match "FEL|WARN|error"
    # also counted the summary line "1 fil(er): 1 OK, 0 med varningar, 0 med fel" - every export
    # reported one phantom problem. Report the summary line itself: it is the verdict.
    $summary = ([string]($res | Select-Object -Last 1)).Trim()
    Note "verify=$tag summary: $summary"
}

function ResetAppState {
    # Test-device reset. This clears app data so the free-export counter starts over.
    # It is a reset of the DEVICE state, not a bypass of the gate - the gate itself is
    # exercised deliberately in -Action gate.
    & $adb shell pm clear $pkg | Out-Null
    Note "pm clear $pkg (test-device state reset)"
    & $adb shell am start -n $activity | Out-Null
    Start-Sleep -Seconds 6
}

# Waits until the label exists, WITHOUT tapping it. Use this to prove a screen is reached.
function WaitUntil([string]$label, [int]$seconds = 30) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $xml = UiDump
        if ($xml -match ('text="' + [regex]::Escape((XmlEscape $label)) + '"')) { return $true }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

# Taps the label as soon as it exists, retrying until the timeout. Fixed sleeps are a guess:
# the landing screen and the wizard's thumbnail grid both take longer on a cold start than
# they do on a warm one, and a guessed sleep silently navigates to the wrong screen.
# A system dialog covering the app is dismissed on the way, otherwise every label looks absent.
function WaitAndTap([string]$label, [int]$seconds = 45) {
    $deadline = (Get-Date).AddSeconds($seconds)
    $checkedDialog = $false
    while ((Get-Date) -lt $deadline) {
        if (TapText $label) { return $true }
        if (-not $checkedDialog) { $checkedDialog = DismissSystemDialog }
        Start-Sleep -Milliseconds 1000
    }
    Note "waitandtap: '$label' never appeared within ${seconds}s"
    return $false
}

# True when the screen really is the workspace. "View" and "Export" are the chips that
# only exist there - the landing screen and the wizard have neither. A stress loop that
# does not check this measured the WRONG screen once and produced confident numbers from
# it, which is worse than producing nothing.
function InWorkspace {
    $xml = UiDump
    if (-not $xml) { return $false }
    return ($xml -match 'text="Export"' -and $xml -match 'text="View"')
}

# The emulator can put a system dialog ("System UI isn't responding", "X has stopped") on top
# of the app. It is a SEPARATE WINDOW, so every app label disappears from the hierarchy and the
# harness reports a navigation failure that has nothing to do with the app. Measured
# 2026-09-20 on a freshly booted mc-target running swiftshader software GL: GearForge had
# rendered its landing screen perfectly underneath, and the ANR belonged to com.android.systemui.
# Answering "Wait" keeps the app alive; "Close app" is the fallback.
function DismissSystemDialog {
    $xml = UiDump
    if (-not $xml) { return $false }
    if ($xml -notmatch "isn't responding|is not responding|has stopped|keeps stopping") { return $false }
    # TapText, never WaitAndTap: WaitAndTap calls this function, and routing back through it
    # would recurse without bound.
    foreach ($label in @("Wait", "Close app")) {
        if (TapText $label) {
            Note "systemdialog: dismissed a system-not-responding dialog with '$label'"
            Start-Sleep -Seconds 2
            return $true
        }
    }
    return $false
}

# The UMP consent form is a SEPARATE WINDOW that covers the landing screen. Every label behind
# it disappears from the hierarchy, so a run that does not answer it polls "Create new gear"
# for 90 s and reports a first-run defect that does not exist. Measured 2026-09-20: the form
# appears ~8 s after a start that follows `pm clear` (which wipes the recorded consent), and
# the landing screenshot drops from 2.1 MB to 525 KB when it lands on top.
# Returns $true when a form was found and answered.
function HandleConsent([string]$choice = "Consent") {
    if (-not (WaitAndTap $choice 20)) { return $false }
    Note "consent: answered with '$choice'"
    Start-Sleep -Seconds 3
    return $true
}

# Width in pixels of the widest measurement label currently on screen, or -1 when there is none.
#
# This single number distinguishes a pill that is sized from its text from one that is sized from a
# constant: the first grows when the system font scale grows, the second does not. Measured on the
# buggy build, the widest label was 374 px at font_scale 1.0 and 374 px at 1.5 (136 dp is 374 px at
# this density); after the fix it is 402 px and 605 px.
function WidestHudLabel {
    $xml = UiDump
    if (-not $xml) { return -1 }
    $max = 0
    foreach ($node in [regex]::Matches($xml, '<node[^>]*>')) {
        $t = [regex]::Match($node.Value, 'text="([^"]*)"')
        if (-not $t.Success) { continue }
        $text = $t.Groups[1].Value
        # A measurement label: it carries a number and is not a bare number.
        if ($text -notmatch "\d" -or $text -notmatch " " -or $text -match "^\d") { continue }
        $b = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $w = [int]$b.Groups[3].Value - [int]$b.Groups[1].Value
        if ($w -gt $max) { $max = $w }
    }
    return $max
}

# Reports every label on screen that carries a number, with its laid-out size. This is the pair of
# numbers that settles whether a label's text is clipped: a box sized from its text changes size
# with the text and with the system font scale; a fixed box does not.
function ReportNumericLabels([string]$tag, [bool]$includeBareNumbers) {
    $xml = UiDump
    # ${tag}, not $tag: PowerShell reads "$tag:" as a scoped variable reference and fails to parse
    # the whole file - a one-character mistake that stops every lane, caught by tools\check-ps1.ps1.
    if (-not $xml -or -not $script:dumpOk) { Note "${tag}: NO DUMP - the screen could not be read"; return -1 }
    $count = 0
    foreach ($node in [regex]::Matches($xml, '<node[^>]*>')) {
        $t = [regex]::Match($node.Value, 'text="([^"]*)"')
        if (-not $t.Success) { continue }
        $text = $t.Groups[1].Value
        if ($text -notmatch "\d" -or $text -notmatch " ") { continue }
        if (-not $includeBareNumbers -and $text -match "^\d") { continue }
        $b = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $w = [int]$b.Groups[3].Value - [int]$b.Groups[1].Value
        $h = [int]$b.Groups[4].Value - [int]$b.Groups[2].Value
        Note "  $tag label chars=$($text.Length) w=$w h=$h text='$text'"
        $count++
    }
    Note "$tag labels=$count"
    return $count
}

# `kill -9` needs the privilege to signal another uid's process, and the adb shell does not have it
# on every image. Without root the "process death" faults silently do nothing - measured 2026-09-21:
# pid unchanged at 10831, activity still resumed, two screenshots of a live app - which is the shape
# of a test that reports green while testing nothing.
function EnsureRoot {
    $who = (& $adb shell whoami 2>&1 | Out-String).Trim()
    if ($who -eq "root") { return $true }
    Note "ensureroot: adb shell is '$who', requesting root"
    & $adb root 2>&1 | Out-Null
    Start-Sleep -Seconds 4
    $who = (& $adb shell whoami 2>&1 | Out-String).Trim()
    Note "ensureroot: adb shell is now '$who'"
    return ($who -eq "root")
}

function OpenWorkspace {    # Landing -> wizard -> preset -> workspace, verified by polling instead of sleeping.
    KeepAwake
    & $adb shell am force-stop $pkg | Out-Null
    # A start issued within a second of force-stop is silently ignored: three consecutive
    # attempts polled for 45 s each on a launcher screen while the app never came up, and a
    # manual start with no preceding force-stop worked first time. Wait, then verify the
    # PROCESS is alive before polling the UI - otherwise the failure looks like a missing label.
    Start-Sleep -Seconds 3
    & $adb shell am start -n $activity | Out-Null
    $pidOk = $false
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Milliseconds 1000
        if ((& $adb shell pidof $pkg).Trim()) { $pidOk = $true; break }
    }
    if (-not $pidOk) { Note "openworkspace: the app process never started" }
    DismissSystemDialog | Out-Null
    HandleConsent | Out-Null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not (WaitAndTap "Create new gear" 45)) { continue }
        if (-not (WaitAndTap "Spur gear" 60)) { continue }
        if (-not (WaitAndTap "General purpose" 60)) { continue }
        if (WaitUntil "Export" 30) {
            Note "openworkspace: workspace reached on attempt $attempt"
            return $true
        }
        Note "openworkspace: not in the workspace after attempt $attempt"
    }
    Note "openworkspace: FAILED after 3 attempts"
    return $false
}

# One driver per device, enforced instead of remembered.
#
# Two concurrent runs do not merely run slowly: they share the accessibility bridge and the screen,
# so both produce WRONG verdicts. Measured 2026-09-21: two walkthrough runs in flight made
# `uiautomator dump` return no output at all, every label read as NOTFOUND, the consent form could
# not be dismissed, and checks like "no tip after restart" passed because nothing was visible to
# find. The failure looked exactly like a broken app and was entirely the harness's own doing.
#
# The lock holds the owning pid, so a lock left behind by a killed run is detected as stale and
# taken over instead of blocking the next run forever.
$lock = "$Repo\build\harness.lock"
if (Test-Path $lock) {
    $holder = (Get-Content $lock -Raw -ErrorAction SilentlyContinue)
    if ($holder) { $holder = $holder.Trim() }
    $alive = $false
    if ($holder -match '^\d+$') {
        # Alive AND still the harness. Without the second test a lock left by a pid the OS has
        # since reused would block every future run forever - and a guard nobody can get past gets
        # deleted by the next person instead of fixed.
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $holder" -ErrorAction SilentlyContinue
        $alive = ($null -ne $proc -and $proc.CommandLine -match "stress-app\.ps1")
    }
    if ($alive) {
        Write-Host "REFUSING TO START: another harness run is in flight (pid $holder)."
        Write-Host "  A second driver on one device makes BOTH runs produce false verdicts."
        Write-Host "  Fix: powershell -ExecutionPolicy Bypass -File tools\harness-kill.ps1 -Serial <serial>"
        exit 1
    }
    Write-Host "harness.lock was held by pid $holder, which is gone or is no longer the harness - taking it over"
}
Set-Content -Path $lock -Value $PID -Encoding ascii

# Every lane must run against the build that is on disk, and this is checked here rather than
# trusted, because on 2026-09-22 the device carrying the API 36 evidence was found to be running a
# build from 19:19 the previous evening while the APK on disk was from 00:49 the next morning - the
# manifest, three dex files and a dozen resources differed. Identity was proven only inside
# stress-rebuild.ps1, which has to be invoked on purpose, so a lane could - and did - draw
# conclusions from bytes that no longer matched the source. A gate that has to be remembered is not
# a gate; the check belongs on the path every lane already takes.
#
# The comparison is by content, not by size: a debug APK legitimately arrives on the device a few
# hundred bytes smaller because the installer strips the v1 signature, and a size check called that
# a failure while calling a genuinely stale build a success.
function AssertInstalledBuild {
    $apk = "$Repo\android\build\outputs\apk\debug\android-debug.apk"
    if (-not (Test-Path $apk)) { Note "buildcheck: no APK on disk at $apk"; return $false }
    $f = Get-Item $apk
    $remote = (& $adb shell pm path $pkg 2>&1 | Out-String).Trim().Replace("package:", "")
    if (-not $remote -or $remote -notmatch "^/") {
        Note "buildcheck: the package is not installed (pm path said '$remote')"
        return $false
    }
    $tag = if ($Serial) { $Serial } else { "device" }
    $pulled = "$Repo\build\installed-$tag.apk"
    & $adb pull $remote $pulled 2>&1 | Out-Null
    if (-not (Test-Path $pulled)) { Note "buildcheck: could not pull the installed apk"; return $false }
    $id = (& py "$Repo\tools\apk_identity.py" $f.FullName $pulled 2>&1 | Out-String)
    $same = ($id -match "APK IDENTITY: SAME BUILD")
    $detail = (($id -split "`n") | Where-Object { $_ -match "entries_|only_in|differing|APK IDENTITY" } | ForEach-Object { $_.Trim() }) -join " | "
    Note "buildcheck: disk=$($f.Length) bytes mtime=$($f.LastWriteTime.ToString('MM-dd HH:mm')) installed='$remote' -> $detail"
    return $same
}

if (-not (AssertInstalledBuild)) {
    Note "REFUSING TO RUN: the installed build is not the build on disk. Run tools\stress-rebuild.ps1 first."
    Note "VERDICT: LANE ABORTED (stale build on the device)"
    exit 1
}

# With two emulators attached, every adb call without -s fails with "more than one
# device/emulator" - and the failure is confusing in the worst way: `pidof` returns the ERROR TEXT,
# `HasText` can read nothing and reports unobserved, and the lane dies with a screen full of FAILs
# that look like app defects. stress-rebuild.ps1 learned this first; the same guard belongs here,
# where the two-device matrix is actually driven. Child processes (the background swipe in `races`)
# inherit ANDROID_SERIAL, so naming the device here parameterises every call including those.
$attached = @((& $adb devices) -split "`n" | Select-String -Pattern "\sdevice$")
if (-not $Serial -and $attached.Count -gt 1) {
    Note "REFUSING TO RUN: $($attached.Count) devices are attached and no -Serial was given."
    Note "VERDICT: LANE ABORTED (ambiguous device)"
    exit 1
}
if ($Serial) {
    $present = @((& $adb devices) -split "`n" | Select-String -Pattern ([regex]::Escape($Serial) + "\s"))
    if ($present.Count -eq 0) {
        Note "REFUSING TO RUN: $Serial is not attached."
        Note "VERDICT: LANE ABORTED (device not attached)"
        exit 1
    }
}

# try/finally so an `exit` inside an action still releases the lock: several actions end with
# `exit 1`, and a lock that survives them turns one bad run into a harness nobody can start.
try {
switch ($Action) {
    "shot" { Shot $Name }

    "consent" {
        # Isolated test of HandleConsent, run while the form is actually on screen.
        $ok = HandleConsent "Consent"
        Note "consent action: handled=$ok"
        Start-Sleep -Seconds 3
        Shot "consent-after"
    }

    "newuser" {
        # First-run measurement. After `pm clear` BOTH the thumbnail cache and the recorded
        # consent are gone, so two things can be true at once:
        #   (a) the wizard's type picker waits for GearPreviewRenderer.warmCache() (28 mesh
        #       renders, ~3-5 s each on this emulator), and
        #   (b) the UMP consent form is a separate window that covers the landing screen, so
        #       uiautomator answers for the DIALOG and "Create new gear" is genuinely absent.
        # (b) is expected behaviour and a harness gap; (a) would be a first-run defect. The
        # first screenshot is what tells them apart, so it is taken before anything is tapped.
        KeepAwake
        & $adb shell pm clear $pkg | Out-Null
        Note "newuser: pm clear done"
        & $adb shell logcat -c
        & $adb shell am start -n $activity | Out-Null
        Start-Sleep -Seconds 4
        Shot "newuser-t4s"
        Start-Sleep -Seconds 4
        Shot "newuser-t8s"
        $tC = Get-Date
        $consented = HandleConsent "Consent"
        Note "newuser: consent form handled=$consented after $([int]((Get-Date) - $tC).TotalMilliseconds)ms of polling"
        $t0 = Get-Date
        $landing = WaitAndTap "Create new gear" 90
        $landingMs = [int]((Get-Date) - $t0).TotalMilliseconds
        Note "newuser: 'Create new gear' tappable after ${landingMs}ms (found=$landing)"
        $t1 = Get-Date
        $firstSeen = -1
        for ($i = 0; $i -lt 45; $i++) {
            if ((UiDump) -match 'text="Spur gear"') {
                $firstSeen = [int]((Get-Date) - $t1).TotalMilliseconds
                break
            }
            if ($i -eq 1) { Shot "newuser-wizard-2s" }
            if ($i -eq 6) { Shot "newuser-wizard-12s" }
            Start-Sleep -Milliseconds 1500
        }
        Note "newuser: 'Spur gear' visible after ${firstSeen}ms (-1 = never within ~67s)"
        Shot "newuser-final"
        LogcatErrors "newuser"
    }

    "walkthrough" {
        # The new-user walkthrough and the back contract, end to end, on a freshly cleared app.
        #
        # Every claim is a text check, not a screenshot: the tip's presence, its text, which tip
        # follows which, that it survives a restart, and that the system back gesture walks ONE
        # wizard step instead of discarding the whole choice. Screenshots are kept as artefacts,
        # but no verdict depends on someone reading one.
        #
        # ASCII only, so the multiplication sign and the accented Swedish labels are built from
        # code points rather than typed. The app is English by default (SettingsStore.lang = "en"),
        # which is what makes the English literals safe to assert on here.
        $times = [char]0x00D7
        $script:fails = 0

        $wizTip = "Pick a gear kind. Everything after this is the same for all of them."
        $coach1 = "Drag to orbit the model. Double-tap to frame it again."
        $coach2 = "Export sits next to Parameters, and can share the file as well as save it."
        $coach3 = "Tap a tooth in the model to edit that single tooth."
        $showAgain = "Show tips again"
        $play = "Play the mesh"
        $pause = "Pause the mesh"

        KeepAwake
        & $adb shell pm clear $pkg | Out-Null
        Note "walkthrough: pm clear (first-run state)"
        & $adb shell logcat -c
        & $adb shell am start -n $activity | Out-Null
        Start-Sleep -Seconds 5
        HandleConsent | Out-Null

        # The first-run walkthrough starts on the wizard, which the landing screen leads to. The
        # tip is not on the landing screen, so entering the wizard is part of the fixture - polling
        # for it before that measured the wrong screen and reported a missing tip.
        Shot "wt-00-landing"
        if (-not (WaitAndTap "Create new gear" 90)) {
            Note "walkthrough: the landing screen never offered 'Create new gear'"
        }
        Start-Sleep -Seconds 2

        # A. the wizard's tip is part of the first screen, not a floating bubble over it
        $tipSeen = WaitText $wizTip 90
        Check "wizard tip shown on first run" $tipSeen
        Check "wizard type grid shown at the same time" (HasText "Spur gear")
        Shot "wt-01-firstrun"

        # B. it advances one tip, and the wizard's tip does not come back on the later steps.
        #    Guarded on A: "the tip is gone" is trivially true when the tip never arrived, and a
        #    check that passes for the wrong reason is worse than no check.
        if ($tipSeen) {
            TapText "Next" | Out-Null
            Check "wizard tip cleared by Next" (WaitGone $wizTip 10)
            Check "still on the type step after Next" (HasText "Spur gear")
        } else {
            Note "walkthrough: skipping the tip-advance checks - the wizard tip never appeared"
        }

        # C. the editor continues the same sequence
        TapText "Spur gear" | Out-Null
        Start-Sleep -Seconds 2
        if (-not (WaitAndTap "General purpose" 60)) { Note "walkthrough: could not enter the workspace" }
        $inEditor = WaitText $coach1 45
        Check "editor tip 1 shown" $inEditor
        Shot "wt-02-editor-tip1"

        if ($inEditor) {
            TapText "Next" | Out-Null
            Check "editor tip 2 shown after Next" (WaitText $coach2 15)
            Check "editor tip 1 gone" (WaitGone $coach1 10)
            Shot "wt-03-editor-tip2"

            TapText "Next" | Out-Null
            Check "editor tip 3 shown after Next" (WaitText $coach3 15)
            Check "editor tip 2 gone" (WaitGone $coach2 10)

            TapText "Got it" | Out-Null
            Check "walkthrough finished by Got it" (WaitGone $coach3 10)
            Shot "wt-04-walkthrough-done"

            # D. the playback speed control lives in the chrome, not over the model
            TapText $play | Out-Null
            Check "speed control appears while playing" (WaitText "Playback speed" 10)
            # The ladder is labelled as fractions of the base rate, and the base is the fastest
            # step, so the chip that must be offered is 1x (see PLAYBACK_SPEEDS).
            Check "the base rate is offered" (HasText ("1" + $times))
            TapText ("1" + $times) | Out-Null
            Start-Sleep -Milliseconds 800
            Shot "wt-05-speed-base"
            TapText $pause | Out-Null
            Check "speed control hidden again when paused" (WaitGone "Playback speed" 10)
        } else {
            Note "walkthrough: skipping the editor-tip and speed checks - the editor never opened"
        }

        # E. a finished walkthrough stays finished: rotation, then two cold restarts. NeverSeen,
        #    not "is it gone now": the question is whether the tip comes back at all.
        & $adb shell settings put system accelerometer_rotation 0 | Out-Null
        & $adb shell settings put system user_rotation 1 | Out-Null
        Start-Sleep -Seconds 3
        Check "no tip after rotating to landscape" ((NeverSeen $coach1 3) -and (NeverSeen $coach3 3))
        & $adb shell settings put system user_rotation 0 | Out-Null
        Start-Sleep -Seconds 3
        for ($r = 1; $r -le 2; $r++) {
            & $adb shell am force-stop $pkg | Out-Null
            Start-Sleep -Seconds 2
            & $adb shell am start -n $activity | Out-Null
            Start-Sleep -Seconds 7
            Check "no tip after restart $r" ((NeverSeen $coach1 3) -and (NeverSeen $wizTip 3))
        }
        Shot "wt-06-after-restart"

        # F. back from the editor leaves the editor (it used to leave the whole stage). The two
        #    restarts in E left the app on the landing screen, so the editor is entered again -
        #    pressing back from the landing screen closes the app, which is correct and would have
        #    been measured as "back from the editor did not reach the landing screen".
        $reentered = (WaitAndTap "Create new gear" 45) -and (WaitAndTap "Spur gear" 60) -and
            (WaitAndTap "General purpose" 60) -and (WaitUntil "Export" 30)
        Note "walkthrough: re-entered the editor=$reentered"
        if ($reentered) {
            SysBack
            Check "back from the editor returns to the landing screen" (WaitText "Create new gear" 20)
        } else {
            Note "walkthrough: skipping the editor-back check - the editor was not reached again"
        }

        # G. the wizard's back gesture walks ONE step. Two steps in, one gesture used to throw
        #    the whole choice away - which is what predictive back made visible to the user.
        #    Started from wherever the previous section left the app: on the landing screen the
        #    wizard is entered first, already inside it the entry is skipped.
        $entered = $true
        # HasText is three-valued: $null means the screen could not be read. Unknown is treated as
        # "not confirmed to be inside the wizard", which sends the run through the entry sequence;
        # that costs time but cannot produce a wrong verdict.
        if ((HasText "Spur gear") -ne $true) {
            $entered = WaitAndTap "Create new gear" 30
            if (-not $entered) { Note "walkthrough: onboarding entry never appeared" }
        }
        if ($entered) {
            if (WaitAndTap "Spur gear" 60) {
                if (WaitText "General purpose" 45) {
                    # Two steps in: the presets page. One gesture back must show the type step
                    # again, and must NOT show the landing screen - that is the whole fix.
                    SysBack
                    Check "wizard back walks one step (preset -> type)" (WaitText "Spur gear" 15)
                    Check "wizard back did not leave the wizard" (NeverSeen "Create new gear" 3)
                    SysBack
                    Check "wizard back at the first step leaves the wizard" (WaitText "Create new gear" 20)
                } else {
                    Note "walkthrough: preset page never appeared"
                }
            } else {
                Note "walkthrough: type step never appeared"
            }
        }

        # H. the tips can be asked for again, which is what makes "once" honest
        #
        # The row sits below the fold of a long dialog, and Compose keeps scrolled-out nodes out of
        # the accessibility tree - so "is it visible without scrolling" is not a property the app
        # promises, and a Check on it failed on three consecutive runs for a reason that was never
        # the app's fault (2026-09-21). What IS promised is that the row can be reached and that
        # pressing it brings the walkthrough back. Scrolling is what a user does; the swipe count
        # and the time are recorded so a regression in reachability is still visible.
        if (WaitAndTap "Settings" 30) {
            # Reported as three distinct outcomes on purpose: "absent" and "could not be read" are
            # different facts, and conflating them is what made this line the session's only
            # unexplained failure.
            $inTree = HasText $showAgain
            $inTreeText = if ($inTree -eq $true) { "observed" } elseif ($inTree -eq $false) { "absent" } else { "unreadable (dump failed)" }
            Note "walkthrough: 'Show tips again' in the accessibility tree before scrolling=$inTreeText"
            $t0 = Get-Date
            $reached = TapScrolling $showAgain 8
            $ms = [int]((Get-Date) - $t0).TotalMilliseconds
            Note "walkthrough: 'Show tips again' reached after ${ms}ms (scrolling allowed)"
            Shot "wt-07-settings"
            Check "'Show tips again' reachable in Settings" $reached
            Start-Sleep -Seconds 1
            TapText "OK" | Out-Null
            Start-Sleep -Seconds 1
            if (WaitAndTap "Create new gear" 30) {
                Check "wizard tip returns after 'Show tips again'" (WaitText $wizTip 30)
                Shot "wt-08-tips-restarted"
            } else {
                Note "walkthrough: landing screen never came back"
            }
        } else {
            Note "walkthrough: Settings never opened from the landing screen"
        }

        LogcatErrors "walkthrough"
        Note "walkthrough: failures=$($script:fails)"
    }

    "hudprobe" {
        # Exact geometry of the measurement HUD's labels, from the accessibility tree.
        #
        # Why this exists: whether a label's text is CLIPPED cannot be settled by looking at a
        # screenshot - but it can be settled by comparing the laid-out width of the label node
        # with the length of its text. Before the fix every anchored label was exactly as wide as
        # LABEL_WIDTH (136dp = 374px at 1080x2400/2.75), so the longest ones ("Pitch diameter
        # 20.000 mm") lost their unit off the end with no ellipsis and no error. After the fix the
        # width tracks the text, so a longer label is a wider node.
        $xml = UiDump
        if (-not $xml) { Note "hudprobe NO DUMP"; break }
        $labels = 0
        $widths = @{}
        foreach ($node in [regex]::Matches($xml, '<node[^>]*>')) {
            $t = [regex]::Match($node.Value, 'text="([^"]*)"')
            if (-not $t.Success) { continue }
            $text = $t.Groups[1].Value
            # A measurement label: it names something and carries a number.
            if ($text -notmatch "\d" -or $text -notmatch " ") { continue }
            if ($text -match "^\d") { continue }
            $b = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $b.Success) { continue }
            $x1 = [int]$b.Groups[1].Value; $x2 = [int]$b.Groups[3].Value
            $w = $x2 - $x1
            $h = [int]$b.Groups[4].Value - [int]$b.Groups[2].Value
            Note ("  hudlabel chars=$($text.Length) w=$w h=$h x1=$x1 x2=$x2 text='$text'")
            $widths[$text.Length] = $w
            $labels++
        }
        $distinct = ($widths.Values | Select-Object -Unique).Count
        Note "hudprobe labels=$labels distinct_widths=$distinct"
        if ($labels -ge 2 -and $distinct -le 1) {
            Note "hudprobe: every label has the SAME width - the box is fixed and long labels are clipped"
        }
        Shot "hudprobe"
    }

    "bedprobe" {
        # The print bed's own labels: turn the platen on, then report their laid-out geometry.
        #
        # The platen is off by default, so a run that does not enable it measures a screen without
        # the thing being checked - and would report "no labels found" as if that were a pass.
        if (-not (OpenWorkspace)) { Note "bedprobe: could not reach the workspace"; exit 1 }
        $opened = TapText "More"
        Start-Sleep -Seconds 1
        $enabled = TapText "Show print bed"
        if (-not $enabled) {
            # The bed is already on: the setting persists, so a second probe in the same session
            # offers "Hide print bed" instead. Leaving the menu open would measure the MENU, and
            # report "no labels" as though the platen had none.
            Note "bedprobe: 'Show print bed' not offered - the bed is already on; closing the menu"
            SysBack
        }
        Note "bedprobe: overflow_opened=$opened bed_enabled=$enabled"
        Start-Sleep -Seconds 3
        ReportNumericLabels "bed" $true | Out-Null
        Shot "bedprobe"
    }

    "settingsprobe" {
        # How long does the Settings dialog take to expose its scrolled-out rows, measured rather
        # than argued about?
        #
        # The history: a run reported "Show tips again" absent from the accessibility tree for 23 s
        # while a screenshot showed it on screen, and the next tap found it in one go. That looked
        # like a haunted check. It was not: two harness runs were in flight holding the same
        # uiautomator bridge, so most of those dumps returned nothing at all and the old two-valued
        # HasText reported "nothing" as "absent". With one driver and a three-valued HasText the
        # remaining question is narrow and answerable - the dialog animates in, so the row can enter
        # the tree a few seconds after the window appears.
        #
        # This prints the first moment the row is OBSERVED, and how many readable observations
        # happened before it. Those two numbers are what separate "slow to appear" from "never
        # appears".
        if (-not (OpenWorkspace)) { Note "settingsprobe: could not reach the workspace"; exit 1 }
        SysBack
        Start-Sleep -Seconds 2
        if (-not (WaitAndTap "Settings" 30)) { Note "settingsprobe: no Settings entry on the landing screen" }

        $t0 = Get-Date
        $readable = 0
        $unreadable = 0
        $firstSeenMs = -1
        for ($i = 1; $i -le 40; $i++) {
            $seen = HasText "Show tips again"
            if ($seen -eq $null) { $unreadable++ } else {
                $readable++
                if ($seen -eq $true) { $firstSeenMs = [int]((Get-Date) - $t0).TotalMilliseconds; break }
            }
            Start-Sleep -Milliseconds 500
        }
        Note "settingsprobe: row first observed after ${firstSeenMs}ms (readable=$readable unreadable=$unreadable)"
        if ($firstSeenMs -lt 0) { Note "settingsprobe: it never entered the tree within ~20s of readable polls" }

        Shot "settingsprobe"
        $xml = UiDump
        if (-not $xml -or -not $script:dumpOk) { Note "settingsprobe: no readable dump for the node listing"; break }
        foreach ($node in [regex]::Matches($xml, '<node[^>]*>')) {
            $t = [regex]::Match($node.Value, 'text="([^"]*)"')
            if (-not $t.Success -or -not $t.Groups[1].Value) { continue }
            $b = [regex]::Match($node.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $b.Success) { continue }
            Note ("  node y1=$($b.Groups[2].Value) y2=$($b.Groups[4].Value) x1=$($b.Groups[1].Value) text='$($t.Groups[1].Value)'")
        }
    }

    "hudscale" {
        # Font-scale invariance of the measurement HUD - the regression test for the defect where a
        # fixed 136 dp label box silently ate the unit off the longest dimensions.
        #
        # The signature of a fixed box is that the label width does NOT change when the system font
        # scale does. So this measures the widest label at two scales and asserts it grew. Nothing
        # about the app's own state is touched; the scale is restored at the end either way.
        $script:fails = 0
        & $adb shell settings put system font_scale 1.0 | Out-Null
        if (-not (OpenWorkspace)) { Note "hudscale: could not reach the workspace at scale 1.0"; exit 1 }
        $a = WidestHudLabel
        Note "hudscale: widest label at font_scale 1.0 = $a px"

        & $adb shell settings put system font_scale 1.5 | Out-Null
        # A scale change recreates the activity, and the harness reaches the workspace the same way
        # a user does, so a cold start is the honest measurement.
        if (-not (OpenWorkspace)) { Note "hudscale: could not reach the workspace at scale 1.5"; exit 1 }
        $b = WidestHudLabel
        Note "hudscale: widest label at font_scale 1.5 = $b px"
        Shot "hudscale-15"

        & $adb shell settings put system font_scale 1.0 | Out-Null

        $ratio = if ($a -gt 0) { [math]::Round($b / $a, 2) } else { 0 }
        Note "hudscale: ratio=$ratio (a label sized from its text grows with the scale; a fixed box gives 1.0)"
        if ($a -le 0 -or $b -le 0) {
            # No labels were read at all, so there is no measurement to compare. Reporting a ratio
            # of 0 here would be a verdict about the app derived from a failed look at it.
            Check "the HUD label is measured at the current font scale" $null
        } else {
            Check "the HUD label is measured at the current font scale" ($ratio -ge 1.3)
        }
        Note "hudscale: failures=$($script:fails)"
    }

    "recover" {        # Clear a stale uiautomator, wake the screen, verify a dump works, and prove the app
        # can be brought to the foreground. Run this after any interrupted test run.
        & $adb shell pkill -f uiautomator 2>$null | Out-Null
        Start-Sleep -Seconds 2
        KeepAwake
        $xml = UiDump
        $ok = ($xml -and $xml.Contains("<hierarchy"))
        Note "recover: dump_ok=$ok"
        & $adb shell am start -n $activity | Out-Null
        Start-Sleep -Seconds 6
        $res = (& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1)
        Note "recover: $(($res.Line -replace '\s+', ' ').Trim())"
        if (-not $ok) { Note "recover: the accessibility bridge is still broken"; exit 1 }
    }

    "uiverify" {
        # Accessibility / purpose lane: every clickable node, with bounds, from the current
        # screen. Also reports nodes that are clickable but carry no label.
        # The label is the UNION OF THE SUBTREE, not the node's own attributes: a Compose
        # Modifier.clickable { Text(...) } reports its text on a child, so reading only the node
        # itself reported all 12 clickable nodes as unlabelled on a screen where none were.
        $xml = UiDump
        $nodes = [regex]::Matches($xml, '<node[^>]*>')
        Note "uiverify nodes=$($nodes.Count)"
        foreach ($n in $nodes) {
            $cl = [regex]::Match($n.Value, 'clickable="([^"]*)"')
            if (-not ($cl.Success -and $cl.Groups[1].Value -eq "true")) { continue }
            $b = [regex]::Match($n.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            $w = 0; $h = 0
            if ($b.Success) {
                $w = [int]$b.Groups[3].Value - [int]$b.Groups[1].Value
                $h = [int]$b.Groups[4].Value - [int]$b.Groups[2].Value
            }
            $t = [regex]::Match($n.Value, 'text="([^"]*)"')
            $c = [regex]::Match($n.Value, 'content-desc="([^"]*)"')
            $label = ($t.Groups[1].Value + " " + $c.Groups[1].Value).Trim()
            $dp = [math]::Round($h / 2.75, 0)
            $flag = if (-not $label) { " NO-LABEL" } elseif ($h -lt 132) { " SMALL-TARGET" } else { "" }
            Note ("  clickable label='$label' ${w}x${h} (h=${dp}dp)$flag")
        }
    }

    "formats" {
        # Every 3D format goes through the same writer path; the free-export gate allows three
        # downloads before it blocks, so a run covers three formats and then needs -Action reset.
        if (-not (OpenWorkspace)) { Note "FORMATS ABORT: could not reach the workspace"; exit 1 }
        & $adb logcat -c
        foreach ($f in ($Formats -split ",")) {
            $f = $f.Trim()
            $file = ExportFormat $f
            if ($file) { PullAndVerify $file $f }
        }
        LogcatErrors "formats"
    }

    "gate" {
        # Drive the free-export counter to zero and capture what the user sees.
        & $adb logcat -c
        for ($i = 0; $i -lt 6; $i++) {
            $file = ExportFormat "STL"
            if ($file) { Note "gate round=$i file=$file" } else { Note "gate round=$i blocked"; break }
        }
        Shot "gate-final"
        LogcatErrors "gate"
    }

    "reset" { ResetAppState }

    "killtest" {
        $script:fails = 0
        # Process death, for real. `am kill` only reclaims CACHED processes - measured: the pid
        # was unchanged after it, so a faults run that relies on `am kill` tests nothing.
        #
        # `kill -9` needs the privilege to signal ANOTHER uid's process, and the adb shell does not
        # have it on every image: measured 2026-09-21 on mc-api26 (API 26), kill -9 left the pid at
        # 10831 and the activity resumed - so the run produced two screenshots of a live app while
        # reporting a process-death test. It is now a hard FAIL with the reason and the fix, because
        # a test that cannot kill the thing it claims to kill tests nothing, and silence about that
        # is exactly how this repository has been misled before.
        OpenWorkspace
        TapText "Parameters" | Out-Null
        Start-Sleep -Seconds 2
        $script:fails = 0
        $p1 = (& $adb shell pidof $pkg)
        $p1 = if ($p1) { $p1.Trim() } else { "" }
        Note "killtest pid before=$p1"
        & $adb logcat -c
        & $adb shell "kill -9 $p1" | Out-Null
        Start-Sleep -Seconds 3
        # pidof prints nothing when the process is gone, and .Trim() on $null throws - which
        # made a successful process-death test look like a harness crash.
        $p2 = (& $adb shell pidof $pkg)
        $p2 = if ($p2) { $p2.Trim() } else { "" }
        $changed = ($p1 -ne $p2)
        Note "killtest pid after kill='$p2' changed=$changed"
        Shot "killtest-dead"
        if (-not $changed) {
            $script:fails++
            Note "killtest: FAIL - the process survived kill -9, so no process-death behaviour was"
            Note "killtest:      exercised. The adb shell cannot signal another uid without root."
            Note "killtest:      Fix: 'adb root' on the device (or 'adb shell su -c') and re-run."
            Note "killtest: failures=$($script:fails)"
            break
        }
        & $adb shell am start -n $activity | Out-Null
        Start-Sleep -Seconds 7
        $p3 = (& $adb shell pidof $pkg)
        $p3 = if ($p3) { $p3.Trim() } else { "" }
        $res = (& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1)
        Note "killtest pid after relaunch=$p3 resumed=$($res.Line)"
        Check "the app comes back after a real process death" (($p3 -ne "") -and ($res.Line -match $pkg))
        Shot "killtest-restarted"
        LogcatErrors "killtest"
        Note "killtest: failures=$($script:fails)"
    }

    "monkey" {
        # Broad crash finder: unknown paths, not deep ones. Run alongside the targeted flows,
        # never instead of them. Monkey crashes for reasons that are not the app's fault, so
        # every hit must be reproduced by hand before it counts as a finding.
        KeepAwake
        OpenWorkspace
        & $adb logcat -c
        & $adb shell monkey -p $pkg --throttle 80 --ignore-crashes --ignore-timeouts -v 3000 *> "$Repo\build\stress-monkey.txt"
        $ev = (Select-String -Path "$Repo\build\stress-monkey.txt" -Pattern "Events injected" -SimpleMatch | Select-Object -Last 1)
        Note "monkey done: $($ev.Line)"
        LogcatErrors "monkey"
        Shot "monkey-final"
    }

    "endurance" {
        # Endurance lane: keep the app working for [Minutes] minutes while sampling memory and
        # file descriptors once a minute. A plateau, not a single value, is the evidence.
        #
        # Deliberately dump-free. A uiautomator dump fails intermittently right after the orbit
        # swipe and the double tap, and a loop that reads the hierarchy on every round then
        # "loses the workspace" and re-navigates - which force-stops the process and destroys
        # the very memory curve the run exists to measure. A wrong call, measured 2026-09-20:
        # two 30-minute runs were thrown away before the dump, not the app, was identified.
        # Coordinates are the measured chip positions; the loop sends them blind and proves
        # the state with a screenshot and a pid check instead of with the hierarchy.
        if (-not (OpenWorkspace)) { Note "ENDURANCE ABORT: could not reach the workspace"; exit 1 }
        & $adb logcat -c
        $deadline = (Get-Date).AddMinutes($Minutes)
        $round = 0
        $samples = 0
        $restarts = 0
        $heapMb = @()
        $fdSamples = @()
        $nextSample = Get-Date
        $lastPid = (& $adb shell pidof $pkg).Trim()
        Note "endurance start: pid=$lastPid minutes=$Minutes"
        while ((Get-Date) -lt $deadline) {
            $round++
            # Parameter sheet: open, move the module slider both ways, close with the View chip.
            & $adb shell input tap 360 352 | Out-Null
            Start-Sleep -Seconds 2
            & $adb shell input swipe 220 1648 780 1648 400 | Out-Null
            Start-Sleep -Milliseconds 700
            & $adb shell input swipe 780 1648 220 1648 400 | Out-Null
            Start-Sleep -Milliseconds 700
            & $adb shell input tap 108 352 | Out-Null
            Start-Sleep -Seconds 1
            # Orbit the model, then re-frame it with a double tap.
            & $adb shell input swipe 500 1250 880 1000 250 | Out-Null
            Start-Sleep -Milliseconds 300
            & $adb shell input tap 560 1350 | Out-Null
            Start-Sleep -Milliseconds 120
            & $adb shell input tap 560 1350 | Out-Null
            Start-Sleep -Seconds 1

            # A crash shows up here as a new pid, which is the one thing the dump could never
            # tell apart from its own failure.
            $curPid = (& $adb shell pidof $pkg).Trim()
            if (-not $curPid) {
                Note "endurance: PROCESS GONE at round $round (was pid $lastPid)"
                $restarts++
            } elseif ($curPid -ne $lastPid) {
                Note "endurance: PROCESS RESTARTED at round $round ($lastPid -> $curPid)"
                $lastPid = $curPid
                $restarts++
            }

            if ((Get-Date) -ge $nextSample) {
                $nextSample = (Get-Date).AddMinutes(1)
                $samples++
                $fd = -1
                if ($curPid) { $fd = (& $adb shell "ls /proc/$curPid/fd | wc -l").Trim() }
                $nativeLine = (& $adb shell dumpsys meminfo $pkg | Select-String "Native Heap:" | Select-Object -First 1) -replace "\s+", " "
                $total = (& $adb shell dumpsys meminfo $pkg | Select-String "TOTAL PSS:" | Select-Object -First 1) -replace "\s+", " "
                Note "endurance minute=$samples round=$round fd=$fd $nativeLine $total"
                # The series is collected so the lane can state a verdict instead of leaving thirty
                # minutes of numbers for someone to interpret by eye. The line WITH the colon is the
                # detail row; "Native Heap" also appears as a table row without one.
                $m = [regex]::Match([string]$nativeLine, "Native Heap:\s+(\d+)")
                if ($m.Success) { $heapMb += [double]$m.Groups[1].Value / 1024.0 }
                if ($fd -match "^\d+$") { $fdSamples += [int]$fd }
            }
            if ($samples -gt 0 -and $samples % 5 -eq 0 -and $round -lt 3) { Shot "endurance-mid" }
        }
        Note "endurance done minutes=$samples rounds=$round process_restarts=$restarts"
        if ($samples -lt 5) { Note "ENDURANCE INCONCLUSIVE: fewer than 5 samples"; exit 1 }

        # The verdict the lane exists to produce. "It ran for thirty minutes" is not a result; a
        # memory curve that flattens is.
        #
        # Warm-up is excluded, and the reason is measured rather than convenient. On the completed
        # 2026-09-21 series (29 samples, 239 rounds, API 36) the native heap went 33.7 -> 44.3 MB over
        # the first six minutes and then oscillated between 44.7 and 48.3 MB for the remaining
        # twenty-three, i.e. the opening samples measure the preview cache filling, not a leak. The
        # earlier ten-sample lane that looked like a leak was the same warm-up, cut off before it
        # settled - so excluding it is the correction of a statistic, not a way past one.
        #
        # Two statistics instead of one, because they catch different shapes: a NET DRIFT over the
        # whole steady window catches a slow monotone climb, and a TAIL RANGE catches growth that is
        # still accelerating at the end. Both are deliberately tighter than the old whole-run min/max,
        # which failed on a single transient excursion (measured fd: one sample at 322 in the warm-up
        # against 309-314 for the last ten minutes) while being perfectly happy with a slow leak.
        # A one-fd-per-minute leak is +10 over the tail window and fails; a one-edit mesh leak is
        # 0.2-0.4 MB x 240 edits = 50-100 MB and fails both by two orders of magnitude.
        $warmup = 3
        $steady = $heapMb | Select-Object -Skip $warmup
        if ($steady.Count -ge 8) {
            $firstSteady = $steady[0]
            $last = $steady[$steady.Count - 1]
            $min = ($steady | Measure-Object -Minimum).Minimum
            $max = ($steady | Measure-Object -Maximum).Maximum
            $fdSteady = if ($fdSamples.Count -gt $warmup) { $fdSamples | Select-Object -Skip $warmup } else { @() }
            $fdMin = if ($fdSteady.Count) { ($fdSteady | Measure-Object -Minimum).Minimum } else { -1 }
            $fdMax = if ($fdSteady.Count) { ($fdSteady | Measure-Object -Maximum).Maximum } else { -1 }
            $tailN = [Math]::Min(10, $steady.Count)
            $tail = $steady | Select-Object -Last $tailN
            $tailMin = ($tail | Measure-Object -Minimum).Minimum
            $tailMax = ($tail | Measure-Object -Maximum).Maximum
            $fdTail = if ($fdSteady.Count -ge 3) { $fdSteady | Select-Object -Last ([Math]::Min(10, $fdSteady.Count)) } else { @() }
            $fdTailMin = if ($fdTail.Count) { ($fdTail | Measure-Object -Minimum).Minimum } else { -1 }
            $fdTailMax = if ($fdTail.Count) { ($fdTail | Measure-Object -Maximum).Maximum } else { -1 }
            Note ("endurance: warmup native heap {0:N1} -> {1:N1} MB over {2} samples (excluded from the verdict)" -f $heapMb[0], $heapMb[$warmup - 1], $warmup)
            Note ("endurance: steady native heap first={0:N1} last={1:N1} drift={2:N1} MB min={3:N1} max={4:N1} over {5} samples" -f $firstSteady, $last, ($last - $firstSteady), $min, $max, $steady.Count)
            Note ("endurance: tail native heap range={0:N1} MB over the last {1} samples ({2:N1} .. {3:N1})" -f ($tailMax - $tailMin), $tailN, $tailMin, $tailMax)
            Note ("endurance: steady fd min={0} max={1} range={2} over {3} samples" -f $fdMin, $fdMax, ($fdMax - $fdMin), $fdSteady.Count)
            Note ("endurance: tail fd range={0} over the last {1} samples ({2} .. {3})" -f ($fdTailMax - $fdTailMin), $fdTail.Count, $fdTailMin, $fdTailMax)
            Check "the native heap does not drift upward under continuous editing" ((($last - $firstSteady)) -lt 12)
            Check "the native heap plateaus in the last ten minutes" ((($tailMax - $tailMin)) -lt 6)
            # An unobserved quantity is a failure, not a pass: with fewer than three fd samples the
            # range is empty and "0 - 0 < 6" would have reported green on a measurement that never ran.
            Check "no file descriptors are leaked" (($fdSteady.Count -ge 3) -and ((($fdTailMax - $fdTailMin)) -lt 6))
        } else {
            Note "ENDURANCE INCONCLUSIVE: fewer than 8 steady samples after the warm-up"
            Check "the endurance lane collected enough samples to judge" ($steady.Count -ge 8)
        }
        Check "the process never restarted during the endurance run" ($restarts -eq 0)
        LogcatErrors "endurance"
        Shot "endurance-final"
    }

    "faults" {
        # Fault injection lane. Every item is a way the app can be interrupted that a user never plans
        # for, and every item now ends in a verdict.
        #
        # What it used to be: a sequence of faults and screenshots with no assertions at all, so the
        # lane could not fail. It printed "fault=kill: pid after kill = 28248 / restarted pid = 28248"
        # - the same pid, i.e. nothing was killed - and drew no conclusion from it. Screenshots are not
        # verdicts; a lane that cannot fail is decoration.
        $script:fails = 0
        if (-not (OpenWorkspace)) { Note "faults: could not reach the workspace"; exit 1 }
        & $adb logcat -c

        # 1. Process death while the app is on screen. `am kill` only reclaims CACHED processes, so it
        #    is not used here at all - the same reasoning as in killtest, where it was measured.
        $root = EnsureRoot
        $before = (& $adb shell pidof $pkg).Trim()
        & $adb shell "kill -9 $before" | Out-Null
        Start-Sleep -Seconds 3
        $after = (& $adb shell pidof $pkg).Trim()
        Note "fault=kill: pid before=$before after='$after' root=$root"
        Check "the process could actually be killed (root required)" ($after -eq "")
        & $adb shell am start -n $activity | Out-Null
        Start-Sleep -Seconds 6
        Check "the app comes back after a killed process" (((& $adb shell pidof $pkg).Trim()) -ne "")
        Check "the editor is usable again after the restart" (WaitText "Export" 30)

        # 2. Rotate with a sheet open: the sheet must survive the configuration change.
        TapText "Parameters" | Out-Null
        Start-Sleep -Seconds 2
        $rowsBefore = InfoRows
        & $adb shell settings put system accelerometer_rotation 0 | Out-Null
        & $adb shell settings put system user_rotation 1 | Out-Null
        Start-Sleep -Seconds 3
        Shot "fault-rotate-landscape"
        & $adb shell settings put system user_rotation 0 | Out-Null
        & $adb shell settings put system accelerometer_rotation 1 | Out-Null
        Start-Sleep -Seconds 3
        $rowsAfter = InfoRows
        Note "fault=rotate rows before='$rowsBefore' after='$rowsAfter'"
        # The sheet's own row labels are the evidence that it is still there; "the screen looks right"
        # would need an eye, and this needs none.
        Check "the parameter sheet survives a rotation" ($rowsAfter -match "File:|Download")
        Check "the app is still foreground after rotating" ((& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1).Line -match [regex]::Escape($pkg))

        # 3. Ten system backs in a row from the workspace.
        for ($i = 0; $i -lt 10; $i++) { & $adb shell input keyevent 4 | Out-Null; Start-Sleep -Milliseconds 250 }
        Start-Sleep -Seconds 1
        Shot "fault-back10"
        Note "fault=back10 screen: $(ProbeLine)"
        $onLanding = (HasText "Create new gear") -eq $true
        $inEditor = (HasText "Export") -eq $true
        # Two acceptable outcomes, one unacceptable: landing or editor, never a launcher because the app
        # died. Ten backs must leave the app alive and sane.
        Check "ten backs leave the app alive and on a real screen" ($onLanding -or $inEditor)

        # 4. Two launches in a row: the second must not leave the app unresumed.
        & $adb shell am start -n $activity | Out-Null
        & $adb shell am start -n $activity | Out-Null
        Start-Sleep -Seconds 4
        $res = (& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1)
        Note "fault=double-start: resumed=$($res.Line)"
        Check "the app is resumed after two launches" ($res.Line -match [regex]::Escape($pkg))

        # 5. Memory pressure: the app must survive a trim and still be there.
        & $adb shell am send-trim-memory $pkg RUNNING_LOW 2>&1 | ForEach-Object { Note "fault=trim-memory: $_" }
        Start-Sleep -Seconds 3
        Check "the app is still alive after RUNNING_LOW trim" (((& $adb shell pidof $pkg).Trim()) -ne "")
        LogcatErrors "faults"
        Note "faults: failures=$($script:fails)"
    }

    "races" {
        # Race and timing lane: the interactions a fast user produces that a scripted flow never does
        # - a tap landing in the middle of a drag, a sheet opened and closed within one frame, a
        # double tap, a cancel that arrives while a gesture is still in flight.
        #
        # The verdict is a FREQUENCY, not an anecdote. An anomaly in one run of twenty is a race and
        # is reported with its rate; an anomaly in every run is a defect in the flow. One attempt
        # would find neither, which is why the loop is twenty rounds and why the count is printed.
        #
        # Every injection is fired with coordinates, not with a label lookup: a uiautomator dump costs
        # one to two seconds, and a race that waits for a dump is over before the tap. The numbers are
        # the measured chip positions on the workspace (View 108,352 / Parameters 360,352, slider
        # y=1648) - the same pair the endurance lane uses. The tap is never the evidence; the OUTCOME
        # is, which is why every round ends by proving the process is the same one.
        $script:fails = 0
        $rounds = 20
        if (-not (OpenWorkspace)) { Note "races: could not reach the workspace"; exit 1 }
        & $adb logcat -c
        # Probed, not assumed: `input motionevent` exists on API 36 and does NOT exist on API 26
        # (measured 2026-09-22 - "Error: Unknown command: motionevent" on emulator-5556). A lane that
        # silently skipped the harshest injection on one API level would report a difference between
        # the levels that is really a difference in the test.
        $motionevent = ((& $adb shell input motionevent 2>&1 | Out-String) -notmatch "Unknown command")
        $pid0 = (& $adb shell pidof $pkg).Trim()
        Note "races start: pid=$pid0 rounds=$rounds motionevent=$motionevent"
        $executed = 0
        $restarts = 0
        $dead = 0
        $reopens = 0
        for ($i = 1; $i -le $rounds; $i++) {
            # (a) Two fingers, one frame: a slow drag along the slider while a tap lands on the chip
            #     that switches the sheet. This is the overlap a user produces by not lifting a finger.
            $drag = Start-Process -FilePath $adb -ArgumentList @("shell", "input", "swipe", "220", "1648", "780", "1648", "1200") -NoNewWindow -PassThru
            Start-Sleep -Milliseconds 250
            & $adb shell input tap 108 352 | Out-Null
            $drag.WaitForExit(4000) | Out-Null
            $executed++
            Start-Sleep -Milliseconds 120

            # (b) Open and close the parameter sheet with no wait in between.
            & $adb shell input tap 360 352 | Out-Null
            & $adb shell input tap 108 352 | Out-Null
            $executed++
            Start-Sleep -Milliseconds 120

            # (c) A double tap on the same chip, 80 ms apart.
            & $adb shell input tap 360 352 | Out-Null
            Start-Sleep -Milliseconds 80
            & $adb shell input tap 360 352 | Out-Null
            $executed++
            Start-Sleep -Milliseconds 250

            # (d) A pointer held down while a SECOND pointer goes down - the multi-touch the framework
            #     has to arbitrate. Only where the tool supports it (API 30+); the recovery UP is sent
            #     for both pointers so the injection cannot leave the device's input stream wedged.
            if ($motionevent -and ($i % 4 -eq 0)) {
                & $adb shell input motionevent DOWN 540 1650 | Out-Null
                & $adb shell input motionevent MOVE 760 1650 | Out-Null
                & $adb shell input motionevent DOWN 300 900 | Out-Null
                Start-Sleep -Milliseconds 150
                & $adb shell input motionevent UP 760 1650 | Out-Null
                & $adb shell input motionevent UP 300 900 | Out-Null
                $executed++
                Start-Sleep -Milliseconds 200
            }

            # (e) Every fifth round: cancel during the gesture. `input keyevent 4` while the swipe is
            #     still running, then straight back into the app.
            if ($i % 5 -eq 0) {
                $drag2 = Start-Process -FilePath $adb -ArgumentList @("shell", "input", "swipe", "220", "1648", "780", "1648", "1500") -NoNewWindow -PassThru
                Start-Sleep -Milliseconds 120
                & $adb shell input keyevent 4 | Out-Null
                $drag2.WaitForExit(4000) | Out-Null
                $executed++
                Start-Sleep -Milliseconds 400
                $cur = (& $adb shell pidof $pkg).Trim()
                if (-not $cur) { $dead++; ${reopens}++ ; Note "races round=${i}: the process was gone after the back cancel"; OpenWorkspace | Out-Null }
                else {
                    $resumed = (& $adb shell dumpsys activity activities | Select-String "ResumedActivity" | Select-Object -First 1)
                    if (-not ($resumed -and ($resumed.Line -match [regex]::Escape($pkg)))) {
                        # Back from the editor is allowed to leave the app; what is not allowed is the app
                        # being unable to come back. Counted, because the rate is the interesting number.
                        $reopens++
                        OpenWorkspace | Out-Null
                    }
                }
            }

            $cur = (& $adb shell pidof $pkg).Trim()
            if (-not $cur) {
                $dead++
                Note "races round=${i}: PROCESS GONE"
                OpenWorkspace | Out-Null
            } elseif ($cur -ne $pid0) {
                $restarts++
                Note "races round=${i}: PROCESS RESTARTED ($pid0 -> $cur)"
                $pid0 = $cur
            }
            if ($i % 10 -eq 0) { Shot "races-$i" }
        }
        Note "races done: rounds=$rounds executed=$executed restarts=$restarts dead=$dead reopens=$reopens"

        # The verdicts. A surviving process is the headline; a working editor afterwards is the proof
        # that the race did not leave the app in a state that merely looks alive.
        Check "no rapid sequence killed the app process" (($restarts -eq 0) -and ($dead -eq 0))
        Check "the lane fired the intended sequences" ($executed -ge 15)
        $lc = LogcatErrors "races"
        Check "no crash appeared in the log during the storm" ($lc -eq 0)
        $before = InfoRows
        & $adb shell input tap 108 352 | Out-Null
        Start-Sleep -Seconds 2
        $after = InfoRows
        Note "races: sheet rows before='$before' after='$after'"
        Check "the editor still answers a normal tap after the storm" (($after -match "File:|Download") -or ($after -match "Download"))
        Check "the app is still on a real screen after the storm" ((HasText "Export") -eq $true)
        Note "races: failures=$($script:fails)"
    }

    "edgeui" {
        # Edge cases that only exist in the UI: the largest font scale the system offers, the dark
        # theme, and a unit change that re-declares every length in the settings panel.
        #
        # The numeric boundaries (every ParamDef at its limits and beyond) are covered where they can
        # be covered deterministically - in core, by ParameterDefBoundaryTest, which sweeps the
        # registry itself. This lane covers what a unit test cannot: what the screen does with the
        # resulting numbers.
        $script:fails = 0
        & $adb logcat -c

        # (1) font_scale 2.0. The scale the system offers is 0.85..2.0; the HUD regression was found
        #     at 1.5, so the honest edge is 2.0.
        & $adb shell settings put system font_scale 2.0 | Out-Null
        $ok = OpenWorkspace
        Check "the editor is reachable at font scale 2.0" ($ok -eq $true)
        $overflow = HudLabelsOutsideScreen
        Note "edgeui: measurement labels outside the screen at 2.0 = $overflow"
        Check "no measurement label runs off the screen at font scale 2.0" ($overflow -eq 0)
        $opened = TapTextOnScreen "Export"
        Start-Sleep -Seconds 3
        $rows = InfoRows
        Note "edgeui: export sheet at 2.0 rows='$rows'"
        Check "the export sheet still opens at font scale 2.0" (($opened -eq $true) -and ($rows -match "Download|File:"))
        & $adb shell input tap 108 352 | Out-Null
        & $adb shell settings put system font_scale 1.0 | Out-Null
        Start-Sleep -Seconds 2

        # (2) Dark theme, set through the platform and verified after the fact rather than assumed to
        #     have taken effect. `cmd uimode` arrived in API 29; API 26 has only the secure setting,
        #     and a lane that writes the setting and does not read it back cannot tell a dark screen
        #     from a setting that was ignored.
        $modeCmd = (& $adb shell cmd uimode night yes 2>&1 | Out-String)
        if ($modeCmd -match "Unknown|unknown|not found|Exception") {
            Note "edgeui: 'cmd uimode' unavailable, falling back to the secure setting"
            & $adb shell settings put secure ui_night_mode 2 | Out-Null
        }
        Start-Sleep -Seconds 2
        $night = ((& $adb shell "settings get secure ui_night_mode" 2>&1 | Out-String).Trim())
        $uiMode = (& $adb shell dumpsys uimode 2>&1 | Select-String "mNightMode" | Select-Object -First 1)
        Note "edgeui: ui_night_mode='$night' dumpsys='$($uiMode.Line)'"
        $dark = ($night -eq "2") -or ($uiMode -and $uiMode.Line -match "yes|2")
        Check "the device is actually in dark mode for this check" ($dark -eq $true)
        $okDark = OpenWorkspace
        Check "the editor is reachable in dark mode" ($okDark -eq $true)
        # A real export in dark mode, verified as a file: the theme must not change what is written.
        $file = ExportFormat "STL"
        Note "edgeui: dark-mode export returned '$file'"
        Check "an export still produces a file in dark mode" ([bool]$file)
        if ($file) { PullAndVerify $file "STL" }
        & $adb shell cmd uimode night no 2>&1 | Out-Null
        & $adb shell settings put secure ui_night_mode 1 | Out-Null
        Start-Sleep -Seconds 2

        # (3) The unit change re-declares the same parameter in diametral pitch. Switching must not
        #     crash, and switching back must not leave the model holding an unconverted value.
        $ok2 = OpenWorkspace
        Check "the editor is reachable before the unit change" ($ok2 -eq $true)
        TapTextOnScreen "Parameters" | Out-Null
        Start-Sleep -Seconds 2
        $inch = TapScrolling "inch (diametral pitch)"
        Start-Sleep -Seconds 2
        $valInch = ModuleFieldText
        $back = $false
        if ($inch) {
            $back = TapScrolling "mm (module)"
            Start-Sleep -Seconds 2
        }
        $valMm = ModuleFieldText
        Note "edgetui: unit switch inch=$inch back=$back moduleText inch='$valInch' mm='$valMm'"
        Check "the unit choice is reachable in the settings panel" ($inch -eq $true)
        Check "switching the unit back leaves a usable module field" ($back -eq $true)
        Check "the module field is not left empty after a unit round trip" ([bool]$valMm)
        $lc = LogcatErrors "edgeui"
        Check "no crash appeared in the log during the edge cases" ($lc -eq 0)
        Note "edgeui: failures=$($script:fails)"
    }

    "exporttiming" {
        # F-2 GUARD. The export sheet's info rows are computed off the main thread, so their
        # values arrive a few seconds after the sheet opens. The rows must still be COMPOSED
        # from the first frame, otherwise the sheet grows when the values land and every format
        # chip moves under the user's finger. Measured before the fix: the DXF chip moved
        # 1463 -> 1257 px (206 px) about 3-4 s after opening, and the row labels were absent
        # at t=2 s but present at t=6 s in the same probe run.
        #
        # This action exits 1 when the layout is unstable, so it can be used as a gate.
        #
        # Independent review found three weaknesses in the first version of this guard, all
        # fixed here: it matched the ENGLISH labels only (on Swedish the guard reported a
        # layout bug that did not exist), it required the y coordinate to be EXACTLY equal (a
        # 1 px entrance-animation shift would fail it), and it checked the rows on the first
        # frame only (a late dump could let the old code pass). The timestamp cross-check at
        # the end closes that last hole by refusing to report PASS on evidence that cannot
        # discriminate - it reports INCONCLUSIVE instead.
        OpenWorkspace
        & $adb logcat -c
        TapText "Export" | Out-Null
        $t0 = Get-Date
        $fail = ""
        $sawSheet = $false
        $firstY = -1
        $firstSampleMs = -1
        for ($i = 0; $i -lt 16; $i++) {
            $xml = UiDump
            $m = [regex]::Match($xml, 'text="DXF"[^>]*?bounds="\[\d+,(\d+)\]')
            if ($m.Success) {
                $y = [int]$m.Groups[1].Value
                # Bilingual: I18n has "Triangles"/"Size" in en and "Trianglar"/"Storlek" in sv.
                # The row's TEXT, not just its presence: an empty value on an early sample and a
                # filled value later proves the row was composed before the async value landed.
                $tv = [regex]::Match($xml, 'text="(Triangles|Trianglar): ?([^"]*)"')
                $trisText = if ($tv.Success) { $tv.Groups[2].Value } else { "<row absent>" }
                $hasSize = $xml -match 'text="(Size|Storlek):'
                $ms = [int]((Get-Date) - $t0).TotalMilliseconds
                Note "t=${ms}ms dxfY=$y triangles='$trisText' sizeRow=$hasSize"
                if (-not $sawSheet) {
                    $sawSheet = $true
                    $firstY = $y
                    $firstSampleMs = $ms
                }
                # Checked on EVERY sample, not only the first: a row that exists and then
                # disappears is just as broken as one that never existed.
                if ($tv.Success -eq $false) {
                    if (-not $fail) { $fail = "the Triangles row is missing at t=${ms}ms - the sheet will grow and the chips will move" }
                }
                if (-not $hasSize) {
                    if (-not $fail) { $fail = "the Size row is missing at t=${ms}ms - the sheet will grow and the chips will move" }
                }
                # 2 px tolerance: the dialog's entrance animation can settle a pixel, and a guard
                # that cries wolf gets ignored.
                if ($firstY -ge 0 -and [math]::Abs($y - $firstY) -gt 2) {
                    if (-not $fail) { $fail = "DXF chip moved from y=$firstY to y=$y while the sheet was open" }
                }
            }
            Start-Sleep -Milliseconds 300
        }
        $log = & $adb logcat -d -s GF_EXPORT:D
        foreach ($l in $log) { Note "  applog: $l" }
        Shot "exporttiming"
        if (-not $sawSheet) {
            Note "F-2 GUARD FAIL: never saw the export sheet"
            exit 1
        }
        if ($fail) {
            Note "F-2 GUARD FAIL: $fail"
            exit 1
        }
        # Can this run discriminate at all? The app logs how long the preview took. If the value
        # had already landed before the first sample was taken, the old code would have passed
        # too - so the run proves nothing and must not be reported as PASS.
        $readyMs = -1
        foreach ($l in $log) {
            $r = [regex]::Match([string]$l, "total=(\d+)ms")
            if ($r.Success) { $readyMs = [int]$r.Groups[1].Value }
        }
        if ($readyMs -ge 0 -and $firstSampleMs -ge 0 -and $readyMs -lt $firstSampleMs) {
            Note "F-2 GUARD INCONCLUSIVE: the preview landed at ${readyMs}ms but the first usable sample was at ${firstSampleMs}ms - this run cannot tell the fixed code from the broken one"
            exit 2
        }
        Note "F-2 GUARD PASS: rows present in every sample, DXF chip stayed at y=$firstY, preview landed at ${readyMs}ms after the first sample (${firstSampleMs}ms)"
    }

    "infoprobe" {
        # Does the export sheet show the same information rows on first open as after an
        # explicit format tap? Observed once by accident (Triangles/Size present only in the
        # second case). This probe makes it measurable instead of anecdotal.
        TapText "Cancel" | Out-Null
        Start-Sleep -Seconds 2
        TapText "Export" | Out-Null
        Start-Sleep -Seconds 2
        Note "probe open+2s rows: $(InfoRows)"
        Start-Sleep -Seconds 4
        Note "probe open+6s rows: $(InfoRows)"
        TapText "STL" | Out-Null
        Start-Sleep -Seconds 2
        Note "probe after tap STL rows: $(InfoRows)"
        TapText "STEP" | Out-Null
        Start-Sleep -Seconds 2
        Note "probe after tap STEP rows: $(InfoRows)"
        Shot "infoprobe"
    }

    "workspace" { OpenWorkspace; Shot "workspace" }

    "restartcycle" {
        # Repeated start/stop cycles: the cold-start and lifecycle lane.
        KeepAwake
        & $adb logcat -c
        for ($i = 1; $i -le 15; $i++) {
            & $adb shell am force-stop $pkg | Out-Null
            Start-Sleep -Milliseconds 700
            $t0 = Get-Date
            & $adb shell am start -W -n $activity | Out-Null
            $ms = [int]((Get-Date) - $t0).TotalMilliseconds
            $mem = (& $adb shell dumpsys meminfo $pkg | Select-String "TOTAL PSS") -replace "\s+", " "
            Note "cycle=$i start_ms=$ms mem=$mem"
            Start-Sleep -Milliseconds 900
        }
        Shot "restartcycle-final"
        LogcatErrors "restartcycle"
    }

    "memleak" {
        # Cache or leak? The memory lane showed the native heap climbing +3.4 MB over ten switches
        # (30.7 -> 34.2 MB), which is either a leak or the per-type preview cache filling up. The
        # difference is not visible in one pass - it needs a SECOND pass over the same types:
        # a cache plateaus once every type has been seen, a leak keeps climbing.
        #
        # Types are switched through the editor's own type menu (tap the label, tap the type), not
        # through the wizard, so what is measured is the editor's churn and nothing else.
        # '$script:fails' is not the verdict here; the verdict is the per-pass numbers, which are
        # printed as all three passes side by side.
        $script:leakTypes = @(
            "Spur gear", "Helical gear", "Bevel gear", "Rack & pinion", "Planetary gear",
            "Worm gear pair", "Internal ring gear", "Hypoid gear", "Cycloidal gear",
            "Harmonic drive", "Face gear", "Screw gear", "Compound gear", "Timing belt"
        )
        if (-not (OpenWorkspace)) { Note "memleak: could not reach the workspace"; exit 1 }

        # Two alternatives were tried and rejected before this one:
        #  (a) look every switch up by text - correct, but 5 uiautomator dumps per switch at ~4 s
        #      each made one pass take ten minutes;
        #  (b) skip the extra passes and infer - that is guessing, and the whole point is to tell a
        #      cache from a leak.
        # Chosen: the menu is the same 14 entries in the same order every time it opens, so reading
        # its geometry ONCE and then addressing items by position removes the per-switch lookups
        # entirely. It is still the real UI path - the real menu, the real items - just measured
        # instead of re-read. A dropdown that failed to open is detected before any of this, so a
        # stale map cannot silently switch nothing.
        function TypeMenuOpen {
            $count = 0
            foreach ($candidate in $script:leakTypes) {
                if ((HasText $candidate) -eq $true) { $count++ }
                if ($count -gt 1) { return $true }
            }
            return $false
        }

        function CurrentTypeLabel {
            foreach ($candidate in $script:leakTypes) {
                if ((HasText $candidate) -eq $true) { return $candidate }
            }
            return $null
        }

        # Opens the menu (if needed) and reads where each item sits. Every label appears twice with
        # the menu open - once as the trigger button and once as an item - and the item is always the
        # lower of the two, which is what makes "take the greatest y" the right rule rather than a
        # coincidence of this screen size.
        function MapTypeMenu {
            $map = @{}
            if (TypeMenuOpen) { SysBack; Start-Sleep -Milliseconds 600 }
            $button = CurrentTypeLabel
            if (-not $button) { Note "memleak: no type button found - cannot map the menu"; return $null }
            if (-not (TapText $button)) { Note "memleak: could not open the type menu"; return $null }
            Start-Sleep -Milliseconds 900
            $xml = UiDump
            if (-not $xml -or -not $script:dumpOk) { Note "memleak: no readable dump of the open menu"; return $null }
            foreach ($t in $script:leakTypes) {
                $best = -1; $bx = -1; $smallest = 100000; $sx = -1
                foreach ($m in [regex]::Matches($xml, 'text="' + [regex]::Escape((XmlEscape $t)) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
                    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
                    $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
                    if ($cy -gt $best) { $best = $cy; $bx = $cx }
                    if ($cy -lt $smallest) { $smallest = $cy; $sx = $cx }
                }
                if ($best -gt 0) { $map[$t] = @($bx, $best) }
                # The button is the HIGHER of the pair: the menu opens below its trigger. Kept, because
                # a switch is button-then-item and the button's position is what makes that possible
                # without a lookup per switch.
                if ($sx -gt 0 -and $smallest -lt $best) { $map["__button__"] = @($sx, $smallest) }
            }
            Note "memleak: menu mapped, $($map.Count - 1) of $($script:leakTypes.Count) items located"
            return $map
        }

        $map = MapTypeMenu
        # The menu is scrollable, so the last entry can be laid out below the window and never appear
        # in the dump. Requiring all 14 would make this lane fail on screens where the menu is one row
        # shorter - a property of the window, not of the app. A missing item falls back to the text
        # lookup in SetGearTypeFast, so coverage stays complete either way.
        $missing = @($script:leakTypes | Where-Object { -not $map.ContainsKey($_) })
        if (-not $map -or -not $map["__button__"] -or ($script:leakTypes.Count - $missing.Count) -lt 10) {
            # Degrades instead of blocking: this lane explains a number the memory lane already
            # reports, so a screen where the menu geometry cannot be read must not stop a chain.
            Note "memleak: menu geometry not readable on this screen - INCONCLUSIVE, use -Action memory"
            break
        }
        if ($missing.Count -gt 0) { Note ("memleak: not laid out in the menu, will be addressed by text: " + ($missing -join ", ")) }
        # Close the menu again: switching is button-then-item, and leaving it open would make the
        # first tap land on an item.
        SysBack
        Start-Sleep -Milliseconds 600

        # Two raw taps for a mapped item, no lookups. The lookups were the whole cost of this action -
        # four uiautomator dumps per switch at roughly four seconds each - and they measured nothing
        # about the app's memory, which is what this lane is for. The trade is that a tap which misses
        # is not noticed immediately, so the current type is verified independently every fifth switch
        # and at the end of every pass: a drift is reported, never silently absorbed.
        $script:switches = 0
        $script:drift = 0
        function SetGearTypeFast([string]$target, [string]$expectedCurrent) {
            if ($expectedCurrent -eq $target) { return }
            $btn = $map["__button__"]
            & $adb shell input tap $btn[0] $btn[1] | Out-Null
            Start-Sleep -Milliseconds 450
            if ($map.ContainsKey($target)) {
                $xy = $map[$target]
                & $adb shell input tap $xy[0] $xy[1] | Out-Null
            } else {
                if (-not (TapText $target)) { Note "memleak: '$target' could not be reached in the open menu" }
            }
            Start-Sleep -Milliseconds 850
            $script:switches++
            # Independent verification, not "the tap was issued so it worked".
            if ($script:switches % 5 -eq 0) {
                $actual = CurrentTypeLabel
                if ($actual -and $actual -ne $target) {
                    Note "memleak: DRIFT - expected '$target' but the editor shows '$actual'"
                    $script:drift++
                }
            }
        }

        function SampleHeap([string]$tag) {
            $p = (& $adb shell pidof $pkg).Trim()
            $fd = if ($p) { (& $adb shell "ls /proc/$p/fd | wc -l" | Out-String).Trim() } else { "n/a" }
            $native = ((& $adb shell dumpsys meminfo $pkg | Select-String "Native Heap" | Select-Object -First 1) -replace "\s+", " ").Trim()
            $pss = ((& $adb shell dumpsys meminfo $pkg | Select-String "TOTAL PSS" | Select-Object -First 1) -replace "\s+", " ").Trim()
            Note "memleak $tag fd=$fd $native $pss"
        }

        # A warm pass first, so pass 1 is not measuring first-touch costs such as font loading and
        # the GL context coming up.
        Note "memleak: warming up over the first three types"
        $current = ($script:leakTypes | Where-Object { $_ -eq (CurrentTypeLabel) })
        if (-not $current) { $current = $script:leakTypes[0] }
        foreach ($t in $script:leakTypes[0..2]) { SetGearTypeFast $t $current; $current = $t }
        SampleHeap "warm"

        for ($pass = 1; $pass -le 3; $pass++) {
            foreach ($t in $script:leakTypes) { SetGearTypeFast $t $current; $current = $t }
            SampleHeap ("pass" + $pass + " switches=" + $script:switches + " drift=" + $script:drift)
        }

        $final = CurrentTypeLabel
        Note "memleak: editor shows '$final' after $($script:switches) switches (drift=$($script:drift))"
        Note "memleak: the verdict is the pass lines - a cache plateaus after pass 1, a leak keeps climbing"
        LogcatErrors "memleak"
    }

    "memory" {
        # Idle growth is the leak signature; growth while the app is WORKING is not.
        #
        # The first version of this lane sampled ten times straight after navigation and showed the
        # native heap climbing 30.7 -> 34.2 MB, which reads as a leak and is not one: the editor
        # starts GearPreviewRenderer.warmCache() when it opens and renders the preview meshes in the
        # background (the repo notes 28 of them, seconds each on a software-GL emulator). Sampling
        # during that is sampling the cache filling up.
        #
        # So: settle first, then measure. Settling is decided by the numbers, not by a guessed sleep -
        # the lane waits until two consecutive samples agree to within half a megabyte, and says so if
        # it never settles, because a heap that never settles under no load is the finding.
        if (-not (OpenWorkspace)) { Note "memory: could not reach the workspace"; exit 1 }
        $script:fails = 0

        function NativeHeapMb {
            # The line WITH the colon, deliberately. dumpsys meminfo prints "Native Heap" twice: a
            # table row without a colon and a detail row with one, and Select-String returns both -
            # so "first line containing Native Heap" picked the table row and the regex for
            # "Native Heap:" never matched. Three iterations of this lane went into that one word.
            $line = (& $adb shell dumpsys meminfo $pkg | Select-String "Native Heap:" | Select-Object -First 1) -replace "\s+", " "
            $m = [regex]::Match([string]$line, "Native Heap:\s+(\d+)")
            if ($m.Success) { return [double]$m.Groups[1].Value / 1024.0 }
            return -1
        }

        Note "memory: waiting for the preview cache to settle"
        $prev = -2
        $settled = $false
        for ($i = 1; $i -le 30; $i++) {
            $now = NativeHeapMb
            if ($now -lt 0) { Note "memory: could not read the native heap"; exit 1 }
            if ($prev -gt 0 -and [math]::Abs($now - $prev) -lt 0.5) {
                Note ("memory: settled after {0} samples at {1:N1} MB" -f $i, $now)
                $settled = $true
                break
            }
            $prev = $now
            Start-Sleep -Seconds 2
        }
        if (-not $settled) { Note "memory: NEVER SETTLED under no load - that is the finding, not a measurement" }

        # Ten samples with the app doing nothing. Any upward trend here is a leak; a flat line is the
        # plateau the acceptance criterion asks for.
        $samples = @()
        for ($i = 1; $i -le 10; $i++) {
            $mb = NativeHeapMb
            $p = (& $adb shell pidof $pkg).Trim()
            $fd = if ($p) { (& $adb shell "ls /proc/$p/fd | wc -l" | Out-String).Trim() } else { "n/a" }
            $samples += $mb
            Note ("memory: idle sample {0} native_heap={1:N1} MB fd={2}" -f $i, $mb, $fd)
            Start-Sleep -Seconds 2
        }
        $first = $samples[0]
        $last = $samples[$samples.Count - 1]
        $min = ($samples | Measure-Object -Minimum).Minimum
        $max = ($samples | Measure-Object -Maximum).Maximum
        Note ("memory: idle native heap min={0:N1} max={1:N1} first={2:N1} last={3:N1} delta={4:N1} MB" -f $min, $max, $first, $last, ($last - $first))
        # Half a megabyte across twenty seconds of doing nothing is noise on this emulator; a real
        # per-second leak would be several times that.
        Check "the app does not grow while idle (no leak)" (($last - $first) -lt 0.5)
        LogcatErrors "memory"
        Note "memory: failures=$($script:fails)"
    }
}
} finally {
    Remove-Item $lock -Force -ErrorAction SilentlyContinue
}
