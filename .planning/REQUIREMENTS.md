# Requirements — Netflix-feel Milestone

## v1 Requirements

### Hero (HERO)

- [ ] **HERO-01** — Hero-banner speelt na 2.5s een muted YouTube-trailer (gestream via TMDB `trailer.key`) in plaats van Ken Burns backdrop. Audio uit, valt terug op backdrop bij ladingsfout of geen trailer.
- [ ] **HERO-02** — Hero-banner heeft "More Info" knop als focus-target naast Play. Klik navigeert naar `MovieDetail`/`SeriesDetail` zonder de player te starten.

### Rails & Discovery — UI (RAIL)

- [ ] **RAIL-01** — Bij D-pad focus op een rail-item, na 700ms idle: expandeert kaart tot popover met grote artwork, plot-snippet (2 regels), year, rating, Play+Info knoppen.
- [ ] **RAIL-02** — Hero-backdrop swap mee met currently-focused rail-item (gedeelde state via VM). Smooth crossfade.

### Profiles (PROF)

- [ ] **PROF-01** — Bij cold-start (>1 profiel actief, geen recente sessie binnen 8u): toon `ProfilePickerScreen` voordat Channels-scherm geladen wordt.
- [ ] **PROF-02** — Profielen hebben optioneel emoji/avatar-veld; profielkiezer toont avatar-tegels in een grid met focus-scale.

### Detail-page (DET)

- [ ] **DET-01** — Bij detail-screen-entry animeert poster/backdrop naar fullbleed met parallax/Crossfade (geen abrupte cut).
- [ ] **DET-02** — Detail-body is verticaal scrollable met sticky header (titel + Play); secties duidelijk gemarkeerd (Verhaal / Cast / Vergelijkbaar).
- [ ] **DET-03** — Typografie herzien: titel `displayMedium`, plot `bodyLarge` met 1.4 line-height, scrim `Brush.horizontalGradient(0f→Black 0.92, 0.65f→Transparent)`.

### Player (PLAY)

- [ ] **PLAY-01** — Player toont "Skip Intro" knop in eerste 90s van een SERIES-episode; D-pad center activeert skip naar 90s mark.

### Series (SERIE)

- [ ] **SERIE-01** — Series-detail toont per-episode thumbnail (TMDB `tv/{id}/season/{n}/episode/{e}.still_path` of Xtream `movie_image`, fallback series-cover).
- [ ] **SERIE-02** — Next-episode countdown card (laatste 20s) toont episode-still + plot-snippet uit dezelfde cache.

### Discovery — Data-driven (DISC)

- [ ] **DISC-01** — Home-screen rail "Trending deze week" via TMDB `trending/movies/week` + `trending/tv/week`, gematcht tegen lokale catalogus (zoals `TmdbPopularRepository` patroon).
- [ ] **DISC-02** — Home-screen rail "Nieuw in je catalogus" op basis van Channel.addedAt (nieuw veld, geseed bij refresh).
- [ ] **DISC-03** — Genre-rails op home ("Actie", "Komedie", etc.) uit TMDB genres; configureerbaar per profiel via Settings.

### Personalization (PERS)

- [ ] **PERS-01** — Home-screen rail "Omdat je [Title] hebt gekeken: vergelijkbaar" — afgeleid van recent-finished movies (positionMs/durationMs > 0.9). Max 2 zichtbaar tegelijk; cache 24u in `recommendation_cache`.
- [ ] **PERS-02** — Watchlist ("Bewaar voor later") separaat van Favorites — eigen Room-tabel, eigen knop op detail-screens, eigen rail op home.

## v2 (Deferred)

- Skip-Outro detectie aan einde van episode
- Profile-avatars als custom upload (initial: emoji-pick only)
- "Top 10 in jouw land" — vereist geo-region detectie
- Cross-device sync van watchlist/progress

## Out of Scope

- DRM / Widevine — providers leveren geen DRM-streams
- Catch-up / PVR — niet gevraagd, complex
- Chromecast / casting — Formuler is zelf eindpunt
- ML-based skip-intro — STB-hardware te beperkt
- Hilt/Koin DI framework — bestaande service-locator volstaat
- Compose BOM upgrade naast Netflix-feel werk — separaat traject

## Traceability

<!-- Gevuld door roadmap. -->

| REQ-ID | Phase | Status |
|--------|-------|--------|
| HERO-01 | Phase 1 | Active |
| HERO-02 | Phase 1 | Active |
| RAIL-01 | Phase 2 | Active |
| RAIL-02 | Phase 2 | Active |
| PROF-01 | Phase 3 | Active |
| PROF-02 | Phase 3 | Active |
| DET-01 | Phase 4 | Active |
| DET-02 | Phase 4 | Active |
| DET-03 | Phase 4 | Active |
| PLAY-01 | Phase 5 | Active |
| SERIE-01 | Phase 5 | Active |
| SERIE-02 | Phase 5 | Active |
| DISC-01 | Phase 6 | Active |
| DISC-02 | Phase 6 | Active |
| DISC-03 | Phase 6 | Active |
| PERS-01 | Phase 7 | Active |
| PERS-02 | Phase 7 | Active |

---
*Last updated: 2026-05-14 after initialization*
