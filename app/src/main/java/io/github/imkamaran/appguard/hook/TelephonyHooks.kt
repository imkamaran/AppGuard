package io.github.imkamaran.appguard.hook

import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.CarrierProfile
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome

/**
 * Carrier, SIM and phone-number reads. TelephonyManager and SubscriptionManager getters run in the
 * app process and query the phone process over binder; answering in a before-hook means the real
 * values are never fetched. Restrict reports what a phone without a SIM reports.
 *
 * IMEI/MEID, IMSI and the SIM serial need READ_PRIVILEGED_PHONE_STATE since Android 10, so normal
 * apps cannot read them and they are not hooked.
 */
internal object TelephonyHooks {
    private val tmDepth = ThreadLocal<IntArray>()
    private val smDepth = ThreadLocal<IntArray>()
    private const val UNKNOWN_CARRIER_ID = -1

    private class Spec(val restricted: Any?, val simulated: (CarrierProfile) -> Any?)

    private val TELEPHONY: Map<String, Spec> = linkedMapOf(
        "getSimOperator" to Spec("") { it.mccMnc },
        "getNetworkOperator" to Spec("") { it.mccMnc },
        "getSimOperatorName" to Spec("") { it.name },
        "getNetworkOperatorName" to Spec("") { it.name },
        "getSimCountryIso" to Spec("") { it.countryIso },
        "getNetworkCountryIso" to Spec("") { it.countryIso },
        "getLine1Number" to Spec(null) { null },
        "getGroupIdLevel1" to Spec(null) { null },
        "getSimCarrierId" to Spec(UNKNOWN_CARRIER_ID) { UNKNOWN_CARRIER_ID },
        "getSimCarrierIdName" to Spec(null) { it.name },
        "getSimSpecificCarrierId" to Spec(UNKNOWN_CARRIER_ID) { UNKNOWN_CARRIER_ID },
        "getSimSpecificCarrierIdName" to Spec(null) { it.name },
    )

    /** Subscription records carry carrier, number and country, so both modes report none. */
    private val SUBSCRIPTIONS: Map<String, () -> Any?> = linkedMapOf(
        "getActiveSubscriptionInfoList" to { ArrayList<SubscriptionInfo>() },
        "getCompleteActiveSubscriptionInfoList" to { ArrayList<SubscriptionInfo>() },
        "getActiveSubscriptionInfo" to { null },
        "getActiveSubscriptionInfoForSimSlotIndex" to { null },
    )

    fun install(rt: HookRuntime): String {
        val counts = linkedMapOf<String, Int>()
        for ((name, spec) in TELEPHONY) {
            counts[name] = hookAllDeclared(TelephonyManager::class.java, name, Value(rt, "TelephonyManager.$name", tmDepth, spec))
        }
        for ((name, empty) in SUBSCRIPTIONS) {
            counts[name] = hookAllDeclared(
                SubscriptionManager::class.java, name,
                Value(rt, "SubscriptionManager.$name", smDepth, Spec(empty()) { empty() }),
            )
        }
        check(counts.getValue("getSimOperator") > 0) { "TelephonyManager.getSimOperator not found" }
        return "hooked: " + counts.entries.filter { it.value > 0 }.joinToString { "${it.key}=${it.value}" }
    }

    private class Value(
        rt: HookRuntime,
        private val api: String,
        family: ThreadLocal<IntArray>,
        private val spec: Spec,
    ) : GuardedHook(rt, Feature.TELEPHONY, family) {
        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            when (policy.telephony) {
                Mode.RESTRICT -> {
                    param.result = fresh(spec.restricted)
                    runtime.record(policy, Category.TELEPHONY, api, Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "reported no SIM")
                }
                Mode.SIMULATE -> {
                    val carrier = policy.carrier ?: return
                    param.result = fresh(spec.simulated(carrier))
                    runtime.record(policy, Category.TELEPHONY, api, Mode.SIMULATE, AuditAction.SIMULATED, Outcome.SUCCEEDED, "carrier ${carrier.id}")
                }
                else -> runtime.record(policy, Category.TELEPHONY, api, policy.telephony, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
            }
        }

        /** Never hand the same mutable list to two callers. */
        private fun fresh(v: Any?): Any? = if (v is ArrayList<*>) ArrayList<Any?>() else v
    }
}
