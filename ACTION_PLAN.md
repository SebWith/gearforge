# Gear Forge — Åtgärdsplan (32 punkter)

> **Single source of truth** för implementationen av Gear Forge (paket `com.gearforge.app`).
> Denna spec är styrande för allt implementationsarbete. Källförteckningen är
> [`lanseringsplan-forbattringar.md`](lanseringsplan-forbattringar.md) vars 32 prioriterade punkter
> (nummer/titel/Åtgärd) återges ordagrant nedan.
>
> Teknisk kontext: libGDX + Jetpack Compose, AdMob rewarded + engångsköp Pro, targetSdk 35, minSdk 24.

Fasindelning (följ exakt):

| Fas | Innehåll | Punkter |
|---|---|---|
| 1 | Kritisk monetisering & compliance | 22, 23, 24, 25, 26, 27, 28, 31 |
| 2 | Stabilitet & telemetri | 1–8 |
| 3 | Lokalisering & UX | 9–16 |
| 4 | Prestanda | 17–21 |
| 5 | Butiksredo | 29, 30, 32 |

---

## Fas 1 — Kritisk monetisering & compliance

### 22. Rewarded-ad aldrig anropad — "Koppla ad till export-gating ('se annons för att ladda ner')"

- **Beslut (ett enda):** Koppla [`AdManager.showRewarded`](android/src/main/java/com/gearforge/app/AdManager.kt:37) till export-gating i Export-dialogen: när användaren saknar Pro och har slut på gratisexporter krävs en fullföljd rewarded-ad innan nedladdning.
- **Motivering:** Annonswrappern finns men triggas aldrig; att gata exporten bakom en valfri annons ger intäkt utan att störa designarbetet, vilket passar en verktygsapp bättre än interruptiva format.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:367) (`ExportSheet`), [`AdManager.kt`](android/src/main/java/com/gearforge/app/AdManager.kt), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt) (nyckel `watch_ad`).
- **Konkret ändring:** Lägg ett gating-steg i `ExportSheet` före `ExportManager.saveToDownloads`: kontrollera `settings.isPro` och `settings.freeAdvancedExports`; visa vid behov en dialog med "Se annons för att ladda ner" som anropar `adManager.showRewarded(onReward = { exportera }, onUnavailable = { visa meddelande })`.
- **Acceptanskriterium:** På en färsk installation utan Pro visas annonsflödet vid export när gratisexporterna är slut, och exporten sker först efter `onReward`; annonsfel ger tydligt meddelande utan krasch.
- **Beroenden:** Beror på punkt 25 (gating-logik via `freeAdvancedExports`/`isPro`); delar Billing-beroende med punkt 23.

### 23. Pro-köp aldrig anropad — "Lägg till Pro-skärm + köpflöde + restore"

- **Beslut (ett enda):** Lägg till en Pro-skärm i Settings med en "Uppgradera till Pro"-knapp som anropar [`BillingManager.purchasePro`](android/src/main/java/com/gearforge/app/BillingManager.kt:57) samt en "Återställ köp"-knapp.
- **Motivering:** `purchasePro` är implementerad men saknar UI-trigger; ett synligt köpflöde med engångsköp är enda vägen till intäkt och möjliggör borttagning av annonser.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:400) (`SettingsDialog`), [`BillingManager.kt`](android/src/main/java/com/gearforge/app/BillingManager.kt), ny fil [`ProScreen.kt`](android/src/main/java/com/gearforge/app/ProScreen.kt).
- **Konkret ändring:** Utöka `SettingsDialog` med en Pro-sektion som visar pris/status (gratis vs Pro) och öppnar en Pro-skärm med köp- och restore-knappar kopplade till `BillingManager.purchasePro` respektive en ny `restorePurchases()`.
- **Acceptanskriterium:** Från Settings kan användaren öppna Pro-skärmen, påbörja köp av `gearforge_pro`, och `settings.isPro` blir `true` efter lyckat köp (verifieras via toast/logg och att annonsgating avaktiveras).
- **Beroenden:** Beror på befintlig `BillingManager.purchasePro`; punkt 27 gör restore-flödet robust.

### 24. Test-annons-ID i kod och manifest — "Byt till riktiga AdMob-ID:n + app-ID"

