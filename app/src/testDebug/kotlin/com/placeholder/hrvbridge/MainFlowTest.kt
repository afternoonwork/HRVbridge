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
    fun handOnlyMovesForwardAndParksAtTwelveWhenDone() {
        val c = connected()
        c.setSettling(30)
        c.startMeasurement()
        assertTrue(c.session is SessionState.Checking)
        var last = c.handAngle
        var sawSettling = false
        var sawMeasuring = false
        repeat(400) {
            looper.idleFor(Duration.ofMillis(10))
            assertTrue("hand went back from $last to ${c.handAngle}", c.handAngle >= last)
            last = c.handAngle
            if (c.session is SessionState.Stabilising && c.handAngle in 1.0..359.9) sawSettling = true
            if (c.session is SessionState.Measuring && c.handAngle in 360.1..719.9) sawMeasuring = true
        }
        assertTrue(sawSettling)
        assertTrue(sawMeasuring)
        assertTrue(c.session is SessionState.Finished)
        assertEquals(720.0, c.handAngle, 0.0)
        assertNotNull(c.lastBeatMs)
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
        assertEquals(0.0, c.handAngle, 0.0)
        assertEquals(StrapState.Connected("Simulated strap"), c.strap)
        c.measureAgain { error("strap is still connected") }
        assertTrue(c.session is SessionState.Checking)
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
