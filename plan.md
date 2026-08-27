# Plan — MVP: "Sweep Runner" (arbetsnamn)

En rundad gubbe går automatiskt framåt på en rak bro. Spelaren sveper bort hinder,
samlar mynt, använder abilities och klarar nivåer — med progression som drivkraft.

## Mål med MVP:n
- Spelbar kärna: gubbe som går framåt, svep-bort-mekanik, mynt och 3 abilities.
- 10 nivåer i 2 teman, med nivåval och successivt stigande svårighet.
- Ekonomi: mynt köper abilities och skins (skins ger liten statbonus).
- Skärmar: start, inställningar, nivåval, butik, spelskärm, resultatskärm.
- Global leaderboard, annonser (rewarded + interstitial), ljud- och haptik-känsla.

## Beslut & motiveringar
1. **Huvudkänsla:** Samlande & progression — mynt, abilities och skins är den
   drivande motivationen; man spelar för att låsa upp och bli bättre. Det ger
   starkast återkommande spelande, vilket är det som gör ett mobilspel attraktivt
   på sikt. (Användaren valde rekommenderat alternativ.)
2. **Core loop (det man gör varje sekund):** Alla mekaniker kombineras —
   svepa bort hinder, abilities, styrning i sidled och skyddslinjer — men
   **styrning av gubben i sidled introduceras successivt när nivåerna blir
   svårare**. Spelet blir därmed lätt att komma in i och växer i djup.
   (Användarens val via fritext: "alt 5, men gör alt 3 som del när nivåerna blir svårare".)
3. **Grafik:** Mix — kodritade former (block, mynt, moln, menyer) + bild-/AI-resurser
   för karaktären och detaljerade bakgrunder. Bäst balans mellan snyggt resultat
   och rimlig arbetsinsats. (Användaren valde rekommenderat alternativ.)
4. **Abilities i MVP (3 st):** Magnet (drar in mynt), Sköld/invincible
   (kan inte knuffas ned) och Megasvep (ett svep tar flera hinder). De täcker
   samlande, försvar och attack. (Användaren valde rekommenderad uppsättning.)
5. **Nivåer:** 10 fasta nivåer uppdelade i 2 teman (t.ex. daghimlen + en
   lava-/nattmiljö). Ger progression och visuell variation utan för stor
   arbetsbörda. (Användaren valde rekommenderat alternativ.)
6. **Ekonomi:** Full mynt-ekonomi — mynt köper både abilities och skins.
   **Skins ger dessutom en liten statbonus** (t.ex. +smidighet så gubben inte
   rubbas lika lätt), men bonusen hålls liten/balanserad och skins kostar en
   del mynt. (Användarens val av alt 3 + egen fritext.)
7. **Leaderboard:** Global via Google Play Games Services — riktiga spelare
   världen över, gratis, bygger tävling och kvarhållning. (Användaren valde
   rekommenderat alternativ.)
8. **Reklam:** Rewarded (valfri belöningsvideo) + interstitial (helskärm i
   naturliga pauser, t.ex. efter avklarad nivå). Bra intäkter utan att störa
   spelandet. (Användaren valde rekommenderat alternativ.)
9. **Liv/förlust:** 3 liv (3 hjärtan) + möjlighet att fortsätta mot mynt
   eller belöningsvideo. Rättvist, matchar konceptbilderna och ger en naturlig
   intäktskrok. (Användaren valde rekommenderat alternativ.)
10. **Språk:** Svenska + engelska (valbart i inställningar) i MVP; fler språk
    läggs till vid en eventuell lansering. (Användarens val: alt 3 + fritext.)
11. **Namn:** Arbetsnamn "Sweep Runner". Namn kan bytas senare om det visar sig
    upptaget. (Användarens val: alt 4 + fritext.)

## Skärmar i MVP:n
- Startskärm (spela, butik, inställningar)
- Inställningar (ljud, haptik, språk, leaderboard)
- Nivåval (10 nivåer, 2 teman, lås/klar-markeringar)
- Butik (abilities + skins)
- Spelskärm (HUD: nivå, poäng, liv, mynt; abilities-knappar)
- Resultatskärm (nivå klar/förlust, poäng, belöningar)

