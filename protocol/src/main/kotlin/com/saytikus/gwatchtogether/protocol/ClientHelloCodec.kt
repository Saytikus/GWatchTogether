package com.saytikus.gwatchtogether.protocol

import com.google.protobuf.InvalidProtocolBufferException
import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import com.saytikus.gwatchtogether.protocol.wire.ClientHello

/** Domain-side handshake input; the generated wire DTO stays at the transport boundary. */
data class ClientHelloModel(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val appVersion: String,
    val capabilities: Set<String>,
)

/** Offline POC handshake codec. A transport must also cap body size before allocating the incoming byte array. */
object ClientHelloCodec {
    const val MAX_MESSAGE_BYTES: Int = 16 * 1024
    private const val MAX_APP_VERSION_BYTES = 96
    private const val MAX_CAPABILITIES = 16
    private const val MAX_CAPABILITY_BYTES = 64
    private val appVersionValue = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]*")
    private val capabilityName = Regex("[A-Za-z][A-Za-z0-9._-]*")

    fun encode(hello: ClientHelloModel): MbResult<ByteArray> {
        val invalid = validate(hello)
        if (invalid != null) return MbResult.Failure(invalid)

        val wire =
            ClientHello
                .newBuilder()
                .setProtocolMajor(hello.protocolMajor)
                .setProtocolMinor(hello.protocolMinor)
                .setAppVersion(hello.appVersion)
                .addAllCapabilities(hello.capabilities)
                .build()
        if (wire.serializedSize > MAX_MESSAGE_BYTES) return failure("message_too_large")
        return MbResult.Success(wire.toByteArray())
    }

    fun decode(bytes: ByteArray): MbResult<ClientHelloModel> {
        if (bytes.size > MAX_MESSAGE_BYTES) return failure("message_too_large")
        val wire =
            try {
                ClientHello.parseFrom(bytes)
            } catch (_: InvalidProtocolBufferException) {
                return failure("malformed_client_hello")
            }
        if (!wire.hasProtocolMajor() || !wire.hasProtocolMinor() || !wire.hasAppVersion()) {
            return failure("missing_client_hello_field")
        }
        val hello =
            ClientHelloModel(
                protocolMajor = wire.protocolMajor,
                protocolMinor = wire.protocolMinor,
                appVersion = wire.appVersion,
                capabilities = wire.capabilitiesList.toSet(),
            )
        if (wire.capabilitiesCount != hello.capabilities.size) return failure("invalid_capabilities")
        val invalid = validate(hello)
        return if (invalid == null) MbResult.Success(hello) else MbResult.Failure(invalid)
    }

    private fun validate(hello: ClientHelloModel): MbError? {
        if (hello.protocolMajor != 1 || hello.protocolMinor != 0) return error("incompatible_protocol")
        if (hello.appVersion.length > MAX_APP_VERSION_BYTES ||
            hello.appVersion.encodeToByteArray().size > MAX_APP_VERSION_BYTES ||
            !appVersionValue.matches(hello.appVersion)
        ) {
            return error("invalid_app_version")
        }
        if (hello.capabilities.size > MAX_CAPABILITIES) return error("invalid_capabilities")
        if (hello.capabilities.any {
                it.length > MAX_CAPABILITY_BYTES ||
                    it.encodeToByteArray().size > MAX_CAPABILITY_BYTES ||
                    !capabilityName.matches(
                        it,
                    )
            }
        ) {
            return error("invalid_capabilities")
        }
        return null
    }

    private fun error(code: String) =
        MbError(
            error = DomainError.Protocol(code),
            retryability = Retryability.Never,
            diagnosticId = null,
        )

    private fun failure(code: String): MbResult.Failure = MbResult.Failure(error(code))
}