- **Beslut (ett enda):** Inför en enda konfigurationskälla (BuildConfig-fält via gradle-property) för AdMob `APP_ID` och rewarded-enhet; behåll Googles TEST-ID:n som debug-default och dokumentera exakt vilka test-ID:n som finns idag och var de ska bytas.
- **Motivering:** Inga produktions-ID:n existerar i projektet; en centraliserad källa undviker utspridda ID:n och gör att release-byggen alltid kompilerar utan att hitta på falska produktions-ID:n.
- **Fil/komponent:** [`android/build.gradle`](android/build.gradle) (buildConfigField + `buildFeatures.buildConfig true`), [`AndroidManifest.xml`](android/src/main/AndroidManifest.xml:26), [`AdManager.kt`](android/src/main/java/com/gearforge/app/AdManager.kt:14).
- **Konkret ändring:** Lägg `buildFeatures { buildConfig true }` och `buildConfigField` för `ADMOB_APP_ID` och `ADMOB_REWARDED_UNIT_ID`, styrda av gradle-properties (`-PadmobAppId=…`/`-PadmobRewardedUnitId=…`) med fallback till Googles test-ID. Manifestets `<meta-data>` `com.google.android.gms.ads.APPLICATION_ID` (idag test-app-ID `ca-app-pub-3940256099942544~3347511713`, [`AndroidManifest.xml:28`](android/src/main/AndroidManifest.xml:28)) och `AdManager.adUnitId` (idag test-enhet `ca-app-pub-3940256099942544/5224354917`, [`AdManager.kt:14`](android/src/main/java/com/gearforge/app/AdManager.kt:14)) läser från samma källa. Dokumentera att dessa två platser är de enda som ska bytas mot produktions-ID vid lansering.
- **Acceptanskriterium:** `assembleRelease` bygger utan hårdkodade produktions-ID; med gradle-properties satta används de angivna ID:n, utan properties används Googles test-ID.
- **Beroenden:** Ingen; dokumenterar exakt var produktions-ID ska föras in senare (krävs innan riktig lansering).

### 25. freeAdvancedExports/highQuality oanvända — "Implementera fria exporter (3) + Pro-lås"

- **Beslut (ett enda):** Implementera exportgating: 3 gratisexporter via [`SettingsStore.freeAdvancedExports`](android/src/main/java/com/gearforge/app/SettingsStore.kt:28) (räknare `consumeAdvancedExport`), därefter Pro-lås eller annons; `highQuality` styr STL/3MF-upplösning och låses till Pro.
- **Motivering:** Fälten är definierade men död logik; att aktivera dem ger en konkret freemium-modell (3 fria exporter + Pro) som passar en verktygsapp och motiverar Pro-köp.
- **Fil/komponent:** [`SettingsStore.kt`](android/src/main/java/com/gearforge/app/SettingsStore.kt:28), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:367) (`ExportSheet`), [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt).
- **Konkret ändring:** I exportflödet dekrementeras `freeAdvancedExports` vid varje export; när räknaren når 0 krävs `isPro` eller rewarded-ad. `highQuality` används för att välja mesh-upplösning/precision och tvingas till låg om användaren inte är Pro.
- **Acceptanskriterium:** Ny installation tillåter exakt 3 exporter utan Pro; fjärde exporten blockeras och hänvisar till Pro eller annons; Pro-köp låser upp obegränsade exporter.
- **Beroenden:** Förutsätter punkt 22 (annons-gating) och punkt 23 (Pro-köp) för fullständigt flöde.

### 26. Endast rewarded-format — "Överväg interstitial/banner eller hybrid; dokumentera strategin"

- **Beslut (ett enda):** Behåll rewarded-only för lansering och dokumentera monetiseringsstrategin i plan/README.
- **Motivering:** För en verktygsapp är interruptiva format (banner/interstitial) störande mitt i designarbetet; rewarded ger frivillig, kontextkänslig intäkt och lägre risk att användare lämnar appen.
- **Fil/komponent:** [`README.md`](README.md) (eller planens monetiseringssektion); ingen kodändring.
- **Konkret ändring:** Skriv ett strategiavsnitt som motiverar varför rewarded-only valts för lansering och under vilka villkor interstitial/banner/hybrid kan övervägas senare (t.ex. efter analys av konverteringsdata).
- **Acceptanskriterium:** Monetiseringsstrategin finns dokumenterad och anger explicit "rewarded-only för lansering" med motivering.
- **Beroenden:** Ingen; ger kontext till punkt 22 och punkt 23.

### 27. Restore-köp och pending-purchases ofullständiga — "Robust 'Återställ köp'-knapp + lyssnare"

