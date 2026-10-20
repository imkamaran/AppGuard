package io.github.imkamaran.appguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imkamaran.appguard.apps.InstalledApp
import androidx.compose.ui.text.style.TextOverflow
import io.github.imkamaran.appguard.policy.PolicySummary
import io.github.imkamaran.appguard.policy.RestartAdvisor
import io.github.imkamaran.appguard.policy.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) { ALL("All"), POLICY("With policy"), USER("User apps"), SYSTEM("System apps") }

@Composable
fun AppListScreen(modifier: Modifier, onOpenApp: (String) -> Unit) {
    val app = appGuard()
    val config by app.policies.state.collectAsStateWithLifecycle()
    val auditVersion by app.audit.version.collectAsStateWithLifecycle()
    val apps by produceState<List<InstalledApp>?>(null) { value = app.apps.load() }
    val hookTimes by produceState(emptyMap<String, Long>(), auditVersion) {
        value = withContext(Dispatchers.IO) { app.audit.allLastHookReportTimes() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var showTemplatePicker by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            placeholder = { Text("Search by name or package") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            },
            singleLine = true,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selecting,
                onClick = { selecting = !selecting; if (!selecting) selected = emptySet() },
                label = { Text("Select") },
            )
            for (f in AppFilter.entries) FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val q = query.trim().lowercase()
        val shown = list.filter { a ->
            val p = config.policies[a.packageName]
            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.POLICY -> p != null
                AppFilter.USER -> !a.isSystem
                AppFilter.SYSTEM -> a.isSystem
            }
            matchesFilter && (q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.lowercase().contains(q))
        }

        if (selecting) {
            val shownPkgs = shown.map { it.packageName }.toSet()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    selected = if (selected.containsAll(shownPkgs)) emptySet() else shownPkgs
                }) { Text(if (selected.containsAll(shownPkgs)) "Deselect all" else "Select all") }
                Spacer(Modifier.weight(1f))
                Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { app.policies.bulkSetEnabled(selected.toList(), true); selecting = false; selected = emptySet() },
                    enabled = selected.isNotEmpty(),
                ) { Text("Enable") }
                OutlinedButton(
                    onClick = { app.policies.bulkSetEnabled(selected.toList(), false); selecting = false; selected = emptySet() },
                    enabled = selected.isNotEmpty(),
                ) { Text("Disable") }
                OutlinedButton(
                    onClick = { showTemplatePicker = true },
                    enabled = selected.isNotEmpty(),
                ) { Text("Template…") }
            }
        }

        if (shown.isEmpty()) {
            EmptyState(
                Icons.Filled.Search,
                "No matching apps",
                if (q.isNotEmpty()) "Nothing matches \"${query.trim()}\" in ${filter.label.lowercase()}." else "No ${filter.label.lowercase()} to show.",
            )
            return
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.packageName }) { a ->
                val p = config.policies[a.packageName]
                val needsRestart = p?.let { RestartAdvisor.needsRestart(it, hookTimes[a.packageName]) } == true
                val isSelected = a.packageName in selected
                val active = PolicySummary.activeCategories(a.packageName, p, config.global)
                ListItem(
                    headlineContent = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(a.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CategoryBadges(active, Modifier.padding(top = 4.dp))
                        }
                    },
                    leadingContent = {
                        if (selecting) Checkbox(checked = isSelected, onCheckedChange = null)
                        else AppIcon(a.packageName)
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (needsRestart) StatusPill("Restart", Tone.WARN)
                            when {
                                a.protectedReason != null -> StatusPill("Protected", Tone.NEUTRAL)
                                p == null || active.isNotEmpty() -> Unit
                                p.enabled && config.global.masterEnabled -> StatusPill("Audit only", Tone.NEUTRAL)
                                p.enabled -> StatusPill("Paused", Tone.WARN)
                                else -> StatusPill("Policy off", Tone.NEUTRAL)
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        if (selecting) selected = if (isSelected) selected - a.packageName else selected + a.packageName
                        else onOpenApp(a.packageName)
                    },
                )
            }
        }
    }

    if (showTemplatePicker) {
        val templates = Templates.all(config.templates)
        AlertDialog(
            onDismissRequest = { showTemplatePicker = false },
            title = { Text("Apply template to ${selected.size} apps") },
            text = {
                Column {
                    for (t in templates) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                app.policies.bulkApplyTemplate(selected.toList(), t)
                                showTemplatePicker = false; selecting = false; selected = emptySet()
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (templates.isEmpty()) Text("No templates yet. Save one from a policy editor.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showTemplatePicker = false }) { Text("Cancel") } },
        )
    }
}
