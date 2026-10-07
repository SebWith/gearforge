# GearForge — projektinstruktioner

Detta är den styrande kontexten för allt arbete i repot. Läs den innan du ändrar kod.

## Vad projektet är

**GearForge - 3D Gear Generator** — en Android-app som genererar parametriska,
3D-utskrivbara kugghjul. Användaren väljer kugghjulstyp, justerar parametrar och
exporterar. Appen har 14 kugghjulstyper och 6 exportformat.

Varumärket skrivs **`GearForge`** — ett ord. Skriv aldrig "Gear Forge".

## Modulkarta

| Modul | Vad den är | Får innehålla | Får ALDRIG innehålla |
|---|---|---|---|
| `core/` | Ren Kotlin, plattformsoberoende | Matematik, geometri, mesh, filskrivare | Android-import, `android.*`, `Context`, Compose |
| `android/` | Compose-UI + libGDX GL-renderare | UI, GL, AdMob, Billing, UMP, export-till-disk | Gear-matematik (den hör i `core`) |

`core` är ett rent JVM-projekt (`org.jetbrains.kotlin.jvm`) — det är därför det går
att testa utan emulator. Utnyttja det: all logik som kan ligga i `core` ska ligga där.

Nyckelfiler:

- `core/.../GearModel.kt` — `GearType`, `GearParams`, `BoreSpec`, `ToothOverride`
- `core/.../GearSpec.kt` — parameterdefinitioner (`ParamDef`), `defaults()`,
  `fields()`, `setNumber()`, `validate()`, `results()` — **937 rader, navet i modellen**
- `core/.../GearCalculator.kt`, `GearProfiles.kt`, `GearBuilder.kt` — matematik och mesh
- `core/.../StlWriter.kt`, `ThreeMfWriter.kt`, `StepWriter.kt`, `IgesWriter.kt`,
  `DxfWriter.kt`, `SvgWriter.kt` — filformat
- `android/.../GearWorkspace.kt` — huvudskärmen, `ExportSheet`, `SettingsDialog`
- `android/.../GearGLView.kt` — egen OpenGL/EGL-renderare, **1200 rader, inga tester**
- `android/.../I18n.kt` — strängkatalog EN + SV

## Bygga och testa

```powershell
.\gradlew.bat :core:test                 # snabbast, ingen emulator behövs
.\gradlew.bat :core:test --tests "*SpurProfileTest*"
.\gradlew.bat :android:assembleDebug
.\gradlew.bat :android:lint
```

- Kör alltid `:core:test` efter en ändring i `core/`. Testsviten är stor (17 filer)
  och fångar geometriregressioner.
- `core/build.gradle` sätter `maxHeapSize = "2g"` för testworkern (stresstestet
  bygger >1M trianglar). Ändra inte det.
- `gradle.properties` varnar: tvinga **inte** `-Dfile.encoding=UTF-8` på daemonen.
  Windows läser @argfile med CP1252 och sökvägen innehåller `ö` — UTF-8-tvång ger
  `ClassNotFoundException` i testworkern.

## Kommandon och miljö (Windows)

- Använd **`py`**, inte `python`. `python` är en trasig Microsoft Store-alias som
  ger exit 9009. `py` fungerar (Python 3.14).
- Proba alltid ett verktyg med ett versionskommando (`py --version`,
  `adb version`) innan du litar på det i ett skript.
- Kedja kommandon med `;` i PowerShell — **inte** `&&`.
- **Konsolens kodtabell måste vara 1252 när du bygger.** Med `chcp 850` skickar JVM:en
  källsökvägarna till Kotlin-kompilatorn i en @argfile som läses med ANSI-kodtabellen,
  och `ö` i reposökvägen blir till bokstäverna `u00F6`:
  `error: source file or directory not found: ...\u00F6verf\u00F6r skrivbord\...`.
  Felet ser ut som ett trasigt repo men är ett kodtabellsfel. Kör `chcp 1252` först
  (mätt 2026-09-20; samma rot som varningen i `gradle.properties`, men den gäller
  konsolen, inte daemonens `-Dfile.encoding`).
