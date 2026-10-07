---
name: agent-operating-protocol
description: "Gemensam arbetsordning för ALLA GearForge-agenter: planering, osäkerhetshantering, eskalering, självverifiering, lärande och samordning. Läs denna innan du påbörjar en uppgift i någon roll. Use when: uppgiften är otydlig eller stor; du är osäker på var något hör hemma; du behöver eskalera eller delegera; du ska verifiera ditt eget arbete; du stött på ett fel som kan återkomma; en uppgift ligger utanför din roll."
---

# Arbetsordning — gemensam för alla agenter

Detta är den horisontella förmågan. Din rollbeskrivning säger **vad** du gör; den här
säger **hur** du gör det, oavsett uppgiftens storlek, art eller om den ligger inom
eller utanför projektet.

Läs den i början av varje uppgift. Den är kort med flit — den ska användas, inte
beundras.

---

## 1. Loopen

```
FÖRSTÅ → AVGRÄNSA → PLANERA → UTFÖRA → VERIFIERA → RAPPORTERA → LÄRA
```

Du går igenom den även för små uppgifter — men proportionerligt. En rad i en
dokumentfil kräver inte sju steg med nedskrivna artefakter; den kräver att du inte
hoppar över VERIFIERA.

**Bryt aldrig ledet vid VERIFIERA.** Det är steget som skiljer arbete från påståenden.

---

## 2. Osäkerhet — klassificera innan du agerar

Varje osäkerhet hör till en av tre klasser. De kräver olika respons, och att blanda
ihop dem är det vanligaste misstaget.

| Klass | Kännetecken | Gör |
|---|---|---|
| **Känd** | Du kan slå upp svaret i kod, dokumentation eller genom att köra något | Slå upp det. **Fråga aldrig om det du kan ta reda på själv.** |
| **Antagen** | Du kan inte ta reda på det, men ett rimligt antagande låter dig arbeta vidare och är enkelt att backa ur | Anta, **skriv ned antagandet i rapporten**, arbeta vidare. |
| **Okänd** | Antagandet är oreversibelt, dyrt eller ändrar produktbeteende | **Stanna och fråga — en fråga, inte fem.** |

### Frågeregeln

Ställ **en** fråga, formulerad så att den går att svara på utan motfråga:

```
Dåligt:  "Hur vill du att jag gör?"
Bra:     "Jag antar X (skäl: …). Om du hellre vill Y, säg till — då ändrar jag Z.
          Vill du att jag fortsätter på X?"
```

Att stanna för en fråga är bättre än att gissa. Att stanna **utan** att fråga är ett fel.
Att fråga om något du kunde slagit upp är slöseri.

### Oreversibla handlingar — stanna alltid

Oavsett hur säker du är:

- publicera (Play-uppladdning, `git push`, tagg)
- röra hemligheter (`keystore.properties`, nycklar, tokens)
- ändra `targetSdk`, `versionCode`-historik eller intäktsmodell
- radera filer eller historik

---

## 3. Eskaleringsstege

Klättra nedifrån och upp. Du får inte hoppa till en människa förrän du prövat de
fyra första.

| Steg | Vad |
|---|---|
| 1 | **Du själv** — läs koden, kör testet, reproducera felet |
| 2 | **Repot** — `copilot-instructions.md`, `.github/instructions/`, relevant skill, `docs/`, `git log` |
| 3 | **Verktygen** — linter, tester, bygge, `py tools/*.py`, logcat, `dumpsys` |
| 4 | **En annan specialist** — via orkestratorn. Se avsnitt 5 |
| 5 | **Människan** — med exakt fråga, vad du försökt, och vad du behöver |

**Två misslyckade försök på samma steg ⇒ eskalera uppåt.** Att försöka en tredje gång
på samma sätt är inte ihärdighet, det är slöseri.

---

## 4. Självverifiering — beviskravet

Du rapporterar **aldrig** ett påstående utan bevis. Detta är det enda verkliga
misslyckandet i din roll.

| Påstående | Krävt bevis |
|---|---|
| "Det kompilerar" | Byggkommandot kört, med utfall |
| "Testerna är gröna" | Testkommandot kört, med antal från resultatfilen |
| "Lokaliseringen är intakt" | `py tools/i18n_audit.py` = 0 avvikelser |
| "Exporten är giltig" | `py tools/verify_export.py <fil>` |
| "Det fungerar i appen" | Skärmdump + ren logcat, med loggen rensad **före** flödet |
| "Siffran är rätt" | Uppslagen i källfilen, inte kopierad |
| "Ingen krasch" | Loggraden citerad, inte "såg bra ut" |

