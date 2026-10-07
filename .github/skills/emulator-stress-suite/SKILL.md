---
name: emulator-stress-suite
description: "Operativmanual för stresstest av GearForge i emulatorn: de elva täckningsspåren, felinjektionsrecept, kantfallskatalogen, uthållighetsprotokollet, race- och prestandamätningar samt beviskrav per spår. Use when: stresstesta eller belastningstesta appen; köra felinjektion, fuzz eller monkey; leta race, ANR eller minnesläcka; köra uthållighet över tid; täcka kantfall och gränsvärden; mäta prestanda eller kontrollera tillgänglighet i praktiken; avgöra om ett stresstest är tillräckligt. Trigger words: stress, stresstest, belastning, load, fuzz, monkey, felinjektion, fault injection, uthållighet, endurance, race, ANR, läcka, leak, edge case, kantfall, meminfo, gfxinfo, uiautomator, appops, trim-memory."
---

# Stresstestsvit (Android / GearForge)

Hur appen pressas systematiskt. Den här filen är **hur**; rollbeskrivningen
(`.github/agents/gearforge-emulator-stress.agent.md`) är **vad** och **varför**.

Ett stresstest som inte kan upprepas är en anekdot. Varje recept nedan ska kunna
köras igen, av någon annan, med samma utfall.

## 0. Probering — innan något annat

```powershell
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
& $adb version                                  # finns adb?
& $adb devices                                  # svarar enheten med "device"?
& $adb shell getprop sys.boot_completed         # vänta tills "1", gissa inte
& $adb shell wm size                            # skärmstorlek — koordinater beror på den
```

Standardläget: **1080×2340**, enhet `emulator-5554`, endast användare 0.
Kör i **två** API-nivåer (`mc-api26` och `mc-target`) — beteendet skiljer sig, och
"fungerar på min AVD" är inte ett resultat.

Ett recept du inte kört i **den här** imagen är obekräftat. Proba kommandot, se att det
faktiskt gör något, markera det som verifierat. Ett kommando som tyst inte gör något är
värre än inget kommando alls.

## 1. Sanering av utgångsläget

| # | Kontroll | Beviset |
|---|---|---|
| 1 | `Get-Item android\build\outputs\apk\debug\android-debug.apk` | Storlek **och** tidsstämpel. Debug ≈ **23 521 668 byte**. Avvikelse = fel build. |
| 2 | `adb shell pm path com.gearforge.geargenerator` + `ls -l <path>` | Samma storlek på enheten som på disk |
| 3 | `adb shell pm list packages \| Select-String gearforge` | Att inget **gammalt** paket ligger kvar bredvid (`com.gearforge.app`-fällan) |
| 4 | `adb shell cmd package resolve-activity --brief com.gearforge.geargenerator` | Rätt komponent: `.../com.gearforge.app.MainActivity` |
| 5 | `adb logcat -c` | Tyst = rensad. Misslyckas den: `adb shell log -t GF_STRESS start` och filtrera från markören. |
| 6 | `adb shell screencap` + läs av skärmen | Utgångsläget, med **mätta** koordinater |
| 7 | `adb shell dumpsys meminfo <pkg>` + `dumpsys meminfo <pkg> \| Select-String Pss` | Baslinje för minne **och** file descriptors |

Spara baslinjen. Utan den finns ingen platå att jämföra mot.

## 2. De elva spåren

Fullt varv innebär **alla** spår. Ett spår som hoppas över ska stå i rapporten med skäl
— inte utelämnas tyst.

| # | Spår | Primär metod | Beviset |
|---|---|---|---|
| 1 | Statisk analys | `:android:lint`, `:android:compileDebugKotlin`, `py tools/i18n_audit.py`, `py tools/check_hardcoded_strings.py`, `py tools/check_persistence.py`, läsning av den felande vägen | Utfall per kommando; noll nya fel |
| 2 | Dynamisk enkelväg | Start → wizard → arbetsyta → parameter → export | Skärmdumpar i sekvens + ren logg |
| 3 | Integration | 6 exportformat, MediaStore/Downloads, dela, öppna sparad fil, `GearType`-urval | `py tools/verify_export.py <fil>` per format |
| 4 | Regression | `.\gradlew.bat :core:test --rerun` | Antal tester ur `core/build/test-results/test/*.xml`, jämfört med förra körningen |
| 5 | Belastning | Se §3 | `Skipped N frames`, `ANR in`, svarstider, minneskurva |
| 6 | Felinjektion | Se §4 | Loggrad + skärmdump per injektion |
| 7 | Kantfall | Se §5 | Värde, skärmdump, formelutfall |
| 8 | Uthållighet | Se §6 | Platå i `meminfo` och fd-antal |
| 9 | Race och timing | Se §7 | Reproduktionsfrekvens över 20 körningar |
| 10 | Prestanda | Se §8 | Uppmätta millisekunder före/efter |
| 11 | Tillgänglighet och ändamålsenlighet | Se §9 | Nodlista + skärmdump + omdöme mot syftet |

## 3. Belastningsrecept

```powershell
# Snabbtryck på en växel: fyra tryck under en sekund. Sluttillståndet ska vara detsamma
# som starttillståndet - inte "nästan".
for ($i = 0; $i -lt 4; $i++) { & $adb shell input tap 986 2028; Start-Sleep -Milliseconds 250 }

# Parametertryck i hundratal: 1000 ändringar i följd, avbrott om något fastnar
for ($i = 0; $i -lt 1000; $i++) { & $adb shell input swipe 220 1648 780 1648 120 }

# Typbyten: 50 st, mät minnet före och efter
for ($i = 0; $i -lt 50; $i++) { ... }
& $adb shell dumpsys meminfo com.gearforge.geargenerator | Select-String "Native Heap|EGL mtrack|TOTAL"
```

`adb shell monkey -p com.gearforge.geargenerator --throttle 100 -v 5000` är ett trubbigt
men effektivt komplement — **inte** en ersättning för målstyrda flöden. Monkey hittar
okända vägar; målstyrda flöden hittar djupa fel. Kör båda, och läs loggen efter
monkey-körningen särskilt noga (den kraschar appen av skäl som inte är appens fel).

## 4. Felinjektionskatalogen

Varje rad är en injektion, ett förväntat beteende, och ett fel om beteendet uteblir.
**Proba kommandot i imagen först** — några är API-beroende.

