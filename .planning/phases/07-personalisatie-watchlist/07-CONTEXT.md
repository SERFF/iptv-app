# Phase 7: "Omdat je X keek" + Watchlist - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

Twee personalisatie-features:
1. "Omdat je [Title] hebt gekeken: vergelijkbaar" rail — per recent-finished movie (positionMs/durationMs > 0.9), max 2 rails zichtbaar tegelijk.
2. Watchlist ("Bewaar voor later") — separate Room-tabel naast Favorites, eigen knop op detail-screens, eigen rail op home.
</domain>

<decisions>
## Implementation Decisions

### Schema
- **D-01:** Migratie 9→10:
  - `CREATE TABLE watchlist (profileId TEXT, channelId TEXT, addedAt INTEGER, PRIMARY KEY(profileId, channelId))` met FK naar profiles + index op profileId.
  - `CREATE TABLE recommendation_cache (profileId TEXT, sourceChannelId TEXT, recommendedChannelIds TEXT, fetchedAt INTEGER, PRIMARY KEY(profileId, sourceChannelId))`. `recommendedChannelIds` = comma-separated IDs.

### Personalisatie-logica
- **D-02:** "Recent-finished" = `WatchProgressEntity` waar `positionMs / durationMs > 0.9` AND `updatedAt > now - 30d`. Pak top 3 op updatedAt DESC; selecteer 2 random voor display.
- **D-03:** Per recent-finished movie: gebruik `TmdbMovieDetailsRepository.matchSimilar()` (bestaand) om vergelijkbare titels uit user's catalogus te halen. Cache resultaat in `recommendation_cache` met TTL 24u.
- **D-04:** Rail-titel format: "Omdat je {sourceTitle} keek". Maximum 2 rails tegelijk; rotation bij next home-load (random pick uit beschikbare bron-titels).
- **D-05:** Geen "Omdat je X keek" rails voor users met <1 recent-finished movie. Graceful empty.

### Watchlist
- **D-06:** `WatchlistEntity(profileId, channelId, addedAt)`. Profielen-gescoped (zoals favorites).
- **D-07:** Detail-screens (Movie + Series) krijgen "Bewaar voor later" knop naast Play/More-Info. Geactiveerd state = "Verwijder uit lijst". Iconen: bookmark-add / bookmark-remove.
- **D-08:** Home-rail "Mijn lijst (later)" toont watchlist items, profile-scoped. Separaat van bestaande Favorites-rail ("Mijn favorieten").
- **D-09:** Rail rendert in toggelable Settings (zoals Phase 6 rail-toggles).

### UI integratie
- **D-10:** Home-rail-volgorde (extending Phase 6):
  1. Continue Watching
  2. Trending
  3. Top 10
  4. Mijn lijst (later) ← nieuw
  5. Nieuw in catalogus
  6. Populair nu
  7. Omdat je {X} keek ← nieuw, max 2
  8. Omdat je {Y} keek ← nieuw
  9. Genre rails
  10. Mijn favorieten

### Performance
- **D-11:** Recommendation-cache wordt geleegd bij refresh van catalog (channel-IDs kunnen verschillen).
- **D-12:** Bij detail-screen open: prefetch matchSimilar() voor titels in `recent-finished` lijst (warmen voor home-render).

### Claude's Discretion
- Random vs round-robin selectie van 2 zichtbare "Omdat je X keek" rails.
- Of "Bewaar voor later" een ster-icon of bookmark krijgt.

</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` §Phase 7
- `.planning/REQUIREMENTS.md` §Personalization (PERS-01, PERS-02)
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/Entities.kt:51` — FavoriteEntity (template)
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/Entities.kt:62` — WatchProgressEntity
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/ChannelDao.kt` — favorites/progress queries
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbMovieDetailsRepository.kt:158` — matchSimilar()

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- FavoriteEntity + DAO als template voor WatchlistEntity.
- `observeFavoriteIds()` patroon → `observeWatchlistIds()`.
- `matchSimilar()` doet al heavy lifting.

### Established Patterns
- Profile-scoped queries met `WHERE profileId = :id`.
- Cascade delete via FK on profile-delete.

</code_context>

<specifics>
## Specific Ideas

- 30-dagen window voor "recent-finished" → prevents very stale rails.
- Top 3 recent → random 2 → variatie op home-loads zonder volledige shuffle.

</specifics>

<deferred>
## Deferred Ideas

- Cross-device sync van watchlist — out of scope, geen account-systeem.
- "Continue Watching" predictions — out of scope.
- Cosine-similarity matching tussen titels (eigen recommender) — TMDB volstaat.

</deferred>

---
