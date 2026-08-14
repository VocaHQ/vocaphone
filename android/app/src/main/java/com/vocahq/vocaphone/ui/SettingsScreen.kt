package com.vocahq.vocaphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vocahq.vocaphone.core.CustomVocabulary
import com.vocahq.vocaphone.core.MicrophonePreference
import com.vocahq.vocaphone.core.ModelLanguageSupport
import com.vocahq.vocaphone.core.TranscriptionLanguage
import com.vocahq.vocaphone.core.TranscriptionQuality
import com.vocahq.vocaphone.core.WritingStyle
import com.vocahq.vocaphone.local.LocalModelCatalog
import com.vocahq.vocaphone.local.LocalModelDescriptor
import com.vocahq.vocaphone.local.LocalModelState
import com.vocahq.vocaphone.settings.AudioRetention
import com.vocahq.vocaphone.settings.KeyboardHeight
import com.vocahq.vocaphone.settings.VocaPhoneSettings

@Composable
fun SettingsScreen(
    settings: VocaPhoneSettings,
    setup: SetupStatus,
    microphone: MicrophoneStatus,
    onLanguage: (TranscriptionLanguage) -> Unit,
    onStyle: (WritingStyle) -> Unit,
    onMicrophone: (MicrophonePreference) -> Unit,
    onAudioRetention: (AudioRetention) -> Unit,
    onTranscriptionQuality: (TranscriptionQuality) -> Unit,
    onCustomVocabulary: (String) -> Unit,
    onNumberRow: (Boolean) -> Unit,
    onKeyboardHeight: (KeyboardHeight) -> Unit,
    onSuggestions: (Boolean) -> Unit,
    onClipboardChip: (Boolean) -> Unit,
    localModels: LocalModelState,
    onLocalTranscriptionEnabled: (Boolean) -> Unit,
    onLocalModel: (LocalModelDescriptor) -> Unit,
    onDownloadLocalModel: (LocalModelDescriptor) -> Unit,
    onCancelLocalModelDownload: () -> Unit,
    onDeleteLocalModel: (LocalModelDescriptor) -> Unit,
    onOpenGateway: () -> Unit,
    onTryDictation: () -> Unit,
    diagnosticEvents: () -> String,
    onClearDiagnosticEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appInfo = remember { context.readAppInfo() }
    var pickingLanguage by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Section(
            title = "Try dictation",
            supporting = "Record in the app, or pick VocaPhone as the keyboard in any text field.",
        ) {
            PrimaryButton("Try dictation", onClick = onTryDictation, modifier = Modifier.fillMaxWidth())
        }

        Section(
            title = "On-device accuracy",
            supporting = "${settings.transcriptionQuality.detail}\n" +
                "Applies to models running on this phone. The gateway decides for itself.",
        ) {
            ChipChoiceRow(
                options = TranscriptionQuality.entries,
                selected = settings.transcriptionQuality,
                label = { it.displayName },
                onSelect = onTranscriptionQuality,
            )
            // Changing this rebuilds a sherpa engine, which takes seconds. Said
            // here so the reload is something the user watched finish rather
            // than something the next dictation runs into.
            localModels.preparing?.let { name ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Loading $name… Please wait.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        CustomVocabularySection(
            vocabulary = settings.customVocabulary,
            onSave = onCustomVocabulary,
            unsupportedModel = LocalModelCatalog.find(settings.localModelId)
                ?.takeIf { settings.localTranscriptionEnabled && !it.supportsCustomVocabulary }
                ?.displayName,
        )

        ImeSetupCard(setup.ime)

        // One row rather than 27 wrapping chips, which pushed every setting below
        // this one off the screen. The full list, with search, lives in a sheet.
        val languageRestriction = ModelLanguageSupport.restriction(
            settings.activeModelLanguages,
            settings.activeModelDetectsLanguage,
            onDevice = settings.localTranscriptionEnabled,
        )
        Section(
            "Transcription language",
            supporting = listOfNotNull(settings.effectiveLanguage.detail, languageRestriction)
                .joinToString("\n"),
        ) {
            InfoRow(label = "Language", value = settings.effectiveLanguage.displayName)
            SecondaryButton("Change language", onClick = { pickingLanguage = true })
        }
        if (pickingLanguage) {
            LanguagePickerSheet(
                selected = settings.effectiveLanguage,
                modelLanguages = settings.activeModelLanguages,
                detectsLanguageAutomatically = settings.activeModelDetectsLanguage,
                onDevice = settings.localTranscriptionEnabled,
                onSelect = onLanguage,
                onDismiss = { pickingLanguage = false },
            )
        }

        Section(
            title = "Writing style",
            supporting = "${settings.style.detail}\n${settings.style.example}",
        ) {
            ChipChoiceRow(
                options = WritingStyle.entries,
                selected = settings.style,
                label = { it.displayName },
                onSelect = onStyle,
            )
        }

        Section(
            title = "Keyboard",
            supporting = "Layout and typing helpers for the VocaPhone keyboard.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Number row")
                    Text(
                        "Show 1-0 above the letter keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.numberRowEnabled,
                    onCheckedChange = onNumberRow,
                )
            }
            Text("Height", style = MaterialTheme.typography.bodyMedium)
            ChipChoiceRow(
                options = KeyboardHeight.entries,
                selected = settings.keyboardHeight,
                label = { it.displayName },
                onSelect = onKeyboardHeight,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Suggestions")
                    Text(
                        "Local English word completions and next-word guesses. " +
                            "Reads a short window of text before the cursor. Off in passwords.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.suggestionsEnabled,
                    onCheckedChange = onSuggestions,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Clipboard paste")
                    Text(
                        "Show a paste chip before you start typing. Predictions replace it once you type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.clipboardChipEnabled,
                    onCheckedChange = onClipboardChip,
                )
            }
        }

        MicrophoneSection(
            selected = settings.microphone,
            status = microphone,
            onSelect = onMicrophone,
        )

        Section(
            title = "Audio retention",
            supporting = "Successful dictations delete their audio immediately. A " +
                "failed one keeps it only this long, so Retry still works.",
        ) {
            ChipChoiceRow(
                options = AudioRetention.entries,
                selected = settings.audioRetention,
                label = { it.displayName },
                onSelect = onAudioRetention,
            )
        }

        Section(
            title = "Gateway",
            supporting = settings.gatewayUrl.ifEmpty { "Not configured" },
        ) {
            Text(
                buildString {
                    append("Engine: ")
                    append(settings.lastEngine.ifEmpty { "unknown" })
                    append(if (settings.lastEngineReady) " (ready)" else " (not ready)")
                    append("\nStreaming: ")
                    append(if (settings.lastStreamingSupported) "supported" else "batch upload")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            SecondaryButton("Gateway settings", onClick = onOpenGateway)
            SecondaryButton(
                text = "Open web dashboard",
                onClick = { context.openUrl(settings.gatewayUrl) },
                enabled = settings.gatewayUrl.isNotEmpty(),
            )
            Text(
                "For more customization, including choosing the speech-to-text model, " +
                    "open your gateway's web dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(
            title = "On-device models",
            supporting = "Run speech-to-text privately on this phone. The gateway is not used while this is enabled.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use on-device transcription")
                    Text(
                        "Downloads are checked with SHA-256 before they are accepted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.localTranscriptionEnabled,
                    onCheckedChange = onLocalTranscriptionEnabled,
                )
            }
            if (modelsOpen) {
                LocalModelPicker(
                    state = localModels,
                    selectedModelId = settings.localModelId,
                    onSelect = onLocalModel,
                    onDownload = onDownloadLocalModel,
                    onCancelDownload = onCancelLocalModelDownload,
                    onDelete = onDeleteLocalModel,
                )
                TextButton(onClick = { modelsOpen = false }) { Text("Hide model catalog") }
            } else {
                Text(
                    if (settings.localTranscriptionEnabled && settings.localModelId.isNotEmpty()) {
                        "Using ${settings.localModelId}."
                    } else {
                        "Catalog is hidden so the rest of Settings stays on screen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton("Show model catalog", onClick = { modelsOpen = true })
            }
        }

        Section("Privacy") {
            Text(
                "VocaPhone's keyboard inserts through Android's text connection. " +
                    "Dictation does not read the field. With Suggestions on, the keyboard " +
                    "reads about 32 characters before the cursor so it can guess the next " +
                    "word; that text stays on this phone and is never logged. The clipboard " +
                    "chip reads the current clip only while the keyboard is visible and " +
                    "you have not started typing. Audio " +
                    "goes to on-device transcription or the gateway you configured. There is " +
                    "no cloud transcription, no analytics, and nothing is written to the " +
                    "clipboard unless you tap Copy.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section(
            title = "About",
            supporting = "VocaPhone ${appInfo.versionName} (${appInfo.versionCode})",
        ) {
            InfoRow("Android", "${appInfo.androidRelease} (SDK ${appInfo.sdkInt})")
            InfoRow("Device", appInfo.device)
            InfoRow("Installed from", appInfo.installedFrom)
            InfoRow("Package", appInfo.packageName)
            InfoRow(
                "Engine",
                settings.lastEngine.ifEmpty { "unknown" } +
                    if (settings.lastEngineReady) " (ready)" else " (not ready)",
            )
            InfoRow(
                "Setup",
                if (setup.isReadyToDictate) {
                    "complete"
                } else {
                    "${setup.completedStepCount} of ${setup.stepCount} steps"
                },
            )
            Text(
                "Diagnostics contain only bounded timestamps, state transitions, " +
                    "error categories and build/source context. They never include " +
                    "transcripts, typed text, audio, gateway hosts or tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "Copy diagnostics",
                    onClick = {
                        context.copyDiagnostics(
                            diagnosticsReport(appInfo, settings, setup, diagnosticEvents())
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Project page",
                    onClick = { context.openUrl(PROJECT_URL) },
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = onClearDiagnosticEvents) { Text("Clear event log") }
        }
    }
}

/**
 * Which microphone dictation asks for. Options the hardware cannot satisfy stay
 * visible but greyed, and the whole row locks while a dictation is running: the
 * input is chosen when the recorder is built, so a mid-recording change would be
 * a promise the current dictation cannot keep.
 */
@Composable
private fun MicrophoneSection(
    selected: MicrophonePreference,
    status: MicrophoneStatus,
    onSelect: (MicrophonePreference) -> Unit,
) {
    val attached = selected in status.available
    Section(
        title = "Microphone",
        supporting = if (attached) selected.detail else selected.unavailableDetail,
    ) {
        ChipChoiceRow(
            options = MicrophonePreference.entries,
            selected = selected,
            label = { it.displayName },
            onSelect = onSelect,
            enabled = { status.changeable && (it in status.available || it == selected) },
        )

        InfoRow("Input in use", status.inUseLabel(selected))

        Text(
            if (!status.changeable) {
                "Finish the current dictation before changing microphones."
            } else {
                "Greyed-out options have no matching microphone connected. " +
                    "Android has the final say on routing, so the input above " +
                    "is what capture actually used."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The list is edited locally and saved on request rather than on every
 * keystroke: the terms are parsed at inference time, and a half-typed name
 * being persisted mid-word is a spelling nobody asked to be biased toward.
 */
@Composable
private fun CustomVocabularySection(
    vocabulary: String,
    onSave: (String) -> Unit,
    unsupportedModel: String?,
) {
    var draft by remember(vocabulary) { mutableStateOf(vocabulary) }
    val terms = remember(draft) { CustomVocabulary.terms(draft) }

    Section(
        title = "Custom words",
        supporting = "Names, places and jargon an on-device Whisper model is " +
            "unlikely to know. One per line, or separated by commas.",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Words and phrases") },
            placeholder = { Text("Kanishk\nVocaHQ\nTailscale") },
            minLines = 3,
            maxLines = 6,
        )
        Text(
            if (terms.isEmpty()) {
                "No custom words. Transcription is unchanged."
            } else {
                "${terms.size} word${if (terms.size == 1) "" else "s"} will bias the decoder. " +
                    "This nudges spelling rather than guaranteeing it, and a very long " +
                    "list starts to crowd out the speech itself."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Said plainly rather than letting the list quietly do nothing: only
        // Whisper's decoder has somewhere to put a vocabulary.
        if (unsupportedModel != null && terms.isNotEmpty()) {
            Text(
                "$unsupportedModel cannot use these words. Only Whisper models take a " +
                    "vocabulary; the list is kept for when you switch back to one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SecondaryButton(
            text = "Save words",
            onClick = { onSave(draft) },
            enabled = draft != vocabulary,
        )
    }
}

private fun Context.copyDiagnostics(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("VocaPhone diagnostics", text))
}

/** No browser is a plausible state on a stripped-down ROM, so failure is silent. */
private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
