# Phase 5: Skip Intro + Episode artwork + next-episode card — Plan

**Status:** Ready for execution

## Tasks

### Task 1 — Room migratie 7→8: episode_art_cache
```kotlin
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS episode_art_cache (
                episodeId TEXT NOT NULL PRIMARY KEY,
                seriesChannelId TEXT NOT NULL,
                seasonNumber INTEGER NOT NULL,
                episodeNumber INTEGER NOT NULL,
                stillUrl TEXT,
                plot TEXT,
                fetchedAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_episode_art_series ON episode_art_cache(seriesChannelId)")
    }
}
// bump @Database(version = 8), register migration
```

### Task 2 — `EpisodeArtEntity` + DAO
```kotlin
@Entity(tableName = "episode_art_cache", indices = [Index("seriesChannelId")])
data class EpisodeArtEntity(
    @PrimaryKey val episodeId: String,
    val seriesChannelId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val stillUrl: String?,
    val plot: String?,
    val fetchedAt: Long,
)

@Dao
interface EpisodeArtDao {
    @Query("SELECT * FROM episode_art_cache WHERE seriesChannelId = :id AND seasonNumber = :season")
    suspend fun getSeasonArt(id: String, season: Int): List<EpisodeArtEntity>

    @Query("SELECT * FROM episode_art_cache WHERE episodeId = :id")
    suspend fun getEpisodeArt(id: String): EpisodeArtEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<EpisodeArtEntity>)
}
```
Voeg `episodeArtDao()` toe aan `IptvDatabase`.

### Task 3 — `TmdbApi` TV season endpoint
```kotlin
@GET("tv/{tvId}/season/{seasonNumber}")
suspend fun getTvSeason(
    @Path("tvId") tvId: Long,
    @Path("seasonNumber") seasonNumber: Int,
    @Query("language") language: String = "nl-NL",
): TmdbTvSeasonDto

@Serializable
data class TmdbTvSeasonDto(
    val id: Long,
    val episodes: List<TmdbTvEpisodeDto>,
)

@Serializable
data class TmdbTvEpisodeDto(
    val id: Long,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
)
```

### Task 4 — `TmdbEpisodeArtRepository` (nieuw)
```kotlin
class TmdbEpisodeArtRepository(private val dao: EpisodeArtDao) {
    suspend fun fetchAndCacheSeason(
        seriesChannelId: String,
        tmdbTvId: Long,
        seasonNumber: Int,
    ) {
        if (!TmdbClient.isConfigured) return
        val cached = dao.getSeasonArt(seriesChannelId, seasonNumber)
        if (cached.isNotEmpty() && cached.all { System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MS }) return

        val season = runCatching {
            withContext(Dispatchers.IO) { TmdbClient.api.getTvSeason(tmdbTvId, seasonNumber) }
        }.getOrNull() ?: return

        val entities = season.episodes.map {
            EpisodeArtEntity(
                episodeId = "$seriesChannelId-s${it.seasonNumber}e${it.episodeNumber}",
                seriesChannelId = seriesChannelId,
                seasonNumber = it.seasonNumber,
                episodeNumber = it.episodeNumber,
                stillUrl = it.stillPath?.let { p -> "https://image.tmdb.org/t/p/w300$p" },
                plot = it.overview,
                fetchedAt = System.currentTimeMillis(),
            )
        }
        dao.upsertAll(entities)
    }

    suspend fun getEpisodeArt(episodeId: String): EpisodeArtEntity? = dao.getEpisodeArt(episodeId)

    companion object { private const val CACHE_TTL_MS = 30L * 24 * 3600 * 1000 }
}
```

### Task 5 — `IptvApp` wire-up
```kotlin
val tmdbEpisodeArt by lazy { TmdbEpisodeArtRepository(db.episodeArtDao()) }
```

