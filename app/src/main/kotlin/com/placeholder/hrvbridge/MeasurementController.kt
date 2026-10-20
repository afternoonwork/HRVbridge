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

enum class SaveKeyResult { SAVED, BLANK, STORAGE_FAILED }

/** Outcome of retrying earlier, not yet uploaded sessions on app open. */
sealed interface RetryStatus {
    data object None : RetryStatus

    data object InProgress : RetryStatus

    data class Uploaded(val count: Int) : RetryStatus

    data class Failed(val reconnect: Boolean) : RetryStatus
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

    init {
        credential = credentials.load()?.also(log::addSecrets)
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
        source = newSource
        connecting = true
        connectFailed = false
        session = null
        current = null
        newSource.start(object : HeartRateSource.Listener {
            private var s: MeasurementSession? = null

            override fun onConnected() {
                if (source !== newSource || s != null) return
                log.add("SOURCE", "connected, measurement clock starts")
                connecting = false
                // The 10 s signal check starts only once notifications are on.
                val started = MeasurementSession(config, startMs = newSource.nowMs())
                s = started
                current = started
                publish()
                handler.postDelayed(ticker, TICK_MS)
            }

            override fun onPacket(bytes: ByteArray, timeMs: Long) {
                if (source !== newSource) return
                log.add("RX", "t=$timeMs " + DebugLog.describePacket(bytes, HeartRateParser.parse(bytes)))
                val session = s ?: return
                session.onPacket(bytes, timeMs)
                publish()
            }

            override fun onDisconnected() {
                if (source !== newSource) return
                log.add("SOURCE", "disconnected")
                val session = s
                if (session == null) {
                    connecting = false
                    connectFailed = true
                    stopSource()
                    return
                }
                session.onDisconnected()
                publish()
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
        }
    }

    fun reset() {
        stopSource()
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
