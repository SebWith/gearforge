---
name: gl-viewport-internals
description: "GearForge:s egna OpenGL/EGL-renderare i GearGLView.kt (1200 rader, inga tester). Use when: ändra eller felsöka 3D-förhandsvisningen; EGL/TextureView-livscykel; VBO-läckor eller minnesprofilering; render-on-demand-kontraktet; kamerahantering, gizmo, plockning; GL-fel i logcat."
---

# GL-viewporten — `GearGLView.kt`

1200 rader egen OpenGL/EGL-renderare. **Inga enhetstester.** Det gör den till den
mest riskfyllda filen i modulen, och ACTION_PLAN pekar ut tre punkter som rör den
(6: livscykel, 20: render on demand, 21: minnesläckor).

`android/src/test/.../GizmoMathTest.kt` täcker gizmo-matematiken, som är utbruten
just för att den går att testa. Följ det mönstret: **bryt ut beräkning, lämna kvar
anropen.**

## Arkitekturen

```
Compose (GearWorkspace)
  └─ AndroidView / TextureView
       └─ GearGLView
            ├─ EGL-setup (display, config, context, surface)
            ├─ rendertråd (egen tråd, inte huvudtråden)
            ├─ VBO:er (vertex + index per mesh)
            ├─ shaders (enkel belysning)
            └─ CameraState (orbit/zoom/pan, gizmo, axis-snap)
```

`GearPreview3D.kt` och `GearOutline.kt` är komponenter ovanpå; `ViewportGizmo.kt`
och `CameraState.kt` är navigeringen.

## Kontrakt 0 — en enda kameradefinition (`ViewportCamera.kt`)

Renderingen är `clip = P · V · M · world`. **`M` är inte valfri:** orbiten är en
*modellrotation* `R = Ry(rotY) · Rx(rotX)` framför en fast kamera, och panoreringen
ligger också i `M`. Allt som ska placera något på skärmen måste därför använda samma
`P · V · M`.

Detta var trasigt (mätt 2026-09-20): `GearGLView.snapshotCameraState` räknade om
`P` och `V` själv och publicerade dem, och `MeasurementHud` projicerade
`P · V · world`. Måttetiketterna och deras ledarlinjer stod alltså still medan
modellen roterade under dem — felet syns bara när kameran rör sig, aldrig i en
enskild skärmdump. `rayFromScreen` var en tredje kopia, med ett påhittat öga på
`(0, 0, 40 · zoom)` och panoreringen kvar i strålen.

`ViewportCamera` äger nu all kameramatematik (projektion, vy, modellmatris,
rigid invers, plockstråle) och är fri från Android- och GL-typer, så den går att
JVM-testa. `GearGLView`, `MeasurementHud`, `BedOverlay` och plockningen använder
den. Lägger du till en ny konsument: använd `CameraState.viewMatrix`,
`projectionMatrix` och `modelMatrix` — räkna aldrig om dem.

**Fällan att känna igen:** `R` vs `R⁻¹`. `GizmoMath` roterade världsaxlarna med
`R⁻¹` i stället för `R`, vilket spegelvänder hela navigationswidgeten (pucken du
nyss tryckte på tänds som *bortvänd*). `GizmoMathTest.pucksMatchTheRenderersViewSpaceAxes`
jämför mot renderarens egna matriser och fäller varje sådan förväxling.

| Fråga | Svar |
|---|---|
| Var projicerar HUD:en? | `ViewportCamera.viewProjection(view, projection, modelMatrix)` |
| Var placeras bäddtexten? | `…, cameraState.bedMatrix` — bädden tar **inte** orbiten, den är ett bord |
| Hur blev en pixel ett modellkoordinat? | `ViewportCamera.modelRay(...)` — modellens eget system, där `pick` skär lokalplanet |

Backningsbevis för hela klassen: `:android:testDebugUnitTest` fäller sex tester när
`modelRay` får tillbaka det gamla ögat/panoreringen och `gizmoQuaternion` får
tillbaka `R⁻¹`.

## Kontrakt 0b — uppspelningen är klockaritmetik (`PlaybackClock.kt`)

Spelhastigheten får **inte** läggas i instanslistan: en ny lista rensar fasen och laddar
om VBO:erna, så modellen hoppar tillbaka till startorienteringen i samma stund som
användaren rör hastighetsknappen. Renderaren har därför en `playbackScale` som bara
skickas vidare till `PlaybackClock`, och `t` kommer från `timeSeconds(now, scale)`.

