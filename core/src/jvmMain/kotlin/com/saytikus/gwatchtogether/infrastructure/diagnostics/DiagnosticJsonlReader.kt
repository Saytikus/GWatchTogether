package com.saytikus.gwatchtogether.infrastructure.diagnostics

import com.saytikus.gwatchtogether.diagnostics.DiagnosticEvent
import com.saytikus.gwatchtogether.diagnostics.DiagnosticSeverity
import com.saytikus.gwatchtogether.diagnostics.DiagnosticValue
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_DRIFT_MS
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_EVENT_BYTES
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_SUMMARY_BYTES
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_SUMMARY_EVENTS
import com.saytikus.gwatchtogether.domain.result.DiagnosticId
import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import java.io.ByteArrayOutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Bounded, read-only normalization from stock Logback JSONL to logical DiagnosticEvent v1. */
class DiagnosticJsonlReader {
    fun read(path: Path): MbResult<List<DiagnosticEvent>> =
        try {
            var totalBytes = 0
            val events = mutableListOf<DiagnosticEvent>()
            PushbackInputStream(Files.newInputStream(path), 1).use { input ->
                while (true) {
                    val lineBytes = readBoundedLine(input) ?: break
                    if (events.size == MAX_DIAGNOSTIC_SUMMARY_EVENTS) throw JsonParseFailure("event_limit")
                    totalBytes += lineBytes.size
                    if (totalBytes > MAX_DIAGNOSTIC_SUMMARY_BYTES) throw JsonParseFailure("summary_limit")
                    events += normalize(BoundedJsonParser(decodeUtf8(lineBytes)).parse())
                }
            }
            MbResult.Success(events)
        } catch (_: JsonParseFailure) {
            failure("malformed")
        } catch (_: Exception) {
            failure("read_failed")
        }

