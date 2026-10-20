package com.placeholder.hrvbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.placeholder.hrvbridge.core.HeartRateSource
import com.placeholder.hrvbridge.core.Json
import com.placeholder.hrvbridge.core.SimulatedStream
import com.placeholder.hrvbridge.core.TimedPacket

/**
 * Debug builds only: a simulated strap replaying test-vectors.json (packaged
 * into debug assets from :core) as raw 0x2A37 packets. The release source set
 * has a stub with the same API and no simulator.
 */
object DebugStrap {
    const val AVAILABLE = true
    val speeds = listOf(1, 10, 60)

    /** Enough data for check, the longest stabilisation (90 s) and the measurement. */
    private const val COVER_MS = 180_000.0
    private const val MIN_BEATS = 30

    private class Scenario(val name: String, val disconnectAtMs: Long? = null, val packets: () -> List<TimedPacket>)

    private var cache: List<Scenario>? = null

    fun scenarios(context: Context): List<String> = load(context).map { it.name }

    fun create(context: Context, scenario: String, speed: Int): HeartRateSource {
        val s = load(context).first { it.name == scenario }
        return SimulatedStrap("Simulated strap: $scenario at ${speed}x", s.packets(), speed.toDouble(), s.disconnectAtMs)
    }

    private fun load(context: Context): List<Scenario> = cache ?: build(context).also { cache = it }

    @Suppress("UNCHECKED_CAST")
    private fun build(context: Context): List<Scenario> {
        val text = context.assets.open("test-vectors.json").bufferedReader().use { it.readText() }
        val series = (Json.parse(text) as Map<String, Any?>)["series"] as List<Map<String, Any?>>
        val vectors = series.mapNotNull { v ->
            val rr = (v["rrMs"] as List<Any?>).map { (it as Number).toDouble() }
            if (rr.size < MIN_BEATS) return@mapNotNull null
            Scenario("Vector: ${v["name"]}") { SimulatedStream.fromRr(loop(rr)) }
        }
        val clean = series.first { it["name"] == "clean 70 beats" }["rrMs"] as List<Any?>
        val cleanRr = clean.map { (it as Number).toDouble() }
        return vectors + listOf(
            Scenario("Poor contact for the first 15 s") { SimulatedStream.fromRr(loop(cleanRr), poorContactUntilMs = 15_000) },
            Scenario("Strap disconnects after 30 s", disconnectAtMs = 30_000) { SimulatedStream.fromRr(loop(cleanRr)) },
            Scenario("Sensor without beat-to-beat data") { SimulatedStream.heartRateOnly(60, COVER_MS.toLong()) },
        )
    }

    private fun loop(rr: List<Double>): List<Double> {
        val out = ArrayList<Double>()
        while (out.sum() < COVER_MS) out.addAll(rr)
        return out
    }
}

/** Replays packets on the main thread at [speed] times real time. */
private class SimulatedStrap(
    override val description: String,
    private val packets: List<TimedPacket>,
    private val speed: Double,
    private val disconnectAtMs: Long?,
) : HeartRateSource {
    private val handler = Handler(Looper.getMainLooper())
    private var realStart = SystemClock.elapsedRealtime()
    private var next = 0
    private var listener: HeartRateSource.Listener? = null

    override fun nowMs(): Long = ((SystemClock.elapsedRealtime() - realStart) * speed).toLong()

    override fun start(listener: HeartRateSource.Listener) {
        this.listener = listener
        realStart = SystemClock.elapsedRealtime()
        next = 0
        listener.onEvent("simulated: connected, ${packets.size} packets queued")
        listener.onConnected()
        schedule()
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    private fun schedule() {
        val l = listener ?: return
        val due = packets.getOrNull(next)?.timeMs
        if (disconnectAtMs != null && (due == null || due > disconnectAtMs)) {
            handler.postDelayed({ if (listener === l) l.onDisconnected() }, delayUntil(disconnectAtMs))
            return
        }
        if (due == null) return // Out of data: like a strap that stopped sending.
        handler.postDelayed({
            if (listener !== l) return@postDelayed
            val p = packets[next++]
            l.onPacket(p.bytes, p.timeMs)
            schedule()
        }, delayUntil(due))
    }

    private fun delayUntil(virtualMs: Long): Long = ((virtualMs - nowMs()) / speed).toLong().coerceAtLeast(0)
}