**Samma regel gäller pausen.** Play-knappen får inte heller bygga om instanslistan —
den gjorde det en gång, via en `playing`-nyckel i `remember`, och då snäppte kuggen
tillbaka till sin byggorientering varje gång användaren tryckte paus. Paus uttrycks i
stället som en fart: `PlaybackClock.PARKED_SCALE` (0). `GearWorkspace` skickar
`if (playing && !reduceMotion) playbackSpeed else 0f`, och `instances` är nycklad på
geometrin — aldrig på `playing`.

Klockan **ackumulerar** koordinaten (`coordinate += Δt · fart`) i stället för att räkna
om den från start. Det ger tre egenskaper på en gång: ett fartsbyte flyttar inte modellen
(ramen som just tog slut körde i den gamla farten), en parkerad klocka håller sin
koordinat, och väggtiden som passerar medan den är parkerad räknas inte som animationstid
(referensstämpeln flyttas fram även då). Den naiva varianten (`elapsed · ny fart`) hoppar
trekvarts varv när man trycker på en snabbare chip.

Stegen i stegen (`PLAYBACK_SPEEDS`) är bråkdelar av **grundfarten**, som är det snabbaste
steget och standardvalet: 0,125× / 0,25× / 0,5× / 1× av ett varv per sekund
(`MeshKinematics.DEFAULT_SPEED_RAD_PER_S`) — en halveringsserie nedåt från basen. Fyra
farter är alltså desamma som förut; det är numreringen och standardvalet som flyttat. Ett
chip som visar 0,5× ska betyda hälften av det man ser, inte hälften av en osynlig referens.

Mätfällan: en kugg med N kuggar ser **identisk** ut var `360/N` grader, så en skärmdump
kan varken visa farten eller ett fasglapp. 90°/s på ett 20-kuggs hjul = exakt fem kuggar
per sekund = samma bild. Bevisa därför farten i `PlaybackClockTest`, inte med
skärmdumpar.

## Hörnarkitekturen — vem äger vilket hörn

Fyra paneler delar viewporten, och var och en komponeras av olika kod. Ordningen är inte
smak utan aritmetik: två paneler i samma hörn med samma inset ritas ovanpå varandra.
Det hände — legenden och HUD:ns hörnblock låg båda 12 dp från övre vänstra hörnet, så
kroppsnamnen och massraden var ömsesidigt oläsbara på varje typ med fler än en kropp
(ett nav räcker, och `result_weight` saknar ankare så blocket ritas alltid).

| Hörn | Ägare |
|---|---|
| Övre vänstra | `AssemblyLegend` (bara vid fler än en kropp) — och HUD:ns hörnblock **under** den |
| Övre högra | `ViewportGizmo` |
| Nedre vänstra | skalstocken (12 dp) och kuggchipen (84 dp) |
| Nedre högra | play-knappen (84 dp) |

Siffrorna bor i `ViewportChrome.kt` (`legendHeightDp`, `legendBottomDp`, `hudCornerTopDp`)
och är testade i `ViewportChromeTest`, eftersom `MeasurementHud` inte kan se legenden och
tvärtom. Ankrade mätetiketter hålls också under legenden, via
`HudProjection.distribute(topLimit = …)`.

## Bäddtexten — var den får ligga

Bäddens bakkant (+Y), med strecken inåt. Främre kanten på en inramad platta hamnar
alltid runt tre fjärdedelar av viewport-höjden, vilket är exakt det band där
uppspelningskontrollerna bor: en linjal där blev halvt övertäckt av hastighetschipsen
så fort man tryckte på play (mätt 2026-09-20).

Och: **ramningen måste ske efter att vyn fått bäddstorleken.** `GearWorkspace` gör det i
en `LaunchedEffect(showBed, settings.bedSizeMm)`. Att kalla `autoFrame()` direkt i
menyns `onClick` körde innan `AndroidView`-uppdateringen hann sätta `bedSizeMm`, så
kameran stod kvar på kuggen och en 220 mm-platta ritades ~4× större än viewporten —
kanterna, hela poängen med att visa bädden, hamnade utanför skärmen.

## Kontrakt 1 — render on demand (punkt 20)

Renderingen ska ske **bara vid förändring**, inte i en oändlig loop.

- Allt som ändrar bilden måste anropa `requestRender()`:
  nya params, ny mesh, kamerarörelse, zoom, fönsterstorlek, tema.
