# Parametriskt kugghjulssystem — utökad designspecifikation

> Detta dokument specificerar hur GearForge:s parametriska kugghjulsgenerator ska
> vidareutvecklas med: (1) asymmetriskt nav/boss/krage med separat vänster/höger
> utskjutning, (2) stoppskruv (grub screw), (3) per-kugge-parametrar med
> asymmetriska flanker, (4) ett brett lager av tilläggsparametrar, (5) ett
> parameterstyrt kuggremstransmissionssystem och (6) den omgivande arkitekturen för
> UI, regenerering, validering, analys och export.
>
> Alla namn, enheter och konventioner bygger vidare på den befintliga kodbasen
> (`core/…/GearParams.kt`, `GearSpec.kt`, `GearCalculator.kt`, `GearProfiles.kt`,
> `Bore.kt`, `GearBuilder.kt`, `PrintAdvisor.kt` samt `android/…/SettingsPanel.kt`,
> `GearPreview3D.kt` och export-skribenterna `StlWriter`/`ThreeMfWriter`/`SvgWriter`/
> `DxfWriter`).

---

## 1. Syfte och avgränsning

Målet är att varje kugghjul ska kunna beskrivas fullständigt, asymmetriskt och
tillverkningsklart genom en deklarativ parameteruppsättning som:

- är **tydligt namngiven** (snake_case, som dagens `hubDiameter`, `profileShift`),
- har **enhet, giltigt intervall, standardvärde, beroenden och formler**,
- kan anges **globalt, per kugge eller som lokala undantag**,
- **valideras** innan geometri genereras (inga tysta degenererade former),
- kan **regenereras inkrementellt** och **exporteras** till CAD/ritning/3D-utskrift.

Dokumentet är en specifikation; implementeringsordningen beskrivs i §14.

---

## 2. Nuläge (befintlig parameterbas)

Befintlig modell (sammanfattad):

| Område | Parametrar |
|---|---|
| Grundgeometri | `module`, `teeth`, `pressureAngleDeg`, `thickness`, `backlash`, `profileShift`, `helixAngleDeg`, `toothProfile` |
| Axelhål | `bore.type` (ROUND/D_CUT/KEYWAY/HEX), `diameter`, `dCutFlatOffset`, `keywayWidth`, `keywayDepth`, `hexAcrossFlats` |
| Nav | `hubDiameter`, `hubLength` (ett enda, centrerat nav) |
| Precision | `precision` (HOBBY/STANDARD/HIGH) → flank-/segmentsteg |
| Typ-specifikt | `coneAngleDeg`, `pitchConeDeg`, `mountingDistance`, `pinionTeeth`, `rackLength`, `planetCount`, `planetTeeth`, `ringTeeth`, `wormStarts`, `wheelTeeth` |
| Material/Last | `material`, `surfaceFinishUm`, `toleranceClass`, `lubrication`, `loadNm`, `speedRpm`, `lifetimeHours`, `safetyFactor` |

Härledda värden finns i `GearCalculator` (`pitchRadius = m·z/2`, `outerRadius = m·(z+2)/2`,
`rootRadius = m·(z−2.5)/2`, `baseRadius = rp·cos(α)`, `centerDistance = m·(zA+zB)/2`,
`planetaryRatioFixedRing` m.fl.). Dessa återanvänds som formelgrund i §4–§13.

Befintlig `GearSpec.validate` producerar varningar (`module`, `ring_teeth`,
`planet_overlap`, `helix_angle`, `bore`). Specifikationen nedan utökar denna med
hårda fel (blockerande) och mjuka varningar (advisory).

---

## 3. Nav / boss / krage — asymmetrisk radiell utskjutning

Terminologi (svenska ↔ engelska): den cylindriska förlängningen kring centrumhålet
kallas **nav** (svenska), **boss** eller **collar/krage** (engelska). Skruven som
låser mot axeln kallas **stoppskruv**, **låsskruv** eller **grub screw/set screw**.

### 3.1 Parametrar

