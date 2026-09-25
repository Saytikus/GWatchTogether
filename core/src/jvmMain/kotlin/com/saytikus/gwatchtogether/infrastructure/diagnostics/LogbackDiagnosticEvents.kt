package com.saytikus.gwatchtogether.infrastructure.diagnostics

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.core.OutputStreamAppender
import com.saytikus.gwatchtogether.diagnostics.DiagnosticEvent
import com.saytikus.gwatchtogether.diagnostics.DiagnosticFlushStatus
import com.saytikus.gwatchtogether.diagnostics.DiagnosticLossState
import com.saytikus.gwatchtogether.diagnostics.DiagnosticRole
import com.saytikus.gwatchtogether.diagnostics.DiagnosticSeverity
import com.saytikus.gwatchtogether.diagnostics.DiagnosticValue
import com.saytikus.gwatchtogether.diagnostics.IDiagnosticEvents
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_EVENT_BYTES
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_QUEUE_DEPTH
import com.saytikus.gwatchtogether.domain.result.DiagnosticId
import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import com.saytikus.gwatchtogether.platform.IClock
import com.saytikus.gwatchtogether.platform.SystemClock
import org.slf4j.helpers.BasicMDCAdapter
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.slf4j.event.Level as Slf4jLevel

/** Configuration for the local-only stock Logback JSONL sink. */
data class DiagnosticLogConfiguration(
    val directory: Path,
    val queueCapacity: Int = MAX_DIAGNOSTIC_QUEUE_DEPTH,
    val maxEventBytes: Int = 16 * 1024,
    val maxFileBytes: Long = 100L * 1024L * 1024L,
    val retentionMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    val clock: IClock = SystemClock,
) {
    init {
        require(queueCapacity in 1..MAX_DIAGNOSTIC_QUEUE_DEPTH)
        require(maxEventBytes in 1..MAX_DIAGNOSTIC_EVENT_BYTES)
        require(maxFileBytes > 0)
        require(retentionMillis > 0)
    }
}

