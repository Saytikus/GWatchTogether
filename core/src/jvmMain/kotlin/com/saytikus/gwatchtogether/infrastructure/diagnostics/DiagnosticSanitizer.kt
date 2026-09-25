package com.saytikus.gwatchtogether.infrastructure.diagnostics

import com.saytikus.gwatchtogether.diagnostics.DiagnosticEvent
import com.saytikus.gwatchtogether.diagnostics.DiagnosticValue
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_DRIFT_MS
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_EVENT_BYTES
import java.time.Instant

internal object DiagnosticSanitizer {
    private val eventNamePattern = Regex("[a-z][a-z0-9]*(\\.[a-z0-9]+)+")
    private val eventNames =
        setOf(
            "app.bootstrap.started",
            "app.bootstrap.completed",
            "connection.attempted",
            "connection.completed",
            "auth.completed",
            "watch.preflight.completed",
            "watch.barrier.completed",
            "watch.resync.completed",
            "device.permission.changed",
            "storage.operation.completed",
            "tray.lifecycle.changed",
            "window.lifecycle.changed",
            "native.lifecycle.changed",
            "sidecar.lifecycle.changed",
            "resource.summary",
            "diagnostic.export.completed",
        )
    private val components =
        setOf(
            "app",
            "connection",
            "auth",
            "watch",
            "device",
            "storage",
            "tray",
            "window",
            "native",
            "sidecar",
            "resource",
            "diagnostic",
        )
    private val safeCodePattern = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    private val errorCodes =
        setOf(
            "cancelled",
            "closed",
            "disk_full",
            "failed",
            "invalid_event",
            "malformed",
            "offline",
            "permission_denied",
            "read_failed",
            "sink_unavailable",
            "summary_limit",
            "timeout",
            "unauthorized",
            "unavailable",
            "unsupported",
        )
    private val opaqueIdPattern = Regex("[a-z][a-z0-9-]{0,63}")
    private val safeAttributes =
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

    fun sanitize(
        event: DiagnosticEvent,
        maxBytes: Int = MAX_DIAGNOSTIC_EVENT_BYTES,
    ): DiagnosticEvent? {
        if (maxBytes <= 0 || event.attributes.size > 24) return null
        if (event.sequence < 0 || (event.monotonicNanos != null && event.monotonicNanos < 0)) return null
        if (event.durationMs != null && event.durationMs < 0) return null
        if (event.retryAttempt != null && event.retryAttempt < 0) return null

        val cleanEventTimestamp = cleanText(event.eventTimestampUtc, 128) ?: return null
        val cleanEventName = cleanText(event.eventName, 128) ?: return null
        val cleanComponent = cleanText(event.component, 96) ?: return null
        val cleanAppVersion = cleanText(event.appVersion, 96) ?: return null
        val cleanBuildRevision = cleanText(event.buildRevision, 96) ?: return null
        val cleanProcessInstanceId = cleanText(event.processInstanceId, 96) ?: return null
        if (
            !isUtcTimestamp(cleanEventTimestamp) ||
            cleanEventName !in eventNames ||
            !eventNamePattern.matches(cleanEventName) ||
            cleanComponent !in components ||
            !APP_VERSION_PATTERN.matches(cleanAppVersion) ||
            !BUILD_REVISION_PATTERN.matches(cleanBuildRevision) ||
            !PROCESS_INSTANCE_PATTERN.matches(cleanProcessInstanceId)
        ) {
            return null
        }

        val observedTimestamp = event.observedTimestampUtc?.let { cleanText(it, 128) ?: return null }
        if (observedTimestamp != null && !isUtcTimestamp(observedTimestamp)) return null
        val operationId = event.operationId?.let { cleanText(it, 96) ?: return null }
        if (operationId != null && !operationIdPattern.matches(operationId)) return null
        val outcome = event.outcome?.let { cleanText(it, 32) ?: return null }
        if (outcome != null && outcome !in outcomes) return null
        val errorCode = event.errorCode?.let { cleanText(it, 96) ?: return null }
        if (errorCode != null && (errorCode !in errorCodes || !safeCodePattern.matches(errorCode))) return null
        val diagnosticId = event.diagnosticId?.let { cleanText(it, 96) ?: return null }
        if (
            diagnosticId != null &&
            (
                !diagnosticIdPattern.matches(diagnosticId) ||
                    diagnosticId.removePrefix("diagnostics.") !in errorCodes
            )
        ) {
            return null
        }
        val attributes = mutableMapOf<String, DiagnosticValue>()
        for ((key, value) in event.attributes) {
            if (key !in safeAttributes) continue
            attributes[key] = sanitizeAttribute(key, value) ?: return null
        }
        val sanitized =
            event.copy(
                eventTimestampUtc = cleanEventTimestamp,
                eventName = cleanEventName,
                component = cleanComponent,
                appVersion = cleanAppVersion,
                buildRevision = cleanBuildRevision,
                processInstanceId = cleanProcessInstanceId,
                observedTimestampUtc = observedTimestamp,
                operationId = operationId,
                outcome = outcome,
                errorCode = errorCode,
                diagnosticId = diagnosticId,
                attributes = attributes,
            )
        return trimToEventBound(sanitized, maxBytes)
    }

