# GearForge — Monetization & AdMob Configuration

Single source of truth for the monetization integration implemented in Phase 1
(ACTION_PLAN points 22, 23, 24, 25, 26, 27, 28, 31).

## 1. AdMob IDs — test values and where they live

The project keeps **Google's official TEST IDs as dev/CI fallbacks**, with the
**real production IDs supplied at release time** via Gradle properties (enforced
by the release guard in `android/build.gradle`).

> The AdMob **App ID and ad unit ID are not secrets** — both are embedded in every
> published APK and visible from the store listing. The secret in this project is
> `android/keystore.properties`, which is gitignored. Recording the production IDs
> here is safe and makes the release command copy-pasteable.

| ID | Production value | Purpose |
|---|---|---|
| App ID | `ca-app-pub-6154121627229543~9677913532` | Mobile Ads SDK initialization |
| Rewarded unit | `ca-app-pub-6154121627229543/4517387519` | Rewarded video shown for the export gate |

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

To build a release with real IDs, use the wrapper script. Set `$expectedVersionCode`
and `$expectedVersionName` from the intended version in `android/build.gradle`.
Confirm the full `$trustedUploadCertSha256` from an independent trusted source such
as Play Console, not from the bundle being checked. JDK 17+, `py`, and hash-pinned
bundletool 1.18.2 are required; see the
[release guard](.github/skills/android-release-guard/SKILL.md).

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
    -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
  -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
  -ExpectedVersionCode $expectedVersionCode -ExpectedVersionName $expectedVersionName `
  -ExpectedCertificateSha256 $trustedUploadCertSha256
```

The wrapper checks exact IDs, package/version, signature coverage, and the trusted
certificate, then emits a hash-bound JSON receipt. Require `Verified AAB` and the
receipt printed by this run. A file left from an older build is not proof.

The underlying Gradle build command (not an equivalent verification gate):

```text
.\gradlew.bat :android:bundleRelease ^
    -PadmobAppId=ca-app-pub-6154121627229543~9677913532 ^
    -PadmobRewardedUnitId=ca-app-pub-6154121627229543/4517387519
```

Keep debug/CI test-ID fallbacks unchanged. The manifest and `AdManager` already
read from these properties; production values belong in the release invocation.

> ⚠️ Never use test IDs for a production release. With the properties unset, the
> app compiles against Google's test IDs by design so CI/debug builds work without
> secrets.

### app-ads.txt — hostname verification (added 2026-10-07)

AdMob proves that the publisher ID owns the app with a file at the **root of the
developer-website hostname**, never in a subdirectory. AdMob derives the hostname
from the developer website in the Play listing; for GearForge that is
`sebwith.github.io`, so the crawler probes `https://sebwith.github.io/app-ads.txt`
and `http://sebwith.github.io/app-ads.txt`.

| What | Where |
|---|---|
| File | `app-ads.txt` in the root of the separate `SebWith/sebwith.github.io` Pages repo (`main`, `/`) |
| Live URL | <https://sebwith.github.io/app-ads.txt> — HTTP 200, `text/plain` (verified 2026-10-07) |
| Content | `google.com, pub-6154121627229543, DIRECT, f08c47fec0942fa0` |
| Developer site | <https://sebwith.github.io/> — landing page in the same repo |

The publisher ID above must match the App ID (`pub-6154121627229543`); the file
holds no secret, only the same public ID that ships in every APK.

Note the two Pages sites are separate and coexist: the `gearforge` repo publishes
to `sebwith.github.io/gearforge/` (source `main` / `docs`) and hosts the privacy
policy Play links to, while only a repo named exactly `sebwith.github.io` can serve
the hostname root. Editing one does not affect the other. After the file changes,
allow up to 24 hours, or request a crawl from AdMob → Apps → app-ads.txt →
*Search for updates*. Verified in AdMob 2026-10-07: *"Ditt utgivar-id hittades och
app-ads.txt har verifierats"*.

