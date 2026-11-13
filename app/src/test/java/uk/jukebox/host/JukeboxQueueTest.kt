package uk.jukebox.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JukeboxQueueTest {
    private class FakePlayer : JukeboxQueue.Player {
        val played = mutableListOf<String>()
        var title: String? = null
        override fun play(query: String) { played += query }
        override fun nowPlaying() = title
    }

    private var now = 0L
    private val player = FakePlayer()
    private val box = JukeboxQueue(player, maxPerUser = 2, clock = { now })

    @Test fun firstRequestPlaysImmediatelyOthersQueue() {
        assertTrue(box.add("song a", "Ann").message.startsWith("Now playing"))
        assertTrue(box.add("song b", "Bob").message.contains("#1"))
        assertEquals(listOf("song a"), player.played)
    }

    @Test fun perUserLimitIgnoresCase() {
        box.add("a", "Ann"); box.add("b", "Ann"); box.add("c", "ann")
        assertFalse(box.add("d", "ANN").ok)
    }

    @Test fun emptyRequestRejected() {
        assertFalse(box.add("   ", "Ann").ok)
        assertTrue(player.played.isEmpty())
    }

    @Test fun autoAdvancesWhenRequestedTrackEnds() {
        val events = mutableListOf<String>()
        box.onEvent = { events += it }
        player.title = "Old song"
        box.add("song a", "Ann"); box.add("song b", "Bob")
        now = 2_000; box.tick()                         // Amazon still showing old song
        player.title = "Song A"; now = 10_000; box.tick() // learns requested track
        now = 100_000; box.tick()                         // still playing: nothing happens
        assertEquals(listOf("song a"), player.played)
        player.title = "Autoplay thing"; now = 200_000; box.tick()
        assertEquals(listOf("song a", "song b"), player.played)
        assertTrue(events.last().contains("song b"))
    }

    @Test fun idleAfterQueueRunsOutThenNextAddPlaysAtOnce() {
        player.title = "Old"
        box.add("song a", "Ann")
        player.title = "Song A"; now = 10_000; box.tick()
        player.title = "Autoplay"; now = 20_000; box.tick()
        assertTrue(box.add("song c", "Cat").message.startsWith("Now playing"))
    }

    @Test fun skipRemoveAndSnapshot() {
        box.add("a", "Ann")
        assertFalse(box.skip().ok)
        box.add("b", "Bob"); box.add("c", "Cat")
        assertTrue(box.remove(1).ok)
        assertEquals(listOf("b"), box.snapshot(null).upNext.map { it.query })
        assertTrue(box.skip().ok)
        assertEquals("Bob", box.snapshot(null).requestedBy)
    }

    @Test fun formParsing() {
        val m = TinyHttpServer.parseForm("q=Mr+Brightside&name=J%C3%B8rn&x=")
        assertEquals("Mr Brightside", m["q"]); assertEquals("Jørn", m["name"]); assertEquals("", m["x"])
    }
}
