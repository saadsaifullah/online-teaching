package com.onlineteachers.shared

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.view.Surface
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Bounded decode queue. After overload, discard dependent video until a fresh IDR. */
class Playback(private val requestKey: () -> Unit, private val onError: (String) -> Unit) {
    private val thread = HandlerThread("live-playback").apply { start() }
    private val h = Handler(thread.looper)
    private var surface: Surface? = null
    private var video: MediaCodec? = null; private var audio: MediaCodec? = null
    private var track: AudioTrack? = null
    private var videoConfig: Frame? = null
    @Volatile private var waitForKey = true
    @Volatile private var closed = false
    @Volatile var muted = false
    private val pending = AtomicInteger()
    fun surface(value: Surface?) { h.post {
        surface = value
        releaseVideo()
        if (value != null) { videoConfig?.let { configure(it) }; requestKey() }
    } }
    fun accept(frame: Frame) {
        if (closed) return
        if (pending.get() >= 30) {
            if (!waitForKey) { waitForKey = true; requestKey() }
            return
        }
        pending.incrementAndGet()
        h.post {
            try {
                if (!closed) {
                    if (frame.flags and Frame.CONFIG != 0) configure(frame) else decode(frame)
                }
            } catch (_: Exception) { onError("Playback error — end the session and reconnect") }
            finally { pending.decrementAndGet() }
        }
    }
    private fun configure(frame: Frame) {
        val o = JSONObject(String(frame.payload))
        val mime = if (frame.kind == 1) "video/avc" else "audio/mp4a-latm"
        require(o.getString("mime") == mime)
        val format: MediaFormat
        if (frame.kind == 1) {
            videoConfig = frame
            if (surface == null) return
            releaseVideo()
            val width = o.getInt("width"); val height = o.getInt("height")
            require(width in 16..1920 && height in 16..1920)
            format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_ROTATION, o.optInt("rotation", 0))
        } else {
            releaseAudio()
            val rate = o.getInt("sampleRate"); require(rate == 44100 && o.getInt("channels") == 1)
            format = MediaFormat.createAudioFormat(mime, rate, 1)
        }
        val csd = o.getJSONArray("csd"); require(csd.length() in 1..3)
        for (i in 0 until csd.length()) format.setByteBuffer("csd-$i", ByteBuffer.wrap(Base64.decode(csd.getString(i), Base64.NO_WRAP)))
        val codec = MediaCodec.createDecoderByType(mime)
        try { codec.configure(format, if (frame.kind == 1) surface else null, null, 0); codec.start() }
        catch (e: Exception) { codec.release(); throw e }
        if (frame.kind == 1) { video = codec; waitForKey = true }
        else {
            audio = codec
            val min = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, 8820)).setTransferMode(AudioTrack.MODE_STREAM).build().apply { play() }
        }
    }
    private fun decode(f: Frame) {
        val codec = (if (f.kind == 1) video else audio) ?: return
        if (f.kind == 1 && waitForKey) {
            if (f.flags and Frame.KEY == 0) return
            waitForKey = false
        }
        val index = codec.dequeueInputBuffer(0)
        if (index >= 0) {
            val b = codec.getInputBuffer(index)!!; b.clear(); require(f.payload.size <= b.remaining()); b.put(f.payload)
            codec.queueInputBuffer(index, 0, f.payload.size, f.ptsUs, 0)
        } else if (f.kind == 1) { waitForKey = true; requestKey() }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (out < 0) break
            if (f.kind == 2 && info.size > 0) {
                val b = codec.getOutputBuffer(out)!!.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                val bytes = ByteArray(info.size); b.get(bytes)
                track?.setVolume(if (muted) 0f else 1f)
                track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            codec.releaseOutputBuffer(out, f.kind == 1)
        }
    }
    private fun releaseVideo() { runCatching { video?.stop() }; runCatching { video?.release() }; video = null; waitForKey = true }
    private fun releaseAudio() {
        runCatching { audio?.stop() }; runCatching { audio?.release() }; audio = null
        runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }; track = null
    }
    fun clear() { h.post { releaseVideo(); releaseAudio(); videoConfig = null } }
    fun close() { closed = true; h.post { releaseVideo(); releaseAudio(); thread.quitSafely() } }
}
