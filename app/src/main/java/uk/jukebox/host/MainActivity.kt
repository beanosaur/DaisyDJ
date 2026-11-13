package uk.jukebox.host

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Host screen: setup checklist, on/off switch, QR code for guests, queue controls. */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var amazon: AmazonMusic

    private lateinit var checklist: LinearLayout
    private lateinit var startStop: Button
    private lateinit var qr: ImageView
    private lateinit var urlText: TextView
    private lateinit var nowText: TextView
    private lateinit var queueList: LinearLayout
    private lateinit var controls: LinearLayout
    private var shownUrl: String? = null

    private val refresher = object : Runnable {
        override fun run() {
            refreshLive()
            ui.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        amazon = AmazonMusic(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    // ---------------------------------------------------------- layout
    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(label("🎵 Group Jukebox", 26f, bold = true))
        root.addView(label("Guests add songs from their phones; this phone plays them in Amazon Music.", 15f))

        root.addView(header("Setup"))
        checklist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checklist)

        startStop = Button(this).apply { setOnClickListener { toggleService() } }
        root.addView(startStop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        root.addView(header("Guests scan this"))
        qr = ImageView(this)
        root.addView(qr, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        urlText = label("", 18f, bold = true).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }
        root.addView(urlText)
        root.addView(label("Guests must be on the same Wi-Fi – or turn on this phone's hotspot and let them join it.", 13f).apply { gravity = Gravity.CENTER })

        root.addView(header("Now playing"))
        nowText = label("", 17f)
        root.addView(nowText)

        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("⏯ Pause/Play") { amazon.togglePause() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("⏭ Skip") { skip() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(button("🗑 Clear") { service()?.let { toast(it.queue.clear().message) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)

        root.addView(header("Up next"))
        queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(queueList)

        root.addView(header("Test it"))
        val testBox = EditText(this).apply { hint = "Song or artist"; setSingleLine() }
        root.addView(testBox)
        root.addView(button("Add song as host") {
            val s = service()
            if (s == null) toast("Start the jukebox first")
            else Thread {
                val msg = s.queue.add(testBox.text.toString(), "Host").message
                runOnUiThread { toast(msg); testBox.setText("") }
            }.start()
        })

        return ScrollView(this).apply { addView(root) }
    }

    // ------------------------------------------------------- checklist
    private fun refreshChecklist() {
        checklist.removeAllViews()
        val power = getSystemService(PowerManager::class.java)
        addCheck("Amazon Music installed (open it once and sign in)", amazon.isInstalled()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AmazonMusic.PKG}")))
        }
        addCheck("Notification access – lets Jukebox see & control what's playing", amazon.hasNotificationAccess()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addCheck("Display over other apps – lets Jukebox start songs while in the background", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addCheck("Battery: unrestricted – so Android doesn't stop the party", power.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        addCheck("Bluetooth speaker connected (check in Settings)", null) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun addCheck(label: String, ok: Boolean?, fix: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val mark = when (ok) { true -> "✅"; false -> "⚠️"; null -> "🔈" }
        row.addView(label("$mark  $label", 15f), LinearLayout.LayoutParams(0, -2, 1f))
        if (ok != true) {
            row.addView(button(if (ok == null) "Open" else "Fix") {
                try { fix() } catch (e: Exception) { toast("Couldn't open that setting – find it in Settings › Apps") }
            })
        }
        checklist.addView(row)
    }

    // ------------------------------------------------------- live view
    private fun refreshLive() {
        val s = service()
        startStop.text = if (s == null) "▶  Start jukebox" else "■  Stop jukebox"
        controls.visibility = if (s == null) View.GONE else View.VISIBLE

        val url = if (s != null) JukeboxService.guestUrl() else null
        if (url != shownUrl) {
            shownUrl = url
            qr.setImageBitmap(url?.let { makeQr(it) })
            qr.visibility = if (url == null) View.GONE else View.VISIBLE
        }
        urlText.text = when {
            s == null -> "Start the jukebox to get the guest link"
            url == null -> "Connect to Wi-Fi or turn on hotspot"
            else -> url
        }

        queueList.removeAllViews()
        if (s == null) {
            nowText.text = "Jukebox is off"
            return
        }
        Thread {
            val state = s.stateJson()
            runOnUiThread {
                val now = state.optString("nowPlaying").takeIf { !state.isNull("nowPlaying") }
                val by = state.optString("requestedBy").takeIf { !state.isNull("requestedBy") }
                nowText.text = when {
                    now == null -> "Nothing yet – waiting for requests"
                    by != null -> "$now\nrequested by $by"
                    else -> now
                }
                queueList.removeAllViews()
                val upNext = state.getJSONArray("upNext")
                if (upNext.length() == 0) queueList.addView(label("Queue is empty", 15f))
                for (i in 0 until upNext.length()) {
                    val item = upNext.getJSONObject(i)
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label("${i + 1}. ${item.getString("query")} · ${item.getString("by")}", 15f), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(button("✕") { toast(s.queue.remove(i).message); refreshLive() })
                    queueList.addView(row)
                }
            }
        }.start()
    }

    // --------------------------------------------------------- actions
    private fun toggleService() {
        val running = service() != null
        if (running) {
            startService(Intent(this, JukeboxService::class.java).setAction(JukeboxService.ACTION_STOP))
        } else {
            if (!amazon.hasNotificationAccess()) {
                toast("Grant notification access first (see Setup)")
                return
            }
            startForegroundService(Intent(this, JukeboxService::class.java))
        }
        ui.postDelayed({ refreshLive() }, 600)
    }

    private fun skip() {
        val s = service() ?: return
        Thread {
            val r = s.queue.skip()
            if (!r.ok) s.amazon.skipInAmazon()
            runOnUiThread { toast(if (r.ok) r.message else "Skipped (nothing queued)") }
        }.start()
    }

    private fun service() = JukeboxService.instance

    // --------------------------------------------------------- helpers
    private fun makeQr(content: String): Bitmap {
        val size = 600
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun label(s: String, sp: Float, bold: Boolean = false): TextView = TextView(this).apply {
        setText(s)
        textSize = sp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun header(s: String) = label(s, 19f, bold = true).apply { setPadding(0, dp(20), 0, dp(6)) }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { setText(label); isAllCaps = false; setOnClickListener { onClick() } }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
