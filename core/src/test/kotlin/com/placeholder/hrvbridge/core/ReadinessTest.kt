package com.placeholder.hrvbridge.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Optional morning questions: Intervals.icu wellness fields 1 to 4 and a sick-day event. */
class ReadinessTest {
    private val day = LocalDate.of(2026, 10, 20)
    private val base = WellnessValues(day, 52, 68.4, 61.2)

    @Test
    fun onlyAnsweredFieldsAreSent() {
        assertEquals(
            """{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2,"soreness":2,"injury":1}""",
            Intervals.wellnessBody(base.copy(answers = ReadinessAnswers.of(soreness = 2, injury = 1))),
        )
        assertEquals(
            """[{"id":"2026-10-20","restingHR":52,"hrv":68.4,"hrvSDNN":61.2,"soreness":1,"fatigue":2,"motivation":3,"injury":4}]""",
            Intervals.bulkBody(listOf(base.copy(answers = ReadinessAnswers.of(1, 2, 3, 4, sick = true)))),
        )
        assertEquals("""{"restingHR":52,"hrv":68.4,"hrvSDNN":61.2}""", Intervals.wellnessBody(base.copy(answers = ReadinessAnswers())))
    }

    @Test
    fun answersOutsideOneToFourAreDropped() {
        val a = ReadinessAnswers.of(soreness = 0, fatigue = 5, motivation = 4)
        assertNull(a.soreness)
        assertNull(a.fatigue)
        assertEquals(4, a.motivation)
        assertFalse(a.isEmpty)
        assertTrue(ReadinessAnswers.of(sick = false).isEmpty)
    }

    @Test
    fun sickEventBody() {
        assertEquals(
            """{"category":"SICK","start_date_local":"2026-10-20T00:00:00","name":"Sick","uid":"hrvbridge-sick-2026-10-20"}""",
            Intervals.sickEventBody(day),
        )
        assertEquals("http://127.0.0.1:1/api/v1/athlete/0/events?upsertOnUid=true", Intervals.eventsUrl("http://127.0.0.1:1"))
    }

    @Test
    fun sickEventIsPostedToLocalServer() {
        var seen = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen = ex.requestMethod + " " + ex.requestURI + " " + ex.requestBody.readBytes().decodeToString()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        try {
            val client = IntervalsClient(baseUrl = "http://127.0.0.1:${server.address.port}")
            assertIs<ApiResult.Success>(client.postSickEvent(Credential.ApiKey("abc123"), day))
            assertEquals(
                """POST /api/v1/athlete/0/events?upsertOnUid=true {"category":"SICK","start_date_local":"2026-10-20T00:00:00","name":"Sick","uid":"hrvbridge-sick-2026-10-20"}""",
                seen,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun recordKeepsAnswers() {
        val s = ArtefactFilter.filter(List(50) { if (it % 2 == 0) 1000.0 else 1010.0 })
        val r = SessionRecord(Instant.parse("2026-10-20T05:30:00Z"), day, SessionConfig(), s, HrvMetrics.compute(s), 0, null)
        assertNull(SessionRecord.fromJson(r.toJson()).answers)
        val withAnswers = r.copy(answers = ReadinessAnswers.of(fatigue = 3, sick = true))
        val json = withAnswers.toJson()
        assertTrue(json.contains(""""answers":{"soreness":null,"fatigue":3,"motivation":null,"injury":null,"sick":true}"""), json)
        val back = SessionRecord.fromJson(json)
        assertEquals(withAnswers.answers, back.answers)
        assertEquals(json, back.toJson())
        assertEquals(withAnswers.answers, withAnswers.wellnessValues()!!.answers)
    }
}
