package uk.jukebox.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.jukebox.host.JukeboxQueue.Song

class JukeboxQueueTest {
    private class FakePlayer : JukeboxQueue.Player {
        val played = mutableListOf<String>()
        var title: String? = null
        override fun play(query: String) { played += query }
        override fun nowPlaying() = title
    }

    private var now = 0L
    private val player = FakePlayer()
    private val box = JukeboxQueue(player, maxPerUser = 2, clock = { now++ })

    private fun song(t: String, id: Long? = null) = Song(t, "Artist", "https://is1-ssl.mzstatic.com/a.jpg", id)

    @Test fun firstRequestPlaysImmediatelyWithArtistInSearch() {
        assertTrue(box.add(song("Song A"), "Ann", "a").message.startsWith("Now playing"))
        assertTrue(box.add(song("Song B"), "Bob", "b").message.contains("#1"))
        assertEquals(listOf("Song A Artist"), player.played)
    }

    @Test fun perClientLimit() {
        box.add(song("1"), "Ann", "a"); box.add(song("2"), "Ann", "a"); box.add(song("3"), "Ann", "a")
        assertFalse(box.add(song("4"), "Ann", "a").ok)
        assertTrue(box.add(song("4"), "Bob", "b").ok)
    }

    @Test fun duplicateBecomesAVote() {
        box.add(song("Playing", 1), "Ann", "a")
        box.add(song("Wanted", 2), "Ann", "a")
        assertFalse(box.add(song("Playing", 1), "Bob", "b").ok)
        assertTrue(box.add(song("Wanted", 2), "Bob", "b").message.contains("vote"))
        assertEquals(1, box.snapshot("b", null, 3).upNext.single().votes)
    }

    @Test fun votesReorderQueueAndToggle() {
        box.add(song("Playing"), "Ann", "a")
        box.add(song("Early"), "Ann", "a")
        box.add(song("Late"), "Bob", "b")
        val late = box.snapshot("c", null, 3).upNext.first { it.song.title == "Late" }
        assertFalse(box.vote(late.id, "b").ok)          // can't vote own
        assertTrue(box.vote(late.id, "c").ok)
        val snap = box.snapshot("c", null, 3)
        assertEquals(listOf("Late", "Early"), snap.upNext.map { it.song.title })
        assertTrue(snap.upNext.first().myVote)
        box.vote(late.id, "c")                            // toggle off
        assertEquals("Early", box.snapshot("c", null, 3).upNext.first().song.title)
    }

    @Test fun highestVotedPlaysNext() {
        box.add(song("Playing"), "Ann", "a")
        box.add(song("Early"), "Ann", "a")
        box.add(song("Late"), "Bob", "b")
        val late = box.snapshot("c", null, 3).upNext.first { it.song.title == "Late" }
        box.vote(late.id, "c")
        box.skip()
        assertEquals("Late Artist", player.played.last())
    }

    @Test fun voteToSkipNeedsHalfTheRoom() {
        assertEquals(1, JukeboxQueue.skipThreshold(1))
        assertEquals(2, JukeboxQueue.skipThreshold(3))
        assertEquals(3, JukeboxQueue.skipThreshold(6))
        box.add(song("Bad"), "Ann", "a"); box.add(song("Good"), "Bob", "b")
        assertTrue(box.voteSkip("c", 6).message.contains("1/3"))
        box.voteSkip("c", 6)                               // same person twice counts once
        box.voteSkip("d", 6)
        assertEquals(listOf("Bad Artist"), player.played)
        assertTrue(box.voteSkip("e", 6).message.contains("skipped"))
        assertEquals("Good Artist", player.played.last())
        assertEquals(0, box.snapshot("c", null, 6).skipVotes)
    }

    @Test fun skipWithEmptyQueueAsksAmazonToSkip() {
        box.add(song("Only"), "Ann", "a")
        val r = box.voteSkip("a", 5)                       // requester can skip their own
        assertTrue(r.needsAmazonSkip)
        assertFalse(box.snapshot("x", null, 5).canVoteSkip)
    }

    @Test fun autoAdvancesWhenRequestedTrackEnds() {
        var t = 0L
        val b = JukeboxQueue(player, clock = { t })
        val events = mutableListOf<String>()
        b.onEvent = { events += it }
        player.title = "Old song"
        b.add(song("A"), "Ann", "a"); b.add(song("B"), "Bob", "b")
        t = 2_000; b.tick()
        player.title = "A"; t = 10_000; b.tick()
        t = 100_000; b.tick()
        assertEquals(1, player.played.size)
        player.title = "Autoplay"; t = 200_000; b.tick()
        assertEquals("B Artist", player.played.last())
        assertTrue(events.last().contains("B"))
    }

    @Test fun freeTextStillWorks() {
        box.add(Song("  mr   brightside "), "", "z")
        assertEquals("mr brightside", player.played.single())
        assertEquals("Someone", box.snapshot("z", null, 1).requestedBy)
    }

    @Test fun formParsing() {
        val m = TinyHttpServer.parseForm("q=Mr+Brightside&name=J%C3%B8rn&x=")
        assertEquals("Mr Brightside", m["q"]); assertEquals("Jørn", m["name"]); assertEquals("", m["x"])
    }
}
