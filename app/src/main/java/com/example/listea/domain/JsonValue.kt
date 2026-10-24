package com.example.listea.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A JSON value, strict enough to be a protocol reader and small enough to stay in `domain/`.
 *
 * **Why not `org.json` or a serialization library.** The Package protocol needs four rules that a
 * general reader does not give by default: duplicate object keys are an error, unknown envelope
 * fields are an error, nesting and string lengths are bounded, and the canonical form has an order
 * fixed by the protocol rather than by the writer. `org.json` is an Android class, which this
 * package may not depend on. `kotlinx-serialization` accepts the last duplicate key instead of
 * refusing it. So the reader is here, and the tests that pin the protocol's rejections are beside
 * it.
 *
 * **What it is not.** It is not a document model for the app. A Prompt body is stored as canonical
 * text and interpreted only by the Viewer that declares its kind; Core reads the envelope fields
 * and treats the body as opaque. Nothing outside the Package reader should branch on a body key.
 *
 * Numbers keep their source spelling. The protocol uses integers for versions, where `1.0` is not
 * `1`; a body's decimals are content, not arithmetic, and re-spelling them would make two
 * submissions of the same text look different for no reason a person could name.
 */
sealed interface JsonValue {

    data class Obj(val entries: List<Pair<String, JsonValue>>) : JsonValue {
        private val byKey: Map<String, JsonValue> by lazy(LazyThreadSafetyMode.NONE) {
            entries.toMap()
        }

        operator fun get(key: String): JsonValue? = byKey[key]

        fun has(key: String): Boolean = byKey.containsKey(key)

        val keys: Set<String> get() = byKey.keys
    }

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    /** A number exactly as written, already validated against the JSON grammar. */
    data class Num(val raw: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** Why a [JsonValue] could not be read. The code is stable and is what a diagnostic stores. */
class JsonParseException(
    val code: String,
    message: String
) : IllegalArgumentException(message)

/** The JSON nesting limit from the Package protocol. */
const val MAX_JSON_DEPTH = 16

/** The per-string limit from the Package protocol. */
const val MAX_JSON_STRING_LENGTH = 10_000

/**
 * Strict UTF-8, then strict JSON.
 *
 * The two are separate failures on purpose: a malformed byte sequence is `invalid-utf8`, while a
 * well-formed document with a trailing comma is `malformed-json`. A BOM is rejected rather than
 * skipped, because two producers writing the same document with and without one would otherwise
 * hash differently.
 */
object Json {

    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw JsonParseException("invalid-utf8", "The file is not valid UTF-8.")
        }
        if (text.startsWith('\uFEFF')) {
            throw JsonParseException("bom", "The file starts with a byte-order mark.")
        }
        return text
    }

    fun parse(
        text: String,
        maxDepth: Int = MAX_JSON_DEPTH,
        maxStringLength: Int = MAX_JSON_STRING_LENGTH
    ): JsonValue = Parser(text, maxDepth, maxStringLength).readDocument()

    fun parse(bytes: ByteArray): JsonValue = parse(decodeUtf8(bytes))

