# Stress debug tools

Everything a device lane needs is in `tools\`. This is the map, plus the traps each tool exists to
close. All of them are ASCII-only PowerShell (or Python) and are checked by
`tools\check-ps1.ps1` before a run.

## The flow

```
1. tools\emulator-status.ps1          what is actually running?
2. tools\emulator-cold-restart.ps1     bring a dead or wedged emulator back
3. tools\harness-kill.ps1              clear strays and free the uiautomator bridge
4. tools\stress-rebuild.ps1 -Serial X  build, install, prove the bytes on the device
5. tools\stress-app.ps1 -Action <lane> -Serial X
6. py tools\a11y_audit.py build\gf-ui.xml
7. tools\stress-suite.ps1              static suite: tests, lint, audits, one verdict
```

## Tools

| Tool | What it answers |
|---|---|
| `emulator-status.ps1` | Which serials, which qemu/emulator processes and with which `-avd`/`-port`, which console ports are listening, which AVD locks exist, host and JVM memory. Writes `build\emulator-status.txt`. |
| `emulator-cold-restart.ps1` | Restarts ONE emulator by console port: kills a half-started instance that holds its AVD without serving a port, clears the stale `*.lock` files, launches with `-gpu swiftshader`, waits for `sys.boot_completed`, and captures the emulator's own output to `build\emulator-<port>.txt`. |
| `harness-kill.ps1` | Stops stray harness runs and frees the uiautomator bridge, then **proves** the bridge answers. Never kills itself or its own parent shell. |
| `dump-inspect.ps1 -Serial X` | What the harness can actually see: raw `uiautomator dump` output, the packages present in the dump, and every text/content-desc in it. Exists because "the harness cannot see the screen" and "the screen lacks that label" are different facts. |
| `stress-rebuild.ps1 -Serial X` | Builds, installs, and proves the device bytes equal the APK on disk. Refuses to waste 7 minutes on a device that is not attached. |
| `stress-app.ps1 -Action <lane>` | The device lanes. Every lane first proves the device runs the APK on disk (`apk_identity.py`, entry by entry) and refuses to start otherwise. See below. |
| `stress-suite.ps1` | Core tests, Android unit tests, lint, the four repository audits, and the PowerShell layer's own syntax — with one verdict line and counts from the JUnit XML. |
| `add-utf8-bom.ps1 -Path <file>` | Adds a UTF-8 BOM to a script that contains non-ASCII bytes, so PowerShell 5.1 stops reading them as CP1252. Verifies the file still parses. |
| `check-ps1.ps1` | Every `tools\*.ps1`: BOM-less + high bytes (the mojibake trap) and syntax errors. `checked=16 problems=0` is the expected line. |
| `apk_identity.py <a.apk> <b.apk>` | Whether two APKs are the same build, by hashing every entry and naming the ones that differ. Exists because a size comparison called a stale build (535 bytes smaller, dated 1.5 hours earlier) identical, and then called a theory about signature stripping a fact. |
| `preflight.ps1` | The state of every attached device before a lane: API level, density, size, packages, the installed APK's path and size, versionCode. |
| `verify_export.py <file>` | Structural check of an exported mesh. Reports in UTF-8 regardless of the console. |
| `i18n_audit.py`, `check_hardcoded_strings.py`, `check_persistence.py`, `a11y_audit.py` | The repository audits; `a11y_audit.py` takes a uiautomator dump. |

## Lanes (`stress-app.ps1 -Action`)

| Lane | What it proves |
|---|---|
| `walkthrough` | First-run walkthrough end to end: the tip on the wizard, three tips in the editor, that it survives rotation and two cold restarts, that back walks ONE wizard step, and that "Show tips again" brings it back. Every claim is a text check. |
| `hudscale` | The measurement labels are sized from their text: the widest label must grow when the system font scale does. A fixed box gives ratio 1.0; measured 402 -> 605 px, ratio 1.5. |
| `hudprobe` / `bedprobe` | The laid-out geometry of every measurement label, with the label's character count — the pair of numbers that shows whether text is being clipped. |
| `memory` | Waits for the preview cache to settle, then measures the native heap under no load. Any upward trend is a leak; a flat line is the plateau. |
| `memleak` | Switches through all 14 gear types three times and compares the passes. Degrades to INCONCLUSIVE instead of blocking when the menu geometry cannot be read. |
| `killtest` | Real process death (`kill -9` + `adb root`) and a clean return. Hard FAIL when the pid does not change. |
| `faults` | Five injections, five verdicts: process death, rotation with a sheet open, ten backs, double launch, `RUNNING_LOW` trim. |
| `restartcycle` | 15 start/stop cycles with PSS per cycle, so a lifecycle leak is visible as a trend. |
| `endurance -Minutes 30` | 30 minutes of parameter edits, orbits and re-frames, with a memory/fd sample each minute. Deliberately dump-free: a hierarchy read on every round loses the workspace and re-navigates, which force-stops the process and destroys the curve. |
| `monkey` | 3000 random events. |
| `races` | 20 rounds of overlapping input: a tap landing mid-drag (a second adb process holds the swipe), a sheet opened and closed in one frame, an 80 ms double tap, a back cancel during a gesture, and on API 30+ a held pointer while a second one goes down. The verdict is a frequency - restarts, deaths and re-opens counted over 20 rounds - plus a functional check that the editor still answers a normal tap afterwards. Fires coordinates, never a label lookup: a race that waits for a uiautomator dump is over before the tap. |
| `edgeui` | UI edge cases: font_scale 2.0 (editor reachable, the export sheet still opens, no measurement label laid out past the screen edge), dark mode verified to have actually taken effect before it is used, and a real export in dark mode whose file is verified, then a unit round trip to diametral pitch and back with the module field still showing a value. |
| `formats -Formats STL` | Exports one format through the real gate, pulls the file and verifies it structurally. One format per invocation: a comma-separated list arrives as an array and the harness cannot split it. |
| `reset` | `pm clear` — resets the free-export counter between format batches. |
| `workspace`, `newuser`, `consent`, `gate`, `exporttiming`, `recover`, `uiverify`, `settingsprobe`, `shot` | Diagnostics and fixtures. |

## Traps that shaped these tools

- **Two drivers on one device.** Two concurrent runs share the accessibility bridge and the screen, so
  both produce false verdicts. Measured 2026-09-21: `uiautomator dump` returned nothing at all, every
  label read as missing, and `check[PASS] no tip after restart` passed on a screen nobody had read.
  A lock file (`build\harness.lock`) now refuses a second run, and `harness-kill.ps1` clears strays.
- **Absence versus unobserved.** A failed dump is not an empty screen. `HasText` is three-valued;
  `Check` counts "could not observe" as a failure.
- **Labels are XML.** `Rack & pinion` arrives as `Rack &amp; pinion`; matching the raw label never
  works. `XmlEscape` is applied to every label pattern.
- **A dropdown item and its trigger share their text.** Tapping "the current value" while the menu is
  open selects an item instead of opening the menu.
- **`am kill` cannot kill the screen you are looking at.** It reclaims cached processes only.
- **`kill -9` needs `adb root`.** Without it the process survives and a "process death" test tests
  nothing.
- **A lane with no assertions cannot fail.** `faults` printed the same pid before and after its kill
  and drew no conclusion. Count the assertions in a lane; zero means it does not run.
- **A tool that cannot report has not run.** `verify_export.py` died printing `<=` to a CP1252
  console, so the IGES check produced no verdict while looking like it had run.
- **The terminal is not a reliable read channel.** `Get-Process | Format-Table` printed eleven blank
  rows and read as "no emulators running" while two were. Write results to a file and read the file.
- **Inline commands must contain no `$variables`.** The task shell expands them: `$p='...'` becomes
  `='...'`. Anything with a variable belongs in a script.
