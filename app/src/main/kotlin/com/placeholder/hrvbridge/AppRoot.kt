package com.placeholder.hrvbridge

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.placeholder.hrvbridge.core.KeyCheck
import com.placeholder.hrvbridge.core.SessionState
import com.placeholder.hrvbridge.ui.ConnectPhase
import com.placeholder.hrvbridge.ui.ConnectScreen
import com.placeholder.hrvbridge.ui.MainActions
import com.placeholder.hrvbridge.ui.MainPhase
import com.placeholder.hrvbridge.ui.MainScreenContent
import com.placeholder.hrvbridge.ui.MainScreenState
import com.placeholder.hrvbridge.ui.MainTopBar
import com.placeholder.hrvbridge.ui.ResultSummary
import com.placeholder.hrvbridge.ui.SettingsActions
import com.placeholder.hrvbridge.ui.SettingsContent
import com.placeholder.hrvbridge.ui.SlidersIcon
import com.placeholder.hrvbridge.ui.Txt
import com.placeholder.hrvbridge.ui.hrv

private enum class Screen { MAIN, SETTINGS, DEBUG_LOG }

/** Connect screen while no key is saved (and until "Continue"), else main, settings and debug log. */
@Composable
fun AppRoot(controller: MeasurementController) {
    var showConnect by rememberSaveable { mutableStateOf(controller.credential == null) }
    if (controller.credential == null || showConnect) {
        ConnectRoute(controller) { showConnect = false }
        return
    }
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    when (screen) {
        Screen.MAIN -> MainRoute(controller) { screen = Screen.SETTINGS }
        Screen.SETTINGS -> SettingsRoute(controller, back = { screen = Screen.MAIN }, debugLog = { screen = Screen.DEBUG_LOG },
            disconnected = { showConnect = true; screen = Screen.MAIN })
        Screen.DEBUG_LOG -> DebugLogRoute(controller) { screen = Screen.SETTINGS }
    }
}

