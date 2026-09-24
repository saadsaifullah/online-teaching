package com.onlineteachers.shared

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.util.Base64
import android.view.Surface
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** All camera and encoder state is confined to the capture thread. No recording files. */
class Capture(private val context: Context, private val output: (Frame) -> Unit, private val error: (String) -> Unit) {
    private val thread = HandlerThread("teacher-capture").apply { start() }
    private val h = Handler(thread.looper)
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var input: Surface? = null
    private var front = true
    private var width = 0; private var height = 0; private var rotation = 0
    private var bitrate = 650000
    private var epoch = 0
    private val sequence = AtomicInteger()
    @Volatile private var closed = false
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private val audioLock = Any()
    private var audioPts = 0L
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (!closed && configs.any { it.clientAudioSessionId == recorder?.audioSessionId && it.isClientSilenced }) {
                error("Microphone silenced by Android — check privacy controls and activate again")
            }
        }
    }
    private val configurations = mutableMapOf<Int, Frame>()
    fun start() { h.post { try { openVideo(); startAudio() } catch (_: Exception) { error("Camera/microphone unavailable — check permissions and privacy switches") } } }
    fun switchCamera() { h.post {
        try { front = !front; closeVideo(); openVideo() }
        catch (_: Exception) { error("Selected camera unavailable") }
    } }
    fun keyframe() { h.post { runCatching {
        synchronized(configurations) { configurations.values.forEach(output) }
        video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    } } }
    fun reduceBitrate() { h.post { runCatching {
        bitrate = (bitrate * 0.8).toInt().coerceAtLeast(250000)
        video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
    } } }
    private fun emit(kind: Int, flags: Int, pts: Long, data: ByteArray) {
        if (closed || data.isEmpty() || data.size > Frame.MAX_PAYLOAD) return
        val frame = Frame(kind, flags, sequence.incrementAndGet(), pts.coerceAtLeast(0), data)
        if (flags and Frame.CONFIG != 0) synchronized(configurations) { configurations[kind] = frame }
        output(frame)
    }
    private fun config(kind: Int, format: MediaFormat) {
        val csd = JSONArray()
        for (i in 0..2) {
            val b = format.getByteBuffer("csd-$i")?.duplicate() ?: break
            csd.put(Base64.encodeToString(ByteArray(b.remaining()).also { b.get(it) }, Base64.NO_WRAP))
        }
        val o = JSONObject().put("mime", if (kind == 1) "video/avc" else "audio/mp4a-latm").put("csd", csd)
        if (kind == 1) o.put("width", width).put("height", height).put("rotation", rotation)
        else o.put("sampleRate", 44100).put("channels", 1)
        emit(kind, Frame.CONFIG, 0, o.toString().toByteArray())
    }
    @SuppressLint("MissingPermission")
    private fun openVideo() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(CameraManager::class.java)
        val target = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == target }
            ?: manager.cameraIdList.first()
        val characteristics = manager.getCameraCharacteristics(id)
        val capabilities = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
            it.isEncoder && it.supportedTypes.any { mime -> mime.equals("video/avc", true) }
        }.getCapabilitiesForType("video/avc").videoCapabilities
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(MediaCodec::class.java)
        val size = sizes.filter { capabilities.isSizeSupported(it.width, it.height) && it.width <= 1280 && it.height <= 720 }
            .minByOrNull { abs(it.width - 854) + abs(it.height - 480) } ?: throw IllegalStateException("No supported video size")
        width = size.width; height = size.height
        rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video = MediaCodec.createEncoderByType("video/avc").apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        input = video!!.createInputSurface(); video!!.start()
        val currentEpoch = ++epoch
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (closed || currentEpoch != epoch) { device.close(); return }
                camera = device
                try {
                    val surface = input ?: return
                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (closed || currentEpoch != epoch) { s.close(); return }
                            session = s
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surface)
                                    val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                                    ranges.minByOrNull { abs(it.lower - 15) + abs(it.upper - 15) }?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                }.build()
                                s.setRepeatingRequest(request, null, h)
                                keyframe()
                            } catch (_: Exception) { error("Camera capture failed") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { error("Camera configuration failed") }
                    }, h)
                } catch (_: Exception) { error("Camera unavailable") }
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); error("Camera disconnected") }
            override fun onError(device: CameraDevice, code: Int) { device.close(); error("Camera unavailable") }
        }, h)
        h.post(object : Runnable {
            override fun run() {
                if (closed || currentEpoch != epoch) return
                try { video?.let { drain(it, 1) }; h.postDelayed(this, 10) }
                catch (_: Exception) { error("Video encoder stopped") }
            }
        })
    }
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0)
        recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
        check(recorder!!.state == AudioRecord.STATE_INITIALIZED)
        audio = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
            configure(MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 48000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        recorder!!.registerAudioRecordingCallback({ command -> h.post(command) }, recordingCallback)
        recorder!!.startRecording(); audioRunning = true
        audioPts = System.nanoTime() / 1000
        audioThread = Thread({
            val pcm = ByteArray(2048)
            try {
                while (audioRunning && !closed) {
                    val n = recorder?.read(pcm, 0, pcm.size) ?: break
                    if (n < 0) { if (audioRunning) error("Microphone unavailable"); break }
                    synchronized(audioLock) {
                        val codec = audio ?: return@synchronized
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            codec.getInputBuffer(index)!!.apply { clear(); put(pcm, 0, n) }
                            codec.queueInputBuffer(index, 0, n, audioPts, 0)
                        }
                        audioPts += (n / 2) * 1000000L / 44100
                        drain(codec, 2)
                    }
                }
            } catch (_: Exception) { if (!closed) error("Microphone encoder stopped") }
        }, "teacher-audio").apply { start() }
    }
    private fun drain(codec: MediaCodec, kind: Int) {
        val info = MediaCodec.BufferInfo()
        while (!closed) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { config(kind, codec.outputFormat); continue }
            if (index < 0) break
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val b = codec.getOutputBuffer(index)!!.duplicate().apply { position(info.offset); limit(info.offset + info.size) }
                emit(kind, if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) Frame.KEY else 0,
                    info.presentationTimeUs, ByteArray(info.size).also { b.get(it) })
            }
            codec.releaseOutputBuffer(index, false)
        }
    }
    private fun closeVideo() {
        epoch++; runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { video?.stop() }; runCatching { video?.release() }; video = null
        input?.release(); input = null
        synchronized(configurations) { configurations.remove(1) }
    }
    fun close() {
        if (closed) return
        closed = true; audioRunning = false
        runCatching { recorder?.stop() }
        h.post {
            audioThread?.join(1000)
            synchronized(audioLock) {
                runCatching { audio?.stop() }; runCatching { audio?.release() }; audio = null
                runCatching { recorder?.unregisterAudioRecordingCallback(recordingCallback) }
                runCatching { recorder?.release() }; recorder = null
            }
            closeVideo(); thread.quitSafely()
        }
    }
}
