---
applyTo: "android/**/*.kt"
---

# `android` — Compose-UI + libGDX GL

Android-modulen äger allt plattformsspecifikt: UI, OpenGL-renderaren, annonser,
köp, samtycke och skrivning till disk.

## Strängar och lokalisering

**All användarsynlig text går genom `I18n.t(lang, "nyckel")`.**

```kotlin
// Rätt
Text(I18n.t(lang, "export"))
// Fel — syns bara i den engelska vyn, ingen varning i kompilering
Text("Export")
```

- Lägg till nyckeln i **både** `en` och `sv` i `I18n.kt`. En nyckel som bara finns
  i `en` faller tyst tillbaka på engelska i den svenska vyn.
- `lang` måste passas ned till varje skärm. Glömmer du parametern får skärmen
  standardvärdet och språkbytet "slutar fungera" utan att något kraschar.
- Använd `{0}`, `{1}`-platshållare — `t()` ersätter dem via `args`. Antalet
  platshållare måste matcha antalet argument i **alla** språk.
- Lokala `Lang`-värden får inte hårdkodas i en skärm; de kommer från
  `SettingsStore`/`MainActivity`.

Kör `py tools/i18n_audit.py` efter varje ändring i `I18n.kt` — den fäller
EN/SV-avvikelser.

## Tillgänglighet (ACTION_PLAN punkt 13)

- Varje `IconButton`/`Icon` som bär betydelse behöver `contentDescription`.
  Dekorativa ikoner får `contentDescription = null`.
- Minsta touch-mål: **48 dp**. Använd `Modifier.size(48.dp)` eller
  `minimumInteractiveComponentSize()`.
- Text mot bakgrund ska uppfylla WCAG AA (4.5:1 för brödtext). Färger kommer från
  `AppTheme.kt` — lägg inte in egna hex-värden i en skärm.
- Testa med TalkBack innan du kallar en skärm klar.

Regler från granskningen 2026-10-01 (`build/audit/a11y-20261001/REPORT.md`) — varje rad
är ett fel som fanns i appen:

- **Bakgrunden kommer från `AppTheme`.** Fönstret är genomskinligt; `AppTheme` lägger en
  `Surface(background)` runt allt. Utan den ritade guiden svart text på svart (1,0:1).
- **Ett textfält utan M3-`label` får `contentDescription = <synlig etikett>`** (som M3:s egen
  sökfält). En `Slider` får `contentDescription` och `stateDescription` med värde och enhet —
  annars läses "7 procent" utan namn.
- **`SelectChip` i stället för `FilterChip`** (bockmarkering; fyllningen ensam mätte 1,1:1).
- **`DialogTitle(...)` som `AlertDialog`-titel**; avsnittsrubriker får `semantics { heading() }`.
- **Switch-rader:** `Modifier.toggleable(role = Role.Switch)` på raden och
  `Switch(onCheckedChange = null)` — en fristående Switch läses "På, brytare" utan namn.
- **Expanderbart** (avsnitt, hjälp): `stateDescription` Expanderad/Hopfälld och en
  `onClickLabel`; ikonen är dekorativ (`contentDescription = null`).
- **Ändrat värde eller fel som syns** (klämning, valideringsfel) får
  `liveRegion = LiveRegionMode.Polite`.
- **Textfält med `onDone` som rensar fokus** får även `Modifier.commitOnEnter { … }`. Annars
  flyttar `clearFocus()` fokus till skärmens första element vid ett fysiskt tangentbord, och
  Enter-tangentens key-up klickar på det (Geometri-rubriken fälldes ihop).
- **Aldrig fast `.height()` på knappar med text** — `heightIn(min = …)`. Vid font_scale 2.0
  klipps annars etiketten.
- **En helskärm paddar för alla systemfält** (`systemBarsPadding()`), inte bara statusfältet.
  Med 3-knappsnavigering (API 26-emulatorn) låg guidens knappar under navigationsfältet.
- **Paneler över 3D-vyn** använder `VIEWPORT_PANEL_ALPHA` (kontrasten gäller över både svart
  och vit pixel, `ThemeContrastTest`).
