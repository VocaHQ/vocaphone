package com.vocahq.vocaphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vocahq.vocaphone.R
import com.vocahq.vocaphone.local.LocalModelDescriptor
import com.vocahq.vocaphone.settings.VocaPhoneSettings

/**
 * Settings → About. Same three blocks as VocaWin: this app, the family, then
 * talk to us. Device diagnostics stay underneath.
 */
@Composable
fun AboutPage(
    appInfo: AppInfo,
    settings: VocaPhoneSettings,
    setup: SetupStatus,
    localModel: LocalModelDescriptor?,
    onDevice: OnDeviceDiagnostics,
    diagnosticEvents: () -> String,
    onClearDiagnosticEvents: () -> Unit,
) {
    val context = LocalContext.current

    Section(title = "This app") {
        FeaturedCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_vocaphone_logo),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                )
                Text(
                    ABOUT_WORDMARK,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (appInfo.versionName.isNotEmpty()) {
                    Text(
                        "Version ${appInfo.versionName} (${appInfo.versionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    ABOUT_STATUS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    ABOUT_TAGLINE,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    ABOUT_ON_DEVICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { context.openHttpUrl(WEBSITE_URL) }) {
                    Text("vocaphone.vocahq.com")
                }
            }
        }
        Text(
            ABOUT_PRIVACY_NOTE,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Section(
        title = "Part of VocaHQ",
        supporting = ABOUT_FAMILY_NOTE,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ABOUT_FAMILY_LINKS.forEach { link ->
                AboutLinkButton(link, context)
            }
        }
    }

    Section(
        title = "Talk to us",
        supporting = ABOUT_FEEDBACK_NOTE,
    ) {
        GitHubButton(
            label = ABOUT_REPORT_BUG,
            onClick = { context.openHttpUrl(NEW_ISSUE_URL) },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ABOUT_CONTACT_LINKS.forEach { link ->
                AboutLinkButton(link, context)
            }
        }
    }

    Section("Device") {
        InfoRow("Android", "${appInfo.androidRelease} (SDK ${appInfo.sdkInt})")
        InfoRow("Device", appInfo.device)
        InfoRow("Installed from", appInfo.installedFrom)
        InfoRow("Package", appInfo.packageName)
        InfoRow(
            "Speech",
            speechSourceCopy(
                localEnabled = settings.localTranscriptionEnabled,
                localModelName = localModel?.displayName,
                gatewayConfigured = settings.isConfigured,
                gatewayUrl = settings.gatewayUrl,
                lastEngine = settings.lastEngine,
                lastEngineReady = settings.lastEngineReady,
            ).engineLabel,
        )
        InfoRow(
            "RAM",
            "${formatBytes(onDevice.totalRamBytes)} total, " +
                "${formatBytes(onDevice.availRamBytes)} free",
        )
        InfoRow(
            "Storage",
            "${formatBytes(onDevice.availStorageBytes)} free of " +
                formatBytes(onDevice.totalStorageBytes),
        )
        InfoRow(
            "Models",
            if (onDevice.downloadedModelIds.isEmpty()) {
                "None downloaded"
            } else {
                "${formatBytes(onDevice.modelStorageBytes)} · " +
                    "${onDevice.downloadedModelIds.size} downloaded"
            },
        )
        InfoRow(
            "CPU",
            buildString {
                append("${onDevice.cpuCores} cores")
                if (onDevice.abi.isNotEmpty()) append(" · ${onDevice.abi}")
                if (onDevice.soc.isNotEmpty()) append(" · ${onDevice.soc}")
            },
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
            ABOUT_DIAGNOSTICS_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton(
                text = ABOUT_COPY_DIAGNOSTICS,
                onClick = {
                    context.copyDiagnostics(
                        diagnosticsReport(
                            appInfo,
                            settings,
                            setup,
                            diagnosticEvents(),
                            onDevice,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = ABOUT_CLEAR_EVENT_LOG,
                onClick = onClearDiagnosticEvents,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Labeled text button. Official VocaDesign marks render at 16 dp. */
@Composable
private fun AboutLinkButton(link: AboutLink, context: Context) {
    // Dark Win-style text links use #0F6B57. currentColor follows this tint.
    val ink = Color(0xFF0F6B57)
    TextButton(
        onClick = { context.openHttpUrl(link.url) },
        colors = ButtonDefaults.textButtonColors(contentColor = ink),
    ) {
        val icon = link.icon
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = ink,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(link.label)
    }
}

/**
 * Filled Report a bug. Dark Win-style: cream on charcoal. Light: cream on #0F6B57.
 */
@Composable
private fun GitHubButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF242424) else Color(0xFF0F6B57)
    val content = Color(0xFFF2F6F2)
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_social_github),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = content,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private fun Context.copyDiagnostics(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("VocaPhone diagnostics", text))
}
