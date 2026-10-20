package io.github.imkamaran.appguard.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imkamaran.appguard.audit.AuditFilter
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditEvent
import io.github.imkamaran.appguard.policy.AuditExport
import io.github.imkamaran.appguard.policy.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Range(val label: String, val millis: Long) {
    HOUR("1 h", 3_600_000L), DAY("24 h", 86_400_000L), WEEK("7 days", 7 * 86_400_000L), ALL("All", 0L)
}

private enum class ActionFilter(val label: String, val actions: Set<AuditAction>) {
    ANY("Any action", emptySet()),
    MODIFIED("Modified", setOf(AuditAction.RESTRICTED, AuditAction.SIMULATED)),
    OBSERVED("Observed", setOf(AuditAction.OBSERVED)),
    HOOKS("Hook status", setOf(AuditAction.HOOK_INSTALLED, AuditAction.HOOK_FAILED, AuditAction.HOOK_ERROR)),
}

@Composable
fun AuditScreen(modifier: Modifier) {
    val app = appGuard()
    val context = LocalContext.current
    val version by app.audit.version.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptySet<Category>()) }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var action by rememberSaveable { mutableStateOf(ActionFilter.ANY) }
    var pkg by rememberSaveable { mutableStateOf<String?>(null) }
    var pkgMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AuditEvent?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun filter(limit: Int) = AuditFilter(
        query = query,
        categories = categories,
        packageName = pkg,
        actions = action.actions,
        since = if (range.millis > 0) System.currentTimeMillis() - range.millis else 0L,
        limit = limit,
    )

    val events by produceState(emptyList<AuditEvent>(), version, query, categories, range, action, pkg) {
        value = withContext(Dispatchers.IO) { app.audit.query(filter(500)) }
    }
    val packages by produceState(emptyList<String>(), version) { value = withContext(Dispatchers.IO) { app.audit.eventPackages() } }

    fun export(uri: Uri?, csv: Boolean) {
        if (uri == null) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = app.audit.query(filter(100_000))
                    val text = if (csv) AuditExport.toCsv(all) else AuditExport.toJson(all)
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    all.size
                }
            }
            snackbar.showSnackbar(result.fold({ "Exported $it events." }, { "Export failed: ${it.message}" }))
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, csv = false) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, csv = true) }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    // The root Scaffold already applies system-bar insets.
    Scaffold(modifier, snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search package, API, detail") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } },
                    singleLine = true,
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export JSON") }, onClick = { menu = false; jsonLauncher.launch("appguard-audit-$stamp.json") })
                        DropdownMenuItem(text = { Text("Export CSV") }, onClick = { menu = false; csvLauncher.launch("appguard-audit-$stamp.csv") })
                        DropdownMenuItem(
                            text = { Text("Delete events…") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; confirmClear = true },
                        )
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in Category.entries) {
                    FilterChip(
                        selected = c in categories,
                        onClick = { categories = if (c in categories) categories - c else categories + c },
                        label = { Text(c.label) },
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in Range.entries) FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
                for (a in ActionFilter.entries) FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.label) })
                Box {
                    FilterChip(selected = pkg != null, onClick = { pkgMenu = true }, label = { Text(pkg ?: "All apps", maxLines = 1) })
                    DropdownMenu(expanded = pkgMenu, onDismissRequest = { pkgMenu = false }) {
                        DropdownMenuItem(text = { Text("All apps") }, onClick = { pkg = null; pkgMenu = false })
                        for (p in packages) DropdownMenuItem(text = { Text(p) }, onClick = { pkg = p; pkgMenu = false })
                    }
                }
            }
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No matching events.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(events) { e ->
                        ListItem(
                            overlineContent = { Text(e.action.label()) },
                            headlineContent = { Text(e.api, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    "${formatTime(e.timestamp)} · ${e.packageName}" + (if (e.count > 1) " · ×${e.count}" else ""),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { AppIcon(e.packageName, 32.dp) },
                            trailingContent = { OutcomePill(e) },
                            modifier = Modifier.clickable { selected = e },
                        )
                    }
                }
            }
        }
    }

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.action.label(), fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    KeyValue("Time", formatTime(e.timestamp))
                    KeyValue("App", e.packageName)
                    KeyValue("Process id", e.pid.toString())
                    KeyValue("Category", e.category.label)
                    KeyValue("API / event", e.api)
                    KeyValue("Policy", e.policy.name)
                    KeyValue("Action", e.action.name)
                    KeyValue("Outcome", e.outcome.name)
                    KeyValue("Occurrences", e.count.toString())
                    if (e.detail.isNotEmpty()) KeyValue("Detail", e.detail)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(if (pkg == null) "Delete all events?" else "Delete events for $pkg?") },
            text = { Text("Deleted events cannot be recovered. Hook status for the affected apps is reset too.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    val target = pkg
                    scope.launch { withContext(Dispatchers.IO) { app.audit.clear(target) } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OutcomePill(e: AuditEvent) {
    val tone = when (e.outcome) {
        io.github.imkamaran.appguard.policy.Outcome.SUCCEEDED -> if (e.action == AuditAction.OBSERVED) Tone.NEUTRAL else Tone.GOOD
        io.github.imkamaran.appguard.policy.Outcome.FAILED -> Tone.BAD
        io.github.imkamaran.appguard.policy.Outcome.UNDETERMINED -> Tone.WARN
    }
    StatusPill(e.outcome.name.lowercase(), tone)
}
