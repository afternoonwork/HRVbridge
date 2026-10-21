package com.placeholder.hrvbridge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.BeatPulse
import com.placeholder.hrvbridge.FoundStrap
import com.placeholder.hrvbridge.PhaseClock
import com.placeholder.hrvbridge.R
import com.placeholder.hrvbridge.core.FailReason

/** A finished measurement with a result, plus its upload state. */
data class ResultSummary(
    val restingHr: Int,
    val rmssd: Double,
    val sdnn: Double,
    val accepted: Int,
    val total: Int,
    val reliable: Boolean,
    val upload: Upload,
) {
    enum class Upload { NONE, IN_PROGRESS, UPLOADED, DRY_RUN, NOT_CONNECTED, RECONNECT, FAILED }
}

/** Everything the main screen shows, as a plain value (also used for the screenshot test). */
sealed interface MainPhase {
    data object NoStrap : MainPhase
    data class Searching(val found: List<FoundStrap>) : MainPhase
    data class Connecting(val name: String) : MainPhase
    data class Connected(val name: String) : MainPhase
    data class CouldNotConnect(val name: String) : MainPhase
    data object BluetoothOff : MainPhase
    data object NotAllowed : MainPhase
    data class Settling(val secondsLeft: Int, val poorContact: Boolean) : MainPhase
    data class Measuring(val secondsLeft: Int, val beats: Int, val poorContact: Boolean) : MainPhase
    data class Failed(val reason: FailReason) : MainPhase
    data class Result(val summary: ResultSummary) : MainPhase
    /** Morning questions on: the result waits for answers before it uploads. */
    data class Questions(val summary: ResultSummary) : MainPhase
    data object NotEnoughBeats : MainPhase
}

/** A string resource with arguments, resolved in composition. */
data class Txt(@StringRes val id: Int, val args: List<Any> = emptyList())

@Composable
fun Txt.resolve(): String = stringResource(id, *args.toTypedArray())

