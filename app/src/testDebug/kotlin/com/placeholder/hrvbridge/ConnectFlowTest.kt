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
        assertEquals(SessionState.Checking(0, false), c.session)
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

    @Test
    fun backgroundingWhileConnectingCancels() {
        val c = testController(app)
        c.start(FakeSource())
        c.onBackgrounded()
        assertTrue(c.cancelledInBackground)
        assertFalse(c.connecting)
    }
}
