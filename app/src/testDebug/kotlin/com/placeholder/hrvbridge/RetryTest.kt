package com.placeholder.hrvbridge

import com.placeholder.hrvbridge.core.ArtefactFilter
import com.placeholder.hrvbridge.core.Credential
import com.placeholder.hrvbridge.core.HrvMetrics
import com.placeholder.hrvbridge.core.SessionConfig
import com.placeholder.hrvbridge.core.SessionRecord
import com.placeholder.hrvbridge.core.SessionStore
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.time.Instant
import java.time.LocalDate

/** Retry of pending sessions on app open, against a local server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RetryTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var server: HttpServer
    private val requests = ArrayList<Pair<String, String>>()
    private var status = 200
    private val dir = Files.createTempDirectory("sessions").toFile()
    private val store = SessionStore(dir)
    private val keys = InMemoryKeyProvider()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(requests) { requests.add(ex.requestMethod + " " + ex.requestURI to ex.requestBody.readBytes().decodeToString()) }
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun session(start: String, beats: Int = 50, uploadedAt: Instant? = null): SessionRecord {
        val s = ArtefactFilter.filter(List(beats) { if (it % 2 == 0) 1000.0 else 1020.0 })
        return SessionRecord(Instant.parse(start), LocalDate.parse(start.take(10)), SessionConfig(), s,
            HrvMetrics.compute(s), 0, uploadedAt)
    }

    private fun connected() = testCredentialStore(app, keys).also { it.save(Credential.ApiKey("abc123")) }

    private fun open(credentials: CredentialStore = connected()): MeasurementController {
        val c = testController(app, dir, credentials, baseUrl = "http://127.0.0.1:${server.address.port}")
        idleUntil { c.retry != RetryStatus.InProgress }
        return c
    }

    @Test
    fun opensAndUploadsPendingSessionsInOneBulkPut() {
        store.save(session("2026-10-18T05:30:00Z"))
        store.save(session("2026-10-19T05:30:00Z"))
        store.save(session("2026-10-17T05:30:00Z", uploadedAt = Instant.parse("2026-10-17T05:33:00Z")))
        val c = open()
        assertEquals(RetryStatus.Uploaded(2), c.retry)
        val (line, body) = synchronized(requests) { requests.single() }
        assertEquals("PUT /api/v1/athlete/0/wellness-bulk", line)
        assertTrue(body, body.startsWith("[{\"id\":\"2026-10-19\",\"restingHR\":59,"))
        assertTrue(body, body.contains("{\"id\":\"2026-10-18\""))
        assertTrue(store.pendingUploads().isEmpty())
        assertNotNull(store.list().first { it.date.toString() == "2026-10-18" }.uploadedAt)
    }

    @Test
    fun unauthorisedKeepsSessionsPendingAndAsksToReconnect() {
        status = 401
        store.save(session("2026-10-19T05:30:00Z"))
        val c = open()
        assertEquals(RetryStatus.Failed(reconnect = true), c.retry)
        assertEquals(1, store.pendingUploads().size)
    }

    @Test
    fun serverErrorKeepsSessionsPending() {
        status = 500
        store.save(session("2026-10-19T05:30:00Z"))
        assertEquals(RetryStatus.Failed(reconnect = false), open().retry)
        assertEquals(1, store.pendingUploads().size)
    }

    @Test
    fun nothingPendingOrNotConnectedSendsNothing() {
        assertEquals(RetryStatus.None, open().retry)
        store.save(session("2026-10-19T05:30:00Z"))
        assertEquals(RetryStatus.None, open(testCredentialStore(app, InMemoryKeyProvider())).retry)
        assertEquals(0, synchronized(requests) { requests.size })
    }

    @Test
    fun unreliableWithoutOverrideIsNotRetried() {
        store.save(session("2026-10-19T05:30:00Z", beats = 10))
        assertEquals(RetryStatus.None, open().retry)
    }

    @Test
    fun savingAKeyRetriesStraightAway() {
        store.save(session("2026-10-19T05:30:00Z"))
        val c = open(testCredentialStore(app, InMemoryKeyProvider()))
        assertEquals(RetryStatus.None, c.retry)
        assertEquals(SaveKeyResult.SAVED, c.saveApiKey("abc123"))
        idleUntil { c.retry != RetryStatus.InProgress }
        assertEquals(RetryStatus.Uploaded(1), c.retry)
        assertNull(store.pendingUploads().firstOrNull())
    }
}
