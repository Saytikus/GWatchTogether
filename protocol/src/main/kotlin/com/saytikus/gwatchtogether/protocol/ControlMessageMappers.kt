package com.saytikus.gwatchtogether.protocol

import com.saytikus.gwatchtogether.domain.result.DomainError
import com.saytikus.gwatchtogether.domain.result.MbError
import com.saytikus.gwatchtogether.domain.result.MbResult
import com.saytikus.gwatchtogether.domain.result.Retryability
import com.saytikus.gwatchtogether.protocol.wire.ControlError
import com.saytikus.gwatchtogether.protocol.wire.ControlErrorCode
import com.saytikus.gwatchtogether.protocol.wire.WatchOperationRef

/** Domain-side identity/revision tuple used to reject stale Watch operations. */
data class WatchOperationReference(
    val serverInstanceId: String,
    val watchSessionId: String,
    val sourceEpoch: Long,
    val watchRevision: Long,
    val operationId: String,
    val expectedRevision: Long,
)

/** Domain-side control error; generated Protobuf DTOs remain at the transport boundary. */
data class ControlErrorModel(
    val error: MbError,
    val currentRevision: Long?,
)

object ControlMessageMappers {
    fun watchOperationReference(wire: WatchOperationRef): MbResult<WatchOperationReference> {
        if (!wire.hasSourceEpoch()) return failure("missing_source_epoch")
        if (!wire.hasWatchRevision()) return failure("missing_watch_revision")
        if (!wire.hasExpectedRevision()) return failure("missing_expected_revision")
        val model =
            WatchOperationReference(
                serverInstanceId = wire.serverInstanceId,
                watchSessionId = wire.watchSessionId,
                sourceEpoch = wire.sourceEpoch,
                watchRevision = wire.watchRevision,
                operationId = wire.operationId,
                expectedRevision = wire.expectedRevision,
            )
        if (model.serverInstanceId.isBlank() || model.watchSessionId.isBlank() || model.operationId.isBlank()) {
            return failure("invalid_watch_operation_reference")
        }
        if (model.sourceEpoch < 0 || model.watchRevision < 0 || model.expectedRevision < 0) {
            return failure("invalid_watch_revision")
        }
        return MbResult.Success(model)
    }

    fun controlError(wire: ControlError): MbResult<ControlErrorModel> {
        if (wire.hasCurrentRevision() && wire.currentRevision < 0) return failure("invalid_watch_revision")
        val domainError =
            when (wire.code) {
                ControlErrorCode.INVALID_ARGUMENT -> DomainError.Validation("invalid_argument")
                ControlErrorCode.STALE_STATE -> DomainError.Protocol("stale_state")
                ControlErrorCode.RESYNC_REQUIRED -> DomainError.Protocol("resync_required")
                ControlErrorCode.CAPABILITY_UNSUPPORTED -> DomainError.Protocol("capability_unsupported")
                ControlErrorCode.INCOMPATIBLE_PROTOCOL -> DomainError.Protocol("incompatible_protocol")
                ControlErrorCode.UPDATE_REQUIRED -> DomainError.Protocol("update_required")
                ControlErrorCode.TEMPORARILY_UNAVAILABLE -> DomainError.Connectivity("temporarily_unavailable")
                ControlErrorCode.INTERNAL -> DomainError.Protocol("internal")
                ControlErrorCode.UNAUTHENTICATED -> DomainError.Authorization("unauthenticated")
                ControlErrorCode.PERMISSION_DENIED -> DomainError.Authorization("permission_denied")
                ControlErrorCode.MEDIA_PREPARE_FAILED -> DomainError.Media("media_prepare_failed")
                ControlErrorCode.PARTICIPANT_TIMEOUT -> DomainError.Protocol("participant_timeout")
                ControlErrorCode.RESOURCE_EXHAUSTED -> DomainError.Protocol("resource_exhausted")
                ControlErrorCode.CONTROL_ERROR_UNSPECIFIED -> return failure("unspecified_control_error")
                ControlErrorCode.UNRECOGNIZED -> return failure("unknown_control_error")
            }
        return MbResult.Success(
            ControlErrorModel(
                error =
                    MbError(
                        error = domainError,
                        retryability = Retryability.Never,
                        diagnosticId = null,
                    ),
                currentRevision = if (wire.hasCurrentRevision()) wire.currentRevision else null,
            ),
        )
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
