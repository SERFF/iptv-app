package nl.vanvrouwerff.iptv.data.settings

sealed interface SourceConfig {
    data class M3u(val url: String) : SourceConfig
    data class Xtream(val host: String, val username: String, val password: String) : SourceConfig
}
