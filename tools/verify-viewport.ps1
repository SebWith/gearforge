# Verification and stress pass for the GearForge viewport work.
#
# ASCII only, on purpose: PowerShell 5.1 reads a BOM-less .ps1 as CP1252, and a stray high byte in
# a string literal has already cost this project a debugging session.
#
# Run one action at a time as a VS Code task, for example
#   powershell -ExecutionPolicy Bypass -File tools\verify-viewport.ps1 -Action verify
param(
    [string]$Action = "shot",
    [string]$Repo = "$PSScriptRoot\..",
    [int]$X = 0,
    [int]$Y = 0
)

$ErrorActionPreference = "Continue"
$adb = "C:\Android\sdk\platform-tools\adb.exe"
$apk = "$Repo\android\build\outputs\apk\debug\android-debug.apk"
$pkg = "com.gearforge.geargenerator"
$activity = "com.gearforge.geargenerator/com.gearforge.app.MainActivity"

function Shot([string]$name) {
    & $adb shell screencap -p "/sdcard/$name.png" | Out-Null
    & $adb pull "/sdcard/$name.png" "$Repo\build\$name.png" | Out-Null
    Write-Host "shot=$name"
}

function Tap([int]$x, [int]$y) { & $adb shell input tap $x $y | Out-Null }

