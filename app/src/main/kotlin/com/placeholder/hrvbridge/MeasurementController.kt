package com.placeholder.hrvbridge

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.placeholder.hrvbridge.core.HeartRateSource
import com.placeholder.hrvbridge.core.MeasurementSession
import com.placeholder.hrvbridge.core.SessionConfig
import com.placeholder.hrvbridge.core.SessionState
import com.placeholder.hrvbridge.core.WellnessValues
import java.time.LocalDate

sealed interface UploadStatus {
    data object None : UploadStatus

    data object InProgress : UploadStatus

    data class Done(val result: UploadResult) : UploadStatus
}

/**
 * Connects a [HeartRateSource] (real strap or simulated) to the measurement
 * session, the upload and the UI. Everything runs on the main thread.
 */
class MeasurementController(
    private val uploader: WellnessUploader = DryRunUploader(),
    private val config: SessionConfig = SessionConfig(),
) : ViewModel() {
    /** Null when idle. */
    var session by mutableStateOf<SessionState?>(null)
        private set
    var upload by mutableStateOf<UploadStatus>(UploadStatus.None)
        private set
    var cancelledInBackground by mutableStateOf(false)
        private set

    val isActive: Boolean
        get() = session.let { it is SessionState.Checking || it is SessionState.Stabilising || it is SessionState.Measuring }

    private val handler = Handler(Looper.getMainLooper())
    private var source: HeartRateSource? = null
    private var current: MeasurementSession? = null
    private var date: LocalDate? = null

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
        val s = MeasurementSession(config, startMs = newSource.nowMs())
        current = s
        source = newSource
        newSource.start(object : HeartRateSource.Listener {
            override fun onPacket(bytes: ByteArray, timeMs: Long) {
                if (current !== s) return
                s.onPacket(bytes, timeMs)
                publish()
            }

            override fun onDisconnected() {
                if (current !== s) return
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
        if (done.result != null && upload == UploadStatus.None) startUpload(done)
    }

    private fun publish() {
        val s = current ?: return
        val state = s.state
        if (state == session) return
        session = state
        when (state) {
            is SessionState.Finished -> {
                stopSource()
                date = LocalDate.now()
                if (state.result?.reliable == true) startUpload(state)
            }
            is SessionState.Failed -> stopSource()
            else -> Unit
        }
    }

    private fun startUpload(done: SessionState.Finished) {
        val result = done.result ?: return
        upload = UploadStatus.InProgress
        uploader.upload(WellnessValues.from(date ?: LocalDate.now(), result)) { upload = UploadStatus.Done(it) }
    }

    private fun stopSource() {
        handler.removeCallbacks(ticker)
        source?.stop()
        source = null
    }

    override fun onCleared() = stopSource()

    private companion object {
        const val TICK_MS = 200L
    }
}
