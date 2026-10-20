package io.github.imkamaran.appguard.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import io.github.imkamaran.appguard.ModuleStatus
import io.github.imkamaran.appguard.audit.AuditVisibility
import io.github.imkamaran.appguard.policy.AppPolicy
import io.github.imkamaran.appguard.policy.ConfigState
import io.github.imkamaran.appguard.policy.GlobalSettings
import io.github.imkamaran.appguard.policy.IdentifierPolicy
import io.github.imkamaran.appguard.policy.PolicySchema
import io.github.imkamaran.appguard.policy.ProtectedPackages
import io.github.imkamaran.appguard.policy.PolicyTemplate
import io.github.imkamaran.appguard.policy.RestartAdvisor
import io.github.imkamaran.appguard.policy.SyntheticIds
import io.github.imkamaran.appguard.policy.Templates
import io.github.imkamaran.appguard.policy.UiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import java.io.File
import java.util.concurrent.Executors

data class PublishStatus(
    /** True when LSPosed provides the cross-process preference file (module active). */
    val remoteAvailable: Boolean,
    val lastPublishedAt: Long = 0L,
    val error: String? = null,
)

/**
 * Source of truth for policies.
 *
 * - Canonical copy: `files/config.json`, written atomically. Survives the module being inactive.
 * - Published copy: the "policies" SharedPreferences opened with MODE_WORLD_READABLE. With
 *   `xposedsharedprefs` LSPosed/Vector stores that file in its own directory and lets only
 *   processes in the module's scope read it (no world-readable file in app data). Every change,
 *   and every start of this app, republishes the complete config and removes stale keys.
 */
