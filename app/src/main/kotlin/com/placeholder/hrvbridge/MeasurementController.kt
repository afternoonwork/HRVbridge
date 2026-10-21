package com.placeholder.hrvbridge

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.placeholder.hrvbridge.core.ApiResult
import com.placeholder.hrvbridge.core.ArtefactFilter
import com.placeholder.hrvbridge.core.Credential
import com.placeholder.hrvbridge.core.DebugLog
import com.placeholder.hrvbridge.core.HeartRateParser
import com.placeholder.hrvbridge.core.HeartRateSource
import com.placeholder.hrvbridge.core.Intervals
import com.placeholder.hrvbridge.core.IntervalsClient
import com.placeholder.hrvbridge.core.KeyCheck
import com.placeholder.hrvbridge.core.MeasurementSession
import com.placeholder.hrvbridge.core.SessionConfig
import com.placeholder.hrvbridge.core.SessionRecord
import com.placeholder.hrvbridge.core.SessionState
import com.placeholder.hrvbridge.core.SessionStore
import com.placeholder.hrvbridge.core.WellnessValues
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors

enum class SaveKeyResult { SAVED, BLANK, STORAGE_FAILED }

/** Outcome of retrying earlier, not yet uploaded sessions on app open. */
sealed interface RetryStatus {
    data object None : RetryStatus

    data object InProgress : RetryStatus

    data class Uploaded(val count: Int) : RetryStatus

    data class Failed(val reconnect: Boolean) : RetryStatus
}

/** The strap, independent of a measurement (main screen rows above the dial). */
sealed interface StrapState {
    data object Idle : StrapState

    data class Searching(val found: List<FoundStrap>) : StrapState

    data class Connecting(val name: String) : StrapState

    data class Connected(val name: String) : StrapState

    data class CouldNotConnect(val name: String) : StrapState

    data object BluetoothOff : StrapState

    data object NotAllowed : StrapState
}

sealed interface UploadStatus {
    data object None : UploadStatus

    data object InProgress : UploadStatus

    data class Done(val result: UploadResult) : UploadStatus
}

/**
 * Connects a [HeartRateSource] (real strap or simulated) to the measurement
 * session, local storage, the debug log, the upload and the UI. Everything
 * runs on the main thread except HTTP.
 */
