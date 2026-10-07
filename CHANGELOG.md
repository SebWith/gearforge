# Changelog

All notable changes to GearForge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.1] - pending upload (frozen 2026-10-02)

`versionName 1.1`, `versionCode 10`, `targetSdk 36`, `minSdk 24`. The first internal-test upload
under `versionCode 9` was rejected by Play on 2026-10-03 as already used (an earlier build had
consumed it), so the identical frozen content was rebuilt as `versionCode 10`. Play never accepts
a reused
`versionCode`, so the history is kept here: 5 and 6 were the 1.0 line, 7 added the In-App Review
card, 8 fixed the runtime storage permission, and this release is 10. The build was frozen and
re-verified on 2026-10-02 (signed AAB with a hash-bound receipt, release APK, API 24 emulator
pass, 45-minute endurance pass, full gate set); it has not been uploaded yet.

### Fixed

- The app could stop responding after a cold start or a font-size change. Every activity
  creation loaded a UMP consent form into a WebView even when consent was already given, and
  then ran `MobileAds.initialize` and `RewardedAd.load` on the main thread; a recreation repeated
  all of it and dropped the ad already loaded. The form is now loaded only when consent is
  required, the SDK is initialized once per process on a background thread, the preloaded ad
  survives recreation, and `OPTIMIZE_AD_LOADING` moves the load's work off the main thread.
  Consent-before-ads ordering and the rewarded flow are unchanged.
- A process death during an export could leave a stale temporary file or a pending Downloads
  row behind forever, because nothing recorded that the app owned them. Exports now journal
their ownership before allocating a temporary file or a MediaStore row, and recovery removes
only journal entries older than 24 hours (at most 32 outstanding, fail closed when full) after
re-checking owner, path and pending state; entries are kept on any query or delete failure.
Device-verified with SIGKILL before and during allocation (record-only and record-plus-partial-
temp leftovers), fresh leftovers survive relaunch, deterministically aged leftovers are
reclaimed, zero SELinux denials. A row killed between insert and write has `DATE_MODIFIED=0`,
which the first guard refused to reclaim; age is now decided by `DATE_ADDED` in that case.
- A render-thread shutdown that exceeded its one-second join could let a resumed surface create
  a second renderer before the first finished. `RenderThreadLifecycle` keeps ownership until EGL
  cleanup completes, restarts only on the current live surface, and keeps ownership on failed
  cleanup instead of risking overlap. Device-verified by SIGSTOPping the renderer across a forced
  recreation and Home/Resume cycles (live owners never exceeded 1, zero ANRs).
- The cold-start parameter-panel ANR: the launch thumbnail warm-up queued 28 uncancellable full
  mesh builds on shared background threads, duplicated by the wizard and by activity re-creation,
  starving the UI thread (37.9 s CPU in a 62 s idle window, 74 % inside `Triangulate`). Request
  coalescing (`SingleFlight`) plus cancellation removed the background CPU from the panel-open
  window (6 366 -> 0 ms release-like), and the geometry-collapsed sheet no longer re-composes
  lazily hidden rows.
- STEP exports were rejected by CAD kernels: the writer interpolated JVM array identities
  (`#[I@...`) and wrote malformed entities, a forward-only shared-edge table, a parallel face
  reference and one shell for disconnected bodies. Repaired and verified with OpenCascade 8.0.1
  (zero check failures, round-trip import for spur, helical, rack and planetary fixtures).
- The accessibility pass repaired 20 distinct defects at the root: wizard black-on-black text
  (1.00:1 -> 14.3/16.0:1), unnamed number fields and sliders, sub-48 dp touch targets, missing
  headings and live regions, an invisible GL viewport (description, camera actions, zoom
  buttons), an English-only gizmo, colour-only chip selection, clipped 200 % font labels and an
  API 26 navigation-bar overlap. Across 135 captures on both devices (EN/SV, light/dark, font
  scales 1.0/1.3/2.0, both orientations): 0 unnamed controls, 0 undersized targets, 0 contrast
  failures among 1 520 measured text nodes, 0 clipped text.
