package com.placeholder.hrvbridge.core

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionStoreTest {
    private val dir = Files.createTempDirectory("sessions").toFile()
    private val store = SessionStore(dir)

    private fun record(start: String, uploadedAt: Instant? = null): SessionRecord {
        val series = ArtefactFilter.filter(List(50) { if (it % 2 == 0) 1000.0 else 1010.0 })
        return SessionRecord(Instant.parse(start), LocalDate.parse(start.take(10)), SessionConfig(), series,
            HrvMetrics.compute(series), 0, uploadedAt)
    }

    @Test
    fun savesAndListsNewestFirst() {
        store.save(record("2026-10-19T05:30:00Z"))
        store.save(record("2026-10-20T05:30:00Z"))
        val all = store.list()
        assertEquals(listOf("2026-10-20T05:30:00Z", "2026-10-19T05:30:00Z"), all.map { it.startedAt.toString() })
        assertEquals(50, all[0].series.rrMs.size)
    }

    @Test
    fun saveOverwritesSameSession() {
        store.save(record("2026-10-20T05:30:00Z"))
        store.save(record("2026-10-20T05:30:00Z", uploadedAt = Instant.parse("2026-10-20T05:33:00Z")))
        assertEquals(1, store.list().size)
        assertEquals(Instant.parse("2026-10-20T05:33:00Z"), store.list()[0].uploadedAt)
        assertEquals(listOf("session-2026-10-20T05-30-00Z.json"), dir.list()!!.toList())
    }

    @Test
    fun skipsUnreadableFiles() {
        store.save(record("2026-10-20T05:30:00Z"))
        dir.resolve("session-garbage.json").writeText("{")
        dir.resolve("notes.txt").writeText("x")
        assertEquals(1, store.list().size)
    }

    @Test
    fun pendingUploadsAreThoseWithResultAndNoUploadTime() {
        store.save(record("2026-10-19T05:30:00Z", uploadedAt = Instant.parse("2026-10-19T05:33:00Z")))
        store.save(record("2026-10-20T05:30:00Z"))
        assertEquals(listOf(Instant.parse("2026-10-20T05:30:00Z")), store.pendingUploads().map { it.startedAt })
    }

    @Test
    fun unreliableSessionsAreNeverPending() {
        val series = ArtefactFilter.filter(listOf(1000.0, 1010.0, 990.0))
        store.save(SessionRecord(Instant.parse("2026-10-20T05:30:00Z"), LocalDate.parse("2026-10-20"), SessionConfig(),
            series, HrvMetrics.compute(series), 0, null))
        assertEquals(emptyList(), store.pendingUploads())
    }

    @Test
    fun overriddenUnreliableSessionIsPendingUntilUploaded() {
        val series = ArtefactFilter.filter(listOf(1000.0, 1010.0, 990.0))
        val r = SessionRecord(Instant.parse("2026-10-20T05:30:00Z"), LocalDate.parse("2026-10-20"), SessionConfig(),
            series, HrvMetrics.compute(series), 0, uploadedAt = null, uploadOverride = true)
        store.save(r)
        assertEquals(listOf(r.startedAt), store.pendingUploads().map { it.startedAt })
        store.save(r.copy(uploadedAt = Instant.parse("2026-10-20T05:34:00Z")))
        assertEquals(emptyList(), store.pendingUploads())
    }

    @Test
    fun sessionWithoutResultIsNeverPendingEvenWithOverride() {
        val series = ArtefactFilter.filter(emptyList())
        store.save(SessionRecord(Instant.parse("2026-10-20T05:30:00Z"), LocalDate.parse("2026-10-20"), SessionConfig(),
            series, null, 0, uploadedAt = null, uploadOverride = true))
        assertEquals(emptyList(), store.pendingUploads())
    }

    @Test
    fun retryBatchTakesNewestPendingSessionPerDate() {
        store.save(record("2026-10-18T05:30:00Z"))
        store.save(record("2026-10-19T05:30:00Z"))
        store.save(record("2026-10-19T06:10:00Z")) // repeat on the same morning wins
        assertEquals(
            listOf("2026-10-19T06:10:00Z", "2026-10-18T05:30:00Z"),
            store.retryBatch().map { it.startedAt.toString() },
        )
    }

    @Test
    fun retryBatchNeverOverwritesANewerUploadedValue() {
        store.save(record("2026-10-19T05:30:00Z"))
        store.save(record("2026-10-19T06:10:00Z", uploadedAt = Instant.parse("2026-10-19T06:13:00Z")))
        assertEquals(emptyList(), store.retryBatch())
    }

    @Test
    fun retryBatchSkipsNewestIfItIsNotUploadable() {
        // Newest of the day is unreliable without override: nothing for that day,
        // and the older reliable one must not overwrite what the user saw last.
        store.save(record("2026-10-19T05:30:00Z"))
        val series = ArtefactFilter.filter(listOf(1000.0, 1010.0, 990.0))
        store.save(SessionRecord(Instant.parse("2026-10-19T06:10:00Z"), LocalDate.parse("2026-10-19"), SessionConfig(),
            series, HrvMetrics.compute(series), 0, null))
        assertEquals(emptyList(), store.retryBatch())
    }

    @Test
    fun deleteAllRemovesOnlySessionFiles() {
        store.save(record("2026-10-19T05:30:00Z"))
        store.save(record("2026-10-20T05:30:00Z"))
        dir.resolve("session-garbage.json").writeText("{")
        dir.resolve("other.txt").writeText("keep")
        assertEquals(3, store.deleteAll())
        assertEquals(emptyList(), store.list())
        assertEquals(listOf("other.txt"), dir.list()!!.toList())
        assertEquals(0, SessionStore(dir.resolve("missing")).deleteAll())
    }

    @Test
    fun createsDirectoryOnDemand() {
        val nested = SessionStore(dir.resolve("a/b"))
        nested.save(record("2026-10-20T05:30:00Z"))
        assertTrue(dir.resolve("a/b").isDirectory)
    }
}
