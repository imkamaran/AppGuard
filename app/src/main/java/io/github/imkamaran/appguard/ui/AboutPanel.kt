package io.github.imkamaran.appguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.imkamaran.appguard.BuildConfig
import io.github.imkamaran.appguard.policy.PolicySchema

private const val GITHUB_HANDLE = "imkamaran"
private const val GITHUB_URL = "https://github.com/$GITHUB_HANDLE"

@Composable
fun AboutPanel() {
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(BuildConfig.APPLICATION_ID, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("AppGuard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} · policy schema v${PolicySchema.SCHEMA_VERSION}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Per-app privacy controls for rooted Android. Choose, app by app, what each one can see of your location, " +
                    "network, SIM, identifiers, contacts, clipboard and sensors.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            AboutLink(
                icon = AppIcons.Code,
                label = "Developer",
                value = "@$GITHUB_HANDLE on GitHub",
                onClick = { runCatching { uri.openUri(GITHUB_URL) } },
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Shield, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Everything stays on this device: no network access, accounts or telemetry. Policies only take effect in apps " +
                        "you both configure here and add to the module scope.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutLink(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHighest)
            .clickable(onClickLabel = "Open $value", onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, cs.primaryContainer, cs.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(AppIcons.OpenInNew, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