- Exporting a gear no longer fails on Android 7.0–9.0 (API 24–28). The app writes
  to the public Downloads folder on those versions, which requires the
  `WRITE_EXTERNAL_STORAGE` permission to be granted at runtime — and it never was,
  so every export silently ended in a generic "Export failed". The permission is
  now requested at the export gate, in all languages.
- Cancelling an export could produce unlimited free exports. The file write ran in a
  cancellable `withContext(Dispatchers.IO)`, and `withContext` re-checks the parent job
  when it resumes — so cancelling while the bytes were being written threw
  `CancellationException` *after* a complete file had reached Downloads. The user got a
  file with no feedback, and `ExportSheet` never received a successful `Result`, so it
  never decremented the free-export counter (consumed only for `Result.success`).
  The write phase is now `NonCancellable`, so the truthful outcome always reaches the
  caller and "file written" and "free export consumed" cannot disagree.
- A failed MediaStore write left a zero-byte or half-written row in Downloads; the
  row is now deleted when the write throws.
- "Restore purchases" answered `false` while the billing client was still connecting
  (cold start, and again after every service disconnect), so a paying user was told
  their purchase could not be found. The request is now queued and answered from the
  first successful connection, and a purchase attempt kicks a reconnect instead of
  failing silently.
- Two saved configurations created within the same second overwrote each other, because
  the editor names them `Gear <epoch seconds>` and the name is the JSON key. Names are
  now made unique instead of losing data.
- The `teeth` field advertised a flat minimum of 5 while the model raised involute gears
  to 8, so typing 5 was accepted, became 8, and produced no clamp warning (the warning
  compares against the field's own bounds) and no `validate()` warning (which inspects
  the already-coerced value). The floor is now one constant,
  `ToothProfile.minTeeth`, read by both the field and the model.
- The DXF export declared no unit while the SVG sibling writes `width="…mm"`, so the same
  gear could be imported as millimetres from one file and as anonymous document units
  from the other. The DXF now carries a HEADER with `$ACADVER` `AC1015`, `$INSUNITS` 4
  (millimetres) and `$MEASUREMENT` 1.
- The dimensions drawn over the 3D view did not follow the model. The renderer drew the
  mesh with `P · V · M` — the orbit and the pan live in the model matrix — while the
  measurement overlay projected the same points with `P · V` only, and the camera snapshot
  carried a second, divergent copy of the camera arithmetic. The labels and their leader
  lines now run through the one definition of the camera
  ([`ViewportCamera.kt`](android/src/main/java/com/gearforge/app/ViewportCamera.kt)), shared
  with the renderer and the tooth picker.
- A dimension label could lose its unit without a trace. The label was drawn into a fixed
  136 dp box, so `Pitch diameter  20.000 mm` was laid out 374 px wide, rendered as
  `Pitch diameter  20.000`, and reported nothing: no ellipsis, no overflow warning, no
  lint. On a 1080 × 2400 screen the three longest labels were clamped to the box and the
  two shortest were not, which is why it read as "the diameters have no unit". Labels are
  now measured and sized to their own text, at the current font scale
  ([`LabelFit.kt`](android/src/main/java/com/gearforge/app/LabelFit.kt)). The print bed's
  ruler labels and corner caption had the same shape — a fixed 54 × 16 dp box, and a
  caption clamped against a width the text did not have — and are measured too.
- Tapping a tooth edited a different tooth. The pick ray was built from an eye position
  invented as "40 units up the Z axis, times the zoom" and kept the pan offset, so it did
  not start where the camera was. It now starts at the real eye and passes through the
  tapped pixel, from the same camera the renderer used.
- The navigation gizmo mirrored the model: turning the model towards the right dragged the
  gizmo's indicator to the left. The quaternion was composed as `qy(rotY) ⊗ qx(rotX)`, the
  inverse of the rotation actually applied.
- The print bed was drawn roughly four times wider than the viewport, with its edges off
  screen, because the camera framed the model before the renderer knew how large the bed
  was. Framing now happens once the bed size is known, and again whenever it changes.
