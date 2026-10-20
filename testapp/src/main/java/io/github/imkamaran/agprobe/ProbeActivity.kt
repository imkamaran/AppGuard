package io.github.imkamaran.agprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.net.NetworkInterface
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Calls each API AppGuard intercepts and prints the result as `AGProbe: <probe>=<value>` lines,
 * on screen and in logcat, so behavior can be compared with the module on and off.
 *
 * Automation: adb shell am start -n <pkg>/io.github.imkamaran.agprobe.ProbeActivity --es probe all
 */
@android.annotation.SuppressLint("MissingPermission", "HardwareIds")
class ProbeActivity : Activity() {
    private lateinit var out: TextView
    private val main = Handler(Looper.getMainLooper())
    private val lm by lazy { getSystemService(LocationManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash-guard test: crash during start-up on request.
        if (intent?.getStringExtra("probe") == "crash") throw IllegalStateException("AGProbe: requested start-up crash")
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        for ((label, probe) in PROBES) {
            column.addView(Button(this).apply { text = label; setOnClickListener { run(probe) } })
        }
        out = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        column.addView(out)
        setContentView(ScrollView(this).apply { addView(column) })
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra("probe")?.let { run(it) }
    }

    private fun run(probe: String) {
        when (probe) {
            "all" -> listOf("android_id", "build", "last", "current", "updates", "updates_pi").forEach { run(it) }
            "android_id" -> report("android_id", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
            "build" -> report("build", "${Build.MANUFACTURER}|${Build.BRAND}|${Build.MODEL}|${Build.DEVICE}|${Build.PRODUCT}")
            "last" -> for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)) {
                report("last.$p", guard { fmt(lm.getLastKnownLocation(p)) })
            }
            "current" -> guard {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { report("current", fmt(it)) }
                "requested"
            }.let { if (it != "requested") report("current", it) }
            "updates" -> requestUpdates()
            "updates_pi" -> requestPendingIntentUpdates()
            "bench" -> bench()
            "clip_write" -> {
                val cm = getSystemService(ClipboardManager::class.java)
                val text = "agprobe-${System.currentTimeMillis() % 100000}"
                report("clip_write", guard { cm.setPrimaryClip(ClipData.newPlainText("probe", text)); text })
            }
            // Android 10+ only lets the focused foreground app read the clipboard: wait for focus.
            "clip_read" -> main.postDelayed({
                val cm = getSystemService(ClipboardManager::class.java)
                report("clip_read", guard {
                    "has=${cm.hasPrimaryClip()} desc=${cm.primaryClipDescription?.label} text=${cm.primaryClip?.getItemAt(0)?.text}"
                })
            }, 1_000)
            "single" -> {
                val listener = LocationListener { report("single.fix", fmt(it)) }
                @Suppress("DEPRECATION")
                report("single", guard { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()); "requested" })
            }
            // Deprecated text-only clipboard API (CharSequence getText / hasText).
            "clip_legacy" -> main.postDelayed({
                val cm = getSystemService(ClipboardManager::class.java)
                @Suppress("DEPRECATION")
                report("clip_legacy", guard { "hasText=${cm.hasText()} text=${cm.text}" })
            }, 1_000)
            "sensors" -> sensors()
            "sensors_cached" -> registerAccel(getSystemService(SensorManager::class.java), cachedAccel, "sensors.cached_accel")
            "privacy" -> listOf("wifi", "contacts", "calllog", "calendar", "phone").forEach { run(it) }
            "wifi" -> wifi()
            "contacts" -> queryProbe("contacts", ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME))
            "calllog" -> queryProbe("calllog", CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER))
            "calendar" -> queryProbe("calendar", CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE))
            "phone" -> phone()
            else -> report("error", "unknown probe $probe")
        }
    }

    private fun requestUpdates() {
        var n = 0
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                n++
                report("updates.$n", fmt(location))
                if (n >= 3) {
                    lm.removeUpdates(this)
                    report("updates", "removed after 3")
                }
            }
        }
        val r = guard { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()); "registered" }
        report("updates", r)
        main.postDelayed({
            if (n < 3) {
                lm.removeUpdates(listener)
                report("updates", "timeout after $n fixes")
            }
        }, 15_000)
    }

    private fun requestPendingIntentUpdates() {
        val pi = PendingIntent.getBroadcast(
            this, 7, Intent(this, LocationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        LocationReceiver.remaining = 2
        LocationReceiver.onDone = { lm.removeUpdates(pi); report("updates_pi", "removed after 2") }
        report("updates_pi", guard { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, pi); "registered" })
        main.postDelayed({
            if (LocationReceiver.remaining > 0) {
                lm.removeUpdates(pi)
                report("updates_pi", "timeout, ${LocationReceiver.remaining} fixes missing")
            }
        }, 15_000)
    }

    private fun sensors() {
        val sm = getSystemService(SensorManager::class.java)
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        @Suppress("DEPRECATION")
        report(
            "sensors.list",
            "total=${all.size} accel=${all.count { it.type == Sensor.TYPE_ACCELEROMETER }} " +
                "orientation=${all.count { it.type == Sensor.TYPE_ORIENTATION }} light=${all.count { it.type == Sensor.TYPE_LIGHT }} " +
                "types=${all.map { it.type }.sorted().joinToString(",")}",
        )
        report("sensors.default_accel", sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.name ?: "null")
        all.firstOrNull { it.type == Sensor.TYPE_ACCELEROMETER }?.let { cachedAccel = it }
        registerAccel(sm, all.firstOrNull { it.type == Sensor.TYPE_ACCELEROMETER }, "sensors.register_accel")
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        report("sensors.register_light", if (light == null) "no sensor" else "returned=${sm.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL)}")
        main.postDelayed({ sm.unregisterListener(lightListener) }, 2_000)
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {}
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    /** Accelerometer seen by an earlier "sensors" probe; lets "sensors_cached" test registration alone. */
    private var cachedAccel: Sensor? = null

    private fun registerAccel(sm: SensorManager, accel: Sensor?, key: String) {
        if (accel == null) {
            report(key, "no sensor object")
            return
        }
        var events = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { events++ }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        report(key, "returned=${sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_NORMAL)}")
        main.postDelayed({
            sm.unregisterListener(listener)
            report("$key.events", "$events in 2s")
        }, 2_000)
    }

    @Suppress("DEPRECATION")
    private fun wifi() {
        val wm = getSystemService(WifiManager::class.java)
        val info = wm.connectionInfo
        report("wifi.info", "ssid=${info?.ssid} bssid=${info?.bssid} mac=${info?.macAddress} ip=${info?.ipAddress}")
        report("wifi.scan", guard { "count=${wm.scanResults.size}" })
        val cm = getSystemService(ConnectivityManager::class.java)
        val transport = cm.getNetworkCapabilities(cm.activeNetwork)?.transportInfo as? WifiInfo
        report("wifi.transport", "ssid=${transport?.ssid} bssid=${transport?.bssid}")
        report("net.hwaddr", guard {
            val all = NetworkInterface.getNetworkInterfaces()?.toList()
            all?.joinToString { "${it.name}:${it.hardwareAddress?.size ?: "null"}" } ?: "interfaces unavailable"
        })
    }

    /** Queries [uri] with and without a projection; reports row and column counts only. */
    private fun queryProbe(key: String, uri: android.net.Uri, projection: Array<String>) {
        report("$key.projection", guard {
            contentResolver.query(uri, projection, null, null, null).use { c -> "rows=${c?.count} cols=${c?.columnCount}" }
        })
        report("$key.all_columns", guard {
            contentResolver.query(uri, null, null, null, null).use { c -> "rows=${c?.count} cols=${c?.columnCount}" }
        })
    }

    private fun phone() {
        val tm = getSystemService(TelephonyManager::class.java)
        report(
            "phone.operator",
            "sim=${tm.simOperator} simName=${tm.simOperatorName} simIso=${tm.simCountryIso} " +
                "net=${tm.networkOperator} netName=${tm.networkOperatorName} netIso=${tm.networkCountryIso}",
        )
        report("phone.carrier", "id=${tm.simCarrierId} name=${tm.simCarrierIdName}")
        @Suppress("DEPRECATION")
        report("phone.number", guard { tm.line1Number ?: "null" })
        val sm = getSystemService(SubscriptionManager::class.java)
        report("phone.subs", guard {
            val subs = sm.activeSubscriptionInfoList
            "count=${subs?.size} first=${subs?.firstOrNull()?.carrierName}"
        })
    }

    /** Median per-call cost of hooked APIs over several rounds; compare target vs control. */
    private fun bench() {
        Thread {
            fun measure(n: Int, block: () -> Unit): Long {
                repeat(n / 10) { block() } // warm-up
                val rounds = LongArray(7) {
                    val t0 = System.nanoTime()
                    repeat(n) { block() }
                    (System.nanoTime() - t0) / n
                }
                rounds.sort()
                return rounds[rounds.size / 2]
            }
            val id = measure(5_000) { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
            val other = measure(5_000) { Settings.Secure.getString(contentResolver, Settings.Secure.ADB_ENABLED) }
            val last = measure(500) { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            report("bench", "android_id=${id}ns other_setting=${other}ns last_known=${last}ns")
        }.start()
    }

    private fun report(key: String, value: String?) {
        val line = "$key=${value ?: "null"}"
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    private inline fun guard(block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "error:${t.javaClass.simpleName}:${t.message}"
    }

    companion object {
        const val TAG = "AGProbe"
        private val PROBES = listOf(
            "Run all" to "all",
            "Android ID" to "android_id",
            "Build info" to "build",
            "Last known location" to "last",
            "Current location" to "current",
            "Location updates (listener)" to "updates",
            "Location updates (PendingIntent)" to "updates_pi",
            "Clipboard write" to "clip_write",
            "Clipboard read" to "clip_read",
            "Sensors" to "sensors",
            "Wi-Fi & network" to "wifi",
            "Contacts" to "contacts",
            "Call log" to "calllog",
            "Calendar" to "calendar",
            "Phone & SIM" to "phone",
        )

        fun fmt(l: Location?): String =
            if (l == null) "null" else "${l.latitude},${l.longitude} acc=${l.accuracy} alt=${if (l.hasAltitude()) l.altitude else "-"} provider=${l.provider}"
    }
}

class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val loc = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED) ?: return
        if (remaining <= 0) return
        remaining--
        Log.i(ProbeActivity.TAG, "updates_pi.fix=${ProbeActivity.fmt(loc)}")
        if (remaining == 0) onDone?.invoke()
    }

    companion object {
        @Volatile
        var remaining = 0
        var onDone: (() -> Unit)? = null
    }
}
