package io.github.imkamaran.appguard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.imkamaran.appguard.AppGuardApp
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.Mode
import java.text.DateFormat
import java.util.Date

@Composable
fun appGuard(): AppGuardApp = LocalContext.current.applicationContext as AppGuardApp

@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val app = appGuard()
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, packageName) { value = app.apps.icon(packageName, px) }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    IconBadge(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, 36.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** An icon centered in a tinted circle. */
@Composable
fun IconBadge(icon: ImageVector, container: Color, content: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}

/** Small icons for the categories an app's policy changes. */
@Composable
fun CategoryBadges(categories: List<Category>, modifier: Modifier = Modifier) {
    if (categories.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in categories) {
            Icon(
                AppIcons.forCategory(c),
                contentDescription = c.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

enum class Tone { GOOD, WARN, BAD, NEUTRAL }

@Composable
fun StatusPill(text: String, tone: Tone) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.GOOD -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.WARN -> cs.tertiaryContainer to cs.onTertiaryContainer
        Tone.BAD -> cs.errorContainer to cs.onErrorContainer
        Tone.NEUTRAL -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun StatusLine(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (icon: ImageVector, tint: Color) = when (tone) {
        Tone.GOOD -> Icons.Filled.CheckCircle to cs.primary
        Tone.WARN -> Icons.Filled.Warning to cs.tertiary
        Tone.BAD -> Icons.Filled.Warning to cs.error
        Tone.NEUTRAL -> Icons.Filled.Info to cs.onSurfaceVariant
    }
    Row(modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

data class ModeOption(val mode: Mode, val title: String, val description: String)

/** Radio list where every option states exactly what it changes. */
@Composable
fun ModeSelector(options: List<ModeOption>, selected: Mode, enabled: Boolean, onSelect: (Mode) -> Unit) {
    Column {
        for (o in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .selectable(selected = o.mode == selected, enabled = enabled, role = Role.RadioButton) { onSelect(o.mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = o.mode == selected, onClick = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
                Column(Modifier.padding(top = 2.dp)) {
                    val alpha = if (enabled) 1f else 0.5f
                    Text(o.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                }
            }
        }
    }
}

@Composable
fun Expandable(label: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) {
            Text(label)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        if (open) Column(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
fun Bullet(text: String) {
    Row {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun ClickableRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) { content() }
}

fun formatTime(ts: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ts))
