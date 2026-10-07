---
name: i18n-checklist
description: "Lägga till, ändra eller granska strängar i GearForge:s EN/SV-katalog (I18n.kt). Use when: lägga till en ny UI-sträng; en text visas på engelska i den svenska vyn; lokalisera en ny skärm; kontrollera att en nyckel finns i båda språken; granska hårdkodade strängar i Compose. Trigger words: I18n, lokalisering, översättning, svenska, engelska, strängnyckel, EN/SV-paritet, hardcoded string, lang."
---

# I18n — strängkatalogen EN/SV

`android/src/main/java/com/gearforge/app/I18n.kt` är en handrullad katalog: två
`mapOf(...)`-block (`en` och `sv`) med **418 nycklar vardera**. Det finns inget
kompileringstidsskydd. En nyckel som bara finns i `en` faller tyst tillbaka på
engelska i den svenska vyn, och en nyckel som saknas helt visas som sin egen
nyckelsträng. Båda felen syns bara i appen.

Detta har redan gått sönder en gång (ACTION_PLAN punkt 9: "Lokalisering brutet").

## Kontrollera innan du är klar

```powershell
py tools/i18n_audit.py
```

Skriptet fäller fyra feltyper och returnerar exit 1 vid avvikelse:

1. Nyckel finns bara i `en` eller bara i `sv`.
2. Dubblett inom samma språk (`mapOf` tar sista värdet tyst).
3. Platshållare `{0}`, `{1}` matchar inte mellan språken.
4. Tomt värde, eller platshållarindex med hål (`{0}`, `{2}` utan `{1}`).

Hooken `gearforge-guard` kör samma skript automatiskt och **blockerar** en
redigering av `I18n.kt` som bryter pariteten.

## Så används katalogen

```kotlin
I18n.t(lang, "export")                    // enkel nyckel
I18n.t(lang, "free_exports_left", "3")    // {0} ersätts av "3"
```

- `lang` är `I18n.Lang.EN` eller `I18n.Lang.SV` och kommer från
  `SettingsStore`/`MainActivity`. **Den måste passas ned till varje skärm.** En
  skärm som glömmer parametern får ett standardvärde och språkbytet "slutar
  fungera" utan att något kraschar.
- Uppslagningen är `(if (lang == EN) en else sv)[key] ?: en[key] ?: key` — tre
  tysta fallbacks. Därför måste pariteten kontrolleras maskinellt.
- Det finns också en variant med anroparens egen fallback, använd för
  datadrivna etiketter från `core` (`ParamDef.label`/`help`).

## Regler

1. **Ingen hårdkodad sträng i Compose.** Allt användarsynligt går genom `I18n.t`.
   Undantag: enheter och symboler utan språklig betydelse (`mm`, `°`, `%`, `✕`).
2. **Lägg alltid nyckeln i både `en` och `sv` i samma ändring.** Aldrig "sv
   kommer senare" — det blir inte av, och felet syns inte.
3. **Matchande platshållarantal.** Ändrar du `"3 exports left"` till
   `"{0} exports left"` måste `sv` få `{0}` också.
4. **Platshållarindex ska vara sammanhängande från 0.** `t()` ersätter `{i}` per
   argumentposition, så `{0}` och `{2}` utan `{1}` ger fel värde på fel plats.
5. **Svensk text skrivs på svenska**, inte maskinöversatt engelska. Kort, aktiv
   form: "Ändra ett värde, se exakt geometri".
6. **Sektionskommentarer** (`// ---- wizard ----`) grupperar nycklar. Lägg nya
   nycklar i rätt sektion i **båda** mapparna så filerna förblir jämförbara.

## `validation_*`-nycklar är ett specialfall

Varje `GearWarning.code` som `GearSpec.validate()` producerar i `core` måste ha en
motsvarande `validation_<kod>`-nyckel i båda språken. `PrintAdvisorKeysTest` och
`RingGeometryValidationTest` kontrollerar detta, men bara för de koder de känner
till — lägger du till en ny varning ska du lägga till nyckeln **och** överväga att
utöka testet.

## Granska en skärm

Sök efter literaler i Compose-filer:

```powershell
Select-String -Path 'android\src\main\java\com\gearforge\app\*.kt' -Pattern 'Text\(\s*"' |
    Where-Object { $_.Line -notmatch 'I18n\.t' }
```

Kontrollera sedan att varje träff antingen är en enhet/symbol eller en riktig
översättningsmiss.

## Verifiera i appen

Språket kan bytas utan att installera om — det är den enda tillförlitliga
kontrollen av att en skärm faktiskt är lokaliserad:

```powershell
adb shell cmd locale set-app-locales com.gearforge.app --user 0 --locales sv
adb shell am force-stop com.gearforge.app
adb shell am start -n com.gearforge.app/.MainActivity
```

Gå igenom skärmen och leta efter engelska ord i den svenska vyn. Byt tillbaka med
`--locales en`. `tools/store-screenshots/capture.ps1` har `SetAppLocale`-hjälpfunktionen
för precis detta.
