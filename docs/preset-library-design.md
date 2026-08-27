# Preset Library — Design & Rationale

Denna sida visas direkt efter att användaren valt en kugghjulstyp i guiden
(`GearWizard`). Målet är att användaren aldrig möts av tomma parameterfält, utan i
stället av ett strukturerat bibliotek av rekommenderade presets med ett tydligt
separerat "Custom"-alternativ.

## Flöde

```
Välj typ  →  Preset-bibliotek  →  3D-redigerare (preset vald)
                          └──→  Custom → full parameterredigerare → 3D-redigerare
```

- **Steg 1 – Typ:** oförändrat rutnät av kugghjulstyper.
- **Steg 2 – Preset-bibliotek:** bred listform med en 3D-miniatyr, namn, typisk
  användning, beskrivning och metadata per preset, plus ett visuellt avskilt
  Custom-kort längst ned.
- **Steg 3 – Custom:** förifylld med typens standardvärden, alla parametrar synliga,
  redigerbara och grupperade (återanvänder `SettingsPanel`), med live 3D-förhandsvisning
  som debounce-uppdateras och en "Fortsätt till 3D"-knapp.

## Varför denna lösning

Alternativen som övervägdes:

1. **Öppna parameterfält direkt** (tidigare lösning) — förkastad: överväldigande för
   nya användare och ger ingen vägledning.
2. **En platt preset-lista utan metadata** — förkastad: svag överblick och ingen
   möjlighet att jämföra konfigurationer vid en blick.
3. **Preset-bibliotek med 3D-miniatyrer + separat custom-väg** (vald) — bäst balans
   mellan tydlighet, överblick, återkoppling och användarens kontroll. Rekommenderade
   presets ger en säker start; custom-vägen behåller full frihet.

## Preset-rationale (per typ)

Presets är byggda på branschstandarder (ISO 54/21771 föredragna moduler, 20° standard
tryckvinkel, standard planetväxelgeometri) och på praktisk användning:

| Typ | Presets | Motivering |
|---|---|---|
| Spur | 0.5 / 1.0 / 2.0 modul | Fin (instrument), standard (allround), grov (3D-utskrift/robot). |
| Helical | 15°/20°/30° helix | Lägre ljud vid större vinkel; grov modul för moment. |
| Bevel | 45° miter 1:1, 45° kompakt, 60° | Standard rätvinklig 1:1; bredare kon för styrka. |
| Rack | M1/M1.5/M2 | Precisionslinjär, CNC-axel, grov linjär. |
| Planetary | 3:1, 5:1, 7:1 | Ring = sol + 2·planet och (sol+ring)/n heltal ger jämnt fördelade planethjul. |
| Worm | 30:1, 15:1, 40:1 | Utväxling = hjulkuggar / ingångar; 40:1 vanligen självhämmande. |
| Internal ring | 44/36/60 kuggar | Matchar planetväxelns ring, kompakt resp. stor. |
| Hypoid | 35°/45°/40° kon | Fordonsaxel, kompakt, tung drift. |
| Cycloidal | 0.5/1.0/0.5 modul | Urverk, precisionsreducerare, finmekanik. |
| Harmonic | 160/200/120 kuggar | Hög utväxling, ultraprecision, kompakt. |
| Face gear | 40/30 kuggar, M1.5 | Standard rätvinklig, kompakt, grov. |
| Screw gear | 45°/30°/45° helix | 1:1 och 2:1 korsade axlar, grov. |

Varje preset är tekniskt giltig enligt `GearSpec.validate` och `GearBuilder.assembly`.

## Interaktionsdetaljer

- **Tangentbord/fokusordning:** listan är en `LazyVerticalGrid`; kort är fokuserbara
  via `Modifier.clickable` (rippel + tillgänglighetssemantik) och läses upp med namn +
  metadata. Tab-ordningen följer visuell ordning (miniatyr → namn → metadata → custom).
- **Hover/tryck:** Material3-kort med rippel vid tryck; custom-kortet har `primaryContainer`
  som bakgrund för tydlig separation.
- **Laddning:** 3D-miniatyrerna rastreras asynkront (`Dispatchers.Default`) och cachas per
  (params-hash, färg); tom yta visas tills bilden är klar. Presetdatan är i minnet och kräver
  ingen nätverksladdning.
- **Felhantering/återställning:** `GearPreviewRenderer` fångar mesh-byggfel (`runCatching`)
  och visar tom miniatyr i stället för att krascha. Custom-sidan visar valideringsvarningar
  från `GearSpec.validate` och klämmer numeriska värden till giltigt intervall.
- **Jämföra presets:** identiska kort och konsekventa metadata-chips (modul, kuggar,
  tryckvinkel, material) gör presets direkt jämförbara.
- **Ångra/återgå:** bakåt från biblioteket går till typval; bakåt från custom går tillbaka
  till biblioteket utan att förlora de inställda värdena (Compose-tillstånd behålls medan
  guiden är öppen). Efter val finns ångra/historik i 3D-redigeraren.
- **Responsivt:** `GridCells.Adaptive(minSize = 320.dp)` ger en kolumn på telefoner och
  två eller fler på surfplattor/landskap utan att tappa hierarkin.

## Implementering

- `core/.../Presets.kt` — per-typ preset-katalog (3 presets × 12 typer).
- `android/.../GearPreview3D.kt` — params-baserad 3D-rasterisering med cache.
- `android/.../GearWizard.kt` — trestegsguide: `PresetLibraryPage`, `PresetCard`,
  `CustomCard`, `CustomParamsPage`.
- `android/.../GearWorkspace.kt` — `SettingsPanel` gjordes återanvändbar; redigerarens
  `PresetSheet` filtrerar nu per typ.
- `android/.../I18n.kt` — nya nycklar för steg 1–3, preset-rubrik/hint och custom.
