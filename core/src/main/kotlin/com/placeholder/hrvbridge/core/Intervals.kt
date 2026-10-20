package com.placeholder.hrvbridge.core

import java.time.LocalDate
import java.util.Base64

/** Values for one day's wellness record. `date` is the user's local date. */
data class WellnessValues(
    val date: LocalDate,
    val restingHr: Int,
    val hrvRmssd: Double,
    val hrvSdnn: Double,
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

    private fun fields(v: WellnessValues) =
        "\"restingHR\":${v.restingHr},\"hrv\":${v.hrvRmssd},\"hrvSDNN\":${v.hrvSdnn}"
}
