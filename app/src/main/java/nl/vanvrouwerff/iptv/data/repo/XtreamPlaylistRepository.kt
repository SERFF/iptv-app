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
import nl.vanvrouwerff.iptv.data.xtream.CategoryFilter
import nl.vanvrouwerff.iptv.data.xtream.XtreamApi
import nl.vanvrouwerff.iptv.data.xtream.XtreamUrls
import nl.vanvrouwerff.iptv.data.xtream.XtreamCategory
import nl.vanvrouwerff.iptv.data.xtream.XtreamLiveStream
import nl.vanvrouwerff.iptv.data.xtream.XtreamSeries
import nl.vanvrouwerff.iptv.data.xtream.XtreamVodStream
import okhttp3.ResponseBody

class XtreamPlaylistRepository(
    private val host: String,
    private val username: String,
    private val password: String,
    private val categoryFilter: CategoryFilter = CategoryFilter(emptyList()),
) : PlaylistRepository {

    private val api: XtreamApi = HttpClient.retrofitFor(host).create(XtreamApi::class.java)
    private val json: Json get() = HttpClient.json

    private fun <T> applyFilter(items: List<T>, category: (T) -> String?, label: String): List<T> {
        if (!categoryFilter.isEnabled) return items
        val kept = items.filter { categoryFilter.accepts(category(it)) }
        // A provider that doesn't use the prefix convention would otherwise end up with an
        // empty catalogue; better to show everything than nothing.
        if (kept.isEmpty() && items.isNotEmpty()) {
            Log.w(TAG, "$label: category filter matched nothing, keeping all ${items.size}")
            return items
        }
        return kept
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
    ): PlaylistSnapshot = withContext(Dispatchers.IO) {
        onProgress(ImportProgress(ImportProgress.Stage.Downloading, 0))
        // Sequential on purpose. Providers can return 30-60 MB per response for VOD/series;
        // parallelising means those bodies sit in memory at the same time and blow the heap.
        // Live is the backbone; VOD and series are best-effort.
        // All of it runs on IO so streaming reads (which touch the socket) don't hit the main thread.
        val liveCats = api.getLiveCategories(username, password)
        val liveStreams = api.getLiveStreams(username, password)
        val live = mapLive(liveCats, liveStreams)
        Log.i(TAG, "Live: ${live.size} channels kept, ${liveCats.size} categories")
        onProgress(ImportProgress(ImportProgress.Stage.Live, live.size))

        val vod: List<Channel> = runCatching {
            val cats = api.getVodCategories(username, password)
            val streams = api.getVodStreamsStream(username, password)
                .useStream { ListSerializer(XtreamVodStream.serializer()).decodeFrom(it) }
            val mapped = mapVod(cats, streams)
            Log.i(TAG, "VOD: ${mapped.size} movies kept (of ${streams.size}), ${cats.size} categories")
            onProgress(ImportProgress(ImportProgress.Stage.Movies, mapped.size))
            mapped
        }.onFailure { Log.e(TAG, "VOD fetch/decode failed", it) }
            .getOrElse { emptyList() }

        val series: List<Channel> = runCatching {
            val cats = api.getSeriesCategories(username, password)
            val list = api.getSeriesStream(username, password)
                .useStream { ListSerializer(XtreamSeries.serializer()).decodeFrom(it) }
            val mapped = mapSeries(cats, list)
            Log.i(TAG, "Series: ${mapped.size} shows kept (of ${list.size}), ${cats.size} categories")
            onProgress(ImportProgress(ImportProgress.Stage.Series, mapped.size))
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
                XmltvParser.parse(stream) { key -> key in keptEpgIds }
            }
        }
            .onSuccess { Log.i(TAG, "EPG: ${it.size} programmes kept for subscribed channels") }
            .onFailure { Log.e(TAG, "EPG fetch/parse failed", it) }
            .getOrElse { emptyList() }

        PlaylistSnapshot(channels = keptChannels, programmes = programmes)
    }

    override suspend fun fetchProgrammes(epgKeys: Set<String>): List<ProgrammeEntity> =
        withContext(Dispatchers.IO) {
            api.getXmltv(username, password).useStream { stream ->
                XmltvParser.parse(stream) { key -> key in epgKeys }
            }
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
        val groupOf = { s: XtreamLiveStream -> s.categoryId?.asScalarString()?.let(names::get) }
        return applyFilter(streams, groupOf, "Live").map { s ->
            val groupTitle = groupOf(s)
            val streamId = s.streamId.asScalarString()
            Channel(
                id = "xt-live:$streamId",
                name = s.name,
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                groupTitle = groupTitle,
                streamUrl = XtreamUrls.stream(host, "live", username, password, "$streamId.ts"),
                epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                type = ContentType.TV,
                archiveDays = if (s.tvArchive?.asScalarString() == "1") {
                    s.tvArchiveDuration?.asScalarString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                } else {
                    0
                },
            )
        }
    }

    private fun mapVod(
        categories: List<XtreamCategory>,
        streams: List<XtreamVodStream>,
    ): List<Channel> {
        val names = categories.associate { it.categoryId.asScalarString() to it.categoryName }
        val groupOf = { s: XtreamVodStream -> s.categoryId?.asScalarString()?.let(names::get) }
        return applyFilter(streams, groupOf, "VOD").map { s ->
            val groupTitle = groupOf(s)
            val streamId = s.streamId.asScalarString()
            val ext = s.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
            Channel(
                id = "xt-vod:$streamId",
                name = s.name,
                logoUrl = s.streamIcon?.takeIf { it.isNotBlank() },
                groupTitle = groupTitle,
                streamUrl = XtreamUrls.stream(host, "movie", username, password, "$streamId.$ext"),
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
        val groupOf = { s: XtreamSeries -> s.categoryId?.asScalarString()?.let(names::get) }
        return applyFilter(series, groupOf, "Series").map { s ->
            val groupTitle = groupOf(s)
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