class MeasurementController @JvmOverloads constructor(
    application: Application,
    private val credentials: CredentialStore = CredentialStore.create(application),
    private val sessions: SessionStore = SessionStore(File(application.filesDir, "sessions")),
    private val config: SessionConfig = SessionConfig(),
    /** Tests point this at a local server. No other code changes it. */
    baseUrl: String = Intervals.BASE_URL,
    /** Wall clock for session times and the wellness date; tests use a fixed one. */
    private val wallClock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) : AndroidViewModel(application) {
    /** Null when idle. */
    var session by mutableStateOf<SessionState?>(null)
        private set
    var upload by mutableStateOf<UploadStatus>(UploadStatus.None)
        private set
    var cancelledInBackground by mutableStateOf(false)
        private set
    var credential by mutableStateOf<Credential?>(null)
        private set
    var retry by mutableStateOf<RetryStatus>(RetryStatus.None)
        private set
    /** Source started, not yet subscribed to 0x2A37. */
    var connecting by mutableStateOf(false)
        private set
    /** Last attempt ended before the strap was connected. */
    var connectFailed by mutableStateOf(false)
        private set
    var strap by mutableStateOf<StrapState>(StrapState.Idle)
        private set
    /** Latest heart rate from the strap, null before the first packet. */
    var heartRate by mutableStateOf<Int?>(null)
        private set
    /** Arrival time of the latest beat that moved the hand, on the source clock. */
    var lastBeatMs by mutableStateOf<Long?>(null)
        private set
    /**
     * Cumulative hand angle in degrees: settling 0 to 360, measuring 360 to 720,
     * done 720. Only increases during a measurement.
     */
    var handAngle by mutableStateOf(0.0)
        private set
    /** True when the hand should jump without animation (reset, or 0 s settling). */
    var handJump by mutableStateOf(true)
        private set
    /** Result of the last key check; null before a check or while it runs. */
    var keyCheck by mutableStateOf<KeyCheck?>(null)
        private set
    var checkingKey by mutableStateOf(false)
        private set
    /** 0, 30, 60 or 90 s; used by [startMeasurement]. */
    var settlingSeconds by mutableStateOf(60)
        private set

    val isActive: Boolean
        get() = connecting ||
            session.let { it is SessionState.Checking || it is SessionState.Stabilising || it is SessionState.Measuring }

    private var logEpoch = SystemClock.elapsedRealtime()
    val log = DebugLog(clockMs = { SystemClock.elapsedRealtime() - logEpoch })

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client = IntervalsClient(baseUrl = baseUrl, log = { log.add("HTTP", it) })
    private var source: HeartRateSource? = null
    private var current: MeasurementSession? = null
    private var startedAt: Instant? = null
    private var record: SessionRecord? = null
    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    /** Session config of the running measurement. */
    private var running: SessionConfig = config
    /** New flow ([connect], [startMeasurement]): the strap stays connected between measurements. */
    private var keepStrap = false
    private var measuringRr = ArrayList<Double>()

    init {
        credential = credentials.load()?.also(log::addSecrets)
        settlingSeconds = settings.getInt(KEY_SETTLING, 60).takeIf { it in SETTLING_CHOICES } ?: 60
        retryPending()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = current ?: return
            val src = source ?: return
            s.onTick(src.nowMs())
            publish()
            if (isActive) handler.postDelayed(this, TICK_MS)
        }
    }

    fun setSettling(seconds: Int) {
        require(seconds in SETTLING_CHOICES)
        settlingSeconds = seconds
        settings.edit().putInt(KEY_SETTLING, seconds).apply()
    }

    /** Connects to the strap only; [startMeasurement] starts the 10 s check later. */
    fun connect(newSource: HeartRateSource) {
        start(newSource, measureOnConnect = false)
    }

    /** "Start": a measurement with the connected strap and the settling time setting. */
    fun startMeasurement() {
        val src = source ?: return
        if (strap !is StrapState.Connected) return
        cancelledInBackground = false
        upload = UploadStatus.None
        record = null
        startedAt = Instant.now(wallClock)
        running = config.copy(stabilisationMs = settlingSeconds * 1000L)
        log.add("SESSION", "start $startedAt, app ${BuildConfig.VERSION_NAME}, $running")
        resetHand()
        val started = MeasurementSession(running, startMs = src.nowMs())
        current = started
        session = null
        publish()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    /** "Done" or a confirmed "Stop": back to the main screen, the strap stays connected. */
    fun finishMeasurement() {
        if (!keepStrap) return reset()
        handler.removeCallbacks(ticker)
        current = null
        session = null
        upload = UploadStatus.None
        resetHand()
        if (source == null) strap = StrapState.Idle
    }

    /** "Repeat" or "Try again" after a measurement: same strap if still connected. */
    fun measureAgain(fallback: () -> HeartRateSource) {
        finishMeasurement()
        if (source != null && strap is StrapState.Connected) startMeasurement() else connect(fallback())
    }

    /** One authenticated read to check the saved key; a rejected key is removed again. */
    fun checkKey() {
        val c = credential ?: return
        keyCheck = null
        checkingKey = true
        val today = LocalDate.now(wallClock)
        io.execute {
            val outcome = client.checkKey(c, today)
            handler.post {
                log.add("AUTH", "key check: $outcome")
                checkingKey = false
                keyCheck = outcome
                if (outcome == KeyCheck.REJECTED && credential == c) {
                    credentials.clear()
                    credential = null
                }
            }
        }
    }

    fun start(newSource: HeartRateSource) = start(newSource, measureOnConnect = true)

    private fun start(newSource: HeartRateSource, measureOnConnect: Boolean) {
        keepStrap = !measureOnConnect
        running = config
        resetHand()
        heartRate = null
        strap = if (newSource is BleHeartRateSource) StrapState.Searching(emptyList()) else StrapState.Connecting(newSource.description)
        if (newSource is BleHeartRateSource) {
            newSource.onScanUpdate = { found, connectingTo ->
                if (source === newSource && strap !is StrapState.Connected) {
                    strap = connectingTo?.let { StrapState.Connecting(it) } ?: StrapState.Searching(found)
                }
            }
        }
        stopSource()
        cancelledInBackground = false
        upload = UploadStatus.None
        record = null
        log.clear()
        logEpoch = SystemClock.elapsedRealtime()
        startedAt = Instant.now(wallClock)
        log.add("SESSION", "start $startedAt, app ${BuildConfig.VERSION_NAME}, $config")
        log.add("SOURCE", newSource.description)
        source = newSource
        connecting = true
        connectFailed = false
        session = null
        current = null
        newSource.start(object : HeartRateSource.Listener {
            private var s: MeasurementSession? = null

            override fun onConnected() {
                if (source !== newSource || s != null) return
                connecting = false
                strap = StrapState.Connected((newSource as? BleHeartRateSource)?.connectedName ?: SIMULATED_NAME)
                if (!measureOnConnect) {
                    log.add("SOURCE", "connected")
                    return
                }
                log.add("SOURCE", "connected, measurement clock starts")
                // The 10 s signal check starts only once notifications are on.
                val started = MeasurementSession(config, startMs = newSource.nowMs())
                s = started
                current = started
                publish()
                handler.postDelayed(ticker, TICK_MS)
            }

            override fun onPacket(bytes: ByteArray, timeMs: Long) {
                if (source !== newSource) return
                val parsed = HeartRateParser.parse(bytes)
                log.add("RX", "t=$timeMs " + DebugLog.describePacket(bytes, parsed))
                parsed?.heartRate?.takeIf { it > 0 }?.let { heartRate = it }
                val session = s ?: current ?: return
                session.onPacket(bytes, timeMs)
                moveHand(parsed?.rrIntervalsMs.orEmpty(), timeMs, session.state)
                publish()
            }

            override fun onDisconnected() {
                if (source !== newSource) return
                log.add("SOURCE", "disconnected")
                val session = s ?: current
                if (strap !is StrapState.Connected) {
                    connecting = false
                    connectFailed = true
                    strap = when ((newSource as? BleHeartRateSource)?.failure) {
                        BleHeartRateSource.Failure.BLUETOOTH_OFF -> StrapState.BluetoothOff
                        BleHeartRateSource.Failure.NOT_ALLOWED -> StrapState.NotAllowed
                        BleHeartRateSource.Failure.CONNECT_TIMEOUT ->
                            StrapState.CouldNotConnect((newSource as BleHeartRateSource).connectingName ?: "strap")
                        else -> StrapState.Idle
                    }
                    stopSource()
                    return
                }
                strap = StrapState.Idle
                heartRate = null
                if (session == null) {
                    stopSource()
                    return
                }
                session.onDisconnected()
                publish()
                stopSource()
            }

            override fun onEvent(message: String) {
                if (source === newSource) log.add("BLE", message)
            }
        })
    }

    /** Foreground only: leaving the app abandons a running measurement. */
    fun onBackgrounded() {
        if (isActive) {
            log.add("SESSION", "cancelled: app left the foreground")
            reset()
            cancelledInBackground = true
        } else if (source != null) {
            // Foreground only: no background Bluetooth, also between measurements.
            log.add("SOURCE", "app left the foreground, strap disconnected")
            reset()
        }
    }

    fun reset() {
        stopSource()
        strap = StrapState.Idle
        heartRate = null
        resetHand()
        current = null
        session = null
        connecting = false
        connectFailed = false
        upload = UploadStatus.None
        cancelledInBackground = false
    }

    /** User override for an unreliable measurement. */
    fun uploadAnyway() {
        val done = session as? SessionState.Finished ?: return
        val r = record ?: return
        if (done.result != null && upload == UploadStatus.None) {
            log.add("UPLOAD", "user override for unreliable measurement")
            // Persist the override first, so a failed upload is retried later.
            record = r.copy(uploadOverride = true).also(::save)
            startUpload(done)
        }
    }

    /**
     * Uploads earlier sessions that are still pending (newest per date) in one
     * bulk PUT. Runs on app open; needs a credential, never dry-runs.
     */
    fun retryPending() {
        val c = credential ?: return
        if (retry == RetryStatus.InProgress) return
        val batch = runCatching { sessions.retryBatch() }.getOrElse {
            log.add("RETRY", "cannot read sessions: $it")
            emptyList()
        }
        if (batch.isEmpty()) return
        retry = RetryStatus.InProgress
        log.add("RETRY", "${batch.size} pending: ${batch.joinToString { it.date.toString() }}")
        val values = batch.map { WellnessValues.from(it.date, it.result!!) }
        io.execute {
            val res = client.putWellnessBulk(c, values)
            handler.post {
                log.add("RETRY", res.toString())
                retry = when (res) {
                    is ApiResult.Success -> {
                        val now = Instant.now()
                        batch.forEach { save(it.copy(uploadedAt = now)) }
                        RetryStatus.Uploaded(batch.size)
                    }
                    is ApiResult.Unauthorised -> RetryStatus.Failed(reconnect = true)
                    else -> RetryStatus.Failed(reconnect = false)
                }
            }
        }
    }

    /** "Advanced: paste personal API key". */
    fun saveApiKey(text: String): SaveKeyResult {
        val c = runCatching { Credential.ApiKey.fromUserInput(text) }.getOrNull() ?: return SaveKeyResult.BLANK
        log.addSecrets(c)
        if (!credentials.save(c)) {
            log.add("AUTH", "could not store credential: Keystore failure")
            return SaveKeyResult.STORAGE_FAILED
        }
        credential = c
        retryPending()
        return SaveKeyResult.SAVED
    }

    fun disconnect() {
        val c = credential ?: return
        // OAuth: also revoke on Intervals.icu (best effort). A personal API key stays valid there.
        if (c is Credential.OAuth) io.execute { client.disconnect(c) }
        credentials.clear()
        credential = null
    }

    /**
     * Deletes all local session files and the debug log. Keeps the stored API key
     * and never touches Intervals.icu. Not while a measurement is running.
     */
    fun deleteAllMeasurements(): Int {
        if (isActive) return 0
        reset()
        record = null
        retry = RetryStatus.None
        val n = runCatching { sessions.deleteAll() }.getOrDefault(0)
        log.clear()
        return n
    }

    /** Debug log plus the session JSON. Secrets are redacted by [DebugLog]. */
    fun shareText(): String = buildString {
        append(log.text())
        record?.let { append("\n\nSession JSON:\n").append(it.toJson()) }
    }

    private fun publish() {
        val s = current ?: return
        val state = s.state
        if (state == session) return
        if (state::class != session?.let { it::class }) log.add("PHASE", state.summary())
        session = state
        when (state) {
            is SessionState.Finished -> {
                if (keepStrap) handler.removeCallbacks(ticker) else stopSource()
                setHand(720.0)
                val r = SessionRecord(
                    startedAt = startedAt ?: Instant.now(wallClock),
                    // The wellness record is the local day the measurement started, not ended.
                    date = (startedAt ?: Instant.now(wallClock)).atZone(wallClock.zone).toLocalDate(),
                    config = running,
                    series = state.series,
                    result = state.result,
                    malformedPackets = s.malformedPackets,
                    uploadedAt = null,
                )
                record = r
                save(r)
                log.add("RESULT", state.result?.toString() ?: "no result")
                if (state.result?.reliable == true) startUpload(state)
            }
            is SessionState.Failed -> if (keepStrap) handler.removeCallbacks(ticker) else stopSource()
            else -> Unit
        }
    }

    private fun startUpload(done: SessionState.Finished) {
        val result = done.result ?: return
        val r = record ?: return
        upload = UploadStatus.InProgress
        uploader().upload(WellnessValues.from(r.date, result)) { res ->
            log.add("UPLOAD", res.toString())
            upload = UploadStatus.Done(res)
            if (res is UploadResult.Success && !res.dryRun && record === r) {
                record = r.copy(uploadedAt = Instant.now()).also(::save)
            }
            if (res is UploadResult.Failure && res.reconnect) log.add("UPLOAD", "credential rejected, reconnect needed")
        }
    }

    private fun uploader(): WellnessUploader {
        val c = credential
        return when {
            c != null -> IntervalsUploader(client, c, io)
            BuildConfig.DEBUG -> DryRunUploader()
            else -> NotConnectedUploader()
        }
    }

    private fun save(r: SessionRecord) {
        runCatching { sessions.save(r) }.onFailure { log.add("STORE", "save failed: $it") }
    }

    /**
     * The hand steps per beat: every beat while settling, accepted beats while
     * measuring (same filter as the result; the result itself comes from the session).
     */
    private fun moveHand(rr: List<Double>, timeMs: Long, state: SessionState) {
        if (rr.isEmpty()) return
        when (state) {
            is SessionState.Stabilising -> if (running.stabilisationMs > 0) {
                val total = running.stabilisationMs.toDouble()
                lastBeatMs = timeMs
                setHand((total - state.remainingMs) / total * 360)
            }
            is SessionState.Measuring -> {
                val first = measuringRr.size
                measuringRr.addAll(rr)
                val accepted = ArtefactFilter.filter(measuringRr, running.filter).accepted
                if (accepted.subList(first, accepted.size).none { it }) return
                val total = running.measurementMs.toDouble()
                lastBeatMs = timeMs
                // With 0 s settling there is no first turn: jump to 12 without spinning.
                if (handAngle < 360 && running.stabilisationMs == 0L) {
                    handJump = true
                    handAngle = 360.0
                }
                setHand(360 + (total - state.remainingMs) / total * 360)
            }
            else -> Unit
        }
    }

    private fun setHand(angle: Double) {
        handJump = false
        handAngle = maxOf(handAngle, minOf(angle, 720.0))
    }

    private fun resetHand() {
        handJump = true
        handAngle = 0.0
        lastBeatMs = null
        measuringRr = ArrayList()
    }

    private fun stopSource() {
        handler.removeCallbacks(ticker)
        source?.stop()
        source = null
    }

    override fun onCleared() {
        stopSource()
        io.shutdown()
    }

    companion object {
        private const val TICK_MS = 200L
        const val KEY_SETTLING = "settlingSeconds"
        val SETTLING_CHOICES = listOf(0, 30, 60, 90)
        const val SIMULATED_NAME = "Simulated strap"

        private fun SessionState.summary(): String = when (this) {
            is SessionState.Finished -> "Finished (${rrMs.size} RR)"
            else -> toString()
        }
    }
}
