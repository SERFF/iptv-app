# Phase 7: "Omdat je X keek" + Watchlist — Plan

**Status:** Ready for execution

## Tasks

### Task 1 — Room migratie 9→10
```kotlin
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS watchlist (
                profileId TEXT NOT NULL,
                channelId TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                PRIMARY KEY (profileId, channelId),
                FOREIGN KEY (profileId) REFERENCES profiles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_watchlist_profile ON watchlist(profileId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS recommendation_cache (
                profileId TEXT NOT NULL,
                sourceChannelId TEXT NOT NULL,
                recommendedChannelIds TEXT NOT NULL,
                fetchedAt INTEGER NOT NULL,
                PRIMARY KEY (profileId, sourceChannelId)
            )
        """.trimIndent())
    }
}
// bump @Database(version = 10), register
```

### Task 2 — Entities
```kotlin
@Entity(tableName = "watchlist", primaryKeys = ["profileId", "channelId"], indices = [Index("profileId")])
data class WatchlistEntity(
    val profileId: String,
    val channelId: String,
    val addedAt: Long,
)

@Entity(tableName = "recommendation_cache", primaryKeys = ["profileId", "sourceChannelId"])
data class RecommendationCacheEntity(
    val profileId: String,
    val sourceChannelId: String,
    val recommendedChannelIds: String,
    val fetchedAt: Long,
)
```

### Task 3 — DAO methods
```kotlin
// WatchlistDao or inline in ChannelDao
@Query("SELECT channelId FROM watchlist WHERE profileId = :pid")
fun observeWatchlistIds(pid: String): Flow<List<String>>

@Query("INSERT OR IGNORE INTO watchlist (profileId, channelId, addedAt) VALUES (:pid, :cid, :now)")
suspend fun addWatchlist(pid: String, cid: String, now: Long = System.currentTimeMillis())

@Query("DELETE FROM watchlist WHERE profileId = :pid AND channelId = :cid")
suspend fun removeWatchlist(pid: String, cid: String)

@Query("""
    SELECT c.* FROM channels c
    JOIN watchlist w ON c.id = w.channelId
    WHERE w.profileId = :pid
    ORDER BY w.addedAt DESC LIMIT 30
""")
suspend fun getWatchlistChannels(pid: String): List<ChannelEntity>

// Recommendation cache
@Query("SELECT * FROM recommendation_cache WHERE profileId = :pid AND sourceChannelId = :sid")
suspend fun getRecommendation(pid: String, sid: String): RecommendationCacheEntity?

@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertRecommendation(row: RecommendationCacheEntity)

@Query("DELETE FROM recommendation_cache WHERE profileId = :pid")
suspend fun clearRecommendationsFor(pid: String)
```

### Task 4 — `RecommendationRepository` (nieuw)
```kotlin
class RecommendationRepository(
    private val dao: ChannelDao,
    private val tmdbMovieDetails: TmdbMovieDetailsRepository,
) {
    suspend fun buildRailsForProfile(profileId: String): List<BecauseYouWatchedRail> {
        val recentFinished = dao.recentFinishedMovies(profileId, threshold = 0.9, since = thirtyDaysAgo())
            .take(3)
        if (recentFinished.isEmpty()) return emptyList()

        val picks = recentFinished.shuffled().take(2)
        return picks.mapNotNull { source ->
            val cached = dao.getRecommendation(profileId, source.id)
            val ids = if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TTL) {
                cached.recommendedChannelIds.split(",").filter { it.isNotBlank() }
            } else {
                val bundle = runCatching { tmdbMovieDetails.lookupMovie(source.id, source.name, null) }.getOrNull()
                    ?: return@mapNotNull null
                val matched = tmdbMovieDetails.matchSimilar(bundle.similar, source.id, dao)
                val matchedIds = matched.map { it.id }
                dao.upsertRecommendation(RecommendationCacheEntity(
                    profileId, source.id, matchedIds.joinToString(","), System.currentTimeMillis()
                ))
                matchedIds
            }
            val channels = ids.mapNotNull { dao.channelById(it)?.toDomain() }
            if (channels.isEmpty()) null
            else BecauseYouWatchedRail(sourceTitle = source.name, items = channels)
        }
    }

    data class BecauseYouWatchedRail(val sourceTitle: String, val items: List<Channel>)
    companion object { private const val TTL = 24L * 3600 * 1000 }
}
```
Vereist `dao.recentFinishedMovies(...)` + `dao.channelById(...)` queries:
```kotlin
@Query("""
    SELECT c.* FROM channels c
    JOIN watch_progress wp ON c.id = wp.channelId
    WHERE wp.profileId = :pid
      AND c.type = 'MOVIE'
      AND wp.durationMs > 0
      AND (wp.positionMs * 1.0 / wp.durationMs) > :threshold
      AND wp.updatedAt > :since
    ORDER BY wp.updatedAt DESC LIMIT 10
""")
suspend fun recentFinishedMovies(pid: String, threshold: Double, since: Long): List<ChannelEntity>

@Query("SELECT * FROM channels WHERE id = :id")
suspend fun channelById(id: String): ChannelEntity?
```

