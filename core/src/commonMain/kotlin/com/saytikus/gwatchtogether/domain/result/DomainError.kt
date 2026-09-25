package com.saytikus.gwatchtogether.domain.result

/** A stable category and code for an expected domain or boundary failure. */
sealed interface DomainError {
    val code: String

    data class Connectivity(
        override val code: String,
    ) : DomainError

    data class Protocol(
        override val code: String,
    ) : DomainError

    data class Media(
        override val code: String,
    ) : DomainError

    data class Authorization(
        override val code: String,
    ) : DomainError

    data class Storage(
        override val code: String,
    ) : DomainError

    data class Validation(
        override val code: String,
    ) : DomainError

    data class Diagnostics(
        override val code: String,
    ) : DomainError
}

data class DiagnosticId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Diagnostic IDs must not be blank" }
    }
}

enum class Retryability {
    Never,
    Safe,
    AfterBackoff,
}

data class MbError(
    val error: DomainError,
    val retryability: Retryability,
    val diagnosticId: DiagnosticId?,
)
