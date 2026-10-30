package com.slimex.cloudcamera.domain.model

enum class ConnectionStepStatus { PENDING, RUNNING, PASSED, WARNING, FAILED, SKIPPED }

data class ConnectionStep(
    val title: String,
    val status: ConnectionStepStatus = ConnectionStepStatus.PENDING,
    val detail: String = "",
    val required: Boolean = true,
)

/** Full outcome of "Test connection" in the Supabase setup screen. */
data class ConnectionReport(
    val steps: List<ConnectionStep> = emptyList(),
    val success: Boolean = false,
    val latencyMs: Long = 0,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val failedStep: ConnectionStep? get() = steps.firstOrNull { it.status == ConnectionStepStatus.FAILED }
}

/** User-facing validation errors for the Supabase setup form. */
sealed interface ConfigValidation {
    data object Valid : ConfigValidation
    data class Invalid(val reason: String) : ConfigValidation
}