- `adb` ligger på `C:\Android\sdk\platform-tools\adb.exe`.
- Tillgängliga AVD:er: `Pixel7`, `mc-api26`, `mc-target`, `EggHunt`.
- Repo-sökvägen innehåller `ö` — citera alltid absoluta sökvägar.

## Release — rör inte utan att följa ritualen

`android/build.gradle` **vägrar** bygga `bundleRelease`/`assembleRelease` med Googles
test-AdMob-ID:n (utgivar-ID `3940256099942544`). Det är avsiktligt: testannonser ger
ingen intäkt och bryter mot AdMob-policy.

Release byggs uteslutande via:

Sätt `$expectedVersionCode` och `$expectedVersionName` från den avsedda versionen i
`android/build.gradle`. Bekräfta `$trustedUploadCertSha256` (64 hextecken) från en
oberoende betrodd källa, exempelvis Play Console, aldrig från AAB:n som ska granskas.

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release-aab.ps1 `
    -AdmobAppId 'ca-app-pub-6154121627229543~9677913532' `
  -AdmobRewardedUnitId 'ca-app-pub-6154121627229543/4517387519' `
  -ExpectedVersionCode $expectedVersionCode -ExpectedVersionName $expectedVersionName `
  -ExpectedCertificateSha256 $trustedUploadCertSha256
