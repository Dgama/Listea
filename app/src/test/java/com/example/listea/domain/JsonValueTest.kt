package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The strict reader behind the Package protocol.
 *
 * These tests pin the rules that a general-purpose JSON library would not give us: duplicate keys
 * are refused rather than collapsed, a BOM is refused rather than skipped, strings are normalized
 * once at the boundary, and the canonical form has an order defined by code point rather than by
 * the producer's field order.
 */
class JsonValueTest {

    private fun codeOf(text: String): String? = try {
        Json.parse(text)
        null
    } catch (e: JsonParseException) {
        e.code
    }

    @Test
    fun `objects keep their entries and expose them by key`() {
        val value = Json.parse("""{"a":1,"b":[true,null,"x"]}""") as JsonValue.Obj
        assertEquals("1", (value["a"] as JsonValue.Num).raw)
        assertEquals(JsonValue.Bool(true), (value["b"] as JsonValue.Arr).items[0])
        assertEquals(JsonValue.Null, (value["b"] as JsonValue.Arr).items[1])
        assertEquals("x", (value["b"] as JsonValue.Arr).items[2].asStringOrNull())
    }

    @Test
    fun `a duplicate key is refused`() {
        assertEquals("duplicate-key", codeOf("""{"a":1,"a":2}"""))
        assertEquals("duplicate-key", codeOf("""{"a":{"x":1,"x":2}}"""))
    }

    @Test
    fun `a byte order mark is refused`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "{}".toByteArray(StandardCharsets.UTF_8)
        assertEquals("bom", try {
            Json.parse(bytes)
            null
        } catch (e: JsonParseException) {
            e.code
        })
    }

    @Test
    fun `invalid UTF-8 is refused before the JSON reader sees it`() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertEquals("invalid-utf8", try {
            Json.parse(bytes)
            null
        } catch (e: JsonParseException) {
            e.code
        })
    }

    @Test
    fun `comments and trailing commas are refused`() {
        assertEquals("malformed-json", codeOf("""{"a":1,}"""))
        assertEquals("malformed-json", codeOf("""[1,2,]"""))
        assertEquals("malformed-json", codeOf("""{"a":1 // note\n}"""))
        assertEquals("malformed-json", codeOf("""{a:1}"""))
    }

    @Test
    fun `numbers follow the JSON grammar`() {
        for (good in listOf("0", "-0", "12", "-3.5", "1e9", "2E-3", "0.25")) {
            assertEquals(JsonValue.Num(good), Json.parse(good))
        }
        for (bad in listOf("01", "-", ".5", "1.", "1e", "NaN", "Infinity", "+1")) {
            assertEquals("malformed-json", codeOf(bad))
        }
    }

    @Test
    fun `an integer is not a decimal`() {
        assertEquals(1, Json.parse("1").asIntOrNull())
        assertEquals(null, Json.parse("1.0").asIntOrNull())
        assertEquals(null, Json.parse("1e0").asIntOrNull())
    }

    @Test
    fun `depth is bounded`() {
        val sixteen = "[".repeat(16) + "0" + "]".repeat(16)
        assertTrue(Json.parse(sixteen) is JsonValue.Arr)
        assertEquals(
            "malformed-json",
            codeOf("[".repeat(17) + "0" + "]".repeat(17))
        )
    }

    @Test
    fun `string length is bounded after unescaping`() {
        assertEquals(
            "limit-exceeded",
            codeOf("\"" + "a".repeat(MAX_JSON_STRING_LENGTH + 1) + "\"")
        )
    }

    @Test
    fun `unescaped control characters and unpaired surrogates are refused`() {
        assertEquals("malformed-json", codeOf("\"a\u0001b\""))
        assertEquals("malformed-json", codeOf("\"\\uD800\""))
        assertEquals("malformed-json", codeOf("\"\\uDC00\""))
        assertEquals("\uD83D\uDE00", Json.parse("\"\\uD83D\\uDE00\"").asStringOrNull())
    }

    @Test
    fun `display strings are NFC normalized`() {
        assertEquals("é", Json.parse("\"e\\u0301\"").asStringOrNull())
        assertEquals("é", Json.parse("\"é\"").asStringOrNull())
    }

    @Test
    fun `canonical output sorts keys and drops whitespace`() {
        val value = Json.parse(
            """
            {
              "z": [1, 2, {"b": true, "a": null}],
              "a": "x\ty"
            }
            """.trimIndent()
        )
        assertEquals("""{"a":"x\ty","z":[1,2,{"a":null,"b":true}]}""", value.canonical())
    }

    @Test
    fun `canonical key order is by code point, not UTF-16 unit`() {
        // U+E000 is one code unit and sorts before U+10000 by code point. UTF-16 would put the
        // surrogate pair first. The protocol says code point.
        val value = JsonValue.Obj(
            listOf(
                "\uD800\uDC00" to JsonValue.Num("1"),
                "\uE000" to JsonValue.Num("2")
            )
        )
        assertTrue(value.canonical().indexOf("\uE000") < value.canonical().indexOf("\uD800"))
    }

    @Test
    fun `canonical output round-trips through the reader`() {
        val text = """{"a":[1,2.5,true,null,"x\u0001"],"b":{}}"""
        val value = Json.parse(text)
        assertEquals(value, Json.parse(value.canonical()))
    }
}
