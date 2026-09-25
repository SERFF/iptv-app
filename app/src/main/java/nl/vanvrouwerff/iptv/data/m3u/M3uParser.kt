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
 *
 * IDs are derived from content, not position, so inserting a channel upstream doesn't
 * shift every other channel's ID (which would orphan favourites and watch progress):
 *  - `tvg-id` when present; a repeated tvg-id (HD/SD variants) gets a `#2`, `#3` suffix
 *    so both rows survive the primary-key constraint.
 *  - otherwise `name#<hash of the stream URL>`.
 */
object M3uParser {

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")

    fun parse(content: String): List<Channel> = parse(content.lineSequence())

    fun parse(lines: Sequence<String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        val usedIds = HashMap<String, Int>()
        var pending: PendingEntry? = null
        var groupFromExtGrp: String? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> continue
                line.startsWith("#EXTINF") -> {
                    pending = parseExtInf(line)
                    groupFromExtGrp = null
                }
                line.startsWith("#EXTGRP:") -> groupFromExtGrp = line.substringAfter(':').trim().ifBlank { null }
                line.startsWith("#") -> continue
                else -> {
                    val entry = pending ?: continue
                    val withGroup = if (entry.group == null && groupFromExtGrp != null) {
                        entry.copy(group = groupFromExtGrp)
                    } else entry
                    channels += withGroup.toChannel(line, usedIds)
                    pending = null
                    groupFromExtGrp = null
                }
            }
        }
        return channels
    }

    private fun parseExtInf(line: String): PendingEntry {
        val commaIdx = indexOfNameSeparator(line)
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

    /** First comma outside a quoted attribute value — `group-title="News, Sports"` is legal. */
    private fun indexOfNameSeparator(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return i
            }
        }
        return -1
    }

    private fun typeFor(url: String): ContentType =
        if (url.contains("/movie/")) ContentType.MOVIE else ContentType.TV

    private data class PendingEntry(
        val name: String,
        val tvgId: String?,
        val logo: String?,
        val group: String?,
    ) {
        fun toChannel(url: String, usedIds: HashMap<String, Int>): Channel {
            val base = tvgId ?: "${name.lowercase()}#${Integer.toHexString(url.hashCode())}"
            val seen = usedIds.merge(base, 1, Int::plus) ?: 1
            val id = if (seen == 1) base else "$base#$seen"
            return Channel(
                id = id,
                name = name.ifBlank { "Kanaal ${usedIds.size}" },
                logoUrl = logo,
                groupTitle = group,
                streamUrl = url,
                epgChannelId = tvgId,
                type = typeFor(url),
            )
        }
    }
}