### Task 5 — `IptvApp` wire-up
```kotlin
val recommendations by lazy { RecommendationRepository(db.channelDao(), tmdbMovieDetails) }
```

### Task 6 — `ChannelsViewModel` rails
```kotlin
val watchlistRail: StateFlow<List<Channel>> = ...
val becauseYouWatched: StateFlow<List<BecauseYouWatchedRail>> = ...

// On rail-build:
watchlistRail.value = dao.getWatchlistChannels(profileId).map { it.toDomain() }
becauseYouWatched.value = app.recommendations.buildRailsForProfile(profileId)
```

### Task 7 — ChannelsScreen rails
Toevoegen in volgorde D-10 uit context. "Omdat je X keek" rails als 2 items vóór genre-rails.

### Task 8 — Detail-screen Watchlist knop
Op MovieDetail + SeriesDetail:
```kotlin
val watchlistIds by viewModel.watchlistIds.collectAsState(emptyList())
val isInList = channel.id in watchlistIds
OutlinedButton(onClick = { viewModel.toggleWatchlist(channel.id) }) {
    Icon(
        if (isInList) Icons.Filled.BookmarkRemove else Icons.Outlined.BookmarkAdd,
        contentDescription = null,
    )
    Text(stringResource(if (isInList) R.string.detail_remove_from_list else R.string.detail_save_for_later))
}
```

In ViewModel:
```kotlin
val watchlistIds: Flow<List<String>> = dao.observeWatchlistIds(profileId)
fun toggleWatchlist(channelId: String) {
    viewModelScope.launch {
        if (channelId in watchlistIds.first()) dao.removeWatchlist(profileId, channelId)
        else dao.addWatchlist(profileId, channelId)
    }
}
```

### Task 9 — Refresh-invalidate
`RefreshUseCase` na catalog-refresh:
```kotlin
val activeProfile = settings.activeProfileId.first()
dao.clearRecommendationsFor(activeProfile)
// Channel-IDs kunnen veranderen, recommendations zijn stale.
```

### Task 10 — Settings rail-toggles uitbreiden
```kotlin
data class RailToggles(
    val trending: Boolean = true,
    val recentlyAdded: Boolean = true,
    val genres: Boolean = true,
    val watchlist: Boolean = true,           // nieuw
    val becauseYouWatched: Boolean = true,   // nieuw
)
```

### Task 11 — i18n
- `rail_watchlist` = "Mijn lijst (later)"
- `rail_because_you_watched` = "Omdat je %1$s keek"
- `detail_save_for_later` = "Bewaar voor later"
- `detail_remove_from_list` = "Verwijder uit lijst"

## File-list

| File | Type |
|------|------|
| `IptvDatabase.kt` | migration, bump v10 |
| `Entities.kt` | +WatchlistEntity, RecommendationCacheEntity |
| `ChannelDao.kt` | +queries |
| `data/recommend/RecommendationRepository.kt` | NEW |
| `IptvApp.kt` | wire-up |
| `ChannelsViewModel.kt` | +rails |
| `ChannelsScreen.kt` | render rails |
| `MovieDetailViewModel.kt` | +watchlist state/toggle |
| `MovieDetailScreen.kt` | +button |
| `SeriesDetailViewModel.kt` | +watchlist state/toggle |
| `SeriesDetailScreen.kt` | +button |
| `data/repo/RefreshUseCase.kt` | clear recommendations |
| `SettingsStore.kt` | extra toggles |
| `strings.xml` | 4 keys |

## Risico's

- **Genre-rail + Because-You-Watched overlap:** kan dezelfde films tonen. Acceptabel — geen dedup tussen rails.
- **Empty state:** als user 0 movies heeft afgekeken, "Omdat je X keek" gewoon verbergen.
- **Migratie 9→10 = last in milestone:** test op upgrade-path vanaf v6.

## Verificatie

| # | Criterium |
|---|-----------|
| 1 | "Omdat je X keek" rail bij ≥1 finished movie |
| 2 | Max 2 rails tegelijk |
| 3 | "Bewaar voor later" knop werkt, persistent |
| 4 | Watchlist-rail toont items |
| 5 | Migratie 9→10 + cascade-delete bij profile-verwijdering |

## Volgende stap
`/gsd-execute-phase 7`
