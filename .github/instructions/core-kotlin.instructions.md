---
applyTo: "core/**/*.kt"
---

# `core` — ren Kotlin, ingen Android

`core` är ett `org.jetbrains.kotlin.jvm`-projekt. Det kompileras och testas utan
Android SDK, utan emulator och utan Gradle Android-plugin.

## Absoluta regler

- **Ingen Android-import.** Inget `android.*`, `androidx.*`, `Context`, `Log`,
  `Uri`. Om du behöver något plattformsspecifikt hör logiken i `android/`.
- **Ingen Compose.** Även `androidx.compose.*` är förbjudet.
- **Ingen fil-I/O till disk.** Skrivarna returnerar `ByteArray`; det är
  `android/.../ExportManager.kt` som skriver till Downloads.
- **Alla längder i millimeter, internt.** Tum hanteras via konvertering i
  `GearCalculator`/`GearSpec.conv`. Lagra aldrig tumvärden i `GearParams`.

## Determinism

- Använd `Double` rakt igenom i geometrin. Blanda inte in `Float` i matematiken —
  `StlWriter` castar till `Float` först vid serialisering, vilket är avsiktligt.
- Ingen iteration över `HashMap`/`HashSet` där ordningen påverkar utdata. Använd
  `LinkedHashMap`, `sortedBy` eller indexbaserade listor. `MeshValidationTest` och
  `StlExportIntegrityTest` faller annars intermittent — den typen av flakighet är
  värre än ett hårt fel.
- Inga tidsberoenden (`System.currentTimeMillis`, `Random` utan seed) i kod som
  producerar mesh eller filer.

## Kontrakt som måste hållas

### `GearSpec.setNumber(p, key, v)`

Sätter ett numeriskt fält. Ska **klampa** värdet till `ParamDef.min`/`max` och
returnera ett nytt `GearParams`. Klampning ska vara *tyst i core* men *synlig i UI*
— UI:t jämför inmatat värde mot `min`/`max` och visar varningen (ACTION_PLAN punkt 10).

Lägger du till en ny numerisk parameter är det här anropet **och** `getNumber`,
`fields()` och `results()` som ska uppdateras. Missar du `getNumber` får fältet
värdet 0 i UI:t utan att något fel syns.

### `GearSpec.validate(p)` → `List<GearWarning>`

Returnerar varningar med `code`, `severity` (`ERROR`/`WARNING`) och `detail`.
`ERROR` blockerar export i `GearWorkspace.startExport()`.

- Ny varning: lägg koden i `validate()` **och** en `validation_*`-nyckel i både
  `en` och `sv` i `I18n.kt`. Det finns ett test (`PrintAdvisorKeysTest`,
  `RingGeometryValidationTest`) som kontrollerar att varje kod har en sträng.
- Fysikaliskt omöjliga samband ska vara `ERROR`, tveksamma val ska vara `WARNING`.
  Exempel på `ERROR`: planetväxel där `ring_teeth < sun + 2·planet`.

### `GearParams`-likhet används som cache-nyckel

`GearWorkspace.kt` cachar byggda meshar på `GearParams` `data class`-hash. Lägger
du till ett fält i `GearParams` som påverkar geometrin får det **inte** vara ett
`var` eller muteras i efterhand — då slutar cachen att invalideras.

## NaN- och degenererad geometri

Extrema parametrar får inte producera `NaN`, `Infinity`, tomma triangel-listor eller
noll-area trianglar. Innan du ändrar en formel:

- Skydda divisioner (`sin(α)` kan bli 0 för små α).
- Klampa eller avvisa värden som ger `acos`-argument utanför `[-1, 1]`.
- Verifiera med `MeshValidationTest`-mönstret (inga NaN-koordinater, ingen
  degenererad triangel).

`GearParams.precision` (`HOBBY`/`STANDARD`/`HIGH`) styr meshupplösning. `HOBBY`
och `STANDARD` ska alltid vara billigare än `HIGH` — testet
`AllGearTypesMatrixTest` bygger alla 14 typer i alla precisioner och är dyrast i
sviten. Lägg inte till arbete i den vägen utan att mäta.

## Testa

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :core:test --tests "*GearCoreTest*"
```

- Lägg nya tester i `core/src/test/java/com/gearforge/core/`, i samma paket.
- Testnamn beskriver beteendet, inte metoden: `planetaryRingBelowSunPlusTwoPlanetsIsError`.
- Testa **gränser**, inte bara typfall: `min`, `max`, `min - ε`, `max + ε`,
  negativt värde, `0`, `NaN`.
- Nya kugghjulstyper ska läggas in i `AllGearTypesMatrixTest` så de täcks av
  matrisen (alla typer × alla precisioner).
