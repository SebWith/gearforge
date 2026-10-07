---
name: gearforge-core-geometry
description: "Kugghjulsmatematik och den parametriska modellen i GearForge:s core-modul. Use when: ändra eller felsöka en geometriformel; lägga till en ny kugghjulstyp; arbeta med GearParams/GearSpec/GearCalculator/GearProfiles/GearBuilder; validering av fysikaliska samband; profilförskjutning, undercut, evolvent, kuggantal; mesh-bygge och loft. Trigger words: gear math, involute, module, pressure angle, profile shift, undercut, planetary, helical, bevel, worm, planetary ring constraint, GearSpec, GearCalculator, GearBuilder, GearProfiles."
---

# GearForge — kärngeometri

Domänkunskap om kugghjulsmatematiken i `core/`. Läs också
`.github/instructions/core-kotlin.instructions.md` (den gäller automatiskt för
`core/**/*.kt`) och `.github/instructions/export-writers.instructions.md` om du rör
en skrivare.

## Filkarta — vem äger vad

| Fil | Ansvar |
|---|---|
| `GearModel.kt` | Datamodellen: `GearType` (14), `ToothProfile`, `BoreType`, `UnitSystem`, `PrecisionLevel`, `BoreSpec`, `ToothOverride`, `GearParams` |
| `GearSpec.kt` | **Navet.** `ParamDef`, `ParamGroup`, `FieldKind`, `ParamScope`, `GearWarning`; `defaults()`, `fields()`, `getNumber`/`setNumber`, `getChoice`/`setChoice`, `getBool`/`setBool`, `validate()`, `results()` |
| `GearCalculator.kt` | Grundformler: delnings-, ytter-, fot- och basdiameter, axelavstånd, utväxling, enhetskonvertering |
| `GearProfiles.kt` | Kuggflanksprofiler: evolvent, cykloidal, rak. Genererar 2D-punkter per kugg |
| `GearBuilder.kt` | Sätter ihop profil + kropp till en `Mesh`; `assembly()` är huvudingången |
| `HubBuilder.kt` | Nav, borrhål, kilspår, D-snitt, sexkant, fyrkant |
| `Bore.kt` | Borrhålsgeometri och passningsberäkning |
| `CompoundGearBuilder.kt` | Tvåstegs (dubbelkugghjul) med distanshylsa |
| `Belt.kt` | Kuggremsdrift: remskivor, spännrullar, remlängd, omslutning |
| `Loft.kt` | Sammanfogning mellan två profiler — **här uppstod non-manifold-buggen** |
| `MeshBuilder.kt`, `MeshOps.kt`, `Triangulate.kt`, `Vec.kt` | Mesh-primitiver, normaler, triangulering |
| `GearAnalysis.kt` | Vikt, tröghetsmoment, effektivt glapp |
| `PrintAdvisor.kt` | Utskriftsråd och minsta väggtjocklek |
| `Presets.kt` | 3:1, 5:1, 7:1-planetsteg m.m. |
| `Expr.kt` | Uttrycksutvärdering för beräknade fält |

## Grundformler (modul `m`, kuggantal `z`, tryckvinkel `α`)

| Storhet | Formel |
|---|---|
| Delningsdiameter | $d = m \cdot z$ |
| Ytterdiameter | $d_a = d + 2m(1 + x)$ |
| Fotdiameter | $d_f = d - 2m(1{,}25 - x)$ |
| Basdiameter | $d_b = d \cdot \cos\alpha$ |
| Axelavstånd | $a = \frac{m(z_1 + z_2)}{2}$ |
| Utväxling | $i = z_2 / z_1$ |
| Kuggtjocklek (delningscirkeln) | $s = \frac{\pi m}{2} + 2xm\tan\alpha$ |
| Undercut-gräns | $z_{min} = 2 / \sin^2\alpha$ |

`x` är profilförskjutningskoefficienten (`profileShift`). Tum läge använder
diametral pitch: $m = 25{,}4 / DP$ — konverteringen ligger i `GearCalculator`, inte
i UI:t.

## Den parametriska modellen

`GearParams` är en `data class` och **hash-code används som cache-nyckel** i
`GearWorkspace.kt`. Konsekvenser:

