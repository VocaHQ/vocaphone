package com.vocahq.vocaphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vocahq.vocaphone.R
import com.vocahq.vocaphone.core.TranscriptionLanguage
import com.vocahq.vocaphone.core.WritingStyle
import com.vocahq.vocaphone.data.DictationRecordEntity
import com.vocahq.vocaphone.data.RECORD_STATE_FAILED
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    records: List<DictationRecordEntity>,
    selectedIds: Set<String>,
    onRetry: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onEnterSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val selecting = selectedIds.isNotEmpty()

    if (records.isEmpty()) {
        EmptyState(
            "Dictations you make will be listed here. Nothing is uploaded except " +
                "the audio your gateway transcribes.",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        items(records, key = { it.sessionId }) { record ->
            HistoryRow(
                record = record,
                selected = record.sessionId in selectedIds,
                selecting = selecting,
                onRetry = { onRetry(record.sessionId) },
                onCopy = { text ->
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    context.copyToClipboard(text)
                },
                onToggleSelect = { onToggleSelect(record.sessionId) },
                onEnterSelect = { onEnterSelect(record.sessionId) },
            )
        }
    }
}

/** Tap copies. Long-press starts a selection. Tapping a selected item drops it. */
internal fun toggleHistorySelection(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    record: DictationRecordEntity,
    selected: Boolean,
    selecting: Boolean,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onToggleSelect: () -> Unit,
    onEnterSelect: () -> Unit,
) {
    val failed = record.state == RECORD_STATE_FAILED
    val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(record.createdAt))
    val transcript = record.transcript?.takeIf { it.isNotEmpty() }
    val canRetry = !selecting && failed && record.recoverable && record.audioPath != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                onLongClick(label = "Select") { onEnterSelect(); true }
            }
            .combinedClickable(
                onClick = {
                    if (selecting) {
                        onToggleSelect()
                    } else if (transcript != null) {
                        onCopy(transcript)
                    }
                },
                onLongClick = {
                    if (selecting) onToggleSelect() else onEnterSelect()
                },
            ),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (selecting) 8.dp else 16.dp,
                top = 12.dp,
                end = if (canRetry) 4.dp else 16.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.Top,
        ) {
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = if (canRetry) 8.dp else 0.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (failed) {
                    Text(
                        record.errorMessage ?: "This dictation failed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (transcript != null) {
                    Text(transcript, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    buildString {
                        append(timestamp)
                        append(" · ")
                        append(TranscriptionLanguage.fromWire(record.language).displayName)
                        append(" · ")
                        append(WritingStyle.fromWire(record.style).displayName)
                        if (record.insertedIntoField) append(" · inserted")
                        if (record.audioPath != null) append(" · audio kept for retry")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canRetry) {
                FilledTonalIconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_retry),
                        contentDescription = "Retry",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    // Explicit only: VocaPhone never writes to the clipboard on its own.
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("VocaPhone transcript", text))
}