### One AdMob app only — the App ID is frozen into shipped builds (2026-10-07)

The Play listing must be linked to the **existing** AdMob app, never registered as
a new one. The App ID is compiled into the manifest of every published build:

| Evidence | Value |
|---|---|
| App ID in the merged release manifest | `ca-app-pub-6154121627229543~9677913532` |
| Receipt for the shipped AAB (`versionCode 10`) | `android-release.aab.verified-7e22a108….json` — `status: verified`, `appId`, `rewardedId` |

`6154121627229543` is the **only** production publisher ID in the project
(`3940256099942544` is Google's test publisher). Therefore, in AdMob's
*Apps to confirm* flow, choose **“Lägg till … i en befintlig AdMob-app”** and pick
the app that owns the rewarded unit `4517387519` — the dialog's own wording for
that option is *"du har en matchande AdMob-app utan paketnamn eller butiks-id"*,
which is exactly this situation. Creating a **new** AdMob app instead would mint a
second App ID that no shipped build uses, leaving the store-linked entry and the
entry that actually serves ads as two different apps.

## 2. Monetization strategy (ACTION_PLAN point 26)

**Decision: rewarded-only for launch.**

GearForge is a design tool where users are actively focused on modelling gears.
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
2. `UserMessagingPlatform.loadAndShowConsentFormIfRequired` loads and shows the form only when
   consent must be collected (`REQUIRED`); ad initialization/loading proceeds only after it
   completes. With consent already given it completes at once, without building a form WebView.
3. Ads are initialized and loaded only while `canRequestAds()` is true. `MobileAds.initialize`
   runs once per process on a background thread, and the preloaded rewarded ad is process-wide
   (`RewardedAdSource` in `AdManager.kt`), so recreating the activity (font scale, locale) neither
   re-initializes the SDK nor drops or replaces a loaded ad; an ad older than one hour is replaced
   (Google: "ads expire after an hour"). The manifest enables `com.google.android.gms.ads.flag.OPTIMIZE_AD_LOADING`,
   which SDK 23.x leaves off; without it `RewardedAd.load` does its work on the main thread.
4. Every step is guarded so a missing/erroring UMP SDK never blocks the app.

The UMP SDK is declared as `com.google.android.ump:user-messaging-platform:2.2.0` in
[`android/build.gradle`](android/build.gradle); `play-services-ads:23.5.0` resolves it to 3.0.0.
Root-cause analysis of the startup ANR that led to this flow:
`build/audit/ads-anr-20261001/REPORT.md`.

### SDK version decision (2026-10-02) — ship on 23.5.0, migrate after launch

Google's official [deprecation table](https://developers.google.com/admob/android/deprecation)
(read 2026-10-02) lists **23.x as *deprecated*** (supported pair at the time: 24.x/25.x;
25.5.0 raised the Android floor to API 24, which this app already meets). Deprecated
versions still serve ads; the sunset date for 23.x is **June 30, 2027**,
after which ad serving is at risk. Nothing in Google Play policy requires a non-deprecated
ads SDK (unlike Billing 8+, which this app already meets with `billing:9.1.0`).

Decision: the launch build keeps **23.5.0**, because that ads/consent stack is the one the
2026-10-01/02 device campaign verified (consent-before-ads, recovered rewarded source,
OPTIMIZE_AD_LOADING, ANR protocol). The API surface used here (`MobileAds.initialize`,
`RewardedAd.load/show`, UMP `requestConsentInfoUpdate` / `loadAndShowConsentFormIfRequired` /
`canRequestAds` / `showPrivacyOptionsForm`) is unchanged in 24.x/25.x, so migration is a
version bump — but a version bump invalidates the verification and must carry its own
device pass (fresh-consent form, test-ad load, rewarded gate, cold-start ANR smoke).
Schedule that migration as the **first post-launch update**, well before 2027-06-30.

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
