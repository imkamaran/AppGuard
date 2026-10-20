package io.github.imkamaran.appguard.hook

import android.hardware.Sensor
import android.hardware.SensorManager
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditLevel
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import io.github.imkamaran.appguard.policy.SensorGroups

/**
 * android.hardware.SensorManager controls (all public, app-process methods):
 *
 * - getSensorList(int): after-hook removes restricted types from the returned list.
 * - getDefaultSensor(int[, boolean]): after-hook returns null for restricted types.
 * - registerListener(... Sensor ...) / requestTriggerSensor(..., Sensor): before-hook returns
 *   false for restricted types, which is the documented "sensor not available" result, so the
 *   registration never reaches the system.
 *
 * In Allow mode ("audit only") nothing is changed; registrations are recorded whenever auditing is
 * on for the app, so the user can see which sensors an app uses. Sensor values are never logged.
 */
internal object SensorHooks {
    private val depth = ThreadLocal<IntArray>()

    fun install(rt: HookRuntime): String {
        val sm = SensorManager::class.java
        val counts = linkedMapOf(
            "getSensorList" to hookAllDeclared(sm, "getSensorList", SensorList(rt)),
            "getDefaultSensor" to hookAllDeclared(sm, "getDefaultSensor", DefaultSensor(rt)),
            "registerListener" to hookAllDeclared(sm, "registerListener", Register(rt, "SensorManager.registerListener")),
            "requestTriggerSensor" to hookAllDeclared(sm, "requestTriggerSensor", Register(rt, "SensorManager.requestTriggerSensor")),
        )
        check(counts.getValue("registerListener") > 0) { "SensorManager.registerListener not found" }
        return "overloads hooked: " + counts.entries.joinToString { "${it.key}=${it.value}" }
    }

    private class SensorList(rt: HookRuntime) : GuardedHook(rt, Feature.SENSORS, depth) {
        override fun after(param: MethodHookParam, policy: EffectivePolicy) {
            if (policy.sensors != Mode.RESTRICT || param.hasThrowable()) return
            val list = param.result as? List<*> ?: return
            val kept = list.filter { (it as? Sensor)?.type !in policy.restrictedSensorTypes }
            if (kept.size == list.size) return
            param.result = java.util.Collections.unmodifiableList(kept)
            runtime.record(policy, Category.SENSORS, "SensorManager.getSensorList", Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "hid ${list.size - kept.size} sensors")
        }
    }

    private class DefaultSensor(rt: HookRuntime) : GuardedHook(rt, Feature.SENSORS, depth) {
        override fun after(param: MethodHookParam, policy: EffectivePolicy) {
            if (policy.sensors != Mode.RESTRICT || param.hasThrowable()) return
            val type = param.args.getOrNull(0) as? Int ?: return
            if (type !in policy.restrictedSensorTypes) return
            param.result = null
            runtime.record(policy, Category.SENSORS, "SensorManager.getDefaultSensor", Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, SensorGroups.label(type))
        }
    }

    private class Register(rt: HookRuntime, private val api: String) : GuardedHook(rt, Feature.SENSORS, depth) {
        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            val sensor = param.args.firstOrNull { it is Sensor } as? Sensor
            // Allow is "audit only": keep registration events whenever auditing is on for this app.
            val observeLevel = if (policy.auditLevel == AuditLevel.OFF) AuditLevel.OFF else AuditLevel.VERBOSE
            if (sensor == null) {
                runtime.audit.record(observeLevel, Category.SENSORS, api, policy.sensors, AuditAction.OBSERVED, Outcome.UNDETERMINED, "legacy SensorListener API; not filtered")
                return
            }
            val label = SensorGroups.label(sensor.type)
            if (policy.sensors == Mode.RESTRICT && sensor.type in policy.restrictedSensorTypes) {
                param.result = false
                runtime.record(policy, Category.SENSORS, api, Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "refused $label")
            } else {
                runtime.audit.record(observeLevel, Category.SENSORS, api, policy.sensors, AuditAction.OBSERVED, Outcome.SUCCEEDED, "registered $label")
            }
        }
    }
}
