package nl.vanvrouwerff.iptv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY sortIndex ASC")
    fun observeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE type = :type ORDER BY sortIndex ASC LIMIT :limit")
    fun observeChannelsByType(type: String, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM channels")
    fun observeChannelCount(): Flow<Int>

    @Query(
        "SELECT * FROM channels WHERE type = :type AND name LIKE '%' || :query || '%' " +
            "COLLATE NOCASE ORDER BY sortIndex ASC LIMIT :limit",
    )
    fun searchChannels(type: String, query: String, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY sortIndex ASC")
    suspend fun allChannels(): List<ChannelEntity>

    @Query("SELECT * FROM categories ORDER BY sortIndex ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortIndex ASC")
    fun observeCategoriesByType(type: String): Flow<List<CategoryEntity>>

    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Transaction
    suspend fun replaceAll(channels: List<ChannelEntity>, categories: List<CategoryEntity>) {
        clearChannels()
        clearCategories()
        insertCategories(categories)
        // Chunk large inserts. Room does per-row binds on a prepared statement, so the
        // driver allocates a 200k-row ArrayList of bindings up front when passed one
        // giant list — measurable GC pressure on the Formuler. Chunking at 1 000 keeps
        // peak allocations low without adding round-trips that matter. Still one outer
        // @Transaction, so Flow observers only fire once on commit.
        channels.chunked(1000).forEach { insertChannels(it) }
    }

    @Query("SELECT channelId FROM favorites WHERE profileId = :profileId")
    fun observeFavoriteIds(profileId: String): Flow<List<String>>

    @Query(
        "INSERT OR IGNORE INTO favorites (profileId, channelId, addedAt) " +
            "VALUES (:profileId, :id, :now)",
    )
    suspend fun addFavorite(
        profileId: String,
        id: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND channelId = :id")
    suspend fun removeFavorite(profileId: String, id: String)

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getChannelById(id: String): ChannelEntity?

    /**
     * Up to `limit` other channels sharing the same groupTitle + type as `excludeId`.
     * Powers the "Meer in X" rail on detail screens.
     */
    @Query(
        "SELECT * FROM channels " +
            "WHERE type = :type AND groupTitle = :groupTitle AND id != :excludeId " +
            "ORDER BY sortIndex ASC LIMIT :limit",
    )
    suspend fun getRelatedByGroup(
        type: String,
        groupTitle: String,
        excludeId: String,
        limit: Int = 20,
    ): List<ChannelEntity>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND channelId = :id LIMIT 1")
    fun observeProgress(profileId: String, id: String): Flow<WatchProgressEntity?>

    /**
     * Progress rows for a specific set of channel / episode IDs. Used by the series detail
     * screen to compute per-season "X of Y bekeken" badges without polling every episode
     * row individually.
     */
    @Query(
        "SELECT * FROM watch_progress WHERE profileId = :profileId AND channelId IN (:ids)",
    )
    fun observeProgressForIds(
        profileId: String,
        ids: List<String>,
    ): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND channelId = :id LIMIT 1")
    suspend fun getProgress(profileId: String, id: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND channelId = :id")
    suspend fun clearProgress(profileId: String, id: String)

    // "Continue watching" — channels the user started but hasn't (nearly) finished, most
    // recently updated first. Series episodes have their progress stored under
    // `xt-episode:…` IDs, which aren't in the channels table, so this query naturally only
    // returns movies. Episode-level continue-watching needs a separate cache — future step.
    @Query(
        "SELECT c.*, w.updatedAt AS progressUpdatedAt, " +
            "w.positionMs AS positionMs, w.durationMs AS durationMs " +
            "FROM channels c " +
            "INNER JOIN watch_progress w ON c.id = w.channelId " +
            "WHERE c.type = :type " +
            "  AND w.profileId = :profileId " +
            "  AND w.positionMs > 0 " +
            "  AND w.durationMs > 0 " +
            "  AND w.positionMs < w.durationMs - :finishThresholdMs " +
            "ORDER BY w.updatedAt DESC " +
            "LIMIT :limit",
    )
    fun observeContinueWatching(
        profileId: String,
        type: String,
        finishThresholdMs: Long,
        limit: Int,
    ): Flow<List<ContinueWatchingMovieRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberEpisode(episode: WatchedEpisodeEntity)

    /**
     * Episodes the user has started watching, joined with their current progress. Same
     * "started but not finished" filter as the movie version. Ordered by most-recently
     * updated progress, not by first-watched time.
     */
    @Query(
        "SELECT e.*, w.updatedAt AS progressUpdatedAt, " +
            "w.positionMs AS positionMs, w.durationMs AS durationMs " +
            "FROM watched_episodes e " +
            "INNER JOIN watch_progress w ON e.episodeId = w.channelId AND e.profileId = w.profileId " +
            "WHERE e.profileId = :profileId " +
            "  AND w.positionMs > 0 " +
            "  AND w.durationMs > 0 " +
            "  AND w.positionMs < w.durationMs - :finishThresholdMs " +
            "ORDER BY w.updatedAt DESC " +
            "LIMIT :limit",
    )
    fun observeContinueWatchingEpisodes(
        profileId: String,
        finishThresholdMs: Long,
        limit: Int,
    ): Flow<List<ContinueWatchingEpisodeRow>>

    @Query(
        "SELECT w.updatedAt FROM watch_progress w " +
            "WHERE w.profileId = :profileId AND w.channelId = :channelId LIMIT 1",
    )
    suspend fun getProgressUpdatedAt(profileId: String, channelId: String): Long?

    // EPG / programmes.

    @Transaction
    suspend fun replaceProgrammes(programmes: List<ProgrammeEntity>) {
        clearProgrammes()
        programmes.chunked(1000).forEach { chunk -> insertProgrammes(chunk) }
    }

    @Query("DELETE FROM programmes")
    suspend fun clearProgrammes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(programmes: List<ProgrammeEntity>)

    /** Programmes currently airing at `now`, one per channel. */
    @Query(
        "SELECT * FROM programmes " +
            "WHERE startMs <= :now AND stopMs > :now " +
            "LIMIT :limit",
    )
    fun observeNowPlaying(now: Long, limit: Int = 500): Flow<List<ProgrammeEntity>>

    /** One-shot "what's airing now on this specific channel" lookup — for the zap banner. */
    @Query(
        "SELECT * FROM programmes " +
            "WHERE channelKey = :key AND startMs <= :now AND stopMs > :now " +
            "LIMIT 1",
    )
    suspend fun getNowPlayingFor(key: String, now: Long): ProgrammeEntity?

    /** Next programme strictly after `now` on a given channel — used for "Straks"-label. */
    @Query(
        "SELECT * FROM programmes " +
            "WHERE channelKey = :key AND startMs > :now " +
            "ORDER BY startMs ASC LIMIT 1",
    )
    suspend fun getNextProgrammeFor(key: String, now: Long): ProgrammeEntity?

    // Series info cache.

    @Query("SELECT * FROM series_info_cache WHERE seriesId = :seriesId LIMIT 1")
    suspend fun getSeriesInfoCache(seriesId: String): SeriesInfoCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSeriesInfoCache(entry: SeriesInfoCacheEntity)

    // TMDB popular cache (one row per list key).

    @Query("SELECT * FROM tmdb_popular_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun getTmdbPopularCache(key: String): TmdbPopularCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTmdbPopularCache(entry: TmdbPopularCacheEntity)
}
