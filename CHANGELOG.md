# Changelog

All notable changes to Gear Forge are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.0] - 2026-08-31

First public production release (`versionName 1.0`, `versionCode 4`, `targetSdk 36`).

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
