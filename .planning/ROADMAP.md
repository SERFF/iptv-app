# Roadmap

## 🚧 **v1.0 — Netflix-feel**

## Summary

**7 phases** | **17 requirements** | All v1 requirements covered ✓

| # | Phase | Goal | Requirements | UI hint |
|---|-------|------|--------------|---------|
| 1 | Hero auto-trailer | Hero-banner toont muted YouTube-trailer + "More Info" knop | HERO-01, HERO-02 | yes |
| 2 | Rail hover-preview | Focus-popover op rail-items + hero-backdrop swap | RAIL-01, RAIL-02 | yes |
| 3 | Profielkiezer | Cold-start profile picker + avatar veld | PROF-01, PROF-02 | yes |
| 4 | Detail-page expand | Fullbleed detail-entry, scrollable body, betere typografie | DET-01, DET-02, DET-03 | yes |
| 5 | Player & series polish | Skip Intro + episode-artwork + uitgebreide next-episode card | PLAY-01, SERIE-01, SERIE-02 | yes |
| 6 | Discovery rails | Trending + Nieuw + Genre rails | DISC-01, DISC-02, DISC-03 | yes |
| 7 | Personalisatie | "Omdat je X keek" + Watchlist | PERS-01, PERS-02 | yes |

---

## Phase 1: Hero auto-trailer + "More Info" knop

**Goal:** Hero-carousel toont na 2.5s een muted YouTube-trailer (via TMDB trailer key) in plaats van Ken Burns backdrop, met "More Info" focus-target naast Play.

**Requirements:** HERO-01, HERO-02

**Success Criteria:**
1. Bij hero-rotation: na 2.5s start trailer muted; bij focus-loss/wissel stopt trailer.
2. Bij ladingsfout/geen trailer: fallback naar bestaande Ken Burns backdrop, geen crash.
3. "More Info" knop is bereikbaar via D-pad rechts vanaf Play; activeert detail-route.
4. Trailer-keys voor zichtbare hero-items (max 5) worden vooraf geladen — geen network-stall op rotation.
5. App boot zonder TMDB-token werkt identiek (huidige Ken Burns fallback blijft).

**Files:** `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt` (HeroBanner ~1285-1349), `ChannelsViewModel.kt`, mogelijk nieuwe `data/tmdb/TmdbHeroPreloader.kt` of `data/youtube/YouTubeStreamExtractor.kt`.

**Dependencies:** Geen.

---

## Phase 2: Rail hover-preview

**Goal:** Rail-items in alle home-rails expandren tot popover op focus-idle, met hero-backdrop swap.

**Requirements:** RAIL-01, RAIL-02

**Success Criteria:**
1. D-pad focus + 700ms idle → popover zichtbaar (scale 1.18, glow border, content-fade).
2. Focus-loss binnen 700ms toont GEEN popover (geen flicker bij snelle scroll).
3. Hero-backdrop crossfade naar focused item's backdrop URL.
4. Popover toont year + rating + 2-regelige plot (uit prefetched MovieDetailsBundle).
5. Geen jank bij scrollen door rails — popover renderpath buiten LazyRow main flow.

**Files:** `ChannelsScreen.kt`, `ChannelsViewModel.kt`. Hergebruik `TmdbMovieDetailsRepository` cache.

**Dependencies:** Geen — kan parallel met Phase 1.

---

## Phase 3: Profielkiezer op cold start + avatars

**Goal:** Cold-start splash routert naar profielkiezer wanneer >1 profiel; profielen krijgen emoji/avatar.

**Requirements:** PROF-01, PROF-02

**Success Criteria:**
1. Bij >1 profiel + geen recente sessie (<8u): Splash → ProfilePickerScreen → Channels.
2. Bij 1 profiel: directe route naar Channels (geen extra scherm).
3. Profielen kunnen emoji ingesteld krijgen via bestaand Profiles-scherm.
4. Wisselen via "Wisselen van profiel" shortcut in Settings → Profiles.
5. Room-migratie 6→7 voegt `avatarEmoji TEXT` toe zonder data-verlies bij upgrade.

**Files:** `data/db/Entities.kt`, `data/db/IptvDatabase.kt` (migration), `data/db/ProfileDao.kt`, nieuwe `ui/profilepicker/ProfilePickerScreen.kt`, `MainActivity.kt` (routing), `ui/profiles/ProfilesScreen.kt` (emoji-picker).

**Dependencies:** Geen.

---

## Phase 4: Detail-page expand + betere typografie

**Goal:** Detail-screens openen met fullbleed-animatie en scrollable body met duidelijke secties.

**Requirements:** DET-01, DET-02, DET-03

**Success Criteria:**
1. Bij detail-open: poster/backdrop animeert van rail-positie naar fullbleed (Crossfade/LookaheadLayout, ~300ms).
2. Body scrollt verticaal met sticky header (titel + Play); D-pad up/down werkt zonder focus-trap.
3. Sectiekopjes "Verhaal", "Cast", "Vergelijkbaar" duidelijk leesbaar tegen backdrop-scrim.
4. Tekst-leesbaarheid getest tegen lichte EN donkere backdrop (scrim 0→0.65→transparent).
5. Geen regressie in MovieDetailScreen + SeriesDetailScreen (beide schermen krijgen update).