| Parameter | Enhet | Intervall | Default | Beskrivning |
|---|---|---|---|---|
| `hubDiameter` | mm | `bore.diameter + 2 … outerDiameter` | `2.2·bore.diameter` | Ytterdiameter på navet (ersätter dagens enda `hubDiameter`; kläms alltid ≥ borr + 2 mm gods). |
| `hubLeftLength` | mm | `0 … 50` | `0` | Navets utskjutning **vänster** om kuggkroppens vänstra plan (negativ axelriktning). |
| `hubRightLength` | mm | `0 … 50` | `0` | Navets utskjutning **höger** om kuggkroppens högra plan. |
| `hubChamfer` | mm | `0 … min(hubDiameter, utskjutning)/2` | `0` | 45° fasning av navets yttre kant (radieavstånd). |
| `hubFillet` | mm | `0 … (hubDiameter−bore.diameter)/2` | `0` | Avrundning i övergången nav ↔ kuggkropp (hållfasthetsgynnsam). |
| `hubDraftAngle` | ° | `0 … 5` | `0` | Släppningsvinkel för pressgjutning/plastformning (konisk krage). |

**Asymmetri** uppnås genom att `hubLeftLength ≠ hubRightLength`; dagens enda
`hubLength` tolkas bakåtkompatibelt som `hubLeftLength = hubRightLength = hubLength/2`.

### 3.2 Geometrisk effekt

- Navet är en cylinder som **förenas** med kuggkroppen vid övergångsradien
  `hubFillet` (eller tangent) och fasas vid `hubChamfer`.
- Centrumhålet (`BoreSpec`) går genom hela navet; navet måste ha minsta gods
  `hubDiameter ≥ bore.diameter + 2·minWall` (minsta godstjocklek per material).
- Navlängderna **ökar totalbredden** och därmed `GearAssembly`-bredd och
  kollisionsgränser i planetväxel/snäckmontering (se §7).

### 3.3 Villkor och konflikthantering

- Hårt fel: `hubDiameter ≤ bore.diameter` → kläms till `bore.diameter + 2·minWall`.
- Hårt fel: `hubChamfer > min(hubDiameter/2, aktuell utskjutning)` → kläms.
- Mjuk varning: navet täcker kuggroten (`hubDiameter > rootDiameter`) → risk att
  kuggar skärs bort; föreslå ekrar/lättningshål istället (§8).

---

## 4. Stoppskruv / låsskruv / grub screw

### 4.1 Parametrar

| Parameter | Enhet | Intervall | Default | Beskrivning |
|---|---|---|---|---|
| `setScrewCount` | st | `0 … 2` | `0` | Antal radiella stoppskruvar (0 = avstängt). |
| `setScrewThread` | — | `M2.5, M3, M4, M5, M6` | `M3` | Gängstorlek (ISO metrisk grovgänga). |
| `setScrewAngle` | ° | `0 … 360` | `90` | Vinkelläge för skruv #1 kring axeln. |
| `setScrewAngle2` | ° | `0 … 360` | `270` | Vinkelläge för skruv #2 (endast om count = 2). |
| `setScrewAxialOffset` | mm | `±hubLength` | `0` | Skruvens axiella läge relativt navets mitt. |
| `setScrewDepth` | mm | `0 … (hubDiameter−bore.diameter)` | `0` (auto) | Radiell gänglängd; auto = till dess skruven når borret. |

### 4.2 Geometri och logik

- Skruven borras/gängas **radiellt genom navet** in i centrumhålet; hålet skapas som
  ett andra borr (`Bore`-modul utökas med `threadedHoles(p)`).
- `setScrewThread` styr nominell håldiameter (t.ex. M3 → borr 2,5 mm, gänga 3 mm).
- Två skruvar placeras förskjutna `setScrewAngle2 − setScrewAngle` (vanligen 90°/180°).
- Konflikt: om `hubLeftLength = hubRightLength = 0` (inget nav) är `setScrewCount > 0`
  ett hårt fel — skruv kräver navgods.

---

## 5. Per-kugge-parametrar — asymmetri, flanker, rot, topp, avlastning

Idag är alla kuggar identiska (en profil per kugghjul). Specifikationen inför tre
nivåer av styrning:

