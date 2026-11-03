package io.github.imkamaran.appguard.hook

import android.telephony.CellInfo
import android.telephony.ServiceState
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
 * Carrier identity does not only come from TelephonyManager: [ServiceState] and the CellIdentity
 * subclasses are parcelable value objects the framework builds in the app process, either from the
 * reply to getServiceState/getAllCellInfo or as the argument of a TelephonyCallback /
 * PhoneStateListener callback. Their own getters never reach a manager method, so hooking the
 * manager alone left their MCC/MNC and operator name real while the manager getters answered with
 * the simulated carrier (Cellular-Z showed "Verizon" next to the real MCC 460 / MNC 00). They are
 * hooked here too, so every path in the process reports the same carrier.
 *
 * IMEI/MEID, IMSI and the SIM serial need READ_PRIVILEGED_PHONE_STATE since Android 10, so normal
 * apps cannot read them and they are not hooked.
 */
internal object TelephonyHooks {
    private val tmDepth = ThreadLocal<IntArray>()
    private val smDepth = ThreadLocal<IntArray>()
    private val ssDepth = ThreadLocal<IntArray>()
    private val ciDepth = ThreadLocal<IntArray>()
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

    /**
     * The network operator as the framework reports it outside TelephonyManager.getNetworkOperator:
     * one ServiceState instance per registration, delivered to listeners or returned by
     * getServiceState. Restrict blanks it like the no-SIM operator getters do.
     */
    private val SERVICE_STATE: Map<String, Spec> = linkedMapOf(
        "getOperatorNumeric" to Spec("") { it.mccMnc },
        "getOperatorAlphaLong" to Spec("") { it.name },
        "getOperatorAlphaShort" to Spec("") { it.name },
    )

    /**
     * The serving cell's MCC/MNC. Unknown values follow the framework convention: the int getters
     * report [CellInfo.UNAVAILABLE] and the string getters return null. getMobileNetworkOperator
     * (Android 11+) returns the pair as one string, e.g. "46000".
     */
    private val CELL_IDENTITY: Map<String, Spec> = linkedMapOf(
        "getMcc" to Spec(CellInfo.UNAVAILABLE) { it.mcc },
        "getMnc" to Spec(CellInfo.UNAVAILABLE) { it.mnc },
        "getMccString" to Spec(null) { it.mccString },
        "getMncString" to Spec(null) { it.mncString },
        "getMobileNetworkOperator" to Spec("") { it.mccMnc },
    )

    /** Declared on the base class, so one hook covers every cell identity type. */
    private val CELL_IDENTITY_NAME: Map<String, Spec> = linkedMapOf(
        "getOperatorAlphaLong" to Spec("") { it.name },
        "getOperatorAlphaShort" to Spec("") { it.name },
    )

    /**
     * Resolved by name: an app process on an older platform simply has no CellIdentityNr, and that
     * must not take the rest of the telephony hooks down with it.
     */
    private val CELL_IDENTITY_CLASSES: List<Class<*>> = listOf(
        "android.telephony.CellIdentityLte",
        "android.telephony.CellIdentityGsm",
        "android.telephony.CellIdentityWcdma",
        "android.telephony.CellIdentityNr",
        "android.telephony.CellIdentityTdscdma",
    ).mapNotNull { name -> runCatching { Class.forName(name) }.getOrNull() }

    private val CELL_IDENTITY_BASE: Class<*> = Class.forName("android.telephony.CellIdentity")

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
        for ((name, spec) in SERVICE_STATE) {
            counts["ServiceState.$name"] = hookAllDeclared(
                ServiceState::class.java, name, Value(rt, "ServiceState.$name", ssDepth, spec),
            )
        }
        for (cls in CELL_IDENTITY_CLASSES) {
            var n = 0
            for ((name, spec) in CELL_IDENTITY) {
                n += hookAllDeclared(cls, name, Value(rt, "${cls.simpleName}.$name", ciDepth, spec))
            }
            counts[cls.simpleName] = n
        }
        for ((name, spec) in CELL_IDENTITY_NAME) {
            counts["CellIdentity.$name"] = hookAllDeclared(
                CELL_IDENTITY_BASE, name, Value(rt, "CellIdentity.$name", ciDepth, spec),
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
