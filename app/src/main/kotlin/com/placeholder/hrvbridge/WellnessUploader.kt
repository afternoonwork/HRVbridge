package com.placeholder.hrvbridge

import android.os.Handler
import android.os.Looper
import com.placeholder.hrvbridge.core.ApiResult
import com.placeholder.hrvbridge.core.Credential
import com.placeholder.hrvbridge.core.Intervals
import com.placeholder.hrvbridge.core.IntervalsClient
import com.placeholder.hrvbridge.core.WellnessValues
import java.util.concurrent.Executor
import java.util.concurrent.Executors

sealed interface UploadResult {
    data class Success(val detail: String, val dryRun: Boolean = false) : UploadResult

    data class Failure(val detail: String, val reconnect: Boolean = false) : UploadResult
}

/** Writes one day's values to Intervals.icu. Calls back on the main thread. */
interface WellnessUploader {
    fun upload(values: WellnessValues, onResult: (UploadResult) -> Unit)
}

/** Builds the real request but sends nothing. Debug builds without a credential. */
class DryRunUploader : WellnessUploader {
    override fun upload(values: WellnessValues, onResult: (UploadResult) -> Unit) {
        onResult(
            UploadResult.Success("PUT ${Intervals.wellnessUrl(values.date)}\n${Intervals.wellnessBody(values)}", dryRun = true),
        )
    }
}

class NotConnectedUploader : WellnessUploader {
    override fun upload(values: WellnessValues, onResult: (UploadResult) -> Unit) =
        onResult(UploadResult.Failure("not connected"))
}

/** Real upload: blocking HTTP on [executor], result posted to the main thread. */
class IntervalsUploader(
    private val client: IntervalsClient,
    private val credential: Credential,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) : WellnessUploader {
    private val main = Handler(Looper.getMainLooper())

    override fun upload(values: WellnessValues, onResult: (UploadResult) -> Unit) {
        executor.execute {
            val r = when (val res = client.putWellness(credential, values)) {
                is ApiResult.Success -> UploadResult.Success("HTTP ${res.code}")
                is ApiResult.Unauthorised -> UploadResult.Failure("HTTP ${res.code}", reconnect = true)
                is ApiResult.HttpError -> UploadResult.Failure("HTTP ${res.code}")
                is ApiResult.NetworkError -> UploadResult.Failure(res.message)
            }
            main.post { onResult(r) }
        }
    }
}