1. **Global** — gäller alla kuggar (dagens parametrar).
2. **Per kugge (mönster)** — ett upprepande mönster över kuggarna, t.ex. varannan
   kugge eller cyklisk variation (för dämpning/vibration).
3. **Lokalt undantag** — enskild kugge åsidosätts helt (t.ex. indexkugge).

### 5.1 Datamodell

```kotlin
data class ToothOverride(
    val leftPressureAngleDeg: Double,   // vänster flank (sett i rotationsriktning)
    val rightPressureAngleDeg: Double,  // höger flank
    val toothThickness: Double,         // bågtjocklek vid delningscirkeln (mm)
    val addendumCoef: Double,           // * module
    val dedendumCoef: Double,           // * module
    val rootFilletR: Double,            // kuggrotsradie (mm)
    val tipChamfer: Double,             // toppfas (mm, 45°)
    val tipRelief: Double,              // toppavlastning (mm, längs flanken)
    val rootRelief: Double,             // rotavlastning (mm)
    val transitionR: Double             // flank→rot övergångsradie (mm)
)

// Globala mönster + undantag:
val toothPattern: ToothPattern?        // null = uniforma
val toothOverrides: Map<Int, ToothOverride>  // lokala undantag, key = kuggindex
```

`ToothPattern` kan vara `NONE`, `ALTERNATING` (varannan), `CYCLIC(period)` eller
`RANDOM_SEED(seed)` för kontrollerad variation.

### 5.2 Parametertabell (per kugge)

| Parameter | Enhet | Intervall | Default | Formel/beroende |
|---|---|---|---|---|
| `leftPressureAngleDeg` | ° | `5 … 35` | `20` | Asymmetrisk vänsterflank. |
| `rightPressureAngleDeg` | ° | `5 … 35` | `20` | Asymmetrisk högerflank. |
| `toothThickness` | mm | `0.4·πm/2 … 0.9·πm/2` | `π·m/2` | Bågtjocklek; summan av tand och lucka = `π·m`. |
| `addendumCoef` | — | `0.5 … 1.6` | `1.0` | Topphöjd över delningscirkeln. |
| `dedendumCoef` | — | `0.8 … 2.0` | `1.25` | Rotsänkning under delningscirkeln. |
| `rootFilletR` | mm | `0 … 0.45·m` | `0.38·m` | Kuggrotens avrundning (brottanvisning). |
| `tipChamfer` | mm | `0 … 0.25·m` | `0` | Toppfasning. |
| `tipRelief` | mm | `0 … 0.05·m` | `0` | Avlastning vid topp (kontaktmönster). |
| `rootRelief` | mm | `0 … 0.05·m` | `0` | Avlastning vid rot. |
| `transitionR` | mm | `0 … 0.5·m` | `0.15·m` | Övergångsradie flank↔rot. |

### 5.3 Asymmetri, konflikt- och beroendehantering

- **Asymmetrisk flank** uppnås med `leftPressureAngleDeg ≠ rightPressureAngleDeg`;
  detta ger en riktad profil (t.ex. för enkelriktad belastning).
- `toothThickness + toothSpace = π·m` — en ökad tandtjocklek krymper luckan
  (och därmed spelet mot motparten); `GearSpec.validate` varnar om
  `toothThickness > 0.7·πm/2` (för tätt ingrepp).
- Lokalt undantag > per-kugge-mönster > global; konflikter löses enligt denna
  prioritetsordning och exponeras i UI som en "override"-badge.
- En lokal `toothThickness`-ändring på en kugge påverkar inte grannarnas
  delningsvinkel (pitch) — men `GearSpec.validate` flaggar om två intilliggande
  undantag överlappar geometriskt.

---

## 6. Parameterdefinitionsmodell (global / per kugge / lokal)

Varje parameter registreras i `GearSpec`-registret med metadata:

```kotlin
data class ParamDef(
    val key: String, val label: String, val group: ParamGroup, val kind: FieldKind,
    val min: Double, val max: Double, val decimals: Int, val unit: String,
    val editable: Boolean, val options: List<String>, val help: String,
    val scope: ParamScope,            // GLOBAL | PER_TOOTH | LOCAL
    val formula: ((GearParams) -> Double)?,  // härlett värde (read-only)
    val dependsOn: List<String>,      // nycklar som styr synlighet/gränser
    val conflict: List<String>        // nycklar som ömsesidigt utesluter
)
```

