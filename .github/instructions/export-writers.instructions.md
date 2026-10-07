---
applyTo: "core/src/main/java/com/gearforge/core/*Writer.kt"
---

# Exportskrivare — formatintegritet

Sex skrivare ligger i `core` och producerar `ByteArray`. Det finns sex format och
inga externa beroenden: allt serialiseras för hand.

| Skrivare | Format | Första bytes | Struktur |
|---|---|---|---|
| `StlWriter.kt` | STL (binär) | 80-byte header | `80 + 4 + n·50` byte |
| `ThreeMfWriter.kt` | 3MF | ZIP (`PK\x03\x04`) | OPC-paket med XML |
| `StepWriter.kt` | STEP (ISO 10303-21) | `ISO-10303-21;` | `HEADER;` … `DATA;` … `ENDSEC;` |
| `IgesWriter.kt` | IGES | 80-teckens fasta poster | Sektioner S/G/D/P/T |
| `DxfWriter.kt` | DXF | `0\nSECTION` | HEADER (`$ACADVER`, `$INSUNITS`, `$MEASUREMENT`) + gruppkoder, parvisa rader |
| `SvgWriter.kt` | SVG | `<?xml` eller `<svg` | XML |

## Invarianten du inte får bryta

### STL

```kotlin
val buf = ByteBuffer.allocate(84 + n * 50).order(ByteOrder.LITTLE_ENDIAN)
```

- **Längden är exakt `84 + n·50`.** Readme-headern är 80 byte, sedan `int32 n`,
  sedan `n` trianglar om 50 byte (12 float + `uint16` attribute-byte-count).
- **Little endian.** `ByteOrder.LITTLE_ENDIAN` är obligatoriskt.
- Headern får inte innehålla ordet `solid` i början — vissa parsers tolkar filen
  som ASCII-STL då. Headern är för närvarande nollställd; behåll det.
- `StlExportIntegrityTest` och `StlWriterTest` kontrollerar längd, triangelantal
  och att antalet faktiskt matchar. Ändrar du layouten faller de — det är meningen.

### 3MF

- Är ett ZIP-arkiv. Ändrar du innehållet måste `[Content_Types].xml`,
  `_rels/.rels` och `3D/3dmodel.model` fortfarande vara konsekventa.
- `AssetExportTest` packar upp och parsar XML:en. Ett trasigt ZIP ger ett hårt fel
  där, inte tyst korruption.

### DXF / SVG (2D)

- Alla konturer ska vara **slutna** polylinjer. En öppen kontur blir ett snitt rakt
  igenom detaljen i en laserskärare.
- Koordinater i millimeter, inga enhetsskalningar inbäddade i geometrin.
- **DXF måste deklarera enheten i HEADER.** Utan `$INSUNITS` är filen enhetslös och
  importören får gissa, medan `SvgWriter` skriver `width="…mm"` — samma kugghjul
  kunde då läsas som millimeter i den ena filen och som dokumentenheter i den
  andra. `$INSUNITS` = 4 (millimeter) och `$MEASUREMENT` = 1 (metriskt) är samma
  enhet som SVG:n anger. `LWPOLYLINE` kräver R14 (AC1014) men `$INSUNITS` kom med
  AC1015, därför anger headern `AC1015`. `DxfExportTest` håller detta fast.
- Yttre kontur och borrhål måste ha rätt orientering (moturs/medurs) så att
  CAM-program tolkar hål som hål.

### STEP / IGES

- STEP är textbaserat men positionsberoende: `ENDSEC;` och `END-ISO-10303-21;` ska
  stå på egna rader.
- IGES kräver att varje post är exakt 80 tecken. En radbrytning på fel ställe eller
  ett tecken för mycket ger en fil som CAD-program avvisar utan begripligt fel.

## Precision

`GearParams.precision` (`HOBBY`/`STANDARD`/`HIGH`) styr hur många segment en
kuggflank delas i. `ExportManager.bytes(params, format, highQuality)` väljer nivå;
icke-Pro tvingas till `STANDARD`.

- Ändrar du upplösningen ändras triangelantalet, vilket ändras i
  `AllGearTypesMatrixTest` och i `ExportSheet`s förhandsvisning (ACTION_PLAN
  punkt 12). Uppdatera båda.
- Högre precision får inte ändra den nominella geometrin — bara finheten. En
  delningsdiameter ska vara identisk oavsett precision.

## Mesh-kvalitet

- **Vattentät mesh:** varje kant ska delas av exakt två trianglar. Undantaget är
  avsiktliga öppningar (t.ex. borrhål som går igenom). `MeshValidationTest`
  kontrollerar detta.
- **Non-manifold-kanten** i compound-växeln var en verklig bugg (se `CHANGELOG.md`).
  Loft mellan två profiler är det ställe där det uppstår — kontrollera
  `Loft.kt`/`CompoundGearBuilder.kt` när du rör sammanfogningar.
- Inga degenererade trianglar (noll area, kollineära hörn), inga `NaN`-koordinater.
  Skrivare ska inte behöva filtrera — byggaren ska aldrig producera dem.

## Testmönster för en ny skrivare

```powershell
.\gradlew.bat :core:test --tests "*StlExportIntegrityTest*"
```

Ett test för en skrivare ska minst kontrollera:

1. **Rubrik/magic bytes** — rätt formatidentifierare.
2. **Längd/struktur** — deklararat antal matchar faktiskt antal.
3. **Round-trip** — om ett test kan parsa tillbaka: antal trianglar, bounding box
   och att ingen koordinat är `NaN`.
4. **Bounding box mot parametrarna** — ytterdiametern ska stämma med
   `GearCalculator`-värdet inom en tolerans. Detta fångar enheter och skalafel.

## Förbjudet

- Att lägga till ett externt beroende för att skriva ett format. Projektet har
  medvetet noll tredjepartsberoenden i `core` utöver kotlin-stdlib.
- Att ändra en skrivares publika signatur utan att uppdatera `ExportManager` i
  `android/` — den är enda anroparen.
- Att skriva till disk från `core`. Returnera `ByteArray`.