- **Beslut (ett enda):** Implementera robust restore via `queryPurchasesAsync` samt hantera pending/acknowledge så att Pro-status återställs pålitligt och köp ackas korrekt.
- **Motivering:** Nuvarande [`PurchasesUpdatedListener`](android/src/main/java/com/gearforge/app/BillingManager.kt:21) sätter `isPro` men saknar acknowledge och robust restore; korrekt ack och restore är krav för Google Play Billing och användarnas förtroende.
- **Fil/komponent:** [`BillingManager.kt`](android/src/main/java/com/gearforge/app/BillingManager.kt), [`SettingsStore.kt`](android/src/main/java/com/gearforge/app/SettingsStore.kt).
- **Konkret ändring:** Lägg `restorePurchases()` som anropar `queryPurchasesAsync` och återställer `settings.isPro`; i `PurchasesUpdatedListener` anropa `acknowledgePurchase` för INAPP och hantera `PURCHASED`/`PENDING`; visa resultat i Pro-skärmen.
- **Acceptanskriterium:** Efter ominstallation och tryck på "Återställ köp" sätts `isPro=true` om köpet finns; köp som inte ackas kan inte återköpas; inget krasch vid avbrutet köpflöde.
- **Beroenden:** Beror på punkt 23 (Pro-skärm med restore-knapp).

### 28. Privacy policy saknas — "Skriv policy, publicera URL, fyll i Data Safety"

- **Beslut (ett enda):** Skriv en privacy policy, publicera den på en URL och fyll i Play Console Data Safety-formuläret innan lansering.
- **Motivering:** AdMob och Billing kräver publicerad privacy policy-URL och korrekt Data Safety-deklaration; utan detta avvisas appen.
- **Fil/komponent:** Ny fil [`privacy-policy.md`](privacy-policy.md) (publiceras externt); dokumentation av Data Safety-svar i planen.
- **Konkret ändring:** Skriv en policy som täcker AdMob (annons-ID), Billing (köp) och att ingen personlig data samlas in; lagra policy-URL för inmatning i Play Console Data Safety.
- **Acceptanskriterium:** Policy-URL finns och är giltig; Data Safety-formuläret är ifyllt och stämmer med appens faktiska databehandling.
- **Beroenden:** Ingen kodändring; krävs tillsammans med punkt 31 innan lansering.

### 31. EU/UAC + familjepolicy — "Implementera UAC + deklarera målgrupp"

- **Beslut (ett enda):** Implementera UMP (User Messaging Platform) samtyckesflöde i appen via `com.google.android.ump` och dokumentera Play Console-sidans User Choice Billing som en konfigurationsuppgift.
- **Motivering:** EU/UAC kräver samtyckeshantering för annonser; UMP kan implementeras nu i appen medan User Choice Billing är en Play Console-konfiguration som inte kan skrivas i kod.
- **Fil/komponent:** [`AdManager.kt`](android/src/main/java/com/gearforge/app/AdManager.kt) (eller ny fil [`ConsentManager.kt`](android/src/main/java/com/gearforge/app/ConsentManager.kt)), [`android/build.gradle`](android/build.gradle), [`AndroidManifest.xml`](android/src/main/AndroidManifest.xml).
- **Konkret ändring:** Initiera UMP (`ConsentInformation`) före annonsladdning, begär samtycke vid behov och respektera valet; dokumentera User Choice Billing som Play Console-inställning och deklarera målgrupp/åldersinriktning.
- **Acceptanskriterium:** På EES-enheter visas samtyckesdialogen innan annonser laddas; annonsbegäran respekterar samtyckesbeslutet; User Choice Billing och målgruppsdeklaration finns dokumenterade för Play Console.
- **Beroenden:** Beror på punkt 24 (konfigurerad annonskälla) och punkt 22 (annonsflöde).

---

## Fas 2 — Stabilitet & telemetri

### 1. Crash-rapportering saknas — "Lägg till Crashlytics + basala log-event"

- **Beslut (ett enda):** Lägg till ett telemetrilager som integrerar Firebase Crashlytics när en Firebase-konfiguration finns, annars faller tillbaka till lokal crash-log (UncaughtExceptionHandler + logcat/fil), så signerad release alltid bygger utan externt Firebase-projekt.
- **Motivering:** Utan crash-rapportering tar åtgärder lång tid efter lansering; en fallback gör att release-bygget aldrig blockeras av saknad Firebase-konfig.
- **Fil/komponent:** Ny fil [`CrashReporter.kt`](android/src/main/java/com/gearforge/app/CrashReporter.kt), [`android/build.gradle`](android/build.gradle) (valfria Firebase-beroenden via property), [`MainActivity.kt`](android/src/main/java/com/gearforge/app/MainActivity.kt).
- **Konkret ändring:** Implementera ett lager som registrerar `Thread.setDefaultUncaughtExceptionHandler` för lokal logg och initierar Crashlytics om `google-services.json`/property finns; lägg basala log-event (skärmbyten, export, köp).
- **Acceptanskriterium:** Release bygger utan Firebase-projekt; ett artificiellt krasch registreras i lokal logg och (om konfigurerad) i Crashlytics.
- **Beroenden:** Ingen; kan byggas ut med riktigt Firebase-projekt senare.