- **Global** (`scope = GLOBAL`): en enda kopia, t.ex. `module`, `material`.
- **Per kugge** (`scope = PER_TOOTH`): en lista/mönster, t.ex. `toothThickness`.
- **Lokal** (`scope = LOCAL`): `Map<Int, …>`-undantag.
- **Härledda** (`formula != null`): read-only, beräknas vid regenerering, t.ex.
  `pitchDiameter = m·z`.

Nya beräknade resultat som ska läggas till i `GearSpec.results`:

| Resultat | Formel |
|---|---|
| `result_pitch_dia` | `m·z` |
| `result_outer_dia` | `m·(z+2·addendumCoef)` |
| `result_root_dia` | `m·(z−2·dedendumCoef)` |
| `result_base_dia` | `m·z·cos(α)` |
| `result_weight` | `V·ρ` (volym från mesh × materialdensitet) |
| `result_inertia` | `J = ½·m·(r_yttre² + r_inre²)` (cylindrisk approximation) |
| `result_backlash` | effektivt spel mot nominell motpart |

---

## 7. Effekter av förlängningar (nav/boss/krage)

| Aspekt | Effekt |
|---|---|
| **Geometri** | Navet vidgar totalbredden och ändrar `GearAssembly`-kollisionsgränser; asymmetriskt nav kräver orienterad montering. |
| **Ingrepp** | Navet påverkar inte tandgeometrin om `hubDiameter ≤ rootDiameter`; annars kapas kuggar → `validate` varnar. |
| **Balans** | Asymmetriskt nav förskjuter masscentrum axiellt; en motviktsparameter `counterbalance` (se §8) kan återställa balans. |
| **Hållfasthet** | Nav med `hubFillet` minskar spänningskoncentrationer vid rot; för stort nav med tunna väggar ökar risken för krymp-/pressspänning. |
| **Vikt** | Längre/tjockare nav ökar vikten linjärt; ekrar/fickor (§8) kompenserar. |
| **Tillverkning** | Nav kräver svarv-/frässteg; `hubDraftAngle` för gjutning; stoppskruv kräver gängning. |
| **Montering** | Asymmetri tvingar en entydig orientering; indexmarkering (§8) bör ange vänster/höger. |

---

## 8. Smarta parametriska tillägg

| Tillägg | Parametrar | Not |
|---|---|---|
| Variabel kuggtjocklek | `toothThickness` (per kugge) | §5 |
| Profilförskjutning | `profileShift` (finns) | positiv förskjutning stärker små kugghjul |
| Tryckvinkel/modul/antal kuggar | `pressureAngleDeg`, `module`, `teeth` (finns) | globala |
| Lättningshål | `lighteningHoleCount`, `lighteningHoleDiameter`, `lighteningHolePCD` | jämnt fördelade, innanför rotcirkeln |
| Ekrar | `spokeCount`, `spokeWidth`, `spokeFillet` | ersätter massiv skiva |
| Fästskruvhål (i nav) | `setScrewCount`, `setScrewThread`, … | §4 |
| Kilspår | `bore.type = KEYWAY`, `keywayWidth`, `keywayDepth` (finns) | |
| D-format axelhål | `bore.type = D_CUT`, `dCutFlatOffset` (finns) | |
| Indexmarkeringar | `indexMarkType` (NONE/SLOT/DOT/TOOTH_FLAT), `indexMarkAngle` | på kuggkroppen |
| Toleransparametrar | `toleranceClass` (finns) + `boreHoleTolerance`, `keywayTolerance` | H7/H8 … |
| Övergångsradier | `rootFilletR`, `transitionR`, `hubFillet` | §3, §5 |
| Materialbesparande fickor | `pocketCount`, `pocketDepth`, `pocketDiameter` | på navets sidor |
| Sensor-/positionsmarkering | `sensorMarkAngle`, `sensorMarkType` | för hall-/optisk givare |
| Kompatibilitet med standardkugghjul | validering mot ISO 54/21771-moduler och 20° PA; `standardProfile` | förköps-/bytbarhet |
| Kollision/ingreppskontroll | `clearanceCheck` (auto), `minToothTipClearance` | §11 |
| Export CAD/3D | STL, 3MF, DXF (finns) + STEP/IGES (ny) | §13 |

