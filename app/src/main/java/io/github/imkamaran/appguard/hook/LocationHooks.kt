package io.github.imkamaran.appguard.hook

import android.app.PendingIntent
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.app.AndroidAppHelper
import de.robv.android.xposed.XposedBridge
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.LocationSpec
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import io.github.imkamaran.appguard.policy.Redact
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * android.location.LocationManager controls. All entry points below are public SDK methods that
 * run in the app process before the request goes to system_server over binder:
 *
 * - getLastKnownLocation(String): after-hook replaces the returned fix (Simulate) or returns null
 *   (Restrict). An original SecurityException is preserved.
 * - getCurrentLocation(...) (API 30+, both overloads): before-hook skips the system request and
 *   delivers a synthetic fix (Simulate) or null (Restrict) through the caller's executor.
 * - requestLocationUpdates(...)/requestSingleUpdate(...) (all overloads with a LocationListener or
 *   PendingIntent): before-hook skips registration with the system. Simulate then delivers
 *   synthetic fixes at the requested interval until removeUpdates; Restrict delivers nothing.
 * - removeUpdates(...): stops a simulated session; the original call still runs (a no-op for
 *   listeners the system never saw).
 *
 * Callbacks are always dispatched from AppGuard's own thread, never from inside a hook, so app
 * code that calls location APIs from a callback is intercepted normally.
 */
internal object LocationHooks {
    /** Shared by all location hooks: only the outermost call of a delegation chain is handled. */
    private val depth = ThreadLocal<IntArray>()

    fun install(rt: HookRuntime): String {
        val lm = LocationManager::class.java
        val counts = linkedMapOf<String, Int>()

        XposedBridge.hookMethod(lm.getDeclaredMethod("getLastKnownLocation", String::class.java), LastKnown(rt))
        counts["getLastKnownLocation"] = 1
        if (Build.VERSION.SDK_INT >= 30) counts["getCurrentLocation"] = hookAllDeclared(lm, "getCurrentLocation", CurrentLocation(rt))
        counts["requestLocationUpdates"] = hookAllDeclared(lm, "requestLocationUpdates", RequestUpdates(rt, single = false))
        counts["requestSingleUpdate"] = hookAllDeclared(lm, "requestSingleUpdate", RequestUpdates(rt, single = true))
        counts["removeUpdates"] = hookAllDeclared(lm, "removeUpdates", RemoveUpdates(rt))

        check(counts.getValue("requestLocationUpdates") > 0) { "no requestLocationUpdates overloads found" }
        return "overloads hooked: " + counts.entries.joinToString { "${it.key}=${it.value}" }
    }

    private fun describeFix(policy: EffectivePolicy, spec: LocationSpec): String =
        "fix " + Redact.coordinates(spec.latitude, spec.longitude, policy.redact)

    private class LastKnown(rt: HookRuntime) : GuardedHook(rt, Feature.LOCATION, depth) {
        override fun after(param: MethodHookParam, policy: EffectivePolicy) {
            val api = "LocationManager.getLastKnownLocation"
            if (param.hasThrowable()) return
            when (policy.location) {
                Mode.SIMULATE -> {
                    val spec = policy.locationSpec ?: return
                    param.result = SyntheticLocation.create(param.args.getOrNull(0) as? String, spec)
                    runtime.record(policy, Category.LOCATION, api, Mode.SIMULATE, AuditAction.SIMULATED, Outcome.SUCCEEDED, describeFix(policy, spec))
                }
                Mode.RESTRICT -> {
                    param.result = null
                    runtime.record(policy, Category.LOCATION, api, Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "returned null")
                }
                else -> runtime.record(policy, Category.LOCATION, api, policy.location, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
            }
        }
    }

    private class CurrentLocation(rt: HookRuntime) : GuardedHook(rt, Feature.LOCATION, depth) {
        @Suppress("UNCHECKED_CAST")
        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            val api = "LocationManager.getCurrentLocation"
            val mode = policy.location
            if (mode != Mode.SIMULATE && mode != Mode.RESTRICT) {
                runtime.record(policy, Category.LOCATION, api, mode, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
                return
            }
            if (!runtime.hasLocationPermission()) return // let the platform throw as usual
            val executor = param.args.firstOrNull { it is Executor } as? Executor
            val consumer = param.args.firstOrNull { it is Consumer<*> } as? Consumer<Location?>
            val action = if (mode == Mode.SIMULATE) AuditAction.SIMULATED else AuditAction.RESTRICTED
            if (executor == null || consumer == null) {
                runtime.record(policy, Category.LOCATION, api, mode, action, Outcome.FAILED, "unrecognized overload; passed through")
                return
            }
            val provider = param.args.firstOrNull { it is String } as? String
            val spec = policy.locationSpec
            val fix = if (mode == Mode.SIMULATE && spec != null) SyntheticLocation.create(provider, spec) else null
            param.result = null
            SimulatedUpdates.post { executor.execute { consumer.accept(fix) } }
            val detail = if (fix != null && spec != null) describeFix(policy, spec) else "delivered null"
            runtime.record(policy, Category.LOCATION, api, mode, action, Outcome.SUCCEEDED, detail)
        }
    }

    private class RequestUpdates(rt: HookRuntime, private val single: Boolean) : GuardedHook(rt, Feature.LOCATION, depth) {
        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            val api = if (single) "LocationManager.requestSingleUpdate" else "LocationManager.requestLocationUpdates"
            val args = param.args
            val target: Any? = args.firstOrNull { it is LocationListener || it is PendingIntent }
            val mode = policy.location