### 2. Export I/O saknar felhantering — "try/catch kring ExportManager.saveToDownloads, visa snackbar/dialog"

- **Beslut (ett enda):** Lägg try/catch kring [`ExportManager.saveToDownloads`](android/src/main/java/com/gearforge/app/ExportManager.kt:51) i exportflödet och visa snackbar/dialog med tydligt felmeddelande vid misslyckande.
- **Motivering:** Skrivfel eller delad fil kastar idag undantag rakt in i UI:t; fångade fel ger robusthet och tydlig feedback.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:367) (`ExportSheet`), [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt).
- **Konkret ändring:** Fånga `Exception` runt `saveToDownloads`-anropet; returnera resultat (boolean/meddelande) och visa snackbar/dialog vid fel, samt hantera `false`-retur från MediaStore.
- **Acceptanskriterium:** En export som misslyckas (t.ex. full disk/nekad behörighet) visar ett meddelande istället för att krascha.
- **Beroenden:** Ingen; kompletteras av punkt 18 (bakgrundstråd) för stora filer.

### 3. Extrema parametrar kan ge NaN/degenererad geometri — "Validera geometriska samband i GearSpec.setNumber + varna i UI"

- **Beslut (ett enda):** Lägg geometrisk validering i [`GearSpec.setNumber`](core/src/main/java/com/gearforge/core/GearSpec.kt:264) (t.ex. ring_teeth ≥ sun + 2·planet) och visa varning i UI:t när ett ogiltigt samband uppstår.
- **Motivering:** Extrema parametrar kan ge NaN eller degenererad mesh; validering i kärnan ger stabilare geometri och färre mystiska modeller.
- **Fil/komponent:** [`GearSpec.kt`](core/src/main/java/com/gearforge/core/GearSpec.kt:264), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt) (panelens resultatrad).
- **Konkret ändring:** I `setNumber` införs ett valideringssteg som klammar/korrigerar beroendeparametrar (planetär: ring ≥ sun + 2·planet) och exponerar en varningsstatus som UI:t läser för att visa varning.
- **Acceptanskriterium:** Ett enhetstest matar ogiltiga kombinationer och verifierar att ingen NaN/degenererad geometri genereras; UI:t visar varning vid ogiltigt samband.
- **Beroenden:** Beror på punkt 5 (tester för valideringen); kopplas till punkt 10 (klampningsvarning).

### 4. Editor-state försvinner vid processdöd/rotation — "Spara type+params i SavedStateHandle"

- **Beslut (ett enda):** Introducera en ViewModel med `SavedStateHandle` som sparar `type` + `params` (via [`SavedConfigs.toJson`](android/src/main/java/com/gearforge/app/SavedConfigs.kt:21)) och återställer editor-state vid processdöd/rotation.
- **Motivering:** Nuvarande `remember`-baserad state försvinner vid processdöd/rotation; `SavedStateHandle` bevarar arbetet över dessa gränser.
- **Fil/komponent:** Ny fil [`EditorViewModel.kt`](android/src/main/java/com/gearforge/app/EditorViewModel.kt), [`MainActivity.kt`](android/src/main/java/com/gearforge/app/MainActivity.kt), [`SavedConfigs.kt`](android/src/main/java/com/gearforge/app/SavedConfigs.kt).
- **Konkret ändring:** Lyft `type`, `params` och `stage` till en ViewModel som sparar dem i `SavedStateHandle` (params som JSON via `SavedConfigs.toJson/fromJson`), och låt `GearWorkspaceScreen` läsa/skriva ViewModel-state.
- **Acceptanskriterium:** Efter rotation eller processdöd återställs samma typ och parametrar i editorn utan förlorat arbete.
- **Beroenden:** Beror på `SavedConfigs` JSON-serialisering (befintlig).

### 5. Bristande enhetstester — "Bygg ut test-suite för matematik + roundtrip + filformat"

- **Beslut (ett enda):** Bygg ut core-test-suiten för matematik (GearCalculator/GearSpec), roundtrip (SavedConfigs/GearParams) och filformat (STL/3MF/SVG/DXF).
- **Motivering:** Få tester för kärnlogiken gör regressioner svåra att fånga; täckning av matematik, serialisering och exportformat ökar tryggheten.
- **Fil/komponent:** [`GearCoreTest.kt`](core/src/test/java/com/gearforge/core/GearCoreTest.kt) (utökas), nya testfiler för filformat.
- **Konkret ändring:** Lägg tester för geometriska formler (pitch/outer/root/base), roundtrip `toJson/fromJson`, och byte-utdata för STL/3MF/SVG/DXF (header/längd/parsbarhet).
- **Acceptanskriterium:** `gradle :core:test` kör grönt och nya tester täcker de angivna områdena.
- **Beroenden:** Ingen; stödjer punkt 3 och punkt 15.