data class MainScreenState(
    val phase: MainPhase,
    val heartRate: Int? = null,
    /** Static hand angle (screenshots, and the value at the last update while live). */
    val handAngle: Double = 0.0,
    /** While measuring: the hand follows this clock every frame. */
    val handClock: PhaseClock? = null,
    /** While measuring: accepted beats, for the breathing ring. */
    val pulse: BeatPulse? = null,
    val settlingSeconds: Int = 60,
    val strapConnected: Boolean = false,
    /** The last measurement was cancelled because the app left the screen. */
    val cancelled: Boolean = false,
    /** Retry of earlier uploads on open: footnote under the strap row. */
    val retryUploaded: Int? = null,
    val retryLine: Txt? = null,
) {
    val isMeasuring get() = phase is MainPhase.Settling || phase is MainPhase.Measuring

    val poorContact get() = when (phase) {
        is MainPhase.Settling -> phase.poorContact
        is MainPhase.Measuring -> phase.poorContact
        else -> false
    }

    /** Moments with haptics (apart from "connected" and "poor contact", tracked separately). */
    enum class Milestone { RECORDING, FINISHED_RELIABLE, FINISHED_UNRELIABLE, UPLOADED, PROBLEM }

    val milestone: Milestone? get() = when (phase) {
        is MainPhase.Measuring -> Milestone.RECORDING
        is MainPhase.CouldNotConnect, is MainPhase.Failed -> Milestone.PROBLEM
        MainPhase.NotEnoughBeats -> Milestone.FINISHED_UNRELIABLE
        is MainPhase.Questions -> if (phase.summary.reliable) Milestone.FINISHED_RELIABLE else Milestone.FINISHED_UNRELIABLE
        is MainPhase.Result -> when (phase.summary.upload) {
            ResultSummary.Upload.UPLOADED -> Milestone.UPLOADED
            ResultSummary.Upload.FAILED, ResultSummary.Upload.RECONNECT -> Milestone.PROBLEM
            else -> if (phase.summary.reliable) Milestone.FINISHED_RELIABLE else Milestone.FINISHED_UNRELIABLE
        }
        else -> null
    }

    val dial: DialModel get() = when (phase) {
        MainPhase.NoStrap, is MainPhase.CouldNotConnect -> DialModel(label = "no strap", faint = true)
        is MainPhase.Searching -> DialModel(label = "searching", faint = true)
        is MainPhase.Connecting -> DialModel(label = "connecting", faint = true)
        MainPhase.BluetoothOff -> DialModel(label = "Bluetooth off", faint = true)
        MainPhase.NotAllowed -> DialModel(label = "no access", faint = true)
        is MainPhase.Connected -> DialModel(heartRate = heartRate, hand = DialModel.Hand.INK)
        is MainPhase.Settling -> DialModel.running(heartRate, handAngle, phase.poorContact)
        is MainPhase.Measuring -> DialModel.running(heartRate, handAngle, phase.poorContact)
        is MainPhase.Failed ->
            if (phase.reason == FailReason.NO_DATA || phase.reason == FailReason.DISCONNECTED || heartRate == null) {
                DialModel(label = "no strap", faint = true)
            } else {
                DialModel(heartRate = heartRate)
            }
        is MainPhase.Result -> phase.summary.let {
            DialModel(
                heartRate = it.restingHr, hand = if (it.reliable) DialModel.Hand.ACCENT else DialModel.Hand.INK,
                handAngle = 720.0, band = if (it.reliable) DialModel.Band.MEASURE else DialModel.Band.MUTED, bandAngle = 360.0,
            )
        }
        MainPhase.NotEnoughBeats -> DialModel(hand = DialModel.Hand.INK, handAngle = 720.0, band = DialModel.Band.MUTED, bandAngle = 360.0)
        is MainPhase.Questions -> DialModel(heartRate = phase.summary.restingHr)
    }

    /** Phase name, announced when it changes. */
    val phaseTitle: String get() = when (phase) {
        MainPhase.NoStrap -> "No strap connected"
        is MainPhase.Searching -> "Looking for heart rate straps"
        is MainPhase.Connecting -> "Connecting to ${phase.name}"
        is MainPhase.Connected -> "${phase.name} connected"
        is MainPhase.CouldNotConnect, MainPhase.BluetoothOff, MainPhase.NotAllowed, is MainPhase.Failed -> "Problem"
        is MainPhase.Settling -> "Settling"
        is MainPhase.Measuring -> "Recording"
        is MainPhase.Result -> if (phase.summary.reliable) "Finished" else "Finished, unreliable"
        MainPhase.NotEnoughBeats -> "Finished, not enough beats"
        is MainPhase.Questions -> "Morning questions"
    }

    /** The dial as one accessibility element. */
    val dialDescription: String get() = buildList {
        when (phase) {
            is MainPhase.Result -> add("Resting heart rate ${phase.summary.restingHr} beats per minute.")
            MainPhase.NotEnoughBeats -> add("No result.")
            else -> {
                val d = dial
                if (d.label != null) add("Heart rate dial, ${d.label}.") else heartRate?.let { add("Heart rate $it beats per minute.") }
            }
        }
        when (phase) {
            is MainPhase.Settling -> add("Settling, ${phase.secondsLeft} seconds left.")
            is MainPhase.Measuring -> add("Recording, ${phase.secondsLeft} seconds left.")
            else -> Unit
        }
    }.joinToString(" ")

    val warning: Txt? get() = when (phase) {
        is MainPhase.CouldNotConnect -> Txt(R.string.could_not_connect, listOf(phase.name))
        MainPhase.BluetoothOff -> Txt(R.string.bluetooth_off)
        MainPhase.NotAllowed -> Txt(R.string.bluetooth_not_allowed)
        is MainPhase.Failed -> Txt(failText(phase.reason))
        MainPhase.NotEnoughBeats -> Txt(R.string.result_none)
        is MainPhase.Result -> phase.summary.let {
            when (it.upload) {
                ResultSummary.Upload.NONE ->
                    if (it.reliable) null else Txt(R.string.unreliable, listOf(it.total - it.accepted, it.total))
                ResultSummary.Upload.NOT_CONNECTED -> Txt(R.string.upload_not_connected)
                ResultSummary.Upload.RECONNECT -> Txt(R.string.upload_reconnect)
                ResultSummary.Upload.FAILED -> Txt(R.string.upload_failed)
                else -> null
            }
        }
        else -> when {
            poorContact -> Txt(R.string.poor_contact)
            cancelled && phase is MainPhase.Connected -> Txt(R.string.cancelled_in_background)
            else -> null
        }
    }

    companion object {
        @StringRes
        fun failText(reason: FailReason) = when (reason) {
            FailReason.NO_DATA -> R.string.fail_no_data
            FailReason.NO_RR -> R.string.fail_no_rr
            FailReason.IMPLAUSIBLE -> R.string.fail_implausible
            FailReason.DISCONNECTED -> R.string.fail_disconnected
        }
    }
}

