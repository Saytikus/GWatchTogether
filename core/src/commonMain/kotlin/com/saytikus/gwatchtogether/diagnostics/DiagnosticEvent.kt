package com.saytikus.gwatchtogether.diagnostics

import com.saytikus.gwatchtogether.domain.result.MbResult

const val DIAGNOSTIC_SCHEMA_VERSION = 1
const val MAX_DIAGNOSTIC_EVENT_BYTES = 16 * 1024
const val MAX_DIAGNOSTIC_QUEUE_DEPTH = 2048
const val MAX_DIAGNOSTIC_SUMMARY_EVENTS = 200
const val MAX_DIAGNOSTIC_SUMMARY_BYTES = 64 * 1024

/** Signed drift values are limited to one day; this is a normalization safety bound, not a sync acceptance target. */
const val MAX_DIAGNOSTIC_DRIFT_MS = 24L * 60L * 60L * 1_000L

enum class DiagnosticSeverity {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

enum class DiagnosticRole {
    Client,
    HostedServer,
    Server,
    NativeMedia,
}

sealed class DiagnosticValue {
    data class Text(
        val value: String,
    ) : DiagnosticValue()

    data class Integer(
        val value: Long,
    ) : DiagnosticValue()

    data class Decimal(
        val value: Double,
    ) : DiagnosticValue()

    data class BooleanValue(
        val value: Boolean,
    ) : DiagnosticValue()
}

data class DiagnosticEvent(
    val eventTimestampUtc: String,
    val severity: DiagnosticSeverity,
    val eventName: String,
    val component: String,
    val role: DiagnosticRole,
    val appVersion: String,
    val buildRevision: String,
    val processInstanceId: String,
    val sequence: Long,
    val observedTimestampUtc: String? = null,
    val monotonicNanos: Long? = null,
    val operationId: String? = null,
    val durationMs: Long? = null,
    val outcome: String? = null,
    val errorCode: String? = null,
    val diagnosticId: String? = null,
    val retryAttempt: Long? = null,
    val attributes: Map<String, DiagnosticValue> = emptyMap(),
)

data class DiagnosticLossState(
    val droppedDebug: Long,
    val droppedInfo: Long,
    val droppedWarn: Long,
    val droppedError: Long,
    val writeFailures: Long,
    val isUnavailable: Boolean,
) {
    val droppedTotal: Long
        get() = droppedDebug + droppedInfo + droppedWarn + droppedError
}

enum class DiagnosticFlushStatus {
    Completed,
    DeadlineExceeded,
    Unavailable,
}

interface IDiagnosticEvents : AutoCloseable {
    fun record(event: DiagnosticEvent): MbResult<Unit>

    fun flush(timeoutMillis: Long = 2_000): DiagnosticFlushStatus

    fun lossState(): DiagnosticLossState

    override fun close()
}
