# GearForge — Prioriterad förbättringsplan & lanseringsanalys

> Genererad 2026-08-24. Grundad i kodgranskning av `AdManager`, `BillingManager`,
> `SettingsStore`, `I18n`, `GearSpec`, `GearWorkspace`, `build.gradle` och
> `AndroidManifest.xml`.

Tidsskattning: **S** = 1–4 h, **M** = 1–2 dagar, **L** = 3–5 dagar.
"Kritisk" = krävs för godkänd lansering med annonser på Google Play.

---

## A. Logik & stabilitet

| # | Problem | Åtgärd | Påverkan | Tid | Kritisk |
|---|---|---|---|---|---|
| 1 | Crash-rapportering saknas — inga Firebase/Crashlytics; fel syns bara som "app kraschar" | Lägg till Crashlytics + basala log-event | Logik: ingen. UX: åtgärder snabbare | M | ✅ |
| 2 | Export I/O saknar felhantering — skrivfel/delad fil kastar undantag rakt in i UI:t | try/catch kring `ExportManager.saveToDownloads`, visa snackbar/dialog | Logik: robusthet. UX: tydliga fel | S | ✅ |
| 3 | Extrema parametrar kan ge NaN/degenererad geometri (t.ex. ring_teeth vs sun+2·planet, module-min) | Validera geometriska samband i `GearSpec.setNumber` + varna i UI | Logik: stabilare mesh. UX: färre mystiska modeller | M | ⚠️ |
| 4 | Editor-state försvinner vid processdöd/rotation — ingen `rememberSaveable`/ViewModel | Spara `type`+`params` i `SavedStateHandle` | Logik: korrekt återställning. UX: inget förlorat arbete | M | ⚠️ |
| 5 | Bristande enhetstester — `core` har få tester för `GearCalculator`/`GearSpec`/`ExportManager` | Bygg ut test-suite för matematik + roundtrip + filformat | Logik: regressioner fångas. UX: indirekt | M | ❌ |
| 6 | GL-ytans livscykel — TextureView/EGL vid paus/återupptag och vy-byten (rotation) ej fullt verifierad | Explicit surface-release på `onPause` + återskapa vid behov | Logik: minnesläckor/krascher | M | ⚠️ |
| 7 | Ingen undo/redo — I18n har "undo" men ingen implementation | Enkel param-stack (20 steg) för ångra | Logik: ny statehantering. UX: högt värde | M | ❌ |
| 8 | Ingen återställ-knapp per typ — "återställ till standard för Spur" saknas | "Reset" i overflow-menyn | Logik: trivial. UX: snabb återhämtning | S | ❌ |

---

## B. Användarvänlighet

| # | Problem | Åtgärd | Påverkan | Tid | Kritisk |
|---|---|---|---|---|---|
| 9 | Lokalisering brutet — nya skärmar är hårdkodad engelska; "Svenska"-valet i inställningar har ingen effekt | Dra alla strängar genom `I18n` (tr) i Landing/Wizard/Workspace/Controls | Logik: renare. UX: språkval fungerar | L | ⚠️ |
| 10 | Tyst klampning — värden utanför intervall justeras tyst; användare tror det är en bugg | Visa gul varning "justerades till min/max" vid klampning | Logik: ingen. UX: färre missförstånd | S | ❌ |
| 11 | Avancerade fält förklarade men inte i sitt sammanhang — Addendum/Dedendum/Profile shift | Ordlista (tooltip/help-ikon) för gear-termer i panelen | Logik: ingen. UX: förståelse | M | ❌ |
| 12 | Export-förhandsgranskning — användaren ser inte filnamn/mått innan sparande | Visa filnamn + polycount/dimensioner i Export-dialogen | Logik: liten. UX: trygghet | S | ❌ |
| 13 | Tillgänglighet — touch-mål, kontrast och contentDescription på alla ikoner | Granska mot WCAG/Material-riktlinjer | UX: bredare målgrupp | M | ⚠️ |
| 14 | Presets saknar bild/förklaring — bara namn | Kort beskrivning per preset | UX: valbarhet | S | ❌ |
| 15 | Språkområden i resultat — mm/in-formatering konsekvent (komma vs punkt) | Centralisera formatering via locale | UX: korrekt | S | ❌ |
| 16 | Onboarding för Pro/ads-flöde saknas — användaren vet inte vad som är gratis | Tydlig prismodell i Settings | UX: transparens (krävs av policy) | M | ✅ |

---

## C. Prestanda

| # | Problem | Åtgärd | Påverkan | Tid | Kritisk |
|---|---|---|---|---|---|
| 17 | Mesh byggs om vid varje parameter-ändring — ryckigt vid slider-drag | Debounce/avkastning + cache per params-hash | Logik: trådning. UX: mjukare | M | ❌ |
| 18 | Export av stora STL:er blockerar/utan progress | Kör på bakgrundstråd + progress + avbryt | Logik: async. UX: responsiv | M | ⚠️ |
| 19 | `minifyEnabled false` — större APK, ingen R8 | Aktivera R8 + testa release noggrant | Logik: skydd. UX: mindre APK | M | ⚠️ |
| 20 | Render on demand ej fullt utnyttjad — potentiellt onödiga frames | Verifiera att GL bara ritar vid förändring | UX: batteri/prestanda | S | ❌ |
| 21 | Minnesprofilering vid typbyte — långa sessioner kan ackumulera GL-resurser | `adb shell dumpsys meminfo` + fixa läckor | Logik: stabilitet | M | ⚠️ |