- **3D-vyn är en `TextureView`** — den får `contentDescription` och en åtgärd per
  `CameraStep` (`ViewCompat.addAccessibilityAction`), annars hoppar skärmläsaren över modellen.
  En gest med två fingrar (nyp, panorering) kräver en synlig knapp med ett finger
  (WCAG 2.5.1) — därför zoomknapparna i 3D-vyns nedre rad. Panorering saknar den fortfarande
  (rapportens restrisk 4).
- Kör `py tools/a11y_audit.py --source android/src/main/java` — ska ge 0 fynd.

## Compose-konventioner i detta repo

- Skärmar är `@Composable`-funktioner i `LandingScreen.kt`, `GearWizard.kt`,
  `GearWorkspace.kt`, `Controls.kt`. Kontroller återanvänds från `Controls.kt`
  (`NumberRow`, `HelpText`, m.fl.) — bygg inte en egen variant av en kontroll som
  redan finns.
- Tillstånd som ska överleva rotation/processdöd hör i `EditorViewModel.kt`
  (`SavedStateHandle`), inte i `remember`.
- Undo/redo går via `UndoStack`. En ny parameterändring som ska kunna ångras måste
  pushas dit.
- Panelsektioner registreras i `SectionExpansion` — en ny `ParamGroup` ska läggas
  till där, annars får den inget kollapsbart huvud.

## GL-renderaren (`GearGLView.kt`)

Filen är 1200 rader, har inga enhetstester och är den mest riskfyllda i modulen.

- **Render on demand:** rita bara vid faktisk förändring. Varje nytt tillstånd som
  påverkar bilden **måste** följas av `requestRender()`. En loop som ritar utan
  förändring drar batteri och bryter kontraktet (ACTION_PLAN punkt 20).
- **Resursägande:** varje `IntBuffer`/VBO som skapas i `rebuildBuffers` ska frigöras
  på samma ställe. Läckor syns bara över tid — verifiera med
  `adb shell dumpsys meminfo com.gearforge.geargenerator` över upprepade typbyten.
- **Livscykel:** ytan ska släppas på `onPause` och återskapas på `onResume`
  (ACTION_PLAN punkt 6). Lägg inte GL-arbete i en `remember`-block som överlever
  ytans död.
- Allt GL-arbete sker på rendertråden. Rör inte GL-resurser från UI-tråden.

## Annonser, köp och samtycke

- **UMP före annonser.** `ConsentManager` körs före `AdManager.init` i
  `MainActivity.onCreate`. Ingen annons får laddas innan samtyckesflödet är klart.
  Varje steg är guardat så ett saknat UMP-svar inte blockerar appen — behåll det.
- **Rewarded är enda annonsformatet** (beslut i `MONETIZATION_CONFIG.md` avsnitt 2).
  Lägg inte in banner eller interstitial utan att beslutet omprövas skriftligt.
- **Billing:** `PURCHASED` INAPP ska `acknowledgePurchase`as. `PENDING` får **inte**
  sätta `isPro = true`. Restore går via `queryPurchasesAsync`.
- **Gating:** Pro → obegränsat. Icke-Pro med `freeAdvancedExports > 0` → exportera
  och dekrementera. Icke-Pro med 0 → rewarded krävs. `highQuality` är Pro-låst och
  tvingas till `STANDARD` precision för icke-Pro.
- **Testa aldrig med produktions-ID:n i debug.** Googles test-ID:n är default; det
  är avsiktligt.

## Felhantering

- Export till disk kan misslyckas (full disk, nekad behörighet).
  `saveToDownloads` returnerar `Result` — fånga den och visa ett meddelande i
  stället för att låta undantaget nå UI:t (ACTION_PLAN punkt 2).
- Tunga operationer (mesh, export) hör på `Dispatchers.IO`/`Default`, aldrig på
  huvudtråden. Stora STL-exporter ska visa progress och kunna avbrytas
  (ACTION_PLAN punkt 18).
- Kraschar något oväntat ska `CrashReporting` fånga det — inte en tom `catch`.

## Release

Rör inte AdMob-ID, `versionCode`, `targetSdk` eller signeringskonfigurationen utan
att gå via `android-release-guard`-skillen. Bumpa `versionCode` vid varje
uppladdning — Play accepterar aldrig ett återanvänt värde.
