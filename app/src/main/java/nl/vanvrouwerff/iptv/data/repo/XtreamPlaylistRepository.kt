package nl.vanvrouwerff.iptv.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.data.epg.XmltvParser
import nl.vanvrouwerff.iptv.data.remote.HttpClient
import nl.vanvrouwerff.iptv.data.xtream.XtreamApi
import nl.vanvrouwerff.iptv.data.xtream.XtreamCategory
import nl.vanvrouwerff.iptv.data.xtream.XtreamLiveStream
import nl.vanvrouwerff.iptv.data.xtream.XtreamSeries
import nl.vanvrouwerff.iptv.data.xtream.XtreamVodStream
import okhttp3.ResponseBody

class XtreamPlaylistRepository(
    private val host: String,
    private val username: String,
    private val password: String,
) : PlaylistRepository {

    private val api: XtreamApi = HttpClient.retrofitFor(host).create(XtreamApi::class.java)
    private val json: Json get() = HttpClient.json

    /**
     * Country-based catalogue filter. Providers tag every category with a `┃XX┃` country
     * prefix ("┃NL┃ NEDERLAND HD", "┃UK┃ SKY SPORTS", "┃EN┃ COMEDY", …). Without filtering
     * the full catalogue is ~200k rows for a typical reseller, which takes 3–4 min to
     * persist on a Formuler Z10 Pro MAX. Restricting to NL/UK/US/USA/EN drops it to
     * ~75k rows — a 2.5× speedup end-to-end — and cuts memory pressure during bulk insert.
     *
     * `EN` is included because most English-language movie/series categories (comedy,
     * drama, HBO, Netflix, …) live under that prefix rather than under ┃US┃/┃UK┃.
     *
     * Matched against `category_name`, not `group-title`, so the same logic works for both
     * parsed DTO shapes. Categories with no recognized prefix drop out — providers put
     * those behind prefixes universally, so a missing prefix generally means "not for us".
     */
    private val allowedCategoryPrefixes = listOf("┃NL┃", "┃UK┃", "┃US┃", "┃USA┃", "┃EN┃")

    private fun isAllowedCategory(categoryName: String?): Boolean {
        if (categoryName == null) return false
        return allowedCategoryPrefixes.any { categoryName.startsWith(it) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetch(etag: String?, lastModified: String?): PlaylistSnapshot = withContext(Dispatchers.IO) {
        // Sequential on purpose. Providers can return 30-60 MB per response for VOD/series;
        // parallelising means those bodies sit in memory at the same time and blow the heap.
        // Live is the backbone; VOD and series are best-effort.
        // All of it runs on IO so streaming reads (which touch the socket) don't hit the main thread.
        val liveCats = api.getLiveCategories(username, password)
        val liveStreams = api.getLiveStreams(username, password)
        val live = mapLive(liveCats, liveStreams)
        Log.i(TAG, "Live: ${live.size} channels kept, ${liveCats.size} categories")

        val vod: List<Channel> = runCatching {
            val cats = api.getVodCategories(username, password)
            val streams = api.getVodStreamsStream(username, password)
                .useStream { ListSerializer(XtreamVodStream.serializer()).decodeFrom(it) }
            val mapped = mapVod(cats, streams)
            Log.i(TAG, "VOD: ${mapped.size} movies kept (of ${streams.size}), ${cats.size} categories")
            mapped
        }.onFailure { Log.e(TAG, "VOD fetch/decode failed", it) }
            .getOrElse { emptyList() }

        val series: List<Channel> = runCatching {
            val cats = api.getSeriesCategories(username, password)
            val list = api.getSeriesStream(username, password)
                .useStream { ListSerializer(XtreamSeries.serializer()).decodeFrom(it) }
            val mapped = mapSeries(cats, list)
            Log.i(TAG, "Series: ${mapped.size} shows kept (of ${list.size}), ${cats.size} categories")
            mapped
        }.onFailure { Log.e(TAG, "Series fetch/decode failed", it) }
            .getOrElse { emptyList() }

        val keptChannels = live + vod + series

        // EPG is best-effort: if the provider has no xmltv endpoint, a decompression
        // hiccup occurs, or parsing fails, we still want live/vod/series to land.
        // Only keep programmes for channels we actually kept — dropping ~80% of channels
        // would otherwise still leave their EPG entries taking ~150MB of DB space for no
        // reason (they'd never be rendered).
        val keptEpgIds = live.mapNotNullTo(HashSet()) { it.epgChannelId }
        val programmes: List<ProgrammeEntity> = runCatching {
            api.getXmltv(username, password).useStream { stream ->
                XmltvParser.parse(stream)
            }
        }
            .onSuccess { Log.i(TAG, "EPG: ${it.size} programmes parsed") }
            .onFailure { Log.e(TAG, "EPG fetch/parse failed", it) }
            .getOrElse { emptyList() }
            .filter { it.channelKey in keptEpgIds }
            .also { Log.i(TAG, "EPG: ${it.size} programmes kept for subscribed channels") }

        PlaylistSnapshot(channels = keptChannels, programmes = programmes)
    }

    private companion object {
        const val TAG = "XtreamRepo"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> kotlinx.serialization.DeserializationStrategy<T>.decodeFrom(stream: java.io.InputStream): T =
        json.decodeFromStream(this, stream)

    private inline fun <T> ResponseBody.useStream(block: (java.io.InputStream) -> T): T =
        use { body -> body.byteStream().use(block) }

    private fun mapLive(
        categories: List<XtreamCategory>,
        streams: List<XtreamLiveStream>,
    ): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        return streams.mapNotNull { s ->
            val groupTitle = s.categoryId?.asScalarString()?.let(names::get)
            if (!isAllowedCategory(groupTitle)) return@mapNotNull null
            val streamId = s.streamId.asScalarString()
            Channel(
                id = "xt-live:$streamId",
                name = s.name,
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                groupTitle = groupTitle,
                streamUrl = "$host/live/$username/$password/$streamId.ts",
                epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                type = ContentType.TV,
            )
        }
    }

    private fun mapVod(
        categories: List<XtreamCategory>,
        streams: List<XtreamVodStream>,
    ): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        return streams.mapNotNull { s ->
            val groupTitle = s.categoryId?.asScalarString()?.let(names::get)
            if (!isAllowedCategory(groupTitle)) return@mapNotNull null
            val streamId = s.streamId.asScalarString()
            val ext = s.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
            Channel(
                id = "xt-vod:$streamId",
                name = s.name,
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                groupTitle = groupTitle,
                streamUrl = "$host/movie/$username/$password/$streamId.$ext",
                epgChannelId = null,
                type = ContentType.MOVIE,
            )
        }
    }

    private fun mapSeries(
        categories: List<XtreamCategory>,
        series: List<XtreamSeries>,
    ): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        return series.mapNotNull { s ->
            val groupTitle = s.categoryId?.asScalarString()?.let(names::get)
            if (!isAllowedCategory(groupTitle)) return@mapNotNull null
            val seriesId = s.seriesId.asScalarString()
            Channel(
                id = "xt-series:$seriesId",
                name = s.name,
                logoUrl = s.cover?.takeIf { it.isNotBlank() },
                groupTitle = groupTitle,
                // Episodes require a separate get_series_info call — deferred to stap 3.
                streamUrl = null,
                epgChannelId = null,
                type = ContentType.SERIES,
            )
        }
    }

    private fun JsonElement.asScalarString(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toString().trim('"')
}
