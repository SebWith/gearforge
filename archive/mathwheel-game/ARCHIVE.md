# Arkiv — MathWheelGame (mattespelet)

Arkiveringsdatum: 2026-08-19
Arkiverat av: utvecklingsagent (GitHub Copilot)

## Varför arkiverat
Det gamla spelet "MathWheelGame" (lyckohjul med matteuppgifter) ersätts av det nya
spelet "Sweep Runner" (arbetsnamn). Mattespelets specifika logik behövs inte
längre och flyttas därför ut ur den aktiva källkoden.

## Säkerhetskopia (snapshot)
Fullständig kopia av ALL källkod togs innan något togs bort:
- `archive/mathwheel-game/core-src/com/mathwheel/game/` (hela core-koden)
- `archive/mathwheel-game/android-src/com/mathwheel/game/` (hela android-koden)

## Vad som arkiverats (togs bort ur aktiv kod)
Från `core/src/main/java/com/mathwheel/game/`:
| Fil | Roll |
|---|---|
| `WheelModel.kt` | Logik för lyckohjulet |
| `MathGenerator.kt` | Generering av matteuppgifter |
| `Difficulty.kt` | Svårighetsnivåer för matte |
| `GameScreen.kt` | Spelskärm (hjulet) |
| `MenuScreen.kt` | Huvudmeny |
| `MathWheelGame.kt` | Spelets huvudklass/ingång |
| `Palette.kt` | Neonglöd-palett |

## Vad som BEHÅLLS i aktiv kod (återanvänds i nya spelet)
- `Fx.kt` — partiklar/skakeffekter
- `GameState.kt` — sparning av mynt/highscore/språk/ljud/haptik
- `I18n.kt` — språkstöd
- `PlatformServices.kt` — haptik- och leaderboard-abstraktion
- `Ui.kt` — textritning
- `AndroidLauncher.kt` + `AndroidPlatformServices.kt` — Android-integrering

## Filändringar (logg)
| Datum | Åtgärd | Sökväg |
|---|---|---|
| 2026-08-19 | Kopierad (backup) | `core/src/.../game/*` → `archive/mathwheel-game/core-src/...` |
| 2026-08-19 | Kopierad (backup) | `android/src/.../game/*` → `archive/mathwheel-game/android-src/...` |
| 2026-08-19 | Borttagen ur aktiv kod | `WheelModel.kt`, `MathGenerator.kt`, `Difficulty.kt`, `GameScreen.kt`, `MenuScreen.kt`, `MathWheelGame.kt`, `Palette.kt` |

## Obs
- Aktiv kod kompilerar inte förrän en ny spelingång byggts (nästa fas). Det är
  förväntat och beskrivet i `README.md`.
- `core/bin/` och `build/` innehåller gamla byggartefakter och kan rensas vid behov.
- Projektet är inte versionshanterat (ingen `.git`); den här loggen är
  historikdokumentationen.