| Injektion | Recept | Förväntat |
|---|---|---|
| Nekad lagringsbehörighet | `adb shell appops set <pkg> WRITE_EXTERNAL_STORAGE deny` (och `... MANAGE_EXTERNAL_STORAGE deny`) | Begripligt felmeddelande, ingen tyst tom fil |
| Full lagring | Fyll `/sdcard` till nära noll (`dd` i en loop), exportera | Fel som går att förstå; ingen halvskriven fil |
| Inget nätverk | `adb shell svc wifi disable` + `svc data disable`, kör UMP/annons/Billing-vägen | Ingen hängning; appen fungerar utan annonser |
| Processdöd mitt i export | Starta export, `adb shell am kill <pkg>` direkt efter | Ingen korrupt fil kvar; inget "lyckades" utan fil |
| Processdöd mitt i flöde | Samma, mitt i ett panel-/wizardsteg | State återställs eller återställs medvetet — aldrig tom skärm |
| Minnespress | `adb shell am send-trim-memory <pkg> RUNNING_LOW` (proba; nivånamn varierar) | GL-resurser och cachar släpps utan krasch |
| Bakgrunda under export | `input keyevent 3` mitt i exporten | Exporten fullföljs eller avbryts rent — aldrig halvvägs |
| Rotation under sheet | `settings put system user_rotation 1` medan en sheet är öppen | Sheet och state överlever; ingen klippt layout |
| Rotation under dragning | Rotera medan en slider hålls nedtryckt | Ingen kvarhängande bubble, inget fastnaglat värde |
| Systemtillbaka × 10 | `input keyevent 4` tio gånger i följd | Landar på en vettig plats, inte i tomt tillstånd |
| Två aktivitetsinstanser | `am start` två gånger, se `dumpsys activity` | Ingen dubbel state, ingen dubbel sheet |
| Omstart med appen öppen | `adb reboot`, vänta, starta | Sparat state intakt, ingen halv skrivning |
| Klocka flyttad | Sätt systemdatum framåt, spara och lista filer | Sortering och tidsstämplar förvirrar inte appen |
| Filnamn med `ö` | Exportera med svenskt namn | Filen skapas, går att öppna, syns rätt i listan |

## 5. Kantfallskatalogen

- **Gränsvärden:** min och max för **varje** `ParamDef` i `GearSpec.kt` — inte ett urval.
  Även ett steg utanför: 0, negativt, extremt stort.
- **Kuggtal:** under gränsen för undercut, över gränsen för vad meshen klarar.
- **Enheter:** tum ↔ mm fram och tillbaka 20 gånger — värdet ska återgå, inte driva.
- **Text:** tomt namn, 200 tecken långt namn, emoji, `"` och `\` i namnet.
- **Fontskala:** `settings put system font_scale 2.0` — klipps texten, överlappar kontroller?
- **Språk mitt i flödet:** byt till `sv` **medan** en sheet är öppen — byter texten live?
- **Tomt tillstånd:** rensa sparade filer, öppna listan — finns ett begripligt tomt läge?
- **Tidiga tillstånd:** starta appen utan nätverk, första gången, utan samtycke.

## 6. Uthållighetsprotokollet

**Längd: minst 30 minuter, helst 60.** Kortare tid döljer långsamma läckor, och
läckor är den felklass som är billigast att hitta här och dyrast att hitta i
produktion.

```
var 60:e sekund:
  - ett varv: typbyte -> orbit -> panel öppna/stäng -> parameterändring
  - mät:  adb shell dumpsys meminfo <pkg>  (Native Heap, EGL mtrack, TOTAL Pss)
  - mät:  antal öppna file descriptors
  - logga: en rad per minut till build\stress-<datum>.txt
