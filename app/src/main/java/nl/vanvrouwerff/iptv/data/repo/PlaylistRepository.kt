package nl.vanvrouwerff.iptv.data.repo

import nl.vanvrouwerff.iptv.data.Channel

data class PlaylistSnapshot(
    val channels: List<Channel>,
    /** EPG programmes, when the source provides them (Xtream's xmltv.php). Best-effort. */
    val programmes: List<nl.vanvrouwerff.iptv.data.db.ProgrammeEntity> = emptyList(),
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false,
)

interface PlaylistRepository {
    suspend fun fetch(etag: String?, lastModified: String?): PlaylistSnapshot
}
