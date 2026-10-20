package com.placeholder.hrvbridge

import android.os.Looper
import com.placeholder.hrvbridge.core.FailReason
import com.placeholder.hrvbridge.core.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate

/** Drives the controller with the debug simulated strap on a fake main looper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SimulatedMeasurementTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun run(scenario: String, speed: Int, realSeconds: Long): MeasurementController {
        val c = MeasurementController()
        c.start(DebugStrap.create(context, scenario, speed))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(realSeconds))
        return c
    }

    @Test
    fun cleanVectorAtSixtyTimesSpeedUploadsAutomatically() {
        // 10 s check + 60 s stabilisation + 60 s measurement = 130 s virtual, about 2.2 s real.
        val c = run("Vector: clean 70 beats", speed = 60, realSeconds = 3)
        val done = c.session as SessionState.Finished
        assertTrue(done.result!!.reliable)
        assertEquals(60, done.result!!.restingHr)
        val upload = (c.upload as UploadStatus.Done).result as UploadResult.Success
        assertTrue(upload.detail, upload.detail.contains("/wellness/${LocalDate.now()}"))
        assertTrue(upload.detail, upload.detail.contains("\"restingHR\":60"))
    }

    @Test
    fun realTimeSpeedIsStillMeasuringAfterOneMinute() {
        val c = run("Vector: clean 70 beats", speed = 1, realSeconds = 75)
        val m = c.session as SessionState.Measuring
        assertEquals(55_000.0, m.remainingMs.toDouble(), 1_000.0)
        assertTrue(c.isActive)
    }

    @Test
    fun unreliableMeasurementWaitsForOverride() {
        val c = run("Vector: more than 5 percent removed", speed = 60, realSeconds = 3)
        val done = c.session as? SessionState.Finished ?: error("state: ${c.session}")
        assertFalse(done.result!!.reliable)
        assertEquals(UploadStatus.None, c.upload)
        c.uploadAnyway()
        assertTrue((c.upload as UploadStatus.Done).result is UploadResult.Success)
    }

    @Test
    fun sensorWithoutRrIsRefused() {
        val c = run("Sensor without beat-to-beat data", speed = 60, realSeconds = 1)
        assertEquals(SessionState.Failed(FailReason.NO_RR), c.session)
    }

    @Test
    fun disconnectFailsTheSession() {
        val c = run("Strap disconnects after 30 s", speed = 60, realSeconds = 1)
        assertEquals(SessionState.Failed(FailReason.DISCONNECTED), c.session)
    }

    @Test
    fun poorContactDelaysTheClock() {
        val c = MeasurementController()
        c.start(DebugStrap.create(context, "Poor contact for the first 15 s", 10))
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(1_200)) // 12 s virtual
        assertEquals(true, (c.session as SessionState.Checking).poorContact)
        looper.idleFor(Duration.ofMillis(2_000)) // 32 s virtual: check ran 16..26 s, now settling
        assertTrue(c.session.toString(), c.session is SessionState.Stabilising)
    }

    @Test
    fun backgroundingCancels() {
        val c = MeasurementController()
        c.start(DebugStrap.create(context, "Vector: clean 70 beats", 10))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        c.onBackgrounded()
        assertEquals(null, c.session)
        assertTrue(c.cancelledInBackground)
    }

    @Test
    fun everyScenarioLoads() {
        val names = DebugStrap.scenarios(context)
        assertTrue(names.toString(), names.size >= 8)
        for (n in names) DebugStrap.create(context, n, 60)
    }
}