```

**Platå, inte enskilda värden.** En enskild topp är skräpinsamling; en **monotont
växande** kurva över tio minuter är en läcka. Rendera kurvan i rapporten som siffror
per minut — en gissning om "såg stabilt ut" är inte ett bevis.

File descriptors:

```powershell
$pid_ = (& $adb shell pidof com.gearforge.geargenerator).Trim()
& $adb shell "ls /proc/$pid_/fd | wc -l"
```

Växer fd-antalet monotont: något stängs inte (ström, lyssnare, cursor, EGL-yta).
Växer `EGL mtrack`/`Native Heap`: VBO:er eller texturer frigörs inte.

## 7. Race och timing

| Recept | Vad det avslöjar |
|---|---|
| 4 tryck < 1 s på en växel | Icke-idempotent till/från-knapp |
| Dubbeltryck på FAB | Dubbel navigering — två skärmar staplade |
| Stäng/öppna panel 5 gånger med 300 ms | Halvkomponerad sheet, förlorad state |
| Dragning avbruten av rotation eller bakgrund | Kvarhängande drag-tillstånd |
| Tryck direkt vid skärmbyte (utan väntetid) | Klick som hamnar på fel skärm — den vanligaste falska "krasch" |
| 20 körningar av samma misstänkta sekvens | Frekvensen. "Ibland" är inte en frekvens. |

Kedja aldrig tryck blint: verifiera mellan stegen att skärmen **faktiskt** ändrades.

```powershell
& $adb shell screencap -p /sdcard/a.png ; & $adb pull /sdcard/a.png build\a.png
# ... åtgärd ...
& $adb shell screencap -p /sdcard/b.png ; & $adb pull /sdcard/b.png build\b.png
(Get-FileHash build\a.png).Hash -ne (Get-FileHash build\b.png).Hash   # True = något hände
```

Identiska hashvärden betyder att ingenting hände — då gick åtgärden fel, och varje
efterföljande steg är värdelöst. Den här kontrollen har räddat flera verifieringar.

## 8. Prestanda

```powershell
& $adb shell am start -W -n com.gearforge.geargenerator/com.gearforge.app.MainActivity  # TotalTime
& $adb shell dumpsys gfxinfo com.gearforge.geargenerator | Select-String "Janky|90th|95th"
& $adb logcat -c ; # rör inte skärmen i 10 s
& $adb logcat -d | Select-String "requestRender|onDrawFrame"    # render on demand
```

- **Kallstart** (`TotalTime`) mäts efter `am force-stop`, aldrig från en varm app.
- **Render on demand:** noll frames utan input. Kontinuerlig aktivitet i stillastående
  vy betyder att en loop ritar utan förändring.
- **Mesh-bygge:** tiden per kugghjulstyp. En typ som är tio gånger långsammare är ett
  fynd även utan krasch.
- Mät **före och efter** åtgärden, i samma miljö. En siffra utan jämförelse är inget.
- **Svält eller långsam?** `/proc/<pid>/task/*/schedstat` ger `run_ns wait_ns` per tråd.
  Väntetid i körkön ≥ egen CPU på `main` betyder att andra trådar tar CPU:n, inte att
  UI-koden är tung. 2026-10-01 var det miniatyrförrenderingen (`GearPreviewRenderer`) som
  svalt parameterpanelen och gav FocusEvent-ANR (`build/audit/panel-latency-20261001/`).
- **Ingen UiAutomator i mätfönstret:** dumpa före (koordinater) och efter (verifiering).
- **Debug och `.benchmark` sida vid sida:** force-stoppa *båda* före varje mätning; en
  kvarhängande ANR-dialog från det andra paketet blockerar navigeringen.
- Ny `.benchmark`-installation visar UMP-formuläret (egen appdata). Svara som debug-appen
  (`consent_status` i `__GOOGLE_FUNDING_CHOICE_SDK_INTERNAL__.xml`).
- `adb install` ger dexopt `verify`; profileinstaller 1.3.1 svarar `result=3` (ART stöds
  inte) på API 36, så en sidladdad build får ingen baseline-profil. Mät `verify` och `speed`.
- PowerShell: en funktion som heter `Measure` skuggas av aliaset `measure`
  (`Measure-Object`) och gör tyst ingenting.
- **Vem blockerar `main`?** `adb shell "setprop log.looper.<uid>.main.slow 100"` före en
  kallstart (API 28+, uid ur `pm list packages -U`) loggar varje Looper-meddelande ≥ 100 ms
  i system-bufferten: `Slow dispatch took 3542ms main … h=<Handler> c=<callback>`. Callbackens
  klass namnger ägaren (`consent_sdk.zzax` = UMP, `gms.ads…` = annons-SDK, `WV.*` = WebView).
  Återställ med `setprop … ''`. Mätt 2026-10-02 (`build/audit/ads-anr-20261001/`).
- **`input tap` mäter ingen latens.** Ett tryck in i en blockerad `main` returnerade på ~0,1 s
  (main var blockerad i 9 s); först nästa tryck väntade (upp till 10 s). ANR-beviset är
  `dumpsys dropbox --print data_app_anr` och `am_anr` i events-bufferten.
  `/data/anr/*` går inte att läsa som shell på dessa images — dropboxen har samma trace.
- **En ANR-dialog kan överleva `am force-stop`.** 2026-10-01 låg fönstret
  `Application Not Responding: com.gearforge.geargenerator` kvar 15 min och fyra kallstarter efter
  den enda ANR:en; den täckte appen och tog alla tryck, så de körningarna kunde inte ge någon ny
  ANR. Kontrollera `dumpsys window windows` före varje mätfönster och stäng dialogen
  (`android:id/aerr_close`) — räkna ANR ur dropboxen per pid, inte ur dialogens närvaro.
- **Läckta aktiviteter:** `adb shell run-as <pkg> kill -10 <pid>` (SIGUSR1: ART kör en full GC,
  loggar `SIGUSR1 forcing GC`; bara debug-builden) och sedan `dumpsys meminfo <pkg>` →
  `Activities:`/`WebViews:` räknar nåbara instanser. Jämför efter varje aktivitetsåterskapande
  (fontskala).

## 9. Tillgänglighet och ändamålsenlighet i praktiken

```powershell
& $adb shell uiautomator dump /sdcard/ui.xml ; & $adb pull /sdcard/ui.xml build\ui.xml
```

Läs XML:en — den ger noder, `contentDescription`, `bounds` och `clickable`.

| Kontroll | Underkänt om |
|---|---|
| Varje klickbar nod har etikett | En ikonknapp utan `contentDescription` |
| Touch-mål | Träffytan under 48 dp |
| Fokusordning | Ordningen följer inte den visuella läsordningen |
| Kontrast | Text mot bakgrund går inte att läsa (granska skärmdumpen, mät vid tvekan) |
| Textskalning | Fontskala 2.0 klipper text eller överlappar kontroller |
| Språk | Engelska ord i den svenska vyn (kör `SetAppLocale 'sv'` och **läs** skärmdumpen) |

**Ändamålsenlighet** — för varje skärm, kontroll och dialog:

1. Finns den för ett skäl som går att formulera i en mening?
2. Gör den det den lovar, utan att användaren behöver veta hur den är byggd?
3. Syns tillståndet (på/av, valt, sparat, fel)?
4. Går handlingen att ångra eller backa ur?
5. Överlever den rotation, bakgrund och processdöd?
6. Är ett fel som uppstår begripligt för en användare som inte kan koden?

Ett "nej" på någon av dessa är ett fynd — även om inget kraschar. En kontroll som
finns men inte går att använda är ett fel, inte en skönhetsfläck.

## 10. Beviskrav per spår

| Påstående | Krävt bevis |
|---|---|
| "Ingen krasch" | Loggrad citerad efter `logcat -c` |
| "Felet är borta" | Samma sekvens körd igen **och** ett test som faller utan fixen |
| "Ingen läcka" | Minneskurva per minut över ≥ 30 min **och** fd-antalet |
| "Fungerar i appen" | Skärmdump tagen **och granskad** till innehållet |
| "Exporten är giltig" | `py tools/verify_export.py <fil>` |
| "Lokaliseringen är intakt" | `py tools/i18n_audit.py` = 0 **och** svensk skärmdump utan engelska |
| "Prestandan är okej" | Millisekunder före och efter, samma miljö |
| "Tillgänglig" | Nodlista ur `uiautomator dump` + granskad skärmdump |

**Backningsbeviset** är skillnaden mellan en fix och en gissning: backa åtgärden, kör
testet, det ska **falla**. Passerar det både med och utan fixen skyddar det ingenting.

## 11. Var resultaten hamnar

- Kommandoutdata: `build\stress-<datum>.txt`, `build\v-build.txt` (repots konvention).
- Skärmdumpar: `build\`. **Granska dem** — filens existens är inte ett bevis.
- Testantal: `core/build/test-results/test/*.xml`.
- Mätkurvor: en rad per minut, med tidsstämpel.

**Färskhetsfällan:** ett `Remove-Item` kan misslyckas tyst medan en tidigare
`Out-File`-ström håller filreferensen. Kontrollera tidsstämpeln innan du läser en
statusfil — annars drar du slutsatser om förra körningen. Detta har redan gett en
felaktig slutsats i det här repot.

## 12. Fällor som redan kostat tid

- **Håll emulatorns skärm vaken.** `adb shell svc power stayon true` +
  `settings put system screen_off_timeout 2147483647`. Ett sovande skal gör att varje
  UI-drivet test mäter en svart skärm: `uiautomator` svarar
  `ERROR: null root node returned by UiTestAutomationBridge`, tryck landar ingenstans och
  den resumed aktiviteten är launchern medan appens process lever — vilket läses som
  "appen vägrar komma fram". Det kostade tre avbrutna körningar i rad.
- **En dödad körning lämnar sin `uiautomator`-process kvar** och den håller
  accessibility-bryggan, så nästa dump ger samma `null root node`. Åtgärd:
  `adb shell pkill -f uiautomator` och försök igen. Harneskets dump ska ha inbyggd retry —
  och aldrig returnera `null`, för då blir felet ett null-referensfel i stället för
  "dumpen misslyckades".
- **`am force-stop` följt av `am start` inom ~1 s startar inte appen.** Processen kan
  starta utan att aktiviteten visas, och `am start` rapporterar ändå `result code=0`.
  Vänta ~3 s och **verifiera att processen lever** (`pidof`) innan du pollar UI:t — annars
  ser felet ut som en saknad etikett och du felsöker fel sak.
- **Fasta väntetider är en gissning.** Polla på etiketten i stället
  (`WaitAndTap`/`WaitUntil`). Landningsskärmen, wizardens miniatyrrutnät och
  presetlistan tar olika lång tid varm och kall.
- **Verifiera att du är på rätt skärm innan loopen börjar.** En uthållighetskörning som
  navigerat fel mäter ingenting men rapporterar självsäkra siffror. Låt den **avbryta
  högt** om förutsättningen inte kan etableras (`exit 1`), inte fortsätta tyst.
- **En bakgrundskörning i en task-terminal dör när nästa task startas** (terminalen
  återanvänds). Använd en dedikerad terminal (`mode=async`) för långa körningar, eller
  starta inga andra tasks medan den kör.
- **`am kill` dödar inte en förgrundsprocess.** Den återtar bara cachade processer. Mätt
  2026-09-20: `pidof` var oförändrat efter `am kill` — ett "processdöd"-test byggt på det
  testar ingenting. Använd `adb shell kill -9 <pid>` (kräver `adb root`) och **verifiera att
  pid faktiskt ändrades**. Det är hela beviset.
- **`adb root` behövs för fd-räkning.** Utan root ger `ls /proc/<pid>/fd` "Permission
  denied" och `wc -l` svarar 0 — vilket ser ut som en stabil app. `adb root` fungerar på
  debug-imagen; kör det före uthållighetsprotokollet.
- **APK-storleken är inte ett färskhetsbevis.** Två builds med olika källkod gav exakt samma
  storlek (23 521 808 byte). Använd **tidsstämpeln**, eller jämför enhetens `stat -c %s` mot
  diskens storlek *och* tid.
- **`adb`-kommandon i långa kedjor kapar terminalens utskrift.** Verifiera med `Get-Item`/`ls -l`
  på utdatafilen i stället för att tro att kommandot dog.
- **PowerShell-sessionen kan tappa sin PATH och sina cmdlets.** Symptom: `powershell`, `cmd`
  och till och med `Get-Content` svarar "not recognized", och parsern vägrar `&`. Sessionen
  är då förbrukad — starta en **ny terminal** (t.ex. via en VS Code-task) i stället för att
  försöka laga den. Använd `[System.IO.File]::ReadAllLines(...)` som nödlösning.
- **`uiautomator dump` loggar själv `D AndroidRuntime: >>>>>> START`.** Ett loggfilter på
  bart `AndroidRuntime` rapporterar därför alltid en krasch som är din egen instrumentering.
  Matcha `FATAL EXCEPTION`, ` E AndroidRuntime`, `ANR in `, `EGL_BAD`, `GL_INVALID`.
- **Skript som skriver via `*>` ger UTF-16-filer.** Läs dem med `-Encoding Unicode` (eller
  `[Text.Encoding]::Unicode`), annars ser de ut att vara tomma.
- **Räkna aldrig samma rapport med två olika mönster.** Lint-rapporten gav 21 varningar med
  ett mönster och 26 med ett annat (samma innehåll) — den "nya" siffran såg ut som en
  regression. Gruppera per regel och jämför regellistan, inte totalen.
- Repo-sökvägen innehåller `ö` — citera absoluta sökvägar. Kedja med `;`, aldrig `&&`.
- Använd `py`, aldrig `python` (trasig Store-alias, exit 9009).
- `.ps1`-filer ska vara **rena ASCII** — PS 5.1 läser BOM-lösa filer som CP1252 och ett
  svenskt tecken i en strängliteral har redan kraschat ett skript.
- Kända brusmönster i loggen: `E rdv` (annons-SDK, errorCode 65586/401),
  `E HwcComposer: getLuts failed` (emulatorgrafik), `E AppOps: attributionTag not declared`
  (annons-SDK). Dokumentera dem — ignorera dem inte otestat.
- `adb shell input` behöver luft: UI:t vill ha ~1,2 s mellan tryck i normala flöden.
- UMP-samtyckesformuläret visas på enhetens språk, inte appens. Känd begränsning, inte
  ett fel — men verifiera att det inte blockerar flödet.

## 13. Verifierade resultat — GearForge, 2026-09-20

Mätt, inte gissat. Siffrorna är till för att nästa körning ska kunna jämföras.

| Vad | Resultat | Metod |
|---|---|---|
| Kallstart | 2,8–4,4 s | `am start -W` × 15, `restartcycle` |
| Start/stopp-cykler | 15 varv, PSS 130–157 MB, ingen trend | `restartcycle` |
| Monkey | 3000 events, noll `FATAL`/`ANR`/`EGL` | `monkey` |
| Processdöd | pid byttes, omstart ren, logg ren | `killtest` |
| Mesh-bygge, standardkugg | 111 ms JVM (varav triangulering 83 ms), 5632 trianglar | `MeshBuildPerformanceTest` |
| Samma mesh i emulatorn | 2,9–5,1 s | `GF_EXPORT`-loggen |
| Exportpanelens layout | Stabil efter fix (chippen y=1244 konstant) | `exporttiming` |
| 3MF / STEP | 1 OK, 0 fel | `verify_export.py` |
| IGES **före** fix | 16 834 poster längre än 80 tecken → **1 med fel** | `verify_export.py` |
| IGES **efter** fix | 22 496 poster, alla ≤ 80 → 1 OK | `verify_export.py` |
| Tillgänglighet | 0 oetiketterade klickytor, 0 mål under 48 dp | `a11y_audit.py` |
| Första start efter `pm clear` | UMP-formuläret täcker skärmen i ~8 s | `newuser` |

**Skalan skiljer sig mellan JVM och emulator med ~30×.** En siffra mätt i `core` säger vad
algoritmen kostar; en siffra mätt i emulatorn säger vad användaren väntar på. Båda behövs —
och en "långsam" siffra i emulatorn är inte automatiskt en algoritmisk defekt.

## 14. Etiketter som mäts, inte gissas — defektklassen och dess instrument

En klass av fel som inte syns i en skärmdump och inte i en logg: **en text som klipps tyst**.
`Text` med `maxLines = 1` kapar svansen utan ellips och utan varning, så varje box som är
mindre än sitt innehåll tappar information i det tysta.

Uppmätt 2026-09-21: mätetiketterna i 3D-vyn ritades i en fast 136 dp-box (374 px vid
1080 × 2400 / 2.75). `Pitch diameter  20.000 mm` lades ut 374 px brett och ritades som
`Pitch diameter  20.000` — enheten var borta, i en app där mm mot tum är hela frågan. De tre
längsta etiketterna låg exakt på klämvärdet, de två kortaste under det, vilket är varför det
såg ut som "diametrarna saknar enhet".

**Instrumentet är `-Action hudprobe`** (och `bedprobe` för byggplattan). Det läser
uiautomator-trädets nodbredder och skriver ut dem per etikett, så "klipps texten?" blir en
jämförelse mellan två tal i stället för en gissning:

```
hudlabel chars=25 w=402 text='Outer diameter  22.000 mm'     <- efter fixen: textens egen bredd
hudlabel chars=25 w=374 text='Outer diameter  22.000 mm'     <- före: klämd mot 136 dp
```

**Regressionstestet är `-Action hudscale`.** En box som mäts från sin text växer med
systemets teckenstorlek; en fast box gör det inte. Körningen mäter den bredaste etiketten vid
`font_scale 1.0` och `1.5` och kräver att kvoten är ≥ 1.3:

| Läge | Bredaste etikett 1.0 | 1.5 | Kvot | Dom |
|---|---|---|---|---|
| Före fixen (fast box) | 374 px | 374 px | 1,0 | **FAIL** |
| Efter fixen (mätt) | 402 px | 605 px | 1,5 | **PASS** |

Det är backningsbeviset: kontrollen faller utan åtgärden. Samma klass fanns på två ställen
till i samma filfamilj — byggplattans ticketiketter (fast 54 × 16 dp) och dess bildtext, vars
klämning räknades mot en gissad 168 dp-bredd som texten inte hade. Alla tre mäts nu av
`LabelFit.fitLabel`, och de fasta konstanterna är borttagna så att det inte finns något att
falla tillbaka på.

**Svep efter klassen:** `grep_search` på `maxLines = 1` tillsammans med `.width(\d+\.dp)`.
`GearWorkspace`-rubriken har `maxLines = 1` men **ellips**, vilket är ärligt — användaren ser
att texten är kapad. Det är skillnaden mellan en medveten förkortning och en tyst förlust.

## 15. Emulatorn: att få tillbaka en död instans

Kedjan nedan är vad som faktiskt krävdes 2026-09-21, i den ordning den behövdes. Varje steg
lärde sig av att det föregående inte räckte.

| Steg | Kommando | Varför |
|---|---|---|
| 1 | `tools\emulator-status.ps1` | En tillförlitlig lägesbild. Skrivs till fil, för terminalen ljög: `Get-Process … \| Format-Table` visade **elva tomma rader** och lästes som "inga emulatorer körs" medan två körde. |
| 2 | `tools\emulator-cold-restart.ps1 -Port 5554 -Avd mc-target` | Dödar paren för **en** port, rensar AVD-lås, startar och väntar på `sys.boot_completed`. |
| 3 | Samma skript | Fångar emulatorns egen utdata till `build\emulator-5554.txt`. Utan den filen fanns **ingen** bevisning: ingen händelselogg, ingen gästlogg, ingen konsolutskrift. |

**Stoppar du allt och startar om räcker inte.** Fyra hinder i rad:

1. **En halvstartad emulator håller sin AVD.** Den svarar inte på sin konsolport, syns inte i
   `adb devices` — men blockerar varje ny start med
   `FATAL | Running multiple emulators with the same AVD`. Skriptet dödar nu varje process som
   bär `-avd <namn>` när porten är tyst: en emulator utan konsolport är inte en emulator.
2. **En kraschad emulator lämnar kvar sina lås.** `hardware-qemu.ini.lock` och
   `multiinstance.lock` i `%USERPROFILE%\.android\avd\<avd>.avd\`. De rensas bara när porten är
   tyst, så ett levande syskon skadas inte.
3. **`opengl32sw.dll` saknas i SDK-installationen** (`C:\Android\sdk\emulator\`). Emulatorn
   loggar `Critical: Failed to load opengl32sw`, faller tillbaka på systemets OpenGL och
   **kraschar**, varefter en modal crash-dialog väntar på en människa. Det läses som "emulatorn
   bootar aldrig". Detta går **inte** att flagga bort — det är en trasig SDK-installation och
   kräver ominstallation av `emulator`-paketet.
4. **`swiftshader_indirect` är ett namn emulatorn inte längre accepterar.**
   `emulator.exe -help-gpu` (37.1.11) listar exakt: `auto`, `host`, `software`, `lavapipe`,
   `swiftshader`, `swangle`. Ett okänt värde ignoreras **tyst**, emulatorn går tillbaka till
   `auto`, väljer värdens OpenGL och kraschar. Repots äldre task-poster skickar
   `swiftshader_indirect` — det ser konfigurerat ut och är det inte. Loggen avslöjar det på
   raden `emuglConfig_init: gpu_mode_requested: auto`.

**Minnet dödar emulatorn.** Mätt samma dag: 11 JVM-processer höll **9 511 MB** (Gradle-daemons,
Kotlin-daemon, testworker) på en 32 GB värd med två emulatorer igång — och monkey-körningen tog
emulatorprocessen. `.\gradlew.bat --stop` frigjorde **4,2 GB** (11 → 8 processer, 5 266 MB).
Kör det innan enhetstester, och räkna med att Gradle-daemonerna är en del av lasten.

**`kill -9` kräver root.** Utan `adb root` kan adb-skalet inte signalera en annan uids process:
`killtest` lämnade pid oförändrat, aktiviteten kvar i förgrunden, och skrev två skärmdumpar av
en levande app medan den såg ut att testa processdöd. `adb root` + omkörning gav pid-byte
(11438 → "") och en ren återstart (11974, RESUMED). `killtest` är nu en hård FAIL när pid inte
byts — ett test som inte kan döda det det påstår sig döda testar ingenting.

**`adb logcat -c` kan misslyckas** (`failed to clear the 'main' log` på API 26). Då är
`-t 600`-fönstret det som gäller, och det ska stå i rapporten att baslinjen inte nollställdes.

## 16. Harnesket får inte ljuga — tre tysta fel som hittades i mina egna skript

| Felet | Symptom | Åtgärd |
|---|---|---|
| `UiDump` lämnade kvar förra filen när `uiautomator dump` misslyckades | `adb pull` kopierade **föregående skärm** och kontrollen läste den som aktuell | Både enhetsfilen och den lokala filen raderas före varje försök |
| `TapTextOnScreen` kontrollerade fönstret, inte scrollbehållaren | En nod kan ligga utanför sin container men innanför fönstret | Under en fold går allt via `TapScrolling`, och anroparen ska verifiera **utfallet**, inte lita på trycket |
| En `Check` som krävde att en rad syntes utan att skrolla | Compose håller noder utanför den synliga ytan borta ur tillgänglighetsträdet, så kontrollen föll i tre körningar i rad av ett skäl som aldrig var appens | Kontrollen mäter **nåbarhet** (skrollning tillåten) och att walkthroughen kommer tillbaka |

**En kontroll ska vara en egenskap appen lovar.** "Raden syns utan att skrolla" var inget löfte,
bara ett antagande jag skrivit in i testet — och ett test som faller av det skälet kostar en
timme och lär ingen något om appen.

### 16b. Den dyraste lärdomen: **två körningar samtidigt**

Uppmätt 2026-09-21. En walkthrough-körning rapporterade att varje etikett saknades på en skärm
där samtyckesformuläret syntes i skärmdumpen, och att `uiautomator dump` svarade **ingenting alls**.
Slutsatsen såg ut som en trasig app eller en trasig enhet. Sanningen: **tre harneskörningar körde
samtidigt**, och alla delar samma brygga och samma skärm.

| Vad som hände | Varför det såg ut som appens fel |
|---|---|
| Två körningar turades om att starta `uiautomator` | Varannan dump kom tillbaka tom |
| En tom dump lästes som "etiketten finns inte" | `check[PASS] no tip after restart` — ett **falskt grönt** på en skärm ingen hade läst |
| Samtyckesformuläret kunde aldrig stängas | "appen fastnar i consent" |

**Skyddet är nu strukturellt, inte en vana:**

1. `tools\harness-kill.ps1` — stoppar kvarvarande körningar, frigör bryggan och **bevisar** att den
   svarar igen (`uiautomator dump -> ... nodes=NN`).
2. En **låsfil** (`build\harness.lock`) med ägande pid. En ny körning **vägrar starta** om hållaren
   lever och fortfarande kör harnesket. En låsfil från en dödad körning tas över — ett skydd ingen
   kan ta sig förbi blir borttaget i stället för lagat.
3. `add-utf8-bom.ps1` får **inte** matcha sig själv eller sin anropande shell: `harness-kill; stress-app`
   i samma kommando lägger `stress-app.ps1` i förälderns kommandorad, och den första versionen dödade
   sin egen förälder (exit -1).

### 16c. Frånvaro, närvaro och **okunnighet** är tre olika svar

`HasText` returnerade `$false` både när etiketten saknades och när dumpen hade misslyckats. Det gör
"jag kunde inte titta" till ett sakligt påstående — och värst: `WaitGone` returnerade då `$true`, så
ett **observerbart ingenting** blev ett grönt `check[PASS]`.

Nu är hjälparna trevärda: `$true` = sedd, `$false` = säkert frånvarande, `$null` = kunde inte läsas.

| Hjälpare | Frågan den svarar på |
|---|---|
| `WaitText` | dök den upp? (`$null` om ingen läsbar observation gjordes) |
| `WaitGone` | är den borta **nu**? |
| `NeverSeen` | visade den sig **aldrig** under fönstret? |
| `Check` | `$null` räknas som **underkänt** — ett påstående från en oobserverad skärm är inte bevis |

Anrop som `Check "x gone" (-not (HasText ...))` är förbjudna: `-not $null` är `$true` i PowerShell.

### 16d. En droppmeny och dess utlösare har **samma text**

Typmenyn i editorn visar aktuell kugghjulstyp på en knapp, och menyn innehåller en post med exakt
samma text. Med menyn öppen träffar `TapText "Spur gear"` **posten**, inte knappen — så nästa val
"erbjöds inte" i en meny som aldrig öppnades. Mätt: `'Rack & pinion' not offered in the menu`.
Skyddet är att **räkna** antalet kända etiketter i trädet: fler än en ⇒ menyn är öppen ⇒ stäng den
först. Att anta menyläget är att gissa.

### 16e. Verktygslagret självt: fyra fel som bara syntes när de rättades

| Felet | Hur det såg ut | Lärdomen |
|---|---|---|
| `stress-rebuild` väntade 40 × 10 s på en enhet som inte fanns i `adb devices` | "skriptet hänger" i sju minuter | Kontrollera **anslutning** före **tillstånd**: billigaste kontrollen först |
| `killtest` utan `adb root` | pid oförändrat, aktiviteten kvar — men två skärmdumpar och ingen felsignal | Ett test som inte kan döda det det påstår sig döda ska **underkännas**, inte tigas ihjäl |
| `add-utf8-bom.ps1` hade själv 22 icke-ASCII-byte | `check-ps1` flaggade det nya verktyget för samma fel det lagade | Det nya verktyget ska granskas av samma grind som resten — `checked=16 problems=0` |
| `"$tag: ..."` i PowerShell | `SYNTAX_ERRORS(1)` — hela skriptet slutade parsas | `${tag}:` — och `check-ps1.ps1` fångade det före en enda enhetskörning |

### 16f. Terminalen som kanal: varför allt går via filer och skript

Uppmätt flera gånger samma dag: `Get-Process … | Format-Table` skrevs ut som **elva tomma rader**
och lästes som "inga emulatorer körs" medan två körde; `Get-Content -Tail` gav ett **äldre**
fönster än filens verkliga slut; och ett inbäddat `$p='…'` i en `-Command`-sträng blev `=…`
eftersom den yttre shellen expanderade variabeln.

Reglerna som följer av det: skriv resultatet till en **fil** och läs filen; lägg allt som
innehåller variabler i ett **skript**, aldrig i en inbäddad kommandosträng; och lita aldrig på en
tabellutskrift du inte kan läsa tillbaka.

### 16g. Etiketter med `&` kan aldrig matchas mot rått XML

`uiautomator` skriver hierarkin som XML, så `Rack & pinion` ligger i dumpen som
`text="Rack &amp; pinion"`. Den som matchar den råa etiketten får **aldrig** träff — och felet ser ut
som "etiketten finns inte", vilket är den värsta formen ett harnesfel kan ta.

Mätt 2026-09-21: typmenyn kartlades till **13 av 14** poster, och den saknade var exakt den här.
Första misstanken var menyläget (16d) — fel. Fixen är `XmlEscape` före varje etikettmönster (i
`TapText`, `HasText`, `TapTextOnScreen`, `WaitUntil`, `ProbeLine`, `InfoRows`) plus `XmlUnescape`
för det som skrivs ut. Tecknen som drabbas: `&`, `<`, `>`, `"`.

### 16h. Ett spår som inte kan underkännas är dekoration

`-Action faults` utförde fem felinjektioner och tog skärmdumpar — utan en enda assertion. Den skrev
`fault=kill: pid after kill = 28248 | restarted pid = 28248`, alltså **samma pid**, och drog ingen
slutsats. Den använde dessutom `am kill`, som bara återtar **cachade** processer och därför aldrig
kan döda appen man tittar på.

Nu: `kill -9` med `EnsureRoot`, och varje injektion slutar i en dom — pid faktiskt bytt, arket
överlevde rotationen, appen ligger kvar i förgrunden efter tio back, appen lever efter
`RUNNING_LOW`. **Fem injektioner, fem påståenden.**

**Räkna påståendena i ett spår. Noll påståenden betyder att spåret inte körs, hur mycket det än
skriver ut.**

### 16i. Ett verktyg som inte kan rapportera har inte kört

`tools\verify_export.py` dog medan den skrev sin IGES-dom:

```
UnicodeEncodeError: 'charmap' codec can't encode character '\u2264' in position 35
```

Orsak: domen innehåller "alla ≤ 80 tecken" med det riktiga tecknet, och anroparens konsol var CP1252.
Exporten hade lyckats — det var **verifieringen** som inte producerade någon dom, vilket är värre än
ett underkännande: kontrollen såg ut att ha körts.

Fixen sitter i verktyget, inte hos anroparen: `sys.stdout.reconfigure(encoding="utf-8")` i toppen av
`verify_export.py`, så varje anropare — harnesket, CI, en människa — får samma sak. Att sätta
`PYTHONIOENCODING` hos varje anropare hade lagat en av dem.

**Verifieringskedjan är själv ett föremål för verifiering.** Efter varje ändring i `tools\`:
`check-ps1.ps1` (syntax och kodning), och kör det nya verktyget mot en känd fil innan du litar på
dess dom.

### 16j. Byggidentiteten — spåret som avgör om de andra elva betyder något

Mätt 2026-09-22: **enheten med API 36-evidenens körde en annan build än den som låg på disk.**
`AndroidManifest.xml`, `classes2/3/4.dex` och ett dussin resurser skilde; filen var 535 byte mindre
och daterad 19:19 kvällen före, medan APK:n på disk var byggd 00:49. Varje lane som hade kört dessförinnan
hade dragit sina slutsatser från bytes som inte längre motsvarade källkoden — inklusive en
uthållighetskörning på 29 minuter.

**Rotorsaken var två saker, och båda satt i harnesket:**

1. Identiteten bevisades bara inuti `tools\stress-rebuild.ps1`, som måste anropas **med avsikt**.
   En lane kunde därför köras utan att någonsin ha frågat vilken build enheten kör. En grind som
   måste kommas ihåg är ingen grind.
2. Kontrollen jämförde **filstorlek**. Två olika builds kan hamna på samma storlek (kontrollen sa
   `same_as_disk=True` om en build som var 1,5 timme äldre än den på disk), och samma storlek är
   inte samma innehåll.

**Åtgärden:** `tools\apk_identity.py` hashar varje post i båda APK:erna och **namnger** de poster som
skiljer. `AssertInstalledBuild` i `stress-app.ps1` kör den i toppen av **varje** lane och vägrar
starta om enheten inte kör bygget på disk:

```
REFUSING TO RUN: the installed build is not the build on disk. Run tools\stress-rebuild.ps1 first.
VERDICT: LANE ABORTED (stale build on the device)
```

**En hypotes som mätningen fällde:** den 535 byte mindre filen förklarades först som att
installeraren tar bort v1-signaturen. Fel — en färsk `adb install -r` av samma APK gav
**post-identiska** filer på både API 26 och API 36. Skillnaden var en gammal build. Det är därför
verktyget klassar `META-INF/*` för sig men inte antar att en skillnad där är ofarlig: en teori som
inte prövats får inte stå kvar i en kommentar som nästa agent läser som fakta.

**Regeln:** en lane som drar en slutsats från skärmen ska först ha bevisat **vilka bytes** som ligger
bakom skärmen. Identiteten prövas med innehåll, aldrig med storlek eller tidsstämpel.

### 16k. `input motionevent` — en verktygsskillnad mellan API-nivåerna

Mätt 2026-09-22, samma kommando mot båda enheterna:

| Enhet | `adb shell input motionevent` | Slutsats |
|---|---|---|
| `emulator-5554` (API 36) | `java.lang.IllegalArgumentException: Argument expected after "motionevent"` | Underkommandot **finns**; felet gäller bara att argument saknas |
| `emulator-5556` (API 26) | `Error: Unknown command: motionevent` | Underkommandot **finns inte** |

Proberna skiljer sig alltså åt och båda är entydiga: ett argumentfel betyder att kommandot finns,
`Unknown command` att det inte gör det. Receptet i `races`:

```powershell
$motionevent = ((& $adb shell input motionevent 2>&1 | Out-String) -notmatch "Unknown command")
```

Detektionen **loggas** (`races start: ... motionevent=True|False`), för en lane som i tysthet hoppar
över sin hårdaste injektion på en API-nivå rapporterar en skillnad mellan nivåerna som i själva
verket är en skillnad i testet.

### 16l. Två PowerShell-fällor som kostade ett varv var

| Skrivet | Felet | Rätt |
|---|---|---|
| `Note "races round=$i: PROCESS GONE"` | `$i:` tolkas som en scope-referens: *"Variable reference is not valid. ':' was not followed by a valid variable name character"* | `${i}` i stället för `$i` |
| `foreach ($s in ...) { ... } *> fil.txt` | `*>` kan inte hänga på ett block: *"The term '*>' is not recognized"* — och **inget skrivs till filen** | `& { foreach (...) { ... } } *> fil.txt`, eller samla i en variabel |
| `($adb shell dumpsys ...)` | Kommandoanrop inuti en parentes kräver anropstecknet: *"Unexpected token 'shell'"* | `(& $adb shell dumpsys ...)` |

Alla tre fångas av `py tools\check-ps1.ps1` i samma sekund de skrivs — kör den efter varje ändring i
harnesket, inte efter nästa körning.

## 17. Granska alltid ett fynd innan du rapporterar det

Fyra av fynden i den här sviten var **mina egna mätfel**, inte appens. Var och en hade
kostat förtroende om den gått ut i en rapport:

| Larmet | Vad som var fel | Lärdomen |
|---|---|---|
| `glGenTextures` utan `glDeleteTextures` = texturläcka | `run()` gör `initEgl()` → `onSurfaceCreated()` **en gång** och `releaseEgl()` river kontexten. Objekten dör med den. | Läs livscykeln, inte bara anropet. En obalans i ett enskilt anrop är inte en läcka. |
| 12 av 12 klickytor saknade etikett | Compose lägger etiketten i ett **barn**. Auditen läste bara nodens egna attribut. | En a11y-kontroll ska läsa **hela subträdet**. Annars flaggar den varje container. |
| Exportchippen såg ~28 dp höga ut | Mätt: 132 px = 48 dp. Ögonskattning ur en skärmdump dög inte. | Mät, titta inte. |
| 21 → 26 lint-varningar = regression | Samma regler; jag hade räknat samma rapport med två olika mönster. | Gruppera per regel, jämför regellistan, inte totalen. |

**Regeln:** varje fynd ska formuleras som en hypotes och **motbevisas** innan det rapporteras.
Ett falskt fynd kostar mer än ett missat, eftersom det skickar någon annan att laga något
som fungerar.

## 18. Systemfönster över appen — de tysta navigeringsfelen

Två olika fönster har kostat körningar, och båda lurar harnesket på samma sätt: de är
**separata windows**, så `uiautomator` dumpar dem och varje app-etikett är genuint borta ur
hierarkin. Harnesket rapporterar då ett navigeringsfel som inte har med appen att göra.

| Fönster | När | Kännetecken | Åtgärd |
|---|---|---|---|
| UMP-samtyckesformuläret | Första starten efter `pm clear` (raderar samtycket), ~8 s in | Landningsskärmens skärmdump faller 2,1 MB → 525 KB | `WaitAndTap "Consent"` (eller `"Do not consent"`) |
| Systemets ANR-dialog | `System UI isn't responding` — emulatorns `com.android.systemui`, typiskt efter färsk boot med swiftshader | Texten `isn't responding` i dumpen | `Wait` (behåller appen) eller `Close app` |

Samtycket sparas, så formuläret kommer **en gång per installation**. Därför fungerar alla
körningar utom den allra första utan det steget.

**Diagnostiken som avgör:** ta en skärmdump. Vid den senaste händelsen hade GearForge renderat
hela landningsskärmen **perfekt** under systemdialogen — appen var oskyldig, ANR:en tillhörde
`com.android.systemui`.

**Implementationsfälla:** låt inte dismiss-funktionen gå via samma vänta-och-tryck-helper
som själv anropar dismiss-funktionen. Det ger obegränsad rekursion (hände, och upptäcktes
bara genom att läsa sin egen ändring en gång till). Använd den råa text-tryckningen inuti
dismiss.

**Döm aldrig tredjepartsfönster med vår a11y-grind.** UMP:s knappar är 39–40 dp; det är
Googles webvy, inte GearForge.

## 19. När ett test inte får rapportera grönt

En vakt som *kan* passera av fel skäl är farligare än ingen vakt: den ger falskt
självförtroende. Fyra regler, alla från en oberoende granskning av `exporttiming`:

- **Läs inte bara första mätpunkten.** Kontrollera villkoret i **varje** sampel. En rad som
  finns och sedan försvinner är lika trasig som en som aldrig fanns.
- **Exakt likhet är för hårt.** En entré-animation kan sätta sig en pixel. Använd en tolerans
  (2 px räcker) — en vakt som ropar varg ignoreras.
- **Matcha inte etiketter på ett språk.** `text="Triangles:"` finns bara i engelska katalogen;
  på svenska heter raden `Trianglar:`. Vakten rapporterade då en layoutbugg som inte fanns.
  Matcha `(Triangles|Trianglar):` och `(Size|Storlek):`.
- **Rapportera INCONCLUSIVE när beviset inte kan diskriminera.** Appen loggar när det
  asynkrona värdet landade. Skedde det **före** första användbara sampeln hade den gamla koden
  också passerat — då är körningen värdelös och ska säga det (`exit 2`), inte grönt.

**Skripthygien:** kör `tools\check-ps1.ps1` efter varje ändring i `tools/`. Den kontrollerar
syntax **och** hög-byte. En BOM-lös `.ps1` med hög-byte läses som CP1252 av PowerShell 5.1,
och `U+201C` blir då en strängavgränsare — vilket ger "Missing closing '}'" i en fil med
balanserade klamrar. En fil **med** BOM är däremot säker och ska inte flaggas.

## 20. API-matrisen — vad som faktiskt skiljer

Kör **båda** nivåerna. Mätt 2026-09-20 på `mc-target` (API 36) och `mc-api26` (API 26):

| Skillnad | API 26 | API 36 |
|---|---|---|
| Exportväg | **Legacy**: skriver direkt till publika Downloads och kräver `WRITE_EXTERNAL_STORAGE` som måste beviljas (`pm grant ... WRITE_EXTERNAL_STORAGE`) | MediaStore.Downloads, ingen behörighet |
| `adb logcat -c` | **Misslyckas** ("failed to clear the 'main' log") — använd markören `log -t GF_STRESS start` och filtrera från den | Fungerar |
| `dumpsys meminfo` | Annat utdataformat: raden `TOTAL PSS` saknas, så naiv filtrering ger tomt | `TOTAL PSS` finns |
| Kallstart | 2,16–2,71 s över 15 varv | 2,8–4,4 s |
| Skärmkoordinater | Annan layout (t.ex. "Create new gear" y≈1730) | y≈1796 |

**Koordinaterna är det starkaste argumentet för textbaserade tryck.** En koordinatbaserad
harness som fungerar på API 36 träffar fel kontroll på API 26 utan att säga till.

Flera enheter samtidigt: sätt `ANDROID_SERIAL` (t.ex. `emulator-5556`). Varje `adb`-anrop
respekterar den, så harnesket blir enhetsparameteriserat utan att röra ett enda anrop.

Fullständig miljöbild: `.github/skills/emulator-ui-verification/SKILL.md` och
`/memories/repo/gearforge-emulator.md`.
