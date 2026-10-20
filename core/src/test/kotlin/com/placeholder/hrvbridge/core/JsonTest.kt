package com.placeholder.hrvbridge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonTest {
    @Test
    fun parsesScalars() {
        assertEquals(true, Json.parse("true"))
        assertEquals(false, Json.parse(" false "))
        assertNull(Json.parse("null"))
        assertEquals(42L, Json.parse("42"))
        assertEquals(-7L, Json.parse("-7"))
        assertEquals(68.4, Json.parse("68.4"))
        assertEquals(1.5e3, Json.parse("1.5E3"))
    }

    @Test
    fun parsesStringsWithEscapes() {
        assertEquals("a\"b\\c/d\n\tü", Json.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00fc\""))
    }

    @Test
    fun parsesNestedStructures() {
        val v = Json.parse("""{"a": [1, 2.5, {"b": null}], "c": "x", "d": {}}""")
        assertEquals(mapOf("a" to listOf(1L, 2.5, mapOf("b" to null)), "c" to "x", "d" to emptyMap<String, Any?>()), v)
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "\"abc", "1 2", "{\"a\":1,}", "01x")) {
            assertFailsWith<JsonException>(bad) { Json.parse(bad) }
        }
    }

    @Test
    fun writesValuesThatParseBack() {
        val v = mapOf(
            "s" to "a\"b\n", "i" to 52, "l" to 7L, "d" to 68.4, "b" to true, "n" to null,
            "list" to listOf(1000.0, 999.0234375), "nested" to mapOf("x" to emptyList<Any>()),
        )
        val text = Json.write(v)
        assertEquals("""{"s":"a\"b\n","i":52,"l":7,"d":68.4,"b":true,"n":null,"list":[1000.0,999.0234375],"nested":{"x":[]}}""", text)
        assertEquals(mapOf("s" to "a\"b\n", "i" to 52L, "l" to 7L, "d" to 68.4, "b" to true, "n" to null,
            "list" to listOf(1000.0, 999.0234375), "nested" to mapOf("x" to emptyList<Any>())), Json.parse(text))
    }

    @Test
    fun refusesNonFiniteNumbersAndUnknownTypes() {
        assertFailsWith<JsonException> { Json.write(Double.NaN) }
        assertFailsWith<JsonException> { Json.write(Double.POSITIVE_INFINITY) }
        assertFailsWith<JsonException> { Json.write(Any()) }
    }

    @Test
    fun quotesStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.quote("a\"b\\c\n\u0001"))
    }
}
