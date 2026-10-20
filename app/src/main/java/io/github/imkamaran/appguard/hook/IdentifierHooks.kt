package io.github.imkamaran.appguard.hook

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import io.github.imkamaran.appguard.policy.Redact

/**
 * Device identifier controls.
 *
 * ANDROID_ID: apps read it through the public static Settings.Secure.getString(ContentResolver,
 * String), which runs in the app process and asks the settings provider over binder. Answering in
 * a before-hook means the real value is never fetched into the process.
 *
 * Build fields: android.os.Build.* are static fields initialized from system properties when the
 * class loads in each process. Overwriting them in this process changes what app code reads; it
 * does not change system properties or other processes.
 */
internal object IdentifierHooks {
    private const val ANDROID_ID_API = "Settings.Secure.getString(android_id)"
    private const val BUILD_API = "android.os.Build fields"

    fun installAndroidId(rt: HookRuntime): String {
        val method = Settings.Secure::class.java.getDeclaredMethod("getString", ContentResolver::class.java, String::class.java)
        XposedBridge.hookMethod(method, object : GuardedHook(rt, Feature.ANDROID_ID) {
            override fun accepts(param: MethodHookParam) = param.args.getOrNull(1) == Settings.Secure.ANDROID_ID

            override fun before(param: MethodHookParam, policy: EffectivePolicy) {
                if (policy.androidId == Mode.SIMULATE) {
                    val value = policy.syntheticAndroidId ?: return
                    param.result = value
                    rt.record(policy, Category.IDENTIFIERS, ANDROID_ID_API, Mode.SIMULATE, AuditAction.SIMULATED, Outcome.SUCCEEDED, Redact.value(value, policy.redact))
                } else {
                    rt.record(policy, Category.IDENTIFIERS, ANDROID_ID_API, policy.androidId, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
                }
            }
        })
        return "hooked Settings.Secure.getString"
    }

    /**
     * Applied once at process start; Build fields are cached by apps and libraries, so changing them
     * mid-run would produce an inconsistent identity. Returns a status description.
     */
    fun applyDeviceInfo(rt: HookRuntime, policy: EffectivePolicy): String {
        if (policy.deviceInfo != Mode.SIMULATE) return "inactive (mode ${policy.deviceInfo}); takes effect on next app start"
        val profile = policy.deviceProfile ?: return "inactive (no profile)"
        val wanted = linkedMapOf(
            "MANUFACTURER" to profile.manufacturer,
            "BRAND" to profile.brand,
            "MODEL" to profile.model,
            "DEVICE" to profile.device,
            "PRODUCT" to profile.product,
        )
        for ((field, value) in wanted) XposedHelpers.setStaticObjectField(Build::class.java, field, value)

        // Read back through reflection (not the compiled constant) to confirm what apps will see.
        val mismatched = wanted.filter { (field, value) -> Build::class.java.getField(field).get(null) != value }.keys
        val outcome = if (mismatched.isEmpty()) Outcome.SUCCEEDED else Outcome.FAILED
        val detail = if (mismatched.isEmpty()) "profile ${profile.id}" else "profile ${profile.id}; not applied: ${mismatched.joinToString()}"
        rt.record(policy, Category.IDENTIFIERS, BUILD_API, Mode.SIMULATE, AuditAction.SIMULATED, outcome, detail)
        if (mismatched.isNotEmpty()) error("Build fields not writable: ${mismatched.joinToString()}")
        return "applied profile ${profile.id}"
    }
}
