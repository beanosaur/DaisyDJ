package uk.jukebox.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Runs in the background while the jukebox is on: hosts the guest web page,
 * holds the queue, and keeps Amazon Music moving through it.
 */
class JukeboxService : Service() {

    lateinit var amazon: AmazonMusic
        private set
    lateinit var queue: JukeboxQueue
        private set

    private lateinit var server: TinyHttpServer
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val lastAddByIp = HashMap<String, Long>()
    private lateinit var pageHtml: ByteArray

    var lastEvent: String = "Ready for requests"
        private set

    private val worker = HandlerThread("jukebox-worker").apply { start() }
    private val background = Handler(worker.looper)

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { queue.tick() }
            background.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        amazon = AmazonMusic(this)
        queue = JukeboxQueue(amazon, maxPerUser = MAX_PER_USER)
        queue.onEvent = { msg -> lastEvent = msg; main.post { updateNotification() } }
        pageHtml = assets.open("index.html").use { it.readBytes() }

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        acquireLocks()
        server = TinyHttpServer(PORT, ::handle)
        try {
            server.start()
        } catch (e: Exception) {
            lastEvent = "Couldn't start web page on port $PORT: ${e.message}"
        }
        background.post(ticker)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        background.removeCallbacksAndMessages(null)
        worker.quitSafely()
        server.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- web
    private fun handle(r: TinyHttpServer.Request): TinyHttpServer.Response = when {
        r.method == "GET" && (r.path == "/" || r.path == "/index.html") ->
            TinyHttpServer.Response(200, "text/html; charset=utf-8", pageHtml)

        r.method == "GET" && r.path == "/api/state" -> json(200, stateJson())

        r.method == "POST" && r.path == "/api/add" -> addFromGuest(r)

        else -> TinyHttpServer.Response.text(404, "Not found")
    }

    private fun addFromGuest(r: TinyHttpServer.Request): TinyHttpServer.Response {
        val now = System.currentTimeMillis()
        synchronized(lastAddByIp) {
            val last = lastAddByIp[r.remote] ?: 0L
            if (now - last < GUEST_COOLDOWN_MS) {
                return json(429, result(false, "Slow down a little – try again in a few seconds."))
            }
            lastAddByIp[r.remote] = now
        }
        val res = queue.add(r.params["q"].orEmpty(), r.params["name"].orEmpty())
        if (res.ok) {
            lastEvent = res.message
            main.post { updateNotification() }
        }
        return json(if (res.ok) 200 else 400, result(res.ok, res.message))
    }

    fun stateJson(): JSONObject {
        val snap = queue.snapshot(amazon.nowPlaying())
        return JSONObject()
            .put("nowPlaying", snap.nowPlaying ?: JSONObject.NULL)
            .put("requestedBy", snap.requestedBy ?: JSONObject.NULL)
            .put("playing", amazon.isPlaying())
            .put("upNext", JSONArray().apply {
                snap.upNext.forEach { put(JSONObject().put("query", it.query).put("by", it.by)) }
            })
    }

    private fun result(ok: Boolean, msg: String) = JSONObject().put("ok", ok).put("message", msg)

    private fun json(status: Int, o: JSONObject) =
        TinyHttpServer.Response.text(status, o.toString(), "application/json; charset=utf-8")

    // ---------------------------------------------------- notification
    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Jukebox running", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JukeboxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val url = guestUrl() ?: "Connect to Wi-Fi or turn on hotspot"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jukebox on · $url")
            .setContentText(lastEvent)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // ----------------------------------------------------------- locks
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jukebox:server")
            .apply { setReferenceCounted(false); acquire() }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jukebox:wifi")
            .apply { setReferenceCounted(false); acquire() }
    }

    companion object {
        const val PORT = 8080
        const val MAX_PER_USER = 3
        const val ACTION_STOP = "uk.jukebox.host.STOP"
        private const val CHANNEL = "jukebox"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 3_000L
        private const val GUEST_COOLDOWN_MS = 4_000L

        @Volatile var instance: JukeboxService? = null
            private set

        /** Address guests type in: works on Wi-Fi and when this phone is the hotspot. */
        fun guestUrl(): String? {
            val candidates = try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { ni -> ni.inetAddresses.toList().map { ni.name to it } }
                    .filter { (_, a) -> a is Inet4Address && a.isSiteLocalAddress }
            } catch (e: Exception) {
                emptyList()
            }
            val best = candidates.firstOrNull { (name, _) ->
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
            } ?: candidates.firstOrNull()
            return best?.second?.hostAddress?.let { "http://$it:$PORT" }
        }
    }
}
