# Gear Forge — Store Readiness

> Phase 5 (ACTION_PLAN points 29, 30, 32). Package `com.gearforge.geargenerator`.
>
> This document is the single checklist for preparing the Google Play listing and
> for tracking the future `targetSdk 36` migration. It complements
> [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) (ads/billing/UMP) and
> [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) (privacy + Data Safety source of truth).

## Release snapshot

| Field | Value |
|---|---|
| applicationId | `com.gearforge.geargenerator` |
| versionCode | `5` (bumped from 4 — new launcher icon) |
| versionName | `1.0` |
| targetSdk | `36` (required for Play submission from Aug 2026) |
| minSdk | `24` |
| Signing | `android/release.keystore`, alias `gearforge`, RSA-2048, 10000 days |
| Signing config | `android/keystore.properties` (gitignored; optional in [`android/build.gradle`](android/build.gradle)) |

---

## 1. Google Play listing copy (EN)

**Title (≤ 30 chars):**

> Gear Forge: Gear Designer

**Short description (≤ 80 chars):**

> Design involute & planetary gears, preview in 3D, export STL/3MF/DXF/SVG.

**Full description:**

Design precise, manufacturable gears right on your phone — no CAD experience
required. Gear Forge is a parametric gear designer for makers, 3D-printing
enthusiasts and engineers who need real geometry, not decorative art.

Build the gear you need:

- **Spur, planetary and compound gears** with full control over module/pitch,
  tooth count, pressure angle, addendum/dedendum, profile shift and backlash.
- **Live 3D preview** — rotate, zoom and inspect the exact teeth you will export.
- **Instant results** — pitch/outer/root/base diameters, center distances and
  tooth dimensions calculated as you type.
- **Presets** for common combinations so you can start from a proven base.

Export what you design:

- **STL & 3MF** for 3D printing.
- **DXF** for laser cutting or CNC.
- **SVG** for 2D layout and documentation.

Free to use: every install gets 3 free exports. Unlock unlimited exports and
high-quality mesh output with the one-time **Pro** purchase, or watch a short
rewarded video to earn an extra export. No account required — your designs stay
on your device.

Gear Forge respects your privacy: we do not collect personal data or upload your
files. Advertising (when shown) uses Google AdMob with consent management for
eligible regions.

---

## 2. Google Play listing copy (SV)

**Titel (≤ 30 tecken):**

> Gear Forge: Kugghjulsdesigner

**Kort beskrivning (≤ 80 tecken):**

> Rita kugghjul, förhandsvisa i 3D, exportera STL/3MF/DXF/SVG.

**Fullständig beskrivning:**

Rita precisa, tillverkningsbara kugghjul direkt i mobilen — inga CAD-kunskaper
krävs. Gear Forge är en parametrisk kugghjulsdesigner för makers, 3D-utskrivare
och ingenjörer som behöver riktig geometri, inte dekorativa skisser.

Bygg det kugghjul du behöver:

- **Rak-, planet- och sammansatta kugghjul** med full kontroll över modul/delning,
  kuggantal, ingreppsvinkel, addendum/dedendum, profilförskjutning och glapp.
- **Live 3D-förhandsvisning** — rotera, zooma och granska exakt de kuggar du ska
  exportera.
- **Direkta resultat** — delnings-/ytter-/rot-/basdiametrar, axelavstånd och
  kuggmått räknas ut medan du skriver.
- **Förinställningar** för vanliga kombinationer så att du kan utgå från en
  beprövad grund.

Exportera det du designar:

- **STL & 3MF** för 3D-utskrift.
- **DXF** för laserskärning eller CNC.
- **SVG** för 2D-layout och dokumentation.

Gratis att använda: varje installation får 3 gratisexporter. Lås upp obegränsade
exporter och högkvalitativ mesh via engångsköpet **Pro**, eller se en kort
belöningsvideo för att tjäna in en extra export. Inget konto krävs — dina
konstruktioner stannar på din enhet.

Gear Forge respekterar din integritet: vi samlar inte in personuppgifter och
laddar inte upp dina filer. Annonsering (när den visas) använder Google AdMob med
samtyckeshantering i tillämpliga regioner.

---

## 3. Store asset checklist