    private fun readBoundedLine(input: PushbackInputStream): ByteArray? {
        val bytes = ByteArrayOutputStream(MAX_DIAGNOSTIC_EVENT_BYTES)
        var sawData = false
        while (true) {
            when (val byte = input.read()) {
                -1 -> {
                    return if (sawData) bytes.toByteArray() else null
                }

                '\n'.code -> {
                    return bytes.toByteArray()
                }

                '\r'.code -> {
                    val next = input.read()
                    if (next != '\n'.code && next != -1) input.unread(next)
                    return bytes.toByteArray()
                }

                else -> {
                    if (bytes.size() == MAX_DIAGNOSTIC_EVENT_BYTES) throw JsonParseFailure("event_limit")
                    bytes.write(byte)
                    sawData = true
                }
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { throw JsonParseFailure("invalid utf8") }

    private fun normalize(value: JsonValue): DiagnosticEvent {
        val root = value.objectValue()
        if (root.fields.keys.any { it !in STOCK_MEMBERS }) throw JsonParseFailure("unknown stock member")
        val physicalTimestamp = root.required("timestamp").numberValue()
        if (physicalTimestamp.toLongOrNull() == null) throw JsonParseFailure("timestamp type")
        if (root.required("loggerName").stringValue().validateText("logger name") != "gwt.diagnostics") {
            throw JsonParseFailure("logger name")
        }
        val level = root.required("level").stringValue().validateText("level")
        val pairs =
            root.required("kvpList").arrayValue().flatMap { item ->
                val fields = item.objectValue().fields
                if (fields.size != 1) throw JsonParseFailure("invalid kvpList item")
                fields.entries.map { (key, itemValue) ->
                    key to itemValue.stringValue()
                }
            }
        if (pairs.map { it.first }.toSet().size != pairs.size) throw JsonParseFailure("duplicate key")
        val keyValues = pairs.toMap()
        if (keyValues.requiredLong("gwt.schema_version") != 1L) throw JsonParseFailure("schema")
        val eventTimestamp = keyValues.requiredTimestamp("gwt.event_timestamp_utc")
        val observedTimestamp = keyValues.optionalTimestamp("gwt.observed_timestamp_utc")
        val eventName = keyValues.requiredText("gwt.event_name")
        if (!EVENT_NAME_PATTERN.matches(eventName)) throw JsonParseFailure("event name")
        val attributes =
            keyValues
                .filterKeys { it.startsWith("gwt.attr.") }
                .mapNotNull { (key, text) ->
                    val attributeName = key.removePrefix("gwt.attr.")
                    text.validateText(key)
                    if (attributeName !in SAFE_ATTRIBUTES) return@mapNotNull null
                    attributeName to parseAttribute(attributeName, text)
                }.toMap()
        val event =
            DiagnosticEvent(
                eventTimestampUtc = eventTimestamp,
                severity = severity(level),
                eventName = eventName,
                component = keyValues.requiredText("gwt.component"),
                role = keyValues.requiredText("gwt.role").toRole(),
                appVersion = keyValues.requiredText("gwt.app_version"),
                buildRevision = keyValues.requiredText("gwt.build_revision"),
                processInstanceId = keyValues.requiredText("gwt.process_instance_id"),
                sequence = keyValues.requiredNonNegativeLong("gwt.sequence"),
                observedTimestampUtc = observedTimestamp,
                monotonicNanos = keyValues.optionalNonNegativeLong("gwt.monotonic_ns"),
                operationId = keyValues.optionalText("gwt.operation_id"),
                durationMs = keyValues.optionalNonNegativeLong("gwt.duration_ms"),
                outcome = keyValues.optionalText("gwt.outcome"),
                errorCode = keyValues.optionalText("gwt.error_code"),
                diagnosticId = keyValues.optionalText("gwt.diagnostic_id"),
                retryAttempt = keyValues.optionalNonNegativeLong("gwt.retry_attempt"),
                attributes = attributes,
            )
        return DiagnosticSanitizer.sanitize(event) ?: throw JsonParseFailure("invalid event")
    }

    private fun severity(value: String): DiagnosticSeverity =
        when (value) {
            "TRACE" -> DiagnosticSeverity.Trace
            "DEBUG" -> DiagnosticSeverity.Debug
            "INFO" -> DiagnosticSeverity.Info
            "WARN" -> DiagnosticSeverity.Warn
            "ERROR" -> DiagnosticSeverity.Error
            else -> throw JsonParseFailure("severity")
        }

    private fun failure(code: String): MbResult.Failure =
        MbResult.Failure(
            MbError(
                error = DomainError.Diagnostics(code),
                retryability = Retryability.Never,
                diagnosticId = DiagnosticId("diagnostics.reader.$code"),
            ),
        )
}

private fun JsonValue.objectValue(): JsonValue.Object = this as? JsonValue.Object ?: throw JsonParseFailure("object")

private fun JsonValue.arrayValue(): List<JsonValue> =
    (this as? JsonValue.Array)?.values ?: throw JsonParseFailure("array")

private fun JsonValue.stringValue(): String =
    (this as? JsonValue.StringValue)?.value ?: throw JsonParseFailure("string")

private fun JsonValue.numberValue(): String =
    (this as? JsonValue.NumberValue)?.value ?: throw JsonParseFailure("number")

private fun JsonValue.Object.required(key: String): JsonValue = fields[key] ?: throw JsonParseFailure("missing $key")

private fun Map<String, String>.requiredText(key: String): String =
    this[key]?.takeIf(String::isNotBlank)?.validateText(key) ?: throw JsonParseFailure("missing $key")

private fun Map<String, String>.optionalText(key: String): String? =
    this[key]?.takeIf(String::isNotBlank)?.validateText(key) ?: this[key]?.let { throw JsonParseFailure("blank $key") }

private fun String.validateText(key: String): String {
    if (any { it == '\u001b' || it == '\r' || it == '\n' || it.isISOControl() }) {
        throw JsonParseFailure("unsafe text $key")
    }
    return this
}

private fun Map<String, String>.requiredLong(key: String): Long =
    requiredText(key).toLongOrNull() ?: throw JsonParseFailure("number $key")

private fun Map<String, String>.requiredNonNegativeLong(key: String): Long =
    requiredLong(key).takeIf { it >= 0 } ?: throw JsonParseFailure("negative $key")

private fun Map<String, String>.optionalNonNegativeLong(key: String): Long? =
    this[key]?.toLongOrNull()?.takeIf { it >= 0 }
        ?: this[key]?.let { throw JsonParseFailure("number $key") }

private fun Map<String, String>.requiredTimestamp(key: String): String {
    val value = requiredText(key)
    if (!value.endsWith("Z") || runCatching { Instant.parse(value) }.isFailure) {
        throw JsonParseFailure("timestamp $key")
    }
    return value
}

private fun Map<String, String>.optionalTimestamp(key: String): String? =
    this[key]?.let {
        if (!it.endsWith("Z") || runCatching { Instant.parse(it) }.isFailure) {
            throw JsonParseFailure("timestamp $key")
        }
        it
    }

private fun parseAttribute(
    name: String,
    value: String,
): DiagnosticValue =
    when (name) {
        in INTEGER_ATTRIBUTES -> {
            value.toLongOrNull()?.takeIf { it >= 0 }?.let(DiagnosticValue::Integer)
                ?: throw JsonParseFailure("attribute $name")
        }

        "drift_ms" -> {
            value
                .toLongOrNull()
                ?.takeIf { it in -MAX_DIAGNOSTIC_DRIFT_MS..MAX_DIAGNOSTIC_DRIFT_MS }
                ?.let(DiagnosticValue::Integer)
                ?: throw JsonParseFailure("attribute $name")
        }

        "cpu_percent" -> {
            value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(DiagnosticValue::Decimal)
                ?: throw JsonParseFailure("attribute $name")
        }

        else -> {
            DiagnosticValue.Text(value)
        }
    }

private fun String.toRole() =
    when (this) {
        "client" -> com.saytikus.gwatchtogether.diagnostics.DiagnosticRole.Client
        "hosted_server" -> com.saytikus.gwatchtogether.diagnostics.DiagnosticRole.HostedServer
        "server" -> com.saytikus.gwatchtogether.diagnostics.DiagnosticRole.Server
        "native_media" -> com.saytikus.gwatchtogether.diagnostics.DiagnosticRole.NativeMedia
        else -> throw JsonParseFailure("role")
    }

private val EVENT_NAME_PATTERN = Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+")
private val SAFE_ATTRIBUTES =
    setOf(
        "operation_id",
        "target",
        "transport",
        "endpoint_alias",
        "retry_attempt",
        "auth_method",
        "failure_code",
        "engine_capability",
        "source_kind",
        "session_alias",
        "epoch",
        "drift_ms",
        "device_kind",
        "permission_state",
        "operation_kind",
        "bytes",
        "lifecycle_state",
        "reason_code",
        "error_code",
        "cpu_percent",
        "memory_bytes",
        "queue_depth",
        "dropped_count",
        "summary_event_count",
        "summary_bytes",
        "redaction_count",
    )
private val INTEGER_ATTRIBUTES =
    setOf(
        "retry_attempt",
        "epoch",
        "bytes",
        "memory_bytes",
        "queue_depth",
        "dropped_count",
        "summary_event_count",
        "summary_bytes",
        "redaction_count",
    )
private val STOCK_MEMBERS = setOf("timestamp", "level", "loggerName", "kvpList")
