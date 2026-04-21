package nl.vanvrouwerff.iptv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChannelEntity::class,
        CategoryEntity::class,
        FavoriteEntity::class,
        WatchProgressEntity::class,
        WatchedEpisodeEntity::class,
        ProgrammeEntity::class,
        SeriesInfoCacheEntity::class,
        TmdbPopularCacheEntity::class,
        ProfileEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile private var instance: IptvDatabase? = null

        /** The built-in profile every install starts with. Always present; cannot be deleted. */
        const val DEFAULT_PROFILE_ID = "default"
        const val DEFAULT_PROFILE_NAME = "Hoofdprofiel"
        // Pleasant accent tint for the default avatar; users can pick their own for new profiles.
        const val DEFAULT_PROFILE_COLOR_ARGB: Int = 0xFFE53935.toInt()

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorites (" +
                        "channelId TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(channelId))",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_progress (" +
                        "channelId TEXT NOT NULL, " +
                        "positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(channelId))",
                )
            }
        }

        /**
         * v8 → v9: introduce multi-profile support.
         *
         * 1. Create the `profiles` table and seed a single "Hoofdprofiel" row. Every
         *    pre-upgrade favorite / progress / episode belongs to this profile.
         * 2. Rebuild `favorites`, `watch_progress`, and `watched_episodes` with a
         *    `profileId` column and composite primary key. SQLite can't ALTER a PK in
         *    place, so we create the new table, copy rows over assigning the default
         *    profile, drop the old table, and rename.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profiles (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "colorArgb INTEGER NOT NULL, " +
                        "sortIndex INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_sortIndex ON profiles(sortIndex)")
                db.execSQL(
                    "INSERT OR IGNORE INTO profiles (id, name, colorArgb, sortIndex, createdAt) " +
                        "VALUES ('$DEFAULT_PROFILE_ID', '$DEFAULT_PROFILE_NAME', " +
                        "$DEFAULT_PROFILE_COLOR_ARGB, 0, $now)",
                )

                db.execSQL(
                    "CREATE TABLE favorites_new (" +
                        "profileId TEXT NOT NULL, " +
                        "channelId TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(profileId, channelId))",
                )
                db.execSQL(
                    "INSERT INTO favorites_new (profileId, channelId, addedAt) " +
                        "SELECT '$DEFAULT_PROFILE_ID', channelId, addedAt FROM favorites",
                )
                db.execSQL("DROP TABLE favorites")
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_profileId ON favorites(profileId)")

                db.execSQL(
                    "CREATE TABLE watch_progress_new (" +
                        "profileId TEXT NOT NULL, " +
                        "channelId TEXT NOT NULL, " +
                        "positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(profileId, channelId))",
                )
                db.execSQL(
                    "INSERT INTO watch_progress_new (profileId, channelId, positionMs, durationMs, updatedAt) " +
                        "SELECT '$DEFAULT_PROFILE_ID', channelId, positionMs, durationMs, updatedAt FROM watch_progress",
                )
                db.execSQL("DROP TABLE watch_progress")
                db.execSQL("ALTER TABLE watch_progress_new RENAME TO watch_progress")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_progress_profileId ON watch_progress(profileId)")

                db.execSQL(
                    "CREATE TABLE watched_episodes_new (" +
                        "profileId TEXT NOT NULL, " +
                        "episodeId TEXT NOT NULL, " +
                        "seriesChannelId TEXT NOT NULL, " +
                        "seriesName TEXT NOT NULL, " +
                        "seasonNumber INTEGER NOT NULL, " +
                        "episodeNumber INTEGER NOT NULL, " +
                        "episodeTitle TEXT NOT NULL, " +
                        "streamUrl TEXT NOT NULL, " +
                        "coverUrl TEXT, " +
                        "durationSecs INTEGER NOT NULL, " +
                        "firstWatchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(profileId, episodeId))",
                )
                db.execSQL(
                    "INSERT INTO watched_episodes_new (profileId, episodeId, seriesChannelId, seriesName, " +
                        "seasonNumber, episodeNumber, episodeTitle, streamUrl, coverUrl, durationSecs, firstWatchedAt) " +
                        "SELECT '$DEFAULT_PROFILE_ID', episodeId, seriesChannelId, seriesName, " +
                        "seasonNumber, episodeNumber, episodeTitle, streamUrl, coverUrl, durationSecs, firstWatchedAt " +
                        "FROM watched_episodes",
                )
                db.execSQL("DROP TABLE watched_episodes")
                db.execSQL("ALTER TABLE watched_episodes_new RENAME TO watched_episodes")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_watched_episodes_profileId ON watched_episodes(profileId)",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tmdb_popular_cache (" +
                        "cacheKey TEXT NOT NULL, " +
                        "payloadJson TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(cacheKey))",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series_info_cache (" +
                        "seriesId TEXT NOT NULL, " +
                        "payloadJson TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(seriesId))",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS programmes (" +
                        "channelKey TEXT NOT NULL, " +
                        "startMs INTEGER NOT NULL, " +
                        "stopMs INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "description TEXT, " +
                        "PRIMARY KEY(channelKey, startMs))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_programmes_channelKey ON programmes(channelKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_programmes_startMs_stopMs ON programmes(startMs, stopMs)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watched_episodes (" +
                        "episodeId TEXT NOT NULL, " +
                        "seriesChannelId TEXT NOT NULL, " +
                        "seriesName TEXT NOT NULL, " +
                        "seasonNumber INTEGER NOT NULL, " +
                        "episodeNumber INTEGER NOT NULL, " +
                        "episodeTitle TEXT NOT NULL, " +
                        "streamUrl TEXT NOT NULL, " +
                        "coverUrl TEXT, " +
                        "durationSecs INTEGER NOT NULL, " +
                        "firstWatchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(episodeId))",
                )
            }
        }

        fun get(context: Context): IptvDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IptvDatabase::class.java,
                    "iptv.db",
                )
                    .addMigrations(
                        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9,
                    )
                    .addCallback(object : RoomDatabase.Callback() {
                        // Fresh installs skip migrations entirely — Room just builds tables
                        // from the entity definitions. Seed the default profile here so the
                        // app never sees a zero-profile state.
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val now = System.currentTimeMillis()
                            db.execSQL(
                                "INSERT OR IGNORE INTO profiles (id, name, colorArgb, sortIndex, createdAt) " +
                                    "VALUES ('$DEFAULT_PROFILE_ID', '$DEFAULT_PROFILE_NAME', " +
                                    "$DEFAULT_PROFILE_COLOR_ARGB, 0, $now)",
                            )
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
