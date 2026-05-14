# Phase 6: Genre + Nieuw + Trending rails - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

Drie nieuwe data-gedreven rails op home-screen:
1. "Trending deze week" — TMDB `trending/movies/week` + `trending/tv/week` gematcht met catalogus.
2. "Nieuw in je catalogus" — items toegevoegd in afgelopen 14 dagen op basis van `addedAt`.
3. Genre-rails — TMDB genres ("Actie", "Komedie", "Drama") configureerbaar via Settings per profiel.

Zonder TMDB-token: alleen "Nieuw in je catalogus" actief.
</domain>

<decisions>
## Implementation Decisions

### Schema
- **D-01:** Migratie 8→9: `ALTER TABLE channels ADD COLUMN addedAt INTEGER`. NULL voor bestaande rijen; geseed op `System.currentTimeMillis()` bij eerste refresh na upgrade. Nieuwe channels altijd addedAt-set.
- **D-02:** Nieuwe tabel `channel_genres`: `(channelId TEXT, tmdbGenreId INTEGER, PRIMARY KEY (channelId, tmdbGenreId))` + index op `tmdbGenreId`. Many-to-many.

### Genre-bron
- **D-03:** Genres komen uit TMDB lookup (movie genres + tv genres). Bij `TmdbMovieDetailsRepository.lookupMovie()` ook genre-IDs opslaan in `channel_genres`. Idem voor TV-lookup (Phase 1.x).
- **D-04:** Genre-naam-mapping uit TMDB `genre/movie/list` + `genre/tv/list` endpoints. Cache 30 dagen in DataStore (klein, niet Room).

### TMDB endpoints
- **D-05:** `TmdbApi`:
  - `GET trending/movie/week` → reuse `TmdbMovieListDto`
  - `GET trending/tv/week` → reuse `TmdbTvListDto`
  - `GET genre/movie/list`, `GET genre/tv/list` → `TmdbGenreListDto`
- **D-06:** `TmdbTrendingRepository` patroon van `TmdbPopularRepository`: 24u cache, match tegen lokale catalogus, returnt `List<Channel>`.

### UI rails
- **D-07:** Nieuwe rails verschijnen in deze volgorde op home (na bestaande):
  1. Continue Watching (bestaand)
  2. **Trending deze week** (nieuw, eerst zichtbaar voor impact)
  3. Top 10 (bestaand)
  4. **Nieuw in je catalogus** (nieuw)
  5. Populair nu (bestaand)
  6. **Genre rails** (per geselecteerd genre — max 5 rails)
  7. Mijn lijst (bestaand)
- **D-08:** Rails configureerbaar via Settings → "Home rails" submenu. Per rail een toggle (on/off). Genre-rails apart: gebruiker kiest welke 0-5 genres tonen.

### Genre-discovery
- **D-09:** Bij eerste keer Settings → Home rails → Genres: lijst alle unieke genres aanwezig in user's catalogus (uit `channel_genres` join). Per genre count erbij ("Actie (124 films)"). Maxima toonbaar tegelijk: 5.

### Performance
- **D-10:** Trending-rail bij TMDB-call rate-limit verlaagd naar 1× per dag. Lokaal opgeslagen + alleen background-refresh.
- **D-11:** Genre-rail queries indexed op `tmdbGenreId` → snel.
- **D-12:** "Nieuw"-rail: `SELECT * FROM channels WHERE addedAt > :cutoff ORDER BY addedAt DESC LIMIT 20`.

### Claude's Discretion
- Exact volgorde van rails (kan via Settings).
- Default-geselecteerde genres (mss top 3 op count).

</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` §Phase 6
- `.planning/REQUIREMENTS.md` §Discovery (DISC-01, DISC-02, DISC-03)
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbPopularRepository.kt` — pattern template
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbApi.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/IptvDatabase.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/Entities.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/ChannelDao.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsViewModel.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/settings/SettingsStore.kt`

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `TmdbPopularRepository` als template voor `TmdbTrendingRepository`.
- `TmdbMovieDetailsRepository.matchSimilar()` als template voor match-against-catalogue.
- `MatchedIdsCache` in Entities.kt (147-152) voor rail-ID caching.

### Established Patterns
- Migrations registered in DB-builder.
- Service-locator entries in IptvApp.
- Rail-builder pattern in ChannelsViewModel.

</code_context>

<specifics>
## Specific Ideas

- "Nieuw in je catalogus" cutoff: 14 dagen. Configureerbaar later.
- Trending refresh in `RefreshUseCase` (bestaand) tegelijk met catalog-refresh.

</specifics>

<deferred>
## Deferred Ideas

- "Top 10 in jouw land" met geo-detect — out of scope.
- Sub-genres (action-comedy, etc.) — TMDB heeft het maar te complex voor v1.
- Dynamic rail-ordering (Netflix prioriteit-AI) — out of scope.

</deferred>

---