### 6. GL-ytans livscykel — "Explicit surface-release på onPause + återskapa vid behov"

- **Beslut (ett enda):** Lägg explicit surface/EGL-release på `onPause` (och motsvarande återskapande på `onResume`) så TextureView-ytan stängs ner och återskapas korrekt.
- **Motivering:** TextureView/EGL-livscykeln vid paus/återupptag och rotation är inte fullt verifierad; explicit release förebygger minnesläckor och krascher.
- **Fil/komponent:** [`GearGLView.kt`](android/src/main/java/com/gearforge/app/GearGLView.kt:109), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt) (lifecycle-koppling).
- **Konkret ändring:** Koppla `GearGLView` till lifecycle (eller `DisposableEffect`) så `onSurfaceTextureDestroyed`/EGL-release sker vid `onPause` och rendertråden startas om vid `onResume`.
- **Acceptanskriterium:** Upprepade paus/återupptag- och rotationscykler lämnar inga GL-resurser kvar och visar viewporten korrekt.
- **Beroenden:** Beror på punkt 21 (minnesprofilering) för verifiering.

### 7. Ingen undo/redo — "Enkel param-stack (20 steg) för ångra"

- **Beslut (ett enda):** Implementera en param-stack med 20 steg för undo (och redo) i editorn.
- **Motivering:** I18n har "undo" men ingen implementation; en stack ger användarna trygghet att experimentera utan att fastna.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt), ny fil [`UndoStack.kt`](android/src/main/java/com/gearforge/app/UndoStack.kt).
- **Konkret ändring:** Pusha varje `GearParams`-förändring till en stack (max 20); lägg undo/redo-knappar i verktygsfältet som återställer föregående/nästa state.
- **Acceptanskriterium:** 20 parameterändringar kan ångras i omvänd ordning; redo återställer framåt; stacken töms korrekt vid typbyte.
- **Beroenden:** Beror på punkt 4 (state-hantering via ViewModel) för att stacken ska överleva rotation.

### 8. Ingen återställ-knapp per typ — "Reset i overflow-menyn"

- **Beslut (ett enda):** Lägg en "Reset"-post i overflow-menyn som återställer aktuell typ till `GearSpec.defaults(type)`.
- **Motivering:** Snabb återhämtning till standardvärdet minskar risken att användare fastnar i en dålig konfiguration.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:164) (overflow-menyn), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt).
- **Konkret ändring:** Lägg `DropdownMenuItem("Reset")` i overflow-menyn som sätter `params = GearSpec.defaults(type)` (och pushar till undo-stacken).
- **Acceptanskriterium:** "Reset" i overflow-menyn återställer alla fält för aktuell typ till standard.
- **Beroenden:** Beror på punkt 7 (undo-stack) för att reset ska kunna ångras.

---

## Fas 3 — Lokalisering & UX

### 9. Lokalisering brutet — "Dra alla strängar genom I18n (tr) i Landing/Wizard/Workspace/Controls"

- **Beslut (ett enda):** Dra alla hårdkodade strängar i Landing/Wizard/Workspace/Controls genom `I18n.t(lang, key)` och utöka en/sv-katalogerna.
- **Motivering:** Nya skärmar är hårdkodad engelska och språkvalet har ingen effekt; centralisering genom I18n gör att svenska faktiskt fungerar.
- **Fil/komponent:** [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt), [`LandingScreen.kt`](android/src/main/java/com/gearforge/app/LandingScreen.kt), [`GearWizard.kt`](android/src/main/java/com/gearforge/app/GearWizard.kt), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt), [`Controls.kt`](android/src/main/java/com/gearforge/app/Controls.kt).
- **Konkret ändring:** Ersätt alla literala UI-strängar med `I18n.t(lang, "nyckel")` och lägg saknade nycklar i en/sv; passera `lang` ner till varje skärm.
- **Acceptanskriterium:** Vid språkbyte till Svenska visas alla synliga UI-strängar på svenska (inga hårdkodade engelska kvar i de listade filerna).
- **Beroenden:** Ingen; ger bas för punkt 11, 12, 14 och 16.

### 10. Tyst klampning — "Visa gul varning 'justerades till min/max' vid klampning"

