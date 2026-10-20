package com.placeholder.hrvbridge

import android.content.Context
import com.placeholder.hrvbridge.core.HeartRateSource

/** Release builds have no simulated strap. Same API as the debug source set. */
object DebugStrap {
    const val AVAILABLE = false
    val speeds = emptyList<Int>()

    fun scenarios(context: Context): List<String> = emptyList()

    fun create(context: Context, scenario: String, speed: Int): HeartRateSource =
        throw UnsupportedOperationException("simulated strap is debug only")
}
