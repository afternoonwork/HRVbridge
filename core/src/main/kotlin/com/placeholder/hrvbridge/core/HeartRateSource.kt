package com.placeholder.hrvbridge.core

/**
 * A heart rate strap as seen by the measurement pipeline. The real BLE
 * implementation and the debug simulated strap both implement this, so
 * everything downstream (parser, filter, session timer, UI, upload) is the same.
 */
interface HeartRateSource {
    /** For the debug log, e.g. device name and address, or the simulated scenario. */
    val description: String

    /** Monotonic clock (ms) that packet timestamps and session ticks use. */
    fun nowMs(): Long

    /**
     * Finds and connects to the strap, then delivers notifications.
     * Callbacks arrive on the main thread. After [stop] there are none.
     */
    fun start(listener: Listener)

    fun stop()

    interface Listener {
        /** Subscribed to 0x2A37; the measurement clock may start now. */
        fun onConnected()

        /** Raw 0x2A37 notification value, unparsed. */
        fun onPacket(bytes: ByteArray, timeMs: Long)

        /** Connection ended, or (before [onConnected]) connecting failed. */
        fun onDisconnected()

        /** Human-readable connection event for the debug log (GATT callbacks, status codes). */
        fun onEvent(message: String)
    }
}