- **Beslut (ett enda):** Visa en gul varning "justerades till min/max" när ett inmatat värde klammas till intervallets gräns.
- **Motivering:** Tyst klampning får användare att tro att appen har en bugg; synlig feedback gör klampningen begriplig.
- **Fil/komponent:** [`Controls.kt`](android/src/main/java/com/gearforge/app/Controls.kt:64) (`NumberRow.apply`), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt).
- **Konkret ändring:** I `NumberRow.apply` upptäck när `parsed` ligger utanför `def.min/def.max` och visa en gul varningstext (t.ex. "Justerades till min/max") istället för att tyst coerce.
- **Acceptanskriterium:** Ett värde över max resulterar i klampning plus synlig gul varning; ett värde inom intervallet visar ingen varning.
- **Beroenden:** Kopplas till punkt 3 (validering) men är oberoende för ren min/max-klampning.

### 11. Avancerade fält förklarade men inte i sitt sammanhang — "Ordlista (tooltip/help-ikon) för gear-termer i panelen"

- **Beslut (ett enda):** Lägg en ordlista (tooltip/help-ikon) som förklarar avancerade gear-termer (Addendum/Dedendum/Profile shift) direkt i panelen.
- **Motivering:** Termerna förklaras inte i sitt sammanhang; en help-ikon bredvid avancerade fält ökar förståelsen utan att belamra layouten.
- **Fil/komponent:** [`Controls.kt`](android/src/main/java/com/gearforge/app/Controls.kt:139) (`HelpText`), [`GearSpec.kt`](core/src/main/java/com/gearforge/core/GearSpec.kt) (help-fält), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt).
- **Konkret ändring:** Lägg en info-ikon/tooltip som expanderar `ParamDef.help` (lokaliserad) för fält som addendum/dedendum/profile_shift, med längre förklaringar i I18n.
- **Acceptanskriterium:** Varje avancerat fält har en tillgänglig förklaring via help-ikon/tooltip på både svenska och engelska.
- **Beroenden:** Beror på punkt 9 (lokaliserade help-strängar).

### 12. Export-förhandsgranskning — "Visa filnamn + polycount/dimensioner i Export-dialogen"

- **Beslut (ett enda):** Visa filnamn, polycount och dimensioner i Export-dialogen innan sparande.
- **Motivering:** Användaren ser inte filnamn/mått innan sparande; en förhandsgranskning ger trygghet och färre felaktiga exporter.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:367) (`ExportSheet`), [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt).
- **Konkret ändring:** Beräkna och visa `baseName + ext`, antal trianglar (från `GearBuilder.merged(params)`) och ungefärliga dimensioner i `ExportSheet`.
- **Acceptanskriterium:** Export-dialogen visar filnamn, polycount och dimensioner som matchar det som faktiskt exporteras.
- **Beroenden:** Ingen; återanvänds av punkt 22 (gating i samma dialog).

### 13. Tillgänglighet — "Granska mot WCAG/Material-riktlinjer"

- **Beslut (ett enda):** Granska och åtgärda tillgänglighet (touch-mål, kontrast, contentDescription) mot WCAG/Material-riktlinjer.
- **Motivering:** Ikoner saknar contentDescription och vissa mål är små; åtgärder breddar målgruppen och minskar risk för avvisning.
- **Fil/komponent:** [`LandingScreen.kt`](android/src/main/java/com/gearforge/app/LandingScreen.kt), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt), [`GearWizard.kt`](android/src/main/java/com/gearforge/app/GearWizard.kt), [`Controls.kt`](android/src/main/java/com/gearforge/app/Controls.kt).
- **Konkret ändring:** Lägg `contentDescription` på alla ikonknappar, säkerställ minsta touch-mål (48dp) och kontrast (via Material-tema) samt testa med TalkBack.
- **Acceptanskriterium:** TalkBack läser upp alla ikonknappars beskrivningar och alla touch-mål uppfyller 48dp.
- **Beroenden:** Ingen; kan göras parallellt med punkt 9.

### 14. Presets saknar bild/förklaring — "Kort beskrivning per preset"

- **Beslut (ett enda):** Lägg en kort beskrivning per preset i PresetSheet (och utöka Preset-datamodellen).
- **Motivering:** Presets visas bara med namn; en beskrivning gör valet begripligt och ökar värdet.
- **Fil/komponent:** [`Presets.kt`](core/src/main/java/com/gearforge/core/Presets.kt:6), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:284) (`PresetSheet`), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt).
- **Konkret ändring:** Utöka `Preset` med `descriptionEn/descriptionSv` och visa den under namnet i `PresetSheet`.
- **Acceptanskriterium:** Varje preset visar en lokaliserad beskrivning i PresetSheet.
- **Beroenden:** Beror på punkt 9 (lokaliserade beskrivningar).

### 15. Språkområden i resultat — "Centralisera formatering via locale"

