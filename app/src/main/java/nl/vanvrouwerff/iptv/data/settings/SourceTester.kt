package nl.vanvrouwerff.iptv.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.m3u.M3uParser
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Outcome of "Verbinding testen" in Instellingen. */
sealed interface SourceTestResult {
    data class Ok(val live: Int, val movies: Int, val series: Int) : SourceTestResult
    data class Failed(val reason: Reason, val detail: String? = null) : SourceTestResult

    enum class Reason { BadLogin, HostUnreachable, Timeout, HttpError, NotAPlaylist, Other }
}

/**
 * Checks a source before it is saved: can we log in / download it, and how much does it
 * hold. Counting streams by scanning the response instead of decoding it keeps memory flat
 * even for providers with tens of thousands of titles.
 */
object SourceTester {

    suspend fun test(source: SourceConfig): SourceTestResult = withContext(Dispatchers.IO) {
        try {
            when (source) {
                is SourceConfig.Xtream -> testXtream(source)
                is SourceConfig.M3u -> testM3u(source)
            }
        } catch (e: UnknownHostException) {
            SourceTestResult.Failed(SourceTestResult.Reason.HostUnreachable, e.message)
        } catch (e: ConnectException) {
            SourceTestResult.Failed(SourceTestResult.Reason.HostUnreachable, e.message)
        } catch (e: SocketTimeoutException) {
            SourceTestResult.Failed(SourceTestResult.Reason.Timeout, e.message)
        } catch (e: IOException) {
            SourceTestResult.Failed(SourceTestResult.Reason.Other, e.message)
        } catch (e: IllegalArgumentException) {
            SourceTestResult.Failed(SourceTestResult.Reason.Other, e.message)
        }
    }

    private fun apiUrl(source: SourceConfig.Xtream, action: String?): String {
        val base = "${source.host.trimEnd('/')}/player_api.php".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Ongeldige host")
        return base.newBuilder()
            .addQueryParameter("username", source.username)
            .addQueryParameter("password", source.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .build()
            .toString()
    }

    private fun testXtream(source: SourceConfig.Xtream): SourceTestResult {
        val client = HttpClient.okHttp
        client.newCall(Request.Builder().url(apiUrl(source, null)).build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                return SourceTestResult.Failed(SourceTestResult.Reason.BadLogin)
            }
            if (!response.isSuccessful) {
                return SourceTestResult.Failed(SourceTestResult.Reason.HttpError, "HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val auth = runCatching {
                val root = HttpClient.json.parseToJsonElement(body).jsonObject
                (root["user_info"] as? JsonObject)?.get("auth")?.jsonPrimitive?.content
            }.getOrNull()
            if (auth != "1") return SourceTestResult.Failed(SourceTestResult.Reason.BadLogin)
        }
        fun count(action: String, marker: String): Int =
            client.newCall(Request.Builder().url(apiUrl(source, action)).build()).execute().use { r ->
                if (!r.isSuccessful) 0 else r.body?.byteStream()?.let { countOccurrences(it, marker) } ?: 0
            }
        return SourceTestResult.Ok(
            live = count("get_live_streams", "\"stream_id\""),
            movies = count("get_vod_streams", "\"stream_id\""),
            series = count("get_series", "\"series_id\""),
        )
    }

    private fun testM3u(source: SourceConfig.M3u): SourceTestResult {
        HttpClient.okHttp.newCall(Request.Builder().url(source.url).build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                return SourceTestResult.Failed(SourceTestResult.Reason.BadLogin)
            }
            if (!response.isSuccessful) {
                return SourceTestResult.Failed(SourceTestResult.Reason.HttpError, "HTTP ${response.code}")
            }
            val channels = response.body?.charStream()?.buffered()?.useLines { M3uParser.parse(it) }.orEmpty()
            if (channels.isEmpty()) return SourceTestResult.Failed(SourceTestResult.Reason.NotAPlaylist)
            return SourceTestResult.Ok(
                live = channels.count { it.type == ContentType.TV },
                movies = channels.count { it.type == ContentType.MOVIE },
                series = channels.count { it.type == ContentType.SERIES },
            )
        }
    }

    /** Streams [input] and counts non-overlapping occurrences of an ASCII [marker]. */
    internal fun countOccurrences(input: InputStream, marker: String): Int {
        val needle = marker.toByteArray(Charsets.US_ASCII)
        val buffer = ByteArray(64 * 1024)
        var matched = 0
        var count = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            for (i in 0 until read) {
                val b = buffer[i]
                if (b == needle[matched]) {
                    matched++
                    if (matched == needle.size) {
                        count++
                        matched = 0
                    }
                } else {
                    matched = if (b == needle[0]) 1 else 0
                }
            }
        }
        return count
    }
}
