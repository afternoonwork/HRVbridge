package com.placeholder.hrvbridge.core

import java.time.LocalDate
import java.util.Base64

/**
 * Optional morning questions. Wellness fields use Intervals.icu's scale 1 to 4
 * (1 = best); [sick] true becomes a SICK calendar event. Unanswered ones are not sent.
 */
data class ReadinessAnswers(
    val soreness: Int? = null,
    val fatigue: Int? = null,
    val motivation: Int? = null,
    val injury: Int? = null,
    val sick: Boolean? = null,
) {
    /** Nothing that would be sent ("not sick" is not sent). */
    val isEmpty: Boolean
        get() = soreness == null && fatigue == null && motivation == null && injury == null && sick != true

    companion object {
        /** Values outside 1 to 4 are dropped. */
        fun of(soreness: Int? = null, fatigue: Int? = null, motivation: Int? = null, injury: Int? = null, sick: Boolean? = null) =
            ReadinessAnswers(valid(soreness), valid(fatigue), valid(motivation), valid(injury), sick)

        private fun valid(v: Int?) = v?.takeIf { it in 1..4 }
    }
}

/** Values for one day's wellness record. `date` is the user's local date. */
data class WellnessValues(
    val date: LocalDate,
    val restingHr: Int,
    val hrvRmssd: Double,
    val hrvSdnn: Double,
    /** Answered morning questions, sent in the same PUT. */
    val answers: ReadinessAnswers? = null,
) {
    companion object {
        fun from(date: LocalDate, result: HrvResult) =
            WellnessValues(date, result.restingHr, result.rmssdRounded, result.sdnnRounded)
    }
}

/** Intervals.icu wellness API request building. Athlete id 0 means the token's athlete. */
object Intervals {
    const val BASE_URL = "https://intervals.icu"
    const val WELLNESS_BULK_URL = "$BASE_URL/api/v1/athlete/0/wellness-bulk"
    const val DISCONNECT_URL = "$BASE_URL/api/v1/disconnect-app"

    /** PUT target; only supplied fields change, so retries are idempotent. */
    fun wellnessUrl(date: LocalDate, base: String = BASE_URL): String = "$base/api/v1/athlete/0/wellness/$date"

    fun wellnessBulkUrl(base: String = BASE_URL): String = "$base/api/v1/athlete/0/wellness-bulk"

    fun disconnectUrl(base: String = BASE_URL): String = "$base/api/v1/disconnect-app"

    fun wellnessBody(v: WellnessValues): String = "{${fields(v)}}"

    fun bulkBody(values: List<WellnessValues>): String =
        values.joinToString(",", "[", "]") { "{\"id\":${Json.quote(it.date.toString())},${fields(it)}}" }

    /** Development auth: personal API key with username API_KEY. */
    fun basicAuth(apiKey: String): String =
        "Basic " + Base64.getEncoder().encodeToString("API_KEY:$apiKey".toByteArray(Charsets.UTF_8))

    fun bearer(accessToken: String): String = "Bearer $accessToken"

    /** Calendar events; upsertOnUid makes a retry update the same event. */
    fun eventsUrl(base: String = BASE_URL): String = "$base/api/v1/athlete/0/events?upsertOnUid=true"

    /** Sick day: a calendar event (not a wellness field) with a fixed uid per date. */
    fun sickEventBody(date: LocalDate): String =
        "{\"category\":\"SICK\",\"start_date_local\":\"${date}T00:00:00\",\"name\":\"Sick\",\"uid\":\"hrvbridge-sick-$date\"}"

    // Unanswered questions are left out, so they do not change the record.
    private fun fields(v: WellnessValues) = buildString {
        append("\"restingHR\":${v.restingHr},\"hrv\":${v.hrvRmssd},\"hrvSDNN\":${v.hrvSdnn}")
        v.answers?.let { a ->
            listOf("soreness" to a.soreness, "fatigue" to a.fatigue, "motivation" to a.motivation, "injury" to a.injury)
                .forEach { (name, value) -> if (value != null) append(",\"$name\":$value") }
        }
    }
}