- The system back gesture thrown away the whole choice in the setup wizard. The visible
  Back button walked one step, but a back press left the wizard from every step — so two
  taps in, one gesture discarded the gear type *and* the preset, and on Android 13+ the
  user watched that happen before it committed.
- Android 12+ device-to-device transfer was still enabled even though
  `android:allowBackup="false"` reads as "no backup at all": `allowBackup` does not disable
  the transfer path, and a manifest with no `<device-transfer>` section has that mode fully
  enabled. Cloud backup and device transfer now both exclude everything
  (`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`).
- The legacy launcher icon was the adaptive foreground on its own — a square — so on
  launchers that use it the corners were filled instead of masked. It now carries the same
  rounded mask as the adaptive icon.
- The assembly legend and the measurement HUD's block of unanchored measurements were drawn in
  the same place: both at 12 dp from the viewport's top-start edge, one translucent panel over
  the other. It affected every type with more than one body — a hub is enough, and the mass row
  has no circle to anchor to, so the block is always drawn — and the body names and the numbers
  were mutually unreadable. The corner is now allocated once, in `ViewportChrome.kt`, and the
  HUD starts below the legend. A measurement label anchored high on the model is kept below the
  legend for the same reason (`HudProjection.distribute(topLimit = …)`).
- A crossed-helical (screw) gear reported the wrong diameters. `GearSpec.transverseModule`
  converted the normal module for `HELICAL` only, while `GearBuilder.mesh` lofts *both* helical
  families from the transverse one — so at the app's own default (β = 45°, m = 1, z = 20) the
  HUD printed 22.0 mm beside a body whose tips are 31.1 mm apart, with its leader line inside
  the material, and the results table agreed with the HUD while both disagreed with the STL.
  `MeasureTest.theReportedOuterDiameterIsTheCircleTheMeshReaches` now pins the reported outer
  diameter against the shipped mesh.
- The wedge that marks an overridden (or just tapped) tooth was placed from the profile's
  *nominal* phase and its *unmodified* radii. Neither describes the tooth: the tip arc runs from
  `c − θ_L` to `c + θ_R`, so a tooth with asymmetric flank angles has its centre off `2π·i/n` by
  `(θ_R − θ_L)/2` — 1.4° on a 20-tooth gear with a 14°/30° flank pair, over a twentieth of a
  pitch — and a profile shift or a raised addendum moves the whole tooth radially. The marker is
  now measured against the same generated outline the tap is resolved against, so the feedback
  and the pick cannot disagree about which tooth is meant `ToothHighlightTest`).
- `GearBuilder.shape` returned a different body from `GearBuilder.mesh` for two families, and the
  consumers of `shape` are not decorative — the SVG and DXF exports, the scrub preview and the
  outline a tap is resolved against all read it. Crossed-helical (screw) gears were generated in the
  normal module while the loft used the transverse one, so the 2D files could describe a gear 41 %
  smaller than the one on screen; and an internal ring came back as an *external* gear while the
  solid is a rim with the tooth profile as its inner boundary. Both now go through one definition of
  the plane they are generated in (`profileParams`, `ringShape`).
- Bevel and hypoid gears reported an outer diameter `2·m·(1 − cos δ)` too large, and drew its anchor
  in the middle of the face. Their profile is generated on the back cone with the virtual tooth count
  `z/cos δ` and then scaled by `cos δ` so the pitch radius stays `m·z/2`, so the tip and root circles
  sit at `m·z/2 ± m·cos δ` and exist on the front face only. At the app's defaults (δ = 45°) the
  leader line ended up about 0.6 mm inside the material. Measurements now carry the height their
  circle exists at (`Measure.anchorZMm`), and the HUD projects each anchor there.
- The internal ring's "Outer dia." was its tooth-root circle — the boundary where the teeth end and
  the rim begins, `max(2 mm, 2·m)` inside the part's real edge — and the HUD anchored the label on
  that same inner circle. `GearBuilder.ringOuterRadius` now owns the number and the mesh, the panel
  and the face-width anchor read it from there.