> The adaptive launcher icon is **already present**:
> [`android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`](android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml)
> plus legacy PNGs in `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi`. No icon work is
> required for Phase 5; only the store graphics below remain to be produced.

| Asset | Required | Recommended size / spec | Status |
|---|---|---|---|
| App icon (store listing) | Yes | **512 × 512 px**, 32-bit PNG, ≤ 1 MB | Reuse the app's launcher icon (adaptive source already present) |
| Feature graphic | Yes | **1024 × 500 px**, JPG or 24-bit PNG (no alpha), ≤ 1 MB | To create |
| Phone screenshots | Yes (min 2, max 8) | **1080 × 1920 px** (or 16:9 / 9:16), JPG/PNG 24-bit no alpha; min 320 px, max 3840 px per side | To create (capture from `gear-screen.png` / `diag-screen.png`-style views) |
| Tablet 7″ / 10″ screenshots | Optional | 16:9 or 9:16, same constraints | Optional |
| Promo video | Optional | YouTube URL, 30 s – 2 min | Optional |
| TV banner | Optional | 1280 × 720 px | Optional |

Screenshot capture notes: enable the Android emulator/device in **English** and
**Swedish** (the app is fully localized via [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt))
and capture (1) the landing screen, (2) the 3D workspace with a rendered gear, and
(3) the export dialog — three distinct, localized screenshots per language is a
good baseline.

---

## 4. Privacy policy & Data Safety

- **Privacy-policy source of truth:** [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).
  It must be **hosted at a public URL** and that URL entered in
  **Google Play Console → App content → Privacy policy** before release.
- **Data Safety form:** complete Play Console → App content → Data safety so the
  answers match [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md):
  - **Ad ID / device IDs** are collected (Google AdMob) — for advertising/analytics;
    disclosure required.
  - **Purchase history** is processed by Google Play Billing (not the app itself).
  - **No personal data** (name, email, location, files) is collected or uploaded.
- **Target audience / UAC (Play Console only):** see
  [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) section 5 — declare target
  audience, confirm the families policy stance, and decide/declare User Choice
  Billing (UAC) for EEA/UK users.
- **AdMob production IDs:** still Google TEST IDs (see
  [`MONETIZATION_CONFIG.md`](MONETIZATION_CONFIG.md) section 1). Swap in real
  AdMob App ID + rewarded unit ID before release.

---

## 5. targetSdk 36 upgrade path (ACTION_PLAN point 32)

**Status:** completed — `targetSdk 36` is already in effect (see [`android/build.gradle`](android/build.gradle)).

**Deadline:** Google Play requires new apps and updates to target **Android 16
(API level 36)** from **August 2026** (new apps) — updates to existing apps must
follow on the same schedule. Plan the migration before the deadline.

**Migration steps:**

1. **Toolchain first** — bump `compileSdk` and `targetSdk` to `36` in
   [`android/build.gradle`](android/build.gradle) and update the Android Gradle
   Plugin, Kotlin, and Compose BOM to versions that officially support API 36.
2. **Edge-to-edge** — API 35 already enforces edge-to-edge for `targetSdk 35+`;
   verify insets handling in Compose (`enableEdgeToEdge`, `WindowInsets`) and the
   libGDX `TextureView` surface stays clear of system bars.
3. **Predictive back** — enable and test the predictive-back system animation on
   Android 16 devices.
4. **Behavioral changes** — review the API 35→36 behavior changes that affect
   this app (job scheduling, foreground-service restrictions, notification
   permissions — none are used, but confirm).
5. **Re-test the Phase 1 surface** (the risky integration points):
   - **UMP consent** — consent form still shows before ads on an EEA device and
     respects the user choice.
   - **Billing** — Pro purchase, acknowledge, restore, and PENDING handling still
     work; no Billing library compatibility warnings at API 36.
   - **Compose** — no rendering/layout regressions after the BOM/AGP bump.
   - **R8/release** — rebuild the signed release with shrinking enabled and verify
     the app boots (no stripped `BuildConfig`/reflection issues).
6. **Version bump** — increment `versionCode` (and `versionName` as appropriate)
   for the targetSdk-36 release.

**Rollback plan:** if API 36 blocks release, revert `targetSdk` to `35` and ship,
then fix and retry — do not ship with a half-migrated toolchain.