---

## 9. Användargränssnittets struktur

### 9.1 Logiska grupper och flikar

Befintliga grupper (`ParamGroup`: GEOMETRY, MATERIAL, TOLERANCES, LOAD, RESULTS)
utökas/omstruktureras till flikar:

1. **Grundgeometri** — module, teeth, pressureAngle, thickness, toothProfile.
2. **Kuggar (avancerat)** — per-kugge/asymmetri (tabell eller mönstereditor), root/tip/relief.
3. **Nav & axel** — hub/boss vänster–höger, bore, kilspår/D-axel, stoppskruv.
4. **Lättning** — ekrar, lättningshål, fickor.
5. **Material & toleranser** — material, toleransklass, spel/passning.
6. **Last & livslängd** — loadNm, speedRpm, lifetimeHours, safetyFactor.
7. **Resultat** — beräknade diametrar, vikt, tröghetsmoment, varningar.

### 9.2 Funktioner

- **Sök/filter** i parameterpanelen (fritext mot `label` + `help`).
- **Realtidsförhandsvisning** — befintlig `GearPreview3D` (params-baserad rasterisering)
  debounce-uppdateras; i redigeraren uppdateras `GearGLView` vid varje commit.
- **Direkta manipulatorer** i vyporten: dra navlängd (axelriktad pil), navdiameter
  (radiepil), stoppsruvsvinkel (vinkelmätare), kuggtoppsfas (per kugge via pick).
- **Uttrycksstyrning** — fält kan innehålla uttryck som `0.38*m`, `2*module+0.1`,
  `π*m/2`; parser utvärderar mot `GearParams` och blockerar cykliska beroenden.
- **Presetbibliotek** — befintligt per-typ-bibliotek utökas med nav-/remspecifika presets.
- **Validering** — hårda fel (blockerar regenerering, röd markering) vs mjuka varningar.
- **Versionshantering** — ångra/historik (finns, `MAX_UNDO`) + namngivna
  "snapshots" av parameteruppsättningar (bygg på `SavedConfigs`).

### 9.3 Globala vs per-kugge i UI

- Globala parametrar visas som vanliga fält.
- Per-kugge-mönster visas som en **kompakt mönstereditor** (välj mönstertyp + parametrar).
- Lokala undantag visas som en **kugglista** (kuggindex → redigera undantag), med
  visuell markering av överstyrda kuggar i 3D-vyn (färgkodning).

---

## 10. Regenerering och uppdateringsordning

1. **Normalisering** — alla indata kläms till giltiga intervall (§6).
2. **Härledning** — `GearCalculator` beräknar diametrar/radier i beroendeordning
   (module/teeth → pitch/outer/root/base).
3. **Profilgenerering** — `GearProfiles` producerar 2D-kontur; per-kugge-undantag
   appliceras per kuggindex innan konturen stängs.
4. **Mesh** — `MeshBuilder.extrude`/`Loft.loft`; nav/boss förenas, borr/fickor
   subtraheras (boolean på 2D-plan följt av extrude, i linje med befintlig pipeline).
5. **Kollision/ingrepp** — mot närliggande delar i `GearAssembly` (planet/snäck/rem).
6. **Cachning** — `meshCache` nycklas på hela `GearParams` (finns i `GearWorkspace`);
   per-kugge-undantag inkluderas i hash (data-klass `equals/hashCode`).

**Uppdateringsordning** är en DAG: `module → pitchRadius → profil → mesh → analys →
export`. Cykler (t.ex. uttryck som refererar varandra) detekteras och blockeras.

**Ogiltig geometri/extrema värden:**
- Kläms i `ParamDef` (min/max) före beräkning.
- Hårda fel (t.ex. `teeth < 3`, `module ≤ 0`, `hubDiameter ≤ bore`) → `GearSpec.validate`
  returnerar `ERROR`-nivå; `GearBuilder` kastar aldrig okontrollerat utan returnerar
  tomt/partiellt resultat som UI visar med felmeddelande.
