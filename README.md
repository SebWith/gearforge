# Sweep Runner (arbetsnamn)

Android-spel byggt med **libGDX + Kotlin** (moduler: `core` + `android`).
Tidigare innehöll projektet "MathWheelGame" (ett mattespel) — det är nu arkiverat.

## Status
- [x] Plan och MVP-specifikation — se `plan.md`
- [x] Arkiv av gammalt mattespel (2026-08-19) — se `archive/`
- [x] Ny spelingång + meny + spelskärm (core loop) — `core/src/main/java/com/sweeprunner/game/`
- [ ] Övriga skärmar (nivåval, butik, inställningar, resultat) och integrationer (annonser, leaderboard)

## Mappstruktur
- `core/` — plattformsoberoende spellogik (Kotlin + libGDX)
- `android/` — Android-appen (launcher, haptik, annonser, leaderboard)
- `archive/mathwheel-game/` — arkiverat mattespel (säkerhetskopia + ändringslogg)
- `plan.md` — komplett MVP-specifikation
- `plan-diskussion-raw.md` — arkiverad rådiskussion från planeringen

## Arkiv
Det gamla mattespelet (MathWheelGame) arkiverades **2026-08-19** eftersom det
ersätts av "Sweep Runner". Säkerhetskopia och ändringslogg finns i
`archive/mathwheel-game/ARCHIVE.md`.

**Observera:** den aktiva koden kompilerar inte förrän den nya spelingången är
byggd — det är ett medvetet mellansteg i ombyggnaden.
