package com.placeholder.hrvbridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.core.FailReason
import com.placeholder.hrvbridge.core.SessionState

@Composable
fun MeasurementScreen(controller: MeasurementController) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        when (val s = controller.session) {
            null -> IdleContent(controller)
            is SessionState.Checking -> Running(controller, s.poorContact) {
                stringResource(R.string.checking, seconds(s.elapsedMs))
            }
            is SessionState.Stabilising -> Running(controller, s.poorContact) {
                stringResource(R.string.stabilising, seconds(s.remainingMs))
            }
            is SessionState.Measuring -> Running(controller, s.poorContact) {
                stringResource(R.string.measuring, seconds(s.remainingMs), s.beats)
            }
            is SessionState.Finished -> FinishedContent(controller, s)
            is SessionState.Failed -> {
                Text(stringResource(failText(s.reason)))
                Button(onClick = controller::reset) { Text(stringResource(R.string.try_again)) }
            }
        }
    }
}

/** Countdown seconds, rounded up so "1 s" shows until the phase ends. */
private fun seconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

private fun failText(reason: FailReason) = when (reason) {
    FailReason.NO_DATA -> R.string.fail_no_data
    FailReason.NO_RR -> R.string.fail_no_rr
    FailReason.IMPLAUSIBLE -> R.string.fail_implausible
    FailReason.DISCONNECTED -> R.string.fail_disconnected
}

@Composable
private fun IdleContent(controller: MeasurementController) {
    if (controller.cancelledInBackground) Text(stringResource(R.string.cancelled_in_background))
    if (!DebugStrap.AVAILABLE) {
        Text(stringResource(R.string.no_strap_yet))
        return
    }
    val context = LocalContext.current
    val scenarios = remember { DebugStrap.scenarios(context) }
    var selected by rememberSaveable { mutableStateOf(scenarios.firstOrNull()) }
    var speed by rememberSaveable { mutableIntStateOf(DebugStrap.speeds.first()) }

    Text(stringResource(R.string.sim_title), style = MaterialTheme.typography.titleMedium)
    for (name in scenarios) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .selectable(selected = name == selected, onClick = { selected = name }, role = Role.RadioButton),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = name == selected, onClick = null)
            Text(name, modifier = Modifier.padding(start = 8.dp))
        }
    }
    Text(stringResource(R.string.sim_speed))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (x in DebugStrap.speeds) {
            FilterChip(
                selected = x == speed,
                onClick = { speed = x },
                label = { Text(stringResource(R.string.sim_speed_value, x)) },
            )
        }
    }
    Button(
        enabled = selected != null,
        onClick = { selected?.let { controller.start(DebugStrap.create(context, it, speed)) } },
    ) { Text(stringResource(R.string.sim_start)) }
}

@Composable
private fun Running(controller: MeasurementController, poorContact: Boolean, status: @Composable () -> String) {
    Text(status(), style = MaterialTheme.typography.titleMedium)
    if (poorContact) Text(stringResource(R.string.poor_contact), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = controller::reset) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun FinishedContent(controller: MeasurementController, s: SessionState.Finished) {
    val r = s.result
    if (r == null) {
        Text(stringResource(R.string.result_none))
    } else {
        Text(stringResource(R.string.result_rhr, r.restingHr), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.result_rmssd, "%.1f".format(r.rmssdRounded)))
        Text(stringResource(R.string.result_sdnn, "%.1f".format(r.sdnnRounded)))
        Text(stringResource(R.string.result_beats, r.acceptedBeats, s.rrMs.size))
        if (!r.reliable) Text(stringResource(R.string.unreliable), color = MaterialTheme.colorScheme.error)
        when (val u = controller.upload) {
            UploadStatus.None -> if (!r.reliable) {
                OutlinedButton(onClick = controller::uploadAnyway) { Text(stringResource(R.string.upload_anyway)) }
            }
            UploadStatus.InProgress -> Text(stringResource(R.string.uploading))
            is UploadStatus.Done -> when (val res = u.result) {
                is UploadResult.Success -> {
                    Text(stringResource(R.string.uploaded))
                    // Only the dry-run uploader exists so far; show the request it built.
                    Text(stringResource(R.string.dry_run_note), style = MaterialTheme.typography.labelMedium)
                    Text(res.detail, style = MaterialTheme.typography.bodySmall)
                }
                is UploadResult.Failure -> Text(stringResource(R.string.upload_failed, res.detail))
            }
        }
    }
    Button(onClick = controller::reset) { Text(stringResource(R.string.done)) }
}
