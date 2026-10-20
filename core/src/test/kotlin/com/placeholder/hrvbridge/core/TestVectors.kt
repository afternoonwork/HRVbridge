package com.placeholder.hrvbridge.core

/** Loads the shared contract in test-vectors.json (also used by the future iOS suite). */
object TestVectors {
    @Suppress("UNCHECKED_CAST")
    val root: Map<String, Any?> by lazy {
        val text = TestVectors::class.java.getResource("/test-vectors.json")!!.readText()
        Json.parse(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    val packets: List<Map<String, Any?>> get() = root["packets"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    val series: List<Map<String, Any?>> get() = root["series"] as List<Map<String, Any?>>

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Suppress("UNCHECKED_CAST")
    fun doubles(v: Any?): List<Double> = (v as List<Any?>).map { (it as Number).toDouble() }
}