class PolicyRepository(context: Context) {
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "config.json"))
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AppGuard-config") }

    private val _state = MutableStateFlow(readCanonical())
    val state: StateFlow<ConfigState> = _state.asStateFlow()

    private val _publish = MutableStateFlow(PublishStatus(remoteAvailable = false))
    val publishStatus: StateFlow<PublishStatus> = _publish.asStateFlow()

    fun policy(packageName: String): AppPolicy? = _state.value.policies[packageName]

    /** Creates the policy on first edit, with a fresh identity seed. */
    fun updatePolicy(packageName: String, transform: (AppPolicy) -> AppPolicy) {
        require(PolicySchema.isValidPackageName(packageName)) { "invalid package name" }
        require(!ProtectedPackages.isProtected(packageName)) { "protected package" }
        mutate { s ->
            val current = s.policies[packageName] ?: newPolicy(packageName)
            val now = System.currentTimeMillis()
            var next = transform(current).copy(packageName = packageName, updatedAt = now)
            if (RestartAdvisor.startupFieldsDiffer(current, next)) {
                next = next.copy(startupChangedAt = now)
            }
            s.copy(policies = s.policies + (packageName to next))
        }
    }

    fun removePolicy(packageName: String) = mutate { s -> s.copy(policies = s.policies - packageName) }

    fun updateGlobal(transform: (GlobalSettings) -> GlobalSettings) =
        mutate { s -> s.copy(global = PolicySchema.sanitize(transform(s.global))) }

    fun updateUi(transform: (UiSettings) -> UiSettings) = mutate { s -> s.copy(ui = transform(s.ui)) }

    /** Recovery: switch every policy off (kept for later re-enabling). */
    fun disableAllPolicies() = mutate { s ->
        s.copy(policies = s.policies.mapValues { (_, p) -> p.copy(enabled = false, updatedAt = System.currentTimeMillis()) })
    }

    fun rotateIdentity(packageName: String) = updatePolicy(packageName) {
        it.copy(identifiers = it.identifiers.copy(seed = SyntheticIds.newSeed()))
    }

    /** Re-publishes the current config, e.g. after the module was activated. */
    fun republish() = io.execute { publish(_state.value) }

    fun addTemplate(template: PolicyTemplate) = mutate { s ->
        val sanitized = Templates.sanitize(template)
        val updated = s.templates.filter { it.id != sanitized.id } + sanitized
        s.copy(templates = updated.take(Templates.MAX_USER_TEMPLATES))
    }

    fun removeTemplate(id: String) = mutate { s ->
        s.copy(templates = s.templates.filter { it.id != id })
    }

    fun bulkApplyTemplate(packages: List<String>, template: PolicyTemplate) = mutate { s ->
        val now = System.currentTimeMillis()
        val updated = s.policies.toMutableMap()
        packages.filter { PolicySchema.isValidPackageName(it) && !ProtectedPackages.isProtected(it) }.forEach { pkg ->
            val existing = updated[pkg]
            val next = Templates.apply(template, existing ?: newPolicy(pkg)).copy(packageName = pkg, updatedAt = now)
            updated[pkg] = stampStartupChange(existing, next, now)
        }
        s.copy(policies = updated)
    }

    fun bulkSetEnabled(packages: List<String>, enabled: Boolean) = mutate { s ->
        val now = System.currentTimeMillis()
        val updated = s.policies.toMutableMap()
        packages.filter { PolicySchema.isValidPackageName(it) && !ProtectedPackages.isProtected(it) }.forEach { pkg ->
            val current = updated[pkg]
            if (current != null || enabled) {
                val next = (current ?: newPolicy(pkg)).copy(enabled = enabled, updatedAt = now)
                updated[pkg] = stampStartupChange(current, next, now)
            }
        }
        s.copy(policies = updated)
    }

    private fun stampStartupChange(old: AppPolicy?, next: AppPolicy, now: Long) =
        if (RestartAdvisor.startupFieldsDiffer(old, next)) next.copy(startupChangedAt = now) else next

    fun importConfig(config: ConfigState) = mutate { config }

    fun cleanUninstalledPolicies(installed: Set<String>): Int {
        if (installed.isEmpty()) return 0
        val before = _state.value.policies.size
        mutate { s -> s.copy(policies = s.policies.filterKeys { it in installed }) }
        return before - _state.value.policies.size
    }

    private fun newPolicy(packageName: String) = AppPolicy(
        packageName = packageName,
        identifiers = IdentifierPolicy(seed = SyntheticIds.newSeed()),
    )

    private fun mutate(change: (ConfigState) -> ConfigState) {
        _state.update(change)
        val snapshot = _state.value
        io.execute {
            writeCanonical(snapshot)
            publish(snapshot)
        }
    }

    private fun readCanonical(): ConfigState {
        if (!file.baseFile.exists()) return ConfigState()
        return try {
            val raw = file.readFully().toString(Charsets.UTF_8)
            val decoded = PolicySchema.json.decodeFromString(ConfigState.serializer(), raw)
            // Drop records whose key and content disagree, or that name protected packages.
            decoded.copy(
                global = PolicySchema.sanitize(decoded.global),
                policies = decoded.policies.filter { (k, v) ->
                    k == v.packageName && PolicySchema.isValidPackageName(k) && !ProtectedPackages.isProtected(k)
                },
            )
        } catch (e: SerializationException) {
            quarantine(e)
        } catch (e: IllegalArgumentException) {
            quarantine(e)
        } catch (e: java.io.IOException) {
            quarantine(e)
        }
    }

    /** Keeps a corrupt file for inspection and starts from defaults (all policies off). */
    private fun quarantine(e: Exception): ConfigState {
        Log.e(TAG, "config.json unreadable; starting with defaults", e)
        runCatching { file.baseFile.copyTo(File(appContext.filesDir, "config.corrupt.json"), overwrite = true) }
        return ConfigState()
    }

    private fun writeCanonical(state: ConfigState) {
        val out = file.startWrite()
        try {
            out.write(PolicySchema.json.encodeToString(ConfigState.serializer(), state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: java.io.IOException) {
            file.failWrite(out)
            Log.e(TAG, "failed to save config", e)
        }
    }

    @SuppressLint("WorldReadableFiles")
    private fun remotePrefs(): SharedPreferences? {
        if (!ModuleStatus.isActive) return null
        return try {
            @Suppress("DEPRECATION")
            appContext.getSharedPreferences(PolicySchema.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            // Framework did not take over the preference file (module inactive or too old).
            null
        }
    }

    private fun publish(state: ConfigState) {
        val prefs = remotePrefs()
        if (prefs == null) {
            _publish.value = PublishStatus(remoteAvailable = false, error = "Module not active: hooked apps cannot read the configuration.")
            return
        }
        val wanted = HashMap<String, Any>()
        wanted[PolicySchema.KEY_SCHEMA] = PolicySchema.SCHEMA_VERSION
        wanted[PolicySchema.KEY_GLOBAL] = PolicySchema.encodeGlobal(state.global)
        for ((pkg, p) in state.policies) wanted[PolicySchema.policyKey(pkg)] = PolicySchema.encodePolicy(p)

        val editor = prefs.edit()
        for (key in prefs.all.keys) if (key !in wanted) editor.remove(key)
        for ((k, v) in wanted) {
            when (v) {
                is Int -> editor.putInt(k, v)
                is String -> editor.putString(k, v)
            }
        }
        // commit(), not apply(): hooked processes poll the file, so it must be on disk now.
        val ok = editor.commit()
        AuditVisibility.grant(appContext, state.policies.values.filter { it.enabled }.map { it.packageName })
        _publish.value = if (ok) {
            PublishStatus(remoteAvailable = true, lastPublishedAt = System.currentTimeMillis())
        } else {
            PublishStatus(remoteAvailable = true, error = "Writing the shared configuration failed.")
        }
    }

    private companion object {
        const val TAG = "AppGuard"
    }
}
