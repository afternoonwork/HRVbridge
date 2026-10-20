package com.placeholder.hrvbridge

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.core.Credential
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
            null -> if (controller.connecting) {
                Text(stringResource(R.string.connecting), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = controller::reset) { Text(stringResource(R.string.cancel)) }
            } else if (controller.credential == null) {
                // Not connected yet: connecting comes first.
                ConnectionSection(controller)
                HorizontalDivider()
                IdleContent(controller)
            } else {
                IdleContent(controller)
                HorizontalDivider()
                ConnectionSection(controller)
            }
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
                ShareLogButton(controller)
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
    when (val r = controller.retry) {
        RetryStatus.None -> Unit
        RetryStatus.InProgress -> Text(stringResource(R.string.retry_in_progress))
        is RetryStatus.Uploaded -> Text(pluralStringResource(R.plurals.retry_uploaded, r.count, r.count))
        is RetryStatus.Failed -> Text(
            stringResource(if (r.reconnect) R.string.upload_reconnect else R.string.retry_failed),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (controller.connectFailed) {
        Text(stringResource(R.string.connect_failed), color = MaterialTheme.colorScheme.error)
        ShareLogButton(controller)
    }
    val context = LocalContext.current
    var simulated by rememberSaveable { mutableStateOf(DebugStrap.AVAILABLE) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionDenied = !result.values.all { it }
        if (!permissionDenied) controller.start(BleHeartRateSource(context))
    }

    if (DebugStrap.AVAILABLE) {
        // Debug builds: choose between the real strap and the simulator.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !simulated, onClick = { simulated = false }, label = { Text(stringResource(R.string.source_bluetooth)) })
            FilterChip(selected = simulated, onClick = { simulated = true }, label = { Text(stringResource(R.string.source_simulated)) })
        }
    }
    if (!simulated) {
        if (permissionDenied) Text(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            if (BlePermissions.granted(context)) {
                controller.start(BleHeartRateSource(context))
            } else {
                permissions.launch(BlePermissions.required().toTypedArray())
            }
        }) { Text(stringResource(R.string.start_measurement)) }
        return
    }
    SimulatorContent(controller)
}

@Composable
private fun SimulatorContent(controller: MeasurementController) {
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
                is UploadResult.Success -> if (res.dryRun) {
                    Text(stringResource(R.string.dry_run_note), style = MaterialTheme.typography.labelMedium)
                    Text(res.detail, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.uploaded))
                }
                is UploadResult.Failure -> Text(
                    when {
                        res.reconnect -> stringResource(R.string.upload_reconnect)
                        controller.credential == null -> stringResource(R.string.upload_not_connected)
                        else -> stringResource(R.string.upload_failed, res.detail)
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Button(onClick = controller::reset) { Text(stringResource(R.string.done)) }
    ShareLogButton(controller)
}

@Composable
private fun ShareLogButton(controller: MeasurementController) {
    val context = LocalContext.current
    val subject = stringResource(R.string.share_log_subject)
    val title = stringResource(R.string.share_log)
    OutlinedButton(onClick = {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, controller.shareText())
        context.startActivity(Intent.createChooser(send, title))
    }) { Text(title) }
}

@Composable
private fun ConnectionSection(controller: MeasurementController) {
    Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleMedium)
    when (val c = controller.credential) {
        null -> Text(stringResource(R.string.connection_none))
        is Credential.ApiKey -> Text(stringResource(R.string.connection_api_key))
        is Credential.OAuth -> Text(
            c.athleteName?.let { stringResource(R.string.connection_oauth, it) } ?: stringResource(R.string.connection_oauth_unnamed),
        )
    }
    if (controller.credential != null) {
        OutlinedButton(onClick = controller::disconnect) { Text(stringResource(R.string.disconnect)) }
    } else {
        var key by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<SaveKeyResult?>(null) }
        // v1 connect path: the user's personal API key (OAuth deferred, see CLAUDE.md).
        Text(stringResource(R.string.api_key_where))
        Text(stringResource(R.string.api_key_stored), style = MaterialTheme.typography.bodySmall)
        run {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; error = null },
                label = { Text(stringResource(R.string.api_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                isError = error != null,
                supportingText = when (error) {
                    SaveKeyResult.BLANK -> ({ Text(stringResource(R.string.api_key_blank)) })
                    SaveKeyResult.STORAGE_FAILED -> ({ Text(stringResource(R.string.api_key_storage_failed)) })
                    else -> null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val r = controller.saveApiKey(key)
                if (r == SaveKeyResult.SAVED) key = "" else error = r
            }) { Text(stringResource(R.string.api_key_save)) }
        }
    }
    Text(stringResource(R.string.keep_values_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.keep_values_body), style = MaterialTheme.typography.bodySmall)
}