    private fun sanitizeAttribute(
        key: String,
        value: DiagnosticValue,
    ): DiagnosticValue? =
        when (key) {
            in integerAttributes -> {
                (value as? DiagnosticValue.Integer)?.takeIf { it.value >= 0 }
            }

            "drift_ms" -> {
                (value as? DiagnosticValue.Integer)?.takeIf {
                    it.value in -MAX_DIAGNOSTIC_DRIFT_MS..MAX_DIAGNOSTIC_DRIFT_MS
                }
            }

            "cpu_percent" -> {
                (value as? DiagnosticValue.Decimal)?.takeIf { it.value.isFinite() }
            }

            else -> {
                (value as? DiagnosticValue.Text)?.let {
                    val text = cleanText(it.value, 512) ?: return@let null
                    if (isSafeStringAttribute(key, text)) DiagnosticValue.Text(text) else null
                }
            }
        }

    private val outcomes = setOf("succeeded", "failed", "cancelled")
    private val operationIdPattern = Regex("operation-[a-f0-9]{16,64}")
    private val diagnosticIdPattern = Regex("diagnostics\\.[a-z0-9_.-]{1,80}")
    private val APP_VERSION_PATTERN = Regex("POC-0-dev\\.[a-z0-9.-]+")
    private val BUILD_REVISION_PATTERN = Regex("(?:local|git-[a-f0-9]{7,64})")
    private val PROCESS_INSTANCE_PATTERN = Regex("process-[a-f0-9]{16,64}")

    private val integerAttributes =
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

    private fun cleanText(
        value: String,
        maxLength: Int,
    ): String? {
        if (value.length > maxLength || value.any { it == '\u001b' || it == '\r' || it == '\n' || it.isISOControl() }) {
            return null
        }
        return value
    }

    private fun isUtcTimestamp(value: String): Boolean =
        value.endsWith("Z") && runCatching { Instant.parse(value) }.isSuccess

    private fun isSafeStringAttribute(
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "target" -> {
                value in setOf("client", "hosted_server", "server", "native_media", "desktop", "android", "web")
            }

            "transport" -> {
                value in setOf("https", "wss", "loopback")
            }

            "endpoint_alias" -> {
                value.startsWith("endpoint-") &&
                    opaqueIdPattern.matches(value.removePrefix("endpoint-"))
            }

            "operation_id" -> {
                value.startsWith("operation-") &&
                    opaqueIdPattern.matches(value.removePrefix("operation-"))
            }

            "auth_method" -> {
                value in setOf("pairing_code", "session")
            }

            "engine_capability" -> {
                value in setOf("h264", "vp8", "software", "hardware")
            }

            "source_kind" -> {
                value == "local_file"
            }

            "session_alias" -> {
                value.startsWith("session-") && opaqueIdPattern.matches(value.removePrefix("session-"))
            }

            "device_kind" -> {
                value in setOf("microphone", "output", "camera")
            }

            "permission_state" -> {
                value in setOf("granted", "denied", "prompt", "unsupported")
            }

            "operation_kind" -> {
                value in setOf("open", "read", "write", "prepare", "start", "stop", "seek")
            }

            "lifecycle_state" -> {
                value in
                    setOf("started", "completed", "stopping", "stopped", "active", "idle", "hidden", "visible")
            }

            "reason_code", "failure_code", "error_code" -> {
                value in errorCodes && safeCodePattern.matches(value)
            }

            else -> {
                false
            }
        }

    private fun trimToEventBound(
        event: DiagnosticEvent,
        maxBytes: Int,
    ): DiagnosticEvent? = event.takeIf { estimatedBytes(it) <= maxBytes }

    /** Conservative upper bound for stock JsonEncoder output, including JSON escaping and fixed members. */
    private fun estimatedBytes(event: DiagnosticEvent): Int {
        val textValues =
            listOf(
                event.eventTimestampUtc,
                event.eventName,
                event.component,
                event.appVersion,
                event.buildRevision,
                event.processInstanceId,
                event.observedTimestampUtc,
                event.operationId,
                event.outcome,
                event.errorCode,
                event.diagnosticId,
            )
        val fixedBytes = textValues.sumOf { it?.let(::escapedUtf8Bytes) ?: 0 } + 1_024
        val attributeBytes =
            event.attributes.entries.sumOf { (key, value) ->
                escapedUtf8Bytes("gwt.attr.$key") + stockValueBytes(value) + 16
            }
        return fixedBytes + attributeBytes
    }

    private fun escapedUtf8Bytes(value: String): Int =
        value.toByteArray(Charsets.UTF_8).size + value.count { it == '"' || it == '\\' }

    private fun stockValueBytes(value: DiagnosticValue): Int =
        when (value) {
            is DiagnosticValue.Text -> escapedUtf8Bytes(value.value)
            is DiagnosticValue.Integer -> 20
            is DiagnosticValue.Decimal -> 24
            is DiagnosticValue.BooleanValue -> 5
        }
}
