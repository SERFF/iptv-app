# Phase 6: Genre + Nieuw + Trending rails — Plan

**Status:** Ready for execution

## Tasks

### Task 1 — Room migratie 8→9
```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE channels ADD COLUMN addedAt INTEGER")
        db.execSQL("UPDATE channels SET addedAt = ${System.currentTimeMillis()} WHERE addedAt IS NULL")
        db.execSQL("CREATE INDEX idx_channels_addedAt ON channels(addedAt)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS channel_genres (
                channelId TEXT NOT NULL,
                tmdbGenreId INTEGER NOT NULL,
                PRIMARY KEY (channelId, tmdbGenreId),
                FOREIGN KEY (channelId) REFERENCES channels(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_channel_genres_genre ON channel_genres(tmdbGenreId)")
    }
}
// bump @Database(version = 9), register migration
```

### Task 2 — Entities + DAOs
```kotlin
// Entities.kt: ChannelEntity krijgt addedAt: Long?
data class ChannelEntity(
    ...
    val addedAt: Long? = null,
)

@Entity(tableName = "channel_genres", primaryKeys = ["channelId", "tmdbGenreId"])
data class ChannelGenreEntity(
    val channelId: String,
    val tmdbGenreId: Int,
)

// ChannelDao additions:
@Query("SELECT * FROM channels WHERE addedAt > :cutoff ORDER BY addedAt DESC LIMIT 20")
suspend fun getRecentlyAdded(cutoff: Long): List<ChannelEntity>

@Query("""
    SELECT c.* FROM channels c
    JOIN channel_genres cg ON c.id = cg.channelId
    WHERE cg.tmdbGenreId = :genreId
    ORDER BY c.sortIndex ASC LIMIT 30
""")
suspend fun getChannelsByGenre(genreId: Int): List<ChannelEntity>

@Query("SELECT DISTINCT tmdbGenreId FROM channel_genres")
suspend fun getAvailableGenres(): List<Int>

@Query("""
    SELECT cg.tmdbGenreId AS genreId, COUNT(*) AS count
    FROM channel_genres cg
    GROUP BY cg.tmdbGenreId
    ORDER BY count DESC
""")
suspend fun getGenreCounts(): List<GenreCountRow>

data class GenreCountRow(val genreId: Int, val count: Int)

// ChannelGenreDao or inline in ChannelDao
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertGenres(rows: List<ChannelGenreEntity>)

@Query("DELETE FROM channel_genres WHERE channelId = :channelId")
suspend fun clearGenresFor(channelId: String)
```

### Task 3 — TmdbApi endpoints + DTOs
```kotlin
@GET("trending/movie/week")
suspend fun getTrendingMovies(): TmdbMovieListDto

@GET("trending/tv/week")
suspend fun getTrendingTv(): TmdbTvListDto

@GET("genre/movie/list")
suspend fun getMovieGenres(@Query("language") lang: String = "nl-NL"): TmdbGenreListDto

@GET("genre/tv/list")
suspend fun getTvGenres(@Query("language") lang: String = "nl-NL"): TmdbGenreListDto

@Serializable
data class TmdbGenreListDto(val genres: List<TmdbGenreDto>)

@Serializable
data class TmdbGenreDto(val id: Int, val name: String)
```

### Task 4 — TmdbTrendingRepository (nieuw)
```kotlin
class TmdbTrendingRepository(private val dao: ChannelDao) {
    private var cache: TrendingCache? = null
    private val mutex = Mutex()

    suspend fun getTrendingMatched(): List<Channel> = mutex.withLock {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }?.matched?.let { return@withLock it }
        if (!TmdbClient.isConfigured) return emptyList()

        val movies = runCatching { TmdbClient.api.getTrendingMovies() }.getOrNull()?.results.orEmpty()
        val tvs = runCatching { TmdbClient.api.getTrendingTv() }.getOrNull()?.results.orEmpty()

        val all = movies.map { it.title to it.releaseDate?.take(4)?.toIntOrNull() } +
                  tvs.map { it.name to it.firstAirDate?.take(4)?.toIntOrNull() }

        val matched = withContext(Dispatchers.Default) {
            val userChannels = dao.allChannels().map { it.toDomain() }
            // match by normalized title + year using TmdbCatalogueMatcher
            matchTitlesAgainstCatalogue(all, userChannels)
        }
        cache = TrendingCache(now, matched)
        matched
    }

    private data class TrendingCache(val fetchedAt: Long, val matched: List<Channel>)
    companion object { private const val CACHE_TTL_MS = 24L * 3600 * 1000 }
}
```

