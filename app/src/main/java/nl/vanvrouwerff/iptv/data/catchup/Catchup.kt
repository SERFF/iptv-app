package nl.vanvrouwerff.iptv.data.catchup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.settings.SourceConfig
import nl.vanvrouwerff.iptv.data.xtream.XtreamUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.time.ZoneId

/** Catch-up (terugkijken) on Xtream sources: which programmes can be replayed and from where. */
object Catchup {

    const val ID_PREFIX = "catchup:"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile private var cachedZone: ZoneId? = null

    /** True when [channel] keeps an archive that still covers a programme starting at [startMs]. */
    fun isAvailable(channel: Channel, startMs: Long, stopMs: Long, now: Long): Boolean =
        channel.type == ContentType.TV &&
            channel.archiveDays > 0 &&
            channel.id.startsWith(LIVE_PREFIX) &&
            startMs < now &&
            startMs >= now - channel.archiveDays * DAY_MS &&
            stopMs > startMs

    fun isCatchupId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /**
     * Builds a playable item for the programme [startMs]..[stopMs] on [channel], or null when
     * the source is not Xtream. A programme that is still running is replayed up to now.
     */
    suspend fun item(channel: Channel, title: String, startMs: Long, stopMs: Long): Channel? {
        val source = IptvApp.get().settings.sourceConfig.first() as? SourceConfig.Xtream ?: return null
        val streamId = channel.id.removePrefix(LIVE_PREFIX).ifBlank { return null }
        val end = minOf(stopMs, System.currentTimeMillis())
        val minutes = ((end - startMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        val url = XtreamUrls.timeshift(
            host = source.host,
            username = source.username,
            password = source.password,
            streamId = streamId,
            startMs = startMs,
            durationMinutes = minutes,
            serverZone = serverZone(source),
        )
        return Channel(
            id = "$ID_PREFIX${channel.id}:$startMs",
            name = "${channel.name} · $title",
            logoUrl = channel.logoUrl,
            groupTitle = channel.groupTitle,
            streamUrl = url,
            epgChannelId = null,
            type = ContentType.MOVIE,
        )
    }

    private suspend fun serverZone(source: SourceConfig.Xtream): ZoneId {
        cachedZone?.let { return it }
        val zone = withContext(Dispatchers.IO) {
            runCatching {
                val url = "${source.host.trimEnd('/')}/player_api.php".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("username", source.username)
                    .addQueryParameter("password", source.password)
                    .build()
                HttpClient.okHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val root = HttpClient.json.parseToJsonElement(r.body!!.string()).jsonObject
                    val id = (root["server_info"] as? JsonObject)?.get("timezone")?.jsonPrimitive?.contentOrNull
                    id?.let(ZoneId::of)
                }
            }.getOrNull()
        } ?: ZoneId.systemDefault()
        cachedZone = zone
        return zone
    }

    private const val LIVE_PREFIX = "xt-live:"
}
