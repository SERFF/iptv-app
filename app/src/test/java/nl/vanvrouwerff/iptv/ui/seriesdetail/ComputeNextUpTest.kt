package nl.vanvrouwerff.iptv.ui.seriesdetail

import nl.vanvrouwerff.iptv.data.db.WatchProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeNextUpTest {

    private fun ep(season: Int, number: Int) = Episode(
        id = "xt-episode:$season-$number",
        rawId = "$season-$number",
        seasonNumber = season,
        episodeNumber = number,
        title = "S${season}E$number",
        streamUrl = "http://x/$season/$number",
        coverUrl = null,
        plot = null,
        durationSecs = 0,
    )

    private val seasons = listOf(
        SeriesSeason(1, "Seizoen 1", listOf(ep(1, 1), ep(1, 2))),
        SeriesSeason(2, "Seizoen 2", listOf(ep(2, 1), ep(2, 2))),
    )

    private fun progress(id: String, pos: Long, dur: Long, at: Long) =
        WatchProgressEntity(profileId = "p", channelId = id, positionMs = pos, durationMs = dur, updatedAt = at)

    @Test
    fun noProgressStartsAtFirstEpisode() {
        val next = computeNextUp(seasons, emptyList())!!
        assertEquals("xt-episode:1-1", next.episode.id)
        assertFalse(next.isResume)
    }

    @Test
    fun resumesMostRecentUnfinishedEpisode() {
        val next = computeNextUp(
            seasons,
            listOf(
                progress("xt-episode:1-1", 100, 100, at = 1),
                progress("xt-episode:2-1", 40, 100, at = 5),
            ),
        )!!
        assertEquals("xt-episode:2-1", next.episode.id)
        assertEquals(2, next.season.number)
        assertEquals(40L, next.resumeMs)
        assertTrue(next.isResume)
    }

    @Test
    fun finishedEpisodeAdvancesAcrossSeasons() {
        val next = computeNextUp(seasons, listOf(progress("xt-episode:1-2", 99, 100, at = 3)))!!
        assertEquals("xt-episode:2-1", next.episode.id)
        assertFalse(next.isResume)
        assertEquals(0L, next.resumeMs)
    }
}
