package com.placeholder.hrvbridge.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class IntervalsTest {
    private val date = LocalDate.of(2026, 10, 20)

    @Test
    fun wellnessUrlUsesLocalDateAndAthleteZero() {
        assertEquals("https://intervals.icu/api/v1/athlete/0/wellness/2026-10-20", Intervals.wellnessUrl(date))
        assertEquals("https://intervals.icu/api/v1/athlete/0/wellness/2026-01-05", Intervals.wellnessUrl(LocalDate.of(2026, 1, 5)))
        assertEquals("https://intervals.icu/api/v1/athlete/0/wellness-bulk", Intervals.WELLNESS_BULK_URL)
        assertEquals("https://intervals.icu/api/v1/disconnect-app", Intervals.DISCONNECT_URL)
    }

    @Test
    fun wellnessBodyHasOnlyTheThreeFields() {
        val v = WellnessValues(date, restingHr = 52, hrvRmssd = 68.4, hrvSdnn = 61.0)
        assertEquals("""{"restingHR":52,"hrv":68.4,"hrvSDNN":61.0}""", Intervals.wellnessBody(v))
    }

    @Test
    fun bulkBodyAddsDateId() {
        val a = WellnessValues(date, 52, 68.4, 61.2)
        val b = WellnessValues(date.plusDays(1), 50, 70.0, 55.5)
        assertEquals(
            """[{"id":"2026-10-20","restingHR":52,"hrv":68.4,"hrvSDNN":61.2},{"id":"2026-10-21","restingHR":50,"hrv":70.0,"hrvSDNN":55.5}]""",
            Intervals.bulkBody(listOf(a, b)),
        )
    }

    @Test
    fun valuesFromResultUseRoundedMetrics() {
        val r = HrvMetrics.compute(ArtefactFilter.filter(listOf(800.0, 850.0, 820.0, 880.0)))!!
        assertEquals(WellnessValues(date, 72, 48.3, 35.0), WellnessValues.from(date, r))
    }

    @Test
    fun authHeaders() {
        // base64("API_KEY:abc123")
        assertEquals("Basic QVBJX0tFWTphYmMxMjM=", Intervals.basicAuth("abc123"))
        assertEquals("Bearer tok", Intervals.bearer("tok"))
    }
}
