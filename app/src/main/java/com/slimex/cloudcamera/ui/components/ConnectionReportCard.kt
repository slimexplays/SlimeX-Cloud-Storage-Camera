package com.slimex.cloudcamera.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.ConnectionStep
import com.slimex.cloudcamera.domain.model.ConnectionStepStatus

/** Colour + label mapping shared by both cloud configuration cards. */
fun ConnectionStep.pillTone(): PillTone = when (status) {
    ConnectionStepStatus.PASSED -> PillTone.GREEN
    ConnectionStepStatus.FAILED -> PillTone.RED
    ConnectionStepStatus.WARNING -> PillTone.AMBER
    ConnectionStepStatus.RUNNING -> PillTone.PURPLE
    ConnectionStepStatus.SKIPPED, ConnectionStepStatus.PENDING -> PillTone.NEUTRAL
}

fun ConnectionStep.statusLabel(): String = when (status) {
    ConnectionStepStatus.PASSED -> "Passed"
    ConnectionStepStatus.FAILED -> "Failed"
    ConnectionStepStatus.WARNING -> "Warning"
    ConnectionStepStatus.RUNNING -> "Testing"
    ConnectionStepStatus.SKIPPED -> "Skipped"
    ConnectionStepStatus.PENDING -> "Pending"
}

/** Renders the ordered steps of a "Test connection" run. */
@Composable
fun ConnectionReportCard(
    report: ConnectionReport,
    modifier: Modifier = Modifier,
) {
    SlimeCard(modifier = modifier) {
        Column {
            report.steps.forEachIndexed { index, step ->
                StepRow(
                    title = step.title,
                    detail = step.detail,
                    tone = step.pillTone(),
                    statusLabel = step.statusLabel(),
                )
                if (index != report.steps.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
