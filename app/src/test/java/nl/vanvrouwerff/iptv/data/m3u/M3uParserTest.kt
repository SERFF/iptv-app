package nl.vanvrouwerff.iptv.data.m3u

import nl.vanvrouwerff.iptv.data.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses single entry with all attributes`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="npo1.nl" tvg-logo="http://logo/npo1.png" group-title="Nederland",NPO 1
            http://stream/npo1.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(1, channels.size)
        val c = channels[0]
        assertEquals("npo1.nl", c.id)
        assertEquals("NPO 1", c.name)
        assertEquals("http://logo/npo1.png", c.logoUrl)
        assertEquals("Nederland", c.groupTitle)
        assertEquals("http://stream/npo1.ts", c.streamUrl)
        assertEquals("npo1.nl", c.epgChannelId)
        assertEquals(ContentType.TV, c.type)
    }

    @Test
    fun `parses entry without attributes`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,Plain Channel
            http://stream/plain.ts
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(1, channels.size)
        assertEquals("Plain Channel", channels[0].name)
        assertNull(channels[0].logoUrl)
        assertNull(channels[0].groupTitle)
        assertNull(channels[0].epgChannelId)
    }

    @Test
    fun `parses multiple entries and skips comments`() {
        val content = """
            #EXTM3U
            # a comment
            #EXTINF:-1 tvg-id="a" group-title="News",A
            http://a/1
            #EXTINF:-1 tvg-id="b" group-title="Sport",B
            http://b/1
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(2, channels.size)
        assertEquals(listOf("A", "B"), channels.map { it.name })
        assertEquals(listOf("News", "Sport"), channels.map { it.groupTitle })
    }

    @Test
    fun `ignores EXTINF without following URL`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,Orphan
            #EXTINF:-1,Valid
            http://valid/1
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(1, channels.size)
        assertEquals("Valid", channels[0].name)
    }

    @Test
    fun `generates stable id from name when tvg-id missing`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,One
            http://one/1
            #EXTINF:-1,One
            http://one/2
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(2, channels.size)
        // Ids must be unique per position; the #index suffix guarantees that.
        assertEquals(channels.map { it.id }.toSet().size, 2)
    }

    @Test
    fun `handles empty or EXTM3U-only input`() {
        assertEquals(0, M3uParser.parse("").size)
        assertEquals(0, M3uParser.parse("#EXTM3U\n").size)
    }

    @Test
    fun `comma inside a quoted attribute does not split the name`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news" group-title="News, Sports",Channel One
            http://x/1
        """.trimIndent()

        val ch = M3uParser.parse(content).single()

        assertEquals("Channel One", ch.name)
        assertEquals("News, Sports", ch.groupTitle)
    }

    @Test
    fun `duplicate tvg-id keeps both channels with unique ids`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="npo1",NPO 1 HD
            http://x/hd
            #EXTINF:-1 tvg-id="npo1",NPO 1 SD
            http://x/sd
        """.trimIndent()

        val channels = M3uParser.parse(content)

        assertEquals(listOf("npo1", "npo1#2"), channels.map { it.id })
        assertEquals(listOf("npo1", "npo1"), channels.map { it.epgChannelId })
    }

    @Test
    fun `id without tvg-id does not depend on position`() {
        val a = M3uParser.parse("#EXTINF:-1,Foo\nhttp://x/foo").single().id
        val b = M3uParser.parse("#EXTINF:-1,Bar\nhttp://x/bar\n#EXTINF:-1,Foo\nhttp://x/foo")[1].id

        assertEquals(a, b)
    }

    @Test
    fun `movie urls are typed as movies`() {
        val channels = M3uParser.parse(
            "#EXTINF:-1,Film\nhttp://x/movie/u/p/1.mp4\n#EXTINF:-1,Zender\nhttp://x/live/u/p/2.ts",
        )

        assertEquals(listOf(ContentType.MOVIE, ContentType.TV), channels.map { it.type })
    }

    @Test
    fun `EXTGRP supplies the group when group-title is missing`() {
        val ch = M3uParser.parse("#EXTINF:-1,Foo\n#EXTGRP:Kids\nhttp://x/foo").single()

        assertEquals("Kids", ch.groupTitle)
    }
}
