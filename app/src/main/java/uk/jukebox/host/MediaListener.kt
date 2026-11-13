package uk.jukebox.host

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself. Android only lets an app read and control other apps'
 * media sessions (Amazon Music's now-playing info) once the user has granted
 * it notification access, which requires this service to exist.
 */
class MediaListener : NotificationListenerService()
