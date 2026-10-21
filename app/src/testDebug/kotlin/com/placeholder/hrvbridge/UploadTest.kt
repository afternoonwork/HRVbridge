package com.placeholder.hrvbridge

import com.placeholder.hrvbridge.core.Credential
import com.placeholder.hrvbridge.core.SessionState
import com.placeholder.hrvbridge.core.SessionStore
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.LocalDate

/** Full pipeline with a stored API key, uploading to a local server (never Intervals.icu). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var server: HttpServer
    private val requests = ArrayList<Triple<String, String, String?>>()
    private var status = 200
    private val dir = Files.createTempDirectory("sessions").toFile()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(requests) {
                requests.add(Triple(ex.requestMethod + " " + ex.requestURI, ex.requestBody.readBytes().decodeToString(),
                    ex.requestHeaders.getFirst("Authorization")))
            }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun measure(): MeasurementController {
        val c = testController(app, dir, baseUrl = "http://127.0.0.1:${server.address.port}")
        assertEquals(SaveKeyResult.SAVED, c.saveApiKey("  abc123  "))
        c.start(DebugStrap.create(app, "Vector: clean 70 beats", 60))
        idleUntil { c.session is SessionState.Finished }
        // Nothing is sent or pending until the user taps "Upload to Intervals.icu".
        assertEquals(UploadStatus.None, c.upload)
        assertEquals(emptyList<Any>(), SessionStore(dir).pendingUploads())
        synchronized(requests) { assertTrue(requests.isEmpty()) }
        c.submitUpload()
        idleUntil { (c.upload as? UploadStatus.Done) != null }
        return c
    }

    @Test
    fun uploadsWithApiKeyAndMarksSessionUploaded() {
        val c = measure()
        assertTrue(c.session is SessionState.Finished)
        assertEquals(UploadStatus.Done(UploadResult.Success("HTTP 200")), c.upload)
        val (line, body, auth) = synchronized(requests) { requests.single() }
        assertEquals("PUT /api/v1/athlete/0/wellness/${LocalDate.now()}", line)
        assertTrue(body, body.startsWith("{\"restingHR\":60,\"hrv\":"))
        assertEquals("Basic QVBJX0tFWTphYmMxMjM=", auth)
        assertNotNull(SessionStore(dir).list().single().uploadedAt)
    }

    @Test
    fun unauthorisedAsksToReconnectAndKeepsSessionPending() {
        status = 401
        val c = measure()
        val r = (c.upload as UploadStatus.Done).result as UploadResult.Failure
        assertTrue(r.reconnect)
        assertNull(SessionStore(dir).list().single().uploadedAt)
        assertEquals(1, SessionStore(dir).pendingUploads().size)
    }

    @Test
    fun sharedLogNeverContainsTheKey() {
        val c = measure()
        // Even a careless log line is redacted.
        c.log.add("TEST", "abc123 QVBJX0tFWTphYmMxMjM=")
        val text = c.shareText()
        assertTrue(text, text.contains("HTTP PUT /api/v1/athlete/0/wellness/"))
        assertFalse(text, text.contains("abc123"))
        assertFalse(text, text.contains("QVBJX0tFWTphYmMxMjM="))
    }

    @Test
    fun disconnectForgetsApiKey() {
        val c = testController(app, dir)
        c.saveApiKey("abc123")
        assertEquals(Credential.ApiKey("abc123"), c.credential)
        c.disconnect()
        assertNull(c.credential)
        assertNull(testController(app, dir).credential)
    }

    @Test
    fun blankKeyIsRejected() {
        assertEquals(SaveKeyResult.BLANK, testController(app, dir).saveApiKey("   "))
    }

    @Test
    fun keystoreFailureOnSaveIsReportedNotThrown() {
        val broken = object : KeyProvider {
            override fun getOrCreateKey() = throw java.security.ProviderException("keystore")

            override fun deleteKey() = Unit
        }
        val c = testController(app, dir, credentials = testCredentialStore(app, broken))
        assertEquals(SaveKeyResult.STORAGE_FAILED, c.saveApiKey("abc123"))
        assertNull(c.credential)
    }

    @Test
    fun failedOverrideUploadStaysPending() {
        status = 500
        val c = testController(app, dir, baseUrl = "http://127.0.0.1:${server.address.port}")
        c.saveApiKey("abc123")
        c.start(DebugStrap.create(app, "Vector: more than 5 percent removed", 60))
        idleUntil { c.session is SessionState.Finished }
        assertEquals(UploadStatus.None, c.upload)
        assertEquals(emptyList<Any>(), SessionStore(dir).pendingUploads())
        c.submitUpload()
        idleUntil { c.upload is UploadStatus.Done }
        assertTrue((c.upload as UploadStatus.Done).result is UploadResult.Failure)
        val stored = SessionStore(dir).list().single()
        assertTrue(stored.uploadOverride)
        assertEquals(listOf(stored.startedAt), SessionStore(dir).pendingUploads().map { it.startedAt })
    }
}
