package com.saytikus.gwatchtogether.infrastructure.diagnostics

internal sealed interface JsonValue {
    data class Object(
        val fields: Map<String, JsonValue>,
    ) : JsonValue

    data class Array(
        val values: List<JsonValue>,
    ) : JsonValue

    data class StringValue(
        val value: String,
    ) : JsonValue

    data class NumberValue(
        val value: String,
    ) : JsonValue

    data object True : JsonValue

    data object False : JsonValue

    data object Null : JsonValue
}

internal class JsonParseFailure(
    message: String,
) : IllegalArgumentException(message)

internal class BoundedJsonParser(
    private val text: String,
) {
    private var position = 0
    private var depth = 0

    init {
        require(text.length <= MAX_JSON_DOCUMENT_CHARACTERS) { "JSON document is too large" }
    }

    fun parse(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        if (position != text.length) fail("trailing data")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (position >= text.length) fail("missing value")
        return when (text[position]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.StringValue(parseString())
            't' -> parseLiteral("true", JsonValue.True)
            'f' -> parseLiteral("false", JsonValue.False)
            'n' -> parseLiteral("null", JsonValue.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> fail("unexpected value")
        }
    }

    private fun parseObject(): JsonValue.Object {
        enterContainer()
        try {
            expect('{')
            val fields = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (consume('}')) return JsonValue.Object(fields)
            while (true) {
                if (fields.size == MAX_JSON_OBJECT_FIELDS) fail("object field limit")
                skipWhitespace()
                if (position >= text.length || text[position] != '"') fail("object key expected")
                val key = parseString()
                if (fields.containsKey(key)) fail("duplicate object key")
                skipWhitespace()
                expect(':')
                fields[key] = parseValue()
                skipWhitespace()
                if (consume('}')) return JsonValue.Object(fields)
                expect(',')
            }
        } finally {
            depth--
        }
    }

    private fun parseArray(): JsonValue.Array {
        enterContainer()
        try {
            expect('[')
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (consume(']')) return JsonValue.Array(values)
            while (true) {
                if (values.size == MAX_JSON_ARRAY_ITEMS) fail("array item limit")
                values += parseValue()
                skipWhitespace()
                if (consume(']')) return JsonValue.Array(values)
                expect(',')
            }
        } finally {
            depth--
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (position < text.length) {
            if (result.length == MAX_JSON_STRING_CHARACTERS) fail("string limit")
            when (val character = text[position++]) {
                '"' -> {
                    return result.toString()
                }

                '\\' -> {
                    if (position >= text.length) fail("incomplete escape")
                    when (val escape = text[position++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(parseUnicodeEscape())
                        else -> fail("invalid escape")
                    }
                }

                in '\u0000'..'\u001f' -> {
                    fail("control character in string")
                }

                else -> {
                    result.append(character)
                }
            }
        }
        fail("unterminated string")
    }

    private fun parseUnicodeEscape(): Char {
        if (position + 4 > text.length) fail("incomplete unicode escape")
        val digits = text.substring(position, position + 4)
        position += 4
        return digits.toIntOrNull(16)?.toChar() ?: fail("invalid unicode escape")
    }

    private fun parseNumber(): JsonValue.NumberValue {
        val start = position
        if (position < text.length && text[position] == '-') position++
        if (!consume('0')) {
            requireDigit()
            while (position < text.length && text[position].isDigit()) position++
        }
        if (consume('.')) {
            requireDigit()
            while (position < text.length && text[position].isDigit()) position++
        }
        if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
            position++
            if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
            requireDigit()
            while (position < text.length && text[position].isDigit()) position++
        }
        return JsonValue.NumberValue(text.substring(start, position))
    }

    private fun parseLiteral(
        literal: String,
        value: JsonValue,
    ): JsonValue {
        if (!text.regionMatches(position, literal, 0, literal.length)) fail("invalid literal")
        position += literal.length
        return value
    }

    private fun requireDigit() {
        if (position >= text.length || !text[position].isDigit()) fail("digit expected")
    }

    private fun expect(character: Char) {
        if (position >= text.length || text[position] != character) fail("expected $character")
        position++
    }

    private fun consume(character: Char): Boolean =
        if (position < text.length && text[position] == character) {
            position++
            true
        } else {
            false
        }

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    private fun enterContainer() {
        if (depth == MAX_JSON_DEPTH) fail("nesting limit")
        depth++
    }

    private fun fail(message: String): Nothing = throw JsonParseFailure("at $position: $message")
}

private const val MAX_JSON_DOCUMENT_CHARACTERS = 16 * 1024
private const val MAX_JSON_DEPTH = 64
private const val MAX_JSON_STRING_CHARACTERS = 8 * 1024
private const val MAX_JSON_OBJECT_FIELDS = 128
private const val MAX_JSON_ARRAY_ITEMS = 256
