# UI/UX-design & flödesplan — GearForge

> Grundad i `LandingScreen.kt`, `GearWizard.kt`, `GearWorkspace.kt`, `Controls.kt`,
> `GearGLView.kt`, `AppTheme.kt` och den bifogade hero-bakgrunden.

## 1. Designprinciper
1. Modellen i centrum — inga fasta paneler täcker 3D-vyn.
2. Bakgrunden genomgående (inkl. bakom 3D-vyn).
3. Avslöja, inte överväldiga — reglage döljs i bottom-sheet.
4. Pedagogik före densitet — reglage kopplas visuellt till kugghjulet.
5. Tema-säkert — samma assets i mörkt/ljust läge.

## 2. Asset-system
Befintligt: `assets-prompt.md` (A1 ikon, A2 wordmark, B1–B6 miniatyrer, C1 splash,
C2 rutnät, D1 feature-grafik, D2 Pro-märke) + 3 bilder i `Assets/`.

Nya assets (prioriterat):
| # | Asset | Syfte | Prio |
|---|---|---|---|
| N1 | `bg_hero` (den bifogade) | bakgrund överallt | 🔴 |
| N2 | `bg_hero_light` | ljus variant | 🟡 |
| N3 | `bg_hero_dim` | nedtonad för modal | 🟡 |
| N4 | Annoterings-hotspots (data) | bokstavspositioner per typ | 🔴 |
| N5/N6 | Tom-/feltillstånd | empty/error states | 🟢 |
| N7 | Panel-chevron (vektor) | öppna/stäng panel | 🟢 |
| N8 | Wordmark (A2) | branding | 🟢 |

Resursregel: gemener utan mellanslag i `res/drawable-nodpi/` (mörk variant i `-night`).

## 3. Sida-för-sida
- **Startsida:** hero-bakgrund + wordmark + CTA "Skapa nytt kugghjul" (primary) +
  "Öppna sparat" (secondary) + "Inställningar"/"Om".
- **Typval:** 2-kolumns rutnät med B1–B6-miniatyrer.
- **NY Annoterad sida** (hög prio): stor bild + bokstäver/pilar → reglage.
- **Editor:** modell i fokus, bottom-sheet-panel, svepbar toppbar.

## 4. Flöde
Startsida → Typval → Annoterad sida → 3D-editor (bottom-sheet för reglage).

## 5. Annoterad sida (högsta prioritet)
Basbild B1–B6 + hotspot-map (`data class Hotspot(key,x,y)`) → rita bokstäver/pilar
via Canvas → reglage-rader med matchande bokstavsbrickor + hjälptext → CTA
"Fortsätt till 3D".

## 6. 3D-vyn
Full yta, bakgrund som GL-texturerad quad (eller transparent EGL), autoFrame,
flytande "centrera"-knapp.

## 7. Svepbar toppbar
`LazyRow`/`HorizontalPager` med segment: Vy · Parametrar · Material · Exportera · Mer.

## 8. Bottom-sheet-panel
`ModalBottomSheet` med sticky grupprubriker, hjälptexter + giltigt intervall,
valideringsbanner, resultat-sektion. Kollapsbara grupper.

## 9. Implementeringsplan
| Prio | Ändring | Effort |
|---|---|---|
| 1 | Bakgrundsbild + assets | S — KLAR |
| 2 | Annoterad sida | M |
| 3 | Bottom-sheet | M |
| 4 | Svepbar toppbar | M |
| 5 | GL-bakgrund | M |
| 6 | Startsida | S — KLAR |
| 7 | Typkort miniatyrer | S |
| 8 | Empty/error-states | S |

## 10. Rekommendation
Modellen centrerad + diskret svepbar toppbar + bottom-sheet-panel + genomgående
bakgrund + pedagogisk annoterad sida före 3D.