class MainActions(
    val findStrap: () -> Unit = {},
    val choose: (String) -> Unit = {},
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val done: () -> Unit = {},
    /** After a failed measurement, or "Repeat": a new measurement with the same strap. */
    val again: () -> Unit = {},
    /** "Upload to Intervals.icu" or "Upload anyway". */
    val submitUpload: () -> Unit = {},
    /** Morning questions: the answers, or null for Skip. */
    val submitAnswers: (com.placeholder.hrvbridge.core.ReadinessAnswers?) -> Unit = {},
    val turnOnBluetooth: () -> Unit = {},
    val openSettings: () -> Unit = {},
)

/** The main screen's top app bar: title, and the settings icon except while measuring. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(showSettings: Boolean, openSettings: () -> Unit) {
    val label = stringResource(R.string.settings)
    androidx.compose.material3.TopAppBar(
        title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium) },
        actions = {
            if (showSettings) {
                androidx.compose.material3.IconButton(
                    onClick = openSettings,
                    modifier = Modifier.semantics { contentDescription = label },
                ) { SlidersIcon(hrv.ink) }
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = hrv.bg, titleContentColor = hrv.ink),
    )
}

/** The main screen body (below the top app bar). The dial stays in place. */
@Composable
fun MainScreenContent(state: MainScreenState, actions: MainActions, modifier: Modifier = Modifier,
                      dialDescription: String = state.dialDescription, now: () -> Long = { 0 }) {
    if (state.phase is MainPhase.Questions) {
        QuestionsPage(submit = actions.submitAnswers, modifier = modifier)
        return
    }
    // Measured heights of everything but the dial, so the dial can shrink to keep
    // warnings and keys visible on small screens.
    var topHeight by remember { mutableIntStateOf(0) }
    var belowHeight by remember { mutableIntStateOf(0) }
    var keysHeight by remember { mutableIntStateOf(0) }
    // Heights of the step indicator, the tallest readout or result, and the result keys,
    // measured without placing them, so the dial keeps one size from Connected to the result.
    var stepHeight by remember { mutableIntStateOf(0) }
    var belowProbe by remember { mutableIntStateOf(0) }
    var keysProbe by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // While measuring the hand sweeps smoothly with the clock, every frame, also with
    // Remove animations (that scales animations, not the frame clock).
    var liveAngle by remember { mutableDoubleStateOf(state.handAngle) }
    LaunchedEffect(state.handClock) {
        val clock = state.handClock ?: return@LaunchedEffect
        while (true) {
            liveAngle = clock.angle(now())
            withFrameMillis { }
        }
    }
    val liveState = if (state.handClock != null) state.copy(handAngle = liveAngle) else state
    BoxWithConstraints(modifier.fillMaxSize().background(hrv.bg)) {
        val gap = if (state.phase is MainPhase.Result || state.phase == MainPhase.NotEnoughBeats) {
            Space.dialToMetrics
        } else {
            Space.dialToReadout - 12.dp
        }
        // The larger gap (result) and the reserves, so Start, phase changes and the result keep the size.
        val others = with(density) {
            (topHeight + maxOf(belowHeight, belowProbe + 16.dp.roundToPx()) +
                maxOf(keysHeight, keysProbe + (12.dp + Space.bottom).roundToPx())).toDp()
        } + 8.dp + Space.stepsToDial + Space.dialToMetrics
        val size = maxOf(160.dp, minOf(dialSize(maxWidth), maxHeight - others))
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.margin),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.padding(top = 8.dp).heightIn(min = maxOf(28.dp, with(density) { stepHeight.toDp() }))
                        .onSizeChanged { topHeight = it.height },
                    contentAlignment = Alignment.Center,
                ) {
                    Probe({ stepHeight = it }) { StepIndicator(Step.SETTLE) }
                    Top(state)
                }
                HeartDial(liveState.dial, size, Modifier.padding(top = Space.stepsToDial), pulse = state.pulse,
                          description = dialDescription)
                Box(Modifier.padding(top = gap).onSizeChanged { belowHeight = it.height }.padding(bottom = 16.dp)) {
                    Probe({ belowProbe = it }) {
                        Below(state.copy(phase = MainPhase.Settling(60, false)), actions)
                        Below(state.copy(phase = MainPhase.Measuring(60, 60, false)), actions)
                        Below(state.copy(phase = MainPhase.Result(probeResult(ResultSummary.Upload.UPLOADED))), actions)
                    }
                    Below(state, actions)
                }
            }
            Box(
                Modifier.fillMaxWidth().onSizeChanged { keysHeight = it.height }.padding(top = 12.dp, bottom = Space.bottom),
                contentAlignment = Alignment.Center,
            ) {
                Probe({ keysProbe = it }) {
                    Keys(state.copy(phase = MainPhase.Result(probeResult(ResultSummary.Upload.NONE))), actions)
                }
                Keys(state, actions)
            }
        }
    }
}