### Task 6 — `SeriesDetailViewModel` integratie
```kotlin
fun onSeasonSelected(season: Int) {
    viewModelScope.launch {
        val tmdbId = state.value.tmdbId ?: return@launch
        app.tmdbEpisodeArt.fetchAndCacheSeason(state.value.channelId, tmdbId, season)
        // expose via state: episodeArt: Map<String, EpisodeArtEntity>
    }
}
```

### Task 7 — `SeriesDetailScreen` episode-grid update
```kotlin
@Composable
private fun EpisodeRow(episode: Episode, art: EpisodeArtEntity?) {
    Row(...) {
        // Thumbnail 160x90dp
        Box(modifier = Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(8.dp))) {
            if (art?.stillUrl != null) {
                AsyncImage(art.stillUrl, ...)
            } else {
                // gradient placeholder met "S{n}E{n}"
                Box(modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(AccentDeep, SurfaceLift))
                ), contentAlignment = Alignment.Center) {
                    Text("S${episode.season}E${episode.episode}", style = labelLarge)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column { Text(episode.title); art?.plot?.let { Text(it.take(120), style = bodySmall) } }
    }
}
```

### Task 8 — Skip Intro overlay in PlayerScreen
```kotlin
val pos by player.positionState.collectAsState()
val isSeries = currentContentType == ContentType.SERIES
val showSkip = isSeries && pos < 90_000L

if (showSkip) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(20_000L); visible = false }
    AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp)) {
        Button(onClick = { player.seekTo(90_000L) }) {
            Text(stringResource(R.string.player_skip_intro))
        }
    }
}
```

### Task 9 — Next-episode card uitbreiden
In bestaande overlay (laatste 20s):
```kotlin
val art = remember(nextEpisode.id) {
    flow {
        emit(app.tmdbEpisodeArt.getEpisodeArt(nextEpisode.id))
    }
}.collectAsState(null).value

Column(...) {
    if (art?.stillUrl != null) {
        AsyncImage(art.stillUrl, modifier = Modifier.size(320.dp, 180.dp))
    } else {
        // gradient placeholder
    }
    Text("Volgende: S${nextEpisode.season}E${nextEpisode.episode} ${nextEpisode.title}")
    art?.plot?.let { Text(it.take(120), style = bodySmall, maxLines = 2) }
    // existing countdown + buttons
}
```

### Task 10 — i18n
- `player_skip_intro` = "Intro overslaan"
- `next_episode_label` = "Volgende"

## File-list

| File | Type |
|------|------|
| `IptvDatabase.kt` | migration, bump version, dao |
| `Entities.kt` | EpisodeArtEntity |
| `EpisodeArtDao.kt` | NEW |
| `TmdbApi.kt` | TV season endpoint + DTOs |
| `data/tmdb/TmdbEpisodeArtRepository.kt` | NEW |
| `IptvApp.kt` | wire-up |
| `SeriesDetailViewModel.kt` | fetch trigger + state |
| `SeriesDetailScreen.kt` | EpisodeRow update |
| `PlayerScreen.kt` | Skip Intro overlay |
| `PlayerActivity.kt` | next-episode card update |
| `strings.xml` | 2 keys |

## Risico's

- **TMDB TV-id mapping:** SeriesDetailViewModel moet weten of er een gekoppelde TMDB tv-id is. Phase 1.x voor SERIES-trailers heeft dezelfde behoefte — overweeg `TmdbCatalogueMatcher` uit te breiden naar TV.
- **Skip Intro op live TV:** mag NIET zichtbaar zijn voor `ContentType.TV` — extra check.
- **Migratie 7→8:** test op upgrade-path.

## Verificatie

| # | Criterium |
|---|-----------|
| 1 | Skip Intro alleen bij SERIES, 0-90s |
| 2 | Skip jumpt naar 90s |
| 3 | Episode-thumbnails zichtbaar of placeholder |
| 4 | Next-episode card met still + plot |
| 5 | Cache overleeft restart |

## Volgende stap
`/gsd-execute-phase 5`