- **Beslut (ett enda):** Centralisera mm/in-formatering (komma vs punkt) via locale i en gemensam formatteringsfunktion.
- **Motivering:** Resultatvärden blandar komma och punkt; en gemensam locale-styrd formatering ger konsekvent output.
- **Fil/komponent:** Ny fil [`Format.kt`](core/src/main/java/com/gearforge/core/Format.kt) (eller i [`GearSpec.kt`](core/src/main/java/com/gearforge/core/GearSpec.kt:344)), [`Controls.kt`](android/src/main/java/com/gearforge/app/Controls.kt:60).
- **Konkret ändring:** Inför `formatNumber(value, decimals, locale)` som ersätter `String.format` i resultat och `fmtNum`, och använd aktuell locale (sv: komma, en: punkt).
- **Acceptanskriterium:** Samma värde formateras med komma på svenska och punkt på engelska, konsekvent överallt.
- **Beroenden:** Beror på punkt 9 (tillgång till aktuellt språk/locale).

### 16. Onboarding för Pro/ads-flöde saknas — "Tydlig prismodell i Settings"

- **Beslut (ett enda):** Visa en tydlig prismodell (vad som är gratis vs Pro) i Settings.
- **Motivering:** Användaren vet inte vad som är gratis; transparens krävs av policy och ökar förtroendet.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:400) (`SettingsDialog`), [`I18n.kt`](android/src/main/java/com/gearforge/app/I18n.kt).
- **Konkret ändring:** Lägg en sektion i Settings som förklarar gratisexporter (3), Pro-fördelar och annonsmodellen, med status (Pro aktiv/ej aktiv).
- **Acceptanskriterium:** Settings visar tydligt vad som ingår gratis och vad Pro ger.
- **Beroenden:** Beror på punkt 25 (gating-regler) och punkt 23 (Pro-flöde) för korrekt text.

---

## Fas 4 — Prestanda

### 17. Mesh byggs om vid varje parameter-ändring — "Debounce/avkastning + cache per params-hash"

- **Beslut (ett enda):** Debounca mesh-ombygget (kort fördröjning under slider-drag) och cacha per params-hash så identiska parametrar inte byggs om.
- **Motivering:** Mesh byggs om vid varje parameterändring vilket ger ryckigt drag; debounce + cache ger mjukare interaktion.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:101) (`LaunchedEffect(params)`), [`GearBuilder.kt`](core/src/main/java/com/gearforge/core/GearBuilder.kt).
- **Konkret ändring:** Ersätt direkt `GearBuilder.assembly(params)` med debounce (t.ex. `snapshotFlow`/delay) och en cache `Map<Int/GearParams-hash, GearAssembly>`.
- **Acceptanskriterium:** Snabb slider-drag utlöser färre ombyggen än antalet förändringar; att återgå till samma params återanvänder cache.
- **Beroenden:** Ingen; stödjer punkt 20 (render on demand).

### 18. Export av stora STL:er blockerar — "Kör på bakgrundstråd + progress + avbryt"

- **Beslut (ett enda):** Kör export på bakgrundstråd med progress och möjlighet att avbryta.
- **Motivering:** Export av stora STL:er blockerar UI-tråden; async-export med progress gör appen responsiv.
- **Fil/komponent:** [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt:367) (`ExportSheet`), [`ExportManager.kt`](android/src/main/java/com/gearforge/app/ExportManager.kt).
- **Konkret ändring:** Flytta `ExportManager.bytes` och `saveToDownloads` till `Dispatchers.IO`/coroutine med progress-indikator och avbryt-knapp.
- **Acceptanskriterium:** Export av stor STL blockerar inte UI-tråden (ANR-fri) och visar progress samt avbryt.
- **Beroenden:** Bygger vidare på punkt 2 (felhantering).

### 19. minifyEnabled false — "Aktivera R8 + testa release noggrant"

- **Beslut (ett enda):** Aktivera R8/minify i release och testa release-bygget noggrant.
- **Motivering:** `minifyEnabled false` ger större APK och ingen R8; aktivering minskar storleken och ökar skyddet.
- **Fil/komponent:** [`android/build.gradle`](android/build.gradle:17) (`buildTypes.release`), [`android/proguard-rules.pro`](android/proguard-rules.pro).
- **Konkret ändring:** Sätt `minifyEnabled true` och lägg nödvändiga keep-regler för libGDX/Compose/AdMob/Billing i proguard-rules.pro.
- **Acceptanskriterium:** `assembleRelease` bygger och appen kör (annonser, köp, GL, export) utan krasch med R8 aktiv; APK är mindre.
- **Beroenden:** Ingen; kräver manuell release-verifiering enligt punkt 30.

### 20. Render on demand ej fullt utnyttjad — "Verifiera att GL bara ritar vid förändring"

