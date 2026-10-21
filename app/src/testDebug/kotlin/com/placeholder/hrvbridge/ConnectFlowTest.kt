package com.placeholder.hrvbridge

import com.placeholder.hrvbridge.core.HeartRatePacketEncoder
import com.placeholder.hrvbridge.core.HeartRateSource
import com.placeholder.hrvbridge.core.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Controller behaviour around connecting, with a hand-driven source (no Bluetooth). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectFlowTest {
    private class FakeSource : HeartRateSource {
        var now = 0L
        var listener: HeartRateSource.Listener? = null
        var stopped = false
        override val description = "fake strap"

        override fun nowMs() = now

        override fun start(listener: HeartRateSource.Listener) {
            this.listener = listener
        }

        override fun stop() {
            stopped = true
        }
    }

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun clockStartsOnlyWhenConnected() {
        val c = testController(app)
        val src = FakeSource()
        c.start(src)
        assertTrue(c.connecting)
        assertTrue(c.isActive)
        assertNull(c.session)
        // Packets before subscription are logged but not measured.
        src.listener!!.onPacket(HeartRatePacketEncoder.encode(60, listOf(1024)), 500)
        src.now = 8_000 // scanning and connecting took 8 s
        src.listener!!.onConnected()
        assertFalse(c.connecting)
        // No separate check phase: settling starts at once (its first 10 s hold the check).
        assertEquals(SessionState.Stabilising(60_000, false), c.session)
    }

    @Test
    fun failureBeforeConnectIsConnectFailedNotSessionFailure() {
        val c = testController(app)
        val src = FakeSource()
        c.start(src)
        src.listener!!.onEvent("failed: no heart rate strap found in 20 s")
        src.listener!!.onDisconnected()
        assertTrue(c.connectFailed)
        assertFalse(c.connecting)
        assertFalse(c.isActive)
        assertNull(c.session)
        assertTrue(src.stopped)
        assertTrue(c.shareText(), c.shareText().contains("BLE failed: no heart rate strap found in 20 s"))
    }

    @Test
    fun cancelWhileConnectingStopsSource() {
        val c = testController(app)
        val src = FakeSource()
        c.start(src)
        c.reset()
        assertTrue(src.stopped)
        assertFalse(c.connecting)
        // Late callbacks from the old source are ignored.
        src.listener!!.onConnected()
        assertNull(c.session)
    }

    private class MutableClock(var now: java.time.Instant, private val zone: java.time.ZoneId) : java.time.Clock() {
        override fun getZone() = zone

        override fun withZone(zone: java.time.ZoneId) = MutableClock(now, zone)

        override fun instant() = now
    }

    @Test
    fun wellnessDateIsTheLocalDayTheMeasurementStarted() {
        // Starts 23:59 in Berlin on 14 Mar, finishes after midnight: the record is for 14 Mar.
        val clock = MutableClock(java.time.Instant.parse("2026-03-14T22:59:00Z"), java.time.ZoneId.of("Europe/Berlin"))
        val dir = java.nio.file.Files.createTempDirectory("sessions").toFile()
        val c = testController(app, dir, wallClock = clock)
        val src = FakeSource()
        c.start(src)
        src.listener!!.onConnected()
        clock.now = java.time.Instant.parse("2026-03-14T23:02:30Z")
        for (p in com.placeholder.hrvbridge.core.SimulatedStream.fromRr(List(200) { 1000.0 })) {
            src.now = p.timeMs
            src.listener!!.onPacket(p.bytes, p.timeMs)
        }
        assertTrue(c.session.toString(), c.session is SessionState.Finished)
        val stored = com.placeholder.hrvbridge.core.SessionStore(dir).list().single()
        assertEquals(java.time.LocalDate.of(2026, 3, 14), stored.date)
    }

    @Test
    fun backgroundingWhileConnectingCancels() {
        val c = testController(app)
        c.start(FakeSource())
        c.onBackgrounded()
        assertTrue(c.cancelledInBackground)
        assertFalse(c.connecting)
    }
}
