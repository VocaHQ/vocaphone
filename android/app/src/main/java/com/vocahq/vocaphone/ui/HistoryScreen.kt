package com.vocahq.vocaphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.DrawableRes
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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
                onRetry = { onRetry(record.sessionId) },
                onDelete = { onDelete(record.sessionId) },
                onCopy = { context.copyToClipboard(it) },
            )
        }
    }
}

@Composable
private fun HistoryRow(
    record: DictationRecordEntity,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val failed = record.state == RECORD_STATE_FAILED
    val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(record.createdAt))
    val transcript = record.transcript?.takeIf { it.isNotEmpty() }
    val canRetry = failed && record.recoverable && record.audioPath != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp, end = 8.dp),
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
            Row {
                if (canRetry) {
                    HistoryAction(
                        icon = R.drawable.ic_retry,
                        contentDescription = "Retry",
                        onClick = onRetry,
                        filled = true,
                    )
                }
                if (transcript != null) {
                    // Explicit only: VocaPhone never writes to the clipboard on its own.
                    HistoryAction(
                        icon = R.drawable.ic_copy,
                        contentDescription = "Copy",
                        onClick = { onCopy(transcript) },
                    )
                }
                HistoryAction(
                    icon = R.drawable.ic_delete,
                    contentDescription = "Delete",
                    onClick = onDelete,
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun HistoryAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    destructive: Boolean = false,
) {
    val tint = when {
        destructive -> MaterialTheme.colorScheme.error
        filled -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val painter = painterResource(icon)
    if (filled) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(painter, contentDescription, Modifier.size(20.dp), tint = tint)
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
        ) {
            Icon(painter, contentDescription, Modifier.size(20.dp))
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("VocaPhone transcript", text))
}
