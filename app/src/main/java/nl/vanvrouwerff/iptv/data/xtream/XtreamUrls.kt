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
}
