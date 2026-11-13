package uk.jukebox.host

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings

/** Starts songs in, and reads what's playing from, the Amazon Music app. */
class AmazonMusic(private val ctx: Context) : JukeboxQueue.Player {

    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)

    fun isInstalled(): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Needed to see (and control) Amazon Music's media session. */
    fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
        }
    }

    private fun controller(): MediaController? = try {
        sessions.getActiveSessions(listener).firstOrNull { it.packageName == PKG }
    } catch (e: SecurityException) {
        null
    }

    override fun play(query: String) {
        // Best route: ask Amazon Music's own media session to search and play.
        val c = controller()
        val actions = c?.playbackState?.actions ?: 0L
        if (c != null && (actions and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L) {
            c.transportControls.playFromSearch(query, Bundle())
            return
        }
        // Fallback: the "play X on Amazon Music" intent Google Assistant uses.
        // From the background this needs the "Display over other apps" permission.
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(PKG)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    override fun nowPlaying(): String? {
        val md = controller()?.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return if (artist.isNullOrBlank()) title else "$title – $artist"
    }

    fun isPlaying() = controller()?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun togglePause() {
        val c = controller() ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    /** Skip within Amazon Music itself (used when our queue is empty). */
    fun skipInAmazon() {
        controller()?.transportControls?.skipToNext()
    }

    companion object {
        const val PKG = "com.amazon.mp3"
    }
}
