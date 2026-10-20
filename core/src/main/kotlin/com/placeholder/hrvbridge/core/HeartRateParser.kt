package com.placeholder.hrvbridge.core

/** One decoded Heart Rate Measurement (0x2A37) notification. */
data class HeartRateMeasurement(
    val heartRate: Int,
    val contactSupported: Boolean,
    val contactDetected: Boolean,
    val energyExpended: Int?,
    /** Flags bit4: the sensor sends RR intervals (the list may still be empty). */
    val rrPresent: Boolean,
    /** Oldest first, in ms, unrounded (raw * 1000 / 1024). */
    val rrIntervalsMs: List<Double>,
    /** An odd byte after the RR values was ignored (malformed packet). */
    val droppedTrailingByte: Boolean,
) {
    /** Contact feature supported but no contact: ask the user to moisten the electrodes. */
    val poorContact: Boolean get() = contactSupported && !contactDetected
}

object HeartRateParser {
    private const val HR_UINT16 = 0x01
    private const val CONTACT_DETECTED = 0x02
    private const val CONTACT_SUPPORTED = 0x04
    private const val ENERGY_EXPENDED = 0x08
    private const val RR_PRESENT = 0x10

    fun rawToMs(raw: Int): Double = raw * 1000.0 / 1024.0

    /** Returns null if the packet is too short for the fields its flags announce. */
    fun parse(bytes: ByteArray): HeartRateMeasurement? {
        if (bytes.size < 2) return null
        val flags = u8(bytes, 0)
        var i = 1
        val hr: Int
        if (flags and HR_UINT16 != 0) {
            if (bytes.size < 3) return null
            hr = u16(bytes, 1)
            i = 3
        } else {
            hr = u8(bytes, 1)
            i = 2
        }
        var energy: Int? = null
        if (flags and ENERGY_EXPENDED != 0) {
            if (bytes.size < i + 2) return null
            energy = u16(bytes, i)
            i += 2
        }
        val rr = ArrayList<Double>()
        var dropped = false
        val rrPresent = flags and RR_PRESENT != 0
        if (rrPresent) {
            while (i + 1 < bytes.size) {
                rr.add(rawToMs(u16(bytes, i)))
                i += 2
            }
            dropped = i < bytes.size
        }
        return HeartRateMeasurement(
            heartRate = hr,
            contactSupported = flags and CONTACT_SUPPORTED != 0,
            contactDetected = flags and CONTACT_DETECTED != 0,
            energyExpended = energy,
            rrPresent = rrPresent,
            rrIntervalsMs = rr,
            droppedTrailingByte = dropped,
        )
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
}
