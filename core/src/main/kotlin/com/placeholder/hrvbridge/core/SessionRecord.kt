package com.placeholder.hrvbridge.core

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One measurement as stored on the device (JSON), with the raw RR intervals
 * so results can be recomputed and support requests answered.
 */
data class SessionRecord(
    val startedAt: Instant,
    /** Local date of the wellness record. */
    val date: LocalDate,
    val config: SessionConfig,
    val series: FilteredSeries,
    val result: HrvResult?,
    val malformedPackets: Int,
    val uploadedAt: Instant?,
    /** The user tapped "Upload" (or "Upload anyway"); only such sessions are uploaded or retried. */
    val uploadOverride: Boolean = false,
    /** Morning questions answered for this measurement; retries send them too. */
    val answers: ReadinessAnswers? = null,
) {
    /** What the upload sends: the result's values plus any answers. */
    fun wellnessValues(): WellnessValues? = result?.let { WellnessValues.from(date, it).copy(answers = answers) }

    val fileName: String
        get() = "session-" + startedAt.truncatedTo(ChronoUnit.SECONDS).toString().replace(':', '-') + ".json"

    fun toJson(): String = Json.write(
        linkedMapOf(
            "version" to VERSION,
            "startedAt" to startedAt.toString(),
            "date" to date.toString(),
            "config" to linkedMapOf(
                "checkMs" to config.checkMs,
                "stabilisationMs" to config.stabilisationMs,
                "measurementMs" to config.measurementMs,
                "minRrMs" to config.filter.minRrMs,
                "maxRrMs" to config.filter.maxRrMs,
                "maxRelativeDeviation" to config.filter.maxRelativeDeviation,
                "medianWindow" to config.filter.medianWindow,
                "maxRemovedFraction" to config.gate.maxRemovedFraction,
                "minValidBeats" to config.gate.minValidBeats,
            ),
            "malformedPackets" to malformedPackets,
            "rrMs" to series.rrMs,
            "accepted" to series.accepted,
            "rejectReasons" to series.rejectReasons.map { it?.name },
            "result" to result?.let {
                linkedMapOf(
                    "meanNnMs" to it.meanNnMs,
                    "restingHr" to it.restingHr,
                    "rmssdMs" to it.rmssdMs,
                    "sdnnMs" to it.sdnnMs,
                    "rmssdRounded" to it.rmssdRounded,
                    "sdnnRounded" to it.sdnnRounded,
                    "acceptedBeats" to it.acceptedBeats,
                    "removedBeats" to it.removedBeats,
                    "validPairs" to it.validPairs,
                    "reliable" to it.reliable,
                )
            },
            "uploadedAt" to uploadedAt?.toString(),
            "uploadOverride" to uploadOverride,
            "answers" to answers?.let {
                linkedMapOf(
                    "soreness" to it.soreness,
                    "fatigue" to it.fatigue,
                    "motivation" to it.motivation,
                    "injury" to it.injury,
                    "sick" to it.sick,
                )
            },
        ),
    )

    companion object {
        const val VERSION = 1

        fun fromJson(text: String): SessionRecord {
            val root = Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("not an object")
            require((root["version"] as? Number)?.toInt() == VERSION) { "unsupported version ${root["version"]}" }
            val c = root["config"] as Map<*, *>
            val config = SessionConfig(
                checkMs = c.long("checkMs"),
                stabilisationMs = c.long("stabilisationMs"),
                measurementMs = c.long("measurementMs"),
                filter = ArtefactFilterConfig(
                    minRrMs = c.double("minRrMs"),
                    maxRrMs = c.double("maxRrMs"),
                    maxRelativeDeviation = c.double("maxRelativeDeviation"),
                    medianWindow = c.long("medianWindow").toInt(),
                ),
                gate = QualityGate(
                    maxRemovedFraction = c.double("maxRemovedFraction"),
                    minValidBeats = c.long("minValidBeats").toInt(),
                ),
            )
            val series = FilteredSeries(
                rrMs = (root["rrMs"] as List<*>).map { (it as Number).toDouble() },
                accepted = (root["accepted"] as List<*>).map { it as Boolean },
                rejectReasons = (root["rejectReasons"] as List<*>).map { r -> (r as String?)?.let { RejectReason.valueOf(it) } },
            )
            val result = (root["result"] as Map<*, *>?)?.let {
                HrvResult(
                    meanNnMs = it.double("meanNnMs"),
                    restingHr = it.long("restingHr").toInt(),
                    rmssdMs = it.double("rmssdMs"),
                    sdnnMs = it.double("sdnnMs"),
                    acceptedBeats = it.long("acceptedBeats").toInt(),
                    removedBeats = it.long("removedBeats").toInt(),
                    validPairs = it.long("validPairs").toInt(),
                    reliable = it["reliable"] as Boolean,
                )
            }
            return SessionRecord(
                startedAt = Instant.parse(root["startedAt"] as String),
                date = LocalDate.parse(root["date"] as String),
                config = config,
                series = series,
                result = result,
                malformedPackets = (root["malformedPackets"] as Number).toInt(),
                uploadedAt = (root["uploadedAt"] as String?)?.let(Instant::parse),
                uploadOverride = root["uploadOverride"] as Boolean? ?: false,
                answers = (root["answers"] as Map<*, *>?)?.let {
                    ReadinessAnswers.of(
                        soreness = (it["soreness"] as Number?)?.toInt(),
                        fatigue = (it["fatigue"] as Number?)?.toInt(),
                        motivation = (it["motivation"] as Number?)?.toInt(),
                        injury = (it["injury"] as Number?)?.toInt(),
                        sick = it["sick"] as Boolean?,
                    )
                },
            )
        }

        private fun Map<*, *>.double(k: String) = (this[k] as Number).toDouble()

        private fun Map<*, *>.long(k: String) = (this[k] as Number).toLong()
    }
}
