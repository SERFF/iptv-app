package nl.vanvrouwerff.iptv.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    val sourceConfig: Flow<SourceConfig?> = context.dataStore.data.map { prefs ->
        when (prefs[TYPE]) {
            TYPE_M3U -> prefs[M3U_URL]?.takeIf { it.isNotBlank() }?.let(SourceConfig::M3u)
            TYPE_XTREAM -> {
                val host = prefs[XT_HOST]?.trim().orEmpty()
                val user = prefs[XT_USER].orEmpty()
                val pass = prefs[XT_PASS].orEmpty()
                if (host.isNotBlank() && user.isNotBlank()) {
                    SourceConfig.Xtream(host, user, pass)
                } else null
            }
            else -> null
        }
    }

    val playlistEtag: Flow<String?> = context.dataStore.data.map { it[PLAYLIST_ETAG] }
    val playlistLastModified: Flow<String?> = context.dataStore.data.map { it[PLAYLIST_LAST_MODIFIED] }

    /**
     * Currently-active profile. Defaults to the built-in "default" profile seeded by
     * the database; anything the app reads before the user has picked a different profile
     * points at that one, so behaviour is identical to the pre-profile build.
     */
    val activeProfileId: Flow<String> =
        context.dataStore.data.map { it[ACTIVE_PROFILE_ID] ?: DEFAULT_PROFILE_ID }

    suspend fun setActiveProfile(id: String) {
        context.dataStore.edit { prefs -> prefs[ACTIVE_PROFILE_ID] = id }
    }

    /**
     * Per-profile last-watched channel id. Each profile gets its own key so switching
     * profiles instantly swaps the hero banner without a single read of the others'
     * history. Returns null for profiles that have never played anything.
     */
    fun lastWatchedChannelId(profileId: String): Flow<String?> =
        context.dataStore.data.map { it[lastWatchedKey(profileId)] }

    suspend fun setLastWatched(profileId: String, channelId: String) {
        context.dataStore.edit { prefs -> prefs[lastWatchedKey(profileId)] = channelId }
    }

    /**
     * Per-profile recent search queries, newest first, capped to RECENT_SEARCHES_MAX.
     * Stored as a single \n-separated string — DataStore Preferences has no list
     * primitive and Proto DataStore would be overkill for five strings per profile.
     */
    fun recentSearches(profileId: String): Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            prefs[recentSearchesKey(profileId)]
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    suspend fun pushRecentSearch(profileId: String, query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val key = recentSearchesKey(profileId)
        context.dataStore.edit { prefs ->
            val existing = prefs[key]
                ?.split('\n')
                ?.filter { it.isNotBlank() && !it.equals(q, ignoreCase = true) }
                ?: emptyList()
            prefs[key] = (listOf(q) + existing).take(RECENT_SEARCHES_MAX).joinToString("\n")
        }
    }

    suspend fun clearRecentSearches(profileId: String) {
        context.dataStore.edit { prefs -> prefs.remove(recentSearchesKey(profileId)) }
    }

    /** Drop every per-profile key when a profile is deleted — no orphan prefs. */
    suspend fun wipeProfilePrefs(profileId: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(lastWatchedKey(profileId))
            prefs.remove(recentSearchesKey(profileId))
        }
    }

    private fun lastWatchedKey(profileId: String) =
        stringPreferencesKey("last_watched_id::$profileId")

    private fun recentSearchesKey(profileId: String) =
        stringPreferencesKey("recent_searches::$profileId")

    /** Epoch millis of the last successful catalogue refresh. 0 = never. */
    val lastRefreshSuccessAt: Flow<Long> = context.dataStore.data.map { it[LAST_REFRESH_AT] ?: 0L }

    suspend fun markRefreshSuccess(nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs -> prefs[LAST_REFRESH_AT] = nowMs }
    }

    /**
     * Automatic nightly refresh. Disabled by default so we don't silently burn the user's
     * bandwidth if they never open Instellingen. Hour is 0..23 in local time; minutes are
     * always :00 — a single knob is enough and avoids a fiddly minute-picker on the remote.
     */
    val autoRefreshEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_REFRESH_ENABLED] ?: false }

    val autoRefreshHour: Flow<Int> =
        context.dataStore.data.map { (it[AUTO_REFRESH_HOUR] ?: DEFAULT_AUTO_REFRESH_HOUR).coerceIn(0, 23) }

    suspend fun setAutoRefreshEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AUTO_REFRESH_ENABLED] = enabled }
    }

    suspend fun setAutoRefreshHour(hour: Int) {
        context.dataStore.edit { prefs -> prefs[AUTO_REFRESH_HOUR] = hour.coerceIn(0, 23) }
    }

    suspend fun saveM3u(url: String) {
        context.dataStore.edit { prefs ->
            prefs[TYPE] = TYPE_M3U
            prefs[M3U_URL] = url.trim()
            prefs.remove(XT_HOST); prefs.remove(XT_USER); prefs.remove(XT_PASS)
            prefs.remove(PLAYLIST_ETAG); prefs.remove(PLAYLIST_LAST_MODIFIED)
        }
    }

    suspend fun saveXtream(host: String, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[TYPE] = TYPE_XTREAM
            prefs[XT_HOST] = host.trim().trimEnd('/')
            prefs[XT_USER] = username.trim()
            prefs[XT_PASS] = password
            prefs.remove(M3U_URL)
            prefs.remove(PLAYLIST_ETAG); prefs.remove(PLAYLIST_LAST_MODIFIED)
        }
    }

    suspend fun savePlaylistValidators(etag: String?, lastModified: String?) {
        context.dataStore.edit { prefs ->
            if (etag != null) prefs[PLAYLIST_ETAG] = etag else prefs.remove(PLAYLIST_ETAG)
            if (lastModified != null) prefs[PLAYLIST_LAST_MODIFIED] = lastModified
            else prefs.remove(PLAYLIST_LAST_MODIFIED)
        }
    }

    private companion object {
        val TYPE = stringPreferencesKey("source_type")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val XT_HOST = stringPreferencesKey("xt_host")
        val XT_USER = stringPreferencesKey("xt_user")
        val XT_PASS = stringPreferencesKey("xt_pass")
        val PLAYLIST_ETAG = stringPreferencesKey("playlist_etag")
        val PLAYLIST_LAST_MODIFIED = stringPreferencesKey("playlist_last_modified")
        val LAST_REFRESH_AT = longPreferencesKey("last_refresh_at")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val AUTO_REFRESH_ENABLED = booleanPreferencesKey("auto_refresh_enabled")
        val AUTO_REFRESH_HOUR = intPreferencesKey("auto_refresh_hour")
        const val TYPE_M3U = "m3u"
        const val TYPE_XTREAM = "xtream"
        const val RECENT_SEARCHES_MAX = 6
        const val DEFAULT_PROFILE_ID = "default"
        const val DEFAULT_AUTO_REFRESH_HOUR = 3
    }
}