- Extremvärden (t.ex. `helixAngleDeg ≥ 89°`, planetöverlapp) → varning + förslag.

---

## 11. Toleranser, spel och passningar

| Syfte | Parametrar | Rekommendation |
|---|---|---|
| Axelhålspassning | `boreHoleTolerance` (H7/H8/F8) | H7 vid press, F8 vid glidning |
| Kilspår | `keywayTolerance` (JS9/N9) | enligt ISO 7738 |
| Tandspel (backlash) | `backlash` (finns) | 0,05–0,15 mm för små moduler; härleds även från material/termisk expansion |
| Navpassning | `hubBoreTolerance` | press-/krymppassning anges som toleransklass |
| Ytjämnhet | `surfaceFinishUm` (finns) | styrs till tillverkningsmetod (fräs/svarv/print) |
| Stoppskruv | `setScrewThread` + gängdjup | gängans ingrepp ≥ 1,5×diameter vid last |

`GearSpec.validate` kontrollerar att `backlash` inte överstiger halva luckan
(`π·m/2 − toothThickness`) och att hålpassningar är rimliga relativt `surfaceFinishUm`.

---

## 12. Analys

Analysen bygger på befintlig mesh + nya analytiska modeller:

| Analys | Metod |
|---|---|
| **Ingrepp/kollision** | minsta tandtoppsspel mot motpart i `GearAssembly`; planetöverlapp (finns) generaliseras till alla par. |
| **Backlash** | effektivt spel = nominellt spel − toleransspridning; visas i Resultat. |
| **Kontaktmönster** | numerisk kontaktlinje från profilgeometri (involut) — varnar vid kantkontakt vid `tipRelief = 0`. |
| **Spänning/deformation** | analytisk tandrotsspänning (Lewis + AGMA-typ) med `safetyFactor`, `loadNm`; grov FE-uppskattning från mesh. |
| **Vikt** | meshvolym × materialdensitet. |
| **Tröghetsmoment** | `J = Σ mᵢ·rᵢ²` från mesh (eller cylindrisk approximation). |

---

## 13. Export

| Format | Innehåll | Status |
|---|---|---|
| STL (binär) | mesh | finns |
| 3MF | mesh + metadata | finns |
| SVG / DXF | 2D-kontur (ritningsunderlag) | finns |
| **STEP / IGES** | neutral CAD-solid (nav, borr, kuggar) | **ny** — bygg på BREP-gränssnitt över mesh eller direkt genererad profil |
| **Ritningsunderlag (DXF)** | utökad med mått, navvyer, toleranser | utökning |
| Teknisk dokumentation | parameterrapport (namn/värde/enhet), vikt, toleranser | ny — genereras som text/PDF-underlag |

Remtransmissionen (nästa avsnitt) exporteras med samma format; STEP inkluderar hela
transmissionen som en assembly.

---

## 14. Kuggremstransmission (parametriskt system)

Ett nytt toppnivå-koncept (`BeltTransmission`) som regenererar remskivor, spännrullar
och remsträckor tillsammans med kugghjulen.

### 14.1 Remtyper (profil + delning)

| Profil | Delning (mm) | Typiskt bruk |
|---|---|---|
| GT2 | 2,0 | 3D-skrivare, precisionspositionering |
| HTD 3M | 3,0 | kompakta drivningar |
| HTD 5M | 5,0 | allmänna drivningar |
| HTD 8M | 8,0 | höga moment |
| T5 | 5,0 | linjära axlar |
| AT5 | 5,0 | positionering, lågt glapp |

### 14.2 Parametrar

