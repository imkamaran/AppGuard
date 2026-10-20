package io.github.imkamaran.appguard.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imkamaran.appguard.ModuleStatus
import io.github.imkamaran.appguard.audit.AuditFilter
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditEvent
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.PolicySummary
import io.github.imkamaran.appguard.policy.ReportMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val REPORT_DAYS = 7

@Composable
fun HomeScreen(modifier: Modifier, onOpenApp: (String) -> Unit, onOpenTab: (Screen) -> Unit) {
    val app = appGuard()
    val config by app.policies.state.collectAsStateWithLifecycle()
    val publish by app.policies.publishStatus.collectAsStateWithLifecycle()
    val auditVersion by app.audit.version.collectAsStateWithLifecycle()
    val tz = remember { TimeZone.getDefault() }
    val recent by produceState(emptyList<AuditEvent>(), auditVersion) {
        value = withContext(Dispatchers.IO) {
            app.audit.query(
                AuditFilter(
                    categories = Category.entries.toSet() - Category.SYSTEM,
                    actions = setOf(AuditAction.RESTRICTED, AuditAction.SIMULATED),
                    limit = 6,
                ),
            )
        }
    }
    val report by produceState(IntArray(REPORT_DAYS), auditVersion) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val counts = IntArray(REPORT_DAYS)
            for ((ts, _) in app.audit.recentEventTimestamps(ReportMath.windowStart(now, REPORT_DAYS, tz))) {
                val i = ReportMath.dayIndex(ts, now, REPORT_DAYS, tz)
                if (i in 0 until REPORT_DAYS) counts[i]++
            }
            counts
        }
    }
    val coverage = remember(config) {
        val counts = PolicySummary.PRIVACY_CATEGORIES.associateWith { 0 }.toMutableMap()
        var protectedApps = 0
        for ((pkg, p) in config.policies) {
            val active = PolicySummary.activeCategories(pkg, p, config.global)
            if (active.isNotEmpty()) protectedApps++
            for (c in active) counts[c] = counts.getValue(c) + 1
        }
        protectedApps to counts
    }
    val (protectedApps, perCategory) = coverage

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            HeroCard(
                moduleActive = ModuleStatus.isActive,
                enforcing = config.global.masterEnabled,
                protectedApps = protectedApps,
                weeklyEvents = report.sum(),
                onToggle = { on -> app.policies.updateGlobal { it.copy(masterEnabled = on) } },
            )
        }
        if (publish.error != null || (!publish.remoteAvailable && ModuleStatus.isActive)) item {
            SectionCard("Configuration not shared", icon = Icons.Filled.Warning) {
                StatusLine(publish.error ?: "Scoped apps cannot read the configuration yet.", Tone.BAD)
            }
        }
        item {
            SectionCard("What's protected", subtitle = "Apps whose policy changes each kind of data.") {
                CoverageGrid(perCategory, onClick = { onOpenTab(Screen.Apps) })
            }
        }
        item { PrivacyReportCard(report, tz) }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently protected", style =MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpenTab(Screen.Audit) }) { Text("See all") }
            }
        }
        if (recent.isEmpty()) item {
            EmptyState(
                AppIcons.Shield,
                "Nothing blocked yet",
                "Accesses that a policy restricts or replaces appear here. The audit log also lists calls that were only observed.",
            )
        }
        items(recent) { e -> RecentEventRow(e, onClick = { onOpenApp(e.packageName) }) }
        item {
            SectionCard("Help") {
                Expandable("Troubleshooting") {
                    Bullet("A policy applies only if the app is also ticked in AppGuard's scope in the LSPosed/Vector manager.")
                    Bullet("Most hooks are installed when the app process starts: force-stop the app after enabling its policy.")
                    Bullet("Each policy page shows whether hooks initialized in that app and when they last reported.")
                    Bullet("If an app misbehaves, switch its policy off (or use the kill switch); the app keeps its normal behavior on next start.")
                }
                Text(
                    "Hook API ${ModuleStatus.hookApiVersion.takeIf { ModuleStatus.isActive } ?: "-"} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                        (publish.lastPublishedAt.takeIf { it > 0 }?.let { " · config shared ${formatTime(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(moduleActive: Boolean, enforcing: Boolean, protectedApps: Int, weeklyEvents: Int, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    data class Look(val container: Color, val content: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val body: String)
    val look = when {
        !moduleActive -> Look(
            cs.errorContainer, cs.onErrorContainer, Icons.Filled.Warning, "Module not active",
            "Enable AppGuard in the LSPosed/Vector manager, then force-stop and reopen AppGuard.",
        )
        !enforcing -> Look(cs.tertiaryContainer, cs.onTertiaryContainer, AppIcons.Pause, "Protection paused", "All policies are off until you resume.")
        else -> Look(
            cs.primaryContainer, cs.onPrimaryContainer, AppIcons.VerifiedUser, "Protection active",
            if (protectedApps == 0) "No app has an active policy yet. Open Apps to choose what each app can see."
            else "Your policies are being enforced.",
        )
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = look.container, contentColor = look.content),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(look.icon, look.content.copy(alpha = 0.12f), look.content, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(look.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(look.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (moduleActive) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroStat("$protectedApps", "protected apps", look.content, Modifier.weight(1f))
                    HeroStat("$weeklyEvents", "events this week", look.content, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = look.content.copy(alpha = 0.15f))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enforce policies", style = MaterialTheme.typography.titleSmall)
                        Text("Kill switch: off pauses every policy within seconds.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enforcing, onCheckedChange = onToggle)
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CoverageGrid(counts: Map<Category, Int>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in PolicySummary.PRIVACY_CATEGORIES.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in row) {
                    val n = counts[c] ?: 0
                    val on = n > 0
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) cs.secondaryContainer else cs.surfaceContainerHighest)
                            .clickable(onClick = onClick)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            AppIcons.forCategory(c), contentDescription = null,
                            tint = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Column {
                            Text(
                                if (c == Category.CONTACTS) "Contacts" else c.label,
                                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (n) { 0 -> "Not limited"; 1 -> "1 app"; else -> "$n apps" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PrivacyReportCard(report: IntArray, tz: TimeZone) {
    if (report.sum() == 0) return
    val maxCount = report.max().coerceAtLeast(1)
    val dayLabels = remember(report) {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = ReportMath.windowStart(System.currentTimeMillis(), REPORT_DAYS, tz) }
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        (0 until REPORT_DAYS).map { fmt.format(Date(cal.timeInMillis)).also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
    }
    val cs = MaterialTheme.colorScheme
    SectionCard("Privacy report", subtitle = "${report.sum()} events in the last $REPORT_DAYS days") {
        Row(Modifier.fillMaxWidth().height(128.dp), verticalAlignment = Alignment.Bottom) {
            for (i in 0 until REPORT_DAYS) {
                val today = i == REPORT_DAYS - 1
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (report[i] > 0) report[i].toString() else "", style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .width(18.dp)
                            .height((report[i].toFloat() / maxCount * 80).dp.coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (report[i] == 0) cs.surfaceVariant else if (today) cs.primary else cs.primary.copy(alpha = 0.55f)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        dayLabels[i],
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                        color = if (today) cs.onSurface else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentEventRow(e: AuditEvent, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (e.action) {
        AuditAction.RESTRICTED -> cs.primaryContainer to cs.onPrimaryContainer
        AuditAction.SIMULATED -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    ListItem(
        overlineContent = { Text("${e.category.label} · ${e.action.label()}") },
        headlineContent = { Text(e.api, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${e.packageName} · ${formatTime(e.timestamp)}" + if (e.count > 1) " · ×${e.count}" else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { IconBadge(AppIcons.forCategory(e.category), container, content) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}

fun AuditAction.label(): String = when (this) {
    AuditAction.OBSERVED -> "Observed"
    AuditAction.RESTRICTED -> "Restricted"
    AuditAction.SIMULATED -> "Simulated"
    AuditAction.HOOK_INSTALLED -> "Hook installed"
    AuditAction.HOOK_FAILED -> "Hook failed"
    AuditAction.HOOK_ERROR -> "Hook error"
}