---

## D. Annonsintegration & intäkter

| # | Problem | Åtgärd | Påverkan | Tid | Kritisk |
|---|---|---|---|---|---|
| 22 | Rewarded-ad aldrig anropad — `showRewarded` finns men ingen UI-flöde triggar den | Koppla ad till export-gating ("se annons för att ladda ner") | Logik: flöde. UX: tydlig | M | ✅ |
| 23 | Pro-köp aldrig anropad — `purchasePro` finns men ingen "Uppgradera"-knapp | Lägg till Pro-skärm + köpflöde + restore | Logik: flöde. UX: intäkt | L | ✅ |
| 24 | Test-annons-ID i kod och manifest (`ca-app-pub-3940…`) | Byt till riktiga AdMob-ID:n + app-ID | Logik: konfig. UX: riktiga annonser | S | ✅ |
| 25 | `freeAdvancedExports`/`highQuality` oanvända — död logik som skulle styra gating | Implementera fria exporter (3) + Pro-lås | Logik: affärslogik | M | ✅ |
| 26 | Endast rewarded-format — passform för verktygsapp är svag | Överväg interstitial/banner eller hybrid; dokumentera strategin | UX: intäktseffekt | L | ⚠️ |
| 27 | Restore-köp och pending-purchases — edge cases ofullständiga (avbruten köp-flow) | Robust "Återställ köp"-knapp + lyssnare | Logik: edge. UX: förtroende | M | ✅ |

---

## E. Butiksredo

| # | Problem | Åtgärd | Påverkan | Tid | Kritisk |
|---|---|---|---|---|---|
| 28 | Privacy policy saknas — AdMob/Billing kräver URL i Play Console + Data Safety | Skriv policy, publicera URL, fyll i Data Safety | Compliance | M | ✅ |
| 29 | `allowBackup=true` + implicit cleartext — säkerhetsvarningar | `allowBackup=false`, `usesCleartextTraffic="false"` | Säkerhet | S | ⚠️ |
| 30 | Ingen release-bygg/ikon/skärmdumpar/listing — bara debug, versionCode 1 | Signerad release, ikon, feature-grafik, EN+SV-listing | Butiksklar | M | ✅ |
| 31 | EU/UAC + familjepolicy — annons + köp kräver User Choice Billing i EES; åldersinriktning | Implementera UAC + deklarera målgrupp | Compliance | L | ✅ |
| 32 | targetSdk-plan — 35 gäller nu, 36 krävs aug 2026 | Planera uppgradering + testa | Framtidskrav | S | ⚠️ |

---

## Samlad nulägesanalys

### Var appen står idag
Produkten är funktionellt stark på kärnan (12 kugghjulstyper, realtids-3D med flat
shading, dynamisk inställningspanel med hjälptexter, export STL/3MF/SVG/DXF,
spara/öppna/presets, responsiv layout, tema + mörkt/ljust). Det är en avancerad
prototyp/beta — inte en butiksklar produkt.

### Kritiskt fynd (verifierat i koden)
Monetiseringen är skal men inte inkopplad: `showRewarded()` och `purchasePro()`
anropas ingenstans i UI:t; `freeAdvancedExports`, `highQuality` och
`consumeAdvancedExport()` definieras men används aldrig. Det finns alltså ingen
annons att visa och inget köp att göra trots att AdMob- och Billing-biblioteken är
integrerade. Därtill ligger Googles test-annons-ID kvar i kod och manifest.

### Återstående faser
1. **Monetisering kopplas ihop** (pkt 22–25, 27) — kritiskt, blockar lansering.
2. **Stabilitet & telemetri** (pkt 1–8) — Crashlytics + state-återställning + tester.
3. **Lokalisering & UX-polish** (pkt 9–16) — svenska måste faktiskt fungera.
4. **Compliance & butik** (pkt 28–32) — privacy policy, Data Safety, UAC, release-bygg.

### Kritiska brister & risker
- **Blockare:** test-ID:n, oanvänd annons/köp, saknad privacy policy/Data Safety,
  ingen release-signering, lokalisering som inte fungerar.
- **Risker:** ingen crash-rapportering (åtgärder tar lång tid efter lansering),
  extrema parametrar kan ge degenererad geometri, ingen undo gör att användare
  lätt "fastnar".
- **Policyrisk:** EU User Choice Billing för EES och familje-/åldersdeklaration
  måste vara korrekt annars avvisas appen.

### Realistiskt tidsspann

| Fas | Tid |
|---|---|
| Monetisering + compliance minimum | 2–3 veckor |
| Stabilitet, lokalisering, UX-polish | 2–3 veckor |
| Butiksredo (ikoner, listing, release-test) | 1 vecka |
| **Totalt till lansering** | **5–7 veckor** (1 utvecklare, deltid) |

### Verdict
Appen är ~2–3 månader (sannolikt 5–7 fokuserade arbetsveckor) från en godkänd
Google Play-lansering med annonser. Den största enskilda bromsklossen är inte
tekniken utan den oinkopplade monetiseringen och compliance-lagret
(privacy/Data Safety/UAC).