## Utanför MVP:n (senare)
- Fler språk, fler abilities/teman, fler nivåer
- Riktiga köp (mynt för pengar) och annonsfri uppgradering
- Musik (MVP har enkla ljudeffekter), fler skins

## Återstående praktiska förberedelser (du behöver ordna)
- Google Play Console-konto (för leaderboard + publicering)
- AdMob-konto (för reklam; test-ID:n funkar under utveckling)
- Grafikresurser för karaktär/bakgrunder (levereras eller AI-genereras)
- Slutlig namnkontroll av "Sweep Runner"

## Nästa steg
1. ✅ Arkiv av gammal mattespels-kod klart (2026-08-19) — se `archive/`.
2. Behåll och bygg vidare på PlatformServices, GameState, Ui, Fx, I18n.
3. Uppgradera libGDX till senaste och lägg till annons-/leaderboard-SDK:er.
4. Bygg spelskärmen (core loop) först, sedan skärmar, ekonomi och integrationer.

## Teknisk strategi (motorval)
- **Motor för MVP:** libGDX + Kotlin med **pseudo-3D (2.5D)** — perspektivritad väg
  och djup-skalade objekt, utan en full 3D-motor.
- **Senare:** motorbyte (t.ex. Unity/Godot) är möjligt men inte nödvändigt —
  libGDX har även äkta 3D. Vid byte återanvänds plan, grafik, ljud och nivådesign,
  medan koden skrivs om.
- **Beslut:** bygg MVP i libGDX nu; utvärdera uppgradering/byte efter MVP.

## Spikade detaljer (från Q&A)
1. **Bro/väg-layout:** Börjar med alt 1 — bred rak bro av vita/grå block med
   3 svaga filer (mittlinje + kanter). Övriga varianter (böljande, sektioner,
   varierande bredd) kan experimenteras in senare som nivåvariation.
   (Användarens val: alt 1 + fritext om framtida experiment.)
2. **Hinder-beteende:** Mix — stillastående hinder som grund, med inslag av
   rörliga (rullande/gungande/nedfallande) på högre nivåer. Lugn inlärning +
   växande utmaning. (Användaren valde rekommenderat alternativ.)
3. **Svep-gest:** Snabb flick (fingerrörelse) i valfri riktning över hindret —
   hindret flyger av. Snabbast och mest förlåtande när det går fort.
   (Användaren valde rekommenderat alternativ.)
4. **Abilities i spel:** Knapp + cooldown — abilities låses upp en gång i butiken
   och används sedan fritt med kort väntetid mellan användningarna. Enkelt och
   rättvist. (Användaren valde rekommenderat alternativ.)
5. **Mynt:** Mix — en trygg myntlinje i mitten + bonusmynt på svårare ställen.
   Ger både avslappnad insamling och risk/belöning. (Användaren valde
   rekommenderat alternativ.)
6. **Träff/knuff:** Vackla + knuffas i sidled; vid kanten får spelaren en kort
   chans att rädda gubben (kant-grepp via snabbt svep). Dramatiskt och
   förlåtande. (Användaren valde rekommenderat alternativ.)
7. **Nivåslut:** Mål-port med flagga + konfettieffekt när gubben passerar.
   Tydligt mål och fira-känsla. (Användaren valde rekommenderat alternativ.)
8. **Mitt-dragning (visualisering):** Svag guide-linje/pil mot mitten längre
   fram. Hjälper spelaren läsa rörelsen utan att störa. (Användaren valde
   rekommenderat alternativ.)
9. **Tempo:** Konstant hastighet inom en nivå, högre basfart i senare nivåer.
   Förutsägbart och enkelt att balansera. (Användaren valde rekommenderat
   alternativ.)
10. **Skins:** Färg + tillbehör i MVP (helt nya karaktärer senare).
    **Standard-skin ska se ut som karaktären i konceptbilden** (liten gubbe med
    ryggsäck). (Användarens val: alt 5 + fritext.)
11. **Nivådesign:** Handgjorda nivåer (alla 10 designas för hand). Full kontroll
    över svårighet och kvalitet. (Användarens val: alt 1; vill att allt skapas åt
    dem eller att jag förklarar hur bildresurser kan hämtas — förklaras i slutet.)