private fun probeResult(upload: ResultSummary.Upload) = ResultSummary(52, 68.4, 61.2, 64, 64, true, upload)

/** Measures [content] (children stacked) at the available width without placing it: no space, not drawn, no semantics. */
@Composable
private fun Probe(onHeight: (Int) -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.clearAndSetSemantics {}.layout { m, c ->
        onHeight(m.measure(c.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)).height)
        layout(0, 0) {}
    }) { content() }
}

@Composable
private fun Top(state: MainScreenState) {
    when (val p = state.phase) {
        MainPhase.NoStrap, is MainPhase.CouldNotConnect, MainPhase.BluetoothOff, MainPhase.NotAllowed ->
            StrapRow(state) { StatusLine(StatusMark.HOLLOW, stringResource(R.string.no_strap)) }
        is MainPhase.Searching -> StrapRow(state) { StatusLine(StatusMark.SPINNER, stringResource(R.string.searching)) }
        is MainPhase.Connecting -> StrapRow(state) { StatusLine(StatusMark.SPINNER, stringResource(R.string.connecting_to, p.name)) }
        is MainPhase.Connected -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.cancelled) state.warning?.let { Notice(it.resolve(), warning = true) }
            StrapRow(state) { StatusLine(StatusMark.INK, stringResource(R.string.connected), bold = p.name) }
        }
        is MainPhase.Settling -> StepIndicator(Step.SETTLE, if (p.poorContact) StepMark.HOLLOW else StepMark.INK)
        is MainPhase.Measuring -> StepIndicator(Step.MEASURE, if (p.poorContact) StepMark.HOLLOW else StepMark.ACCENT)
        is MainPhase.Failed -> StepIndicator(Step.SETTLE, StepMark.HOLLOW)
        is MainPhase.Result -> StepIndicator(
            Step.UPLOAD, if (p.summary.upload == ResultSummary.Upload.UPLOADED) StepMark.ACCENT else StepMark.HOLLOW,
        )
        MainPhase.NotEnoughBeats, is MainPhase.Questions -> StepIndicator(Step.UPLOAD, StepMark.HOLLOW)
    }
}

@Composable
private fun StrapRow(state: MainScreenState, line: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        line()
        val retry = state.retryUploaded?.let { pluralStringResource(R.plurals.retry_uploaded, it, it) } ?: state.retryLine?.resolve()
        retry?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = hrv.ink2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Below(state: MainScreenState, actions: MainActions) {
    when (val p = state.phase) {
        MainPhase.NoStrap -> Notice(stringResource(R.string.no_strap_hint))
        is MainPhase.Searching -> if (p.found.size >= 2) StrapList(p.found, actions.choose)
        is MainPhase.Connecting -> Unit
        is MainPhase.Connected -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.lie_still), style = MaterialTheme.typography.bodyMedium, color = hrv.ink)
            Text(
                if (state.settlingSeconds == 0) stringResource(R.string.plan_no_settling)
                else stringResource(R.string.plan_settling, state.settlingSeconds),
                style = MaterialTheme.typography.bodySmall, color = hrv.ink2, textAlign = TextAlign.Center,
            )
        }
        is MainPhase.CouldNotConnect, MainPhase.BluetoothOff, MainPhase.NotAllowed, is MainPhase.Failed, MainPhase.NotEnoughBeats ->
            state.warning?.let { Notice(it.resolve(), warning = true) }
        is MainPhase.Settling -> Readout(state, null, stringResource(R.string.phase_settling), p.secondsLeft,
            stringResource(R.string.phase_settling_detail), p.poorContact)
        is MainPhase.Measuring -> Readout(state, StatusMark.ACCENT, stringResource(R.string.phase_recording), p.secondsLeft,
            pluralStringResource(R.plurals.beats_recorded, p.beats, p.beats), p.poorContact)
        is MainPhase.Result -> Result(state, p.summary)
        is MainPhase.Questions -> Unit
    }
}

@Composable
private fun StrapList(found: List<FoundStrap>, choose: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(hrv.surface, RoundedCornerShape(12.dp))
            .border(1.dp, hrv.line, RoundedCornerShape(12.dp)),
    ) {
        found.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = hrv.line, modifier = Modifier.padding(start = 16.dp))
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button) { choose(s.address) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, color = hrv.ink, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.dbm, s.rssi), style = MaterialTheme.typography.bodySmall, color = hrv.ink2)
            }
        }
    }
}

