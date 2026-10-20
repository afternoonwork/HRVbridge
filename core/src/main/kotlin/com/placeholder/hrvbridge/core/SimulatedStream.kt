package com.placeholder.hrvbridge.core

import kotlin.math.roundToInt

/** A raw 0x2A37 notification and its arrival time (ms on the session clock). */
class TimedPacket(val timeMs: Long, val bytes: ByteArray)

/** Builds 0x2A37 Heart Rate Measurement packets, the inverse of [HeartRateParser]. */
object HeartRatePacketEncoder {
    fun msToRaw(ms: Double): Int = (ms * 1024.0 / 1000.0).roundToInt()

    fun encode(
        heartRate: Int,
        rrRaw: List<Int>,
        contactDetected: Boolean = true,
        rrPresent: Boolean = true,
    ): ByteArray {
        val uint16 = heartRate > 255
        var flags = 0x04 // contact feature supported
        if (uint16) flags = flags or 0x01
        if (contactDetected) flags = flags or 0x02
        if (rrPresent) flags = flags or 0x10
        val out = ArrayList<Byte>()
        out.add(flags.toByte())
        out.add(heartRate.toByte())
        if (uint16) out.add((heartRate shr 8).toByte())
        if (rrPresent) {
            for (raw in rrRaw) {
                out.add(raw.toByte())
                out.add((raw shr 8).toByte())
            }
        }
        return out.toByteArray()
    }
}

/** Synthetic sensor output, used by the simulated strap and by tests. */
object SimulatedStream {
    /**
     * One notification every [intervalMs], like real straps, carrying the RR
     * values of the beats that ended since the previous one (0, 1 or more).
     * Packets before [poorContactUntilMs] report poor contact.
     */
    fun fromRr(rrMs: List<Double>, intervalMs: Long = 1000, poorContactUntilMs: Long = 0): List<TimedPacket> {
        val packets = ArrayList<TimedPacket>()
        var beatEnd = 0.0
        var next = 0
        var heartRate = if (rrMs.isEmpty()) 60 else (60000.0 / rrMs[0]).roundToInt()
        var t = intervalMs
        while (next < rrMs.size) {
            val beats = ArrayList<Double>()
            while (next < rrMs.size && beatEnd + rrMs[next] <= t) {
                beatEnd += rrMs[next]
                beats.add(rrMs[next])
                next++
            }
            if (beats.isNotEmpty()) heartRate = (60000.0 / beats.average()).roundToInt()
            val bytes = HeartRatePacketEncoder.encode(
                heartRate = heartRate,
                rrRaw = beats.map(HeartRatePacketEncoder::msToRaw),
                contactDetected = t > poorContactUntilMs,
            )
            packets.add(TimedPacket(t, bytes))
            t += intervalMs
        }
        return packets
    }

    /** A sensor that never sends RR intervals (flags bit4 clear). */
    fun heartRateOnly(heartRate: Int, durationMs: Long, intervalMs: Long = 1000): List<TimedPacket> =
        (intervalMs..durationMs step intervalMs).map {
            TimedPacket(it, HeartRatePacketEncoder.encode(heartRate, emptyList(), rrPresent = false))
        }
}
