package nl.vanvrouwerff.iptv.data.xtream

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object XtreamUrls {

    /** `{host}/{kind}/{user}/{pass}/{file}` with each segment percent-encoded. */
    fun stream(host: String, kind: String, username: String, password: String, file: String): String {
        val base = host.trimEnd('/').toHttpUrlOrNull()
            ?: return "${host.trimEnd('/')}/$kind/$username/$password/$file"
        return base.newBuilder()
            .addPathSegment(kind)
            .addPathSegment(username)
            .addPathSegment(password)
            .addPathSegment(file)
            .build()
            .toString()
    }

    /**
     * Catch-up as a finished HLS playlist:
     * `{host}/timeshift/{user}/{pass}/{minutes}/{yyyy-MM-dd:HH-mm}/{streamId}.m3u8`, with the
     * start time written in the server's own time zone.
     */
    fun timeshift(
        host: String,
        username: String,
        password: String,
        streamId: String,
        startMs: Long,
        durationMinutes: Int,
        serverZone: java.time.ZoneId,
    ): String {
        val stamp = java.time.Instant.ofEpochMilli(startMs).atZone(serverZone).format(TIMESHIFT_FORMAT)
        val base = host.trimEnd('/').toHttpUrlOrNull()
            ?: return "${host.trimEnd('/')}/timeshift/$username/$password/$durationMinutes/$stamp/$streamId.m3u8"
        return base.newBuilder()
            .addPathSegment("timeshift")
            .addPathSegment(username)
            .addPathSegment(password)
            .addPathSegment(durationMinutes.toString())
            .addPathSegment(stamp)
            .addPathSegment("$streamId.m3u8")
            .build()
            .toString()
    }

    private val TIMESHIFT_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
}