| Parameter | Enhet | Intervall | Default | Beskrivning |
|---|---|---|---|---|
| `beltProfile` | — | GT2, HTD3M, HTD5M, HTD8M, T5, AT5 | GT2 | Remtyp → delning. |
| `beltWidth` | mm | `4 … 100` | 6 | Rembredd. |
| `driverTeeth` | st | `8 … 200` | 20 | Drivande remskivans kuggantal. |
| `drivenTeeth` | st | `8 … 200` | 40 | Drivna remskivans kuggantal. |
| `centerDistance` | mm | `min … 2000` | auto | Axelavstånd; auto beräknar närmaste giltiga remlängd. |
| `beltTeeth` | st | `≥ driverTeeth+2·drivenTeeth/…` | auto | Remmens kuggantal → `beltLength = beltTeeth·pitch`. |
| `idlerCount` | st | `0 … 3` | 0 | Antal spännrullar. |
| `idlerDiameter` | mm | `≥ 2·pitch` | auto | Spännrullediameter (slät eller tandad). |
| `idlerPosition` | — | beräknad | auto | Spännrullens läge (mellan hjulen, på slak sida). |
| `flangeCount` | st | `0 … 2` | 2 | Flänsar på drivande hjul (styr remmen). |
| `flangeDiameter` | mm | `pitchDia + 2·(2 … 6)` | auto | Flänsdiameter. |
| `hubBore` / `hubDiameter` / `hubLength` | mm | som §3 | — | Remskivans nav (återanvänd nav-modell). |
| `mountingHoleCount` / `mountingHolePCD` | st/mm | — | 0 | Fästhål (för motormontering). |
| `beltTension` | N | `0 … 500` | auto | Remsppänning (påverkar nedböjning/spännrullsförskjutning). |
| `beltBacklash` | mm | `0 … 0,5` | 0 | Glapp mellan remkugg och remskiva (tolerans). |
| `beltToleranceClass` | — | ISO-klasser | — | Tillverkningstolerans för remskivor. |

### 14.3 Formler och beroenden

- `pitchDiameter = driverTeeth · pitch / π` (remskivans delningsdiameter).
- `ratio = drivenTeeth / driverTeeth`.
- `beltTeeth` väljs som närmaste giltiga heltal så att
  `L = 2C + π/2·(D1+D2) + (D2−D1)²/(4C)` (approximation för öppen rem) och att
  `L = beltTeeth·pitch`; om `centerDistance` är auto löses `C` fram ur samma ekvation.
- Antal kuggar i ingrepp per remskiva beräknas och kontrolleras mot minsta
  rekommenderade (vanligen ≥ 6) — **ingreppskontroll**.

### 14.4 Validering

- Hårt fel: `beltLength` ej delbar med `pitch` (måste vara helt kuggantal).
- Hårt fel: remskive-`teeth` < minsta antal för profilen (undviker remkuggskjuvning).
- Varning: för få kuggar i ingrepp, för högt `beltTension`, remkollision med nav/fläns.

### 14.5 Regenerering och analys

Hela transmissionen (`pulleys + idlers + belt`) genereras som en `GearAssembly`-liknande
struktur (`BeltAssembly`), ritas i samma 3D-vy och analyseras med:
- **ingrepp** (kugg-i-ingrepp, rem↔remskiva),
- **backlash** (`beltBacklash`),
- **utväxling** och **remhastighet**,
- **vikt/tröghetsmoment** per remskiva och totalt.

Export sker med samma pipeline (§13); STEP exporterar hela transmissionen.

---

## 15. Föreslagen implementeringsordning

1. **Nav/boss + stoppskruv** — utöka `BoreSpec`/`GearParams`, ny `Hub`-modul i core,
   ny flik "Nav & axel" i `SettingsPanel`.
2. **Per-kugge-parametrar** — `ToothOverride` + `toothOverrides`, utökad `GearProfiles`
   (profil per kuggindex), kugglista-UI.
3. **Lättning/ekrar/fickor/index** — nya borr-/subtraktionsmönster i `Bore`.
4. **Toleranser & analys** — `GearCalculator`-utökningar + nya `GearSpec.results`.
5. **STEP/IGES-export** — neutral CAD-export (BREP-via-mesh eller direktprofil).
6. **Kuggremstransmission** — `BeltTransmission` i core + ny toppnivåvy i appen.
7. **UI-verktyg** — uttrycksparser, sök, manipulatorer, versionssnapshots.

Varje steg valideras med enhetstester (utökad `GearCoreTest`) och emulatorverifiering
(3D-rendering + `SettingsPanel` + export) i linje med befintlig arbetsmetod.
