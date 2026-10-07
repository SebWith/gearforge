---
description: "Regenerera och validera Play-tillgångar (skärmdumpar, ikon, feature graphic)"
agent: agent
---

# Butikstillgångar

Allt butiksmaterial genereras av skript. Redigera aldrig en PNG för hand — ändra
skriptet eller källcapturen.

## Regenerera

```powershell
py tools/store-screenshots/build_slides.py     # 14 skärmdumpar + manifest.csv
py tools/store-assets/build_store_assets.py    # 512 px ikon + feature graphic
```

Kör **hela** körningen. `--only en` skriver om `manifest.csv` med bara de raderna.

## Validera

Play avvisar tyst felaktiga filer, så kontrollera själv:

```powershell
py -c "from PIL import Image; import glob,os
for p in sorted(glob.glob('store-assets/final/*/*.png')) + ['store-assets/app-icon-512.png','store-assets/feature-graphic.png']:
    im=Image.open(p); mb=os.path.getsize(p)/1048576
    print(f'{p:60} {im.size[0]}x{im.size[1]} {im.mode:8} {mb:.2f} MB')"
```

| Tillgång | Krav | Faller om |
|---|---|---|
| `store-assets/app-icon-512.png` | 512 × 512, ≤ 1 MB | Fel storlek — största mipmapen är bara 192 px, så ikonen renderas om (skalas inte upp) |
| `store-assets/feature-graphic.png` | 1024 × 500, **ingen alpha**, ≤ 1 MB | `mode` innehåller `A` (RGBA) — Play avvisar alpha |
| `store-assets/final/en/*.png` | 1080 × 1920, 24-bitars RGB, max 8 | Fel storlek eller alpha-kanal |
| `store-assets/final/sv/*.png` | samma, max 8 | Samma |

Alpha-kanalen är den fälla som redan bitit en gång: originalet var
`Format32bppArgb` och fick plattas till `Format24bppRgb`.

## Granska bilderna visuellt

```powershell
Get-ChildItem store-assets\final\en\*.png | Select-Object -ExpandProperty FullName
```

Öppna och kontrollera:

1. **Crop-sömmen** — landar beskärningen mitt i en textrad ser bilden skuren ut
   (hände `sv-06`). Använd `py tools/store-screenshots/pick_offset.py <fil>` för att
   hitta en ren söm innan du ändrar `Slide.offset`.
2. **Rätt språk i rätt mapp** — den svenska vyn ska visa svensk UI.
3. **Headline stämmer med appen** — varje påstående ska vara verifierat mot koden.

## Uppdatera manifest

`store-assets/final/manifest.csv` indexerar skärmdumparna. Verifiera att antalet
rader matchar antalet filer och att ordningen stämmer med listan i
`STORE_READINESS.md` avsnitt 3.

## Rapportera

Ange antal genererade filer, deras dimensioner och bit-djup, samt om något avvek.
Uppdatera `STORE_READINESS.md` om statusändring skett.