```

Skriptet kräver JDK 17+, `py` och hashkontrollerad bundletool 1.18.2 i `build/tools/`.
Det verifierar exakta ID:n, paket, version, signatur och certifikat samt frånvaro av
testutgivaren. Kräv `Verified AAB` och kvittot som skrivs vid `Receipt`: dess SHA-256
ska matcha just den färska artefakten. En existerande AAB eller enbart hittade
ID-strängar är inte ett godkänt resultat. Se release-guard-skillen för detaljer.

### Hemligheter — committa aldrig

`android/keystore.properties`, `android/release.keystore`, `.github-token`.
Alla är gitignorade. Rör dem inte, skriv dem inte i loggar, föreslå dem inte i
kodblock med värden.

## Regler som inte får brytas

1. **All UI-text går genom `I18n.t(lang, "nyckel")`.** Ingen hårdkodad sträng i
   Compose. Varje ny nyckel måste läggas i **både** `en` och `sv`-mappen i
   `I18n.kt`. Saknad nyckel faller tyst tillbaka på engelska eller på nyckeln själv
   — det syns inte i kompilering, bara i appen. Detta har redan gått sönder en gång.
2. **`core` får inte importera Android.** Gör det inte, inte ens "tillfälligt".
3. **Varje geometriändring kräver ett test.** Ändrar du en formel i `core`, lägg
   till eller uppdatera ett test i samma ändring och kör `:core:test`.
4. **Påståenden i dokumentation ska vara verifierade mot bygget.** `STORE_READINESS.md`
   har en tabell "Claims verified against the build" — uppdatera den när fakta
   ändras, och skriv aldrig ett påstående om en funktion som inte finns i koden.
5. **Trovärdiga siffror slås upp, gissas inte.** Antal kugghjulstyper står i
   `GearType`-enumen, exportformat i `ExportManager.Format`, versionCode i
   `android/build.gradle`. Läs filen i stället för att anta.
6. **`versionCode` får aldrig återanvändas.** Play avvisar en uppladdning med ett
   redan använt versionCode (detta har hänt — se `CHANGELOG.md`).
7. **Ändra inte `targetSdk` utan att gå igenom migreringslistan** i
   `STORE_READINESS.md` avsnitt 5.

## Dokumentkarta

| Fil | Roll |
|---|---|
| `ACTION_PLAN.md` | 32 punkter i 5 faser — styrande spec för implementationen |
| `MONETIZATION_CONFIG.md` | Sanning för AdMob-ID, Billing, UMP, gating |
| `PRIVACY_POLICY.md` | Sanning för integritet och Data Safety |
| `STORE_READINESS.md` | Butiksklarhet: listing-copy, assets, claim-tabell |
| `CHANGELOG.md` | Keep a Changelog-format |
| `docs/` | Designdokument (kuggsystem, presets, settings-audit) |
| `lanseringsplan-forbattringar.md` | Ursprungskällan till ACTION_PLAN |

## Agenter och routing

Repot har en orkestrator som **enda ingång** för uppgifter:
`.github/agents/gearforge-orchestrator.agent.md`. Den klassificerar uppgiften och
skickar den som subagent till rätt specialist, sammanställer och verifierar.

Välj orkestratorn när du inte vet vem som ska ha uppgiften. Välj specialisten
direkt när området är uppenbart.

| Om uppgiften rör … | Agent |
|---|---|
| Kugghjulsmatematik, mesh, filskrivare, valideringsregler, ny kugghjulstyp | `gearforge-geometry-engineer` |
| Compose-skärmar, kontroller, I18n-strängar, undo/redo, GL-viewporten | `gearforge-ui-engineer` |
| `build.gradle`, manifest, versionCode, AAB, keystore, R8, AdMob/Billing/UMP, targetSdk | `gearforge-release-engineer` |
| Verifiera i emulatorn, krasch, logcat, GL-fel, minnesläcka | `gearforge-emulator-verifier` |
| Stresstest, belastning, fuzz, felinjektion, kantfall, rotorsak och stabilisering | `gearforge-emulator-stress` |
| Play-listing, skärmdumpar, ikon, claim-tabellen, butikscopy EN/SV | `gearforge-store-listing` |
| `contentDescription`, touch-mål, kontrast, TalkBack | `gearforge-a11y-auditor` |
| Dokumentation, CI, hooks, `tools/`, agentanpassningar | ingen specialist — gör det själv |

Specialisterna har medvetet **inte** `agent`-verktyget: bara orkestratorn delegerar.
Det förhindrar cirkulär delegering (A → B → A utan framsteg). Delegering sker på
`description`-matchning och är därför probabilistisk, inte en deterministisk router.

### Förmågemodellen — horisontellt och vertikalt

Varje agent har två lager:

| Lager | Innehåll | Var |
|---|---|---|
| **Vertikalt** — rollen | Domänkunskap, arbetsyta, gränser | `agents/*.agent.md` |
| **Horisontellt** — metoden | Planering, osäkerhetsklassning, eskalering, beviskrav, lärande, samordning | `skills/agent-operating-protocol/SKILL.md` |

Det horisontella lagret är **gemensamt för alla** och gör att varje agent kan bära en
stor, otydlig eller oväntad uppgift — utan att dess `description` blir så bred att
routingen slutar fungera. För uppgifter utanför projektet finns dessutom
`skills/foreign-domain-onboarding/SKILL.md`.

**Varför inte "varje agent kan allt":** dokumentationen för VS Code-agenter listar
"Swiss-army agents" och "Role confusion" som anti-patterns, och `agents:`-routingen
matchar på `description`. Sex agenter som alla beskriver sig som universella matchar
allt — och då kan orkestratorn inte längre välja. Förmågan blir *lägre*, inte högre.
Specialisering är det som gör delegeringen träffsäker.

Efter varje ändring av en anpassningsfil:

```powershell
py tools/validate_customizations.py
```

Den fångar de tysta YAML-felen — ett oescapade kolon i en `description` eller
`handoff.prompt` gör att agenten aldrig laddas, utan någon felruta.

## Fällor som redan kostat tid

- **Filnamnsfällan i PowerShell:** `Measure-Object -Line` räknar inte sista raden om
  filen saknar avslutande nyckel. Lita inte på radantal för exakthet.
- **Långa utskrifter:** terminalen visar bara de sista raderna. Ett bygge som ser ut
  att "stanna tidigt" kör ofta fortfarande — verifiera med `Get-Item` på en känd
  utdatafil i stället för att anta fel.
- **`local.properties` och `adb` pekar på olika SDK:er.** Om Gradle klagar på SDK:n,
  kontrollera `local.properties` (`sdk.dir`) först.
- **R8 i release:** `minifyEnabled true` + `shrinkResources true` är på. Nya
  reflektion-/serialiseringsberoenden kan behöva keep-regler i
  `android/proguard-rules.pro`.
