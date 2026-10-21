package com.placeholder.hrvbridge

import android.os.Looper
import com.placeholder.hrvbridge.core.ReadinessAnswers
import com.placeholder.hrvbridge.core.SessionStore
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration

/** Morning questions on: the result waits, then values, answers and the sick event go up together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadinessFlowTest {
    private val app = RuntimeEnvironment.getApplication()
    private val requests = ArrayList<String>()
    private var status = 200
    private lateinit var server: HttpServer
    private val dir = Files.createTempDirectory("sessions").toFile()
    private val credentials by lazy { testCredentialStore(app) }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(requests) { requests.add(ex.requestMethod + " " + ex.requestURI + " " + ex.requestBody.readBytes().decodeToString()) }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun measured(): MeasurementController {
        val c = testController(app, dir, credentials, baseUrl = "http://127.0.0.1:${server.address.port}")
        c.saveApiKey("abc123")
        c.enableQuestions(true)
        c.start(DebugStrap.create(app, "Vector: clean 70 beats", 60))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        return c
    }

    @Test
    fun answersAndSickEventUploadTogether() {
        val c = measured()
        assertEquals(UploadStatus.AwaitingAnswers, c.upload)
        assertEquals(0, synchronized(requests) { requests.count { it.startsWith("PUT /api/v1/athlete/0/wellness/") } })
        c.submitAnswers(ReadinessAnswers.of(soreness = 2, motivation = 1, sick = true))
        idleUntil { c.upload is UploadStatus.Done }
        assertTrue(c.upload.toString(), (c.upload as UploadStatus.Done).result is UploadResult.Success)
        val sent = synchronized(requests) { requests.toList() }
        val put = sent.single { it.startsWith("PUT /api/v1/athlete/0/wellness/") }
        assertTrue(put, put.endsWith(""""restingHR":60,"hrv":17.6,"hrvSDNN":12.5,"soreness":2,"motivation":1}""") ||
            put.contains(""""soreness":2,"motivation":1}"""))
        val event = sent.single { it.startsWith("POST /api/v1/athlete/0/events?upsertOnUid=true ") }
        assertTrue(event, event.contains(""""category":"SICK"""") && event.contains(""""uid":"hrvbridge-sick-"""))
        assertTrue(sent.indexOf(put) < sent.indexOf(event))
        // Stored with the session.
        assertEquals(ReadinessAnswers.of(soreness = 2, motivation = 1, sick = true), SessionStore(dir).list().single().answers)
    }

    @Test
    fun skipUploadsValuesOnly() {
        val c = measured()
        c.submitAnswers(null)
        idleUntil { c.upload is UploadStatus.Done }
        val sent = synchronized(requests) { requests.toList() }
        assertTrue(sent.single { it.startsWith("PUT ") }.contains(""""hrvSDNN":"""))
        assertTrue(sent.none { it.contains("soreness") || it.startsWith("POST ") })
    }

    @Test
    fun retrySendsAnswersAndSickEvent() {
        status = 500
        val c = measured()
        c.submitAnswers(ReadinessAnswers.of(fatigue = 3, sick = true))
        idleUntil { c.upload is UploadStatus.Done }
        status = 200
        synchronized(requests) { requests.clear() }
        val again = testController(app, dir, credentials, baseUrl = "http://127.0.0.1:${server.address.port}")
        idleUntil { again.retry != RetryStatus.InProgress && again.retry != RetryStatus.None }
        assertEquals(RetryStatus.Uploaded(1), again.retry)
        val sent = synchronized(requests) { requests.toList() }
        assertTrue(sent.toString(), sent.single { it.startsWith("PUT /api/v1/athlete/0/wellness-bulk") }.contains(""""fatigue":3"""))
        assertTrue(sent.toString(), sent.any { it.startsWith("POST /api/v1/athlete/0/events?upsertOnUid=true") })
    }
}
