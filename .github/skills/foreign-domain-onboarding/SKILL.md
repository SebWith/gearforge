---
name: foreign-domain-onboarding
description: "Metod för att arbeta i en kodbas, ett system eller en domän du inte känner: rekognosering, antaganden, konstanter, verktygsprobning och när du ska stanna. Use when: uppgiften ligger utanför GearForge; du möter ett okänt ramverk, språk eller system; du ska arbeta i ett annat repo; du vet inte var du ska börja; du måste ta reda på hur något byggs, testas eller körs."
---

# Att arbeta i en okänd domän

Du är GearForge-specialist. Det betyder inte att du bara kan GearForge — det betyder
att du har en metod. Den här skillen är metoden för allt utanför din hemmabana.

Kärnan: **du kan inte läsa dig till en kodbas, du måste mäta den.** Varje antagande du
inte prövat är en framtida överraskning.

---

## 1. Rekognosering — fem frågor innan du rör något

Svara på dessa **innan** du redigerar en enda rad. Varje svar ska komma från ett
kommando eller en fil, inte från ett antagande.

| Fråga | Var svaret finns |
|---|---|
| **Vad är detta?** | `README`, `package.json`/`build.gradle`/`pom.xml`/`Cargo.toml`, toppnivåkataloger |
| **Hur bygger det?** | Byggfilens tasks/scripts. Kör det. |
| **Hur testar jag?** | Testkatalogen, testrunnern, CI-workflowen |
| **Vad får jag inte röra?** | `.gitignore`, hemlighetsfiler, genererade kataloger, lås-filer |
| **Hur vet jag att jag lyckats?** | Den befintliga testsviten, linter, eller ett körbart kommando |

Hittar du ingen testsvit: **säg det**. Avsaknaden ändrar hur försiktig du måste vara,
och det är en av de viktigaste sakerna att rapportera.

---

## 2. Proba verktygen — lita aldrig på `Get-Command`

Ett verktyg som finns i PATH kan ändå vara obrukbart. Windows Store-aliaser
(`python.exe`, `flutter.exe`) passerar `Get-Command` men ger exit 9009 vid körning.

```powershell
# Fel: bevisar bara att något ligger i PATH
Get-Command python

# Rätt: bevisar att det kör
python --version      # eller: py --version, node -e "console.log(1)"
```

Proban du behöver i en ny miljö:

| Vad | Proba |
|---|---|
| Python | `py --version` **och** `python --version` — de kan vara olika saker |
| Node | `node --version` |
| Java | `java -version` |
| Android | `adb version` + `adb devices` |
| Git | `git rev-parse --show-toplevel` — ger dig repo-roten och om du ens står i ett repo |

**Skriv ned vilka som fungerade.** Det är den första saken nästa agent behöver veta.

---

## 3. Konstanter som biter varje gång

Dessa är inte projektspecifika. De gäller i varje ny miljö och de kostar tid varje gång
de glöms.

| Fälla | Symptom | Motmedel |
|---|---|---|
| **Icke-ASCII i skriptfiler** | "Missing closing '}'" i en fil med balanserade klamrar | Håll skript rent ASCII. PowerShell 5.1 läser `.ps1` utan BOM som CP1252 — `✓` blir `“`, som är en strängavgränsare |
| **Stdin-kodning** | `ö` blir `?`, regex matchar inget, skriptet blir tyst no-op | Läs `OpenStandardInput()` som råa byte, avkoda explicit som UTF-8 |
| **Sökvägar med mellanslag eller accenter** | Kommandon klipps av | Citera **alla** absoluta sökvägar |
| **`&&` i PowerShell** | Parsningsfel | Använd `;` |
| **Terminalen visar bara sista raderna** | Ett bygge ser ut att "stanna tidigt" men kör | Skriv utdata till en **fil** och läs filen. Verifiera med `Get-Item` på utdatafilen |
| **`*>` skriver UTF-16** | Loggen blir oläsbar | Använd `2>&1 \| Out-File -Encoding utf8` |
| **Fel SDK i PATH vs byggfil** | Bygget klagar på ett SDK som finns | Läs byggfilens sökväg (`local.properties`, `.nvmrc`, `go.mod`) — inte PATH |
| **Tyst fångade fel** | "Det bara gör ingenting" | Misstänk en fångad exception. Leta efter `catch`, `Result.failure`, tomma `catch {}` |

**Hypotesen ska prövas, inte tros på.** När något beter sig underligt: isolera det i ett
minimalt fall och bevisa orsaken innan du ändrar något i produktionen.

---

## 4. Bygg en mental modell innan du ändrar

1. **Hitta ingångspunkterna** — var börjar exekveringen? Var möts data och logik?
2. **Följ ett dataflöde** hela vägen genom systemet, från inmatning till utmatning.
   Det avslöjar arkitekturen snabbare än att läsa katalogträdet.
3. **Leta efter konventioner, inte bara kod.** Namngivning, felhanteringsmönster,
   testupplägg. Att följa dem är billigare än att införa egna.
4. **Hitta det som är otestat** — där är risken. I GearForge var det
   `GearGLView.kt` (1200 rader, noll tester).
5. **Läs historiken.** `git log --oneline -20` berättar vad som nyligen gått sönder och
   vad teamet bryr sig om.

---

## 5. Osäkerhetsbudget

Innan du börjar: bedöm hur mycket du får gissa.

| Signal | Tolkning |
|---|---|
| Det finns tester du kan köra | **Hög budget.** Kör, läs felet, iterera |
| Det finns en linter eller typkontroll | **Medelhög.** Maskinen fångar dina misstag |
| Det finns varken test eller typkontroll | **Låg.** Varje ändring är en gissning — håll dem små och reversibla |
| Ändringen är oreversibel (data, publicering, schema) | **Noll.** Stanna och fråga |

Låg budget ⇒ små steg, täta kontroller, och skriv ned varje antagande i rapporten.

---

## 6. När du ska stanna

Stanna och fråga när:

- två försök på samma steg misslyckats
- antagandet är oreversibelt eller dyrt att backa ur
- du behöver en hemlighet, ett konto eller ett beslut om produktbeteende
- du upptäcker att uppgiften egentligen ligger i en annan domän

Stanna **inte** för att du inte hittat svaret än. Steg 1–3 i eskaleringsstegen
(`agent-operating-protocol`, avsnitt 3) är ditt ansvar.

---

## 7. Lämna platsen bättre än du fann den

När du lärt dig något om en ny miljö: **skriv ned det**, så nästa försök börjar där du
slutade i stället för på noll.

Dokumentera alltid:

- hur det byggs, testas och körs (exakta kommandon)
- vilka verktyg som saknas eller är trasiga i miljön
- konstanter du snubblat på (kodning, sökvägar, kvotering)
- vad som är otestat eller riskabelt

Detta är den enda investering som gör att den andra uppgiften i en okänd domän går
snabbare än den första.
