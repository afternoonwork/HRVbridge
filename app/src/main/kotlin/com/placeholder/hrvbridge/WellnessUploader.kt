package com.placeholder.hrvbridge

import com.placeholder.hrvbridge.core.Intervals
import com.placeholder.hrvbridge.core.WellnessValues

sealed interface UploadResult {
    data class Success(val detail: String) : UploadResult

    data class Failure(val detail: String) : UploadResult
}

/** Writes one day's values to Intervals.icu. Calls back on the main thread. */
interface WellnessUploader {
    fun upload(values: WellnessValues, onResult: (UploadResult) -> Unit)
}

/** Builds the real request but sends nothing. Used until auth and HTTP exist. */
class DryRunUploader : WellnessUploader {
    override fun upload(values: WellnessValues, onResult: (UploadResult) -> Unit) {
        onResult(UploadResult.Success("PUT ${Intervals.wellnessUrl(values.date)}\n${Intervals.wellnessBody(values)}"))
    }
}
