# Schets — "Populair deze week"-sectie voor VOD & Series

Status: Idee, niet in scope v1 (zie [prd.md](../prd.md) §4 Non-goals, §11 v2.0).
Doel van dit document: de aanpak vastleggen zodat 'm later één-op-één op te pakken is.

## Wat
Op het VOD- en Series-scherm een rij bovenaan: "Populair deze week". Toont alleen items
die de gebruiker ook daadwerkelijk kan afspelen vanuit zijn eigen Xtream-library.
Niet-matchbare items worden overgeslagen — geen dode posters.

## Databron: TMDB
- Endpoint films: `GET https://api.themoviedb.org/3/trending/movie/week?language=nl-NL`
- Endpoint series: `GET https://api.themoviedb.org/3/trending/tv/week?language=nl-NL`
- Auth: v4 bearer token, opgeslagen als `BuildConfig.TMDB_TOKEN` (lokaal in
  `local.properties`, niet committen).
- Posters: `https://image.tmdb.org/t/p/w500{poster_path}`.
- Rate limit: 50 req/s — ruim genoeg, we halen 2 endpoints per refresh.
- Caching: Room-tabel `trending_item` met `fetched_at`, TTL 12u. Stale-while-revalidate:
  toon oude data direct, refresh op achtergrond.

## Matchen met Xtream-library
Dit is het echte werk. TMDB zegt "Dune: Part Two (2024)", Xtream heeft
"Dune Part 2 2024 1080p WEB-DL x265".

### Strategie 1 — TMDB ID uit Xtream (voorkeur)
Veel Xtream-providers leveren `tmdb_id` of `tmdb` mee in:
- `get_vod_info` → `info.tmdb_id`
- `get_series_info` → `info.tmdb`

Eerst bij app-start één keer checken of onze provider dit vult. Zo ja: matching is
een simpele lookup op ID. Dit pad is betrouwbaar, gebruik altijd als beschikbaar.

### Strategie 2 — Fuzzy titel + jaar (fallback)
Als `tmdb_id` leeg is:
1. Normaliseer beide titels: lowercase, strip jaar, resolutie-tags
   (`1080p|720p|4k|2160p`), codec-tags (`x264|x265|hevc|h264`), bron-tags
   (`web-?dl|bluray|hdrip|webrip`), punctuatie, dubbele spaties.
2. Extract jaar met regex `\b(19|20)\d{2}\b` uit Xtream-titel.
3. Match = token-set ratio ≥ 90 **én** jaar binnen ±1 (release vs. rip-jaar).
4. Token-set ratio: Jaccard over gesplitste woorden, of FuzzyWuzzy-achtig algoritme —
   `org.apache.commons.text.similarity.JaroWinklerSimilarity` werkt prima en zit
   in een kleine lib.

Precompute een `Map<NormalizedTitle, VodItem>` bij cache-load, anders doe je O(n²)
op 5000+ items.

## Architectuur

```
data/
  trending/
    TmdbApi.kt              # Retrofit interface
    TrendingDto.kt          # kotlinx.serialization
    TrendingRepository.kt   # fetch + cache + match
    TitleMatcher.kt         # normalize + fuzzy match
  local/
    TrendingDao.kt
    TrendingEntity.kt

ui/
  vod/
    VodScreen.kt            # + TrendingRow bovenaan
  series/
    SeriesScreen.kt         # + TrendingRow bovenaan
  common/
    TrendingRow.kt          # herbruikbaar, Compose for TV
```

`TrendingRepository` geeft `Flow<List<PlayableTrendingItem>>` terug. Een
`PlayableTrendingItem` heeft:
- TMDB-metadata (titel, poster, rating, overview)
- Referentie naar `VodItem` / `SeriesItem` uit onze eigen library (voor playback)

Alleen items met succesvolle match komen in de flow.

## UI
- Rij bovenaan VOD-scherm en Series-scherm.
- Poster-kaarten, focus-scale bij D-pad-hover (consistent met rest van de app).
- OK → detail/player (zelfde flow als library-items, want het *is* een library-item).
- Leeg na matching? Rij verbergen, niet "niets gevonden" tonen.

## Open vragen voor later
- Wil je ook Nederlandse/lokale trending of puur global? TMDB heeft geen
  per-land trending, alleen `language`. Voor landspecifiek: overwegen
  `/discover/movie` met `region=NL` en `sort_by=popularity.desc`.
- "Populair" = TMDB-views. Trakt.tv meet echt kijkgedrag, wellicht relevanter.
  Nadeel: kleinere dataset, meer geskewed naar series-fans.
- Privacy: TMDB zelf heeft geen user-tracking nodig (geen user-context in calls),
  dus niks om zorgen over te maken.

## Verwijzing
- Haakt aan op v2.0 in [prd.md](../prd.md) §11.
- Vereist eerst VOD/Series-support überhaupt — dit is een *verrijking*, niet
  het fundament.
