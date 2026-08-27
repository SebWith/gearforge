# ASSET BRIEF — "Gear Forge" (Android)

> Klart att klistra in i Nano Banana / bildgenererare.
> Generera en bild i taget. Behåll samma stil och vinkel genom hela uppsättningen.

---

You are generating the complete visual asset set for **Gear Forge**, an Android
app (portrait, Material Design 3) where hobbyists and makers design custom gears
for 3D printing. The user picks a gear type (spur, helical, bevel, rack, gear
pair, planetary), tweaks parameters (teeth, module, pressure angle, bore), sees a
real-time 3D preview, and exports STL/3MF/DXF/SVG files. The app is monetized
with rewarded ads and a one-time "Pro" upgrade.

## GLOBAL ART STYLE (apply to every image)

- **Style:** clean, modern "engineering / product design" look. Slightly
  stylized 3D renders — not photorealistic, not flat vector icons.
- **Lighting:** soft studio lighting, one key light from the upper-left, subtle
  fill light, and a soft contact shadow on the ground. No harsh shadows.
- **Gear material:** light steel-gray metal, semi-gloss machined look — matches
  the in-app 3D preview (roughly #B8C2CC base color, high metalness, low-medium
  roughness, subtle brushed texture).
- **Camera angle:** isometric 3/4 view (about 35° pitched down, 45° yaw). Keep
  the SAME angle for every gear render so thumbnails match the real 3D model.
- **Palette:**
  - Primary steel blue `#00658C`
  - Light blue accent `#82D1FF`
  - Light background `#F7F9FB`
  - Dark background `#0E1418`
  - Neutral warm gray for thumbnail backdrops
- **No baked-in text/words** anywhere unless explicitly requested.
- **Theme-safe:** use mid-tones, never pure white or pure black, so assets work
  on both light and dark UI themes.
- **Format:** PNG. Use **transparent background** where noted; otherwise use the
  exact solid background color listed per asset. Minimum 1024 px (512 px for
  small icons is fine). Powers of 2 (512, 1024, 2048).

## ASSET LIST

### A. Branding

**A1 — App icon**
- 1024×1024, full-bleed square; keep the icon inside the center ~66% safe zone.
- A single bold stylized gear (8–12 teeth) in a steel-blue gradient, with a
  subtle forge motif — e.g. a small spark or an anvil/wrench negative space in
  the center hole.
- Slight 3D bevel, clean silhouette, modern. Background: vertical gradient from
  `#00658C` to `#004B68`, with a thin `#82D1FF` rim light on the top edge.
- **No text.**

**A2 — Wordmark / logo (horizontal)**
- Transparent PNG, ~1600×600.
- The text "Gear Forge" in a bold, rounded geometric sans-serif, color `#00658C`.
- Replace the "O" in "Forge" with a small gear, or place a small gear mark to
  the left of the text.
- Minimal, flat, no drop shadow. Provide one light version (blue text on
  transparent) and one white version (for dark backgrounds).

### B. Start-screen card thumbnails

- Each: **512×512, square, object centered, transparent background**, soft
  contact shadow, isometric 3/4 view, consistent scale and lighting.

- **B1 — Spur gear** (raka kuggar): a single classic cylindrical gear with
  straight teeth, center bore visible, face slightly toward camera.
- **B2 — Helical gear** (snedskurna): a single gear with angled/helical teeth
  (about 15° twist), same framing as B1.
- **B3 — Bevel gear** (koniska): a cone-shaped gear with teeth on the angled
  face, apex pointing up-left.
- **B4 — Rack** (kuggstång): a straight horizontal toothed bar (linear gear),
  long and low, teeth facing up.
- **B5 — Gear pair** (kugghjulspar): two meshing spur gears of different sizes
  side by side, teeth visibly interlocking.
- **B6 — Planetary** (planetväxel): a ring gear with internal teeth, a central
  sun gear, and three planet gears between them.

### C. Screens & backgrounds

**C1 — Splash screen illustration**
- 1080×1920 portrait, solid dark background `#0E1418`.
- Hero composition: one large glowing steel-blue gear (rim-lit with `#82D1FF`),
  faint blueprint grid lines and smaller out-of-focus gears in the background,
  soft light rays.
- Centered horizontal wordmark "Gear Forge" below the gear.
- Also produce a **text-free** version.

**C2 — Start-screen background texture**
- Seamless/tileable 1024×1024, very subtle engineering blueprint grid (thin
  `#C2E8FF` lines on `#F7F9FB`), plus one faint large gear-outline watermark in
  the corner.
- Very low contrast so UI text stays perfectly readable on top.

### D. Store & monetization

**D1 — Play Store feature graphic**
- 1024×500 landscape.
- "Gear Forge" wordmark on the left; 3–4 gear renders (spur, helical, bevel,
  planetary) arranged on the right; steel-blue gradient background with a subtle
  blueprint grid.
- Leave a clean empty area for the store tagline (no baked tagline text).

**D2 — Pro badge (optional)**
- 256×256, transparent. A rounded badge: a gold/steel star over a small gear,
  no text. (Only needed if you want a custom badge instead of the default vector
  star icon.)

### E. SKIP (do not generate)

- Preset thumbnails and the actual gear meshes: those are generated live by the
  app's own 3D engine, so no image is needed.
- Toolbar/UI icons: already handled as vector Material icons in code.

## OUTPUT SUMMARY

PNG. Transparent background for A2, B1–B6, D2. Solid backgrounds as specified
for A1, C1, C2, D1. Same isometric 3/4 camera and same soft studio lighting on
every gear render. No baked-in text except the "Gear Forge" wordmark in A2, C1,
and D1.