- A planetary's ring pitch diameter was reported from the `ring_teeth` field, while the builder
  *overrides* a value that does not satisfy the meshing constraint `Zr = Zs + 2·Zp` (with a
  `validate()` warning). The reported circle, and the HUD's leader line with it, therefore described
  a ring that was not in the scene. `GearCalculator.planetaryRingTeeth` is now the one definition,
  used by the builder, the kinematics and the panel.
- The measurement HUD left out a body of every assembly it drew: no pinion for the rack pair, no sun
  or planet for the planetary, no worm — and neither of the number it exists for — for the worm pair,
  and no second stage for the compound gear. The rack pair, the worm pair, the belt and the compound
  gear now report each body they draw; the planets and the pinion stay in the listed block rather
  than anchored, because the HUD can only anchor a circle centred on the model's axis.
- A harmonic drive cut the model's default 5 mm shaft bore — `Bore.cutsBore` allows it — while its
  field list had no bore fields at all, so the hole was neither visible nor editable in the panel
  and its diameter was reported in the HUD as a fixed number. It offers the field now.
- An anchored measurement label could be drawn across the navigation gizmo: the label placement
  separated labels from each other and from the assembly legend, but knew nothing about the widget in
  the top-trailing corner, and the HUD is composed after it. Labels whose pill reaches into the
  gizmo's column now start below it (`ViewportChrome.gizmoBottomDp`).

### Added

- A privacy-options entry point when regulators require one: Settings shows a "Privacy options"
  row only while the UMP SDK reports `PrivacyOptionsRequirementStatus.REQUIRED` and opens the
  Google consent form for withdrawal/changes (`ConsentManager.privacyOptionsRequired()` /
  `showPrivacyOptions()`; I18n key in both catalogues; four new JVM regression tests).
- Per-app language for Android 13+: `res/xml/locales_config.xml` (en, sv) and
  `android:localeConfig`, and the in-app language switch also sets the app locale through
  `LocaleManager` (`MainActivity.applyAppLocale`), so system surfaces such as the per-app
  language screen follow the in-app choice.
- A Pro-only "High-quality export" switch in Settings. `SettingsStore.highQuality` already
  drove every export (`highQuality = isPro && settings.highQuality`) but had no control
  anywhere in the UI, so a Pro user could neither see nor change it.
- A playback speed control — 0.125×, 0.25×, 0.5× and 1× of the base rate — shown in the editor's
  top bar while the mesh is turning, and remembered between runs (`SettingsStore.playbackSpeed`,
  [`PlaybackClock.kt`](android/src/main/java/com/gearforge/app/PlaybackClock.kt)). It lives
  in the top bar rather than over the viewport: a chip row floating in the viewport lands
  wherever the model happens to be after an orbit, which is on top of the dimensions it is
  meant to help you read.
- The print bed is labelled on itself: "Print bed 220 × 220 mm", "10 mm grid" and a
  millimetre ruler along its back edge
  ([`BedOverlay.kt`](android/src/main/java/com/gearforge/app/BedOverlay.kt)). The platen is
  drawn at true scale so "does this fit on my printer?" is a comparison, but an unlabelled
  grid answers nothing — a square could be 5 mm or 20 mm.
- A first-run walkthrough: four tips, in the order a new user meets them — the gear type
  step, then orbiting, exporting and tapping a tooth in the editor
  ([`Tips.kt`](android/src/main/java/com/gearforge/app/Tips.kt),
  `SettingsStore.tipsStep`). It is first-run rather than once-per-session, it survives a
  restart, and Settings offers "Show tips again".
- Predictive back on Android 13+: `android:enableOnBackInvokedCallback="true"`, so the
  system animates the screen the back gesture will reveal instead of committing blind.

### Changed

- The Play button controls the rate and nothing else: pressing pause no longer moves the model.
  Pause used to be expressed by rebuilding the viewport's instance list, and a new instance list
  resets the renderer's spin phase (`GearGLView.playbackScale`) — so pausing snapped the gear
  back to the orientation it was built with, and resuming started the rotation over. Pause now
  takes the same path as a speed change: a parked clock (`PlaybackClock.PARKED_SCALE`) that holds
  its coordinate, including the wall time that passes while nothing is drawn.