class LogbackDiagnosticEvents(
    private val configuration: DiagnosticLogConfiguration,
) : IDiagnosticEvents {
    private sealed interface QueueItem {
        data class Event(
            val value: DiagnosticEvent,
        ) : QueueItem

        data class Flush(
            val completed: CountDownLatch,
        ) : QueueItem

        data object Stop : QueueItem
    }

    private val queue = ArrayBlockingQueue<QueueItem>(configuration.queueCapacity)
    private val queueLock = Any()
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val unavailable = AtomicBoolean(false)
    private val droppedDebug = AtomicLong()
    private val droppedInfo = AtomicLong()
    private val droppedWarn = AtomicLong()
    private val droppedError = AtomicLong()
    private val writeFailures = AtomicLong()
    private val debugLock = Any()
    private var debugStartedMonotonicNanos = -1L
    private var debugUntilMonotonicNanos = 0L
    private var lastDebugMonotonicNanos = -1L
    private val rotationSequence = AtomicLong()
    private var segmentStartedEpochMillis = 0L
    private val sinkLock = Any()
    private val loggerContext =
        LoggerContext().apply {
            // Standalone contexts do not inherit SLF4J's adapter; provide an empty private one for event creation.
            setMDCAdapter(BasicMDCAdapter())
            start()
        }
    private val logger: Logger =
        loggerContext.getLogger(DIAGNOSTIC_LOGGER_NAME).apply {
            isAdditive = false
            level = Level.TRACE
        }
    private var outputStream: FileOutputStream? = null
    private var appender: OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>? = null
    private val worker =
        thread(
            start = true,
            isDaemon = true,
            name = "gwt-diagnostics-writer",
        ) {
            writeLoop()
        }

    init {
        runCatching {
            Files.createDirectories(configuration.directory)
            openSink()
        }.onFailure {
            markUnavailable()
        }
    }

    /** Enables verbose records for at most the contract's 15-minute diagnostic window. */
    fun enableDebugForMinutes(minutes: Long = 15) {
        synchronized(debugLock) {
            val now = configuration.clock.monotonicNanos()
            if (now < 0 || (lastDebugMonotonicNanos >= 0 && now < lastDebugMonotonicNanos) || minutes !in 1..15) {
                disableDebug(now)
                return
            }
            val duration = runCatching { Math.multiplyExact(minutes, DEBUG_NANOS_PER_MINUTE) }.getOrNull()
            val deadline = duration?.let { runCatching { Math.addExact(now, it) }.getOrNull() }
            if (deadline == null || deadline <= now) {
                disableDebug(now)
                return
            }
            lastDebugMonotonicNanos = now
            debugStartedMonotonicNanos = now
            debugUntilMonotonicNanos = deadline
        }
    }

    override fun record(event: DiagnosticEvent): MbResult<Unit> {
        if (closed.get()) return failure("closed")
        if (unavailable.get()) return failure("sink_unavailable")
        if (
            event.severity == DiagnosticSeverity.Trace ||
            (
                event.severity == DiagnosticSeverity.Debug &&
                    !isDebugEnabledAt(configuration.clock.monotonicNanos())
            )
        ) {
            incrementDropped(DiagnosticSeverity.Debug)
            return MbResult.Success(Unit)
        }
        val sanitized =
            DiagnosticSanitizer.sanitize(event, minOf(configuration.maxEventBytes, MAX_DIAGNOSTIC_EVENT_BYTES))
                ?: return failure("invalid_event")
        if (enqueue(sanitized)) return MbResult.Success(Unit)
        if (closed.get()) return failure("closed")

        incrementDropped(sanitized.severity)
        return MbResult.Success(Unit)
    }

    override fun flush(timeoutMillis: Long): DiagnosticFlushStatus {
        if (unavailable.get()) return DiagnosticFlushStatus.Unavailable
        if (closed.get() || timeoutMillis <= 0) return DiagnosticFlushStatus.DeadlineExceeded
        val completed = CountDownLatch(1)
        val offered =
            synchronized(queueLock) {
                if (closed.get() || unavailable.get()) false else queue.offer(QueueItem.Flush(completed))
            }
        if (!offered) return DiagnosticFlushStatus.DeadlineExceeded
        if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) return DiagnosticFlushStatus.DeadlineExceeded
        return if (unavailable.get()) DiagnosticFlushStatus.Unavailable else DiagnosticFlushStatus.Completed
    }

    override fun lossState(): DiagnosticLossState =
        DiagnosticLossState(
            droppedDebug = droppedDebug.get(),
            droppedInfo = droppedInfo.get(),
            droppedWarn = droppedWarn.get(),
            droppedError = droppedError.get(),
            writeFailures = writeFailures.get(),
            isUnavailable = unavailable.get(),
        )

    override fun close() {
        synchronized(queueLock) {
            if (!closed.compareAndSet(false, true)) return
            stopRequested.set(true)
            queue.offer(QueueItem.Stop)
        }
        worker.join(2_000)
        synchronized(sinkLock) {
            try {
                retireSink()
            } finally {
                logger.detachAndStopAllAppenders()
                loggerContext.stop()
            }
        }
    }

    private fun isDebugEnabledAt(now: Long): Boolean =
        synchronized(debugLock) {
            if (now < 0 || (lastDebugMonotonicNanos >= 0 && now < lastDebugMonotonicNanos)) {
                disableDebug(now)
                return false
            }
            lastDebugMonotonicNanos = now
            if (debugStartedMonotonicNanos < 0 || debugUntilMonotonicNanos <= debugStartedMonotonicNanos) return false
            if (now >= debugUntilMonotonicNanos) {
                disableDebug(now)
                return false
            }
            true
        }

    private fun disableDebug(now: Long) {
        if (now >= 0) lastDebugMonotonicNanos = maxOf(lastDebugMonotonicNanos, now)
        debugUntilMonotonicNanos = 0
        debugStartedMonotonicNanos = -1
    }

    private fun writeLoop() {
        while (true) {
            if (stopRequested.get() && queue.isEmpty()) {
                flushSink()
                return
            }
            when (val item = runCatching { queue.take() }.getOrNull() ?: return) {
                is QueueItem.Event -> {
                    write(item.value)
                }

                is QueueItem.Flush -> {
                    flushSink()
                    item.completed.countDown()
                }

                QueueItem.Stop -> {
                    drainRemaining()
                    flushSink()
                    return
                }
            }
        }
    }

    private fun drainRemaining() {
        while (true) {
            when (val item = queue.poll() ?: return) {
                is QueueItem.Event -> {
                    write(item.value)
                }

                is QueueItem.Flush -> {
                    flushSink()
                    item.completed.countDown()
                }

                QueueItem.Stop -> {
                    return
                }
            }
        }
    }

    private fun write(event: DiagnosticEvent) {
        if (unavailable.get()) {
            incrementDropped(event.severity)
            return
        }
        synchronized(sinkLock) {
            runCatching {
                rotateIfNeeded()
                val builder = logger.atLevel(event.severity.toSlf4j())
                builder.addKeyValue("gwt.schema_version", 1)
                builder.addKeyValue("gwt.event_timestamp_utc", event.eventTimestampUtc)
                builder.addKeyValue("gwt.event_name", event.eventName)
                builder.addKeyValue("gwt.role", event.role.stockValue)
                builder.addKeyValue("gwt.component", event.component)
                builder.addKeyValue("gwt.app_version", event.appVersion)
                builder.addKeyValue("gwt.build_revision", event.buildRevision)
                builder.addKeyValue("gwt.process_instance_id", event.processInstanceId)
                builder.addKeyValue("gwt.sequence", event.sequence)
                event.observedTimestampUtc?.let { builder.addKeyValue("gwt.observed_timestamp_utc", it) }
                event.monotonicNanos?.let { builder.addKeyValue("gwt.monotonic_ns", it) }
                event.operationId?.let { builder.addKeyValue("gwt.operation_id", it) }
                event.durationMs?.let { builder.addKeyValue("gwt.duration_ms", it) }
                event.outcome?.let { builder.addKeyValue("gwt.outcome", it) }
                event.errorCode?.let { builder.addKeyValue("gwt.error_code", it) }
                event.diagnosticId?.let { builder.addKeyValue("gwt.diagnostic_id", it) }
                event.retryAttempt?.let { builder.addKeyValue("gwt.retry_attempt", it) }
                event.attributes.forEach { (key, value) ->
                    builder.addKeyValue("gwt.attr.$key", value.stockValue)
                }
                builder.log()
                enforceRetention()
            }.onFailure {
                writeFailures.incrementAndGet()
                incrementDropped(event.severity)
                markUnavailable()
            }
        }
    }

    private fun openSink() {
        val path = configuration.directory.resolve("gwatch-diagnostics.jsonl")
        if (Files.exists(path)) {
            segmentStartedEpochMillis =
                runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java).creationTime().toMillis()
                }.getOrDefault(configuration.clock.epochMillis())
            if (isSegmentExpired()) {
                Files.move(path, nextRotatedPath(), StandardCopyOption.REPLACE_EXISTING)
                enforceRetention()
                segmentStartedEpochMillis = configuration.clock.epochMillis()
            }
        } else {
            segmentStartedEpochMillis = configuration.clock.epochMillis()
        }
        val stream = FileOutputStream(path.toFile(), true)
        outputStream = stream
        val jsonEncoder =
            JsonEncoder().apply {
                context = logger.loggerContext
                setWithTimestamp(true)
                setWithLevel(true)
                setWithLoggerName(true)
                setWithKVPList(true)
                setWithSequenceNumber(false)
                setWithNanoseconds(false)
                setWithThreadName(false)
                setWithContext(false)
                setWithMarkers(false)
                setWithMDC(false)
                setWithMessage(false)
                setWithArguments(false)
                setWithThrowable(false)
                setWithFormattedMessage(false)
                start()
            }
        val outputAppender =
            OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
                context = logger.loggerContext
                encoder = jsonEncoder
                outputStream = stream
                start()
            }
        appender = outputAppender
        logger.addAppender(outputAppender)
    }

    private fun rotateIfNeeded() {
        val path = configuration.directory.resolve("gwatch-diagnostics.jsonl")
        if (!Files.exists(path)) return
        val size = Files.size(path)
        val ageExpired = isSegmentExpired()
        val rotationThreshold = (configuration.maxFileBytes - configuration.maxEventBytes).coerceAtLeast(0)
        if (!ageExpired && (size == 0L || size < rotationThreshold)) return
        retireSink()
        val rotated = nextRotatedPath()
        Files.move(path, rotated, StandardCopyOption.REPLACE_EXISTING)
        enforceRetention()
        openSink()
    }

    private fun isSegmentExpired(now: Long = configuration.clock.epochMillis()): Boolean =
        now >= segmentStartedEpochMillis && now - segmentStartedEpochMillis >= configuration.retentionMillis

    private fun nextRotatedPath(): Path {
        var path: Path
        do {
            path =
                configuration.directory.resolve(
                    "gwatch-diagnostics-${configuration.clock.epochMillis()}-${rotationSequence.incrementAndGet()}.jsonl",
                )
        } while (Files.exists(path))
        return path
    }

    private fun enforceRetention() {
        val cutoff = configuration.clock.epochMillis() - configuration.retentionMillis
        val rotatedFiles =
            Files.list(configuration.directory).use { paths ->
                paths
                    .filter { it.fileName.toString().startsWith("gwatch-diagnostics-") }
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .toList()
            }
        rotatedFiles
            .filter { runCatching { Files.getLastModifiedTime(it).toMillis() < cutoff }.getOrDefault(false) }
            .forEach { runCatching { Files.deleteIfExists(it) } }
        var retainedBytes =
            rotatedFiles
                .filter { Files.exists(it) }
                .sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        retainedBytes +=
            runCatching {
                Files.size(configuration.directory.resolve("gwatch-diagnostics.jsonl"))
            }.getOrDefault(0L)
        rotatedFiles
            .filter { Files.exists(it) }
            .sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(Long.MAX_VALUE) }
            .forEach { path ->
                if (retainedBytes > configuration.maxFileBytes) {
                    val bytes = runCatching { Files.size(path) }.getOrDefault(0L)
                    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) retainedBytes -= bytes
                }
            }
    }

    private fun retireSink() {
        val currentAppender = appender
        appender = null
        val currentOutputStream = outputStream
        outputStream = null
        try {
            if (currentAppender != null) {
                logger.detachAppender(currentAppender)
                currentAppender.stop()
            }
        } finally {
            currentOutputStream?.close()
        }
    }

    private fun flushSink() {
        synchronized(sinkLock) {
            runCatching {
                rotateIfNeeded()
                outputStream?.flush()
                enforceRetention()
            }.onFailure {
                writeFailures.incrementAndGet()
                markUnavailable()
            }
        }
    }

    private fun markUnavailable() {
        synchronized(sinkLock) {
            runCatching { retireSink() }
        }
        synchronized(queueLock) {
            if (!unavailable.compareAndSet(false, true)) return
            while (true) {
                when (val item = queue.poll() ?: return) {
                    is QueueItem.Event -> {
                        incrementDropped(item.value.severity)
                    }

                    is QueueItem.Flush -> {
                        item.completed.countDown()
                    }

                    QueueItem.Stop -> {
                        queue.offer(item)
                        return
                    }
                }
            }
        }
    }

    private fun enqueue(event: DiagnosticEvent): Boolean =
        synchronized(queueLock) {
            if (closed.get() || unavailable.get()) return false
            if (queue.offer(QueueItem.Event(event))) return true
            val candidate =
                queue
                    .firstOrNull { item ->
                        item is QueueItem.Event &&
                            item.value.severity.priority < event.severity.priority
                    } as? QueueItem.Event
            if (candidate != null && queue.remove(candidate)) {
                incrementDropped(candidate.value.severity)
                return queue.offer(QueueItem.Event(event))
            }
            false
        }

    private fun incrementDropped(severity: DiagnosticSeverity) {
        when (severity) {
            DiagnosticSeverity.Trace, DiagnosticSeverity.Debug -> droppedDebug.incrementAndGet()
            DiagnosticSeverity.Info -> droppedInfo.incrementAndGet()
            DiagnosticSeverity.Warn -> droppedWarn.incrementAndGet()
            DiagnosticSeverity.Error -> droppedError.incrementAndGet()
        }
    }

    private fun failure(code: String): MbResult.Failure =
        MbResult.Failure(
            MbError(
                error = DomainError.Diagnostics(code),
                retryability = Retryability.AfterBackoff,
                diagnosticId = DiagnosticId("diagnostics.$code"),
            ),
        )
}

