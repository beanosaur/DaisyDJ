package uk.jukebox.host

/**
 * The shared song queue. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Amazon Music has no "add to queue" API, so we keep our own queue and start each
 * song when the previous requested one finishes. We spot "finished" by watching the
 * now-playing title: once Amazon moves on to a different track, the next request plays.
 *
 * Guests can upvote queued songs (most votes play first) and vote to skip the
 * current one. Guests are identified by a random client ID their browser keeps.
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

    /** A song as picked from search results, or typed free-form (title only). */
    data class Song(
        val title: String,
        val artist: String? = null,
        val artwork: String? = null,
        val trackId: Long? = null,
    ) {
        val label get() = if (artist.isNullOrBlank()) title else "$title – $artist"
        val searchQuery get() = if (artist.isNullOrBlank()) title else "$title $artist"
    }

    class Request(
        val id: Long,
        val song: Song,
        val by: String,
        val clientId: String,
        val addedAt: Long,
    ) {
        val voters = LinkedHashSet<String>()
        val votes get() = voters.size
    }

    data class Result(val ok: Boolean, val message: String, val needsAmazonSkip: Boolean = false)

    data class Item(
        val id: Long, val song: Song, val by: String,
        val votes: Int, val myVote: Boolean, val mine: Boolean,
    )

    data class Snapshot(
        val nowPlaying: String?,
        val nowArtwork: String?,
        val requestedBy: String?,
        val skipVotes: Int,
        val skipNeeded: Int,
        val iVotedSkip: Boolean,
        val canVoteSkip: Boolean,
        val upNext: List<Item>,
    )

    private class Current(
        val request: Request,
        val startedAt: Long,
        val previousTitle: String?,
        var track: String? = null,
    )

    private val queue = mutableListOf<Request>()
    private var current: Current? = null
    private val skipVoters = HashSet<String>()
    private var nextId = 1L

    /** Called whenever something worth telling guests happens. */
    var onEvent: (String) -> Unit = {}

    @Synchronized
    fun add(raw: Song, rawName: String, clientId: String): Result {
        val song = raw.copy(
            title = clean(raw.title, 100),
            artist = raw.artist?.let { clean(it, 80) }?.ifEmpty { null },
        )
        val by = clean(rawName, 20).ifEmpty { "Someone" }
        if (song.title.isEmpty()) return Result(false, "Pick a song first.")

        current?.let {
            if (sameSong(it.request.song, song)) return Result(false, "That's playing right now! 🎶")
        }
        queue.firstOrNull { sameSong(it.song, song) }?.let { existing ->
            if (existing.clientId != clientId) existing.voters.add(clientId)
            return Result(true, "Already in the queue – we've counted your vote 👍")
        }
        val mine = queue.count { it.clientId == clientId }
        if (mine >= maxPerUser) {
            return Result(false, "You've already got $mine songs waiting – give others a go!")
        }
        queue += Request(nextId++, song, by, clientId, clock())
        if (current == null) return playNext()
        val position = ordered().indexOfFirst { it.clientId == clientId && sameSong(it.song, song) } + 1
        return Result(true, "Added! You're #$position in the queue.")
    }

    /** Toggle an upvote. Returns the new state in the message. */
    @Synchronized
    fun vote(requestId: Long, clientId: String): Result {
        val r = queue.firstOrNull { it.id == requestId } ?: return Result(false, "That song has already played.")
        if (r.clientId == clientId) return Result(false, "You can't vote for your own song 😄")
        return if (r.voters.remove(clientId)) Result(true, "Vote removed")
        else { r.voters.add(clientId); Result(true, "Voted 👍") }
    }

    /** Guest vote to skip the current request. Skips once enough people agree. */
    @Synchronized
    fun voteSkip(clientId: String, activeGuests: Int): Result {
        val c = current ?: return Result(false, "Nothing from the queue is playing.")
        if (c.request.clientId == clientId) return skipNow("Skipped by the requester.")
        skipVoters.add(clientId)
        val needed = skipThreshold(activeGuests)
        if (skipVoters.size >= needed) return skipNow("The crowd has spoken – skipped! ⏭")
        return Result(true, "Skip vote counted (${skipVoters.size}/$needed)")
    }

    /** Host skip. */
    @Synchronized
    fun skip(): Result = skipNow("Skipped.")

    @Synchronized
    fun clear(): Result {
        val n = queue.size
        queue.clear()
        return Result(true, "Cleared $n queued song(s).")
    }

    @Synchronized
    fun remove(requestId: Long): Result {
        val r = queue.firstOrNull { it.id == requestId } ?: return Result(false, "That song isn't in the queue.")
        queue.remove(r)
        return Result(true, "Removed ${r.song.title}.")
    }

    @Synchronized
    fun snapshot(clientId: String, liveTitle: String?, activeGuests: Int): Snapshot {
        val c = current
        return Snapshot(
            nowPlaying = c?.request?.song?.label ?: liveTitle,
            nowArtwork = c?.request?.song?.artwork,
            requestedBy = c?.request?.by,
            skipVotes = skipVoters.size,
            skipNeeded = skipThreshold(activeGuests),
            iVotedSkip = clientId in skipVoters,
            canVoteSkip = c != null,
            upNext = ordered().map {
                Item(it.id, it.song, it.by, it.votes, clientId in it.voters, it.clientId == clientId)
            },
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
                    skipVoters.clear()
                }
            }
        }
        event?.let(onEvent)
    }

    // ------------------------------------------------------------ internals
    /** Most votes first; ties go to whoever asked first. */
    private fun ordered(): List<Request> =
        queue.sortedWith(compareByDescending<Request> { it.votes }.thenBy { it.addedAt }.thenBy { it.id })

    /** Caller must hold the lock. */
    private fun skipNow(message: String): Result {
        if (queue.isEmpty()) {
            current = null
            skipVoters.clear()
            return Result(true, message, needsAmazonSkip = true)
        }
        val next = playNext()
        return next.copy(message = "$message ${next.message}")
    }

    /** Caller must hold the lock. */
    private fun playNext(): Result {
        val next = ordered().first()
        queue.remove(next)
        skipVoters.clear()
        val before = player.nowPlaying()
        return try {
            player.play(next.song.searchQuery)
            current = Current(next, clock(), before)
            Result(true, "Now playing: ${next.song.label} (from ${next.by})")
        } catch (e: Exception) {
            current = null
            Result(false, "Couldn't start '${next.song.label}': ${e.message}")
        }
    }

    private fun sameSong(a: Song, b: Song): Boolean =
        if (a.trackId != null && b.trackId != null) a.trackId == b.trackId
        else a.label.equals(b.label, ignoreCase = true)

    private fun clean(s: String, max: Int) = s.trim().replace(Regex("\\s+"), " ").take(max)

    companion object {
        const val SETTLE_MS = 8_000L
        const val SAME_SONG_MS = 60_000L

        /** Half the room (rounded up), but at least 2 people once there's a crowd. */
        fun skipThreshold(activeGuests: Int): Int =
            if (activeGuests <= 1) 1 else maxOf(2, (activeGuests + 1) / 2)
    }
}
