---
description: "Granska EN/SV-pariteten i I18n.kt och hitta hårdkodade UI-strängar"
agent: agent
---

# I18n-granskning

Kör paritetskontrollen och granska sedan Compose-filerna. Rapportera konkreta
åtgärder, inte allmänna råd.

## Steg 1 — maskinell kontroll

```powershell
py tools/i18n_audit.py
```

Om exit-koden inte är 0: lista varje avvikelse (nyckel, språk, rad) och föreslå den
exakta rad som ska läggas till. Översätt inte själv utan att visa förslaget.

## Steg 2 — hårdkodade strängar

```powershell
Select-String -Path 'android\src\main\java\com\gearforge\app\*.kt' -Pattern 'Text\(\s*"' |
    Where-Object { $_.Line -notmatch 'I18n\.t' }
```

Bedöm varje träff:

- **Enhet eller symbol** (`mm`, `°`, `%`, `✕`, tester) → OK, hoppa över.
- **Riktig text** → översättningsmiss. Föreslå en nyckel, både `en`- och
  `sv`-värdet, och radbytet i anropande fil.

## Steg 3 — `validation_*`-täckning

Jämför de varningskoder `GearSpec.validate()` kan producera i
`core/src/main/java/com/gearforge/core/GearSpec.kt` mot `validation_*`-nycklarna i
`I18n.kt`. Varje kod måste ha en nyckel i **båda** språken.

## Steg 4 — platshållare

För varje nyckel som innehåller `{0}`: verifiera att anropsstället skickar lika
många argument till `I18n.t(...)`. En nyckel med `{0}` som anropas utan argument
visar literaltexten `{0}` för användaren.

## Rapport

Sammanfatta i en tabell: fil, rad, problem, föreslagen åtgärd. Om allt är grönt,
säg det med den faktiska siffran (antal nycklar i båda språken).