**Files:** `ui/detail/MovieDetailScreen.kt`, `ui/seriesdetail/SeriesDetailScreen.kt`, `ui/theme/Type.kt`.

**Dependencies:** Geen.

---

## Phase 5: Skip Intro + Episode artwork + uitgebreide next-episode card

**Goal:** Player krijgt Skip Intro knop, series-grid krijgt episode-thumbnails, next-episode card wordt visueel rijker.

**Requirements:** PLAY-01, SERIE-01, SERIE-02

**Success Criteria:**
1. Bij SERIES-episode + positie 0-90s: "Skip Intro" knop zichtbaar; center-click jumpt naar 90s.
2. Skip Intro verschijnt NIET voor MOVIE/TV content.
3. Series-detail grid toont per-episode thumbnail (TMDB still of fallback series-cover); geen lege placeholders.
4. Next-episode countdown card toont still + plot-snippet (laatste 20s van huidige episode).
5. Episode-art cache (Room-tabel) overleeft app-restart; geen refetch voor reeds-gecachete episodes.

**Files:** `player/PlayerActivity.kt`, `player/PlayerScreen.kt`, `ui/seriesdetail/SeriesDetailScreen.kt`, `ui/seriesdetail/SeriesDetailViewModel.kt`, `data/db/Entities.kt` (EpisodeArtCache), `data/db/EpisodeArtDao.kt` (nieuw), `data/db/IptvDatabase.kt` (migratie 7→8).

**Dependencies:** Geen.

---

## Phase 6: Genre rails + "Nieuw toegevoegd" + Trending

**Goal:** Drie nieuwe data-gedreven rails op home: Trending (TMDB), Nieuw (lokaal), Genre-rails (TMDB genres).

**Requirements:** DISC-01, DISC-02, DISC-03

**Success Criteria:**
1. "Trending deze week" rail toont min. 5 items wanneer TMDB-token configured én catalog overlap aanwezig is.
2. "Nieuw in je catalogus" rail toont items toegevoegd in laatste 14 dagen op basis van `addedAt`.
3. Genre-rails ("Actie", "Komedie", "Drama") tonen channels uit dezelfde genre-tag; aantal rails configurable in Settings.
4. Room-migratie 8→9 voegt `addedAt INTEGER` en `channel_genres` tabel toe zonder data-verlies.
5. Zonder TMDB-token: alleen "Nieuw in je catalogus" zichtbaar; geen crash.

**Files:** `data/db/Entities.kt`, `data/db/ChannelDao.kt`, `data/db/IptvDatabase.kt`, `data/tmdb/TmdbApi.kt`, nieuwe `data/tmdb/TmdbTrendingRepository.kt`, `ui/channels/ChannelsViewModel.kt`, `ui/channels/ChannelsScreen.kt`, `data/settings/SettingsStore.kt` (rail-toggles).

**Dependencies:** Phase 7 deelt Room-migratie-volgorde — Phase 6 vóór Phase 7 of merge migraties.

---

## Phase 7: "Omdat je X keek" + Watchlist

**Goal:** Personalisatie-rail per recent-finished title; Watchlist als aparte verzameling naast Favorites.

**Requirements:** PERS-01, PERS-02

**Success Criteria:**
1. Bij ≥1 recent-finished movie (positionMs/durationMs > 0.9): "Omdat je [Title] keek" rail toont matched similar-titles uit catalogus.
2. Max 2 "Omdat je X keek"-rails zichtbaar tegelijk; rotation bij volgende home-load.
3. "Bewaar voor later" knop op MovieDetail + SeriesDetail; click voegt toe aan Watchlist.
4. Home-rail "Mijn lijst (later)" toont Watchlist items; separaat van bestaande Favorites.
5. Room-migratie 9→10 (of gecombineerd met Phase 6) voegt `watchlist` + `recommendation_cache` toe.

**Files:** `data/db/Entities.kt`, `data/db/ChannelDao.kt`, `data/db/IptvDatabase.kt`, `ui/channels/ChannelsViewModel.kt`, `ui/detail/MovieDetailScreen.kt`, `ui/seriesdetail/SeriesDetailScreen.kt`.

**Dependencies:** Phase 6 (gedeelde migratie-versie).

---

## Cross-cutting

- **TMDB rate-limiting:** Phase 1+6 voegen API-calls toe — hergebruik bestaande retry/throttle in `TmdbClient` of voeg toe.
- **ChannelsScreen-split:** 2113 regels al, Phase 1+2+6+7 raken het — overweeg refactor-spike vóór Phase 1 (out-of-roadmap, mag aanvullen).
- **Verificatie:** elke phase op echte Formuler + Chromecast HD.
- **Manueel testen:** `./gradlew test` voor parser/matcher; UAT-script per phase via `/gsd-verify-work`.

---
*Last updated: 2026-05-14 after initialization*
