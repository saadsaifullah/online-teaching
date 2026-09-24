package com.onlineteachers.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Frame(val kind: Int, val flags: Int, val sequence: Int, val ptsUs: Long, val payload: ByteArray) {
    fun encode(): ByteArray {
        require(kind in 1..2 && flags in 0..3 && ptsUs >= 0 && payload.size in 1..MAX_PAYLOAD)
        return ByteBuffer.allocate(24 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x4f.toByte()).put(0x54.toByte()).put(1.toByte()).put(kind.toByte())
            .put(flags.toByte()).put(byteArrayOf(0, 0, 0)).putInt(sequence).putLong(ptsUs)
            .putInt(payload.size).put(payload).array()
    }
    companion object {
        const val MAX_PAYLOAD = 524288
        const val CONFIG = 1
        const val KEY = 2
        fun decode(bytes: ByteArray): Frame {
            require(bytes.size in 25..MAX_PAYLOAD + 24)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(b.get().toInt() == 0x4f && b.get().toInt() == 0x54 && b.get().toInt() == 1)
            val kind = b.get().toInt(); val flags = b.get().toInt()
            require(kind in 1..2 && flags in 0..3)
            require(b.get().toInt() == 0 && b.get().toInt() == 0 && b.get().toInt() == 0)
            val seq = b.int; val pts = b.long; val length = b.int
            require(pts >= 0 && length == b.remaining())
            return Frame(kind, flags, seq, pts, ByteArray(length).also { b.get(it) })
        }
    }
}

enum class MonitorState { OFFLINE, CONNECTING, READY, VIEWING, ERROR }
object ActivationPolicy {
    fun mayActivate(camera: Boolean, microphone: Boolean, notifications: Boolean, visible: Boolean) =
        camera && microphone && notifications && visible
}