private const val DIAGNOSTIC_LOGGER_NAME = "gwt.diagnostics"
private const val DEBUG_NANOS_PER_MINUTE = 60_000_000_000L

private val DiagnosticRole.stockValue: String
    get() =
        when (this) {
            DiagnosticRole.Client -> "client"
            DiagnosticRole.HostedServer -> "hosted_server"
            DiagnosticRole.Server -> "server"
            DiagnosticRole.NativeMedia -> "native_media"
        }

private val DiagnosticSeverity.priority: Int
    get() =
        when (this) {
            DiagnosticSeverity.Trace, DiagnosticSeverity.Debug -> 0
            DiagnosticSeverity.Info -> 1
            DiagnosticSeverity.Warn -> 2
            DiagnosticSeverity.Error -> 3
        }

private fun DiagnosticSeverity.toSlf4j(): Slf4jLevel =
    when (this) {
        DiagnosticSeverity.Trace -> Slf4jLevel.TRACE
        DiagnosticSeverity.Debug -> Slf4jLevel.DEBUG
        DiagnosticSeverity.Info -> Slf4jLevel.INFO
        DiagnosticSeverity.Warn -> Slf4jLevel.WARN
        DiagnosticSeverity.Error -> Slf4jLevel.ERROR
    }

private val DiagnosticValue.stockValue: Any
    get() =
        when (this) {
            is DiagnosticValue.Text -> value
            is DiagnosticValue.Integer -> value
            is DiagnosticValue.Decimal -> value
            is DiagnosticValue.BooleanValue -> value
        }
