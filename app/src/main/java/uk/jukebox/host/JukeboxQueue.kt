package uk.jukebox.host

/**
 * The shared song queue. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Amazon Music has no "add to queue" API, so we keep our own queue and start each
 * song when the previous requested one finishes. We spot "finished" by watching the
 * now-playing title: once Amazon moves on to a different track, the next request plays.
 */
class JukeboxQueue(
    private val player: Player,
    private val maxPerUser: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Player {
        fun play(query: String)
        fun nowPlaying(): String?
    }

    data class Request(val query: String, val by: String)
    data class Result(val ok: Boolean, val message: String)

    private data class Current(
        val request: Request,
        val startedAt: Long,
        val previousTitle: String?,
        var track: String? = null,
    )

    private val queue = ArrayDeque<Request>()
    private var current: Current? = null

    /** Called whenever something worth telling guests happens. */
    var onEvent: (String) -> Unit = {}

    @Synchronized
    fun add(rawQuery: String, rawName: String): Result {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ").take(100)
        val by = rawName.trim().replace(Regex("\\s+"), " ").take(20).ifEmpty { "Someone" }
        if (query.isEmpty()) return Result(false, "Type a song or artist first.")
        val mine = queue.count { it.by.equals(by, ignoreCase = true) }
        if (mine >= maxPerUser) {
            return Result(false, "You've already got $mine songs waiting – give others a go!")
        }
        queue.addLast(Request(query, by))
        if (current == null) return playNext()
        return Result(true, "Added! You're #${queue.size} in the queue.")
    }

    @Synchronized
    fun skip(): Result {
        if (queue.isEmpty()) return Result(false, "Nothing queued to skip to.")
        return playNext()
    }

    @Synchronized
    fun clear(): Result {
        val n = queue.size
        queue.clear()
        return Result(true, "Cleared $n queued song(s).")
    }

    @Synchronized
    fun remove(index: Int): Result {
        if (index !in queue.indices) return Result(false, "That song isn't in the queue.")
        val r = queue.removeAt(index)
        return Result(true, "Removed ${r.query}.")
    }

    data class Snapshot(
        val nowPlaying: String?,
        val requestedBy: String?,
        val upNext: List<Request>,
    )

    @Synchronized
    fun snapshot(liveTitle: String?): Snapshot {
        val c = current
        return Snapshot(
            nowPlaying = c?.track ?: liveTitle ?: c?.request?.query,
            requestedBy = c?.request?.by,
            upNext = queue.toList(),
        )
    }

    /** Call every few seconds. Starts the next song when the requested one has ended. */
    fun tick() {
        val title = player.nowPlaying() ?: return
        var event: String? = null
        synchronized(this) {
            val c = current ?: return
            val age = clock() - c.startedAt
            if (c.track == null) {
                // Learn which track Amazon actually started for this request.
                if (age >= SETTLE_MS && (title != c.previousTitle || age >= SAME_SONG_MS)) {
                    c.track = title
                }
                return
            }
            if (title != c.track) {
                if (queue.isNotEmpty()) {
                    event = playNext().message
                } else {
                    current = null // Amazon's own autoplay carries on
                }
            }
        }
        event?.let(onEvent)
    }

    /** Caller must hold the lock. */
    private fun playNext(): Result {
        val next = queue.removeFirst()
        val before = player.nowPlaying()
        return try {
            player.play(next.query)
            current = Current(next, clock(), before)
            Result(true, "Now playing: ${next.query} (from ${next.by})")
        } catch (e: Exception) {
            current = null
            Result(false, "Couldn't start '${next.query}': ${e.message}")
        }
    }

    companion object {
        const val SETTLE_MS = 8_000L
        const val SAME_SONG_MS = 60_000L
    }
}