@Composable
private fun ConnectRoute(controller: MeasurementController, proceed: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var storageFailed by remember { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val phase = when {
        storageFailed -> ConnectPhase.Problem(Txt(R.string.api_key_storage_failed))
        controller.checkingKey -> ConnectPhase.Checking
        controller.keyCheck == KeyCheck.REJECTED -> ConnectPhase.Problem(Txt(R.string.api_key_rejected))
        saved && controller.credential != null -> ConnectPhase.Connected(checked = controller.keyCheck == KeyCheck.VALID)
        else -> ConnectPhase.Idle
    }
    val view = LocalView.current
    LaunchedEffect(controller.keyCheck) {
        when (controller.keyCheck) {
            KeyCheck.REJECTED -> view.haptic(confirm = false)
            else -> Unit
        }
    }
    Scaffold(containerColor = hrv.bg) { padding ->
        Box(Modifier.padding(padding)) {
            ConnectScreen(
                key = key,
                onKeyChange = { key = it },
                phase = phase,
                save = {
                    when (controller.saveApiKey(key)) {
                        SaveKeyResult.SAVED -> {
                            key = ""
                            storageFailed = false
                            saved = true
                            controller.checkKey()
                        }
                        SaveKeyResult.STORAGE_FAILED -> storageFailed = true
                        SaveKeyResult.BLANK -> Unit
                    }
                },
                proceed = proceed,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainRoute(controller: MeasurementController, openSettings: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val state = mainState(controller)
    var confirmStop by remember { mutableStateOf(false) }

    val connectBle = { controller.connect(BleHeartRateSource(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Denied: the source reports "not allowed".
        connectBle()
    }
    val findStrap = {
        if (BlePermissions.granted(context)) connectBle() else permissions.launch(BlePermissions.required().toTypedArray())
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { findStrap() }

    // Back and predictive back during a measurement ask before stopping.
    BackHandler(enabled = state.isMeasuring) { confirmStop = true }

    // Haptics at the listed moments only.
    var lastConnected by remember { mutableStateOf(state.strapConnected) }
    var lastPoor by remember { mutableStateOf(state.poorContact) }
    var lastMilestone by remember { mutableStateOf(state.milestone) }
    LaunchedEffect(state.strapConnected, state.poorContact, state.milestone) {
        if (state.strapConnected && !lastConnected) view.haptic(confirm = true)
        if (state.poorContact && !lastPoor) view.haptic(confirm = false)
        val m = state.milestone
        if (m != null && m != lastMilestone) {
            view.haptic(confirm = m != MainScreenState.Milestone.FINISHED_UNRELIABLE && m != MainScreenState.Milestone.PROBLEM)
        }
        lastConnected = state.strapConnected
        lastPoor = state.poorContact
        lastMilestone = m
    }

    // TalkBack: the dial's description at most every 5 s, at once when the phase changes.
    var dialDescription by remember { mutableStateOf(state.dialDescription) }
    var describedAt by remember { mutableLongStateOf(0L) }
    var describedPhase by remember { mutableStateOf(state.phaseTitle) }
    LaunchedEffect(state.dialDescription) {
        val now = System.currentTimeMillis()
        if (now - describedAt >= 5_000 || state.phaseTitle != describedPhase) {
            dialDescription = state.dialDescription
            describedAt = now
            describedPhase = state.phaseTitle
        }
    }

    Scaffold(
        containerColor = hrv.bg,
        topBar = { MainTopBar(showSettings = !state.isMeasuring, openSettings = openSettings) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            MainScreenContent(
                state,
                MainActions(
                    findStrap = findStrap,
                    choose = { address -> (controller.bleSource)?.choose(address) },
                    start = controller::startMeasurement,
                    stop = { confirmStop = true },
                    done = controller::finishMeasurement,
                    again = { controller.measureAgain { BleHeartRateSource(context) } },
                    uploadAnyway = controller::uploadAnyway,
                    submitAnswers = controller::submitAnswers,
                    turnOnBluetooth = {
                        runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    },
                    openSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                ),
                dialDescription = dialDescription,
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_title)) },
            text = { Text(stringResource(R.string.stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    controller.finishMeasurement()
                }) { Text(stringResource(R.string.stop), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.keep_going)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoute(controller: MeasurementController, back: () -> Unit, debugLog: () -> Unit, disconnected: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = back)
    Scaffold(
        containerColor = hrv.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(back) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = hrv.bg, titleContentColor = hrv.ink),
            )
        },
    ) { padding ->
        SettingsContent(
            controller.settlingSeconds,
            SettingsActions(
                setSettling = controller::setSettling,
                questionsEnabled = controller.questionsEnabled,
                enableQuestions = controller::enableQuestions,
                disconnect = {
                    controller.disconnect()
                    disconnected()
                },
                deleteAll = { controller.deleteAllMeasurements() },
                openDebugLog = debugLog,
                simScenarios = if (DebugStrap.AVAILABLE) DebugStrap.scenarios(context) else emptyList(),
                simSpeeds = DebugStrap.speeds,
                connectSimulated = { name, speed ->
                    controller.connect(DebugStrap.create(context, name, speed))
                    back()
                },
            ),
            Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogRoute(controller: MeasurementController, back: () -> Unit) {
    val context = LocalContext.current
    val text = remember { controller.shareText() }
    val subject = stringResource(R.string.share_log_subject)
    BackHandler(onBack = back)
    Scaffold(
        containerColor = hrv.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_log), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(back) },
                actions = {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, subject))
                    }) { Text(stringResource(R.string.share_log), color = hrv.ink) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = hrv.bg, titleContentColor = hrv.ink),
            )
        },
    ) { padding ->
        Text(
            text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = hrv.ink,
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    val label = stringResource(R.string.back)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text("←", style = MaterialTheme.typography.titleMedium, color = hrv.ink)
    }
}

/** CONFIRM and REJECT need API 30; older versions use VIRTUAL_KEY and LONG_PRESS. */
private fun View.haptic(confirm: Boolean) {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (confirm) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
    } else {
        if (confirm) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(type)
}

/** The main screen's state from the controller. */
private fun mainState(c: MeasurementController): MainScreenState {
    fun seconds(ms: Long) = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
    val strapPhase: MainPhase = when (val s = c.strap) {
        StrapState.Idle -> MainPhase.NoStrap
        is StrapState.Searching -> MainPhase.Searching(s.found)
        is StrapState.Connecting -> MainPhase.Connecting(s.name)
        is StrapState.Connected -> MainPhase.Connected(s.name)
        is StrapState.CouldNotConnect -> MainPhase.CouldNotConnect(s.name)
        StrapState.BluetoothOff -> MainPhase.BluetoothOff
        StrapState.NotAllowed -> MainPhase.NotAllowed
    }
    val phase = when (val s = c.session) {
        null -> strapPhase
        is SessionState.Checking -> MainPhase.Checking(seconds(c.checkMs - s.elapsedMs), s.poorContact)
        is SessionState.Stabilising -> MainPhase.Settling(seconds(s.remainingMs), s.poorContact)
        is SessionState.Measuring -> MainPhase.Measuring(seconds(s.remainingMs), s.beats, s.poorContact)
        is SessionState.Failed -> MainPhase.Failed(s.reason)
        is SessionState.Finished -> s.result?.let { r ->
            MainPhase.Result(
                ResultSummary(
                    restingHr = r.restingHr, rmssd = r.rmssdRounded, sdnn = r.sdnnRounded,
                    accepted = r.acceptedBeats, total = s.rrMs.size, reliable = r.reliable,
                    upload = when (val u = c.upload) {
                        UploadStatus.None, UploadStatus.AwaitingAnswers -> ResultSummary.Upload.NONE
                        UploadStatus.InProgress -> ResultSummary.Upload.IN_PROGRESS
                        is UploadStatus.Done -> when (val res = u.result) {
                            is UploadResult.Success -> if (res.dryRun) ResultSummary.Upload.DRY_RUN else ResultSummary.Upload.UPLOADED
                            is UploadResult.Failure -> when {
                                res.reconnect -> ResultSummary.Upload.RECONNECT
                                c.credential == null -> ResultSummary.Upload.NOT_CONNECTED
                                else -> ResultSummary.Upload.FAILED
                            }
                        }
                    },
                ),
            ).let { if (c.upload == UploadStatus.AwaitingAnswers) MainPhase.Questions(it.summary) else it }
        } ?: MainPhase.NotEnoughBeats
    }
    val retry = c.retry
    return MainScreenState(
        phase = phase,
        heartRate = c.heartRate,
        handAngle = c.handAngle,
        handJump = c.handJump,
        settlingSeconds = c.settlingSeconds,
        strapConnected = c.strap is StrapState.Connected,
        cancelled = c.cancelledInBackground,
        retryUploaded = (retry as? RetryStatus.Uploaded)?.count,
        retryLine = when (retry) {
            RetryStatus.InProgress -> Txt(R.string.retry_in_progress)
            is RetryStatus.Failed -> Txt(if (retry.reconnect) R.string.upload_reconnect else R.string.retry_failed)
            else -> null
        },
    )
}