- Lägger du till ett fält som påverkar geometrin måste det vara en `val` i
  konstruktorn. Ett `var` eller externt muterbart tillstånd gör att cachen inte
  invalideras och att användaren ser gammal geometri.
- Standardvärdet för ett nytt fält får inte ändra befintliga typers geometri utan
  att `defaults()` uppdateras för alla 14 typer.

`GearSpec.defaults(type)` ger startvärden per typ. `GearSpec.fields(p)` ger den
UI-drivna parameterlistan (`ParamDef` per fält, filtrerad per typ). **Fälten är
data, inte UI** — UI:t i `android/` renderar dem generiskt via `Controls.kt`.

Så här hänger det ihop när ett värde ändras:

```
användaren skriver i NumberRow (android/)
  -> GearSpec.setNumber(params, key, v)      // klampar till min/max
  -> ny GearParams
  -> GearSpec.validate(params)               // List<GearWarning>
  -> GearBuilder.assembly(params)            // Mesh  (debounce + cache)
  -> GearGLView.rebuildBuffers + requestRender
```

## Att lägga till en ny kugghjulstyp

Detta är den vanligaste större ändringen. Ordningen spelar roll — hoppar du över ett
steg syns felet först i appen.

1. **`GearModel.kt`** — lägg till värdet i `enum class GearType`.
2. **`GearSpec.kt` → `defaults(type)`** — startvärden. En ny typ utan gren får
   `SPUR`-värden tyst, vilket ger en vilseledande app.
3. **`GearSpec.kt` → `fields(p)`** — lägg typen i rätt `when`-gren och peka ut
   vilka `ParamDef` som gäller. Återanvänd befintliga fält (`module`, `teeth`,
   `pressureAngleDeg`, `bore`, …) i stället för att hitta på nya.
4. **`GearSpec.kt` → `getNumber`/`setNumber`/`getChoice`/`getBool`** — om nya
   nycklar införs måste de hanteras i **både** läs- och skrivvägen. Missar du
   `getNumber` visas 0 i UI:t utan felmeddelande.
5. **`GearSpec.kt` → `validate(p)`** — fysikaliska samband som `ERROR`. Lägg en
   `validation_*`-nyckel i **både** `en` och `sv` i `I18n.kt`.
6. **`GearSpec.kt` → `results(p)`** — vilka beräknade värden som visas.
7. **`GearCalculator.kt`** — nya formler. Håll dem fria från `GearParams` där det
   går, så de blir lätta att testa.
8. **`GearProfiles.kt` / `GearBuilder.kt`** — profilgenerering och mesh.
   Återanvänd `MeshBuilder`/`Triangulate` i stället för att bygga trianglar för hand.
9. **`Presets.kt`** — minst en preset av den nya typen.
10. **`I18n.kt`** — UI-namn (`I18n.t`) och alla `validation_*`-koder, i båda språken.
11. **Test** — lägg typen i `AllGearTypesMatrixTest` (kör alla typer × precisioner)
    och lägg till ett specifikt test för de nya formlerna.
12. **`docs/`** — om typen är ny för användaren, uppdatera relevant designdokument.

## Vanliga fel och vad de beror på

| Symtom | Trolig orsak |
|---|---|
| `NaN` i mesh eller `results()` | Division med `sin(0)`/`cos(90°)`, eller `acos`-argument utanför `[-1, 1]` |
| Alla fält visar 0 i UI:t | Ny nyckel saknas i `getNumber` |
| Varning visas på engelska i svensk vy | `validation_*`-nyckel saknas i `sv` |
| Geometrin uppdateras inte vid parameterändring | Cachenyckeln (`GearParams`-hash) invalideras inte |
| Kuggen ser "avhuggen" ut vid foten | För liten `rootFilletCoef` eller undercut — kontrollera $z_{min}$ |
| Intermittent testfall | Iteration över `HashMap`/`HashSet` där ordningen påverkar utdata |
| Mesh läcker / non-manifold kant | `Loft.kt` eller `CompoundGearBuilder.kt` — kontrollera att ringen sluts |

### STEP serialization and CAD checks