- **Beslut (ett enda):** Verifiera och säkerställ att GL endast ritar vid faktisk förändring (inga tomma frames).
- **Motivering:** Render on demand finns men utnyttjas inte fullt; onödiga frames drar batteri och prestanda.
- **Fil/komponent:** [`GearGLView.kt`](android/src/main/java/com/gearforge/app/GearGLView.kt:575) (`onDrawFrame`), [`GearWorkspace.kt`](android/src/main/java/com/gearforge/app/GearWorkspace.kt).
- **Konkret ändring:** Granska alla `requestRender()`-anrop så att frames endast utlöses vid state-förändring (parametrar, rotation, zoom), och ta bort eventuella loopar som ritar utan förändring.
- **Acceptanskriterium:** Vid stillastående vy sker inga kontinuerliga draw-anrop (verifieras via logg/profilering).
- **Beroenden:** Beror på punkt 17 (debounce/cache) för att minimera förändringar.

### 21. Minnesprofilering vid typbyte — "adb shell dumpsys meminfo + fixa läckor"

- **Beslut (ett enda):** Profilera minne med `adb shell dumpsys meminfo` vid upprepade typbyten och åtgärda upptäckta GL-resursläckor.
- **Motivering:** Långa sessioner kan ackumulera GL-resurser vid typbyte; profilering + fix ger stabilitet.
- **Fil/komponent:** [`GearGLView.kt`](android/src/main/java/com/gearforge/app/GearGLView.kt) (VBO-bufferhantering), dokumentation i plan.
- **Konkret ändring:** Kör meminfo-profilering över typbytescykler, identifiera läckor (t.ex. VBO:er som inte raderas vid `rebuildBuffers`) och frigör resurser korrekt.
- **Acceptanskriterium:** Minnet växer inte obegränsat vid upprepade typbyten (meminfo stabilt över tid).
- **Beroenden:** Beror på punkt 6 (GL-livscykel) och punkt 17 (cache).

---

## Fas 5 — Butiksredo

### 29. allowBackup=true + implicit cleartext — "allowBackup=false, usesCleartextTraffic=false"

- **Beslut (ett enda):** Sätt `android:allowBackup="false"` och `android:usesCleartextTraffic="false"` i `<application>`.
- **Motivering:** allowBackup=true och implicit cleartext ger säkerhetsvarningar; inaktivering minskar attackytan.
- **Fil/komponent:** [`AndroidManifest.xml`](android/src/main/AndroidManifest.xml:8) (`<application>`).
- **Konkret ändring:** Lägg `android:allowBackup="false"` och `android:usesCleartextTraffic="false"` i application-elementet.
- **Acceptanskriterium:** Merged manifest visar allowBackup=false och usesCleartextTraffic=false; release bygger utan varning.
- **Beroenden:** Ingen.

### 30. Ingen release-bygg/ikon/skärmdumpar/listing — "Signerad release, ikon, feature-grafik, EN+SV-listing"

- **Beslut (ett enda):** Skapa signerad release-bygg, appikon, feature-grafik, skärmdumpar och EN+SV-listing.
- **Motivering:** Bara debug, versionCode 1 och ingen ikon/listing gör appen icke-butiksklar; komplettering krävs för publicering.
- **Fil/komponent:** [`android/build.gradle`](android/build.gradle) (signing config), [`android/src/main/res`](android/src/main/res) (ikon/mipmap), ny listing/grafik.
- **Konkret ändring:** Konfigurera signering (keystore/property), bumpa versionCode, leverera ikon + feature-grafik + skärmdumpar, och skriv EN+SV-listing.
- **Acceptanskriterium:** En signerad release-APK/AAB byggs; ikon, grafik, skärmdumpar och EN+SV-listing finns.
- **Beroenden:** Beror på punkt 19 (R8) och punkt 24 (riktiga ID) för färdig release.

### 32. targetSdk-plan — "Planera uppgradering + testa"

- **Beslut (ett enda):** Behåll targetSdk 35 för denna release och dokumentera uppgraderingsvägen till 36 (krävs aug 2026).
- **Motivering:** 35 gäller nu men 36 krävs aug 2026; att hålla 35 nu och dokumentera vägen ger tid att testa 36 utan att blockera lansering.
- **Fil/komponent:** [`android/build.gradle`](android/build.gradle:12) (`targetSdk`), dokumentation i plan.
- **Konkret ändring:** Behåll `targetSdk 35`, dokumentera steg för 36 (compileSdk-bump, beteendeförändringar, test) och planera uppgradering.
- **Acceptanskriterium:** Planen anger targetSdk 35 nu och konkreta steg för 36-uppgradering före aug 2026.
- **Beroenden:** Ingen; följer upp punkt 30 (release).
