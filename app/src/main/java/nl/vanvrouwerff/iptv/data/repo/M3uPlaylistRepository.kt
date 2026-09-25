package nl.vanvrouwerff.iptv.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.data.m3u.M3uParser
import okhttp3.OkHttpClient
import okhttp3.Request

class M3uPlaylistRepository(
    private val url: String,
    private val httpClient: OkHttpClient,
) : PlaylistRepository {

    override suspend fun fetch(
        etag: String?,
        lastModified: String?,
        onProgress: (ImportProgress) -> Unit,
    ): PlaylistSnapshot =
        withContext(Dispatchers.IO) {
            onProgress(ImportProgress(ImportProgress.Stage.Downloading, 0))
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (etag != null) header("If-None-Match", etag)
                    if (lastModified != null) header("If-Modified-Since", lastModified)
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 304) {
                    return@withContext PlaylistSnapshot(channels = emptyList(), notModified = true)
                }
                if (!response.isSuccessful) {
                    error("Playlist HTTP ${response.code}")
                }
                val body = response.body ?: error("Lege playlist")
                val channels = body.charStream().buffered().useLines { M3uParser.parse(it) }
                onProgress(ImportProgress(ImportProgress.Stage.Live, channels.size))
                PlaylistSnapshot(
                    channels = channels,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                )
            }
        }
}
