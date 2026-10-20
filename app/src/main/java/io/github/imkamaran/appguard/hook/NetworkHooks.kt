package io.github.imkamaran.appguard.hook

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import io.github.imkamaran.appguard.policy.Redact
import java.net.NetworkInterface

/**
 * Wi-Fi network identity. WifiInfo is a parcelable value object whose getters run in the app
 * process, so hooking the getters covers every way an app obtains one (WifiManager.getConnectionInfo,
 * NetworkCapabilities.getTransportInfo, callbacks). Restrict mirrors what Android itself reports
 * to apps without location permission. Connectivity is never touched.
 */
internal object NetworkHooks {
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private const val NO_MAC = "02:00:00:00:00:00"

    /** Returned by a value picker to leave the call unmodified. */
    private val PASS = Any()

    fun install(rt: HookRuntime): String {
        val counts = linkedMapOf<String, Int>()
        val info = WifiInfo::class.java
        // SSID/BSSID are decided after the getter so Simulate can keep Android's own redaction
        // (e.g. NetworkCapabilities copies without location info); replacing a redacted value
        // with a plausible one would make the spoofing detectable.
        counts["getSSID"] = hookAllDeclared(info, "getSSID", Value(rt, "WifiInfo.getSSID", sensitive = true, afterCall = true) { p, real ->
            when {
                p.network != Mode.SIMULATE -> UNKNOWN_SSID
                real == null || real == UNKNOWN_SSID -> PASS
                else -> p.syntheticSsid?.let { "\"$it\"" } ?: PASS
            }
        })
        counts["getBSSID"] = hookAllDeclared(info, "getBSSID", Value(rt, "WifiInfo.getBSSID", sensitive = true, afterCall = true) { p, real ->
            when {
                p.network != Mode.SIMULATE -> NO_MAC
                real == null || real == NO_MAC -> PASS
                else -> p.syntheticBssid ?: PASS
            }
        })
        counts["getMacAddress"] = hookAllDeclared(info, "getMacAddress", Value(rt, "WifiInfo.getMacAddress") { _, _ -> NO_MAC })
        counts["getIpAddress"] = hookAllDeclared(info, "getIpAddress", Value(rt, "WifiInfo.getIpAddress") { p, _ ->
            if (p.network == Mode.SIMULATE) PASS else 0
        })
        counts["getScanResults"] = hookAllDeclared(WifiManager::class.java, "getScanResults", Value(rt, "WifiManager.getScanResults") { _, _ ->
            ArrayList<ScanResult>()
        })
        counts["getHardwareAddress"] = hookAllDeclared(NetworkInterface::class.java, "getHardwareAddress", Value(rt, "NetworkInterface.getHardwareAddress") { _, _ ->
            null
        })
        check(counts.getValue("getSSID") > 0) { "WifiInfo.getSSID not found" }
        return "hooked: " + counts.entries.joinToString { "${it.key}=${it.value}" }
    }

    private class Value(
        rt: HookRuntime,
        private val api: String,
        private val sensitive: Boolean = false,
        /** Decide after the original getter ran, with its result; otherwise answer before it runs. */
        private val afterCall: Boolean = false,
        /** (policy, real value or null before the call) -> replacement, or [PASS]. */
        private val pick: (EffectivePolicy, Any?) -> Any?,
    ) : GuardedHook(rt, Feature.NETWORK) {
        override val hasAfter: Boolean get() = afterCall

        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            if (!afterCall) decide(param, policy, null)
        }

        override fun after(param: MethodHookParam, policy: EffectivePolicy) {
            if (afterCall && !param.hasThrowable()) decide(param, policy, param.result)
        }

        private fun decide(param: MethodHookParam, policy: EffectivePolicy, real: Any?) {
            val mode = policy.network
            val value = if (mode == Mode.ALLOW) PASS else pick(policy, real)
            if (value === PASS) {
                runtime.record(policy, Category.NETWORK, api, mode, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
                return
            }
            param.result = value
            if (mode == Mode.SIMULATE) {
                val detail = if (sensitive) Redact.value(value?.toString(), policy.redact) else "synthetic value"
                runtime.record(policy, Category.NETWORK, api, mode, AuditAction.SIMULATED, Outcome.SUCCEEDED, detail)
            } else {
                runtime.record(policy, Category.NETWORK, api, mode, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "hidden")
            }
        }
    }
}
