# Gear Forge — Monetization & AdMob Configuration

Single source of truth for the monetization integration implemented in Phase 1
(ACTION_PLAN points 22, 23, 24, 25, 26, 27, 28, 31).

## 1. AdMob IDs — test values and where they live

The project keeps **Google's official TEST IDs as dev/CI fallbacks**, with the
**real production IDs supplied at release time** via Gradle properties (enforced
by the release guard in `android/build.gradle`).

> ⚠️ **Production AdMob IDs are secrets and must never be committed.** This file
> contains placeholders only. If production IDs were ever pushed to a public repo,
> rotate them in the Google AdMob console and purge them from git history.

| ID | Production value | Purpose |
|---|---|---|
| App ID | `ca-app-pub-XXXXXXXXXXXXX~YYYYYY` | Mobile Ads SDK initialization |
| Rewarded unit | `ca-app-pub-XXXXXXXXXXXXX/YYYYYY` | Rewarded video shown for the export gate |

### Where each ID lives

1. **App ID** — [`android/src/main/AndroidManifest.xml`](android/src/main/AndroidManifest.xml:26)
   in the `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID"
   android:value="${admobAppId}"/>` entry. The placeholder is injected by
   [`android/build.gradle`](android/build.gradle) via `manifestPlaceholders`.

2. **Rewarded unit** — [`android/src/main/java/com/gearforge/app/AdManager.kt`](android/src/main/java/com/gearforge/app/AdManager.kt:17)
   reads `BuildConfig.ADMOB_REWARDED_UNIT_ID`, which is generated from a
   `buildConfigField` in [`android/build.gradle`](android/build.gradle).

### Where to swap in real production IDs (do this before Play release)

Both values are driven by **Gradle properties** with test-ID fallbacks, declared at
the top of [`android/build.gradle`](android/build.gradle):

- `admobAppId` → App ID (fallback `ca-app-pub-3940256099942544~3347511713`)
- `admobRewardedUnitId` → Rewarded unit (fallback `ca-app-pub-3940256099942544/5224354917`)

To build a release with real IDs:

```bash
.\gradlew.bat :android:assembleRelease ^
    -PadmobAppId=ca-app-pub-XXXXXXXXXXXXX~YYYYYY ^
    -PadmobRewardedUnitId=ca-app-pub-XXXXXXXXXXXXX/YYYYYY
```

Alternatively, replace the two `?:` fallbacks at the top of
[`android/build.gradle`](android/build.gradle:9) with the real IDs. **These two
places are the only locations that must change** — the manifest and `AdManager`
already read from the single source of truth.

> ⚠️ Never use test IDs for a production release. With the properties unset, the
> app compiles against Google's test IDs by design so CI/debug builds work without
> secrets.

## 2. Monetization strategy (ACTION_PLAN point 26)

**Decision: rewarded-only for launch.**

Gear Forge is a design tool where users are actively focused on modelling gears.
Interruptive formats (banners, interstitials) would break the flow mid-design and
increase churn for marginal revenue. Instead:

- **Rewarded video** is used only at the export gate: when a non-Pro user has
  exhausted their 3 free exports, they may watch one ad to unlock a single download.
  This is voluntary, context-relevant, and does not interrupt editing.
- **One-time Pro purchase** (`gearforge_pro`) removes the ad gate entirely and
  unlocks unlimited exports and high-quality mesh output.

Banners/interstitials/hybrid may be reconsidered **only after** launch analytics
(conversion rate, ad completion rate, Pro uptake) show a clear, non-disruptive
opportunity — for example a post-export confirmation banner. No such formats are
wired in this phase.

## 3. Free-export gating & Pro (ACTION_PLAN points 22, 23, 25, 27)

- New installs have **3 free exports** (`SettingsStore.freeAdvancedExports`, default 3).
- Export flow in [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt) `ExportSheet`:
  - **Pro** → always allowed, no counter consumption, `highQuality` honored.
  - **Non-Pro with exports left** → allowed, `consumeAdvancedExport()` decrements the counter.
  - **Non-Pro with 0 exports** → rewarded ad required; `onReward` proceeds, `onUnavailable`
    shows the localized `ad_unavailable` message and does **not** export.
- `highQuality` (`SettingsStore.highQuality`) maps to the core precision path
  `GearParams.precision` (HIGH vs STANDARD) via
  [`ExportManager.bytes`](android/src/main/java/com/gearforge/app/ExportManager.kt:28)
  and is **Pro-gated**: non-Pro exports are forced to STANDARD precision.
- Pro purchase + restore live in the Settings dialog:
  - `BillingManager.purchasePro` launches the Play Billing flow for `gearforge_pro`.
  - `BillingManager.restorePurchases` re-runs `queryPurchasesAsync`.
  - `PURCHASED` INAPP purchases are `acknowledgePurchase`d; `PENDING` purchases are
    surfaced but do **not** grant Pro until a final `PURCHASED` state.

## 4. UMP consent (ACTION_PLAN point 31, app side)

[`ConsentManager`](android/src/main/java/com/gearforge/app/ConsentManager.kt) runs
before [`AdManager.init`](android/src/main/java/com/gearforge/app/AdManager.kt) in
[`MainActivity.onCreate`](android/src/main/java/com/gearforge/app/MainActivity.kt:28):

1. `ConsentInformation.requestConsentInfoUpdate` is requested.
2. If a consent form is available, `UserMessagingPlatform.loadConsentForm` loads it.
3. If consent status is `REQUIRED`, the form is shown; ad initialization/loading
   proceeds only after dismissal.
4. Every step is guarded so a missing/erroring UMP SDK never blocks the app.

The UMP SDK is added via `com.google.android.ump:user-messaging-platform:2.2.0` in
[`android/build.gradle`](android/build.gradle).

## 5. User Choice Billing (UAC) & target audience — Play Console side (ACTION_PLAN point 31)

These cannot be implemented in app code and must be completed in **Google Play Console**
before release:

- **User Choice Billing (UAC)**: for users in the EEA/UK/related territories, decide
  whether to offer an alternative billing system alongside Google Play Billing and, if
  so, enroll in the UAC program and declare the alternative billing provider(s) in Play
  Console (Monetization setup → Alternative billing APIs). If Google Play Billing only
  is used, confirm that choice in the declarations.
- **Target audience & families**: complete the Target audience and content declaration
  (age groups, appeal to children, content rating) in Play Console → App content.
  If the app is not directed at children, declare "No" to children/families design and
  ensure UMP is configured with `setTagForUnderAgeOfConsent(false)` (already set in
  [`ConsentManager.kt`](android/src/main/java/com/gearforge/app/ConsentManager.kt:38)).
- **Data safety**: fill the Play Console Data Safety form to match
  [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) and publish a privacy-policy URL (see point 28).
