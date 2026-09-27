package com.saytikus.gwatchtogether.protocol

import com.google.protobuf.InvalidProtocolBufferException
import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import com.saytikus.gwatchtogether.protocol.wire.ControlEnvelope

/** Decodes only a ClientHello envelope; request ID presence does not affect the handshake model. */
object ClientHelloEnvelopeCodec {
    fun decode(bytes: ByteArray): MbResult<ClientHelloModel> {
        if (bytes.size > ClientHelloCodec.MAX_MESSAGE_BYTES) return failure("message_too_large")
        val envelope =
            try {
                ControlEnvelope.parseFrom(bytes)
            } catch (_: InvalidProtocolBufferException) {
                return failure("malformed_control_envelope")
            }
        if (envelope.payloadCase != ControlEnvelope.PayloadCase.CLIENT_HELLO) {
            return failure("unexpected_control_payload")
        }
        return ClientHelloCodec.decode(envelope.clientHello.toByteArray())
    }

    private fun failure(code: String): MbResult.Failure =
        MbResult.Failure(
            MbError(
                error = DomainError.Protocol(code),
                retryability = Retryability.Never,
                diagnosticId = null,
            ),
        )
}
