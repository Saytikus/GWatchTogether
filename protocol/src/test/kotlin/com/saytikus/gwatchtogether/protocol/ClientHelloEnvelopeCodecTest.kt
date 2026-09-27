package com.saytikus.gwatchtogether.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import com.saytikus.gwatchtogether.protocol.wire.ClientHello
import com.saytikus.gwatchtogether.protocol.wire.ControlEnvelope
import com.saytikus.gwatchtogether.protocol.wire.ControlError
import com.saytikus.gwatchtogether.protocol.wire.ServerHello
import com.saytikus.gwatchtogether.protocol.wire.WatchReady
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ClientHelloEnvelopeCodecTest {
    private val hello = ClientHelloModel(1, 0, "POC-0-dev.git-1234567", setOf("watch", "voice"))
    private val wireHello: ClientHello
        get() =
            ClientHello.parseFrom(
                assertIs<MbResult.Success<ByteArray>>(ClientHelloCodec.encode(hello)).value,
            )

    @Test
    fun validClientHelloEnvelopeMapsToDomainModel() {
        val envelope = ControlEnvelope.newBuilder().setClientHello(wireHello).build()
        assertHello(envelope.toByteArray())
    }

    @Test
    fun requestIdPresenceAndValueDoNotChangeHandshake() {
        val absent = ControlEnvelope.newBuilder().setClientHello(wireHello)
        listOf(
            absent.build(),
            absent.setRequestId("").build(),
            absent.setRequestId("fixture-request").build(),
        ).forEach { assertHello(it.toByteArray()) }
    }

    @Test
    fun unsetAndOtherKnownPayloadsFailClosed() {
        val envelopes =
            listOf(
                ControlEnvelope.getDefaultInstance(),
                ControlEnvelope.newBuilder().setServerHello(ServerHello.getDefaultInstance()).build(),
                ControlEnvelope.newBuilder().setWatchReady(WatchReady.getDefaultInstance()).build(),
                ControlEnvelope.newBuilder().setError(ControlError.getDefaultInstance()).build(),
            )
        envelopes.forEach { assertFailure("unexpected_control_payload", it.toByteArray()) }
    }

    @Test
    fun unknownOnlyPayloadFailsClosed() {
        // Field 99, varint 1, is not a known payload case.
        assertFailure("unexpected_control_payload", byteArrayOf(0x98.toByte(), 0x06, 0x01))
    }

    @Test
    fun validEnvelopeAtExactMaximumSizeIsAccepted() {
        val envelope = ControlEnvelope.newBuilder().setClientHello(wireHello).build()
        // Field 99's length-delimited tag and the two-byte varint length add four bytes at this size.
        val paddingSize = ClientHelloCodec.MAX_MESSAGE_BYTES - envelope.serializedSize - 4
        val unknownField =
            UnknownFieldSet
                .newBuilder()
                .addField(
                    99,
                    UnknownFieldSet.Field
                        .newBuilder()
                        .addLengthDelimited(ByteString.copyFrom(ByteArray(paddingSize)))
                        .build(),
                ).build()
        val bytes =
            envelope
                .toBuilder()
                .mergeUnknownFields(unknownField)
                .build()
                .toByteArray()

        assertEquals(ClientHelloCodec.MAX_MESSAGE_BYTES, bytes.size)
        assertHello(bytes)
    }

    @Test
    fun malformedAndOversizedEnvelopeFailClosed() {
        assertFailure("malformed_control_envelope", byteArrayOf(0x12, 0x05, 0x01))
        assertFailure("message_too_large", ByteArray(ClientHelloCodec.MAX_MESSAGE_BYTES + 1))
    }

    @Test
    fun nestedVersionAndPresenceValidationIsDelegatedToClientHelloCodec() {
        val incompatible = wireHello.toBuilder().setProtocolMajor(2).build()
        val missingVersion = wireHello.toBuilder().clearAppVersion().build()
        assertFailure("incompatible_protocol", envelopeWith(incompatible))
        assertFailure("missing_client_hello_field", envelopeWith(missingVersion))
    }

    @Test
    fun additiveUnknownOuterFieldDoesNotChangeClientHello() {
        val unknownField = byteArrayOf(0x98.toByte(), 0x06, 0x01)
        assertHello(envelopeWith(wireHello) + unknownField)
    }

    private fun envelopeWith(wire: ClientHello): ByteArray =
        ControlEnvelope
            .newBuilder()
            .setClientHello(wire)
            .build()
            .toByteArray()

    private fun assertHello(bytes: ByteArray) {
        assertEquals(hello, assertIs<MbResult.Success<ClientHelloModel>>(ClientHelloEnvelopeCodec.decode(bytes)).value)
    }

    private fun assertFailure(
        code: String,
        bytes: ByteArray,
    ) {
        val error = assertIs<MbResult.Failure>(ClientHelloEnvelopeCodec.decode(bytes)).error
        assertEquals(DomainError.Protocol(code), error.error)
        assertEquals(Retryability.Never, error.retryability)
        assertNull(error.diagnosticId)
    }
}
