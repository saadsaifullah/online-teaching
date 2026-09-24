package com.onlineteachers.teacher

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.onlineteachers.shared.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

object TeacherStatus {
    val status = MutableStateFlow("Offline — local activation required")
    val active = MutableStateFlow(false)
    val pairingCode = MutableStateFlow("")
    @Volatile var localPreview: ((Frame) -> Unit)? = null
    @Volatile var service: MonitoringService? = null
}

class MonitoringService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var capture: Capture? = null
    private var relay: RelayClient? = null
    @Volatile private var viewing = false
    @Volatile private var paused = false
    @Volatile private var needsKey = true
    @Volatile private var ready = false
    private var videoReady = false; private var audioReady = false
    private var lastReduction = 0L
    private val configs = mutableMapOf<Int, Frame>()
    private var failed = false
    override fun onBind(intent: Intent?) = null
    @SuppressLint("MissingPermission") // Runtime permission and notification gates are checked below.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (capture != null) return START_NOT_STICKY
        TeacherStatus.service = this
        try {
            check(permissionsReady())
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("monitoring", "Visible classroom monitoring", NotificationManager.IMPORTANCE_LOW))
            check(nm.getNotificationChannel("monitoring").importance != NotificationManager.IMPORTANCE_NONE)
            startForeground(100, notification("Camera and microphone active • Connecting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            TeacherStatus.active.value = true
            val vault = Vault(this)
            val address = vault.get("address"); val key = vault.get("key")
            require(RelayClient.validAddress(address) && RelayClient.validKey(key))
            relay = RelayClient(address, "teacher", key, { event -> main.post { handle(event) } }, onState = { state -> main.post {
                if (!TeacherStatus.active.value) return@post
                if (state != "Connected") { viewing = false; paused = false; needsKey = true }
                update(if (state == "Connected" && ready) "Ready — camera/microphone active; no viewer" else state)
            } })
            capture = Capture(this, { frame ->
                TeacherStatus.localPreview?.invoke(frame)
                if (frame.flags and Frame.CONFIG != 0) {
                    synchronized(configs) { configs[frame.kind] = frame }
                    main.post {
                        if (frame.kind == 1) videoReady = true else audioReady = true
                        if (!ready && videoReady && audioReady) { ready = true; publishReady() }
                    }
                }
                if (viewing) transmit(frame)
            }, { message -> main.post { fail(message) } }).also { it.start() }
            relay!!.start()
            main.post(permissionWatch)
        } catch (_: Exception) { fail("Activation failed — enable camera, microphone and notifications, then retry") }
        return START_NOT_STICKY
    }
    private fun transmit(frame: Frame) {
        val link = relay ?: return
        if (link.queuedBytes() > 256000) {
            needsKey = true
            val now = SystemClock.elapsedRealtime()
            if (now - lastReduction > 3000) { lastReduction = now; capture?.reduceBitrate(); capture?.keyframe() }
            return
        }
        if (frame.kind == 1 && frame.flags and Frame.CONFIG == 0) {
            if (paused) return
            if (needsKey) {
                if (frame.flags and Frame.KEY == 0) return
                needsKey = false
                synchronized(configs) { configs.values.forEach { link.sendFrame(it) } }
            }
        }
        link.sendFrame(frame)
    }
    private fun handle(o: JSONObject) {
        when (o.optString("type")) {
            "authenticated" -> publishReady()
            "viewer" -> {
                viewing = o.getBoolean("active"); paused = false; needsKey = true
                if (viewing) capture?.keyframe()
                update(if (viewing) "Admin viewing — live camera and microphone" else "Ready — camera/microphone active; no viewer")
            }
            "switch_camera" -> { needsKey = true; capture?.switchCamera() }
            "pause_video" -> paused = true
            "resume_video" -> { paused = false; needsKey = true; capture?.keyframe() }
            "keyframe" -> capture?.keyframe()
            "reduce_bitrate" -> capture?.reduceBitrate()
            "pair_code" -> TeacherStatus.pairingCode.value = o.getString("code")
            "paired" -> TeacherStatus.pairingCode.value = "Paired successfully"
            "reset_done" -> { TeacherStatus.pairingCode.value = "Pairing removed"; viewing = false }
            "error" -> update(o.optString("message", "Relay error"))
        }
    }
    private fun publishReady() {
        relay?.send(JSONObject().put("type", "status").put("state", if (ready) "ready" else "connecting"))
        if (ready && relay?.authenticated == true) update("Ready — camera/microphone active; no viewer")
    }
    fun pair() { relay?.command("create_pair") }
    fun resetPairing() { relay?.command("reset_pair") }
    fun previewKey() { capture?.keyframe() }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "monitoring").setSmallIcon(R.drawable.ic_brand)
            .setContentTitle("Online Teachers • Monitoring active").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop monitoring", stop).build()
    }
    @SuppressLint("MissingPermission") // Notifications are required at activation and rechecked while active.
    private fun update(value: String) {
        TeacherStatus.status.value = value
        if (TeacherStatus.active.value) getSystemService(NotificationManager::class.java).notify(100, notification(value))
    }
    private fun permissionsReady(): Boolean = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    } && NotificationManagerCompat.from(this).areNotificationsEnabled()
    private val permissionWatch = object : Runnable {
        override fun run() {
            if (!TeacherStatus.active.value) return
            if (!permissionsReady() || getSystemService(NotificationManager::class.java).getNotificationChannel("monitoring")?.importance == NotificationManager.IMPORTANCE_NONE) {
                fail("Stopped — camera, microphone or notifications disabled"); return
            }
            main.postDelayed(this, 3000)
        }
    }
    private fun fail(message: String) {
        failed = true; TeacherStatus.status.value = message
        relay?.send(JSONObject().put("type", "status").put("state", "error"))
        stopSelf()
    }
    override fun onDestroy() {
        TeacherStatus.active.value = false; TeacherStatus.service = null; TeacherStatus.pairingCode.value = ""
        main.removeCallbacksAndMessages(null)
        viewing = false; capture?.close(); capture = null; relay?.disconnect(); relay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!failed) TeacherStatus.status.value = "Offline — monitoring stopped; local activation required"
        super.onDestroy()
    }
}
