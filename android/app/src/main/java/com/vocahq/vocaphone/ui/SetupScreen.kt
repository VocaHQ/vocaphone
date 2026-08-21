package com.vocahq.vocaphone.ui

import android.Manifest
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import com.vocahq.vocaphone.BuildConfig
import com.vocahq.vocaphone.R
import com.vocahq.vocaphone.local.LocalModelDescriptor
import com.vocahq.vocaphone.local.LocalModelState
import com.vocahq.vocaphone.settings.VocaPhoneSettings
import com.vocahq.vocaphone.telemetry.TelemetryInspectPayload

internal object SetupCopy {
    /** Vector mark. Adaptive mipmaps crash painterResource. */
    val LOGO = R.drawable.ic_vocaphone_logo
    const val TITLE = "Set up VocaPhone"
    const val INTRO = "Turn on the keyboard, allow the microphone, then download a model."
    const val START = "Start dictating"
    const val BROWSE_MODELS = "Browse other models"
    const val BROWSE_MODELS_DETAIL =
        "English-only, smaller, or Whisper models also fit this phone."
    const val BROWSE_SHEET_TITLE = "Other models"
    const val BROWSE_SHEET_SUPPORTING =
        "These also run on this phone. The recommendation is still the default."
    const val WELCOME_SAMPLE = "Meet me by the station at six."
    const val WELCOME_A11Y = "A short waveform becomes text at the cursor."

    fun keyboardStatus(status: ImeSetupStatus): String = when {
        status.selected -> "VocaPhone is the selected keyboard."
        status.enabled -> "Choose VocaPhone from the keyboard list."
        else -> "Turn on the VocaPhone keyboard."
    }

    fun keyboardAction(status: ImeSetupStatus): String? = when {
        status.selected -> null
        status.enabled -> "Choose VocaPhone keyboard"
        else -> "Enable keyboard"
    }
}

/**
 * Guided setup for the IME path. Short enough to finish without scrolling
 * past a catalog.
 */
@Composable
fun SetupScreen(
    status: SetupStatus,
    settings: VocaPhoneSettings,
    localModels: LocalModelState,
    onOpenGateway: () -> Unit,
    onLocalTranscriptionEnabled: (Boolean) -> Unit,
    onLocalModel: (LocalModelDescriptor) -> Unit,
    onDownloadLocalModel: (LocalModelDescriptor) -> Unit,
    onDownloadAndUseLocalModel: (LocalModelDescriptor) -> Unit,
    onCancelLocalModelDownload: () -> Unit,
    onTelemetryDecision: (Boolean) -> Unit,
    telemetryInspect: () -> TelemetryInspectPayload,
    telemetryPendingCount: () -> Int,
    telemetryDeliveryStatus: () -> String,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupWelcomeVisual()
                Text(
                    SetupCopy.INTRO,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SetupProgress(status)
            }

            ImeSetupCard(status.ime)

            Section("Permissions") {
                ChecklistRow(
                    title = "Microphone",
                    detail = "Only while you dictate.",
                    satisfied = status.microphone,
                    actionLabel = "Grant",
                    onAction = { requestPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
                ChecklistRow(
                    title = "Notifications",
                    detail = "Shown while you record.",
                    satisfied = status.notifications,
                    actionLabel = "Grant",
                    onAction = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            Section("Speech") {
                SpeechSourceCard(
                    settings = settings,
                    compact = true,
                    onOpenGateway = onOpenGateway,
                    onLocalTranscriptionEnabled = onLocalTranscriptionEnabled,
                )
                if (settings.localTranscriptionEnabled) {
                    LocalModelPicker(
                        state = localModels,
                        selectedModelId = settings.localModelId,
                        compact = true,
                        onSelect = onLocalModel,
                        onDownload = onDownloadLocalModel,
                        onDownloadAndUse = onDownloadAndUseLocalModel,
                        onCancelDownload = onCancelLocalModelDownload,
                    )
                }
            }

            // Last, and only once the checklist is done: asking for usage reporting
            // before the user has a working transcript is asking a favour of someone
            // still deciding whether the app is worth their time.
            //
            // The BuildConfig check is repeated here even though the card checks it
            // too, because the payload view below is a separate call. Without it R8
            // keeps that composable — and the ingest path string inside it — in the
            // F-Droid APK, where nothing can ever reach it.
            if (BuildConfig.TELEMETRY && status.isReadyToDictate && !settings.telemetryAsked) {
                UsageReportingSetupCard(
                    onDecision = onTelemetryDecision,
                    inspect = telemetryInspect,
                    pendingCount = telemetryPendingCount,
                    deliveryStatus = telemetryDeliveryStatus,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = SetupCopy.START,
                onClick = onFinish,
                enabled = status.isReadyToDictate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Setup handoff for enabling and selecting the system keyboard. */
@Composable
internal fun ImeSetupCard(status: ImeSetupStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val action = SetupCopy.keyboardAction(status)
    if (action == null) {
        Text(
            SetupCopy.keyboardStatus(status),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Notice(modifier = modifier) {
        Text("VocaPhone keyboard", style = MaterialTheme.typography.titleSmall)
        Text(
            SetupCopy.keyboardStatus(status),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        SecondaryButton(
            text = action,
            onClick = {
                if (status.enabled) {
                    ImeSetup.showPicker(context)
                } else {
                    ImeSetup.openSettings(context)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The same first-run moment as iOS: speech as bars, then a sentence at a
 * cursor. Animations off skips the listen beat and shows the typed state.
 */
@Composable
private fun SetupWelcomeVisual(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    var typed by remember { mutableStateOf(reduceMotion) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            typed = true
            return@LaunchedEffect
        }
        delay(1_050)
        typed = true
    }

    val wave by rememberInfiniteTransition(label = "setup-welcome").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "setup-welcome-phase",
    )
    val cursor by rememberInfiniteTransition(label = "setup-cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(530, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "setup-cursor-blink",
    )
    val barColor = MaterialTheme.colorScheme.primary
    val listen by animateFloatAsState(
        targetValue = if (typed) 0f else 1f,
        animationSpec = tween(280),
        label = "setup-welcome-settle",
    )

    FeaturedCard(
        modifier = modifier.semantics { contentDescription = SetupCopy.WELCOME_A11Y },
    ) {
        Row(
            modifier = Modifier.height(28.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            repeat(12) { index ->
                val rest = 8f + (index % 5) * 5f
                val listening = 8f + 16f * abs(
                    sin((wave + index / 12f) * 2f * Math.PI.toFloat()),
                )
                Box(
                    Modifier
                        .width(4.dp)
                        .height((rest + (listening - rest) * listen).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor.copy(alpha = 0.35f + 0.5f * listen)),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (typed) {
                Text(SetupCopy.WELCOME_SAMPLE, style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(18.dp)
                    .background(
                        barColor.copy(
                            alpha = if (!typed) {
                                0f
                            } else if (reduceMotion) {
                                1f
                            } else {
                                cursor
                            },
                        ),
                    ),
            )
        }
    }
}

/** How far through setup the user is, so the list has a visible end. */
@Composable
private fun SetupProgress(status: SetupStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${status.completedStepCount} of ${status.stepCount} steps done",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { status.completedStepCount.toFloat() / status.stepCount },
            modifier = Modifier.fillMaxWidth(),
        )
        // Remaining work is the checklist below. Chips here only repeated it.
    }
}
