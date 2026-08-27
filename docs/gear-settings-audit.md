# GearForge — Gear Settings & Parameters Audit

**Scope:** Systematic bug/error audit of the settings and parameters for every gear variant in the GearForge Android app.
**Type:** Analysis + documentation only — no application code was modified.
**Audited surface:** [`GearModel.kt`](core/src/main/java/com/gearforge/core/GearModel.kt), [`GearSpec.kt`](core/src/main/java/com/gearforge/core/GearSpec.kt), [`GearCalculator.kt`](core/src/main/java/com/gearforge/core/GearCalculator.kt), [`GearBuilder.kt`](core/src/main/java/com/gearforge/core/GearBuilder.kt), [`GearProfiles.kt`](core/src/main/java/com/gearforge/core/GearProfiles.kt), [`Belt.kt`](core/src/main/java/com/gearforge/core/Belt.kt), [`Bore.kt`](core/src/main/java/com/gearforge/core/Bore.kt), [`HubBuilder.kt`](core/src/main/java/com/gearforge/core/HubBuilder.kt), [`Loft.kt`](core/src/main/java/com/gearforge/core/Loft.kt), [`Expr.kt`](core/src/main/java/com/gearforge/core/Expr.kt), [`GearAnalysis.kt`](core/src/main/java/com/gearforge/core/GearAnalysis.kt), [`PrintAdvisor.kt`](core/src/main/java/com/gearforge/core/PrintAdvisor.kt), [`Presets.kt`](core/src/main/java/com/gearforge/core/Presets.kt), and the Android editor layer ([`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt), [`GearWizard.kt`](android/src/main/java/com/gearforge/app/GearWizard.kt), [`EditorViewModel.kt`](android/src/main/java/com/gearforge/app/EditorViewModel.kt), [`SettingsStore.kt`](android/src/main/java/com/gearforge/app/SettingsStore.kt), [`SavedConfigs.kt`](android/src/main/java/com/gearforge/app/SavedConfigs.kt), [`Controls.kt`](android/src/main/java/com/gearforge/app/Controls.kt), [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt), [`Format.kt`](android/src/main/java/com/gearforge/app/Format.kt), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt)).

## 1. Executive summary

The parameter system is cleanly architected (declarative [`ParamDef`](core/src/main/java/com/gearforge/core/GearSpec.kt:26) registry, a single [`GearParams`](core/src/main/java/com/gearforge/core/GearModel.kt:54) data class, central `getNumber`/`setNumber`/`setChoice` accessors, and locale-aware display via [`Format.kt`](android/src/main/java/com/gearforge/app/Format.kt)). Defaults for every type validate cleanly (covered by [`GearCoreTest.kt`](core/src/test/java/com/gearforge/core/GearCoreTest.kt:71)). However, there are several **correctness defects where a parameter the user can edit is silently ignored by the geometry**, a **validation layer whose "ERROR" severity is cosmetic** (it never blocks export), and **unbounded values accepted through deserialization** that can hang or exhaust memory. These are the highest-value fixes.

Top findings:

1. **[`rack_length` is ignored](core/src/main/java/com/gearforge/core/GearProfiles.kt:207)** — the rack is sized by `teeth × π × module`, not by the user-facing `rack_length` field. The result panel even reports the wrong value, so a user exporting a "60 mm" rack actually gets a 31.4 mm bar.
2. **[`backlash` has a fixed upper bound independent of module](core/src/main/java/com/gearforge/core/GearSpec.kt:147)** — at small module (min 0.2 mm) a legal backlash of 2.0 mm exceeds a full tooth pitch, producing inverted/self-intersecting teeth with no warning.
3. **[`GearSeverity.ERROR` never blocks generation or export](core/src/main/java/com/gearforge/core/GearSpec.kt:44)** — hard validation failures (hub wall, grub screw with no hub, per-tooth thickness) are displayed but the mesh is still built and exported.
4. **[Deserialization trusts unbounded counts](android/src/main/java/com/gearforge/app/SavedConfigs.kt:122)** — `teeth`, `planetCount`, `spokeCount`, `beltDriverTeeth`, etc. are only floor-clamped (`>= 3`/`>= 8`); a crafted saved config can trigger million-iteration geometry loops (OOM/freeze).
5. **[The `GearParams.init` guard is bypassed by `copy()`](core/src/main/java/com/gearforge/core/GearModel.kt:142)** — `setNumber`, presets and export quality all mutate via `copy()`, which does not re-run the `require` checks, allowing `module ≤ 0` / `NaN` to reach division sites.

## 2. Priority legend

- **Critical** — broken geometry, crashes, unbounded loops, or silent data corruption.
- **High** — incorrect or dead settings, unit/precision inconsistencies, unvalidated deserialized inputs.
- **Medium** — locale/unit display, missing logging, performance, naming.
- **Low** — polish, docs, accessibility, test coverage.

---

## 3. Critical actions

### C1 — `rack_length` is ignored by rack geometry (dependencies)
- **Problem/Risk:** [`GearProfiles.rackOutline`](core/src/main/java/com/gearforge/core/GearProfiles.kt:201) computes `length = teeth * pitch` (line 207–208) and never reads `p.rackLength`. The UI field [`rack_length`](core/src/main/java/com/gearforge/core/GearSpec.kt:331) and the result row `result_rack_length` (which prints `p.rackLength`, [`GearSpec.kt:605`](core/src/main/java/com/gearforge/core/GearSpec.kt:605)) both show the user's value, but the exported mesh, the pinion offset in [`GearBuilder.assembly`](core/src/main/java/com/gearforge/core/GearBuilder.kt:70) and the actual bar length all disagree. For the default rack (teeth 10, module 1), the UI says 60 mm but the bar is 31.4 mm.
- **Recommended choice:** Make `rack_length` authoritative: either derive the tooth count from `rack_length` (ceil to whole pitches) or derive `rack_length` from `teeth × π × module` and mark it read-only/calculated. Keep `pinionTeeth` for the pinion only.
- **Why it is best:** Removes the only parameter whose displayed value contradicts the produced file, which is the most user-visible correctness bug in the app.

### C2 — `backlash` bound is independent of module (validation / boundary)
- **Problem/Risk:** [`backlash`](core/src/main/java/com/gearforge/core/GearSpec.kt:147) allows 0–2.0 mm regardless of module. In [`involuteSpur`](core/src/main/java/com/gearforge/core/GearProfiles.kt:62), [`straightSpur`](core/src/main/java/com/gearforge/core/GearProfiles.kt:118), [`cycloidSpur`](core/src/main/java/com/gearforge/core/GearProfiles.kt:148) and [`rackOutline`](core/src/main/java/com/gearforge/core/GearProfiles.kt:213) the flank half-angle `psi` is derived from `thickness − backlash`; at module 0.2 mm a backlash of 2.0 mm makes this negative, inverting teeth or producing self-intersecting polygons. [`GearSpec.validate`](core/src/main/java/com/gearforge/core/GearSpec.kt:655) only checks per-tooth overrides, not the global `backlash` vs pitch.
- **Recommended choice:** Add a cross-field validation that `backlash < π·m` (or a fraction of the pitch, e.g. `< 0.25·π·m`), and either clamp the field or surface a `GearWarning` when violated.
- **Why it is best:** Prevents degenerate/self-intersecting geometry that is currently produced silently for small-module gears.

### C3 — `GearParams.init` guard is bypassed by `copy()` (validation / error handling)
- **Problem/Risk:** [`GearParams`](core/src/main/java/com/gearforge/core/GearModel.kt:54) declares `require(module > 0)`, `require(teeth >= 3)`, `require(thickness > 0)` in its `init` (lines 142–146), but Kotlin data-class `copy()` does **not** re-run `init`. [`GearSpec.setNumber`](core/src/main/java/com/gearforge/core/GearSpec.kt:456) mutates via `copy()`, so `setNumber(p, "module", 0.0)` (or a negative/NaN value) creates an invalid `GearParams` with no exception; the value then reaches [`pitchRadius`](core/src/main/java/com/gearforge/core/GearCalculator.kt:15) and [`helicalTwist`](core/src/main/java/com/gearforge/core/GearBuilder.kt:207) division sites. Presets and [`ExportManager.effective`](android/src/main/java/com/gearforge/app/ExportManager.kt:110) also use `copy()`.
- **Recommended choice:** Clamp/validate inside `setNumber` (and any `copy()`-based mutation) — e.g. `module = v.coerceIn(MIN_MODULE_MM, MAX_MODULE_MM).takeIf { it.isFinite() } ?: default` — or add a `fun GearParams.coerced(): GearParams` that re-asserts invariants before every geometry call.
- **Why it is best:** A single guard point at the mutation boundary restores the invariant the `init` was intended to enforce without requiring callers to remember it.

### C4 — `GearSeverity.ERROR` does not block regeneration or export (error handling)
- **Problem/Risk:** [`GearSpec.validate`](core/src/main/java/com/gearforge/core/GearSpec.kt:655) emits `GearSeverity.ERROR` for hard failures (hub wall [`GearSpec.kt:728`](core/src/main/java/com/gearforge/core/GearSpec.kt:728), hub chamfer [`GearSpec.kt:733`](core/src/main/java/com/gearforge/core/GearSpec.kt:733), grub screw with no hub [`GearSpec.kt:743`](core/src/main/java/com/gearforge/core/GearSpec.kt:743), per-tooth thickness [`GearSpec.kt:750`](core/src/main/java/com/gearforge/core/GearSpec.kt:750)). The settings panel renders them as an error banner ([`GearWorkspace.kt:703`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:703)), but the mesh is still built ([`GearWorkspace.kt:149`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:149)) and [`ExportSheet.doExport`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:821) never consults `validate` at all — so the doc comment "hard errors block regeneration" is false.
- **Recommended choice:** In `ExportSheet`, check `GearSpec.validate(params).any { it.severity == ERROR }` and require explicit override before writing a file; optionally block the live mesh rebuild on ERROR as well.
- **Why it is best:** Makes the ERROR severity meaningful and prevents users from exporting physically impossible parts they were explicitly warned about.

### C5 — `setNumber` does not enforce `ParamDef` bounds (validation / boundary)
- **Problem/Risk:** The UI clamps values via [`NumberRow.apply`](android/src/main/java/com/gearforge/app/Controls.kt:93) using `def.min`/`def.max`, but [`GearSpec.setNumber`](core/src/main/java/com/gearforge/core/GearSpec.kt:456) accepts any double for most keys (`module`, `thickness`, `backlash`, `pressure_angle`, `addendum`, `dedendum`, etc. are copied verbatim). Only integer counts are clamped (and only floor-clamped, not to the `ParamDef` max). Any non-UI caller (tests, future code, presets) can therefore set out-of-range values that the model silently accepts.
- **Recommended choice:** Move the min/max contract into the core by having `setNumber` apply the same bounds declared in [`GearSpec.fields`](core/src/main/java/com/gearforge/core/GearSpec.kt:307), so the UI and the model share one source of truth for limits.
- **Why it is best:** Eliminates the class of "UI clamps, model does not" inconsistencies and makes bounds enforceable regardless of entry point.

### C6 — Deserialization trusts unbounded counts (boundary / performance / security)
- **Problem/Risk:** [`SavedConfigs.fromJson`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:117) reads `teeth = o.optInt("teeth", 20)` with no upper clamp (the constructor only requires `>= 3`). A corrupted or maliciously edited config with `teeth = 2_000_000` drives million-iteration loops in [`involuteSpur`](core/src/main/java/com/gearforge/core/GearProfiles.kt:49) (each tooth emits ~22 points) — an OOM/hang. The same applies to `planetCount`, `spokeCount`, `lighteningHoleCount`, `beltDriverTeeth`, `beltDrivenTeeth`, `wormStarts`, `wheelTeeth`, `ringTeeth`.
- **Recommended choice:** After deserialization, run every count and length through a central clamp (the `ParamDef` ranges in [`GearSpec.fields`](core/src/main/java/com/gearforge/core/GearSpec.kt:307)) before constructing `GearParams`, or reject/coerce out-of-range values.
- **Why it is best:** Provides a hard upper bound at the trust boundary, preventing both accidental freezes and denial-of-service via imported JSON.

### C7 — Screw-gear `helix_angle` is silently forced to 45° (dependencies)
- **Problem/Risk:** [`GearBuilder.mesh`](core/src/main/java/com/gearforge/core/GearBuilder.kt:50) builds `GearType.SCREW_GEAR` with `helicalTwist(p.copy(helixAngleDeg = 45.0))`, ignoring the user's `helix_angle` field (range 15–75°, [`GearSpec.kt:376`](core/src/main/java/com/gearforge/core/GearSpec.kt:376)). The "2:1 crossed" preset sets `helixAngleDeg = 30.0` ([`Presets.kt:348`](core/src/main/java/com/gearforge/core/Presets.kt:348)) but the produced geometry is always a 45° twist.
- **Recommended choice:** Use `p.helixAngleDeg` directly (or, if 45° is intended as a pair relationship, make the field calculated/read-only instead of editable).
- **Why it is best:** Restores the documented meaning of the parameter; an editable field that has no effect is a guaranteed user-facing bug.

### C8 — WORM_PAIR exposes a dead `teeth` field and applies a hidden bore (dependencies)
- **Problem/Risk:** [`GearSpec.fields`](core/src/main/java/com/gearforge/core/GearSpec.kt:345) gives `WORM_PAIR` the shared `commonGeometry`, which includes `teeth` and `thickness`, but the wheel geometry is driven by `wheel_teeth` ([`GearBuilder.wheelMesh`](core/src/main/java/com/gearforge/core/GearBuilder.kt:130)) and the worm by `worm_starts` ([`GearBuilder.wormMesh`](core/src/main/java/com/gearforge/core/GearBuilder.kt:124)). `teeth` is therefore a dead, silently-ignored setting. Additionally, `WORM_PAIR` has no `boreFields`/`hubFields`, yet the wheel retains the default [`BoreSpec`](core/src/main/java/com/gearforge/core/GearModel.kt:23) (ROUND, 5 mm) which is cut into the wheel ([`GearBuilder.wheelMesh`](core/src/main/java/com/gearforge/core/GearBuilder.kt:130) reuses `p`).
- **Recommended choice:** Remove `teeth` from `WORM_PAIR` fields (or make it a read-only "worm effective teeth" result) and either expose the bore/hub or explicitly strip the bore for this type.
- **Why it is best:** Eliminates a setting that appears to matter but does nothing, and avoids an uneditable bore appearing in exported geometry.

### C9 — Internal ring exposes ignored profile fields and validates a bore it never cuts (dependencies)
- **Problem/Risk:** [`GearSpec.fields`](core/src/main/java/com/gearforge/core/GearSpec.kt:355) gives `INTERNAL_RING` `profileFields` (profile shift, addendum, dedendum) plus the `tooth_profile` choice, but [`GearProfiles.internalRingOutline`](core/src/main/java/com/gearforge/core/GearProfiles.kt:175) hardcodes a trapezoid with fixed 1.25/1.0 coefficients and ignores `toothProfile`/`profileShift`/`addendumCoef`/`dedendumCoef`. Meanwhile [`GearSpec.validate`](core/src/main/java/com/gearforge/core/GearSpec.kt:670) warns about the bore, but [`GearBuilder.ringMesh`](core/src/main/java/com/gearforge/core/GearBuilder.kt:135) never calls `Bore.holes` — so the default 5 mm bore is neither editable nor actually present.
- **Recommended choice:** Remove the ignored fields from `INTERNAL_RING` (or implement them in `internalRingOutline`), and either remove the ring from the bore validation path or actually cut the bore.
- **Why it is best:** Prevents dead controls and self-contradictory validation output for this gear type.

### C10 — Belt idlers are absent from the belt path (dependencies)
- **Problem/Risk:** [`toBeltTransmission`](core/src/main/java/com/gearforge/core/Belt.kt:47) synthesizes idlers with hardcoded positions (`offsetX = -8`, `offsetY = 12 + 10·i`, [`Belt.kt:53`](core/src/main/java/com/gearforge/core/Belt.kt:53)), and [`beltPath2D`](core/src/main/java/com/gearforge/core/Belt.kt:191)/[`beltBandMesh`](core/src/main/java/com/gearforge/core/Belt.kt:202) build the loop from only driver+driven pulleys, ignoring the idler list entirely. The assembly shows idler meshes, but the belt band does not wrap around them.
- **Recommended choice:** Include idler centers in `beltLoop`/`beltBandMesh` (or, if a two-pulley model is intended, remove the idler meshes/controls so the geometry matches the UI).
- **Why it is best:** The exported belt band must match the pulleys that are actually placed; otherwise idler placement is a lie.

---

## 4. High actions

### H1 — `SavedConfigs` default for `hubLength` disagrees with the model (defaults / persistence)
- **Problem/Risk:** [`GearParams.hubLength`](core/src/main/java/com/gearforge/core/GearModel.kt:71) defaults to `0.0` (no hub), but [`SavedConfigs.fromJson`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:142) falls back to `10.0` when the key is absent. Loading an older config without `hubLength` silently gains a 5 mm-per-side hub.
- **Recommended choice:** Align the fallback to `0.0` (the model default) and add a round-trip test that asserts `toJson`/`fromJson` preserve every field.
- **Why it is best:** A serialization default that differs from the in-memory default is a classic silent data-corruption source.

### H2 — Two conflicting unit sources (`SettingsStore.useInch` vs `GearParams.unit`) (unit-system)
- **Problem/Risk:** [`SettingsStore.useInch`](android/src/main/java/com/gearforge/app/SettingsStore.kt:20) is read once, in the export dimension display ([`GearWorkspace.kt:892`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:892)), but has no setter caller anywhere — it is permanently `false` and has no UI. The per-gear `unit` choice ([`GearSpec.kt:130`](core/src/main/java/com/gearforge/core/GearSpec.kt:130)) is the value that actually drives module/diametral-pitch. The export preview therefore shows mm dimensions even when the user selected inch units.
- **Recommended choice:** Collapse to a single source of truth — derive display units from `params.unit` (or mirror `params.unit` into `SettingsStore.useInch` when changed) and remove the dead preference.
- **Why it is best:** One unit toggle, one place that reads it, no mismatch between the gear editor and the export preview.

### H3 — `SettingsStore.highQuality` is dead (defaults / code quality)
- **Problem/Risk:** [`SettingsStore.highQuality`](android/src/main/java/com/gearforge/app/SettingsStore.kt:32) defaults to `true` and is never written — there is no toggle in [`SettingsDialog`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:959). It is only AND-ed with `isPro` at [`GearWorkspace.kt:807`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:807), so Pro users are always HIGH and the preference is inert.
- **Recommended choice:** Either add a "high quality" setting to the dialog or remove the preference and use `isPro` directly.
- **Why it is best:** A never-settable preference is misleading dead configuration.

### H4 — `PrecisionLevel` is not user-controllable and `HOBBY` is unreachable (code quality / defaults)
- **Problem/Risk:** There is no `precision` entry in [`GearSpec.fields`](core/src/main/java/com/gearforge/core/GearSpec.kt:307), so the live viewport always builds at `PrecisionLevel.STANDARD` ([`GearBuilder.sliceCount`](core/src/main/java/com/gearforge/core/GearBuilder.kt:218), [`GearProfiles.flankSteps`](core/src/main/java/com/gearforge/core/GearProfiles.kt:31)). `HOBBY` is never set anywhere; `HIGH` is only reachable through [`ExportManager.effective`](android/src/main/java/com/gearforge/app/ExportManager.kt:110) for Pro exports. Consequently [`PrintAdvisor.recommendBacklash`](core/src/main/java/com/gearforge/core/PrintAdvisor.kt:10) has dead HOBBY/HIGH branches.
- **Recommended choice:** Expose a `precision` choice (or a quality toggle) in the UI, or remove the unused enum values and hard-code one sampling path.
- **Why it is best:** Either the setting should work or it should not exist; the current state leaves three-quarters of the sampling logic untestable through the UI.

### H5 — Set-screw `depth`/`axial_offset` are ignored, and screw holes are axial (dependencies)
- **Problem/Risk:** [`HubBuilder.build`](core/src/main/java/com/gearforge/core/HubBuilder.kt:26) positions screw holes by angle only (`screwPositions`, lines 34–38) and ignores `setScrewDepth` and `setScrewAxialOffset` (both exposed in [`GearSpec.hubFields`](core/src/main/java/com/gearforge/core/GearSpec.kt:182)). The holes are circular cutouts at a mid radius extruded along Z, i.e. axial cylinders, not radial through-holes into the bore as the KDoc claims. (Suspicion: this is likely a geometry bug, but confirm against a rendered export before changing.)
- **Recommended choice:** Either implement radial through-holes honoring `setScrewDepth`/`setScrewAxialOffset`, or remove/disable those two fields so they are not offered as real options.
- **Why it is best:** Two editable fields currently have zero effect, and the produced screw holes do not match the documented intent.

### H6 — `Expr` allows division-by-zero/NaN and mishandles units in inch mode (floating-point / unit-system)
- **Problem/Risk:** [`Expr.parseTerm`](core/src/main/java/com/gearforge/core/Expr.kt:39) does `v /= parseFactor()` with no zero guard, so `1/0` returns `Infinity` (not an exception, so `eval` does not fall back to null). Separately, [`Expr.parseFactor`](core/src/main/java/com/gearforge/core/Expr.kt:58) returns `p.module` (always mm) for `m`/`module`, but in inch mode the module field displays diametral pitch; an expression like `2*m` is evaluated in mm and then treated as diametral pitch by [`setNumber`](core/src/main/java/com/gearforge/core/GearSpec.kt:457), producing a wildly wrong module.
- **Recommended choice:** Guard the division (return null on zero divisor) and make `m`/`module` resolve to the value in the *displayed* unit (or disable expressions for the unit-converted field).
- **Why it is best:** Prevents non-finite values from poisoning geometry and fixes unit semantics for the only field that changes meaning with `unit`.

### H7 — `pocket_*` parameters are dead code (code quality)
- **Problem/Risk:** [`GearParams`](core/src/main/java/com/gearforge/core/GearModel.kt:122) defines `pocketCount`/`pocketDepth`/`pocketDiameter`, and [`getNumber`/`setNumber`](core/src/main/java/com/gearforge/core/GearSpec.kt:420) handle them, but no `ParamDef` ever exposes them and [`Bore`](core/src/main/java/com/gearforge/core/Bore.kt) has no pocket generator — they are serialized ([`SavedConfigs.kt:83`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:83)) but never rendered or used.
- **Recommended choice:** Implement pockets or delete the fields and their accessors/serialization.
- **Why it is best:** Removes misleading "supported" parameters that persist data users can never influence or see.

### H8 — `NumberRow` round-trips values through `Float` (floating-point)
- **Problem/Risk:** [`NumberRow.apply`](android/src/main/java/com/gearforge/app/Controls.kt:93) clamps as `Double` but emits `onChange(v.toFloat())`; [`GearWorkspace`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:314) converts back with `.toDouble()`. For the inch diametral-pitch field (`25.4/12 ≈ 2.1166…`) and high-precision `module` values this loses precision on every edit.
- **Recommended choice:** Carry `Double` through the `onChange` callbacks end-to-end (keep `Float` only for the slider thumb position).
- **Why it is best:** Preserves the `decimals = 3` precision the `ParamDef` declares instead of degrading it to ~7 significant digits.

### H9 — Per-tooth pressure angle near 90° is unvalidated in core/deserialization (validation)
- **Problem/Risk:** The UI panel enforces `0 < angle < 89°` ([`GearWorkspace.kt:471`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:471)), but [`SavedConfigs.readToothOverrides`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:204) loads `leftPressureAngleDeg`/`rightPressureAngleDeg` unvalidated. An angle ≥ 90° makes `cos(angle) < 0`, so `rb = rp·cos(angle)` is negative and [`flankAngle`](core/src/main/java/com/gearforge/core/GearProfiles.kt:98) computes `acos(negative/positive) = NaN`, which then propagates into the polygon/mesh.
- **Recommended choice:** Validate per-tooth pressure angles (and all override fields) in `GearSpec.validate` and on deserialization, not just in the UI panel.
- **Why it is best:** Deserialized data is the untrusted path; validation must live where the data enters the model, not only in a widget.

### H10 — Free-form `setChoice` values are never validated (validation)
- **Problem/Risk:** [`GearSpec.setChoice`](core/src/main/java/com/gearforge/core/GearSpec.kt:550) copies `material`, `tolerance`, `lubrication`, `set_screw_thread`, `index_mark`, `bore_hole_tolerance`, `keyway_tolerance` and `belt_profile` verbatim with no membership check. `belt_profile` is mitigated by [`toBeltTransmission`](core/src/main/java/com/gearforge/core/Belt.kt:47) falling back to `GT2`, but the UI then shows a chip with no selected state (the stored value matches no option), and an invalid `index_mark` silently produces no marker.
- **Recommended choice:** Validate choice strings against the option lists at write time, coercing to a safe default and logging the rejection.
- **Why it is best:** Keeps the model in a state where every stored choice is renderable and meaningful.

### H11 — Silent failure paths with no logging (error handling / logging)
- **Problem/Risk:** Three code paths swallow errors with no trace: [`SavedConfigs.fromJson`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:200) returns `null` on any parse error (a corrupted config is silently dropped with no user notice), [`EditorViewModel.readSavedParams`](android/src/main/java/com/gearforge/app/EditorViewModel.kt:158) returns an empty map on any error, and [`Expr.eval`](core/src/main/java/com/gearforge/core/Expr.kt:12) returns `null` on any exception. None log to [`CrashReporting`](android/src/main/java/com/gearforge/app/CrashReporting.kt:23).
- **Recommended choice:** Add a `Log.w`/`CrashReporting.logEvent` at each catch so failures are diagnosable, and surface a non-blocking "could not load" indicator where appropriate.
- **Why it is best:** These are the exact places data loss is silently swallowed; logging makes regression detection possible.

---

## 5. Medium actions

### M1 — Mixed locale formatting between `PrintAdvisor` and `Format` (locale)
- **Problem/Risk:** [`PrintAdvisor.fmt`](core/src/main/java/com/gearforge/core/PrintAdvisor.kt:84) hardcodes `String.format(Locale.US, …)` while the rest of the UI uses locale-aware [`Format.decimal`](android/src/main/java/com/gearforge/app/Format.kt:18). Swedish users see a point decimal separator in print-advice text but a comma in the results panel.
- **Recommended choice:** Route `PrintAdvisor` text through the same locale-aware formatter (pass a formatter or locale in).
- **Why it is best:** Consistent decimal separators across all displayed numbers.

### M2 — `result_backlash` hardcoded to mm in inch mode (unit-system)
- **Problem/Risk:** [`GearSpec.results`](core/src/main/java/com/gearforge/core/GearSpec.kt:641) emits `result_backlash` as `${fmt(effectiveBacklash(p), 3)} mm` with a hardcoded "mm" and no `conv()` conversion, while every other length uses [`conv`](core/src/main/java/com/gearforge/core/GearSpec.kt:761). In inch mode the panel mixes inch diameters with a mm backlash.
- **Recommended choice:** Apply the same `conv()`/unit-suffix logic to `result_backlash`.
- **Why it is best:** Removes a unit inconsistency within a single results table.

### M3 — No logging in the core geometry/export layer (logging)
- **Problem/Risk:** The core module (`GearBuilder`, `GearProfiles`, `BeltCalculator`, writers) contains no logging, unlike the Android layer ([`GearGLView.kt`](android/src/main/java/com/gearforge/app/GearGLView.kt:89)). Degenerate geometry, NaN propagation, or silent clamps are invisible in the crash/event log.
- **Recommended choice:** Add a minimal core-side logger or event hook (e.g. "gear generated with N vertices", "validation clamped value X") gated behind a debug flag.
- **Why it is best:** Makes core regressions diagnosable without a debugger, matching the logging standard already present in the Android layer.

### M4 — Save/export filenames can collide (persistence / data loss)
- **Problem/Risk:** The save button names configs `"Gear ${System.currentTimeMillis() / 1000}"` ([`GearWorkspace.kt:234`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:234)); two saves within the same second silently overwrite. The export filename `gear_${teeth}t_m${module}` ([`GearWorkspace.kt:818`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:818)) omits the gear type, so a spur and a helical with the same teeth/module overwrite each other in Downloads.
- **Recommended choice:** Include the gear type and a unique suffix in both, and prompt before overwriting a saved config name.
- **Why it is best:** Prevents silent loss of a previously saved config or exported file.

### M5 — Belt auto centre distance can exceed the field's max (boundary)
- **Problem/Risk:** [`BeltCalculator.resolve`](core/src/main/java/com/gearforge/core/Belt.kt:92) seeds/derives `centerDistanceMm` with no upper clamp, while the UI field is bounded to 1000 mm ([`GearSpec.kt:292`](core/src/main/java/com/gearforge/core/GearSpec.kt:292)). Large pulleys (up to 200 teeth at HTD 8M) produce auto distances beyond what the user can manually enter, and the result row shows a value outside the stated range.
- **Recommended choice:** Clamp/validate the resolved centre distance to the declared field range, or raise the field max to match the auto range.
- **Why it is best:** Keeps auto-generated values consistent with the documented input limits.

### M6 — Export is not interruptible mid-compute (performance)
- **Problem/Risk:** [`ExportManager.export`](android/src/main/java/com/gearforge/app/ExportManager.kt:84) honours cancellation only at phase boundaries; the CPU-bound mesh build/triangulation (which dominates for large `teeth`) is not cancellable (acknowledged in the KDoc). The live viewport mitigates with debounce + cache ([`GearWorkspace.kt:138`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:138)), but an aborted export still blocks the worker until the build finishes.
- **Recommended choice:** Add periodic `ensureActive()` inside the profile/triangulation loops, or cap tooth counts before export.
- **Why it is best:** Improves responsiveness and reduces wasted work when the user cancels a large export.

### M7 — `screwMinorRadius` is misnamed (code quality)
- **Problem/Risk:** [`HubBuilder.screwMinorRadius`](core/src/main/java/com/gearforge/core/HubBuilder.kt:15) returns tap-drill **diameters** (e.g. 5.0 for M6) that are then halved at the call site ([`HubBuilder.kt:33`](core/src/main/java/com/gearforge/core/HubBuilder.kt:33)). The name implies a radius.
- **Recommended choice:** Rename to `screwMinorDiameter` and return diameters, or return the actual radius.
- **Why it is best:** Removes a foot-gun for future maintainers working on the screw-hole geometry.

### M8 — Hub fields' UI max exceeds physically possible values (boundary / dependencies)
- **Problem/Risk:** `hub_chamfer` is bounded to 20 mm ([`GearSpec.kt:169`](core/src/main/java/com/gearforge/core/GearSpec.kt:169)) and `hub_diameter` to 60 mm, but the physically valid chamfer is `min(hubDiameter/2, hubL, hubR)` (enforced only as a post-hoc ERROR at [`GearSpec.kt:732`](core/src/main/java/com/gearforge/core/GearSpec.kt:732)). The slider lets users reach values that are always invalid for small hubs.
- **Recommended choice:** Derive the chamfer field max from the current hub dimensions (or use a fractional bound), so the control range matches the material available.
- **Why it is best:** Reduces invalid states the user can enter and the associated error spam.

### M9 — Planetary bores are hidden but applied (dependencies)
- **Problem/Risk:** `PLANETARY` has no `boreFields`/`hubFields` ([`GearSpec.kt:335`](core/src/main/java/com/gearforge/core/GearSpec.kt:335)), yet [`GearBuilder.planetary`](core/src/main/java/com/gearforge/core/GearBuilder.kt:152) builds the sun from the default 5 mm bore and the planets with `max(2, module*2)`. Users cannot see or change these bores, but they appear in the exported assembly.
- **Recommended choice:** Either expose planetary bore fields or document/centralize the fixed bore as a derived, non-editable value.
- **Why it is best:** Avoids surprising holes in exported parts that no UI control describes.

### M10 — Saved-config map is unbounded and names unvalidated (security)
- **Problem/Risk:** [`SavedConfigs.save`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:227) inserts an arbitrary `name` as a JSON key into a single SharedPreferences string with no length/character limits and no cap on the number of entries. Long or numerous configs can bloat the preferences file and slow startup.
- **Recommended choice:** Cap the number of saved configs and validate/limit name length and characters.
- **Why it is best:** Protects the preferences store from unbounded growth and keeps config listing fast.

---

## 6. Low actions

### L1 — Expression fields vs numeric keyboard (accessibility)
- **Problem/Risk:** [`NumberRow`](android/src/main/java/com/gearforge/app/Controls.kt:130) uses `KeyboardType.Decimal`/`Number` for fields the code documents as expression-capable (`0.38*m`, `pi*m/2` in [`Expr.kt`](core/src/main/java/com/gearforge/core/Expr.kt:5)). Most soft keyboards with this type do not expose the letters and `*` needed to enter such expressions, and no help text advertises the feature.
- **Recommended choice:** Either use a text keyboard for expression-capable fields with a hint, or document expressions in the field help/glossary.
- **Why it is best:** The expression feature is effectively undiscoverable and possibly unusable on the numeric keypad.

### L2 — Missing test coverage for known behaviors (testability)
- **Problem/Risk:** The test suite covers defaults, validation, and file formats, but has no tests asserting: `rack_length` is honored; screw-gear `helix_angle` is honored; inch/diametral-pitch round-trips through `setNumber`/`getNumber`; `SavedConfigs` JSON round-trips every field (which would have caught H1); `setScrewDepth`/`setScrewAxialOffset` effects; `Expr` division-by-zero.
- **Recommended choice:** Add targeted tests for each of the above so the fixed behaviors are regression-protected.
- **Why it is best:** These are exactly the behaviors that regressed silently; tests turn them into explicit contracts.

### L3 — Legacy `hub_length` exposed as an editable field (code quality)
- **Problem/Risk:** [`GearSpec.hubFields`](core/src/main/java/com/gearforge/core/GearSpec.kt:163) still exposes `hub_length` (labeled "Hub length") as an editable control, though it is documented legacy and only used when `hub_left_length`/`hub_right_length` are both 0 ([`GearCalculator.effectiveHubLeft`](core/src/main/java/com/gearforge/core/GearCalculator.kt:59)).
- **Recommended choice:** Hide it behind the advanced section or migrate it to the left/right fields and drop the legacy fallback.
- **Why it is best:** Reduces confusion from two overlapping hub-length controls.

### L4 — Incorrect KDoc on precision vs SVG/DXF (docs)
- **Problem/Risk:** [`ExportManager.bytes`](android/src/main/java/com/gearforge/app/ExportManager.kt:48) claims "SVG/DXF … are unaffected by precision", but [`GearBuilder.shape`](core/src/main/java/com/gearforge/core/GearBuilder.kt:27) → [`externalOutline`](core/src/main/java/com/gearforge/core/GearProfiles.kt:23) → [`flankSteps`](core/src/main/java/com/gearforge/core/GearProfiles.kt:31) does sample by `precision`. High-quality Pro exports produce different 2D outlines.
- **Recommended choice:** Correct the comment to state that precision affects 2D flank sampling.
- **Why it is best:** Prevents maintainers from assuming the flag is a no-op for vector formats.

### L5 — Mixed metric/imperial results (unit-system)
- **Problem/Risk:** In inch mode, [`GearSpec.results`](core/src/main/java/com/gearforge/core/GearSpec.kt:641) shows diameters in inches but weight in kg and inertia in kg·m² (metric only). This is defensible but inconsistent and undocumented.
- **Recommended choice:** Keep SI for mass/inertia but add a note, or offer imperial mass units.
- **Why it is best:** Avoids user confusion about why some results convert and others do not.

### L6 — Teeth upper bound exists only in the UI (boundary)
- **Problem/Risk:** The 200-tooth cap is declared only as `max = 200.0` in [`commonGeometry`](core/src/main/java/com/gearforge/core/GearSpec.kt:141) and is not enforced in `GearParams` or `setNumber` (which only clamps the floor). Any non-UI path can exceed it.
- **Recommended choice:** Enforce the upper bound in `setNumber` (or a shared coerce helper) alongside the floor.
- **Why it is best:** Mirrors the UI contract at the model layer.

### L7 — MediaStore row left empty when stream write fails (error handling)
- **Problem/Risk:** In [`saveToDownloads`](android/src/main/java/com/gearforge/app/ExportManager.kt:143), if `openOutputStream(uri)` returns null the code returns a failed `Result` but the MediaStore row was already inserted, leaving an empty file in Downloads.
- **Recommended choice:** Delete the inserted row on write failure, or write to a temp file first and insert after success.
- **Why it is best:** Prevents orphan empty files and makes export failures self-cleaning.

### L8 — `Expr` identifier matching accepts prefixes (code quality)
- **Problem/Risk:** [`Expr.parseFactor`](core/src/main/java/com/gearforge/core/Expr.kt:58) matches single-letter `m`/`z` only when not followed by a letter/digit, but `module`/`teeth` are matched by raw `startsWith` at position — an input like `moduleX` resolves `module` and then fails on the trailing `X`, which is fine, but `pi2` resolves `pi` then fails on `2`. The behavior is acceptable but under-documented and untested.
- **Recommended choice:** Add a small expression test suite documenting the accepted grammar.
- **Why it is best:** Removes ambiguity about what the tiny evaluator accepts.

---

## 7. Coverage matrix

| Area | Covered by items |
|---|---|
| Validation | C2, C3, C4, C5, C9, H9, H10 |
| Error handling | C3, C4, H11, L7 |
| Boundary / limit values | C2, C5, C6, M5, M8, L6 |
| Default values | H1, H3, H4 |
| Dependencies (cross-field) | C1, C7, C8, C9, C10, H5, M8, M9 |
| Performance | C6, M6 |
| Security | C6, M10 |
| Accessibility | L1 |
| Code quality | H3, H4, H7, M7, L3, L8 |
| Testability | L2 |
| Logging | H11, M3 |
| Locale / unit-system | H2, H6, M1, M2, L5 |
| Floating-point precision | H6, H8 |
| Persistence / serialization | H1, M4, M10 |

## 8. Confirm-before-fixing notes

- **H5** — the claim that grub-screw holes are axial (not radial) is based on reading [`HubBuilder.build`](core/src/main/java/com/gearforge/core/HubBuilder.kt:26); confirm visually against a rendered export before reworking the geometry.
- **C3** — the `copy()`-bypasses-`init` behavior is a language-level fact, but confirm the specific UI path cannot currently produce `module = 0` before treating it as reachable; it remains a latent invariant risk regardless.
- **M9** — the "hidden bore" for planetary is confirmed by code inspection but should be verified against an actual STL to decide whether it is acceptable by design.

---

*Report generated from source inspection of the `core` and `android` modules. No application code was modified.*