12. **Nivåmål:** Alla nivåer har samma mål — nå brons slut utan att förlora
    alla liv. Variation kommer från hinder och teman. (Användaren valde
    rekommenderat alternativ.)
13. **Stjärnbetyg:** 1–3 stjärnor baserat på poäng + mynt; 3 stjärnor kräver
    dessutom att nivån klaras felfritt (\"Perfekt\"). Ger omspelbarhet.
    (Användaren valde rekommenderat alternativ.)
14. **Combo-system:** Combo-multiplikator (x2, x3...) vid snabba svep i rad, med
    synlig indikator/timer på skärmen. Belönar skicklighet och ger tydlig
    progression. (Användaren valde rekommenderat alternativ.)
15. **Tutorial:** Korta kontextuella tips + en extra förlåtande första nivå
    (\"lär genom att göra\"). Spelaren kommer igång direkt. (Användaren valde
    rekommenderat alternativ.)
16. **Paus-meny:** Pausknapp med fortsätt/starta om/meny/ljud på-av + auto-paus
    vid samtal/hemknapp. Skyddar mot orättvisa förluster. (Användaren valde
    rekommenderat alternativ.)
17. **Ljud/musik:** Bara ljudeffekter i MVP (genereras i kod, inga ljudfiler
    krävs). Musik läggs till senare med rätt licens. (Användaren valde
    rekommenderat alternativ.)
18. **Antal skins:** 3 st vid lansering (standard + 2 att köpa), fler läggs till
    efter lansering. (Användarens val av alt 5.)
19. **Prisnivåer:** Abilities billigare (nås tidigt), skins dyrare (kosmetik,
    "ska kosta en del"). (Användaren valde rekommenderat alternativ.)
20. **Fortsättning:** Max 1 fortsättning per nivå (mot mynt eller video),
    återställer alla 3 liv. Rättvist och tydligt. (Användaren valde
    rekommenderat alternativ.)
21. **Livsystem:** Liv är per nivå — varje nivå startar alltid med 3 liv, ingen
    global mätare eller väntetid. Spelaren kan alltid spela. (Användaren valde
    rekommenderat alternativ.)
22. **Interstitial-frekvens:** Varannan avklarad nivå, aldrig de två första
    nivåerna. Bra balans mellan intäkt och spelupplevelse. (Användaren valde
    rekommenderat alternativ.)
23. **Belöningsvideo:** Används på två ställen — fortsättning vid förlust och
    dubbla mynten efter en klarad nivå. Två naturliga, högt värderade
    belöningar. (Användaren valde rekommenderat alternativ.)
24. **Teman (2 st):** Daghimmel (blå himmel, moln) + **Rymden** (mörk rymd med
    stjärnor/planeter). (Användarens val: fritext "rymden".)
25. **Målgrupp:** Alla åldrar (familjevänligt innehåll). Bredast publik och
    passar den glada stilen. (Användaren valde rekommenderat alternativ.)
26. **Vy (korrigering av kontrollfråga 1):** Pseudo-3D (2.5D) — vy snett
    bakifrån/ovanifrån och lätt från sidan, likt konceptbilden, med perspektivdjup
    mot horisonten. (Användarens fritext.)
---
# Komplett specifikation — MVP (för utvecklingsteamet)

> Notering: uppdragsmallen nämnde "matteuppgifter". Det gamla mattespelet är nu
> arkiverat (se `archive/`), så den punkten tolkas som spelets **kärninteraktion
> med hinder** (svep bort + abilities). Matte ingår INTE i MVP:n.

## 1. Spelarvy och kameraperspektiv
- **Stil:** pseudo-3D (2.5D) — en 3D-liknande vy som i konceptbilden, sedd snett
  **bakifrån/ovanifrån och lätt från sidan**, så djupet och gubbens ryggsäck syns
  i perspektiv.
- **Gubben:** liten figur i **nedre tredjedelen**, vänd bort/framåt (rygg +
  ryggsäck syns), gåendes mot en punkt på horisonten. Rundad, mjukt skuggad
  3D-look.
- **Världen:** bron/vägen är vit/grå och går i perspektiv från nedre skärmkant mot
  en horisont i övre kanten (smalnar mot en försvinnandepunkt) — det ger djup.
  Hinder står på vägen framför gubben och blir mindre ju längre bort de är.
- **Kamera:** fast vinkel (låst perspektiv). Världen rullar mot spelaren i samma
  takt som gubben går; gubben hålls på fast skärmposition (nedre tredjedel,
  centrerad i sidled).
- **Sidostyrning:** flyttar gubben tvärs över vägen (i bildens sidled) inom vägens
  kanter. Kameran följer INTE i sidled.
- **Teknisk lösning:** 3D-looken byggs med perspektivprojektion av 2D-element
  (objekt skalas efter djup, vägen ritas i perspektiv) — genomförbart i libGDX
  utan en full 3D-motor.

## 2. Rörelse och styrning
- **Framåt:** konstant, automatisk gånghastighet; ökar per nivå. Ingen bakåtrörelse.
- **Sidled:** håll + dra fingret horisontellt för att styra. Introduceras
  successivt: nivå 1–2 går gubben själv mot mitten; från nivå 3 kan spelaren styra.
- **Mitt-dragningskraft:** när spelaren inte styr dras gubben mjukt mot brons
  mittlinje — men mot en punkt **längre fram**, så kurvan blir framåtriktad (inte
  en hård tvär sväng).
- **Acceleration/broms:** mjuk acceleration in i nivån (~0,5 s). Ingen manuell
  broms eller speedboost i MVP (hastighet är nivåbaserad).
- **Knapplayout mobil:**
  - Svep (snabb fingerrörelse) över ett hinder = sopa bort hindret.
  - Håll + dra horisontellt = styra gubben i sidled (högre nivåer).
  - Tre ability-knappar nederst: **Magnet, Sköld, Megasvep** (tryck = aktivera).
- **Tangentbord (desktop/testläge):** A/D eller piltangenter = sidostyrning,
  mus-drag = svep, 1/2/3 = abilities.
- **Visualisering av riktning/hastighet:** gubben lutar i styrriktningen; en
  subtil linje kan visa "dragning mot mitten längre fram". Hastigheten syns via
  hur snabbt bro-bitar rullar och ev. lätta fartstrimmor.

## 3. Interaktion med hinder (kärninteraktionen)
- **Presentation:** hinder står på bron framför gubben som block, bollar,
  trianglar och staplar (varierande former/storlekar per nivå).
- **Aktivering:** spelaren sveper snabbt över ett hinder för att sopa bort det
  innan gubben når det.
- **Rätt (borttaget i tid):** hindret flyger av med effekt; gubben passerar;
  poäng + ev. mynt delas ut.
- **Fel (ej borttaget):** gubben blockeras och knuffas mot brons kant. Faller han
  av förloras ett liv (se §4 och §8).
- **Abilities påverkar:** Megasvep = ett svep tar flera hinder i rad; Sköld =
  gubben kan inte knuffas ned under effekten; Magnet = drar in mynt automatiskt.

## 4. Spelflöde och progression
- **Start:** Nivåval → spelskärm → gubben accelererar in (~0,5 s).
- **Mål per nivå:** nå brons slut utan att förlora alla liv. Poäng = avstånd +
  borttagna hinder + mynt.
- **Nivåer:** 10 st i 2 teman (nivå 1–5 = daghimmel, nivå 6–10 = rymden).
  Nivåer låses upp i ordning (nivå N+1 kräver att nivå N är klar).
- **Avslut:** brons slut nås → "Nivå klar"-skärm (poäng, mynt, ev. interstitial).
  Alla liv förlorade → "Förlust"-skärm med Spela om / Fortsätt (mynt eller video).
- **Svårighetskurva:** fler och tätare hinder, högre basfart, sidostyrning från nivå 3.

## 5. HUD och återkoppling
- **Överst (fast):** NIVÅ, POÄNG, LIV (3 hjärtan), MYNT (ikon + antal).
- **Nederst:** 3 ability-knappar med aktiva/cooldown-tillstånd (nedtonade när ej redo).
- **Tipsruta vid nivåstart:** t.ex. "Svep bort hinder innan han når dem".
- **Visuell återkoppling:** svep-spår, hinder som flyger iväg, poängpopup,
  skakning vid träff/fall, partiklar vid mynt.
- **Ljudåterkoppling:** se §7.

## 6. Animationer och visuella detaljer
- **Gångcykel:** ben/armar svänger, kroppen guppar lätt, ryggsäcken vickar.
- **Riktningsindikator:** gubben lutar åt styrhåll; ev. subtil linje mot
  "mitten längre fram".
- **Effekter:** hinder bort = snabb "poff" + poängpopup; träff = skakning +
  gubben vacklar; mynt = glittrande partiklar + räknare.
- **Skärmövergångar:** mjuk fade (~0,3 s) mellan menyer; kort zoom-in vid nivåstart.
- **Teman:** daghimmel (blå himmel, moln) vs rymden (mörk rymd, stjärnor/planeter).

## 7. Ljud och musik
- MVP har enkla **ljudeffekter** (musik skjuts upp, enligt beslut).
- Effekter: mynt-plock, svep (whoosh), hinder borttaget (poff), knuff (thud),
  fall (fallande + duns), nivå klar (kort fanfar), knapptryck.
- **Haptik:** lätt vibration vid träff/knuff, kraftig vid fall.

## 8. Felhantering och kantfall
- **Vid kanten:** gubben kan inte lämna bron. Knuffas han mot kanten utan att
  falla glider han tillbaka mot mitten (mitt-kraften).
- **Kollision med hinder:** blockeras + knuffas i sidled; flera hinder kan kedja
  knuffar.
- **Flera fel (0 liv kvar):** förlust. "Fortsätt" (mynt/video) återställer 3 liv
  och behåller poäng/progress i nivån; annars "Spela om" (nivån startas om).
- **Avsluta mitt i nivå:** nivåförloppet sparas inte (startas om); mynt/highscore
  och köp sparas direkt och permanent.
- **Reklam ej tillgänglig** (ingen video/internet): videoknappar döljs eller
  visar "ej tillgänglig"; spelet fungerar ändå fullt ut.
- **Leaderboard ej inloggad:** poängen sparas lokalt och skickas vid nästa
  inloggning; lokal topplista visas under tiden.
- **Rotation/avbrott:** spelet fortsätter i porträtt; tillstånd sparas vid paus.

## 9. Tekniska krav för MVP
- **Plattformar:** Android (minSdk 24, target/compileSdk 35), porträttläge.
  Desktop (libGDX) som utvecklings-/testläge med samma kod.
- **Prestanda:** stabilt 60 FPS på medelklass-telefon; objekt- och
  partikelpooling, inga frekventa allokeringar i spelloopen.
- **Logisk skärmupplösning:** 720×1280 med FitViewport (skalar till alla enheter).
- **Funktionsdugliga delar:** core loop, 10 nivåer, 3 abilities, mynt-ekonomi,
  minst 3 skins, leaderboard (Google Play Games Services), annonser (rewarded +
  interstitial), lokal sparning, 2 språk, ljud + haptik.
- **Robusthet:** inga krascher vid paus/återupptagning, rotation eller
  annonsvisning; spelartillstånd sparas automatiskt vid köp och nivåslut.

## Slutlig sammanfattning (beslut i en blick)
| Område | Beslut |
|---|---|
| Huvudkänsla | Samlande & progression |
| Core loop | Svep bort hinder + abilities; sidostyrning från nivå 3 |
| Grafik | Mix: kodritat + bild-/AI-resurser |
| Abilities | Magnet, Sköld, Megasvep |
| Nivåer | 10 nivåer, 2 teman (1–5 dag, 6–10 rymden) |
| Ekonomi | Mynt köper abilities + skins; skins ger liten statbonus |
| Leaderboard | Global (Google Play Games Services) |
| Reklam | Rewarded + interstitial |
| Liv | 3 liv + fortsättning (mynt/video) |
| Språk | Svenska + engelska |
| Namn | "Sweep Runner" (arbetsnamn, kontrolleras) |
