package nl.vanvrouwerff.iptv.data.m3u

import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType

/**
 * Minimal M3U/M3U8 extended-playlist parser.
 *
 * Supports the common shape:
 *   #EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="...",Channel Name
 *   http://host/path
 *
 * Attributes are best-effort: unknown keys are ignored, missing keys become null.
 */
object M3uParser {

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    fun parse(content: String): List<Channel> {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .iterator()

        val channels = mutableListOf<Channel>()
        var pending: PendingEntry? = null
        var index = 0

        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.startsWith("#EXTM3U") -> continue
                line.startsWith("#EXTINF") -> pending = parseExtInf(line)
                line.startsWith("#") -> continue
                else -> {
                    val entry = pending ?: continue
                    channels += entry.toChannel(index++, line)
                    pending = null
                }
            }
        }
        return channels
    }

    private fun parseExtInf(line: String): PendingEntry {
        val commaIdx = line.indexOf(',')
        val header = if (commaIdx >= 0) line.substring(0, commaIdx) else line
        val name = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
        val attrs = attrRegex.findAll(header).associate { it.groupValues[1] to it.groupValues[2] }
        return PendingEntry(
            name = name,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() },
        )
    }

    private data class PendingEntry(
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val group: String?,
    ) {
        fun toChannel(index: Int, url: String): Channel {
            val stableId = tvgId ?: "${name.lowercase()}#$index"
            return Channel(
                id = stableId,
                name = name.ifBlank { "Kanaal $index" },
                logoUrl = logo,
                groupTitle = group,
                streamUrl = url,
                epgChannelId = tvgId,
                type = ContentType.TV,
            )
        }
    }
}