@Composable
private fun Readout(state: MainScreenState, mark: StatusMark?, label: String, seconds: Int, detail: String, poor: Boolean) {
    if (poor) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {}) {
                mark?.let { StatusDot(it) }
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = hrv.ink)
                Text(stringResource(R.string.seconds, seconds), style = MaterialTheme.typography.bodyMedium, color = hrv.ink)
            }
            state.warning?.let { Notice(it.resolve(), warning = true) }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                mark?.let { StatusDot(it) }
                Text(label, style = MaterialTheme.typography.bodyMedium, color = hrv.ink)
            }
            Text(stringResource(R.string.seconds, seconds), style = Readout.timer, color = hrv.ink,
                 modifier = Modifier.semantics { contentDescription = "$seconds seconds left" })
            Text(detail, style = MaterialTheme.typography.bodySmall, color = hrv.ink2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Result(state: MainScreenState, r: ResultSummary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Metric("rMSSD", r.rmssd, dim = !r.reliable)
            Metric("SDNN", r.sdnn, dim = !r.reliable)
        }
        // The unreliable warning already says how many beats were discarded.
        if (r.reliable || r.upload != ResultSummary.Upload.NONE) {
            Text(stringResource(R.string.beats_used, r.accepted, r.total), style = MaterialTheme.typography.bodySmall, color = hrv.ink2)
        }
        when (r.upload) {
            ResultSummary.Upload.IN_PROGRESS -> StatusLine(StatusMark.SPINNER, stringResource(R.string.uploading))
            ResultSummary.Upload.UPLOADED -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusLine(StatusMark.ACCENT, stringResource(R.string.uploaded))
                Text("✓", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = hrv.ink)
            }
            ResultSummary.Upload.DRY_RUN -> StatusLine(StatusMark.HOLLOW, stringResource(R.string.dry_run_note))
            else -> state.warning?.let { Notice(it.resolve(), warning = true) }
        }
    }
}

@Composable
private fun Metric(name: String, value: Double, dim: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("%.1f".format(java.util.Locale.UK, value), style = Readout.value, color = if (dim) hrv.ink2 else hrv.ink)
            Text("ms", style = MaterialTheme.typography.bodySmall, color = if (dim) hrv.ink2 else hrv.ink,
                 modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(name, style = MaterialTheme.typography.labelMedium, color = hrv.ink2)
    }
}

@Composable
private fun Keys(state: MainScreenState, actions: MainActions) {
    when (val p = state.phase) {
        MainPhase.NoStrap -> PrimaryKey(stringResource(R.string.find_strap), actions.findStrap)
        is MainPhase.Searching, is MainPhase.Connecting -> Spacer(Modifier.size(56.dp))
        is MainPhase.Connected -> PrimaryKey(stringResource(R.string.start), actions.start, leading = {
            Spacer(Modifier.size(8.dp).background(hrv.accent, CircleShape))
        })
        is MainPhase.CouldNotConnect -> PrimaryKey(stringResource(R.string.try_again), actions.findStrap)
        MainPhase.BluetoothOff -> PrimaryKey(stringResource(R.string.turn_on_bluetooth), actions.turnOnBluetooth)
        MainPhase.NotAllowed -> PrimaryKey(stringResource(R.string.open_settings), actions.openSettings)
        is MainPhase.Settling, is MainPhase.Measuring -> SecondaryKey(stringResource(R.string.stop), actions.stop)
        is MainPhase.Failed -> if (p.reason == FailReason.NO_RR) {
            PrimaryKey(stringResource(R.string.done), actions.done)
        } else {
            PrimaryKey(stringResource(R.string.try_again), actions.again)
        }
        is MainPhase.Result -> when {
            p.summary.upload != ResultSummary.Upload.NONE -> PrimaryKey(stringResource(R.string.done), actions.done)
            p.summary.reliable ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PrimaryKey(stringResource(R.string.upload_to_intervals), actions.submitUpload)
                    TextKey(stringResource(R.string.close_without_uploading), actions.done)
                }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrimaryKey(stringResource(R.string.repeat), actions.again)
                TextKey(stringResource(R.string.upload_anyway), actions.submitUpload)
            }
        }
        MainPhase.NotEnoughBeats -> PrimaryKey(stringResource(R.string.repeat), actions.again)
        is MainPhase.Questions -> Unit
    }
}
