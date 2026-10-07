# Application audit - 2026-09-24

The initial audit below is retained as historical evidence. The
[follow-up](#follow-up---geometry-sharing-performance-and-release) supersedes its
remaining-findings status and verification counts. Neither phase is unconditional
release approval.

## Scope and limits

Risk-based review of the current GearForge working tree: core validation and geometry,
Android state/persistence, EGL lifecycle, export contracts, cancellation and storage,
consent and billing, build configuration, manifest/provider/backup boundaries, CI and
test tooling. Existing uncommitted changes were preserved. No release identifiers,
SDK levels, signing material, production dependencies or monetization rules changed.
Mockito and org.json were added as JVM test dependencies.

Findings are based on code and reproducible checks, not the claimed model that wrote
the code. This is not proof that every parameter combination, integration or failure
mode is correct. Source/configuration review is broader than runtime coverage.

## Fixed findings

| Area | Root cause and impact | Change and evidence |
| --- | --- | --- |
| Numeric parameters | `GearSpec.setNumber` used model-wide limits instead of the active field's advertised limits. Writes could leave the UI contract. | Clamp in the displayed unit before conversion. `ParamDefBoundaryTest.everyFiniteWriteRespectsAdvertisedFieldLimits` failed on 3,108 writes before the fix and passes afterwards. |
| Hub validation | A missing hub side contributed zero to the chamfer limit, rejecting otherwise valid one-sided hubs. | Only present protrusions limit chamfer. `GearAdvancedTest.hubChamferLimitUsesOnlyPresentProtrusions`: red then green. This does not implement chamfer geometry. |
| EGL resume | Destroyed contexts left stale model/bore/bed GPU handles; consumed pending geometry did not re-upload automatically. | Reset context-owned handles and rebuild retained CPU geometry in `GearGLView.onSurfaceCreated`. Static checks plus two unchanged-model Home/Resume cycles on API 36. |
| Saved configurations | A malformed root JSON document was replaced with an empty object during save, destroying the old collection. | Abort before editing preferences, return failure and show an EN/SV error. `SavedConfigsTest`: the corruption case fails with the old fallback, all three tests pass with the fix. First save and duplicate names remain covered. |
| Printer presets | Float persistence changed the exact Double values compared by selection chips. | Normalize stored preset values without discarding custom values. `SettingsStoreTest`: three failing regressions before the fix, four passing tests afterwards. |
| Consent and ads | UMP failure callbacks continued into ad initialization/loading without checking permission. | Gate initialization, loading, cached results and showing on `canRequestAds`; preserve existing valid cached consent and reject late callbacks. `ConsentAdsRegressionTest` covers SDK gates and failures. |
| Purchase acknowledgement | Ownership queries granted restored purchases but did not acknowledge unacknowledged purchases; retry state was only in memory. | Recovered owned Pro purchases enter the ACK path; token-specific retries and duplicate in-flight guards. Existing entitlement policy unchanged. `BillingAcknowledgementTest` covers restore, failed ACK/retry and closed-activity callbacks. Together with consent tests: 32 new tests, red before fixes and green after. |
| Export cancellation | `IO + NonCancellable` protected the write but lost its result on dispatcher return to a cancelled caller. Cancel also released the UI lock before the job ended. | Nested dispatcher switch inside `NonCancellable`, with an active-job check before committing; release UI state in the job's `finally`. Two deterministic blocked-write tests failed before the fix and pass afterwards; cancellation before writing creates no file. |
| Incomplete downloads | Stream opening was outside MediaStore rollback; rows were immediately public. Legacy direct writes left partial files or destroyed existing files. | Pending MediaStore rows are published only after closing the stream; failures roll back opening, writing and publication. Legacy/cache files use a temporary sibling then rename. Fault-injection tests demonstrated the failures and pass after the fix. |
| Shared filename | The extension already included a dot, but the cache path inserted another. | Use the extension verbatim, e.g. `.stl` instead of `..stl`. This spelling change has not been exercised through a recipient app. |

Primary code: [GearSpec](../core/src/main/java/com/gearforge/core/GearSpec.kt),
[GearGLView](../android/src/main/java/com/gearforge/app/GearGLView.kt),
[SavedConfigs](../android/src/main/java/com/gearforge/app/SavedConfigs.kt),
[SettingsStore](../android/src/main/java/com/gearforge/app/SettingsStore.kt),
[AdManager](../android/src/main/java/com/gearforge/app/AdManager.kt),
[ConsentManager](../android/src/main/java/com/gearforge/app/ConsentManager.kt),
[BillingManager](../android/src/main/java/com/gearforge/app/BillingManager.kt),
[ExportManager](../android/src/main/java/com/gearforge/app/ExportManager.kt),
[GearWorkspace](../android/src/main/java/com/gearforge/app/GearWorkspace.kt).

## Verification

| Check | Result |
| --- | --- |
| `:core:test` | 240 tests, 28 JUnit XML files, zero failures/errors/skips. Results dated 2026-09-24 19:07:43. |
| `:android:testDebugUnitTest` | 112 tests, 12 JUnit XML files, zero failures/errors/skips. Results dated 2026-09-24 19:38:21. |
| `:android:assembleDebug` | APK built at 19:38:25, 23,461,608 bytes. |
| `:android:lint` | Successful; debug report has zero errors and two warnings: newer Gradle available and optional Modifier ordering in MeasurementHud. |
| Editor diagnostics for touched files | No errors reported. |
| `py tools/i18n_audit.py` | 473 keys in each language, matching placeholders. |
| `py tools/check_hardcoded_strings.py` | Zero unexpected literals; two allowlisted. |
| `py tools/check_persistence.py` | Pass; one intentional reserved-field exclusion. |
| `py tools/validate_customizations.py` | Zero errors/warnings. |
| PowerShell syntax | All 18 top-level tools scripts parse without errors. Direct `check-ps1.ps1` execution was policy-blocked; the parser was run without changing policy. This is not an execution test. |

Focused tests were run immediately around each reproducible repair. A separate
read-only review of the Android changes found no additional substantiated P1/P2
regression. Tests using Mockito verify contracts, not actual Play services or a real
ContentResolver implementation.

### API 36 runtime

Device: `emulator-5554`, AVD `mc-target`. Installed APK matches all 218 archive
entries of the new build. SHA-256:
`D5CDEC7ABB526F1FEEF854B8F7A30121E8D791E87BCA49E903B98689EF71E28C`.

The verifier navigated Create new gear -> Spur gear -> General purpose, then ran
two Home/Resume cycles without parameter changes. Both resumed the same activity
and PID 3146 (`LaunchState: HOT`); the model regions were pixel-identical.
Logcat was cleared before the flow. Final log checks found zero FATAL exceptions,
EGL/GL errors, application ANRs or native crashes, and no E/F entries for the app PID.

One STL download consumed exactly one existing emulator credit (2 -> 1). The pulled
file passed `verify_export.py`: 281,684 bytes, 5,632 triangles, 22 x 22 x 6 mm,
no NaN/Infinity or degenerate triangles. No credit reset, Pro override, purchase or
advertisement was used. The app remains in the model view.

Evidence directory:
[runtime-resume-20260924-194339](../build/audit/runtime-resume-20260924-194339/).
It contains `apk-identity-before.txt`, `logcat-final.txt`, `verify-stl.txt`, the STL,
UI XML, `resume-comparison-verified.json` and screenshots `05-before-home1.png`,
`07-after-resume1.png`, `08-before-home2.png`, `10-after-resume2.png`.
These generated artifacts are local, not committed deliverables.

The image tool did not expose usable visual content to the lead reviewer. Screenshot
existence, XML and pixel equality were checked, but visual correctness and an
EN/SV appearance review are not claimed.

## Initial remaining findings

Historical status before the requested follow-up. See the current status below.

| Priority | Finding / cause | Impact and next action |
| --- | --- | --- |
| P2, confirmed | `hubChamfer` is stored and validated but not used by the mesh builder. | The setting does not create the advertised geometry. Implement chamfer lofting in core with mesh/volume/bounds regression tests before treating it as functional. |
| P2, confirmed | `writeShareCopy` deletes the previous cache entries before a new share. | A recipient that has not opened an earlier URI can lose access when another share starts. Use unique retained files with age-based cleanup; test delayed receivers. |
| P2, observed only | Logcat reports 69 and 42 skipped frames while using the parameter panel. Root cause is not established. | Capture a main-thread/Compose/mesh trace and reproduce on the same AVD and a physical device before choosing a fix. No performance improvement is claimed. |
| Hardening | Process death cannot run `finally`; a legacy `.tmp` file or a pending MediaStore row may remain. Failed rollback can also leave a pending row. | Add bounded recovery/cleanup for this app's stale exports and test process death. Do not delete unrelated downloads. |
| Test coverage | CI omits Android unit tests; export verifier only receives a syntax check there. | Add `:android:testDebugUnitTest` and deterministic exported fixtures to CI. |
| Supply chain | Wrapper has no distribution checksum; CI actions use mutable tags. | Verify official hashes, pin wrapper/action artifacts and add a dependency vulnerability check. No CVE status was established here. |
| Release guard | Release script does not automatically run complete artifact/signature verification; AAB checker matches publisher prefixes rather than exact ad IDs. | Add exact-ID and signature checks as required post-build gates. Do not change release identifiers or signing credentials. |
| Maintenance | Two lint warnings and Gradle 9 deprecation notices remain. | Correct Modifier argument ordering with call-site checks; schedule a separately verified Gradle migration rather than blindly upgrading. |

Not covered in the initial audit: API 24-28 device storage path, API 26 runtime, release/R8
execution, the other five formats through Android UI, external CAD compatibility,
real consent/ads/Play purchases, TalkBack/contrast/font-scale review, a 30-minute
memory/FD endurance run, exhaustive races/fuzzing or external vulnerability scanning.
No memory-leak-free, performance, accessibility, release-ready or whole-app bug-free
claim follows from the successful tests above.

## Follow-up - geometry, sharing, performance and release

Requested follow-up, 2026-09-24. Existing edits and device data were preserved.
No publication, new versionCode, signing-material change, SDK migration or real
payment occurred. Release 9 / 1.1 was rebuilt locally for verification only.

### Implemented repairs

| Area | Root cause | Repair and discriminating evidence |
| --- | --- | --- |
| Hub end geometry | The builder ignored `hubChamfer`. | `HubBuilder.build` now uses `Loft.loftWithOuterChamfer` for a nominal 45-degree exterior chamfer at each free end. A mesh-radius test measured 7.000000000000001 mm before the repair instead of the expected 6 mm. Eight chamfer tests pass, covering one-sided/asymmetric/legacy hubs, dimensions, volume, closed topology and orientation. |
| Chamfer boundaries | The limit must account for real hole contours, not only the nominal round bore. | `HubBuilder.chamferLimit` shares a conservative limit with `GearSpec`: protrusion length and at least 0.2 mm end wall for the polygonal hub profile, including screw holes. Validation rejects negative/nonfinite chamfers. Zero chamfer retains the prior triangular surfaces. |
| Share lifetime | Each share deleted earlier copies, and Android could evict the cache independently. | `ExportManager.createShareCopy` writes a unique atomic file in `filesDir/exports`. Ordinary files older than seven days are cleaned on a subsequent share; younger files and future timestamps remain. Legacy cache cleanup uses the same age limit, and its FileProvider root remains supported. Three red-before/green-after regressions cover distinct files, preserved bytes, non-cache storage and retention boundaries; all 13 export tests pass. |
| Parameter-panel CPU cost | An eager scrolling column composed offscreen numeric text fields. Mesh work was already asynchronous/debounced. | Row-level `LazyColumn`, stable keys/content types, bounded visible sheet height and saveable field state. ART sampled `NumberRow` CPU fell from 210 to 21 ms in the measured opening trace. See the performance limits below. |
| Focused value lost on collapse | A lazy row could disappear before committing its focused field. Comparing a parsed Double with a widened model Float also generated stale recommits for unchanged decimals. | Clear focus before collapsing a section and compare at the model's Float precision (`numberRowValueChanged`). Red/green tests cover unchanged decimals and real changes; final API26/API36 runtime entered 24, collapsed, saved and reloaded 24. |
| Toolbar reachability | An unconstrained gear-type label could consume action space; horizontal system insets were not reserved. | Constrain the selector with weight and apply horizontal safe-drawing insets. Final portrait/landscape XML bounds and action checks pass on both devices. This is not a human visual review. |
| Release false acceptance | The old checker accepted publisher prefixes, missing metadata/signature and mismatched IDs; the builder could select an old bundle and omit verification. | Exact parsed metadata/DEX strings, package/version expectations, cryptographic signature coverage, an independently trusted certificate pin, fixed artifact path, freshness and SHA-bound mandatory receipt. Seventeen verifier regression tests pass, including signed/tampered fixtures. |

The hub retains nominal outer base diameter, axial extent and bore contour. Hub and
gear are still separate closed touching bodies, not a boolean union. Tests check
mesh dimensions at 1e-9 mm tolerance and volume at no more than 1e-7 mm3 tolerance.
This does not certify every downstream CAD/printing tool.

Seven-day share retention is a cleanup policy, not a permanent recipient-access
guarantee. Uninstall/data clear and URI-grant expiry or revocation can end access.
FileProvider exposes only the two export subdirectories, not all private files.

Code and regression tests:
[HubBuilder.kt](../core/src/main/java/com/gearforge/core/HubBuilder.kt),
[Loft.kt](../core/src/main/java/com/gearforge/core/Loft.kt),
[GearAdvancedTest.kt](../core/src/test/java/com/gearforge/core/GearAdvancedTest.kt),
[ExportManagerTest.kt](../android/src/test/java/com/gearforge/app/ExportManagerTest.kt),
[Controls.kt](../android/src/main/java/com/gearforge/app/Controls.kt),
[SettingsPanelRowsTest.kt](../android/src/test/java/com/gearforge/app/SettingsPanelRowsTest.kt),
[verify_aab.py](../tools/verify_aab.py),
[test_verify_aab.py](../tools/test_verify_aab.py).

### Final build and targeted runtime

| Check | Result |
| --- | --- |
| Core suite after geometry repair | 247 tests, 28 XML suites, zero failures/errors/skips. No later core changes. |
| Final Android debug unit suite | 123 tests, zero failures/errors/skips, including eight panel tests. |
| Final Android release unit suite | The same 123 tests executed in the release variant, zero failures/errors/skips. These are not minified-runtime tests. |
| Release verifier suite | 17 passing tests. |
| Debug/release lint | Zero errors, two existing warnings per variant: `AndroidGradlePluginVersion` and `ModifierParameter`. |
| R8 runtime/build evidence | Minification/resource shrinking enabled; actual R8 outputs, mapping, signed AAB/APK and matching APK/AAB DEX verified. Release APK not debuggable. |
| Final API36 debug, emulator-5554 | 24 -> collapse -> save -> reload 24; undo 20 and redo 24; More/Reset view and play/pause states pass. Twelve portrait/landscape control bounds pass. |
| Final API26 release, emulator-5558 | The same focused regression sequence and twelve control bounds pass. Network was disabled to avoid production ad traffic. |
| Final targeted runtime logs | Zero new FATAL/ANR/GL matches between markers on both devices. The historical debug ANR at 20:32:21 is unchanged, not disproved or attributed by this result. |
| Preservation | All original 92 model fields restored. Four debug and three release pre-existing saves unchanged; three test saves per device retained. Credits unchanged at 1 and 0 in the final targeted flows. |
| Documentation follow-up | Customizations validator: zero errors/warnings. I18n audit: 473 keys per language, matching placeholders. Existing unrelated Markdown diagnostics remain in store/readme documents. |

The latest main-source timestamp was 20:44:39 UTC. Debug APK creation was 20:46:09;
AAB 20:50:59; release APK 20:51:31. Unit-result timestamps follow their source
changes. Installed final APKs match the current build outputs and retained copies.

| Final artifact | SHA-256 |
| --- | --- |
| Debug APK | `030CDF9F6AE7A849DD4680C7180D484E80064A829216ACB71AE4AC427E111193` |
| Release APK | `A134B2D04D94C6076CBB31743BC31982FBA605F9DDB630F32826ABF622DEA277` |
| Release AAB | `4491F3F4D994D59CE3F9CC987FB9D6ED49FCE1B84D2435423D2810E06E3E72C7` |

Package remains `com.gearforge.geargenerator`, version 9 / 1.1, minSdk 24 and
targetSdk 36. The user confirmed the full upload-certificate SHA-256 from an
independent trusted source:
`BBCD4A4DDFF70072ED0C7F1EA9C8CBC772725679F4FC58009D40F9348CC31D94`.
No private key or password was exposed. Exact production App ID and rewarded ID
match the expected values; the checked content has no Google test-publisher ID.
The current receipt binds these expectations to the final AAB hash. A new upload
still requires an unused versionCode and completion of outstanding release gates.

Final evidence: [UI/build report](../build/audit/ui-final-20260924/REPORT.md),
[AAB receipt](../build/audit/ui-final-20260924/after-aab-receipt.json).
Earlier hashes in other audit folders are superseded for the final binaries.

### API26 storage and remaining platform crash

An isolated API26 AVD (`gf-release-api26-20260924`, emulator-5558) exercised a real
minified release APK. Cold start, wizard/editor, hub 4 mm/chamfer 0.5 mm, save/load,
Home/Resume and the runtime storage permission/ALLOW path passed. One STL download
was parsed: 339,284 bytes, 6,784 triangles, 22 x 22 x 10 mm. Three exports used
exactly the initial three credits; no reset or fourth export was performed.

Two separate share copies were created. The earlier file/URI remained readable
after the second share through privileged `content read`. Gmail retained a read
grant, but onboarding prevented actual sending: this does not prove an
authenticated recipient completed delivery. These storage/share checks used the
preceding release; later changes were confined to panel/UI behavior, followed by
the final targeted minified runtime checks above.

The second share exposed an unresolved Android8 accessibility/chooser crash:
`ViewRootImpl$AccessibilityInteractionConnectionManager.onAccessibilityStateChanged`
called `View.sendAccessibilityEvent(int)` on null at 19:56:33.288. UiAutomator
attached at 19:56:33.187 and the chooser started at 19:56:33.201. The captured stack
contains framework frames only. This supports a platform accessibility-transition
race, but does not prove it cannot affect a real user. No speculative exception
swallowing, accessibility disabling or unrelated R8 keep rule was added.

Upstream source provides additional support for the diagnosis:
[AOSP android-8.0.0_r36](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.0.0_r36/core/java/android/view/ViewRootImpl.java)
checks only `mAttachInfo.mHasWindowFocus` before dereferencing `mView` in that
callback; its detach path clears `mView`.
[AOSP android-8.1.0_r1](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-8.1.0_r1/core/java/android/view/ViewRootImpl.java)
adds `mView != null` to the same condition. These are source observations, not an
API27 device test or proof of the exact OEM patch level. The null guard belongs to
the platform, not code that this app's R8 rules can preserve. No supported app-side
remedy was established, and no OS/minSdk change was made.

The final UI-only run did not repeat that exhausted-credit export path and does
not close this finding. Evidence:
[release/API26 report](../build/audit/release-api26-20260924/REPORT.md),
[fatal stack](../build/audit/release-api26-20260924/fatal-stack.txt).

### Measured performance and endurance

The row-composition repair reduced sampled `NumberRow` CPU from 210 to 21 ms.
First-opening maximum traversal-to-draw fell from 1,630.8748 to 959.0052 ms;
maximum frame duration fell from 2,128.541769 to 1,252.071105 ms. However, the
geometry-collapsed median maximum traversal-to-draw worsened from 212.0759 to
319.6435 ms in three before/after samples. Cold layout is still slow. These data
do not establish general smoothness, 60 FPS or physical-device performance.
Evidence and reusable runner:
[panel performance](../build/audit/panel-performance-20260924/),
[profile-settings-panel.ps1](../tools/profile-settings-panel.ps1).

The first endurance pass used the pre-final-UI debug APK
`83011BC3B33842763308A3C494356B7B962612AEADA33F01B5A2FD7CFB0C6243`.
It completed 1,815.62 seconds, 57 rounds, 31 samples, 11 Home/Resume cycles,
480 XML captures and 5,408 assertions with the same PID and no FATAL/ANR/GL matches.
Native heap rose 6,380 KB; the final ten samples rose 1,460 KB (fitted slope
185.102 KB/min). Total PSS was 232,382 -> 236,526 KB, range 225,943..255,755 KB.
FD count was 289 -> 309, with zero tail endpoint drift. This is neither a proven
leak nor proof of leak freedom. A release build shared the host during part of
that run, so it is not an isolated host-performance benchmark.

Evidence: [first endurance summary](../build/audit/endurance-emulator-5554-20260924-192819-run/summary.json),
[samples](../build/audit/endurance-emulator-5554-20260924-192819-run/samples.csv).

The final debug APK listed above completed a second pass from 21:17:22.1381304 to
21:47:35.3155565 UTC: 1,813.18 seconds, 68 rounds, 31 samples, 13 Home/Resume
cycles, 572 XML captures and 6,429 assertions. PID 28938 remained unchanged;
global and app FATAL/ANR/GL counts were all zero. Before/after APK identity checks
passed. The profile and module (`1.000`) were restored and the panel was closed.
App source and device access were reserved for the test; no export or purchase
was performed by this workload.

| Final-pass metric | First -> last (KB, except FD) | Final ten samples |
| --- | --- | --- |
| Native heap | 42,364 -> 49,448 (+7,084) | +1,744; fitted slope 280.159 KB/min |
| Native allocated | 49,074 -> 56,341 (+7,267) | +2,015; fitted slope 299.273 KB/min |
| Total PSS | 236,505 -> 243,674; range 233,597..253,547 | +3,835; fitted slope 954.819 KB/min |
| File descriptors | 311 -> 309; range 288..311 | Zero endpoint drift |

The native-growth signal persists in the final build, including the last ten
samples. Those samples were not monotonic, and totals do not distinguish retained
live allocations, allocator behaviour or a leak. The two passes had different
round counts and host conditions, so their slopes are not a controlled regression
comparison. `LeakFreeClaim` remains false. Evidence:
[final endurance summary](../build/audit/endurance-final-20260924/summary.json),
[final samples](../build/audit/endurance-final-20260924/samples.csv).

The harness hardcodes `SharedHostReleaseBuild = true`; that summary field is not
a measurement of concurrent host activity. No Gradle build was initiated by this
coordinator during the final pass. Neither pass is a controlled host benchmark,
and this metadata cannot establish a cause for latency or memory growth.

Read-only resource tracing found that ordinary model/shadow replacement deletes
old VBOs, the pending-instance reference is replaceable rather than an unbounded
queue, and the mesh cache is limited to 24 entries (not a byte budget). It also
identified a separate lifecycle risk in `GearGLView.stopRenderThread`: the owner
reference is cleared before a one-second join, with no subsequent liveness check.
If shutdown exceeds that timeout, resume can create another thread sharing the
renderer before the first finishes. No overlapping contexts or shutdown timeout
was observed in the collected data. A timed stop/start regression plus live
context/VBO counts and allocation stacks is needed to connect this risk to the
native-heap trend; heap totals alone do not establish that connection.

### Current open gates

| Priority/status | Remaining work |
| --- | --- |
| Release blocker, external | Real Play purchase, acknowledgement, restore and pending-purchase transitions require the user's Play/licence-test phone. The user confirmed account/product availability but could not connect the phone. No real transaction was attempted; mocks are not Play verification. Online consent/rewarded flows also remain unverified. |
| P2, observed crash | Reproduce the API26 chooser/accessibility transition independently of a new UiAutomator connection and with a real recipient. Preserve the full stack and test a supported platform remedy before claiming full API26 compatibility. |
| P2, measured performance limit | Investigate remaining cold/collapsed panel latency with controlled traces and a physical device. The demonstrated CPU repair is not a general performance pass. |
| Memory diagnosis | Native growth persisted in both 30-minute passes. Capture allocation ownership/stacks and a longer controlled run to distinguish bounded retention, allocator behaviour and a leak; the final pass is not a leak-free result. |
| Lifecycle race risk | Prove and guard render-thread ownership across shutdowns exceeding the one-second join timeout. This source-level risk is not yet a reproduced cause of the measured heap growth. |
| Initial hardening backlog | Superseded by the [remaining-work ledger](#remaining-work-execution---2026-09-25): process-death recovery, Android unit/fixture CI gates and wrapper/action pinning are implemented; the `ModifierParameter` lint warning and one Kotlin advisory remain. |
| Coverage limits | API24 and other OS/device variants, real recipient delivery, other five formats through Android UI/external CAD, large-font/TalkBack/contrast and human screenshot review are not certified by this work. |

Release documentation and agent examples now require the full expected version,
independently trusted certificate pin and fresh hash-bound receipt. This is a
fail-closed artifact gate, not permission to publish or proof of online integrations.

## Remaining-work execution - 2026-09-25

This ledger tracks the next requested pass. Earlier measurements and artifact
hashes remain historical evidence, not verification of subsequent source edits.
Existing changes and device data must be preserved; no publishing, payment,
signing-secret changes, SDK migration or monetization bypass is authorized.

Status as of 2026-10-02. "JVM-verified" means unit/fixture evidence only. The
2026-10-01/02 emulator work (panel latency, accessibility, ads/consent ANR) ran
on the debug build `4b5bd255...` on emulator-5556 (API 36) and emulator-5554
(API 26); the 2026-09-25 core changes (export recovery, render-thread lifecycle)
are device-verified as of 2026-10-02 — see the export and render rows below and
`build/audit/export-verify-20261002/REPORT.md` for the full evidence set
(SIGKILL before/after allocation on the legacy path, pending-row finding and fix,
render-thread SIGSTOP with zero ANRs). The same pass added the UMP privacy-options
entry point and per-app locale (row below), re-ran all gates (core 254/0, Android
193/0, lint 0 errors on debug APK `CD226EEC...`) and rebuilt the signed release:
AAB `7A916318...` with a matching verification receipt, release APK `4CFEC883...`
(9/1.1, minSdk 24, targetSdk 36, pinned upload certificate), both recorded in
`build/audit/export-verify-20261002/`. Final close-out 2026-10-03: the gates re-ran
with forced execution (30/30 tasks, `BUILD SUCCESSFUL in 9m 56s`; core 254/0 and
Android 193/0 with fresh timestamps, lint 0 errors), the instrumented MediaStore
suite is green on API 36 (`OK (2 tests)`), the AAB was rebuilt bit-identically and
re-verified with a new receipt `android-release.aab.verified-6b25017b...json` bound
to the same SHA-256, and every release packaging task reports UP-TO-DATE for the
frozen sources — the artifacts above are the final ones. Later on 2026-10-03 the Play
Console rejected `versionCode 9` as already used, so the identical content was rebuilt
as `versionCode 10`: AAB `88916C71...EBFCD` with receipt `android-release.aab.verified-7e22a108...json`
and release APK `38BE4DD1...04F8` — those are the upload artifacts.

| Work item | Status | Evidence or exact requirement to proceed |
| --- | --- | --- |
| Stale temporary files and pending downloads after process death | Implemented; device-verified 2026-10-02 (legacy) + MediaStore delete branch | `ExportManager` journals ownership before allocating a temp file or MediaStore row, recovers only journalled entries older than 24 hours (at most 32 outstanding, fail closed when full), rechecks owner/path/pending state before deleting and retains entries after query/delete failure. Device evidence: SIGKILL before allocation (record only) and mid-write (record + 84 % / 45 % partial temp), fresh leftovers survive relaunch, deterministically aged leftovers are reclaimed (record + temp), zero AVC denials. MediaStore finding: a row killed between insert and write has `DATE_MODIFIED=0`; the old guard refused to reclaim it forever — fixed so `DATE_ADDED` decides age in that case (new JVM test `zeroModifiedPendingRowIsReclaimedWhenItsAddedStampIsStale`; API 36 hides other-app pending rows from root/CLI, so the delete branch is JVM-verified with the fresh-safety half device-verified). **Update 2026-10-02 (evening):** the delete branch is now DEVICE-verified too — `MediaStoreRecoveryDeviceTest` runs as the row owner on API 36 (instrumented, `OK (2 tests)`): a stale zero-modified pending row with an aged journal record is reclaimed (row and record gone) and a fresh one survives untouched. Measured fact used by the test: a newly inserted pending row already carries `DATE_MODIFIED=0`, and MediaProvider rejects owner updates to those stamps, so aging goes through recovery's injectable `nowMillis`. [Export report](../build/audit/export-verify-20261002/REPORT.md), [instrumented evidence](../build/audit/preupload-20261002/INSTRUMENTED_TEST_EVIDENCE.md). |
| Android JVM tests and deterministic export fixtures in CI | Implemented | CI runs `:android:testDebugUnitTest` and uploads its JUnit XML; `AssetExportTest.exportVerificationFixtures` writes all six formats from the production writers and `verify_export.py` checks them with dimension/count expectations. `test_verify_export.py` 10/10, including malformed inputs. Hosted Linux CI has not been executed. |
| STEP export produced invalid files (found by the new fixture gate) | Fixed | `StepWriter` interpolated JVM array identities (`#[I@...`), wrote malformed VECTOR/DIRECTION/REAL entities, forward-only shared edges, a parallel face reference axis, an invalid product/representation chain and one shell for disconnected bodies. OpenCascade 8.0.1 rejected the original with 73,218 model-check failures and zero shapes. After the repair: core 254/254, six fixtures byte-stable, and OpenCascade import plus round-trip pass with zero check failures for spur, helical, rack and planetary fixtures. [STEP report](../build/audit/step-repair-20260925/REPORT.md). |
| Wrapper checksum and immutable CI action pins | Implemented | Gradle 8.13 distribution SHA-256 `20f1b117...aed78` from services.gradle.org; wrapper validation runs before Gradle; all 20 action uses pinned to full commits resolved from the official repositories. The setup-android commit is unsigned upstream (identity verified, no signature). `test_ci_config.py` 4/4. [CI report](../build/audit/ci-hardening-20260925/REPORT.md). |
| Dependency vulnerability scanning | Implemented; 0 advisories after the Kotlin migration | Checksum-pinned OSV Scanner 2.6.0 over every resolved Maven version; network or coverage failure is an error, never zero findings. Compatible constraints (Guava runtime; JDOM, jose4j, Commons Compress/Lang, Bouncy Castle and Netty build tooling) reduced 282/13/49 to 284 scanned / 1 affected; the Kotlin 2.4.20 migration then reached 284 scanned / 0 affected across 16 configurations (2026-10-01). Coverage limit: lint's own build-time dependencies sit outside the inventory. [Dependency report](../build/audit/dependency-remediation-20260925/REPORT.md), [Kotlin/R8 report](../build/audit/kotlin-r8-migration-20261001/REPORT.md). |
| GHSA-r937-wjx7-w2jp / CVE-2026-53914 (KGP 2.0.21) | Fixed 2026-10-01 | Migrated to Kotlin 2.4.20 (KGP, Compose compiler, stdlib) with `kotlinOptions` -> `compilerOptions`, and overrode AGP's bundled R8 8.13.19 with stable R8 9.1.56 in `settings.gradle` (proven by the release `mapping.txt` header and the AAB/APK dex marker). Lint runs 9.4.1 via `android.experimental.lint.version` after the bundled lint misread Kotlin 2.4 metadata (false `Recycle` warning). OSV: 0 affected. Remove the R8 override once AGP bundles R8 >= 9.1.29. [Kotlin/R8 report](../build/audit/kotlin-r8-migration-20261001/REPORT.md). |
| Modifier warning and Gradle deprecations | Done except version notices | Deprecated Gradle DSL call sites fixed (no `--warning-mode all` deprecation notices); `ModifierParameter` fixed (MeasurementHud argument ordering, 2026-10-01). Remaining: `AndroidGradlePluginVersion` (Gradle 8.14.5 available; Kotlin 2.5 will require >= 8.14.4) and `GradleDependency` (stable lint 9.4.1 kept over the suggested alpha), plus the experimental `android.overridePathCheck`. |
| Render-thread shutdown timeout ownership | Implemented; device-verified 2026-10-02 | `RenderThreadLifecycle` keeps ownership until EGL cleanup completes; delayed completion restarts only on the current live surface; failed cleanup keeps ownership rather than risk overlap. Device test: `GearGLRenderer` SIGSTOPped across a forced activity recreation and Home/Resume cycles — `liveOwners` never exceeded 1, every release logged `retained=[0,0,0]`, zero ANRs, same pid throughout. `RenderThreadLifecycleTest` 16/16. [Export report](../build/audit/export-verify-20261002/REPORT.md). |
| UMP privacy options + per-app locale | Implemented 2026-10-02 | `ConsentManager.privacyOptionsRequired()` / `showPrivacyOptions()`; the Settings sheet shows a "Privacy options" row only when UMP reports REQUIRED (new I18n keys EN/SV); four new JVM tests in `ConsentAdsRegressionTest` (harness handles `showPrivacyOptionsForm`). Per-app locale: `res/xml/locales_config.xml` (en, sv), `android:localeConfig` in the manifest, `MainActivity.applyAppLocale` (API 33+ `LocaleManager`) on both language-switch call sites. Human checks remaining: TalkBack voice + the system per-app-language screen on a physical device. |
| Native-memory growth and measurement metadata | Partly done | Debug-only `GearGLLifecycle` logs now report renderer ownership and GL-handle counts (not per frame). `endurance-device.ps1` reports host build activity as an explicit parameter (unknown by default) instead of hardcoded true. Attribution still requires a controlled emulator run with these counters and allocation stacks. |
| Cold/collapsed parameter-panel latency and historical ANR | Fixed 2026-10-01 | Root cause: the launch thumbnail warm-up queued 28 uncancellable full mesh builds (14 types x 2 themes) on shared background threads, duplicated by wizard/recreation, starving the UI thread (release-like build: 37.9 s CPU in a 62 s idle window; 74 % inside `Triangulate`). Repair: `SingleFlight` request coalescing plus cancellation. Background CPU in the panel-open window 6 366 -> 0 ms (release-like) and 21 221 -> 0 ms (debug); debug open layout->draw median -835 ms across 5 interleaved pairs. The 2026-09-24 20:32:21 ANR is attributed to those warm-up workers contending with the UI thread (saved dropbox dump; the exact late message is unrecoverable because the trace rotated). The remaining ~400 ms release-like sheet open is first composition plus emulated-GPU/host cost; a physical-device comparison still needs a connected device. [Panel report](../build/audit/panel-latency-20261001/REPORT.md). |
| API26 chooser/accessibility crash | Diagnosed, not fixed | AOSP 8.0 vs 8.1 source evidence above. Requires a share run without a concurrent UiAutomator attachment and a real recipient before any API26 compatibility claim. |
| Delayed recipient and six Android export paths | Six UI paths done 2026-10-02; recipient still external | All six formats exported through the app's own UI on `emulator-5556` (only free credits, no gate bypass): STL, 3MF and STEP consumed the first three credits; after a fresh data reset SVG, IGES and DXF consumed the next three. Every file was pulled from Downloads and passed `tools/verify_export.py` (6 × `1 OK, 0 warnings, 0 errors`; STL 5 632 triangles / 22×22×6 mm, 3MF valid ZIP, STEP structural, IGES 22 496 records, SVG well-formed, DXF 2 837 group-code pairs). The rewarded-gate variant came up at zero credits and opened the test creative (see the consent/rewarded row); the recipient-delivery half still needs a configured recipient app and a real person, which stays outside this environment. Evidence: `build/audit/preupload-20261002/sf2-*-result.json` + `files/`. |
| API24 and other supported device variants | Done 2026-10-02 | Isolated AVD `gf-api24` (API 24, isolated home, software GPU) with the signed release APK: landing, wizard, 20T gear, export through the app's own ALLOW flow for the legacy write permission, one STL pulled and verified by `tools/verify_export.py` (1 OK, 5 632 triangles, 22×22×6 mm). Only uiautomator self-crashes appear in the log (app_process noise, documented — not the app). |
| Large font, labels, contrast and TalkBack | Fixed and emulator-verified (automated) | 20 failures found and fixed at the root: wizard black-on-black 1.00:1 -> 14.3/16.0:1, unnamed number fields and sliders, sub-48 dp targets, missing headings/live regions, invisible GL viewport (description, 7 camera actions, zoom buttons), English-only gizmo, colour-only chip selection, clipped 200 % font labels, API 26 navigation-bar overlap. After: across 135 captures (both devices, EN/SV, light/dark, font scale 1.0/1.3/2.0, portrait/landscape) 0 unnamed controls, 0 targets under 48 dp, 0 contrast failures among 1 520 measured text nodes, 0 clipped text. Residual: human TalkBack pass, per-app-locale voice, one-finger pan alternative. [A11y report](../build/audit/a11y-20261001/REPORT.md). |
| Ads/consent main-thread ANR (found by the a11y pass) | Fixed 2026-10-02 | Root cause: every launch built a UMP form WebView although consent existed, then ran `MobileAds.initialize` + `RewardedAd.load` synchronously on the main thread inside UMP callbacks (SDK 23.5.0 flags off before 24.0.0), and every recreation repeated that and leaked the destroyed Activity plus one WebView (5 Activities / 8 WebViews after four recreations). Fix: process-wide `RewardedAdSource` (init once on `Dispatchers.IO`, one preloaded ad surviving recreation), `loadAndShowConsentFormIfRequired`, `OPTIMIZE_AD_LOADING`. Result: ANR dialogs 1 of 5 clean cold starts before -> 0 of 5 after; 2 of 3 windows under host overload before -> 0 of 3 after (one non-ads ANR remains possible, documented); recreations stay at 1 Activity / 4 WebViews / 13 AppContexts. Gates: 186 tests, lint 0 errors; APK `4b5bd255...` installed on both emulators. [Ads/ANR report](../build/audit/ads-anr-20261001/REPORT.md). |
| Longer stress and race/fault coverage | Implemented; device-verified 2026-10-02 | 45-minute endurance on `emulator-5556` with the final debug APK `CD226EEC…`: 100 rounds, 2 703.8 s, 842 UI captures, 9 448 assertions, 20 Home/Resume cycles, PID 9297 unchanged, process restarts 0, FATAL/ANR/GL 0/0/0, profile and module (`1.000`) restored, panel closed. Two earlier attempts failed on harness assumptions that had aged out (the workspace header became a content-desc; the panel now opens with the Geometry section expanded) — both root-caused and fixed before the passing run. Native heap 31 412 → 40 356 KB (tail +48 KB, non-monotone), total PSS 208 117 → 208 926 KB (tail −16 022 KB), FD 302 → 283. `LeakFreeClaim=false`: neither a proven leak nor proof of leak freedom. [Endurance summary](../build/audit/endurance-20261002-final4/summary.json). |
| External CAD compatibility | STEP done; other formats structural only | Four STEP fixtures pass OpenCascade (pinned local `cadquery-ocp-novtk` wheel). STL/3MF/IGES/DXF/SVG have repository structural checks only; FreeCAD and other kernels are not installed. |
| Real Play purchase/ACK/restore/PENDING | Blocked, external; mock coverage in place | `BillingAcknowledgementTest` (19 cases) covers restore, failed-ACK retry, duplicate in-flight guards and closed-activity callbacks. The manual final-verification checklist and run still require the user's Play/licence-test phone with active `gearforge_pro`; no real transaction has occurred. |
| Online consent/rewarded verification | Consent verified on fresh install; rewarded gate opened, completion environment-blocked | On a truly fresh install the UMP form precedes ads and covers the landing until answered; after consent, launches go straight to the landing. The rewarded gate was exercised at zero credits: the sheet's primary button becomes `Watch ad to download` and tapping it opened the AdMob test creative full-screen (AdActivity). On this SwiftShader emulator the ad's WebView GL work then stalled the emulated GPU pipe and produced two `Input dispatching timed out` ANRs whose traces show the app's main thread waiting in `DrawFrameTask` while `RenderThread` blocked in `qemu_pipe_read` inside the ad's Trichrome GL shader compile — no app frames on either stack (environment artifact, traces kept). Completing watch→reward→export still needs a GPU-capable device; the gate's decision logic remains JVM-tested. The privacy-options entry point is implemented and shown when UMP reports REQUIRED. |
| New release/R8 verification | Done 2026-10-03 (sources frozen) | Final pair verified: AAB `7A916318...FADBC7FA` — rebuilt 2026-10-03 with a bitwise-identical result and a fresh signature-verification receipt `android-release.aab.verified-6b25017b...json` (status `verified`, certificate pin `BBCD4A4D...`, package/version 9 / 1.1, exact production AdMob IDs, 222 signed entries, no test publisher) — and release APK `4CFEC883...CD01C` (single signer matching the pin, min 24 / target 36, not debuggable; `release-apk-check.txt`). Gates re-run the same night with forced execution: core 254/0, Android 193/0, lint 0 errors (`tests-forced-20261003-summary.txt`). Supersedes the pre-fix pair `04F2C94A...` / `B5AF99EC...`. Play then rejected `versionCode 9` as already used (2026-10-03), so the same frozen content was rebuilt as `versionCode 10`: AAB `88916C71...EBFCD`, receipt `verified-7e22a108...`, release APK `38BE4DD1...04F8` — the versionCode 10 pair is the upload artifact. Not approval to upload — that step is the developer's. |