function Swipe([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms) {
    & $adb shell input swipe $x1 $y1 $x2 $y2 $ms | Out-Null
}

function BuildAndInstall {
    & "$Repo\gradlew.bat" :android:assembleDebug --console=plain *> "$Repo\build\v-build.txt"
    $built = (Get-Content "$Repo\build\v-build.txt" -Encoding Unicode | Select-String -Pattern "BUILD SUCCESSFUL").Count
    Write-Host "build_successful=$built"
    & $adb install -r $apk
    & $adb shell am force-stop $pkg
    & $adb shell am start -n $activity | Out-Null
    Start-Sleep -Seconds 5
}

function OpenWorkspace {
    # Coordinates read off build\s0-start.png, not guessed. On this landing layout:
    #   "Create new gear" y 1660..1830 (centre 1745), "Saved files" y 1870..2005.
    # Tapping 1825 aimed at the gap and hit "Saved files", which silently sent every later tap
    # somewhere useless - the screenshots then had identical file sizes and looked like "nothing
    # happened". Measure the layout before chaining taps.
    Tap 540 1745
    Start-Sleep -Seconds 3
    Tap 285 725
    Start-Sleep -Seconds 3
    Tap 540 970
    Start-Sleep -Seconds 7
}

switch ($Action) {
    "shot" { Shot "x-shot" }

    "bootwait" {
        # Blocks until the device answers and the system has finished booting. Waiting inside the
        # script is the only reliable way: a poll from the outside guesses at the timing.
        for ($i = 0; $i -lt 60; $i++) {
            $state = (& $adb get-state 2>$null)
            if ($state -match "device") {
                $boot = (& $adb shell getprop sys.boot_completed 2>$null)
                if ($boot -match "1") {
                    Write-Host "BOOTED after $i checks"
                    & $adb shell wm size
                    return
                }
            }
            Start-Sleep -Seconds 5
        }
        Write-Host "NOT_BOOTED"
    }

    "verify" {
        # The whole pass: build, install, reach the workspace, then push on it hard.
        BuildAndInstall
        OpenWorkspace
        Shot "s0-start"

        # 1. Playback: four taps in under a second. The state must end where it started (off).
        for ($i = 0; $i -lt 4; $i++) { Tap 986 2028; Start-Sleep -Milliseconds 250 }
        Start-Sleep -Milliseconds 500
        Shot "s1-playback-toggle"

        # 2. Orbit: three drags starting on the mesh, then a double tap (A6) to frame it again.
        for ($i = 0; $i -lt 3; $i++) {
            Swipe 500 1250 880 1000 250
            Start-Sleep -Milliseconds 150
        }
        Shot "s2-orbited"
        Tap 560 1350
        Start-Sleep -Milliseconds 120
        Tap 560 1350
        Start-Sleep -Seconds 1
        Shot "s3-double-tap-framed"

        # 3. Pick teeth at four different places, quickly: the chip index has to follow every time.
        foreach ($p in @(@(620, 1400), @(560, 1470), @(500, 1400), @(560, 1330))) {
            Tap $p[0] $p[1]
            Start-Sleep -Milliseconds 350
        }
        Shot "s4-last-tooth"

        # 4. Open the panel from the tooth chip. This is the path the A3 fix has to survive, and the
        #    Module row is where the doubled unit appeared.
        Tap 272 1995
        Start-Sleep -Seconds 6
        Shot "s5-panel-top"

        # 5. Collapse and expand a header repeatedly, then scroll to the bottom where the printer
        #    profile lives (B4).
        Tap 540 1240
        Start-Sleep -Milliseconds 400
        Tap 540 1240
        Start-Sleep -Milliseconds 400
        for ($i = 0; $i -lt 5; $i++) {
            Swipe 540 2000 540 900 200
            Start-Sleep -Milliseconds 200
        }
        Shot "s6-panel-bottom"

        # 6. Mid-drag capture (C1): the value bubble and the scrub strip exist only while the finger
        #    is down, so the device shell runs the swipe in the background and captures during it.
        & $adb shell "input swipe 300 1500 700 1500 3000 & sleep 1.2; screencap -p /sdcard/s7-drag.png"
        & $adb pull "/sdcard/s7-drag.png" "$Repo\build\s7-drag.png" | Out-Null
        Write-Host "shot=s7-drag"
        Start-Sleep -Seconds 3

        # 7. Close and reopen the panel five times as fast as the taps allow: repeated transitions
        #    are where a sheet ends up half-shown.
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1
        for ($i = 0; $i -lt 5; $i++) {
            Tap 272 1995
            Start-Sleep -Milliseconds 300
            & $adb shell input keyevent 4 | Out-Null
            Start-Sleep -Milliseconds 300
        }
        Tap 272 1995
        Start-Sleep -Seconds 5
        Shot "s8-reopened"

        # 8. The overflow menu on the viewport, which now also carries the unit switch (B5).
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1
        Tap 1030 152
        Start-Sleep -Seconds 2
        Shot "s9-menu"
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1

        # 9. Landscape. Nothing here is meant to break when the device turns.
        & $adb shell settings put system accelerometer_rotation 0 | Out-Null
        & $adb shell settings put system user_rotation 1 | Out-Null
        Start-Sleep -Seconds 3
        Shot "s11-landscape"
        & $adb shell settings put system user_rotation 0 | Out-Null
        & $adb shell settings put system accelerometer_rotation 1 | Out-Null
        Start-Sleep -Seconds 2

        # 10. Everything the app itself logged as a failure, plus the crash and ANR markers.
        & $adb shell "logcat -d -t 800" |
            Select-String -Pattern "FATAL|AndroidRuntime|ANR in|geargenerator.*Exception|StrictMode" |
            Select-Object -Last 25 | ForEach-Object { $_.Line }
        Write-Host "verify_done"
    }

    "share" {
        # The share path on its own, so a failure can be told apart from the rest of the pass.
        Tap 360 322
        Start-Sleep -Seconds 3
        Shot "s10a-export-dialog"
    }

    "c1" {
        # Mid-drag on a slider. The panel is opened from the mode chip rather than the tooth chip:
        # the chip row sits at a fixed height whatever the camera is doing, while the tooth chip only
        # exists after a pick and moves with the model. From p9/s5 the "Module" track is at y 1648
        # when the panel opens at the top. The value bubble exists only while the finger is down, so
        # the device shell runs the swipe in the background and captures during it.
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1
        Tap 360 322
        Start-Sleep -Seconds 6
        Shot "c1a-before"
        & $adb shell "input swipe 220 1648 780 1648 3500 & sleep 1.4; screencap -p /sdcard/c1b-drag.png"
        & $adb pull "/sdcard/c1b-drag.png" "$Repo\build\c1b-drag.png" | Out-Null
        Write-Host "shot=c1b-drag"
        Start-Sleep -Seconds 3
        Shot "c1c-after"
    }

    "suite" {
        & "$Repo\gradlew.bat" :core:test --rerun --console=plain *> "$Repo\build\core-suite.txt"
        $exit = $LASTEXITCODE
        $lines = Get-Content "$Repo\build\core-suite.txt" -Encoding Unicode
        $lines | Select-String -Pattern " FAILED$" | Select-Object -Last 25 | ForEach-Object { $_.Line }
        $passed = ($lines | Select-String -Pattern " PASSED$").Count
        Write-Host "SUITE exit=$exit passed=$passed"
    }

    "compile" {
        & "$Repo\gradlew.bat" :android:compileDebugKotlin :core:test --tests "*AssemblyBodyKeysTest*" --tests "*GearPaletteTest*" :core:test --console=plain *> "$Repo\build\v-compile.txt"
        $exit = $LASTEXITCODE
        $lines = Get-Content "$Repo\build\v-compile.txt" -Encoding Unicode
        $lines | Select-String -Pattern "^e: | FAILED$|error:" | Select-Object -Last 30 | ForEach-Object { $_.Line }
        $passed = ($lines | Select-String -Pattern " PASSED$").Count
        Write-Host "COMPILE exit=$exit passed=$passed"
    }

    "verifyall" {
        # One pass over everything that is still unverified, from a known state: force-stop, start,
        # navigate by measured coordinates, and check the screen actually changed before trusting the
        # next tap. Blind chaining is what sent three earlier passes to the wrong screen.
        BuildAndInstall

        # B4: the printer profile lives in Settings, which the landing screen opens directly.
        Tap 290 2115
        Start-Sleep -Seconds 5
        Shot "v-b4-settings"
        for ($i = 0; $i -lt 3; $i++) {
            Swipe 540 1700 540 900 250
            Start-Sleep -Milliseconds 250
        }
        Shot "v-b4-scrolled"
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1

        OpenWorkspace
        $nav = (Get-Item "$Repo\build\v-nav.png" -ErrorAction SilentlyContinue).Length
        Shot "v-nav"
        $nav = (Get-Item "$Repo\build\v-nav.png").Length
        Write-Host "nav_size=$nav"
        if ($nav -lt 1500000 -or $nav -gt 2000000) { Write-Host "NAV_UNEXPECTED"; return }

        # B6: the three coach marks, stepped through and then dismissed.
        Shot "v-b6-first"
        Tap 700 300
        Start-Sleep -Milliseconds 500
        Tap 700 300
        Start-Sleep -Milliseconds 500
        Shot "v-b6-third"
        Tap 700 300
        Start-Sleep -Milliseconds 500
        Shot "v-b6-gone"

        # C1: the panel and a capture taken while a slider is being dragged.
        Tap 360 322
        Start-Sleep -Seconds 6
        Shot "v-c1-panel"
        & $adb shell "input swipe 220 1648 780 1648 3500 & sleep 1.4; screencap -p /sdcard/v-c1-drag.png"
        & $adb pull "/sdcard/v-c1-drag.png" "$Repo\build\v-c1-drag.png" | Out-Null
        Write-Host "shot=v-c1-drag"
        Start-Sleep -Seconds 3
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1

        # B3: export, then share. The chooser appearing at all proves the FileProvider uri was
        # accepted; a missing root would throw instead, and the logcat sweep at the end would show it.
        Tap 600 322
        Start-Sleep -Seconds 4
        Shot "v-b3-dialog"
        Tap 640 1860
        Start-Sleep -Seconds 4
        Shot "v-b3-chooser"
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 1

        # Landscape and back.
        & $adb shell settings put system accelerometer_rotation 0 | Out-Null
        & $adb shell settings put system user_rotation 1 | Out-Null
        Start-Sleep -Seconds 3
        Shot "v-landscape"
        & $adb shell settings put system user_rotation 0 | Out-Null
        & $adb shell settings put system accelerometer_rotation 1 | Out-Null
        Start-Sleep -Seconds 2

        & $adb shell "logcat -d -t 900" |
            Select-String -Pattern "FATAL|AndroidRuntime|ANR in|geargenerator.*Exception|FileProvider|IllegalArgumentException" |
            Select-Object -Last 25 | ForEach-Object { $_.Line }
        Write-Host "verifyall_done"
    }

    "planetary" {
        # D4: a multi-body assembly, so the legend and the per-body colours have something to name.
        # The type dropdown is opened first and captured, because its row positions depend on the
        # number of items above the target.
        Tap 250 152
        Start-Sleep -Seconds 2
        Shot "v-types"
    }

    "planetaryPick" {
        Tap 250 640
        Start-Sleep -Seconds 8
        Shot "v-legend"
    }

    "finalverify" {
        # The whole remaining list in one pass, with a checked transition after every stage:
        # B6 coach marks, B3 share (with the ClipData fix), C1 mid-drag, and landscape.
        BuildAndInstall
        OpenWorkspace
        Shot "f-nav"
        $nav = (Get-Item "$Repo\build\f-nav.png").Length
        Write-Host "nav_size=$nav"
        if ($nav -lt 1500000 -or $nav -gt 2000000) { Write-Host "NAV_UNEXPECTED"; return }

        # B6: the marks are in the top strip; Next and the dismiss cross are at its right end.
        Shot "f-b6-1"
        Tap 905 403
        Start-Sleep -Milliseconds 600
        Shot "f-b6-2"
        Tap 905 403
        Start-Sleep -Milliseconds 600
        Shot "f-b6-3"
        Tap 1000 403
        Start-Sleep -Milliseconds 600
        Shot "f-b6-dismissed"

        # B3: export, share, and the log that says whether the grant crossed the process boundary.
        Tap 600 322
        Start-Sleep -Seconds 4
        Tap 287 1837
        Start-Sleep -Seconds 5
        Shot "f-share"
        & $adb shell "logcat -d -t 500" |
            Select-String -Pattern "Permission Denial|ChooserPreview|FATAL|AndroidRuntime|ResolverActivity" |
            Select-Object -Last 12 | ForEach-Object { $_.Line }
        # Leave the sharesheet without pressing Back into the landing screen.
        & $adb shell input keyevent 4 | Out-Null
        Start-Sleep -Seconds 2

        # C1: mid-drag on the first slider of the panel, opened from the mode chip.
        Tap 360 322
        Start-Sleep -Seconds 6
        Shot "f-panel"
        & $adb shell "input swipe 240 1650 760 1650 4000 & sleep 1.6; screencap -p /sdcard/f-drag.png"
        & $adb pull "/sdcard/f-drag.png" "$Repo\build\f-drag.png" | Out-Null
        Write-Host "shot=f-drag"
        Start-Sleep -Seconds 3

        & $adb shell settings put system accelerometer_rotation 0 | Out-Null
        & $adb shell settings put system user_rotation 1 | Out-Null
        Start-Sleep -Seconds 3
        Shot "f-landscape"
        & $adb shell settings put system user_rotation 0 | Out-Null
        & $adb shell settings put system accelerometer_rotation 1 | Out-Null
        Start-Sleep -Seconds 2
        & $adb shell "logcat -d -t 300" |
            Select-String -Pattern "FATAL|AndroidRuntime|ANR in" |
            Select-Object -Last 10 | ForEach-Object { $_.Line }
        Write-Host "finalverify_done"
    }

    "errors" {
        # Prints the compile errors with the file url stripped. Kotlin's messages are long, the
        # console folds them at a narrow width, and the fold lands inside the url - which is how an
        # error ends up looking like nothing but a path.
        foreach ($line in (Get-Content "$Repo\build\v-compile.txt" -Encoding Unicode)) {
            if ($line -match "^e: ") {
                Write-Host ("ERROR " + ($line -replace "^.*\.kt:\d+:\d+\s*", ""))
            }
        }
    }

    "shareonly" {
        # Verification of the ClipData fix, from a state that cannot be wrong: clearing the app's data
        # removes the resumed wizard state that sent the previous pass to the type picker instead of
        # the landing screen. It also resets the free-export counter, so the share is not gated.
        & $adb shell pm clear $pkg | Out-Null
        & $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
        BuildAndInstall
        Shot "g-landing"
        $landing = (Get-Item "$Repo\build\g-landing.png").Length
        Write-Host "landing_size=$landing"
        Tap 540 1745
        Start-Sleep -Seconds 3
        Tap 285 725
        Start-Sleep -Seconds 3
        Tap 540 970
        Start-Sleep -Seconds 8
        Shot "g-workspace"
        $nav = (Get-Item "$Repo\build\g-workspace.png").Length
        Write-Host "nav_size=$nav"
        if ($nav -lt 1500000) { Write-Host "NAV_UNEXPECTED"; return }

        Tap 600 322
        Start-Sleep -Seconds 4
        Shot "g-export"
        Tap 287 1837
        Start-Sleep -Seconds 5
        Shot "g-chooser"
        Write-Host "--- logtail ---"
        & $adb shell "logcat -d -t 400" |
            Select-String -Pattern "Permission Denial|ChooserPreview|ResolverActivity|FATAL|AndroidRuntime" |
            Select-Object -Last 12 | ForEach-Object { $_.Line }
        Write-Host "shareonly_done"
    }

    "c1share" {
        # The two steps that blind chaining kept missing, done so that every transition is checked.
        # The rule this pass learned: Back inside the workspace leaves the editor for the landing
        # screen, so the next tap starts a *new* wizard flow. Never press Back here; dismiss a sheet
        # by tapping the scrim above it instead, and prove the workspace is back before the next tap.
        BuildAndInstall
        OpenWorkspace
        $nav = (Get-Item "$Repo\build\v-nav2.png" -ErrorAction SilentlyContinue).Length
        Shot "v-nav2"
        $nav = (Get-Item "$Repo\build\v-nav2.png").Length
        if ($nav -lt 1500000 -or $nav -gt 2000000) { Write-Host "NAV_UNEXPECTED size=$nav"; return }

        # C1: open the panel from the mode chip and capture a slider mid-drag.
        Tap 360 322
        Start-Sleep -Seconds 6
        Shot "v2-panel"
        & $adb shell "input swipe 220 1648 780 1648 3500 & sleep 1.4; screencap -p /sdcard/v2-drag.png"
        & $adb pull "/sdcard/v2-drag.png" "$Repo\build\v2-drag.png" | Out-Null
        Write-Host "shot=v2-drag"
        Start-Sleep -Seconds 3

        # Dismiss by tapping the scrim well above the sheet, then prove the workspace is back.
        Tap 540 260
        Start-Sleep -Seconds 3
        Shot "v2-back"
        $back = (Get-Item "$Repo\build\v2-back.png").Length
        Write-Host "back_size=$back"
        if ($back -lt 1500000) { Write-Host "NOT_BACK_IN_WORKSPACE"; return }

        # B3: the export dialog, captured before anything is tapped so the buttons can be measured.
        Tap 600 322
        Start-Sleep -Seconds 4
        Shot "v2-export"
        $dlg = (Get-Item "$Repo\build\v2-export.png").Length
        Write-Host "dialog_size=$dlg"
    }

    "shareTap" {
        # Taps coordinates measured from a screenshot, so nothing is guessed at.
        Tap $X $Y
        Start-Sleep -Seconds 4
        Shot "v3-after-share"
        & $adb shell "logcat -d -t 400" |
            Select-String -Pattern "FATAL|AndroidRuntime|FileProvider|IllegalArgumentException|ResolverActivity" |
            Select-Object -Last 15 | ForEach-Object { $_.Line }
        Write-Host "shareTap_done"
    }

    default { Write-Host "unknown action: $Action" }
}
