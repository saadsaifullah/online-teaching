package com.onlineteachers.shared

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.random.Random

class RelayClient(
    private val address: String, private val role: String, private val key: String,
    private val onEvent: (JSONObject) -> Unit,
    private val onFrame: (Frame) -> Unit = {},
    private val onState: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile var authenticated = false; private set
    private var attempts = 0
    private var generation = 0
    private val reconnect = Runnable { connect() }
    fun start() { stopped = false; connect() }
    private fun connect() {
        if (stopped) return
        handler.removeCallbacks(reconnect)
        val current = ++generation
        onState("Connecting")
        socket = http.newWebSocket(Request.Builder().url(address).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped || current != generation) return
                try {
                    val o = JSONObject(text)
                    when (o.getString("type")) {
                        "challenge" -> {
                            val nonce = o.getString("nonce"); val expires = o.getLong("expires")
                            val message = "$role:$nonce:$expires"
                            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
                            val proof = Base64.encodeToString(mac.doFinal(message.toByteArray()), Base64.NO_WRAP)
                            webSocket.send(JSONObject().put("type", "auth").put("role", role).put("proof", proof).toString())
                        }
                        "authenticated" -> { authenticated = true; attempts = 0; onState("Connected"); onEvent(o) }
                        "ping" -> send(JSONObject().put("type", "pong").put("at", o.getLong("at")))
                        else -> onEvent(o)
                    }
                } catch (_: Exception) { webSocket.close(1002, "Invalid control message") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (stopped || current != generation) return
                try { onFrame(Frame.decode(bytes.toByteArray())) }
                catch (_: Exception) { webSocket.close(1002, "Invalid media") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost(current) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost(current) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }
    private fun lost(current: Int) {
        handler.post {
            if (!stopped && current == generation) {
                authenticated = false
                onState("Offline — reconnecting")
                handler.removeCallbacks(reconnect)
                val delay = min(30000L, 1000L shl min(attempts++, 5)) + Random.nextLong(500)
                handler.postDelayed(reconnect, delay)
            }
        }
    }
    fun send(o: JSONObject): Boolean = authenticated && socket?.send(o.toString()) == true
    fun command(type: String) = send(JSONObject().put("type", type))
    fun sendFrame(f: Frame): Boolean = authenticated && socket?.send(f.encode().toByteString()) == true
    fun queuedBytes(): Long = socket?.queueSize() ?: 0L
    fun disconnect() {
        stopped = true; authenticated = false; generation++
        handler.removeCallbacks(reconnect); socket?.close(1000, "Local stop"); socket = null
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
    companion object {
        fun validAddress(value: String): Boolean = runCatching {
            val u = URI(value)
            u.scheme == "wss" && !u.host.isNullOrBlank() && u.path == "/ws" && u.userInfo == null && u.fragment == null && u.query == null
        }.getOrDefault(false)
        fun validKey(value: String) = value.matches(Regex("[A-Za-z0-9_-]{43}"))
    }
}
