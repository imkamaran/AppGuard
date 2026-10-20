package io.github.imkamaran.appguard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imkamaran.appguard.BuildConfig
import io.github.imkamaran.appguard.ModuleStatus
import io.github.imkamaran.appguard.policy.AuditLevel
import io.github.imkamaran.appguard.policy.BackupCodec
import io.github.imkamaran.appguard.policy.CategoryDefaults
import io.github.imkamaran.appguard.policy.ConfigState
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.GlobalSettings
import io.github.imkamaran.appguard.policy.Implementation
import io.github.imkamaran.appguard.policy.ImportResult
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.PolicySchema
import io.github.imkamaran.appguard.policy.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier) {
    val app = appGuard()
    val context = LocalContext.current
    val config by app.policies.state.collectAsStateWithLifecycle()
    val publish by app.policies.publishStatus.collectAsStateWithLifecycle()
    val g = config.global
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDisableAll by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<ConfigState?>(null) }
    var restoreNotes by remember { mutableStateOf(emptyList<String>()) }

    val stamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = BackupCodec.export(config, System.currentTimeMillis(), BuildConfig.VERSION_NAME)
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
            snackbar.showSnackbar(result.fold({ "Backup exported." }, { "Export failed: ${it.message}" }))
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.policies.updateGlobal { it.copy(notifyRestricted = true) }
        else scope.launch { snackbar.showSnackbar("Notification permission denied. Allow it in system settings to get access notifications.") }
    }
    val restoreLauncher =rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            }
            raw.fold(
                onSuccess = { text ->
                    when (val result = BackupCodec.import(text)) {
                        is ImportResult.Ok -> { confirmRestore = result.config; restoreNotes = result.notes }
                        is ImportResult.Rejected -> snackbar.showSnackbar(result.reason)
                    }
                },
                onFailure = { snackbar.showSnackbar("Could not read the file: ${it.message}") },
            )
        }
    }

    Scaffold(modifier, snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                SectionCard("Appearance", icon = Icons.Filled.Create) {
                    val themes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                    SingleChoiceSegmentedButtonRow {
                        themes.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = config.ui.theme == mode,
                                onClick = { app.policies.updateUi { it.copy(theme = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                            ) { Text(label) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Dynamic color")
                                Text("Use colors from your wallpaper.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = config.ui.dynamicColor, onCheckedChange = { on -> app.policies.updateUi { it.copy(dynamicColor = on) } })
                        }
                    }
                }
            }
            item {
                SectionCard("Default policy behavior", icon = AppIcons.Shield, subtitle = "Used by categories set to \"Default\" in an app's policy. Applies only to apps with an enabled policy; other apps are never modified.") {
                    DefaultRow("Location", g.defaults.location, true) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(location = m)) } }
                    DefaultRow("Clipboard reads", g.defaults.clipboard, true) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(clipboard = m)) } }
                    DefaultRow("Sensors (Restrict hides each app's selected sensor groups)", g.defaults.sensors, true) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(sensors = m)) } }
                    DefaultRow("Wi-Fi network identity", g.defaults.network, true) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(network = m)) } }
                    DefaultRow("Contacts, call log & calendar", g.defaults.contacts, true) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(contacts = m)) } }
                    DefaultRow("Phone & SIM info", g.defaults.telephony, true) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(telephony = m)) } }
                    DefaultRow("Device identifiers (Allow only)", g.defaults.identifiers, false) { m -> app.policies.updateGlobal { it.copy(defaults = it.defaults.copy(identifiers = m)) } }
                    TextButton(onClick = { app.policies.updateGlobal { it.copy(defaults = CategoryDefaults()) } }) { Text("Reset defaults to Allow") }
                }
            }
            item {
                SectionCard("Logging", icon = Icons.Filled.Notifications, subtitle = "Hook-status reports are always recorded so the app can show whether enforcement is active.") {
                    val levels = listOf(AuditLevel.OFF to "Off", AuditLevel.ACTIONS to "Modifications", AuditLevel.VERBOSE to "Everything")
                    SingleChoiceSegmentedButtonRow {
                        levels.forEachIndexed { i, (level, label) ->
                            SegmentedButton(
                                selected = g.auditLevel == level,
                                onClick = { app.policies.updateGlobal { it.copy(auditLevel = level) } },
                                shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                            ) { Text(label) }
                        }
                    }
                    Text(
                        when (g.auditLevel) {
                            AuditLevel.OFF -> "No privacy events are recorded."
                            AuditLevel.ACTIONS -> "Records calls where a policy restricted or simulated a result."
                            AuditLevel.VERBOSE -> "Also records calls that were only observed and passed through."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Redact sensitive values")
                            Text("Hides synthetic identifiers and coordinates in event details. Clipboard contents and real identifiers are never logged either way.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = g.redactSensitive, onCheckedChange = { on -> app.policies.updateGlobal { it.copy(redactSensitive = on) } })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Access notifications")
                            Text("Post a notification when a restricted access is blocked.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = g.notifyRestricted, onCheckedChange = { on ->
                            val needsGrant = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else app.policies.updateGlobal { it.copy(notifyRestricted = on) }
                        })
                    }
                }
            }
            item {
                SectionCard("Retention", icon = Icons.Filled.DateRange) {
                    var days by remember(g.retentionDays) { mutableFloatStateOf(g.retentionDays.toFloat()) }
                    Text("Keep events for ${days.roundToInt()} days")
                    Slider(
                        value = days,
                        onValueChange = { days = it },
                        valueRange = 1f..90f,
                        onValueChangeFinished = { app.policies.updateGlobal { it.copy(retentionDays = days.roundToInt()) } },
                    )
                    var max by remember(g.maxEvents) { mutableFloatStateOf(g.maxEvents.toFloat()) }
                    Text("Keep at most ${(max.roundToInt() / 100) * 100} events")
                    Slider(
                        value = max,
                        onValueChange = { max = it },
                        valueRange = GlobalSettings.MIN_MAX_EVENTS.toFloat()..50_000f,
                        onValueChangeFinished = { app.policies.updateGlobal { it.copy(maxEvents = (max.roundToInt() / 100) * 100) } },
                    )
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { app.audit.enforceRetention(app.policies.state.value.global) } }) { Text("Apply retention now") }
                }
            }
            item {
                SectionCard("Backup & restore", icon = Icons.Filled.Share, subtitle = "Export or import the complete configuration: policies, templates and settings.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { backupLauncher.launch("appguard-backup-$stamp.json") }) { Text("Export") }
                        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Import") }
                    }
                }
            }
            item {
                SectionCard("Recovery & housekeeping", icon = Icons.Filled.Build) {
                    Bullet("Turning a policy off makes the app's hooks pass everything through within a few seconds; restart the app for a clean state.")
                    Bullet("If AppGuard itself cannot be opened, disable the module in the LSPosed/Vector manager: apps return to normal on their next start.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmDisableAll = true }) { Text("Disable all policies") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val installed = withContext(Dispatchers.IO) { app.apps.load().map { it.packageName }.toSet() }
                                val n = app.policies.cleanUninstalledPolicies(installed)
                                snackbar.showSnackbar(if (n > 0) "Removed $n policies for uninstalled apps." else "All policies belong to installed apps.")
                            }
                        }) { Text("Clean uninstalled") }
                    }
                }
            }
            item {
                SectionCard("Compatibility diagnostics", icon = Icons.Filled.Info) {
                    KeyValue("Hook runtime", if (ModuleStatus.isActive) "active, Xposed API ${ModuleStatus.hookApiVersion}" else "not active")
                    KeyValue("Shared configuration", if (publish.remoteAvailable) "available" else "unavailable")
                    publish.error?.let { KeyValue("Problem", it) }
                    KeyValue("Policy schema", "v${PolicySchema.SCHEMA_VERSION}")
                    KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    KeyValue("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    KeyValue("ABI", Build.SUPPORTED_ABIS.joinToString())
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    for (f in Feature.entries) {
                        val (text, tone) = when (f.implementation) {
                            Implementation.VERIFIED -> "verified" to Tone.GOOD
                            Implementation.UNVERIFIED -> "unverified" to Tone.WARN
                            Implementation.NOT_IMPLEMENTED -> "not implemented" to Tone.NEUTRAL
                        }
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            StatusPill(text, tone)
                        }
                    }
                }
            }
            item { AboutPanel() }
        }
    }

    if (confirmDisableAll) {
        AlertDialog(
            onDismissRequest = { confirmDisableAll = false },
            title = { Text("Disable all policies?") },
            text = { Text("Every app policy is switched off. Settings are kept so you can re-enable them individually.") },
            confirmButton = { TextButton(onClick = { confirmDisableAll = false; app.policies.disableAllPolicies() }) { Text("Disable all") } },
            dismissButton = { TextButton(onClick = { confirmDisableAll = false }) { Text("Cancel") } },
        )
    }

    confirmRestore?.let { restoredConfig ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore backup?") },
            text = {
                Column {
                    Text("This replaces all policies, templates and settings.")
                    if (restoreNotes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (n in restoreNotes) Bullet(n)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.policies.importConfig(restoredConfig)
                    confirmRestore = null
                    scope.launch { snackbar.showSnackbar("Backup restored.") }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultRow(label: String, value: Mode, enabled: Boolean, onChange: (Mode) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        val choices = GlobalSettings.DEFAULT_CHOICES
        SingleChoiceSegmentedButtonRow {
            choices.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = value == m,
                    onClick = { onChange(m) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                ) { Text(if (m == Mode.ALLOW) "Allow" else "Restrict") }
            }
        }
    }
}
