package com.saytikus.gwatchtogether.protocol

import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.protocol.wire.ClientHello
import com.saytikus.gwatchtogether.protocol.wire.ControlError
import com.saytikus.gwatchtogether.protocol.wire.ControlErrorCode
import com.saytikus.gwatchtogether.protocol.wire.WatchOperationRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClientHelloCodecTest {
    private val hello = ClientHelloModel(1, 0, "POC-0-dev.git-1234567", setOf("watch", "voice"))

    @Test
    fun generatedRoundTripMapsToDomain() {
        val encoded = assertIs<MbResult.Success<ByteArray>>(ClientHelloCodec.encode(hello)).value
        assertEquals(hello, assertIs<MbResult.Success<ClientHelloModel>>(ClientHelloCodec.decode(encoded)).value)
    }

    @Test
    fun unknownAdditiveFieldDoesNotBreakHandshake() {
        // Field 99, wire type varint, value 1; future fields must remain additive.
        val unknownField = byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val encoded = assertIs<MbResult.Success<ByteArray>>(ClientHelloCodec.encode(hello)).value
        assertEquals(
            hello,
            assertIs<MbResult.Success<ClientHelloModel>>(ClientHelloCodec.decode(encoded + unknownField)).value,
        )
    }

    @Test
    fun omittedAndInvalidFieldsFailClosed() {
        assertFailure(
            "missing_client_hello_field",
            ClientHelloCodec.decode(ClientHello.getDefaultInstance().toByteArray()),
        )
        val noVersion =
            ClientHello
                .newBuilder()
                .setProtocolMajor(1)
                .setProtocolMinor(0)
                .build()
        assertFailure("missing_client_hello_field", ClientHelloCodec.decode(noVersion.toByteArray()))
        assertFailure("incompatible_protocol", ClientHelloCodec.encode(hello.copy(protocolMajor = 2)))
        assertFailure("incompatible_protocol", ClientHelloCodec.encode(hello.copy(protocolMinor = 1)))
        assertFailure("invalid_app_version", ClientHelloCodec.encode(hello.copy(appVersion = "unsafe\nvalue")))
    }

    @Test
    fun oversizedMalformedAndDuplicateCapabilitiesFailClosed() {
        assertFailure("message_too_large", ClientHelloCodec.decode(ByteArray(ClientHelloCodec.MAX_MESSAGE_BYTES + 1)))
        assertFailure("malformed_client_hello", ClientHelloCodec.decode(byteArrayOf(0x0a, 0x05, 0x01)))
        val duplicates =
            ClientHello
                .newBuilder()
                .setProtocolMajor(1)
                .setProtocolMinor(0)
                .setAppVersion(hello.appVersion)
                .addCapabilities("watch")
                .addCapabilities("watch")
                .build()
        assertFailure("invalid_capabilities", ClientHelloCodec.decode(duplicates.toByteArray()))
        assertFailure(
            "invalid_capabilities",
            ClientHelloCodec.encode(hello.copy(capabilities = setOf("invalid capability"))),
        )
    }

    @Test
    fun encodeRejectsHugeCallerStringsBeforeUtf8Sizing() {
        val hugeString = CharArray(100_000) { 'x' }.concatToString()
        assertFailure("invalid_app_version", ClientHelloCodec.encode(hello.copy(appVersion = hugeString)))
        assertFailure("invalid_capabilities", ClientHelloCodec.encode(hello.copy(capabilities = setOf(hugeString))))
    }

    @Test
    fun unknownErrorEnumIsPreservedButMapsToTypedFailure() {
        val encoded =
            ControlError
                .newBuilder()
                .setCodeValue(12345)
                .build()
                .toByteArray()
        val parsed = ControlError.parseFrom(encoded)
        assertEquals(12345, parsed.codeValue)
        assertEquals(ControlErrorCode.UNRECOGNIZED, parsed.code)
        assertFailure("unknown_control_error", ControlMessageMappers.controlError(parsed))
    }

    @Test
    fun controlErrorAndWatchReferenceMapToDomainModels() {
        val wireError =
            ControlError
                .newBuilder()
                .setCode(ControlErrorCode.STALE_STATE)
                .setCurrentRevision(17)
                .build()
        val error = assertIs<MbResult.Success<ControlErrorModel>>(ControlMessageMappers.controlError(wireError)).value
        assertEquals(DomainError.Protocol("stale_state"), error.error.error)
        assertEquals(17, error.currentRevision)
        val authorizationError =
            ControlError.newBuilder().setCode(ControlErrorCode.PERMISSION_DENIED).build()
        assertEquals(
            DomainError.Authorization("permission_denied"),
            assertIs<MbResult.Success<ControlErrorModel>>(
                ControlMessageMappers.controlError(authorizationError),
            ).value.error.error,
        )

        val wireReference =
            WatchOperationRef
                .newBuilder()
                .setServerInstanceId("server-instance")
                .setWatchSessionId("watch-session")
                .setSourceEpoch(3)
                .setWatchRevision(17)
                .setOperationId("operation")
                .setExpectedRevision(16)
                .build()
        val reference =
            assertIs<MbResult.Success<WatchOperationReference>>(
                ControlMessageMappers.watchOperationReference(wireReference),
            ).value
        assertEquals(3, reference.sourceEpoch)
        assertEquals(17, reference.watchRevision)
        assertEquals(16, reference.expectedRevision)
    }

    @Test
    fun watchReferenceRequiresEachRevisionFieldButAllowsExplicitZero() {
        val completeReference =
            WatchOperationRef
                .newBuilder()
                .setServerInstanceId("server-instance")
                .setWatchSessionId("watch-session")
                .setOperationId("operation")
                .setSourceEpoch(0)
                .setWatchRevision(0)
                .setExpectedRevision(0)
                .build()
        val mapped =
            assertIs<MbResult.Success<WatchOperationReference>>(
                ControlMessageMappers.watchOperationReference(completeReference),
            ).value
        assertEquals(0, mapped.sourceEpoch)
        assertEquals(0, mapped.watchRevision)
        assertEquals(0, mapped.expectedRevision)

        assertFailure(
            "missing_source_epoch",
            ControlMessageMappers.watchOperationReference(completeReference.toBuilder().clearSourceEpoch().build()),
        )
        assertFailure(
            "missing_watch_revision",
            ControlMessageMappers.watchOperationReference(completeReference.toBuilder().clearWatchRevision().build()),
        )
        assertFailure(
            "missing_expected_revision",
            ControlMessageMappers.watchOperationReference(
                completeReference.toBuilder().clearExpectedRevision().build(),
            ),
        )
    }

    @Test
    fun invalidControlErrorAndWatchReferenceFailClosed() {
        assertFailure(
            "unspecified_control_error",
            ControlMessageMappers.controlError(ControlError.getDefaultInstance()),
        )
        assertFailure(
            "missing_source_epoch",
            ControlMessageMappers.watchOperationReference(WatchOperationRef.getDefaultInstance()),
        )
        val completeReference =
            WatchOperationRef
                .newBuilder()
                .setServerInstanceId("server-instance")
                .setWatchSessionId("watch-session")
                .setOperationId("operation")
                .setSourceEpoch(0)
                .setWatchRevision(0)
                .setExpectedRevision(0)
                .build()
        assertFailure(
            "invalid_watch_revision",
            ControlMessageMappers.watchOperationReference(completeReference.toBuilder().setSourceEpoch(-1).build()),
        )
        assertFailure(
            "invalid_watch_revision",
            ControlMessageMappers.watchOperationReference(completeReference.toBuilder().setWatchRevision(-1).build()),
        )
        assertFailure(
            "invalid_watch_revision",
            ControlMessageMappers.watchOperationReference(
                completeReference.toBuilder().setExpectedRevision(-1).build(),
            ),
        )

        val invalidCurrentRevision =
            ControlError
                .newBuilder()
                .setCode(
                    ControlErrorCode.STALE_STATE,
                ).setCurrentRevision(-1)
                .build()
        assertFailure("invalid_watch_revision", ControlMessageMappers.controlError(invalidCurrentRevision))
    }

    private fun assertFailure(
        code: String,
        result: MbResult<*>,
    ) {
        val failure = assertIs<MbResult.Failure>(result)
        assertEquals(DomainError.Protocol(code), failure.error.error)
    }
}