- The playback base rate is now the **fastest** step of the speed ladder — one revolution per
  second (`MeshKinematics.DEFAULT_SPEED_RAD_PER_S`) — and every chip's label is a fraction of it:
  `PLAYBACK_SPEEDS` is 0.125× / 0.25× / 0.5× / 1×, with 1× selected as the default. The four rates
  are the ones the app already offered (one revolution per second down to one every eight seconds),
  so nothing was taken away; what moved is which end of that span the app starts at and calls the
  base, because a chip that reads 0.5× should mean half of the rate on screen rather than half of a
  rate that is not. A stored preference from an older build keeps its number, which now means a
  fraction of the new base.

### Removed

- Nothing in this release. The `FileProvider` entry that used to sit here described a state
  that no longer exists: the provider is back and it has a caller again (the export sheet's
  "Share"), so `res/xml/file_paths.xml` is no longer an empty configuration. An entry
  claiming a component is gone is exactly as damaging as one claiming it exists.

### Security

- Kotlin toolchain 2.0.21 -> 2.4.20 (KGP, Compose compiler, stdlib) removes
  GHSA-r937-wjx7-w2jp / CVE-2026-53914; AGP's bundled R8 is overridden with stable R8 9.1.56
  in `settings.gradle` until the AGP default reaches 9.1.29. OSV scanning of every resolved
  Maven version reports 0 affected packages across 16 configurations. The Gradle wrapper is
  checksum-pinned and all 20 CI action uses are pinned to full commits.

### Documentation

- `tools/verify_aab.py` replaces the inline `py -c` one-liner in the release checklist: it checks the
  four AdMob content rules and prints the `versionCode`/`versionName` the bundle actually carries.
  `android-release-guard` also records the two traps found while verifying this release — `apksigner`
  cannot read a bundle at all (`Missing AndroidManifest.xml`; use `keytool -printcert -jarfile`), and
  `intermediates/bundle_manifest/` can hold a stale manifest from an older AGP run, which is not the
  one the bundle packages.
- `STORE_READINESS.md`: the claim table said the uploaded bundle was `versionCode 7`;
  `android/build.gradle` is at `8`.
- `about_body` (EN + SV) listed four of the six export formats. It now names STL, 3MF,
  STEP, IGES, SVG and DXF, matching the claim table.
- Corrected the `ExportManager.bytes` KDoc, which claimed the quality flag was a no-op for
  SVG/DXF. `GearBuilder.shape` reaches `GearProfiles.externalOutline`, which samples the
  flank with the same `flankSteps`, so the flag does change the 2D polygon.
- `Gear Forge` (two words) replaced with `GearForge` in `ACTION_PLAN.md`,
  `CHANGELOG.md` and `MONETIZATION_CONFIG.md`, as the brand rule requires.

## [1.0] - 2026-08-31

First public production release (`versionName 1.0`, `versionCode 6`, `targetSdk 36`).

### Added

- Parametric gear designer for spur, helical, bevel, internal ring, rack, worm,
  worm wheel, planetary and compound (dubbelkugghjul) gears.
- Compound gear (two-stage) with spacer geometry and watertight meshes.
- Presets library covering all gear types (14.5° AGMA, planetary sets, compound pairs).
- Live 3D preview with a Blender-style viewport navigation gizmo (orbit, zoom,
  pan, axis-snap views and HOME).
- Export to STL, 3MF, DXF and SVG.
- Google AdMob rewarded video at the export gate with UMP consent management.
- Google Play Billing one-time "Pro" purchase (unlimited exports + high-quality meshes).
- English and Swedish localization.
- Privacy policy (EN + SV) and hub-bore-follow geometry.
- Accessibility improvements (`performClick` handling).

### Changed

- `targetSdk` raised to 36 (Android 16) as required for Play submission from Aug 2026.
- Release builds now use R8 code shrinking + resource shrinking.
- Release builds are signed with a dedicated RSA-2048 upload keystore.
- Repository now ships the Gradle wrapper for reproducible builds.

### Fixed

- Compound gear interface faces are watertight (non-manifold edge fix).
- `screw-2to1` preset now has a true 2:1 ratio.
