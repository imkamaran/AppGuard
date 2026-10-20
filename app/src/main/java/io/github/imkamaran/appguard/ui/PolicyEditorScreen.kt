package io.github.imkamaran.appguard.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imkamaran.appguard.ModuleStatus
import io.github.imkamaran.appguard.apps.InstalledApp
import io.github.imkamaran.appguard.apps.RootShell
import io.github.imkamaran.appguard.audit.HookStatus
import io.github.imkamaran.appguard.policy.AppPolicy
import io.github.imkamaran.appguard.policy.DeviceProfiles
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Implementation
import io.github.imkamaran.appguard.policy.LocationPolicy
import io.github.imkamaran.appguard.policy.LocationSpec
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.PolicyResolver
import io.github.imkamaran.appguard.policy.SensorGroups
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import io.github.imkamaran.appguard.policy.CarrierProfiles
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.ContactSources
import io.github.imkamaran.appguard.policy.PolicySummary
import io.github.imkamaran.appguard.policy.TelephonyPolicy
import io.github.imkamaran.appguard.policy.PolicyTemplate
import io.github.imkamaran.appguard.policy.RestartAdvisor
import io.github.imkamaran.appguard.policy.SyntheticIds
import io.github.imkamaran.appguard.policy.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PolicyEditorScreen(packageName: String, onBack: () -> Unit) {
    val app = appGuard()
    val config by app.policies.state.collectAsStateWithLifecycle()
    val auditVersion by app.audit.version.collectAsStateWithLifecycle()
    val info by produceState<InstalledApp?>(null, packageName) { value = app.apps.find(packageName) }
    val hooks by produceState(emptyList<HookStatus>(), packageName, auditVersion) {
        value = withContext(Dispatchers.IO) { app.audit.hookStatus(packageName) }
    }
    val stored = config.policies[packageName]
    val policy = stored ?: AppPolicy(packageName)
    val resolution = PolicyResolver.resolve(packageName, stored, config.global)
    val protectedReason = info?.protectedReason
    val editable = protectedReason == null
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showApplyTemplate by remember { mutableStateOf(false) }
    var showSaveTemplate by remember { mutableStateOf(false) }
    val lastHookReport by produceState<Long?>(null, packageName, auditVersion) {
        value = withContext(Dispatchers.IO) { app.audit.lastHookReportTime(packageName) }
    }
    // No report at all is already explained by the runtime status card.
    val needsRestart = lastHookReport != null && stored?.let { RestartAdvisor.needsRestart(it, lastHookReport) } == true

    fun update(transform: (AppPolicy) -> AppPolicy) {
        if (editable) app.policies.updatePolicy(packageName, transform)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.label ?: packageName, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (stored != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete policy") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (resolution != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(packageName, 56.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(info?.label ?: packageName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (protectedReason != null) {
                            StatusLine("AppGuard never hooks this package: $protectedReason", Tone.NEUTRAL)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Apply policy", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (policy.enabled) "Settings below apply to this app." else "Off: the app keeps its normal behavior; settings below are kept.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(checked = policy.enabled, onCheckedChange = { on -> update { it.copy(enabled = on) } })
                            }
                            val active = resolution?.let { PolicySummary.activeCategories(it.effective) }.orEmpty()
                            if (active.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text("Protected now", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (c in active) CategoryChip(c)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showApplyTemplate = true }) { Text("Apply template") }
                                OutlinedButton(onClick = { showSaveTemplate = true }) { Text("Save as template") }
                            }
                        }
                    }
                }
            }
            if (needsRestart) item {
                SectionCard("Restart needed", icon = Icons.Filled.Refresh) {
                    StatusLine("Policy changed since last app start. Force-stop the app for all changes to take effect.", Tone.WARN)
                }
            }
            if (editable) item {
                RuntimeCard(policy.enabled, hooks) {
                    scope.launch {
                        val r = RootShell.forceStop(packageName)
                        snackbar.showSnackbar(
                            if (r.isSuccess) "App stopped; its next start applies the policy."
                            else "Could not stop the app: ${r.exceptionOrNull()?.message}",
                        )
                    }
                }
            }
            if (resolution != null && resolution.issues.isNotEmpty()) item {
                SectionCard("Not applied") {
                    for (issue in resolution.issues) StatusLine(issue, Tone.WARN)
                }
            }
            item { LocationSection(policy.location, editable, hooks) { loc -> update { it.copy(location = loc) } } }
            item {
                SectionCard(Category.NETWORK.label, icon = AppIcons.forCategory(Category.NETWORK)) {
                    FeatureStatus(Feature.NETWORK, hooks)
                    ModeSelector(networkOptions, policy.network.mode, editable) { m -> update { it.copy(network = it.network.copy(mode = m)) } }
                    if (policy.network.mode == Mode.SIMULATE) {
                        val seed = policy.identifiers.seed
                        if (SyntheticIds.isValidSeed(seed)) {
                            KeyValue("Network name", SyntheticIds.wifiSsid(packageName, seed))
                            KeyValue("BSSID", SyntheticIds.wifiBssid(packageName, seed))
                            Text(
                                "Derived from this app's identity; rotating the identity under Device identifiers changes it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            StatusLine("No identity seed yet; save the policy once to create one.", Tone.WARN)
                        }
                    }
                }
            }
            item { TelephonySection(policy, editable, hooks) { t -> update { it.copy(telephony = t) } } }
            item {
                IdentifierSection(policy, editable, hooks, onChange = { ids -> update { it.copy(identifiers = ids) } }, onRotate = {
                    app.policies.rotateIdentity(packageName)
                })
            }
            item {
                SectionCard(Category.CONTACTS.label, icon = AppIcons.forCategory(Category.CONTACTS)) {
                    FeatureStatus(Feature.CONTACTS, hooks)
                    ModeSelector(contactsOptions, policy.contacts.mode, editable) { m -> update { it.copy(contacts = it.contacts.copy(mode = m)) } }
                    Spacer(Modifier.height(8.dp))
                    Text("Data hidden by Restrict", style = MaterialTheme.typography.titleSmall)
                    CheckGroup(ContactSources.ALL.map { Triple(it.id, it.label, it.description) }, policy.contacts.restrictedSources, editable) { id, on ->
                        update {
                            val s = it.contacts.restrictedSources
                            it.copy(contacts = it.contacts.copy(restrictedSources = if (on) s + id else s - id))
                        }
                    }
                }
            }
            item {
                SectionCard(Category.CLIPBOARD.label, icon = AppIcons.forCategory(Category.CLIPBOARD)) {
                    FeatureStatus(Feature.CLIPBOARD, hooks)
                    ModeSelector(clipboardOptions, policy.clipboard.mode, editable) { m -> update { it.copy(clipboard = it.clipboard.copy(mode = m)) } }
                }
            }
            item {
                SectionCard(Category.SENSORS.label, icon = AppIcons.forCategory(Category.SENSORS)) {
                    FeatureStatus(Feature.SENSORS, hooks)
                    ModeSelector(sensorOptions, policy.sensors.mode, editable) { m -> update { it.copy(sensors = it.sensors.copy(mode = m)) } }
                    Spacer(Modifier.height(8.dp))
                    Text("Sensor groups hidden by Restrict", style = MaterialTheme.typography.titleSmall)
                    CheckGroup(SensorGroups.ALL.map { Triple(it.id, it.label, it.description) }, policy.sensors.restrictedGroups, editable) { id, on ->
                        update {
                            val g = it.sensors.restrictedGroups
                            it.copy(sensors = it.sensors.copy(restrictedGroups = if (on) g + id else g - id))
                        }
                    }
                }
            }
            item {
                SectionCard("Crash-loop protection", icon = AppIcons.Shield) {
                    FeatureStatus(Feature.CRASH_GUARD, hooks)
                }
            }
            item {
                SectionCard("Audit logging", icon = Icons.Filled.Notifications, subtitle ="Records intercepted calls for this app in the local audit log. Values are redacted unless redaction is turned off in Settings; clipboard contents and real identifiers are never recorded.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Log events for this app", Modifier.weight(1f))
                        Switch(checked = policy.auditEnabled, enabled = editable, onCheckedChange = { on -> update { it.copy(auditEnabled = on) } })
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete policy?") },
            text = { Text("All settings for this app, including its synthetic identity, are removed. The app gets its original behavior.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.policies.removePolicy(packageName)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showApplyTemplate) {
        val templates = Templates.all(config.templates)
        AlertDialog(
            onDismissRequest = { showApplyTemplate = false },
            title = { Text("Apply template") },
            text = {
                Column {
                    if (templates.isEmpty()) Text("No templates available.", style = MaterialTheme.typography.bodySmall)
                    for (t in templates) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val applied = Templates.apply(t, policy)
                                update { applied.copy(packageName = packageName) }
                                showApplyTemplate = false
                                scope.launch { snackbar.showSnackbar("Applied \"${t.name}\"") }
                            }.padding(vertical = 10.dp),
                        ) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showApplyTemplate = false }) { Text("Cancel") } },
        )
    }

    if (showSaveTemplate) {
        var name by remember { mutableStateOf(info?.label ?: packageName) }
        AlertDialog(
            onDismissRequest = { showSaveTemplate = false },
            title = { Text("Save as template") },
            text = {
                Column {
                    Text("Save the current settings as a reusable template.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Template name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = Templates.fromPolicy(java.util.UUID.randomUUID().toString(), name.trim(), policy)
                        app.policies.addTemplate(t)
                        showSaveTemplate = false
                        scope.launch { snackbar.showSnackbar("Saved \"${t.name}\"") }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveTemplate = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryChip(c: Category) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.forCategory(c), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(c.label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Checkbox list of (id, label, description) entries; [selected] holds the checked ids. */
@Composable
private fun CheckGroup(items: List<Triple<String, String, String>>, selected: Set<String>, editable: Boolean, onToggle: (String, Boolean) -> Unit) {
    for ((id, label, description) in items) {
        val checked = id in selected
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).toggleable(value = checked, enabled = editable, role = Role.Checkbox) { onToggle(id, it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = editable)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelephonySection(policy: AppPolicy, editable: Boolean, hooks: List<HookStatus>, onChange: (TelephonyPolicy) -> Unit) {
    val t = policy.telephony
    SectionCard(Category.TELEPHONY.label, icon = AppIcons.forCategory(Category.TELEPHONY)) {
        FeatureStatus(Feature.TELEPHONY, hooks)
        ModeSelector(telephonyOptions, t.mode, editable) { m -> onChange(t.copy(mode = m)) }
        if (t.mode == Mode.SIMULATE) {
            var open by remember { mutableStateOf(false) }
            val selected = CarrierProfiles.byId(t.carrierProfile)
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (editable) open = it }) {
                OutlinedTextField(
                    value = selected?.label ?: "Unknown carrier",
                    onValueChange = {},
                    readOnly = true,
                    enabled = editable,
                    label = { Text("Carrier") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (c in CarrierProfiles.ALL) {
                        DropdownMenuItem(
                            text = { Text("${c.label}  ·  ${c.mccMnc}") },
                            onClick = {
                                open = false
                                onChange(t.copy(carrierProfile = c.id))
                            },
                        )
                    }
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(6.dp))
                KeyValue("Operator", "${selected.name} (${selected.mccMnc})")
                KeyValue("Country", selected.countryIso.uppercase())
                KeyValue("Phone number", "hidden")
            }
        }
    }
}

@Composable
private fun RuntimeCard(enabled: Boolean, hooks: List<HookStatus>, onRestart: () -> Unit) {
    SectionCard("Runtime status", icon = AppIcons.VerifiedUser, subtitle ="Reported by the app's own process when it starts with an enabled policy.") {
        when {
            !ModuleStatus.isActive -> StatusLine("Module not active; nothing is enforced.", Tone.BAD)
            !enabled -> StatusLine("Policy is off; the app is not modified.", Tone.NEUTRAL)
            hooks.isEmpty() -> StatusLine(
                "No report from this app yet. Make sure it is in AppGuard's scope in the LSPosed/Vector manager, then restart it.",
                Tone.WARN,
            )
            else -> {
                val last = hooks.maxOf { it.timestamp }
                val failed = hooks.filter { !it.installed }
                StatusLine("Last start reported at ${formatTime(last)} (pid ${hooks.maxBy { it.timestamp }.pid}).", if (failed.isEmpty()) Tone.GOOD else Tone.WARN)
                for (f in failed) StatusLine("${f.feature}: ${f.detail}", Tone.BAD)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRestart, enabled = enabled) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Force-stop app (root)")
        }
    }
}

/** Build-time implementation status plus what this app's process reported. */
@Composable
private fun FeatureStatus(feature: Feature, hooks: List<HookStatus>) {
    when (feature.implementation) {
        Implementation.VERIFIED -> StatusLine("Enforcement verified: ${feature.verifiedOn}", Tone.GOOD)
        Implementation.UNVERIFIED -> StatusLine("Implemented; enforcement not yet verified on a device.", Tone.WARN)
        Implementation.NOT_IMPLEMENTED -> StatusLine("Not implemented in this build. These controls have no effect.", Tone.NEUTRAL)
    }
    if (feature.implementation != Implementation.NOT_IMPLEMENTED) {
        val h = hooks.firstOrNull { it.feature == feature.name }
        when {
            h == null -> StatusLine("Hooks: no report from this app yet.", Tone.NEUTRAL)
            h.installed -> StatusLine("Hooks installed at ${formatTime(h.timestamp)}: ${h.detail}", Tone.GOOD)
            else -> StatusLine("Hooks failed to install: ${h.detail}", Tone.BAD)
        }
    }
    Expandable("Interception points & limitations") {
        for (p in feature.interceptionPoints) Bullet(p)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        for (l in feature.limitations) Bullet(l)
    }
}

private val locationOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the location default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Do not modify location results."),
    ModeOption(Mode.RESTRICT, "Restrict", "Last-known location returns nothing; current-location requests get no fix; update requests are not registered."),
    ModeOption(Mode.SIMULATE, "Simulate", "Return the test location below from LocationManager instead of the device's position."),
)
private val androidIdOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the identifier default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Return the real, already app-scoped Android ID."),
    ModeOption(Mode.SIMULATE, "Simulate", "Return a stable synthetic Android ID derived from this app's identity seed."),
)
private val deviceInfoOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the identifier default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Report the real manufacturer, brand, model, device and product."),
    ModeOption(Mode.SIMULATE, "Simulate", "Report the selected device profile in android.os.Build (applies at next app start)."),
)
private val clipboardOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the clipboard default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Do not modify clipboard reads."),
    ModeOption(Mode.RESTRICT, "Restrict", "The app's own clipboard reads see an empty clipboard. Copying, and pasting via the keyboard, keep working."),
)
private val networkOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the network default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Do not modify Wi-Fi details."),
    ModeOption(Mode.RESTRICT, "Restrict", "Hide the Wi-Fi name, router address, nearby networks and local IP, as Android does for apps without location permission."),
    ModeOption(Mode.SIMULATE, "Simulate", "Report a stable made-up router name and address for this app; nearby-network scans are empty."),
)
private val contactsOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the contacts default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Do not modify contacts, call log or calendar reads."),
    ModeOption(Mode.RESTRICT, "Restrict", "Reads of the data selected below return no entries, as if it were empty. Nothing is deleted."),
)
private val telephonyOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the phone & SIM default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow", "Do not modify carrier, SIM or phone-number reads."),
    ModeOption(Mode.RESTRICT, "Restrict", "Look like no SIM is inserted: no carrier, country or phone number. Calls and mobile data keep working."),
    ModeOption(Mode.SIMULATE, "Simulate", "Report the carrier chosen below; the phone number stays hidden."),
)
private val sensorOptions = listOf(
    ModeOption(Mode.DEFAULT, "Default", "Use the sensor default from Settings (Allow unless changed)."),
    ModeOption(Mode.ALLOW, "Allow (audit only)", "Sensor data is untouched; sensor registrations are recorded in the audit log."),
    ModeOption(Mode.RESTRICT, "Restrict", "The selected sensor groups look absent: hidden from sensor lists and registration fails."),
)

