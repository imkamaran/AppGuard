package io.github.imkamaran.appguard.hook

import android.Manifest
import android.content.pm.PackageManager
import android.app.AndroidAppHelper
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditLevel
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import io.github.imkamaran.appguard.policy.Resolution

/** Per-process state shared by all hooks of one target app. */
internal class HookRuntime(
    val packageName: String,
    private val source: RemotePolicySource,
    val audit: AuditClient,
) {
    /** The policy in force right now, or null when it was disabled after the process started. */
    fun policy(): EffectivePolicy? = source.current()?.effective

    fun installAll(initial: Resolution) {
        for (issue in initial.issues) {
            audit.record(AuditLevel.VERBOSE, Category.SYSTEM, "policy.issue", Mode.ALLOW, AuditAction.OBSERVED, Outcome.SUCCEEDED, issue)
        }
        install(Feature.ANDROID_ID) { IdentifierHooks.installAndroidId(this) }
        install(Feature.DEVICE_INFO) { IdentifierHooks.applyDeviceInfo(this, initial.effective) }
        install(Feature.LOCATION) { LocationHooks.install(this) }
        install(Feature.CLIPBOARD) { ClipboardHooks.install(this) }
        install(Feature.SENSORS) { SensorHooks.install(this) }
        install(Feature.NETWORK) { NetworkHooks.install(this) }
        install(Feature.CONTACTS) { ContactsHooks.install(this) }
        install(Feature.TELEPHONY) { TelephonyHooks.install(this) }
    }

    /** Reports that the crash guard suspended this app's policy (no hooks were installed). */
    fun reportCrashGuardTripped() {
        audit.record(
            AuditLevel.OFF, Category.SYSTEM, "hook:${Feature.CRASH_GUARD.name}", Mode.ALLOW, AuditAction.HOOK_FAILED, Outcome.SUCCEEDED,
            "policy suspended after ${CrashGuard.MAX_EARLY_CRASHES} crashes at start-up; save the policy again to retry",
        )
    }

    fun reportCrashGuardArmed() {
        audit.record(AuditLevel.OFF, Category.SYSTEM, "hook:${Feature.CRASH_GUARD.name}", Mode.ALLOW, AuditAction.HOOK_INSTALLED, Outcome.SUCCEEDED, "armed")
    }

    private inline fun install(feature: Feature, block: () -> String) {
        try {
            val detail = block()
            audit.record(AuditLevel.OFF, Category.SYSTEM, "hook:${feature.name}", Mode.ALLOW, AuditAction.HOOK_INSTALLED, Outcome.SUCCEEDED, detail)
        } catch (t: Throwable) {
            HookLog.w("${feature.name} hooks unavailable in $packageName", t)
            audit.record(AuditLevel.OFF, Category.SYSTEM, "hook:${feature.name}", Mode.ALLOW, AuditAction.HOOK_FAILED, Outcome.FAILED, t.toString().take(200))
        }
    }

    fun record(policy: EffectivePolicy, category: Category, api: String, mode: Mode, action: AuditAction, outcome: Outcome, detail: String = "") {
        audit.record(policy.auditLevel, category, api, mode, action, outcome, detail)
    }

    fun onHookError(feature: Feature, api: String, t: Throwable) {
        HookLog.limited("${feature.name}:$api", "$api hook failed in $packageName; original behavior kept", t)
        audit.record(AuditLevel.OFF, feature.category, api, Mode.ALLOW, AuditAction.HOOK_ERROR, Outcome.FAILED, t.javaClass.simpleName)
    }

    /** True unless the app is known to lack both location permissions. */
    fun hasLocationPermission(): Boolean {
        val app = AndroidAppHelper.currentApplication() ?: return true
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
