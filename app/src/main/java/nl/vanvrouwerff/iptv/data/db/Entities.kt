package nl.vanvrouwerff.iptv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [Index("groupTitle"), Index("sortIndex"), Index("type")],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val streamUrl: String?,
    val epgChannelId: String?,
    val sortIndex: Int,
    val type: String,
)

@Entity(
    tableName = "categories",
    primaryKeys = ["id", "type"],
)
data class CategoryEntity(
    val id: String,
    val name: String,
    val sortIndex: Int,
    val type: String,
)

@Entity(
    tableName = "profiles",
    indices = [Index("sortIndex")],
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ARGB tint for the avatar circle. Stored as Int so Room can bind it directly. */
    val colorArgb: Int,
    val sortIndex: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["profileId", "channelId"],
    indices = [Index("profileId")],
)
data class FavoriteEntity(
    val profileId: String,
    val channelId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "watch_progress",
    primaryKeys = ["profileId", "channelId"],
    indices = [Index("profileId")],
)
data class WatchProgressEntity(
    val profileId: String,
    val channelId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/**
 * Lightweight metadata cache for a series episode the user has started watching. Series
 * episodes don't live in the main `channels` table (which holds only live/VOD/series-meta),
 * so this table lets us reconstruct episode info for the "Continue watching" rail without
 * having to refetch `get_series_info` every time.
 */
@Entity(
    tableName = "watched_episodes",
    primaryKeys = ["profileId", "episodeId"],
    indices = [Index("profileId")],
)
data class WatchedEpisodeEntity(
    val profileId: String,
    val episodeId: String,
    val seriesChannelId: String,
    val seriesName: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val streamUrl: String,
    val coverUrl: String?,
    val durationSecs: Long,
    val firstWatchedAt: Long,
)

/** Join row for continue-watching, carrying the progress timestamp for cross-type sort. */
data class ContinueWatchingMovieRow(
    @androidx.room.Embedded val channel: ChannelEntity,
    val progressUpdatedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
)

data class ContinueWatchingEpisodeRow(
    @androidx.room.Embedded val episode: WatchedEpisodeEntity,
    val progressUpdatedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * A single programme from XMLTV (EPG). `channelKey` matches the Xtream channel's
 * `epg_channel_id` (a.k.a. tvg-id). Composite PK keeps duplicates out if the provider
 * double-lists an airing.
 */
@Entity(
    tableName = "programmes",
    primaryKeys = ["channelKey", "startMs"],
    indices = [
        Index("channelKey"),
        Index(value = ["startMs", "stopMs"]),
    ],
)
data class ProgrammeEntity(
    val channelKey: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
)

/**
 * Cached `get_series_info` JSON per series. Opening the same series twice in quick
 * succession shouldn't hit the network again — the show's episode list rarely changes
 * within a 24-hour window.
 */
@Entity(tableName = "series_info_cache")
data class SeriesInfoCacheEntity(
    @PrimaryKey val seriesId: String,
    val payloadJson: String,
    val fetchedAt: Long,
)

/**
 * Cached TMDB response for a list key like "popular_tv". A single row per list is enough
 * for our use case — we only have one "Populair nu" rail to populate.
 */
@Entity(tableName = "tmdb_popular_cache")
data class TmdbPopularCacheEntity(
    @PrimaryKey val cacheKey: String,
    val payloadJson: String,
    val fetchedAt: Long,
)
