package com.saytikus.gwatchtogether.protocol

import com.saytikus.gwatchtogether.protocol.wire.ClientHello
import com.saytikus.gwatchtogether.protocol.wire.ControlEnvelope
import com.saytikus.gwatchtogether.protocol.wire.ControlError
import com.saytikus.gwatchtogether.protocol.wire.ServerHello
import com.saytikus.gwatchtogether.protocol.wire.WatchReady
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlEnvelopeWireSchemaTest {
    @Test
    fun descriptorPinsEnvelopeFieldNumbersAndPayloadOneof() {
        val descriptor = ControlEnvelope.getDescriptor()
        assertEquals(
            mapOf(
                "request_id" to 1,
                "client_hello" to 2,
                "server_hello" to 3,
                "watch_ready" to 4,
                "error" to 5,
            ),
            descriptor.fields.associate { it.name to it.number },
        )
        assertEquals(
            listOf("client_hello", "server_hello", "watch_ready", "error"),
            descriptor.oneofs
                .single { it.name == "payload" }
                .fields
                .map { it.name },
        )
        assertTrue(descriptor.findFieldByName("request_id").hasPresence())
    }

    @Test
    fun absentAndExplicitlyEmptyRequestIdRemainDistinctAcrossRoundTrip() {
        val absent = ControlEnvelope.newBuilder().build()
        val empty = ControlEnvelope.newBuilder().setRequestId("").build()

        val parsedAbsent = ControlEnvelope.parseFrom(absent.toByteArray())
        val parsedEmpty = ControlEnvelope.parseFrom(empty.toByteArray())
        assertFalse(parsedAbsent.hasRequestId())
        assertTrue(parsedEmpty.hasRequestId())
        assertEquals("", parsedAbsent.requestId)
        assertEquals("", parsedEmpty.requestId)
        assertFalse(ControlEnvelope.parseFrom(parsedAbsent.toByteArray()).hasRequestId())
        assertTrue(ControlEnvelope.parseFrom(parsedEmpty.toByteArray()).hasRequestId())
        assertFalse(parsedAbsent == parsedEmpty)
    }

    @Test
    fun eachKnownPayloadSelectsAndRoundTripsItsOneofCase() {
        val fixtures =
            listOf(
                ControlEnvelope.newBuilder().setClientHello(ClientHello.getDefaultInstance()).build() to
                    ControlEnvelope.PayloadCase.CLIENT_HELLO,
                ControlEnvelope.newBuilder().setServerHello(ServerHello.getDefaultInstance()).build() to
                    ControlEnvelope.PayloadCase.SERVER_HELLO,
                ControlEnvelope.newBuilder().setWatchReady(WatchReady.getDefaultInstance()).build() to
                    ControlEnvelope.PayloadCase.WATCH_READY,
                ControlEnvelope.newBuilder().setError(ControlError.getDefaultInstance()).build() to
                    ControlEnvelope.PayloadCase.ERROR,
            )

        fixtures.forEach { (envelope, selectedCase) ->
            assertEquals(selectedCase, envelope.payloadCase)
            val parsed = ControlEnvelope.parseFrom(envelope.toByteArray())
            assertEquals(selectedCase, parsed.payloadCase)
            assertEquals(envelope, parsed)
        }
    }

    @Test
    fun omittedPayloadRemainsUnsetAcrossRoundTrip() {
        val envelope = ControlEnvelope.newBuilder().setRequestId("fixture-request").build()
        val parsed = ControlEnvelope.parseFrom(envelope.toByteArray())
        assertEquals(ControlEnvelope.PayloadCase.PAYLOAD_NOT_SET, parsed.payloadCase)
        assertTrue(parsed.hasRequestId())
        assertEquals(envelope, parsed)
    }

    @Test
    fun unknownAdditiveFieldIsRetainedAcrossRoundTrip() {
        // Field 99, varint 1; this is an additive field outside the current envelope descriptor.
        val unknownField = byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val envelope = ControlEnvelope.newBuilder().setClientHello(ClientHello.getDefaultInstance()).build()
        val parsed = ControlEnvelope.parseFrom(envelope.toByteArray() + unknownField)

        assertEquals(ControlEnvelope.PayloadCase.CLIENT_HELLO, parsed.payloadCase)
        assertEquals(listOf(1L), parsed.unknownFields.getField(99).varintList)
        val reparsed = ControlEnvelope.parseFrom(parsed.toByteArray())
        assertEquals(parsed, reparsed)
        assertContentEquals(parsed.toByteArray(), reparsed.toByteArray())
        assertEquals(listOf(1L), reparsed.unknownFields.getField(99).varintList)
    }
}
