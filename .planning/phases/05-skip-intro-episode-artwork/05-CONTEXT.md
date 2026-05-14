# Phase 5: Skip Intro + Episode artwork + next-episode card - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

Player toont "Skip Intro" knop in eerste 90s van SERIES-episodes (heuristisch, geen ML). Series-detail krijgt per-episode thumbnails. Next-episode countdown card (laatste 20s) toont episode-still + plot-snippet.
</domain>

<decisions>
## Implementation Decisions

### Skip Intro
- **D-01:** Pure tijd-heuristiek: button zichtbaar van positie 0 tot 90s, ALLEEN voor SERIES-episodes. Niet voor MOVIE of TV.
- **D-02:** Klik (KEYCODE_DPAD_CENTER) → `player.seekTo(90_000L)`. Button hidden bij `currentPosition >= 90s`.
- **D-03:** Button-overlay positie: rechts-onder, boven seekbar. Auto-fade na 20s zichtbaarheid (gebruiker kan negeren).

### Episode artwork
- **D-04:** Nieuwe Room-tabel `episode_art_cache`:
```
episode_art_cache (
    episodeId TEXT PRIMARY KEY,
    seriesChannelId TEXT,
    seasonNumber INTEGER,
    episodeNumber INTEGER,
    stillUrl TEXT NULL,
    plot TEXT NULL,
    fetchedAt INTEGER NOT NULL
)
```
- **D-05:** Fetching strategy: bij Series-detail open, fire-and-forget background fetch voor zichtbare seizoen. TMDB endpoint `tv/{tmdbId}/season/{n}` levert episodes-array met `still_path` + `overview` voor alle episodes ineens (één HTTP call). Cache TTL 30 dagen.
- **D-06:** Fallback wanneer cache miss en fetching pending: Xtream `movie_image` (al beschikbaar via `WatchedEpisodeEntity.coverUrl` voor watched), of series-cover. Nooit lege placeholder zichtbaar.
- **D-07:** Migratie 7→8: CREATE TABLE episode_art_cache.

### Next-episode card
- **D-08:** Bestaande overlay in PlayerActivity (laatste 20s) uitbreiden:
  - Top: episode-still 320×180dp (uit cache)
  - Body: "Volgende: S{n}E{n} {title}"
  - Plot-snippet (2 regels, bodySmall)
  - Countdown-bar + "Play Now"/"Cancel" knoppen (bestaand)
- **D-09:** Bij geen still beschikbaar: gradient placeholder met S{n}E{n} groot. Geen crash.

### TMDB integratie
- **D-10:** `TmdbApi.kt` krijgt `getTvSeason(tvId, seasonNumber)` endpoint.
- **D-11:** `TmdbEpisodeArtRepository` (nieuw) wraps fetch + cache, hergebruikt patroon van `TmdbMovieDetailsRepository`.

### Claude's Discretion
- Skip Intro button styling (compact vs prominent).
- Stilt cache eviction-strategie (handmatig of automatisch op refresh).

</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` §Phase 5
- `.planning/REQUIREMENTS.md` §Player + Series (PLAY-01, SERIE-01, SERIE-02)
- `app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerActivity.kt` — next-episode overlay
- `app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerScreen.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/seriesdetail/SeriesDetailScreen.kt` — episode grid
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/Entities.kt:81` — WatchedEpisodeEntity
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/IptvDatabase.kt` — migrations
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbApi.kt`

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- PlayerActivity heeft al countdown-card structuur in laatste 20s.
- WatchedEpisodeEntity heeft `coverUrl` veld als fallback.
- TmdbMovieDetailsRepository als template voor TmdbEpisodeArtRepository.

### Established Patterns
- Migrations als anonymous Migration classes, registered in DB-builder.
- Repository met cache + IO dispatcher + Retrofit-call.

</code_context>

<specifics>
## Specific Ideas

- 90s skip-intro window is conservatieve heuristiek. Echte Netflix doet ML-detection van patroon — buiten scope.
- Bij episode-fetch: alleen seizoen waar gebruiker nu in zit. Niet hele serie tegelijk (rate limit).

</specifics>

<deferred>
## Deferred Ideas

- Skip Outro detectie (einde van episode) — out of scope.
- ML-based intro detection — STB te beperkt.
- Chapter markers in seekbar — out of scope.

</deferred>

---
