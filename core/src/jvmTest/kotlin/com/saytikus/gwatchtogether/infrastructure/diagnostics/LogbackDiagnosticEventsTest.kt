package com.saytikus.gwatchtogether.infrastructure.diagnostics

import com.saytikus.gwatchtogether.diagnostics.DiagnosticEvent
import com.saytikus.gwatchtogether.diagnostics.DiagnosticFlushStatus
import com.saytikus.gwatchtogether.diagnostics.DiagnosticRole
import com.saytikus.gwatchtogether.diagnostics.DiagnosticSeverity
import com.saytikus.gwatchtogether.diagnostics.DiagnosticValue
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_DRIFT_MS
import com.saytikus.gwatchtogether.diagnostics.MAX_DIAGNOSTIC_EVENT_BYTES
import com.saytikus.gwatchtogether.platform.FixedClock
import com.saytikus.gwatchtogether.platform.IClock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LogbackDiagnosticEventsTest {
    @Test
    fun writesStockJsonEncoderKvpListAndDropsUnsafeAttributesBeforeDisk() {
        val directory = Files.createTempDirectory("gwt-diagnostics-test")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        val rejected =
            diagnostics.record(
                event(
                    attributes =
                        mapOf(
                            "endpoint_alias" to DiagnosticValue.Text("C:\\Users\\private\\movie.mkv"),
                            "token" to DiagnosticValue.Text("seeded-secret-token"),
                            "reason_code" to DiagnosticValue.Text("bad\r\n\u001b[31mtext"),
                        ),
                ),
            )
        assertTrue(rejected is com.saytikus.gwatchtogether.domain.result.MbResult.Failure)
        diagnostics.record(
            event(
                attributes =
                    mapOf(
                        "endpoint_alias" to DiagnosticValue.Text("endpoint-local"),
                        "reason_code" to DiagnosticValue.Text("failed"),
                    ),
            ),
        )

        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()
        val lines = Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl"))
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("{"))
        assertTrue(lines.single().endsWith("}"))
        assertTrue(lines.single().contains("kvpList"))
        assertTrue(lines.single().contains("\"loggerName\":\"gwt.diagnostics\""))
        assertTrue(lines.single().contains("gwt.event_name"))
        assertTrue(lines.single().contains("endpoint-local"))
        assertFalse(lines.single().contains("seeded-secret-token"))
        assertFalse(lines.single().contains('\r'))
        assertFalse(lines.single().contains('\n'))
        assertFalse(lines.single().contains("\u001b"))
    }

    @Test
    fun debugIsDisabledByDefaultAndCanOnlyBeEnabledForBoundedWindow() {
        val directory = Files.createTempDirectory("gwt-diagnostics-debug")
        val diagnostics =
            LogbackDiagnosticEvents(
                DiagnosticLogConfiguration(directory, clock = FixedClock(100, 1)),
            )
        diagnostics.record(event(severity = DiagnosticSeverity.Debug))
        assertEquals(1, diagnostics.lossState().droppedDebug)
        diagnostics.enableDebugForMinutes()
        diagnostics.record(event(sequence = 2, severity = DiagnosticSeverity.Debug))
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()
        assertEquals(1, Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl")).size)
    }

    @Test
    fun debugExpiresAtExactMonotonicDeadlineAndIgnoresWallClockRollback() {
        val directory = Files.createTempDirectory("gwt-diagnostics-debug-monotonic")
        val clock = MutableTestClock(epoch = 10_000, monotonic = 1_000)
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory, clock = clock))
        diagnostics.enableDebugForMinutes(1)

        diagnostics.record(event(sequence = 2, severity = DiagnosticSeverity.Debug))
        clock.epoch = Long.MIN_VALUE
        clock.monotonic = 1_000 + 60_000_000_000L - 1
        diagnostics.record(event(sequence = 3, severity = DiagnosticSeverity.Debug))
        clock.monotonic = 1_000 + 60_000_000_000L
        diagnostics.record(event(sequence = 4, severity = DiagnosticSeverity.Debug))

        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()
        val normalized =
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(
                DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl")),
            ).value
        assertEquals(listOf(2L, 3L), normalized.map { it.sequence })
    }

    @Test
    fun debugMonotonicRollbackAfterForwardObservationFailsClosed() {
        val directory = Files.createTempDirectory("gwt-diagnostics-debug-rollback")
        val clock = MutableTestClock(epoch = 10_000, monotonic = 1_000)
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory, clock = clock))

        diagnostics.enableDebugForMinutes(1)
        diagnostics.record(event(sequence = 2, severity = DiagnosticSeverity.Debug))
        clock.monotonic = 900
        diagnostics.record(event(sequence = 3, severity = DiagnosticSeverity.Debug))
        diagnostics.enableDebugForMinutes(1)
        clock.monotonic = 999
        diagnostics.record(event(sequence = 4, severity = DiagnosticSeverity.Debug))
        clock.monotonic = 1_000 + 60_000_000_000L - 1
        diagnostics.record(event(sequence = 5, severity = DiagnosticSeverity.Debug))

        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()
        val normalized =
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(
                DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl")),
            ).value
        assertEquals(listOf(2L), normalized.map { it.sequence })
    }

    @Test
    fun debugClockRollbackAndUnsafeNumericBoundsFailClosed() {
        val directory = Files.createTempDirectory("gwt-diagnostics-debug-bounds")
        val clock = MutableTestClock(epoch = 10_000, monotonic = 1_000)
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory, clock = clock))

        diagnostics.enableDebugForMinutes(15)
        clock.monotonic = 999
        diagnostics.record(event(sequence = 2, severity = DiagnosticSeverity.Debug))
        clock.monotonic = 1_000 + 15 * 60_000_000_000L
        diagnostics.record(event(sequence = 3, severity = DiagnosticSeverity.Debug))
        diagnostics.enableDebugForMinutes(16)
        diagnostics.record(event(sequence = 4, severity = DiagnosticSeverity.Debug))
        clock.monotonic = Long.MAX_VALUE
        diagnostics.enableDebugForMinutes()
        diagnostics.record(event(sequence = 5, severity = DiagnosticSeverity.Debug))

        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()
        assertTrue(Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl")).isEmpty())
    }

    @Test
    fun simultaneouslyLiveSinksKeepLoggerContextsAndFilesIsolated() {
        val firstDirectory = Files.createTempDirectory("gwt-diagnostics-isolation-first")
        val secondDirectory = Files.createTempDirectory("gwt-diagnostics-isolation-second")
        val first = LogbackDiagnosticEvents(DiagnosticLogConfiguration(firstDirectory))
        val second = LogbackDiagnosticEvents(DiagnosticLogConfiguration(secondDirectory))

        repeat(10) { index ->
            first.record(
                event(
                    sequence = index.toLong(),
                    attributes = mapOf("endpoint_alias" to DiagnosticValue.Text("endpoint-first")),
                ),
            )
            second.record(
                event(
                    sequence = index.toLong(),
                    attributes = mapOf("endpoint_alias" to DiagnosticValue.Text("endpoint-second")),
                ),
            )
        }
        assertEquals(DiagnosticFlushStatus.Completed, first.flush())
        assertEquals(DiagnosticFlushStatus.Completed, second.flush())
        first.close()

        second.record(
            event(
                sequence = 10,
                attributes = mapOf("endpoint_alias" to DiagnosticValue.Text("endpoint-second")),
            ),
        )
        assertEquals(DiagnosticFlushStatus.Completed, second.flush())
        second.close()

        val firstLines = Files.readAllLines(firstDirectory.resolve("gwatch-diagnostics.jsonl"))
        val secondLines = Files.readAllLines(secondDirectory.resolve("gwatch-diagnostics.jsonl"))
        assertEquals(10, firstLines.size)
        assertEquals(11, secondLines.size)
        assertTrue(firstLines.all { it.contains("endpoint-first") && !it.contains("endpoint-second") })
        assertTrue(secondLines.all { it.contains("endpoint-second") && !it.contains("endpoint-first") })
        assertTrue(first.lossState().writeFailures == 0L)
        assertTrue(second.lossState().writeFailures == 0L)
    }

    @Test
    fun queueOverflowIsBestEffortAndLossIsAccounted() {
        val directory = Files.createTempDirectory("gwt-diagnostics-overflow")
        val diagnostics =
            LogbackDiagnosticEvents(
                DiagnosticLogConfiguration(directory = directory, queueCapacity = 1),
            )
        val submitted = 2_000
        repeat(submitted) { diagnostics.record(event(sequence = it.toLong())) }
        diagnostics.flush(2_000)
        val loss = diagnostics.lossState()
        diagnostics.close()
        val written = Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl")).size
        assertEquals(submitted.toLong(), written + loss.droppedTotal)
    }

    @Test
    fun retentionBoundsActiveAndRotatedFilesTogether() {
        val directory = Files.createTempDirectory("gwt-diagnostics-retention")
        val maxBytes = 4_000L
        val diagnostics =
            LogbackDiagnosticEvents(
                DiagnosticLogConfiguration(directory = directory, maxFileBytes = maxBytes),
            )
        repeat(100) { sequence ->
            diagnostics.record(event(sequence = sequence.toLong()))
            diagnostics.flush()
        }
        diagnostics.close()

        val retainedBytes =
            Files.list(directory).use { paths ->
                paths
                    .filter { it.fileName.toString().startsWith("gwatch-diagnostics") }
                    .toList()
                    .sumOf { Files.size(it) }
            }
        assertTrue(retainedBytes <= maxBytes)
    }

    @Test
    fun repeatedSizeRotationDetachesRetiredAppendersAndPreservesBoundedIsolatedJsonl() {
        val firstDirectory = Files.createTempDirectory("gwt-diagnostics-rotation-first")
        val secondDirectory = Files.createTempDirectory("gwt-diagnostics-rotation-second")
        val configuration =
            DiagnosticLogConfiguration(
                directory = firstDirectory,
                maxFileBytes = 8_000,
                maxEventBytes = 7_000,
            )
        val first = LogbackDiagnosticEvents(configuration)
        val second = LogbackDiagnosticEvents(configuration.copy(directory = secondDirectory))

        val seededSecret = "synthetic-rotation-secret-7f3a"
        val syntheticPrivatePath = "C:\\synthetic-user\\private\\sample-video.mkv"
        repeat(25) { sequence ->
            val attributes =
                buildMap {
                    put("endpoint_alias", DiagnosticValue.Text("endpoint-first"))
                    if (sequence == 24) {
                        put("token", DiagnosticValue.Text(seededSecret))
                        put("media_path", DiagnosticValue.Text(syntheticPrivatePath))
                    }
                }
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<Unit>>(
                first.record(event(sequence = sequence.toLong(), attributes = attributes)),
            )
            assertEquals(DiagnosticFlushStatus.Completed, first.flush())
            assertEquals(1, attachedAppenderCount(first))
            assertEquals(1, attachedAppenderCount(second))
        }
        second.record(
            event(
                attributes = mapOf("endpoint_alias" to DiagnosticValue.Text("endpoint-second")),
            ),
        )
        assertEquals(DiagnosticFlushStatus.Completed, second.flush())
        assertEquals(1, attachedAppenderCount(second))

        first.close()
        assertEquals(0, attachedAppenderCount(first))
        assertEquals(1, attachedAppenderCount(second))
        second.close()
        assertEquals(0, attachedAppenderCount(second))

        val retainedFiles =
            Files.list(firstDirectory).use { paths ->
                paths
                    .filter { it.fileName.toString().startsWith("gwatch-diagnostics") }
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .toList()
            }
        val retainedBytes = retainedFiles.sumOf { Files.size(it) }
        val rotatedFiles = retainedFiles.filter { it.fileName.toString() != "gwatch-diagnostics.jsonl" }
        assertTrue(rotatedFiles.size >= 2, "expected repeated size rotations")
        assertTrue(retainedBytes <= configuration.maxFileBytes)
        val retainedLines = retainedFiles.flatMap(Files::readAllLines)
        assertTrue(retainedLines.isNotEmpty())
        assertTrue(retainedLines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(retainedLines.all { it.contains("endpoint-first") && !it.contains("endpoint-second") })
        val retainedJsonl = retainedLines.joinToString("\n")
        val jsonEscapedSyntheticPrivatePath = syntheticPrivatePath.replace("\\", "\\\\")
        assertTrue(jsonEscapedSyntheticPrivatePath.contains("\\\\"))
        assertFalse(retainedJsonl.contains(seededSecret))
        assertFalse(retainedJsonl.contains(syntheticPrivatePath))
        assertFalse(retainedJsonl.contains(jsonEscapedSyntheticPrivatePath))

        val retainedEvents =
            retainedFiles.flatMap { path ->
                assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(
                    DiagnosticJsonlReader().read(path),
                ).value
            }
        assertTrue(retainedEvents.isNotEmpty())
        assertTrue(retainedEvents.all { it.sequence in 0L..24L })
        val retainedSequence24 = retainedEvents.single { it.sequence == 24L }
        assertEquals(setOf("endpoint_alias"), retainedSequence24.attributes.keys)
        assertTrue(
            Files
                .readAllLines(
                    secondDirectory.resolve("gwatch-diagnostics.jsonl"),
                ).single()
                .contains("endpoint-second"),
        )
        assertEquals(0L, first.lossState().writeFailures)
        assertEquals(0L, second.lossState().writeFailures)
    }

    @Test
    fun freeFormSensitiveValuesAreRejectedBeforeDisk() {
        val directory = Files.createTempDirectory("gwt-diagnostics-sensitive")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        val path = "C:\\Users\\private\\movie.mkv"
        val rejectedEvents =
            listOf(
                event().copy(eventTimestampUtc = path),
                event().copy(component = path),
                event().copy(appVersion = path),
                event().copy(buildRevision = path),
                event().copy(processInstanceId = path),
                event().copy(observedTimestampUtc = path),
                event().copy(operationId = path),
                event().copy(outcome = path),
                event().copy(errorCode = path),
                event().copy(diagnosticId = path),
                event(attributes = mapOf("endpoint_alias" to DiagnosticValue.Text(path))),
            )
        rejectedEvents.forEach { candidate ->
            assertTrue(diagnostics.record(candidate) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure)
        }
        diagnostics.close()
        assertEquals(0, Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl")).size)
    }

    @Test
    fun oversizedTextAndAttributeSetsAreRejectedAsWholeEvents() {
        val directory = Files.createTempDirectory("gwt-diagnostics-rejection")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        assertTrue(
            diagnostics.record(
                event(attributes = mapOf("endpoint_alias" to DiagnosticValue.Text("endpoint-" + "x".repeat(512)))),
            ) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
        assertTrue(
            diagnostics.record(
                event(
                    attributes =
                        (0..24).associate { index ->
                            "unknown-$index" to DiagnosticValue.Text("value")
                        },
                ),
            ) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
        diagnostics.close()
        assertEquals(0, Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl")).size)
    }

    @Test
    fun activeSegmentExpiresUsingInjectedClockDuringLowVolumeFlush() {
        val directory = Files.createTempDirectory("gwt-diagnostics-active-age")
        val clock = MutableTestClock(1_000)
        val diagnostics =
            LogbackDiagnosticEvents(
                DiagnosticLogConfiguration(directory = directory, retentionMillis = 1_000, clock = clock),
            )
        diagnostics.record(event())
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        clock.epoch = 2_001
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()

        assertTrue(
            Files.list(directory).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith("gwatch-diagnostics-") }
            },
        )
    }

    @Test
    fun activeSegmentExpiresOnRestartUsingCreationAge() {
        val directory = Files.createTempDirectory("gwt-diagnostics-restart-age")
        val clock = MutableTestClock(System.currentTimeMillis())
        val configuration = DiagnosticLogConfiguration(directory = directory, retentionMillis = 1, clock = clock)
        LogbackDiagnosticEvents(configuration).also {
            it.record(event())
            assertEquals(DiagnosticFlushStatus.Completed, it.flush())
            it.close()
        }
        clock.epoch += 100
        LogbackDiagnosticEvents(configuration).also {
            assertEquals(0L, Files.size(directory.resolve("gwatch-diagnostics.jsonl")))
            it.close()
        }
    }

    @Test
    fun invalidTimestampsAreRejectedBeforeDisk() {
        val directory = Files.createTempDirectory("gwt-diagnostics-timestamp")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        val rejected = diagnostics.record(event().copy(eventTimestampUtc = "not-a-timestamp"))
        val rejectedObserved = diagnostics.record(event().copy(observedTimestampUtc = "not-a-timestamp"))
        assertTrue(rejected is com.saytikus.gwatchtogether.domain.result.MbResult.Failure)
        assertTrue(rejectedObserved is com.saytikus.gwatchtogether.domain.result.MbResult.Failure)
        diagnostics.close()
        assertEquals(0, Files.readAllLines(directory.resolve("gwatch-diagnostics.jsonl")).size)
    }

    @Test
    fun failedSinkDoesNotThrowIntoApplicationAndReportsUnavailable() {
        val parent = Files.createTempDirectory("gwt-diagnostics-failure")
        val blockedPath = parent.resolve("not-a-directory")
        Files.writeString(blockedPath, "occupied")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(blockedPath))

        val result = diagnostics.record(event())
        assertTrue(result is com.saytikus.gwatchtogether.domain.result.MbResult.Failure)
        assertTrue(diagnostics.lossState().isUnavailable)
        assertEquals(DiagnosticFlushStatus.Unavailable, diagnostics.flush())
        diagnostics.close()
    }

    @Test
    fun readerNormalizesStockMembersAndRejectsWrongTypesAndDuplicateKeys() {
        val directory = Files.createTempDirectory("gwt-diagnostics-reader")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        diagnostics.record(event(sequence = 7))
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()

        val normalized = DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl"))
        when (normalized) {
            is com.saytikus.gwatchtogether.domain.result.MbResult.Success -> {
                assertEquals(7, normalized.value.single().sequence)
            }

            is com.saytikus.gwatchtogether.domain.result.MbResult.Failure -> {
                error("normalization failed")
            }
        }

        val malformed = directory.resolve("malformed.jsonl")
        Files.writeString(
            malformed,
            "{\"timestamp\":1,\"level\":\"INFO\",\"kvpList\":[{\"gwt.sequence\":\"wrong\"},{\"gwt.sequence\":\"2\"}]}\n",
        )
        assertTrue(
            DiagnosticJsonlReader().read(malformed) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
    }

    @Test
    fun readerPreservesCatalogTypesAndDropsUnknownAttributes() {
        val directory = Files.createTempDirectory("gwt-diagnostics-reader-types")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        diagnostics.record(
            event(
                attributes =
                    mapOf(
                        "bytes" to DiagnosticValue.Integer(42),
                        "cpu_percent" to DiagnosticValue.Decimal(12.5),
                        "unknown" to DiagnosticValue.Text("must not normalize"),
                    ),
            ),
        )
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()

        val result = DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl"))
        val normalized =
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(result)
                .value
                .single()
        assertEquals(DiagnosticValue.Integer(42), normalized.attributes["bytes"])
        assertEquals(DiagnosticValue.Decimal(12.5), normalized.attributes["cpu_percent"])
        assertFalse("unknown" in normalized.attributes)
    }

    @Test
    fun allDiagnosticRolesRoundTripThroughTheStockWireMapping() {
        val directory = Files.createTempDirectory("gwt-diagnostics-roles")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        DiagnosticRole.entries.forEachIndexed { index, role ->
            diagnostics.record(event(sequence = index.toLong(), role = role))
        }
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()

        val result = DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl"))
        val normalized =
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(result)
                .value
        assertEquals(DiagnosticRole.entries.toList(), normalized.map { it.role })
    }

    @Test
    fun allDiagnosticCatalogEventsRoundTripThroughTheStockWireMapping() {
        val directory = Files.createTempDirectory("gwt-diagnostics-catalog")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        val catalog =
            listOf(
                "app.bootstrap.started" to "app",
                "app.bootstrap.completed" to "app",
                "connection.attempted" to "connection",
                "connection.completed" to "connection",
                "auth.completed" to "auth",
                "watch.preflight.completed" to "watch",
                "watch.barrier.completed" to "watch",
                "watch.resync.completed" to "watch",
                "device.permission.changed" to "device",
                "storage.operation.completed" to "storage",
                "tray.lifecycle.changed" to "tray",
                "window.lifecycle.changed" to "window",
                "native.lifecycle.changed" to "native",
                "sidecar.lifecycle.changed" to "sidecar",
                "resource.summary" to "resource",
                "diagnostic.export.completed" to "diagnostic",
            )
        catalog.forEachIndexed { index, (eventName, component) ->
            diagnostics.record(event(sequence = index.toLong(), eventName = eventName, component = component))
        }
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()

        val normalized =
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(
                DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl")),
            ).value
        assertEquals(catalog.map { it.first }, normalized.map { it.eventName })
        assertEquals(catalog.map { it.second }, normalized.map { it.component })
    }

    @Test
    fun readerRejectsUnknownCatalogAndUnsafePhysicalTextAcrossAllTextSeams() {
        val directory = Files.createTempDirectory("gwt-diagnostics-reader-catalog")
        val base =
            linkedMapOf(
                "gwt.schema_version" to "1",
                "gwt.event_timestamp_utc" to "2026-09-09T12:00:00Z",
                "gwt.event_name" to "connection.completed",
                "gwt.role" to "client",
                "gwt.component" to "connection",
                "gwt.app_version" to "POC-0-dev.test",
                "gwt.build_revision" to "local",
                "gwt.process_instance_id" to "process-0123456789abcdef",
                "gwt.sequence" to "1",
            )
        val textSeams =
            listOf(
                "gwt.component" to "C:\\Users\\private\\movie.mkv",
                "gwt.app_version" to "C:\\Users\\private\\movie.mkv",
                "gwt.build_revision" to "C:\\Users\\private\\movie.mkv",
                "gwt.process_instance_id" to "C:\\Users\\private\\movie.mkv",
                "gwt.operation_id" to "C:\\Users\\private\\movie.mkv",
                "gwt.outcome" to "C:\\Users\\private\\movie.mkv",
                "gwt.error_code" to "C:\\Users\\private\\movie.mkv",
                "gwt.diagnostic_id" to "C:\\Users\\private\\movie.mkv",
                "gwt.attr.endpoint_alias" to "C:\\Users\\private\\movie.mkv",
                "gwt.attr.target" to "C:\\Users\\private\\movie.mkv",
            )
        textSeams.forEachIndexed { index, (key, unsafeValue) ->
            val values = base.toMutableMap().apply { this[key] = unsafeValue }
            val path = directory.resolve("unsafe-$index.jsonl")
            Files.writeString(path, stockRecord(values))
            assertTrue(
                DiagnosticJsonlReader().read(path) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
                "reader accepted unsafe value for $key",
            )
        }

        val invalidCatalogs =
            listOf(
                "gwt.event_name" to "watch.unknown",
                "gwt.component" to "unknown",
                "gwt.outcome" to "partial",
                "gwt.error_code" to "private_path",
                "gwt.attr.endpoint_alias" to "endpoint-C:\\Users\\private",
                "gwt.attr.permission_state" to "maybe",
            )
        invalidCatalogs.forEachIndexed { index, (key, invalidValue) ->
            val values = base.toMutableMap().apply { this[key] = invalidValue }
            val path = directory.resolve("invalid-$index.jsonl")
            Files.writeString(path, stockRecord(values))
            assertTrue(
                DiagnosticJsonlReader().read(path) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
                "reader accepted invalid catalog value for $key",
            )
        }

        val escaped =
            base.toMutableMap().apply {
                this["gwt.attr.endpoint_alias"] = "endpoint-quoted\\\"value"
            }
        val escapedPath = directory.resolve("escaped.jsonl")
        Files.writeString(escapedPath, stockRecord(escaped))
        assertTrue(
            DiagnosticJsonlReader().read(escapedPath) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
    }

    @Test
    fun driftBoundsPreserveSignAndRejectOverflowWithoutClamping() {
        val directory = Files.createTempDirectory("gwt-diagnostics-drift")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        diagnostics.record(
            event(
                attributes =
                    mapOf(
                        "drift_ms" to DiagnosticValue.Integer(-MAX_DIAGNOSTIC_DRIFT_MS),
                    ),
            ),
        )
        diagnostics.record(
            event(
                sequence = 2,
                attributes =
                    mapOf(
                        "drift_ms" to DiagnosticValue.Integer(MAX_DIAGNOSTIC_DRIFT_MS),
                    ),
            ),
        )
        assertTrue(
            diagnostics.record(
                event(
                    sequence = 3,
                    attributes =
                        mapOf(
                            "drift_ms" to DiagnosticValue.Integer(MAX_DIAGNOSTIC_DRIFT_MS + 1),
                        ),
                ),
            ) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
        assertTrue(
            diagnostics.record(
                event(
                    sequence = 4,
                    attributes = mapOf("drift_ms" to DiagnosticValue.Integer(Long.MIN_VALUE)),
                ),
            ) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()

        val result = DiagnosticJsonlReader().read(directory.resolve("gwatch-diagnostics.jsonl"))
        val normalized =
            assertIs<com.saytikus.gwatchtogether.domain.result.MbResult.Success<List<DiagnosticEvent>>>(result)
                .value
        assertEquals(DiagnosticValue.Integer(-MAX_DIAGNOSTIC_DRIFT_MS), normalized[0].attributes["drift_ms"])
        assertEquals(DiagnosticValue.Integer(MAX_DIAGNOSTIC_DRIFT_MS), normalized[1].attributes["drift_ms"])
        assertEquals(2, normalized.size)
    }

    @Test
    fun readerRejectsInjectionAndOversizedUnterminatedInput() {
        val directory = Files.createTempDirectory("gwt-diagnostics-reader-hostile")
        val injection = directory.resolve("injection.jsonl")
        Files.writeString(
            injection,
            """{"timestamp":1,"level":"INFO","loggerName":"gwt","kvpList":[{"gwt.schema_version":"1"},{"gwt.event_timestamp_utc":"2026-09-09T12:00:00Z\\r\\nforged"},{"gwt.event_name":"connection.completed"},{"gwt.role":"client"},{"gwt.component":"test"},{"gwt.app_version":"POC-0-dev.test"},{"gwt.build_revision":"local"},{"gwt.process_instance_id":"process-test"},{"gwt.sequence":"1"}]}""",
        )
        assertTrue(
            DiagnosticJsonlReader().read(injection) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )

        val oversized = directory.resolve("oversized.jsonl")
        Files.writeString(oversized, "x".repeat(MAX_DIAGNOSTIC_EVENT_BYTES + 1))
        assertTrue(
            DiagnosticJsonlReader().read(oversized) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
    }

    @Test
    fun readerRejectsUnsupportedSchemaAndDeepJsonWithoutStackOverflow() {
        val directory = Files.createTempDirectory("gwt-diagnostics-reader-bounds")
        val unsupportedSchema = directory.resolve("schema.jsonl")
        Files.writeString(
            unsupportedSchema,
            "{\"timestamp\":1,\"level\":\"INFO\",\"loggerName\":\"gwt\",\"kvpList\":[{" +
                "\"gwt.schema_version\":\"2\"}]}\n",
        )
        assertTrue(
            DiagnosticJsonlReader().read(
                unsupportedSchema,
            ) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )

        val deeplyNested = directory.resolve("deep.jsonl")
        Files.writeString(deeplyNested, "[".repeat(1_000) + "0" + "]".repeat(1_000) + "\n")
        assertTrue(
            DiagnosticJsonlReader().read(deeplyNested) is com.saytikus.gwatchtogether.domain.result.MbResult.Failure,
        )
    }

    @Test
    fun eventAndSummaryBoundsAreAppliedBeforeTheStockEncoder() {
        val directory = Files.createTempDirectory("gwt-diagnostics-bound")
        val diagnostics = LogbackDiagnosticEvents(DiagnosticLogConfiguration(directory))
        val safeAttributes =
            listOf(
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
            )
        diagnostics.record(
            event(
                attributes =
                    safeAttributes.associateWith { name ->
                        when (name) {
                            "retry_attempt",
                            "epoch",
                            "bytes",
                            "memory_bytes",
                            "queue_depth",
                            "dropped_count",
                            "summary_event_count",
                            "summary_bytes",
                            "redaction_count",
                            -> DiagnosticValue.Integer(42)

                            "cpu_percent" -> DiagnosticValue.Decimal(42.0)

                            "drift_ms" -> DiagnosticValue.Integer(42)

                            "endpoint_alias" -> DiagnosticValue.Text("endpoint-test")

                            "operation_id" -> DiagnosticValue.Text("operation-0123456789abcdef")

                            "session_alias" -> DiagnosticValue.Text("session-test")

                            "target" -> DiagnosticValue.Text("desktop")

                            "transport" -> DiagnosticValue.Text("https")

                            "auth_method" -> DiagnosticValue.Text("session")

                            "engine_capability" -> DiagnosticValue.Text("h264")

                            "source_kind" -> DiagnosticValue.Text("local_file")

                            "device_kind" -> DiagnosticValue.Text("camera")

                            "permission_state" -> DiagnosticValue.Text("granted")

                            "operation_kind" -> DiagnosticValue.Text("read")

                            "lifecycle_state" -> DiagnosticValue.Text("active")

                            "reason_code", "failure_code", "error_code" -> DiagnosticValue.Text("failed")

                            else -> DiagnosticValue.Text("attribute")
                        }
                    },
            ),
        )
        assertEquals(DiagnosticFlushStatus.Completed, diagnostics.flush())
        diagnostics.close()
        val line = Files.readString(directory.resolve("gwatch-diagnostics.jsonl"))
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
    }

    private fun attachedAppenderCount(diagnostics: LogbackDiagnosticEvents): Int {
        val loggerField = LogbackDiagnosticEvents::class.java.getDeclaredField("logger").apply { isAccessible = true }
        val logger = loggerField.get(diagnostics) as ch.qos.logback.classic.Logger
        val appenders = logger.iteratorForAppenders()
        var count = 0
        while (appenders.hasNext()) {
            assertTrue(appenders.next().isStarted, "stopped appender remained attached")
            count++
        }
        return count
    }

    private fun stockRecord(values: Map<String, String>): String {
        val kvpList =
            values.entries.joinToString(",") { (key, value) ->
                "{" + jsonQuote(key) + ":" + jsonQuote(value) + "}"
            }
        return "{\"timestamp\":1,\"level\":\"INFO\",\"loggerName\":\"gwt.diagnostics\",\"kvpList\":[" +
            kvpList +
            "]}\n"
    }

    private fun jsonQuote(value: String): String =
        "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") +
            "\""

    private class MutableTestClock(
        var epoch: Long,
        var monotonic: Long = epoch,
    ) : IClock {
        override fun epochMillis(): Long = epoch

        override fun monotonicNanos(): Long = monotonic
    }

    private fun event(
        sequence: Long = 1,
        severity: DiagnosticSeverity = DiagnosticSeverity.Info,
        role: DiagnosticRole = DiagnosticRole.Client,
        eventName: String = "connection.completed",
        component: String = "connection",
        attributes: Map<String, DiagnosticValue> = emptyMap(),
    ) = DiagnosticEvent(
        eventTimestampUtc = "2026-09-09T12:00:00Z",
        severity = severity,
        eventName = eventName,
        component = component,
        role = role,
        appVersion = "POC-0-dev.test",
        buildRevision = "local",
        processInstanceId = "process-0123456789abcdef",
        sequence = sequence,
        outcome = "succeeded",
        attributes = attributes,
    )
}