- En `while(true) { draw() }`-loop utan tillståndskontroll bryter kontraktet och
  drar batteri. Så här verifierar du:

```powershell
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
& $adb logcat -c
# rör inte skärmen i 10 sekunder
& $adb logcat -d | Select-String "requestRender|onDrawFrame"
```

Kontinuerlig aktivitet utan input = en loop ritar utan förändring.

- Lägger du till ett nytt visuellt tillstånd: lägg till `requestRender()` på
  **samma ställe** som tillståndet sätts. Att sätta state utan att begära en ny
  frame ger en bild som inte uppdateras — ett fel som är lätt att tro är ett
  cache-problem i `core`.

## Kontrakt 2 — resursägande (punkt 21)

Varje GL-resurs som skapas ska frigöras på samma ställe.

- `rebuildBuffers` skapar VBO:er. Anropas den igen utan att den förra uppsättningen
  raderas (`glDeleteBuffers`) läcker varje typbyte en buffer.
- Samma sak gäller textures och framebuffers om sådana införs.
- Verifiera över tid, inte vid ett enskilt tillfälle:

```powershell
& $adb shell dumpsys meminfo com.gearforge.geargenerator | Select-String "TOTAL|Native|EGL"
# byt kugghjulstyp 10 gånger
& $adb shell dumpsys meminfo com.gearforge.geargenerator | Select-String "TOTAL|Native|EGL"
```

`Native Heap` och `EGL mtrack` ska vara stabilt över tid. Obegränsad tillväxt är
en läcka.

## Kontrakt 3 — EGL/TextureView-livscykeln (punkt 6)

- Ytan ska **släppas** när appen pausas och **återskapas** när den återupptas.
  `onSurfaceTextureDestroyed` och EGL-release hör till pausen, inte till
  aktivitetens förstörelse.
- Rendertråden ska stoppas innan ytan släpps och startas om efter att den
  återskapats. Att rita mot en förstörd yta ger `EGL_BAD_SURFACE`.
- GL-arbete sker på rendertråden. Att röra GL-resurser från UI-tråden ger
  intermittent korruption som bara syns ibland.
- Testa med upprepade paus/återuppta- och rotationscykler:

```powershell
& $adb shell am start -n com.gearforge.geargenerator/com.gearforge.app.MainActivity
& $adb shell input keyevent KEYCODE_HOME
& $adb shell am start -n com.gearforge.geargenerator/com.gearforge.app.MainActivity
& $adb shell settings put system accelerometer_rotation 0
& $adb shell settings put system user_rotation 1    # landskap
& $adb shell settings put system user_rotation 0    # porträtt
& $adb logcat -d -t 200 "*:E" | Select-String "EGL|GL_|FATAL|gearforge"
```

## Loggrader att känna igen

| Rad | Betyder |
|---|---|
| `eglCreateWindowSurface failed` | Ytan är inte redo, eller släpptes för tidigt |
| `EGL_BAD_SURFACE`, `EGL_BAD_CONTEXT` | Ritning mot en död yta |
| `GL_INVALID_OPERATION` | Buffert bundet fel, eller GL-anrop i fel ordning |
| `GL_OUT_OF_MEMORY` | Läcka, eller för stora meshar på en gång |
| `Skipped N frames` | Huvudtråden blockerad — meshen byggs på fel tråd |

## Prestanda

- Meshbygget (`GearBuilder.assembly`) ska inte köras på UI-tråden. `GearWorkspace`
  debouncar och cachar på `GearParams`-hash (ACTION_PLAN punkt 17) — en ändring som
  kringgår cachen märks som ryckighet vid slider-drag.
- `PrecisionLevel.HIGH` ger markant fler trianglar. Stresstestet bygger >1M
  trianglar och kräver `maxHeapSize = "2g"` i `core/build.gradle` — rör inte det.
- Undvik att bygga om VBO:er när bara kameran rör sig. Kameran ska bara uppdatera
  uniformer och kräva en ny frame.

## Fallback: vad du gör i stället för ett test

GL-koden går inte att enhetstesta i `core`. Gör i stället:

1. **Bryt ut matematiken** till en ren funktion i ett testbart objekt (som
   `GizmoMathTest` gör med gizmo-matematiken) och testa den i `core` eller
   `android/src/test`.
2. **Verifiera i emulatorn** med `emulator-ui-verification`-skillen: skärmdump +
   logcat över minst 10 typbyten.
3. **Beskriv i ändringen** vad du verifierade och hur — inte bara att det fungerar.