Kotlin `"#$pointIds[index]"` interpolates the array identity, not its element;
use `"#${pointIds[index]}"`. STEP also requires REAL decimal points,
`DIRECTION('',(x,y,z))`, and `VECTOR('',#direction,magnitude)`. Shared edges need
per-face traversal orientation; face reference axes must lie in their planes.
Use PRODUCT_CONTEXT and PRODUCT_DEFINITION_SHAPE / SHAPE_DEFINITION_REPRESENTATION
for the product-to-BREP chain, and a separate closed shell per connected body.
Keep source-level regressions: OpenCascade can silently split a disconnected
shell into valid solids on import, so a successful CAD round-trip alone does not
prove the original topology was valid. See the STEP tests in GearCoreTest and
the real fixtures in AssetExportTest.

## Fysikaliska samband som måste vara `ERROR`

- **Planetväxel:** `ring_teeth ≥ sun + 2·planet`, annars går inte planeterna i ingrepp.
- **Undercut:** `z < 2/sin²α` utan positiv profilförskjutning.
- **Top-land:** topplandsbredden måste vara positiv efter glapp.
- **Kuggöverlapp:** kuggarna får inte överlappa vid foten.
- **Kuggrem:** minst 8 kuggar per remskiva, remmen bredare än delningen.
- **Anliggning:** effektivt glapp får inte bli negativt.

## En kropp, en yta: modulkonventioner per typ

Fyra fel i samma familj har kostat tid: **ett rapporterat tal som beskriver en annan kropp
än den som ritas.** Ingen av dem fångades av ett formeltest, eftersom formeln var
internkonsistent — bara fel. Regeln är därför: jämför talet med **meshen**, inte med sin
egen härledning (`MeasureTest.theReportedOuterDiameterIsTheCircleTheMeshReaches`).

| Familj | Var profilen genereras | Fällan |
|---|---|---|
| Helical **och** screw gear | Tvärmodulen `m_t = m_n / cos β`. Användarens `module` är den **normala**. | Endast `HELICAL` konverterades, så screw gear rapporterade 22,0 mm på en kropp som är 31,1 mm över tipparna (β=45°, m=1, z=20) — och 2D-exporten ritade en mindre kugg än STL:en |
| Bevel / hypoid | Back-konen: `z_v = z / cos δ`, sedan skala `cos δ` så delningsradien blir `m·z/2` | Tipp- och fotcirklarna ligger på `m·z/2 ± m·cos δ` och finns **bara på framsidan** (z = 0). Att projicera ankaret i midplanet lade ledlinjen ~0,6 mm in i materialet |
| Internal ring | Ringen är en fälg: `PlanarShape(circle(ringOuterRadius), listOf(internalRingOutline))` | `shape()` svarade med en **yttre** kuggprofil; "Outer dia." var fotcirkeln, `max(2 mm, 2·m)` innanför delens verkliga kant |
| Planetary | `Zr = Zs + 2·Zp` — ett **ingreppskrav**, inte ett fältvärde. Byggaren skriver över avvikande `ring_teeth` (med `validate()`-varning) | Panelen rapporterade fältet, så ringens delningscirkel och HUD:ens ledlinje beskrev en ring som inte fanns i scenen |

- **En definition per konvention:** `GearBuilder.profileParams` (planet kroppen genereras
  i), `GearSpec.transverseModule` (samma sak för rapporterade diametrar),
  `GearBuilder.ringOuterRadius`, `GearCalculator.planetaryRingTeeth`. `GearBuilder.shape`
  och `GearBuilder.mesh` måste gå genom samma väg — annars beskriver SVG/DXF,
  scrub-förhandsvisningen och plockningen en annan kropp än vyn.
- **Höjden är en del av ett ankare.** `Measure.anchorRadiusMm` är radien; `anchorZMm` är
  höjden där cirkeln finns (`null` = midplanet). En konisk kropp har sin tippcirkel på
  framsidan, en rak kropp har den överallt.
- **Kontrollprovet:** `MeasureTest.theTwoDimensionalProfileIsTheSectionTheSolidIsLoftedFrom`
  jämför `shape(p).outer` högsta radie med `mesh(p)`:s, och
  `everyBodyOfAnAssemblyIsMeasured` kräver att varje kropp i en assembly har ett tal.

## Verifiera

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :core:test --tests "*SpurProfileTest*"
.\gradlew.bat :core:test --tests "*AllGearTypesMatrixTest*"
```

`:core:test` kräver ingen emulator — det är hela poängen med att hålla matematiken i
`core`. Kör den innan du påstår att en geometriändring är klar.
