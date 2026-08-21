package com.vocahq.vocaphone.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vocahq.vocaphone.BuildConfig
import com.vocahq.vocaphone.R
import com.vocahq.vocaphone.core.DictationPhase
import com.vocahq.vocaphone.core.DictationState
import com.vocahq.vocaphone.core.TextInsertion
import com.vocahq.vocaphone.local.LocalModelCatalog
import com.vocahq.vocaphone.settings.VocaPhoneSettings
import com.vocahq.vocaphone.telemetry.TelemetryInspectPayload

internal object DictateCopy {
    const val DICTATE = "Dictate"
    const val CLEAR = "Clear"
    const val LANGUAGE = "Language"
    const val STYLE = "Writing style"
    const val MODEL = "Model"
    const val GATEWAY = "Gateway"
    const val NO_MODEL = "No model"
}

/**
 * In-app dictation. The transcript lands in a scratchpad the user can edit.
 */
@Composable
fun DictateScreen(
    state: DictationState,
    settings: VocaPhoneSettings,
    setup: SetupStatus,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenGateway: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenStyle: () -> Unit,
    onOpenModel: () -> Unit,
    onTelemetryDecision: (Boolean) -> Unit,
    telemetryInspect: () -> TelemetryInspectPayload,
    telemetryPendingCount: () -> Int,
    telemetryDeliveryStatus: () -> String,
    modifier: Modifier = Modifier,
) {
    var scratchpad by remember { mutableStateOf(TextFieldValue()) }

    // A finished in-app dictation is spliced at the scratchpad cursor.
    LaunchedEffect(state.transcript, state.phase) {
        val transcript = state.transcript
        if (transcript != null && state.phase == DictationPhase.READY_TO_INSERT) {
            val text = scratchpad.text
            val selection = scratchpad.selection
            val plan = TextInsertion.plan(text, selection.start, selection.end, transcript)
            if (plan != null) {
                scratchpad = TextFieldValue(
                    text = plan.updatedText,
                    selection = androidx.compose.ui.text.TextRange(plan.cursor),
                )
            }
            onDismiss()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Guided setup asks once, at its end -- but only people who go through
        // setup ever reach that screen. Everyone upgrading from an earlier beta
        // already has onboardingComplete set, so SetupScreen never renders for
        // them and they would never be asked at all. Asking here covers them,
        // and disappears for good either way once answered.
        if (BuildConfig.TELEMETRY && settings.onboardingComplete && !settings.telemetryAsked) {
            UsageReportingSetupCard(
                onDecision = onTelemetryDecision,
                inspect = telemetryInspect,
                pendingCount = telemetryPendingCount,
                deliveryStatus = telemetryDeliveryStatus,
            )
        }

        DictateShortcutBar(
            language = settings.effectiveLanguage.displayName,
            style = settings.style.displayName,
            model = dictateModelChipLabel(settings),
            modelOnDevice = settings.localTranscriptionEnabled,
            onOpenLanguage = onOpenLanguage,
            onOpenStyle = onOpenStyle,
            onOpenModel = onOpenModel,
        )

        if (showDictateStatus(state.phase) || state.isRecording || state.phase == DictationPhase.FAILED) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showDictateStatus(state.phase)) {
                    Text(state.statusText, style = MaterialTheme.typography.bodyLarge)
                }
                if (state.isRecording) {
                    LinearProgressIndicator(
                        progress = { state.level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${state.recordedMillis / 1000}s" +
                            (state.inputRouteLabel?.let { " · $it" } ?: "") +
                            (if (state.streaming) " · streaming" else " · batch upload"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.approachingLimit) {
                        Text(
                            "One minute left before the five-minute limit stops this recording.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.partialTranscript.isNotEmpty()) {
                        Text(
                            state.partialTranscript,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.phase == DictationPhase.FAILED) {
                    Text(
                        state.failure?.message.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SetupRepair(
            status = setup,
            onOpenGateway = onOpenGateway,
        )

        Text("Scratchpad", style = MaterialTheme.typography.titleSmall)
        Text(
            "Transcripts are inserted at the cursor. Nothing here is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = scratchpad,
            onValueChange = { scratchpad = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Your text") },
        )

        DictateActionRow(
            state = state,
            setup = setup,
            scratchpadEmpty = scratchpad.text.isEmpty(),
            onStart = onStart,
            onFinish = onFinish,
            onCancel = onCancel,
            onRetry = onRetry,
            onDismiss = onDismiss,
            onClear = { scratchpad = TextFieldValue() },
        )
    }
}

@Composable
private fun DictateActionRow(
    state: DictationState,
    setup: SetupStatus,
    scratchpadEmpty: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.isRecording -> {
                SecondaryButton("Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                PrimaryButton("Finish", onClick = onFinish, modifier = Modifier.weight(2f))
            }
            state.phase.isBusy -> SecondaryButton(
                "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            state.canRetry -> {
                SecondaryButton("Dismiss", onClick = onDismiss, modifier = Modifier.weight(1f))
                PrimaryButton(
                    text = "Retry",
                    onClick = { state.sessionId?.let { onRetry(it.toString()) } },
                    modifier = Modifier.weight(2f),
                )
            }
            else -> {
                DestructiveButton(
                    text = DictateCopy.CLEAR,
                    onClick = onClear,
                    enabled = !scratchpadEmpty,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = DictateCopy.DICTATE,
                    onClick = onStart,
                    enabled = setup.isReadyToDictate,
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }
}

@Composable
private fun DictateShortcutBar(
    language: String,
    style: String,
    model: String,
    modelOnDevice: Boolean,
    onOpenLanguage: () -> Unit,
    onOpenStyle: () -> Unit,
    onOpenModel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            DictateShortcutCell(
                icon = R.drawable.ic_language,
                label = language,
                contentDescription = "${DictateCopy.LANGUAGE}, $language",
                onClick = onOpenLanguage,
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DictateShortcutCell(
                icon = R.drawable.ic_style,
                label = style,
                contentDescription = "${DictateCopy.STYLE}, $style",
                onClick = onOpenStyle,
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DictateShortcutCell(
                icon = if (modelOnDevice) R.drawable.ic_models else R.drawable.ic_connection,
                label = model,
                contentDescription = "${DictateCopy.MODEL}, $model",
                onClick = onOpenModel,
            )
        }
    }
}

@Composable
private fun RowScope.DictateShortcutCell(
    @DrawableRes icon: Int,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun dictateModelChipLabel(settings: VocaPhoneSettings): String =
    if (settings.localTranscriptionEnabled) {
        LocalModelCatalog.find(settings.localModelId)?.displayName ?: DictateCopy.NO_MODEL
    } else {
        DictateCopy.GATEWAY
    }

internal fun showDictateStatus(phase: DictationPhase): Boolean =
    phase != DictationPhase.IDLE &&
        phase != DictationPhase.FAILED &&
        phase != DictationPhase.PERMISSION_REPAIR
