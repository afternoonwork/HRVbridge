package com.placeholder.hrvbridge.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * Shared rounding rule (see test-vectors.json "roundingRule"), for positive values:
 * n = floor(x * 1e9 + 0.5) in IEEE double, an exact integer, then n / 10^9
 * rounded half up in decimal arithmetic. The first step pins the double to
 * 9 decimals so decimal ties stored slightly below (1.15 = 1.1499999...)
 * still round up. Every step is exactly specified, so other platforms get
 * identical results.
 */
object Rounding {
    fun halfUp(x: Double, decimals: Int): Double = decimal(x, decimals).toDouble()

    fun halfUpToInt(x: Double): Int = decimal(x, 0).intValueExact()

    private fun decimal(x: Double, decimals: Int): BigDecimal {
        require(x >= 0.0 && x < 1e6) { "value out of range: $x" }
        require(decimals in 0..9)
        val nano = floor(x * 1e9 + 0.5).toLong()
        return BigDecimal.valueOf(nano, 9).setScale(decimals, RoundingMode.HALF_UP)
    }
}