            if (mode != Mode.SIMULATE && mode != Mode.RESTRICT) {
                // Policy switched away from Simulate while running: stop feeding synthetic fixes.
                target?.let { SimulatedUpdates.stop(it) }
                runtime.record(policy, Category.LOCATION, api, mode, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
                return
            }
            if (!runtime.hasLocationPermission()) return
            val action = if (mode == Mode.SIMULATE) AuditAction.SIMULATED else AuditAction.RESTRICTED
            if (target == null) {
                // Unknown overload (e.g. a future API shape): do not guess, keep platform behavior.
                runtime.record(policy, Category.LOCATION, api, mode, action, Outcome.FAILED, "unrecognized overload; passed through")
                return
            }

            param.result = null // the system never sees this registration
            if (mode == Mode.RESTRICT) {
                SimulatedUpdates.stop(target)
                runtime.record(policy, Category.LOCATION, api, mode, action, Outcome.SUCCEEDED, "registration suppressed")
                return
            }
            val provider = args.firstOrNull { it is String } as? String
            val interval = SimulatedUpdates.intervalFrom(args)
            SimulatedUpdates.start(
                runtime = runtime,
                target = target,
                provider = provider,
                intervalMs = interval,
                executor = args.firstOrNull { it is Executor } as? Executor,
                looper = args.firstOrNull { it is Looper } as? Looper ?: Looper.myLooper(),
                single = single,
            )
            // Registration was replaced; whether fixes arrive is only known at delivery, which
            // records its own SUCCEEDED/FAILED event.
            val plan = if (single) "one synthetic fix" else "synthetic fixes every ${interval / 1000}s"
            runtime.record(policy, Category.LOCATION, api, mode, action, Outcome.UNDETERMINED, "registration replaced; $plan pending")
        }
    }

    private class RemoveUpdates(rt: HookRuntime) : GuardedHook(rt, Feature.LOCATION, depth) {
        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            param.args.firstOrNull()?.let { SimulatedUpdates.stop(it) }
        }
    }
}

internal object SyntheticLocation {
    fun create(provider: String?, spec: LocationSpec): Location = Location(provider ?: LocationManager.GPS_PROVIDER).apply {
        latitude = spec.latitude
        longitude = spec.longitude
        accuracy = spec.accuracyMeters
        spec.altitudeMeters?.let { altitude = it }
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}

/** Delivers synthetic fixes to listeners/PendingIntents whose system registration was skipped. */
internal object SimulatedUpdates {
    private const val MIN_INTERVAL_MS = 1_000L
    private const val MAX_INTERVAL_MS = 60_000L

    private val handler: Handler by lazy {
        Handler(HandlerThread("AppGuard-location").apply { start() }.looper)
    }
    private val sessions = ConcurrentHashMap<Any, Session>()

    fun post(r: Runnable) {
        handler.post {
            try {
                r.run()
            } catch (t: Throwable) {
                HookLog.limited("location-post", "synthetic location delivery failed", t)
            }
        }
    }

    fun intervalFrom(args: Array<Any?>): Long {
        val raw = args.firstOrNull { it is Long } as? Long
            ?: args.firstNotNullOfOrNull { a ->
                if (a == null || a.javaClass.name != "android.location.LocationRequest") null
                else runCatching { a.javaClass.getMethod("getIntervalMillis").invoke(a) as Long }.getOrNull()
            }
            ?: MIN_INTERVAL_MS
        return raw.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    fun start(
        runtime: HookRuntime,
        target: Any,
        provider: String?,
        intervalMs: Long,
        executor: Executor?,
        looper: Looper?,
        single: Boolean,
    ) {
        val session = Session(runtime, target, provider, intervalMs, executor, looper, single)
        sessions.put(target, session)?.cancelled = true
        handler.post(session)
    }

    fun stop(target: Any) {
        sessions.remove(target)?.let {
            it.cancelled = true
            handler.removeCallbacks(it)
        }
    }

    private class Session(
        val runtime: HookRuntime,
        val target: Any,
        val provider: String?,
        val intervalMs: Long,
        val executor: Executor?,
        val looper: Looper?,
        val single: Boolean,
    ) : Runnable {
        @Volatile
        var cancelled = false

        override fun run() {
            if (cancelled) return
            try {
                val policy = runtime.policy()
                val spec = policy?.locationSpec
                if (policy?.location != Mode.SIMULATE || spec == null) {
                    // Policy changed while running; the system was never registered, so stop.
                    stop(target)
                    return
                }
                deliver(SyntheticLocation.create(provider, spec))
                if (single) stop(target) else handler.postDelayed(this, intervalMs)
            } catch (t: Throwable) {
                HookLog.limited("location-session", "synthetic location session stopped", t)
                stop(target)
            }
        }

        private fun deliver(fix: Location) {
            when (target) {
                is LocationListener -> {
                    val call = Runnable { if (!cancelled) target.onLocationChanged(fix) }
                    if (executor != null) executor.execute(call) else Handler(looper ?: Looper.getMainLooper()).post(call)
                }
                is PendingIntent -> {
                    val ctx = AndroidAppHelper.currentApplication() ?: return
                    try {
                        target.send(ctx, 0, Intent().putExtra(LocationManager.KEY_LOCATION_CHANGED, fix))
                    } catch (_: PendingIntent.CanceledException) {
                        stop(target)
                    }
                }
            }
        }
    }
}
