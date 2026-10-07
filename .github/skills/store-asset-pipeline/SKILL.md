---
name: store-asset-pipeline
description: "Bygga GearForge:s Play-tillgångar deterministiskt (7 EN + 7 SV skärmdumpar, 512 px ikon, feature graphic). Use when: generera eller regenerera butikstillgångar; en skärmdump ser skuren ut; Play avvisar en bild; uppdatera manifest.csv; lägga till eller ändra en skärmdumpsrubrik."
---

# Butikstillgångar — pipelinen

Alla butiksbilder genereras av två skript. En PNG redigeras aldrig för hand — den
byggs om.

```powershell
py tools/store-screenshots/build_slides.py     # 14 skärmdumpar + manifest.csv
py tools/store-assets/build_store_assets.py    # 512 px ikon + feature graphic
```

Skripten härleder repo-roten från `__file__`, så de kan köras från valfri katalog.
Använd `py`, aldrig `python`.

## Hur `build_slides.py` fungerar

1. Läser råa emulatorcaptures från `tools/store-screenshots/_capture` (1080 × 2340).
2. `load_capture` tar bort systemlisterna: `BAR_TOP = 132`, `BAR_BOTTOM = 66`.
3. Lägger copyn ovanpå appens egen hero-bakgrund.
4. Renderar HTML med **headless Edge** i exakt 1080 × 1920.
5. `normalise()` tvingar 24-bitars RGB.

Steg 5 är inte kosmetika: Play avvisar alpha-kanaler, och headless-renderingen
producerar dem annars.

## Fällorna som redan kostat tid

### Crop-sömmen mitt i en textrad

`Slide.offset` flyttar beskärningsfönstret nedåt. Landar gränsen mitt i en textrad
ser bilden **skuren** ut — det hände `sv-06`, vars källa är `sv-07-results.png`.

```powershell
py tools/store-screenshots/pick_offset.py sv-07-results.png
```

Verktyget hittar rena sömmar (rader utan text) och skriver ut kandidater. Välj en
och sätt `Slide.offset` till den. Gissa aldrig ett offset-värde.

### `--only` skriver över manifestet

```powershell
py tools/store-screenshots/build_slides.py --only en   # manifest.csv får BARA en-raden
```

Kör alltid **hela** körningen innan du committar `manifest.csv`.

### Alpha-kanalen i feature graphic

Originalet var `Format32bppArgb` med 1523 icke-opaka pixlar → Play avvisar. Skriptet
plattar till `Format24bppRgb`. Tar du bort den raden kommer felet tillbaka.

### Ikonen skalas inte upp

Play kräver 512 × 512, men största launcher-mipmapen är bara 192 × 192. Ikonen
renderas därför **om** från de adaptiva källorna (vit `#FFFFFF`-bakgrund +
`mipmap-xxxhdpi/ic_launcher_foreground.png`). Att uppskala 192 px ger en suddig ikon.

## Validera resultatet

```powershell
py -c "from PIL import Image; import glob,os
for p in sorted(glob.glob('store-assets/final/*/*.png'))+['store-assets/app-icon-512.png','store-assets/feature-graphic.png']:
    im=Image.open(p); print(f'{p:58} {im.size[0]}x{im.size[1]} {im.mode:8} {os.path.getsize(p)/1048576:.2f} MB')"
```

| Tillgång | Krav |
|---|---|
| `app-icon-512.png` | 512 × 512, ≤ 1 MB |
| `feature-graphic.png` | 1024 × 500, **`mode` utan `A`**, ≤ 1 MB |
| `final/en/*.png`, `final/sv/*.png` | 1080 × 1920, 24-bitars RGB, max 8 per språk |

Skärmdumpar får vara större än 1 MB — Plays 1 MB-gräns gäller ikon och feature
graphic. Behåll ändå 24-bitars RGB.

## Captures — råmaterialet

```powershell
. .\tools\store-screenshots\capture.ps1     # laddar Tap/Swipe/Shot/Back/SetAppLocale
SetAppLocale 'sv'
Tap 540 1804
Shot "sv-07-results"
```

Nya captures hamnar i `tools/store-screenshots/_capture`. De är gitignorade — bara
de färdiga bilderna i `store-assets/final/` ska med.

## Rubriker måste stämma med appen

Varje rubrik i en skärmdump är ett påstående om appen. Verifiera mot källkoden
innan du skriver den. Listan över de 14 rubrikerna finns i `STORE_READINESS.md`
avsnitt 3 — ändrar du en, uppdatera både tabellen och `build_slides.py`.

## Kontrollpunkter

- [ ] Hela `build_slides.py` körd (inte `--only`).
- [ ] `manifest.csv` matchar antalet filer i `final/en` och `final/sv`.
- [ ] Varje bild validerad: storlek, dimensioner, `mode` utan `A`.
- [ ] Varje bild visuellt granskad för crop-söm.
- [ ] Rätt språk i rätt mapp.
- [ ] Rubrikerna verifierade mot koden.
