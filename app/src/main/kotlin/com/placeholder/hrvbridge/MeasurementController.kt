package com.placeholder.hrvbridge

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.placeholder.hrvbridge.core.Credential
import com.placeholder.hrvbridge.core.DebugLog
import com.placeholder.hrvbridge.core.HeartRateParser
import com.placeholder.hrvbridge.core.HeartRateSource
import com.placeholder.hrvbridge.core.Intervals
import com.placeholder.hrvbridge.core.IntervalsClient
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

    val isActive: Boolean
        get() = session.let { it is SessionState.Checking || it is SessionState.Stabilising || it is SessionState.Measuring }

    private var logEpoch = SystemClock.elapsedRealtime()
    val log = DebugLog(clockMs = { SystemClock.elapsedRealtime() - logEpoch })

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client = IntervalsClient(baseUrl = baseUrl, log = { log.add("HTTP", it) })
    private var source: HeartRateSource? = null
    private var current: MeasurementSession? = null
    private var startedAt: Instant? = null
    private var record: SessionRecord? = null

    init {
        credential = credentials.load()?.also(log::addSecrets)
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

    fun start(newSource: HeartRateSource) {
        stopSource()
        cancelledInBackground = false
        upload = UploadStatus.None
        record = null
        log.clear()
        logEpoch = SystemClock.elapsedRealtime()
        startedAt = Instant.now()
        log.add("SESSION", "start $startedAt, app ${BuildConfig.VERSION_NAME}, $config")
        log.add("SOURCE", newSource.description)
        val s = MeasurementSession(config, startMs = newSource.nowMs())
        current = s
        source = newSource
        newSource.start(object : HeartRateSource.Listener {
            override fun onPacket(bytes: ByteArray, timeMs: Long) {
                if (current !== s) return
                log.add("RX", "t=$timeMs " + DebugLog.describePacket(bytes, HeartRateParser.parse(bytes)))
                s.onPacket(bytes, timeMs)
                publish()
            }

            override fun onDisconnected() {
                if (current !== s) return
                log.add("SOURCE", "disconnected")
                s.onDisconnected()
                publish()
            }
        })
        publish()
        handler.postDelayed(ticker, TICK_MS)
    }

    /** Foreground only: leaving the app abandons a running measurement. */
    fun onBackgrounded() {
        if (isActive) {
            log.add("SESSION", "cancelled: app left the foreground")
            reset()
            cancelledInBackground = true
        }
    }

    fun reset() {
        stopSource()
        current = null
        session = null
        upload = UploadStatus.None
        cancelledInBackground = false
    }

    /** User override for an unreliable measurement. */
    fun uploadAnyway() {
        val done = session as? SessionState.Finished ?: return
        if (done.result != null && upload == UploadStatus.None) {
            log.add("UPLOAD", "user override for unreliable measurement")
            startUpload(done)
        }
    }

    /** "Advanced: paste personal API key". Returns false if the input is blank. */
    fun saveApiKey(text: String): Boolean {
        val c = runCatching { Credential.ApiKey.fromUserInput(text) }.getOrNull() ?: return false
        log.addSecrets(c)
        credentials.save(c)
        credential = c
        return true
    }

    fun disconnect() {
        val c = credential ?: return
        // OAuth: also revoke on Intervals.icu (best effort). A personal API key stays valid there.
        if (c is Credential.OAuth) io.execute { client.disconnect(c) }
        credentials.clear()
        credential = null
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
                stopSource()
                val r = SessionRecord(
                    startedAt = startedAt ?: Instant.now(),
                    date = LocalDate.now(),
                    config = config,
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
            is SessionState.Failed -> stopSource()
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

    private fun stopSource() {
        handler.removeCallbacks(ticker)
        source?.stop()
        source = null
    }

    override fun onCleared() {
        stopSource()
        io.shutdown()
    }

    private companion object {
        const val TICK_MS = 200L

        fun SessionState.summary(): String = when (this) {
            is SessionState.Finished -> "Finished (${rrMs.size} RR)"
            else -> toString()
        }
    }
}
