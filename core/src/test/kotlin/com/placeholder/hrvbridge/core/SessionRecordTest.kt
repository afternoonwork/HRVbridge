package com.placeholder.hrvbridge.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionRecordTest {
    private val rr = listOf(800.0, 850.0, 250.0, 820.0, 880.0, 999.0234375)
    private val series = ArtefactFilter.filter(rr)

    private fun record(uploadedAt: Instant? = null, uploadOverride: Boolean = false) = SessionRecord(
        startedAt = Instant.parse("2026-10-20T05:30:00Z"),
        date = LocalDate.of(2026, 10, 20),
        config = SessionConfig(),
        series = series,
        result = HrvMetrics.compute(series),
        malformedPackets = 1,
        uploadedAt = uploadedAt,
        uploadOverride = uploadOverride,
    )

    @Test
    fun roundTripsThroughJson() {
        val r = record(uploadedAt = Instant.parse("2026-10-20T05:33:10Z"))
        val back = SessionRecord.fromJson(r.toJson())
        assertEquals(r.copy(series = back.series), back)
        assertEquals(rr, back.series.rrMs)
        assertEquals(series.accepted, back.series.accepted)
        assertEquals(series.rejectReasons, back.series.rejectReasons)
    }

    @Test
    fun roundTripsUploadOverride() {
        assertEquals(true, SessionRecord.fromJson(record(uploadOverride = true).toJson()).uploadOverride)
        assertEquals(false, SessionRecord.fromJson(record().toJson()).uploadOverride)
    }

    @Test
    fun missingOverrideFieldMeansNoOverride() {
        // Files written before the field existed.
        val text = record().toJson().replace(",\"uploadOverride\":false", "")
        assertEquals(false, text.contains("uploadOverride"))
        assertEquals(false, SessionRecord.fromJson(text).uploadOverride)
    }

    @Test
    fun keepsRawRrUnrounded() {
        val text = record().toJson()
        assertEquals(true, text.contains("999.0234375"), text)
    }

    @Test
    fun storesRoundedUploadValuesNextToRawMetrics() {
        @Suppress("UNCHECKED_CAST")
        val m = (Json.parse(record().toJson()) as Map<String, Any?>)["result"] as Map<String, Any?>
        val r = HrvMetrics.compute(series)!!
        assertEquals(r.restingHr.toLong(), m["restingHr"])
        assertEquals(r.rmssdRounded, m["rmssdRounded"])
        assertEquals(r.sdnnRounded, m["sdnnRounded"])
        assertEquals(r.rmssdMs, m["rmssdMs"])
        assertEquals(false, m["reliable"])
    }

    @Test
    fun handlesMissingResultAndUpload() {
        val empty = ArtefactFilter.filter(emptyList())
        val r = record().copy(series = empty, result = null)
        val back = SessionRecord.fromJson(r.toJson())
        assertNull(back.result)
        assertNull(back.uploadedAt)
        assertEquals(emptyList(), back.series.rrMs)
    }

    @Test
    fun fileNameSortsChronologically() {
        assertEquals("session-2026-10-20T05-30-00Z.json", record().fileName)
    }

    @Test
    fun rejectsUnknownVersion() {
        val text = record().toJson().replace("\"version\":1", "\"version\":99")
        assertFailsWith<IllegalArgumentException> { SessionRecord.fromJson(text) }
    }
}
