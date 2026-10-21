package com.placeholder.hrvbridge

import android.os.Looper
import com.placeholder.hrvbridge.core.KeyCheck
import com.placeholder.hrvbridge.core.SessionState
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.time.Duration

/** The redesigned flow: connect, then Start; the strap stays connected; the hand; the key check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainFlowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val looper get() = shadowOf(Looper.getMainLooper())

    private fun connected(scenario: String = "Vector: clean 70 beats", speed: Int = 60): MeasurementController {
        val c = testController(app)
        c.connect(DebugStrap.create(app, scenario, speed))
        looper.idle()
        return c
    }

    @Test
    fun connectingDoesNotStartTheClock() {
        val c = connected()
        assertEquals(StrapState.Connected("Simulated strap"), c.strap)
        looper.idleFor(Duration.ofSeconds(1))
        assertNull(c.session)
        assertNotNull(c.heartRate)
    }

    @Test
    fun handSweepsWithTheClockAndRingBreathesOnlyWhileMeasuring() {
        val c = connected()
        c.setSettling(30)
        c.startMeasurement()
        assertTrue(c.session is SessionState.Stabilising)
        val settle = c.phaseClock!!
        assertEquals(0, settle.turn)
        assertEquals(30_000, settle.durationMs)
        var last = settle.angle(c.nowMs())
        var pulsesWhileSettling = 0
        var sawMeasuring = false
        repeat(400) {
            looper.idleFor(Duration.ofMillis(10))
            val clock = c.phaseClock ?: return@repeat
            val angle = clock.angle(c.nowMs())
            assertTrue("hand went back from $last to $angle", angle >= last)
            last = angle
            if (c.session is SessionState.Stabilising) pulsesWhileSettling = c.pulse.id
            if (c.session is SessionState.Measuring && angle in 360.1..719.9) sawMeasuring = true
        }
        assertEquals("no breathing while settling", 0, pulsesWhileSettling)
        assertTrue(sawMeasuring)
        assertTrue(c.session is SessionState.Finished)
        assertNull("done: the dial is static", c.phaseClock)
        // About one pulse per accepted beat of the 60 s measurement (clean vector, 60 bpm).
        assertTrue("pulses ${c.pulse.id}", c.pulse.id in 50..70)
        assertTrue(c.pulse.rrMs in 300.0..2000.0)
    }

    @Test
    fun ringBreathesFromThirtyFiveToFullOpacityOverOneBeat() {
        assertEquals(0.35f, com.placeholder.hrvbridge.ui.breath(0f), 0.001f)
        assertEquals(1f, com.placeholder.hrvbridge.ui.breath(0.25f), 0.001f)
        assertEquals(0.35f, com.placeholder.hrvbridge.ui.breath(1f), 0.001f)
    }

    @Test
    fun doneKeepsTheStrapAndRepeatMeasuresAgain() {
        val c = connected()
        c.setSettling(0)
        c.startMeasurement()
        looper.idleFor(Duration.ofSeconds(3))
        assertTrue(c.session is SessionState.Finished)
        c.finishMeasurement()
        assertNull(c.session)
        assertNull(c.phaseClock)
        assertEquals(StrapState.Connected("Simulated strap"), c.strap)
        c.measureAgain { error("strap is still connected") }
        // Settling 0 s: straight into the measurement (its first 10 s hold the check).
        assertTrue(c.session is SessionState.Measuring)
    }

    @Test
    fun settlingSettingIsRemembered() {
        testController(app).setSettling(90)
        assertEquals(90, testController(app).settlingSeconds)
    }

    @Test
    fun keyCheckAgainstLocalServer() {
        var status = 200
        val paths = ArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(paths) { paths.add(ex.requestMethod + " " + ex.requestURI.path) }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
        try {
            val c = testController(app, baseUrl = "http://127.0.0.1:${server.address.port}")
            assertEquals(SaveKeyResult.SAVED, c.saveApiKey("abc123"))
            c.checkKey()
            idleUntil { !c.checkingKey }
            assertEquals(KeyCheck.VALID, c.keyCheck)
            assertNotNull(c.credential)
            assertTrue(synchronized(paths) { paths.any { it.startsWith("GET /api/v1/athlete/0/wellness/") } })

            status = 401
            c.checkKey()
            idleUntil { !c.checkingKey }
            assertEquals(KeyCheck.REJECTED, c.keyCheck)
            assertNull(c.credential)
        } finally {
            server.stop(0)
        }
    }
}