### Task 5 — TmdbGenreRepository (nieuw)
Cache genre-id → name in DataStore. Fetch op eerste call van eender welk rail.
```kotlin
class TmdbGenreRepository(private val settings: SettingsStore) {
    private var genreNames: Map<Int, String>? = null
    suspend fun nameFor(genreId: Int): String? {
        ensureLoaded()
        return genreNames?.get(genreId)
    }

    private suspend fun ensureLoaded() {
        if (genreNames != null) return
        if (!TmdbClient.isConfigured) return
        val cached = settings.cachedGenreMap()  // Map<Int, String>?
        if (cached != null && System.currentTimeMillis() - settings.genreMapFetchedAt() < TTL) {
            genreNames = cached
            return
        }
        val movies = runCatching { TmdbClient.api.getMovieGenres().genres }.getOrNull().orEmpty()
        val tv = runCatching { TmdbClient.api.getTvGenres().genres }.getOrNull().orEmpty()
        val merged = (movies + tv).associate { it.id to it.name }
        settings.saveGenreMap(merged)
        genreNames = merged
    }
}
```

### Task 6 — TmdbMovieDetailsRepository update
Bij `lookupMovie()` na success: extract genre-IDs uit details, persist via `dao.upsertGenres()`.
```kotlin
val genres = details.genres.orEmpty().map { ChannelGenreEntity(channelId, it.id) }
if (genres.isNotEmpty()) {
    dao.clearGenresFor(channelId)
    dao.upsertGenres(genres)
}
```

### Task 7 — RefreshUseCase update
Bij elke refresh: nieuwe items krijgen `addedAt = now`. Bestaande items behouden bestaande addedAt. Migratie handles initial seed.

### Task 8 — `ChannelsViewModel` rails uitbreiden
```kotlin
val trendingRail: StateFlow<List<Channel>> = ...
val recentlyAddedRail: StateFlow<List<Channel>> = ...
val genreRails: StateFlow<List<GenreRail>> = ...

data class GenreRail(val genreId: Int, val name: String, val items: List<Channel>)

// On rail-build:
val enabledRails = settings.enabledRails.first()
if (enabledRails.trending) trendingRail.value = app.tmdbTrending.getTrendingMatched()
if (enabledRails.recentlyAdded) {
    val cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
    recentlyAddedRail.value = dao.getRecentlyAdded(cutoff).map { it.toDomain() }
}
val selectedGenres = settings.selectedGenres.first()
genreRails.value = selectedGenres.map { gid ->
    GenreRail(gid, app.tmdbGenres.nameFor(gid) ?: "Genre", dao.getChannelsByGenre(gid).map { it.toDomain() })
}
```

### Task 9 — ChannelsScreen — render rails
Voeg `RailRow` items toe in correcte volgorde (zie D-07). Hergebruik bestaande `Rail` composable.

### Task 10 — SettingsStore + Settings UI
```kotlin
// SettingsStore additions
val enabledRails: Flow<RailToggles>  // DataStore object
val selectedGenres: Flow<List<Int>>  // max 5
suspend fun setEnabledRails(t: RailToggles)
suspend fun setSelectedGenres(ids: List<Int>)

data class RailToggles(
    val trending: Boolean = true,
    val recentlyAdded: Boolean = true,
    val genres: Boolean = true,
)
```

Nieuw `HomeRailsSettingsScreen.kt`:
- Toggle per rail
- Genre-picker: lijst genres uit `dao.getGenreCounts()` met TMDB-naam, checkbox max 5.

### Task 11 — i18n
- `rail_trending` = "Trending deze week"
- `rail_recently_added` = "Nieuw in je catalogus"
- `settings_home_rails` = "Home rails"

## File-list

| File | Type |
|------|------|
| `IptvDatabase.kt` | migration, bump v9, +DAOs |
| `Entities.kt` | +ChannelGenreEntity, ChannelEntity.addedAt |
| `ChannelDao.kt` | +queries |
| `TmdbApi.kt` | +endpoints + DTOs |
| `data/tmdb/TmdbTrendingRepository.kt` | NEW |
| `data/tmdb/TmdbGenreRepository.kt` | NEW |
| `data/tmdb/TmdbMovieDetailsRepository.kt` | +genre persist |
| `IptvApp.kt` | wire-up |
| `data/repo/RefreshUseCase.kt` | addedAt-seed |
| `ChannelsViewModel.kt` | new rails state |
| `ChannelsScreen.kt` | render new rails |
| `SettingsStore.kt` | rail toggles + selected genres |
| `ui/settings/HomeRailsSettingsScreen.kt` | NEW |
| `ui/settings/SettingsScreen.kt` | link to HomeRails |
| `strings.xml` | 3 keys |

## Risico's

- **Catalog refresh + addedAt:** moet onderscheid maken tussen "nieuwe channel" en "channel bestond al maar addedAt was NULL". Migration handles initial NULL→now seed; daarna check-before-insert in RefreshUseCase.
- **Genre-data afhankelijk van TMDB-lookups:** Voor films die nooit detail-bekeken werden zijn er geen genres. Trigger background-lookup voor first-page hero/popular kan helpen.
- **Migratie 8→9 grootste in milestone:** test goed.

## Verificatie

| # | Criterium |
|---|-----------|
| 1 | Trending-rail min 5 items met TMDB token |
| 2 | Nieuw-rail toont items < 14d |
| 3 | Genre-rails (geselecteerde 0-5) zichtbaar |
| 4 | Migratie 8→9 zonder data-verlies |
| 5 | Zonder TMDB token: alleen Nieuw zichtbaar |

## Volgende stap
`/gsd-execute-phase 6`
