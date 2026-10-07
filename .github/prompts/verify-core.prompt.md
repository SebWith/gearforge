---
description: "Kör kärntestsviten och sammanfatta eventuella geometriregressioner"
agent: agent
---

# Verifiera core

Kör testsviten utan emulator och rapportera resultatet konkret.

```powershell
.\gradlew.bat :core:test --console=plain
```

## Vid grönt

Rapportera antalet körda tester och att ingen regression finns. Nämn att detta
täcker geometri, round-trip och filformat.

## Vid rött

1. Extrahera de felande testerna ur utskriften. Om terminalen visar för lite,
   läs rapporten i stället:
   `core/build/reports/tests/test/index.html` eller XML i
   `core/build/test-results/test/`.
2. För varje fel: ange testnamn, förväntat värde, faktiskt värde och den formel i
   `core/` som troligen ligger bakom.
3. Bedöm om felet är en **regression** (koden ändrades nyss) eller ett **nytt
   testfall som avslöjar befintligt fel**. Det styr åtgärden.
4. Föreslå den minsta ändring som gör testet grönt utan att försvaga det.

För ett enskilt test:

```powershell
.\gradlew.bat :core:test --tests "*SpurProfileTest*"
```

## Vanliga orsaker i detta repo

| Symptom | Trolig orsak |
|---|---|
| `NaN` i förväntat värde | Division med `sin(0)`, `acos` utanför `[-1,1]` |
| Intermittent fel | Iteration över `HashMap`/`HashSet` |
| `ClassNotFoundException` i workern | `-Dfile.encoding=UTF-8` tvingat på daemonen |
| `OutOfMemoryError` | `maxHeapSize = "2g"` i `core/build.gradle` borttaget eller sänkt |

Avsluta med en tydlig dom: **grönt** eller **rött med N fel**, och exakt vilken fil
som ska ändras härnäst.
