---
name: export-format-integrity
description: "Verifiera och felsöka GearForge:s sex exportformat (STL, 3MF, STEP, IGES, DXF, SVG). Use when: en exporterad fil inte går att öppna; ändra en skrivare i core; lägga till ett nytt exportformat; kontrollera triangelantal, vattentäthet, enheter eller bounding box; avgöra om en mesh är giltig för 3D-utskrift. Trigger words: STL, 3MF, STEP, IGES, DXF, SVG, export, writer, mesh, watertight, manifold, triangle count, binary STL, bounding box, ExportManager, ExportSheet."
---

# Exportformat — integritet och verifiering

Sex format, sex skrivare i `core/`, noll externa beroenden. Läs också
`.github/instructions/export-writers.instructions.md` för invarianten per format.

## Kedjan

```
GearBuilder.assembly(params)        -> Mesh (vertices + triangles)
StlWriter/ThreeMfWriter/...          -> ByteArray
ExportManager.bytes(params, format, highQuality)   (android/)
  -> väljer PrecisionLevel utifrån highQuality
ExportManager.saveToDownloads(...)   -> MediaStore -> Downloads
GearWorkspace.ExportSheet            -> förhandsvisning, gating, felhantering
```

`ExportManager.Format` är den enda källan till filändelse och MIME-typ:

| Format | Ändelse | MIME |
|---|---|---|
| `STL` | `.stl` | `model/stl` |
| `THREE_MF` | `.3mf` | `model/3mf` |
| STEP / IGES / DXF / SVG | enligt enum | enligt enum |

Lägger du till ett format ska det in i `Format`-enumen, i `bytes()`-`when`, i
`ExportSheet`s formatväljare och i `STORE_READINESS.md`s claim-tabell (den säger
"6 exportformat" — siffran ska stämma).

## Verifiera en exporterad fil

`tools/verify_export.py` läser en fil och kontrollerar struktur, deklarerat antal
och bounding box:

```powershell
py tools/verify_export.py "$env:USERPROFILE\Downloads\spur.stl"
py tools/verify_export.py "$env:USERPROFILE\Downloads\*.stl" --expect-diameter 22.0
```

Den fångar de fel som tyst ger en värdelös fil: fel endianness, felaktig
längdberäkning, `NaN`-koordinater, degenererade trianglar och skal-/enhetsfel
(`--expect-diameter` mot `GearCalculator`s ytterdiameter).

## Vad som faktiskt går sönder

| Symtom | Orsak | Kontroll |
|---|---|---|
| CAD säger "not a valid STL" | Längd ≠ `84 + n·50`, eller fel endianness | `verify_export.py`, `StlExportIntegrityTest` |
| Modellen är spegelvänd / ihålig | Fel triangelorientering (normaler inåt) | `MeshOps.faceNormal`, `MeshValidationTest` |
| Modellen är 25,4× för stor/liten | Tumvärde läckt in i millimetergränssnittet | `--expect-diameter` mot `GearCalculator` |
| Slicern klagar på "non-manifold edges" | Kant delas av ≠ 2 trianglar — oftast i `Loft.kt` | `MeshValidationTest` |
| 3MF går inte att öppna | ZIP- eller OPC-struktur inkonsekvent | `AssetExportTest` |
| Laser skär rakt igenom detaljen | Öppen polylinje i DXF/SVG | Kontrollera att konturen sluts |
| IGES avvisas utan felmeddelande | Post är inte exakt 80 tecken | Räkna tecken per post |
| Filen är tom | Triangellistan tom — byggaren returnerade inget | Kontrollera att parametrarna passerade `validate()` |

## Precision vs geometri

`PrecisionLevel` (`HOBBY`/`STANDARD`/`HIGH`) styr hur fint en kuggflank delas upp.
Högre precision får **bara** ändra finheten, aldrig det nominella måttet:

```powershell
# Samma ytterdiameter ska rapporteras för HOBBY, STANDARD och HIGH.
py tools/verify_export.py hobby.stl  --expect-diameter 22.000
py tools/verify_export.py high.stl   --expect-diameter 22.000
```

Icke-Pro-tillgång tvingar `STANDARD` (se `GearWorkspace.kt`: `highQuality = isPro &&
settings.highQuality`). Ändrar du upplösningen ändras triangelantalet — uppdatera
förhandsvisningen i `ExportSheet` (ACTION_PLAN punkt 12) i samma ändring.

## Testmönster

```powershell
.\gradlew.bat :core:test --tests "*StlExportIntegrityTest*"
.\gradlew.bat :core:test --tests "*StlWriterTest*"
.\gradlew.bat :core:test --tests "*AssetExportTest*"
.\gradlew.bat :core:test --tests "*MeshValidationTest*"
.\gradlew.bat :core:test --tests "*AllGearTypesMatrixTest*"
```

Ett nytt skrivartest ska minst innehålla:

1. **Magic bytes / rubrik** — rätt formatidentifierare.
2. **Längd** — deklarerat antal matchar faktiskt antal (och STL:s exakta `84 + 50n`).
3. **Round-trip** — parsa tillbaka och jämför triangelantal och bounding box.
4. **Bounding box mot parametrarna** — ytterdiametern ska stämma med
   `GearCalculator` inom tolerans.
5. **Ingen `NaN`** i någon koordinat, ingen degenererad triangel.
6. **Ett extremfall** — minsta tillåtna modul, högsta kuggantal, eller en typ med
   avsiktlig öppning.

## Felsökning i appen

Exporterar appen men filen blir fel, kontrollera i denna ordning:

1. `ExportSheet`s förhandsvisning (filnamn, triangelantal, mått) — stämmer den med
   `GearBuilder.merged(params)`? Om inte är felet i förhandsvisningen, inte i
   skrivaren.
2. `ExportManager.bytes` — vilken `PrecisionLevel` valdes? Logga den.
3. Skrivaren — kör motsvarande `:core:test --tests "*<Namn>Test*"`.
4. `saveToDownloads` — returnerade den `Result.failure`? Sök i logcat efter
   undantaget (ACTION_PLAN punkt 2 gjorde denna väg felhanterad med flit).
5. Behörighet/MediaStore — på API 29+ skrivs via MediaStore till Downloads. En
   nekad skrivning syns som ett misslyckat `Result`, inte som en krasch.

```powershell
adb logcat -d -t 200 "*:E" | Select-String "gearforge|AndroidRuntime|FATAL|MediaStore"
```