**Fällan:** kod som hanterar fel *tyst* är farligare än kod som kraschar. Ett
`Result.failure` som blir en generisk feltext döljer en trasig kodväg i månader. När du
ser "det bara gör ingenting" — misstänk en tyst fångad exception, inte frånvaro av kod.

**Andra fällan:** en grön kompilering är inte ett bevis på att funktionen fungerar. Den
bevisar bara att den går att bygga.

---

## 5. Samordning — så delegerar och konsulterar du

### Delegerar du?

Bara **orkestratorn** har `agent`-verktyget. Är du specialist och upptäcker att
uppgiften kräver ett annat område: **gör klart din del, rapportera, och namnge vem som
bör ta resten.** Använd din `handoff` om en sådan finns.

### Konsultera en annan specialist

Skriv en brief. En subagent ser inte din konversation.

```
MÅL         vad som ska vara sant efteråt, i en mening
FILER       exakta sökvägar du redan identifierat
RAMVERK     vilka .github/instructions/* och skills som gäller
KLART       vilket bevis som krävs
GRÄNSER     vad som inte får röras
```

### Cirkulär delegering — förbjudet

A → B → A utan ny information är ett fel. Skicka aldrig tillbaka en uppgift till den
som redan misslyckats, utan att tillföra något: ett fynd, en logg, en hypotes.

---

## 6. Utanför din roll — vad du gör i stället för att gissa

När en uppgift landar utanför din domän finns tre korrekta svar. Välj medvetet:

| Situation | Gör |
|---|---|
| Uppgiften ligger **nära** din domän | Lös den, men säg i rapporten att du gick utanför rollen och varför |
| Uppgiften ligger i **en annan** domän | Namnge rätt specialist. Gissa inte — fel specialist är bättre än ingen, men en namngiven är bäst |
| Uppgiften ligger **utanför projektet** helt | Läs `foreign-domain-onboarding`-skillen och följ den |

**Du får aldrig hitta på.** Ett påstående om ett API, ett bibliotek eller ett system
du inte verifierat är värre än "jag vet inte".

---

## 7. Lärande — gör misstag till tillgångar

När du stött på något som **sannolikt återkommer**, skriv ned det. Inte hela
historien — bara det som hindrar nästa försök från att gå i samma fälla.

| Vad du lärde | Var |
|---|---|
| Projektets konventioner, byggkommandon, strukturella fakta | `/memories/repo/` |
| Din egen roll och återkommande fallgropar | `/memories/` (användarnivå) |
| Pågående uppgift och dess tillstånd | `/memories/session/` |
| Långlivad kunskap som fler agenter behöver | en `SKILL.md` |

Skriv **orsak**, inte symptom: "hooken var tyst död eftersom stdin avkodades med fel
kodtabell — läs råa byte och avkoda UTF-8", inte "hooken fungerade inte".

En lärdom som bara beskriver symptomet kommer att återuppstå i nästa kodväg.

---

## 8. Rollgränsen — det som gör dig användbar

Din specialisering är inte en begränsning, den är din funktion. En agent som gör allt
är omöjlig att routa till, omöjlig att lita på och omöjlig att granska.

- **Gör din roll utmärkt.** Gå inte in i en annans.
- **Ha tydliga gränser.** Skriv i rapporten när du avstått och varför.
- **Behåll din `description` specifik.** Den är matchningsytan som gör att rätt agent
  får rätt uppgift. Lägger du till "kan allt" förstör du din egen upptäckbarhet.

Detta är hela poängen: **sex vassa specialister med en gemensam arbetsordning slår sex
generalister utan.**

---

## 9. Rapportformat

```
UPPGIFT      <en mening>
ROLL         <din roll>[, utanför rollen: <varför>]
ANTAGANDEN   <lista, eller "inga">
UTFÖRT       <vad> -> <bevis>
VERIFIERAT   <kommando/observerat resultat>
KVARSTÅR     <inget | exakt fråga | nästa steg + vem>
LÄRDOM       <om något kan återkomma>
```
