package com.onlineteachers.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onlineteachers.shared.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var relay: RelayClient? = null
    private var playback: Playback? = null
    private val main = Handler(Looper.getMainLooper())
    private var status by mutableStateOf("Disconnected")
    private var connected by mutableStateOf(false)
    private var viewing by mutableStateOf(false)
    private var teacherReady by mutableStateOf(false)
    private var paired by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var metrics by mutableStateOf("Waiting for a session")
    private var full by mutableStateOf(false)
    private var bytes = 0L; private var lastMetric = 0L; private var rtt = 0L
    private var lastKey = 0L
    private var wantedViewing = false
    private lateinit var vault: Vault
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); vault = Vault(this)
        setContent { SchoolTheme {
            var address by remember { mutableStateOf(runCatching { vault.get("address") }.getOrDefault("")) }
            var key by remember { mutableStateOf(runCatching { vault.get("key") }.getOrDefault("")) }
            var code by remember { mutableStateOf("") }
            var muted by remember { mutableStateOf(false) }
            if (full && viewing) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        VideoSurface(Modifier.weight(1f).fillMaxWidth()) { playback?.surface(it) }
                        TextButton(onClick = { full = false }) { Text("Exit full screen") }
                        TextButton(onClick = { end() }) { Text("End session") }
                    }
                }
            } else SchoolPage("Administrator") {
                StatusCard(status)
                if (!connected) {
                    SettingsFields(address, { address = it.trim() }, key, { key = it.trim() }, relay == null)
                    Text("Use the Admin setup key. The Teacher phone must also approve pairing with its one-time code.")
                    Button(onClick = {
                        if (!RelayClient.validAddress(address) || !RelayClient.validKey(key)) status = "Enter a valid wss:// address ending /ws and a 43-character Admin key"
                        else runCatching { vault.put("address", address); vault.put("key", key); connect(address, key) }
                            .onFailure { status = "Unable to save credentials or connect" }
                    }, enabled = relay == null, modifier = Modifier.fillMaxWidth()) { Text("Connect securely") }
                }
                if (connected && !paired) {
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code from Teacher phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { relay?.send(JSONObject().put("type", "pair").put("code", code)); code = "" }, enabled = code.length == 6) { Text("Pair phones") }
                }
                if (viewing) {
                    VideoSurface(Modifier.fillMaxWidth().height(300.dp)) { playback?.surface(it) }
                    Text(metrics, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { full = true }) { Text("Full screen") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { relay?.command("switch_camera") }) { Text("Switch camera") }
                        OutlinedButton(onClick = { muted = !muted; playback?.muted = muted }) { Text(if (muted) "Unmute audio" else "Mute audio") }
                    }
                    OutlinedButton(onClick = { paused = !paused; relay?.command(if (paused) "pause_video" else "resume_video") }) { Text(if (paused) "Resume video" else "Pause video") }
                    Text("Audio continues when video is paused. Mute changes playback on this phone only.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { end() }, modifier = Modifier.fillMaxWidth()) { Text("End session") }
                } else if (connected && paired) {
                    Button(onClick = { wantedViewing = true; relay?.command("start_view") }, enabled = teacherReady, modifier = Modifier.fillMaxWidth()) { Text("Start live viewing") }
                }
                if (relay != null) TextButton(onClick = { disconnect() }) { Text("Disconnect / edit settings") }
                Text("Live only • No built-in recording. Leaving this app ends viewing. The classroom phone always shows that monitoring is active.", style = MaterialTheme.typography.bodySmall)
            }
        } }
    }
    private fun connect(address: String, key: String) {
        disconnect()
        playback = Playback({ main.post { requestKey() } }, { message -> main.post { status = message } })
        relay = RelayClient(address, "admin", key, { o -> main.post { event(o) } }, { f ->
            playback?.accept(f)
            main.post { bytes += f.payload.size }
        }, { state -> main.post {
            if (state != "Connected") {
                connected = false; viewing = false; teacherReady = false; status = state
                playback?.clear(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } }).also { it.start() }
        main.post(tick)
    }
    private fun requestKey() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKey >= 1000) { lastKey = now; relay?.command("keyframe") }
    }
    private fun event(o: JSONObject) {
        when (o.optString("type")) {
            "authenticated" -> { connected = true; status = "Authenticated — checking Teacher phone" }
            "status" -> {
                paired = o.optBoolean("paired")
                val state = o.optString("state")
                teacherReady = state == "ready" || state == "viewing"
                status = when (state) {
                    "ready" -> "Teacher ready — monitoring locally activated"
                    "viewing" -> "Live viewing active"
                    "error" -> "Teacher camera/microphone unavailable"
                    "connecting" -> "Teacher camera/microphone starting"
                    else -> "Teacher offline — local activation may be required"
                }
                if (!teacherReady) { viewing = false; playback?.clear() }
                if (paired && teacherReady && wantedViewing && !viewing) relay?.command("start_view")
            }
            "paired" -> { paired = true; status = "Phones paired" }
            "viewing" -> {
                viewing = o.getBoolean("active"); paused = false
                if (viewing) { status = "Live viewing active"; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                else { playback?.clear(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); full = false }
            }
            "pong" -> rtt = (SystemClock.elapsedRealtime() - o.getLong("at")).coerceAtLeast(0)
            "reset_done" -> { paired = false; wantedViewing = false; end() }
            "error" -> { wantedViewing = false; status = o.optString("message", "Connection error") }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (relay == null) return
            val now = SystemClock.elapsedRealtime()
            if (lastMetric > 0) {
                val kbps = bytes * 8 / (now - lastMetric).coerceAtLeast(1)
                metrics = "$kbps kbps • Relay RTT ${rtt} ms • ${if (rtt > 600) "Weak connection" else "Connected"}"
                if (viewing && rtt > 600) relay?.command("reduce_bitrate")
            }
            bytes = 0; lastMetric = now
            relay?.send(JSONObject().put("type", "ping").put("at", now))
            main.postDelayed(this, 3000)
        }
    }
    private fun end() {
        wantedViewing = false; relay?.command("end_view"); viewing = false; full = false; paused = false
        playback?.clear(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun disconnect() {
        end(); main.removeCallbacks(tick); relay?.disconnect(); relay = null
        playback?.close(); playback = null; connected = false; paired = false; teacherReady = false
        status = "Disconnected"; bytes = 0; lastMetric = 0
    }
    override fun onStop() { disconnect(); super.onStop() }
}
