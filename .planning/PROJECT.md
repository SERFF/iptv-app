# IPTV Player Android TV — Netflix-feel milestone

## What This Is

Native Android TV IPTV player (Kotlin + Compose for TV + Media3/ExoPlayer) voor M3U en Xtream Codes bronnen. Originally getuned op Formuler Z10 Pro Max, draait op elke Android TV 10+ device. Open-source, Apache 2.0. Deze milestone tilt het home-screen, detail-screens en player tot het niveau waar gebruikers een Netflix-app verwachten — geen kale lijst meer, maar live-voelende discovery.

## Core Value

**Premium tv-kijkervaring op een D-pad remote.** Als de home-screen en detail-screens zich net zo levendig en persoonlijk aanvoelen als Netflix, wint deze player het van de stock Formuler/Xtream-UIs.

## Requirements

### Validated

<!-- Initial release reeds shipped. -->

- ✓ M3U / Xtream-source ingest — initial release
- ✓ Lokale Room-catalogus met ETag/Last-Modified refresh — initial release
- ✓ Live TV met EPG, audio/sub-tracks, channel up/down — initial release
- ✓ VOD movie + series detail-screens — initial release
- ✓ Multi-profile (favorites, watch progress, recent search per profiel) — initial release
- ✓ TMDB-verrijking (poster, plot, popular rail, cast, similar, trailer) — initial release
- ✓ Hero-carousel met Ken Burns + Top-10 rails + next-episode countdown — "Netflix vibe" v1 (dff2926, 9682d64)

### Active

<!-- v1 Netflix-feel scope. -->

- [ ] **HERO-01** Hero-banner speelt na 2.5s een muted YouTube-trailer (in plaats van Ken Burns backdrop)
- [ ] **HERO-02** Hero-banner heeft "More Info" knop naast Play die naar detail navigeert zonder af te spelen
- [ ] **RAIL-01** Rail-items tonen na 700ms focus-idle een hover-preview popover (artwork, plot-snippet, year, rating, Play/Info)
- [ ] **RAIL-02** Hero-backdrop swap mee met currently-focused rail-item
- [ ] **PROF-01** Cold-start toont profielkiezer-scherm als >1 profiel actief is
- [ ] **PROF-02** Profielen hebben emoji/avatar veld naast huidige kleur
- [ ] **DET-01** Detail-screen poster animeert naar fullbleed bij entry (parallax/Crossfade)
- [ ] **DET-02** Detail-body scrollable met sectie-kopjes (Verhaal / Cast / Vergelijkbaar)
- [ ] **DET-03** Typografie herzien (display-medium title, body-large plot, 1.4 line-height)
- [ ] **PLAY-01** Player toont "Skip Intro" knop in eerste 90s van een SERIES-episode
- [ ] **SERIE-01** Series-detail toont per-episode thumbnail (TMDB still_path of fallback)
- [ ] **SERIE-02** Next-episode countdown card toont episode-still + plot-snippet
- [ ] **DISC-01** "Trending deze week" rail (TMDB trending/movies+tv/week, gematcht met catalogus)
- [ ] **DISC-02** "Nieuw in je catalogus" rail (lokaal, op `addedAt`)
- [ ] **DISC-03** Genre-rails configurable per profiel ("Actie", "Komedie", etc., uit TMDB genres)
- [ ] **PERS-01** "Omdat je [Title] hebt gekeken" rail (afgeleid van recent-finished movies)
- [ ] **PERS-02** Watchlist ("Bewaar voor later") separaat van Favorites, met rail op home

### Out of Scope

- DRM / Widevine — providers leveren geen DRM-streams via M3U/Xtream
- Catch-up / PVR — buiten initial-release scope, niet gevraagd
- Chromecast / casting — Formuler is zelf eindpunt
- ML-based skip-intro detection — heuristisch (eerste 90s) volstaat; ML te zwaar voor STB
- Wisselen tussen Compose for TV alpha versies — wachten op stabiele BOM, niet bumpen tijdens deze milestone

## Context

Bestaande codebase (`app/src/main/java/nl/vanvrouwerff/iptv/`) is `~6500 regels` Kotlin verdeeld over `ui/channels/` (2113 r), `ui/detail/` (878), `ui/seriesdetail/` (830) en `player/` (1934). Service-locator patroon via `IptvApp`, geen DI framework. Compose for TV op `1.0.0-alpha10` — bekende focus-search crash workaround in MainActivity. Room schema versie 6 (te bekijken). TMDB-integratie via Retrofit + kotlinx.serialization met cache-TTLs. Cleartext HTTP enabled voor IPTV bronnen.

Uncommitted WIP op `main`: perf-fix voor "Meer zoals dit"-rail (process-lifetime movie-index, two-pass UI update). Wordt onderdeel van milestone OR aparte fix-PR voor merge.

User test-hardware: Formuler Z10 Pro Max + Chromecast HD. Emulator faalt op D-pad/HDMI-audio gedrag — alles op echte hardware verifiëren.

## Constraints

- **Tech stack**: Kotlin + Compose for TV alpha10 + Media3 + Room — geen DI frameworks (service-locator opzettelijk)
- **Compatibility**: Android TV API 29+ (minSdk 29), JDK 17/21 (AGP 8.5.2 vereist)
- **Hardware**: Formuler Z10 Pro Max + Chromecast HD — geen ML, beperkte CPU/GPU
- **Performance**: Catalogus tot 20k+ channels — alle scans moeten geïndexeerd of cached zijn
- **TMDB**: optioneel — alle features moeten ook werken zonder token (graceful degrade)
- **Cleartext HTTP**: blijft enabled (IPTV bronnen vereisen het), TMDB/YouTube blijft HTTPS
- **Geen DI**: service-locator via `IptvApp` — geen Hilt/Koin introductie

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Volledige milestone via GSD ipv losse PRs | Gebruiker vroeg expliciet om gestructureerd traject met phases | — Pending |
| Room-migraties toegestaan | Nieuwe features (genres, addedAt, watchlist, profile-avatar, episode-art-cache) vereisen schema-uitbreiding | — Pending |
| Geen DI introduceren | Bestaande service-locator werkt, zou onnodig risico zijn | — Pending |
| Heuristische skip-intro (eerste 90s) ipv ML | STB-hardware te beperkt voor ML, en pure-tijd-heuristiek dekt 90% van series | — Pending |
| YouTube extractor research nodig | NewPipeExtractor zou kunnen werken voor inline trailers, maar lib is groot — onderzocht in Phase 1 | — Pending |
| Tests blijven manueel op hardware | Emulator dekt D-pad/HDMI-audio niet betrouwbaar | ✓ Good (bestaand patroon) |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-14 after initialization*