    private class Parser(
        private val text: String,
        private val maxDepth: Int,
        private val maxStringLength: Int
    ) {
        private var index = 0

        fun readDocument(): JsonValue {
            skipWhitespace()
            if (index >= text.length) fail("The file is empty.")
            val value = readValue(0)
            skipWhitespace()
            if (index != text.length) fail("Unexpected content after the document.")
            return value
        }

        private fun readValue(depth: Int): JsonValue {
            if (index >= text.length) fail("The document ends where a value was expected.")
            return when (val c = text[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Unexpected character '$c'.")
            }
        }

        private fun readObject(depth: Int): JsonValue {
            if (depth > maxDepth) fail("The document is nested too deeply.")
            index++ // {
            val entries = mutableListOf<Pair<String, JsonValue>>()
            val seen = mutableSetOf<String>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("An object key must be a string.")
                val key = readString()
                if (!seen.add(key)) {
                    throw JsonParseException("duplicate-key", "The object key \"$key\" appears twice.")
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                entries += key to readValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        if (peek() == '}') fail("A trailing comma is not allowed.")
                    }
                    '}' -> {
                        index++
                        return JsonValue.Obj(entries)
                    }
                    else -> fail("Expected ',' or '}'.")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue {
            if (depth > maxDepth) fail("The document is nested too deeply.")
            index++ // [
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        if (peek() == ']') fail("A trailing comma is not allowed.")
                    }
                    ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("Expected ',' or ']'.")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("The string is not closed.")
                when (val c = text[index++]) {
                    '"' -> break
                    '\\' -> out.append(readEscape())
                    else -> {
                        if (c < ' ') fail("A control character must be escaped.")
                        out.append(c)
                    }
                }
                if (out.length > maxStringLength) {
                    throw JsonParseException(
                        "limit-exceeded",
                        "A string is longer than $maxStringLength characters."
                    )
                }
            }
            rejectUnpairedSurrogates(out)
            return java.text.Normalizer.normalize(out, java.text.Normalizer.Form.NFC)
        }

        private fun readEscape(): Char {
            if (index >= text.length) fail("The escape is not closed.")
            return when (val c = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> fail("Unknown escape '\\$c'.")
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) fail("A \\u escape needs four hex digits.")
            var value = 0
            repeat(4) {
                val digit = text[index++].digitToIntOrNull(16)
                    ?: fail("A \\u escape needs four hex digits.")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length) fail("The number is not finished.")
            when (val c = text[index]) {
                '0' -> {
                    index++
                    if (index < text.length && text[index] in '0'..'9') {
                        fail("A number may not have a leading zero.")
                    }
                }
                in '1'..'9' -> {
                    while (index < text.length && text[index] in '0'..'9') index++
                }
                else -> fail("Expected a digit after '-'.")
            }
            if (index < text.length && text[index] == '.') {
                index++
                if (index >= text.length || text[index] !in '0'..'9') {
                    fail("A fraction needs at least one digit.")
                }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || text[index] !in '0'..'9') {
                    fail("An exponent needs at least one digit.")
                }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            return JsonValue.Num(text.substring(start, index))
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, index)) fail("Unexpected character '${text[index]}'.")
            index += literal.length
            return value
        }

        private fun rejectUnpairedSurrogates(value: CharSequence) {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) {
                        fail("The string contains an unpaired surrogate.")
                    }
                    i += 2
                } else {
                    if (c.isLowSurrogate()) fail("The string contains an unpaired surrogate.")
                    i++
                }
            }
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        private fun peek(): Char {
            if (index >= text.length) fail("The document ends early.")
            return text[index]
        }

        private fun expect(c: Char) {
            if (index >= text.length || text[index] != c) fail("Expected '$c'.")
            index++
        }

        private fun fail(message: String): Nothing =
            throw JsonParseException("malformed-json", message)
    }
}

/**
 * The protocol's canonical form: object keys sorted by code point, no insignificant whitespace,
 * the same escape rules, and numbers exactly as read.
 *
 * The writer is separate from the reader because canonicalization is a property of what is stored
 * and hashed, not of what is convenient to parse. It is total: every [JsonValue] has one string.
 */
fun JsonValue.canonical(): String = buildString { appendCanonical(this) }

private fun JsonValue.appendCanonical(out: StringBuilder) {
    when (this) {
        is JsonValue.Obj -> {
            out.append('{')
            entries.sortedWith { a, b -> compareCodePoints(a.first, b.first) }
                .forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    appendJsonString(out, key)
                    out.append(':')
                    value.appendCanonical(out)
                }
            out.append('}')
        }
        is JsonValue.Arr -> {
            out.append('[')
            items.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                value.appendCanonical(out)
            }
            out.append(']')
        }
        is JsonValue.Str -> appendJsonString(out, value)
        is JsonValue.Num -> out.append(raw)
        is JsonValue.Bool -> out.append(if (value) "true" else "false")
        JsonValue.Null -> out.append("null")
    }
}

/** Code-point order, which is the protocol's order and not UTF-16 code-unit order. */
private fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val left = a.codePointAt(i)
        val right = b.codePointAt(j)
        if (left != right) return left.compareTo(right)
        i += Character.charCount(left)
        j += Character.charCount(right)
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun appendJsonString(out: StringBuilder, value: String) {
    out.append('"')
    var i = 0
    while (i < value.length) {
        when (val c = value[i]) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (c < ' ') {
                    out.append("\\u")
                    out.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        i++
    }
    out.append('"')
}

/** The object as an object, or null. Reader helpers, not a schema. */
fun JsonValue.asObjOrNull(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue.asArrOrNull(): JsonValue.Arr? = this as? JsonValue.Arr

fun JsonValue.asStringOrNull(): String? = (this as? JsonValue.Str)?.value

/** An integer in the protocol's sense: no fraction, no exponent. */
fun JsonValue.asIntOrNull(): Int? {
    val raw = (this as? JsonValue.Num)?.raw ?: return null
    if ('.' in raw || 'e' in raw || 'E' in raw) return null
    return raw.toIntOrNull()
}

fun JsonValue.asBooleanOrNull(): Boolean? = (this as? JsonValue.Bool)?.value
