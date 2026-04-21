package nl.vanvrouwerff.iptv.data.epg

import android.util.Xml
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.TimeZone

/**
 * Minimal streaming XMLTV parser. Reads `<programme>` elements one at a time so we never
 * hold the whole document in memory — EPG feeds from big Xtream providers are routinely
 * 30-50 MB.
 *
 * XMLTV timestamps look like "20260420183000 +0200" or plain "20260420183000" (UTC). Only
 * the subset we actually need (title, desc, start/stop, channel) is parsed; everything
 * else is ignored.
 */
object XmltvParser {

    fun parse(input: InputStream): List<ProgrammeEntity> {
        val programmes = mutableListOf<ProgrammeEntity>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                readProgramme(parser)?.let(programmes::add)
            } else {
                // Fast-skip: don't descend into <channel> etc, we only need <programme>.
                event = parser.next()
                continue
            }
            event = parser.next()
        }
        return programmes
    }

    private fun readProgramme(parser: XmlPullParser): ProgrammeEntity? {
        val startAttr = parser.getAttributeValue(null, "start") ?: return null
        val stopAttr = parser.getAttributeValue(null, "stop")
        val channel = parser.getAttributeValue(null, "channel")?.takeIf { it.isNotBlank() }
            ?: return null

        val startMs = parseXmltvTime(startAttr) ?: return null
        val stopMs = stopAttr?.let(::parseXmltvTime) ?: (startMs + DEFAULT_DURATION_MS)

        var title: String? = null
        var description: String? = null

        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> title = readText(parser).also { depth-- }
                        "desc" -> description = readText(parser).also { depth-- }
                        else -> skipElement(parser).also { depth-- }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        val safeTitle = title?.takeIf { it.isNotBlank() } ?: return null
        return ProgrammeEntity(
            channelKey = channel,
            startMs = startMs,
            stopMs = stopMs,
            title = safeTitle,
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        while (true) {
            val ev = parser.next()
            when (ev) {
                XmlPullParser.TEXT -> sb.append(parser.text ?: "")
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
    }

    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Accepts "YYYYMMDDhhmmss" (UTC) or "YYYYMMDDhhmmss +0200" (with tz offset). Returns
     * epoch millis, or null if unparseable.
     */
    private fun parseXmltvTime(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.length < 14) return null
        val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
        val month = trimmed.substring(4, 6).toIntOrNull() ?: return null
        val day = trimmed.substring(6, 8).toIntOrNull() ?: return null
        val hour = trimmed.substring(8, 10).toIntOrNull() ?: return null
        val minute = trimmed.substring(10, 12).toIntOrNull() ?: return null
        val second = trimmed.substring(12, 14).toIntOrNull() ?: return null

        val offsetMin = if (trimmed.length >= 19) {
            // Format: " +0200" or " -0130"
            val sign = when (trimmed[trimmed.length - 5]) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
            val h = trimmed.substring(trimmed.length - 4, trimmed.length - 2).toIntOrNull() ?: return null
            val m = trimmed.substring(trimmed.length - 2).toIntOrNull() ?: return null
            sign * (h * 60 + m)
        } else {
            0
        }

        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis - offsetMin * 60_000L
    }

    private const val DEFAULT_DURATION_MS = 30L * 60_000L
}