@Composable
private fun LocationSection(loc: LocationPolicy, editable: Boolean, hooks: List<HookStatus>, onChange: (LocationPolicy) -> Unit) {
    SectionCard(Category.LOCATION.label, icon = AppIcons.forCategory(Category.LOCATION)) {
        FeatureStatus(Feature.LOCATION, hooks)
        ModeSelector(locationOptions, loc.mode, editable) { m -> onChange(loc.copy(mode = m)) }
        if (loc.mode == Mode.SIMULATE) {
            Spacer(Modifier.height(8.dp))
            LocationEditor(loc, editable, onChange)
        }
    }
}

@Composable
private fun LocationEditor(loc: LocationPolicy, editable: Boolean, onChange: (LocationPolicy) -> Unit) {
    var lat by rememberSaveable(loc.latitude) { mutableStateOf(loc.latitude.toString()) }
    var lon by rememberSaveable(loc.longitude) { mutableStateOf(loc.longitude.toString()) }
    var acc by rememberSaveable(loc.accuracyMeters) { mutableStateOf(loc.accuracyMeters?.toString() ?: "") }
    var alt by rememberSaveable(loc.altitudeMeters) { mutableStateOf(loc.altitudeMeters?.toString() ?: "") }

    val latV = lat.trim().toDoubleOrNull()
    val lonV = lon.trim().toDoubleOrNull()
    val accV = acc.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
    val altV = alt.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val parseErrors = buildList {
        if (latV == null) add("Latitude is not a number.")
        if (lonV == null) add("Longitude is not a number.")
        if (acc.isNotBlank() && accV == null) add("Accuracy is not a number.")
        if (alt.isNotBlank() && altV == null) add("Altitude is not a number.")
    }
    val errors = parseErrors.ifEmpty { LocationSpec.validate(latV!!, lonV!!, accV, altV) }
    val dirty = latV != loc.latitude || lonV != loc.longitude || accV != loc.accuracyMeters || altV != loc.altitudeMeters

    StatusPill("Simulated location: ${loc.latitude}, ${loc.longitude}", Tone.WARN)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Latitude", lat, editable, Modifier.weight(1f)) { lat = it }
        NumberField("Longitude", lon, editable, Modifier.weight(1f)) { lon = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Accuracy m (opt.)", acc, editable, Modifier.weight(1f)) { acc = it }
        NumberField("Altitude m (opt.)", alt, editable, Modifier.weight(1f)) { alt = it }
    }
    for (e in errors) StatusLine(e, Tone.BAD)
    if (errors.isEmpty() && latV == 0.0 && lonV == 0.0) StatusLine("0, 0 is in the Atlantic Ocean; apps may treat it as \"no location\".", Tone.WARN)
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = { onChange(loc.copy(latitude = latV!!, longitude = lonV!!, accuracyMeters = accV, altitudeMeters = altV)) },
        enabled = editable && errors.isEmpty() && dirty,
    ) { Text("Save location") }
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.padding(vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentifierSection(
    policy: AppPolicy,
    editable: Boolean,
    hooks: List<HookStatus>,
    onChange: (io.github.imkamaran.appguard.policy.IdentifierPolicy) -> Unit,
    onRotate: () -> Unit,
) {
    val ids = policy.identifiers
    SectionCard("Device identifiers", icon = AppIcons.forCategory(Category.IDENTIFIERS)) {
        Text("Android ID", style = MaterialTheme.typography.titleSmall)
        FeatureStatus(Feature.ANDROID_ID, hooks)
        ModeSelector(androidIdOptions, ids.androidId, editable) { m -> onChange(ids.copy(androidId = m)) }
        if (ids.androidId == Mode.SIMULATE) {
            val preview = if (SyntheticIds.isValidSeed(ids.seed)) SyntheticIds.androidId(policy.packageName, ids.seed) else "(no identity seed yet)"
            KeyValue("App will read", preview)
            TextButton(onClick = onRotate, enabled = editable) { Text("Rotate identity (new stable value)") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Device model & manufacturer", style = MaterialTheme.typography.titleSmall)
        FeatureStatus(Feature.DEVICE_INFO, hooks)
        ModeSelector(deviceInfoOptions, ids.deviceInfo, editable) { m -> onChange(ids.copy(deviceInfo = m)) }
        if (ids.deviceInfo == Mode.SIMULATE) {
            var open by remember { mutableStateOf(false) }
            val selected = DeviceProfiles.byId(ids.deviceProfile)
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (editable) open = it }) {
                OutlinedTextField(
                    value = selected?.label ?: "Unknown profile",
                    onValueChange = {},
                    readOnly = true,
                    enabled = editable,
                    label = { Text("Device profile") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (p in DeviceProfiles.ALL) {
                        DropdownMenuItem(
                            text = { Text("${p.label}  ·  ${p.model}") },
                            onClick = {
                                open = false
                                onChange(ids.copy(deviceProfile = p.id))
                            },
                        )
                    }
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(6.dp))
                KeyValue("MANUFACTURER", selected.manufacturer)
                KeyValue("BRAND", selected.brand)
                KeyValue("MODEL", selected.model)
                KeyValue("DEVICE", selected.device)
                KeyValue("PRODUCT", selected.product)
            }
        }
    }
}
