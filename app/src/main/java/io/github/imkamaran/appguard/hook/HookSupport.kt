package io.github.imkamaran.appguard.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object HookLog {
    private const val TAG = "AppGuard: "
    private val rateLimit = ConcurrentHashMap<String, Long>()

    fun i(msg: String) = XposedBridge.log(TAG + msg)

    fun w(msg: String, t: Throwable? = null) {
        XposedBridge.log(TAG + msg + (t?.let { " - $it" } ?: ""))
    }

    /** Logs [key] at most once a minute so a failing hook on a hot path cannot flood the log. */
    fun limited(key: String, msg: String, t: Throwable?) {
        val now = System.currentTimeMillis()
        val last = rateLimit[key]
        if (last == null || now - last > 60_000) {
            rateLimit[key] = now
            w(msg, t)
        }
    }
}

/**
 * Prevents AppGuard's own code from re-entering hooks on the same thread, e.g. when building a
 * result calls another hooked API.
 */
internal object Reentry {
    private val active = ThreadLocal<BooleanArray>()

    fun enter(): Boolean {
        var flag = active.get()
        if (flag == null) {
            flag = BooleanArray(1)
            active.set(flag)
        }
        if (flag[0]) return false
        flag[0] = true
        return true
    }

    fun exit() {
        active.get()?.set(0, false)
    }
}

/**
 * Base for every AppGuard hook:
 * - catches everything, so a hook bug degrades to the original behavior instead of a crash;
 * - skips work when this process currently has no effective policy;
 * - with a [family], only the outermost call of a delegation chain (public overload calling a
 *   hidden overload) runs the policy, so one app call yields one decision and one audit event.
 */
internal abstract class GuardedHook(
    protected val runtime: HookRuntime,
    private val feature: Feature,
    private val family: ThreadLocal<IntArray>? = null,
) : XC_MethodHook() {

    final override fun beforeHookedMethod(param: MethodHookParam) {
        if (family == null) {
            if (accepts(param)) guarded(param) { policy -> before(param, policy) }
            return
        }
        val depth = family.get() ?: IntArray(1).also { family.set(it) }
        depth[0]++
        val outer = depth[0] == 1
        // Per-call extras allocate, so they are only used by hooks that need the depth flag.
        param.setObjectExtra(KEY_OUTER, outer)
        if (outer) guarded(param) { policy -> before(param, policy) }
    }

    final override fun afterHookedMethod(param: MethodHookParam) {
        if (family == null) {
            if (hasAfter) guarded(param) { policy -> after(param, policy) }
            return
        }
        try {
            if (param.getObjectExtra(KEY_OUTER) != false) guarded(param) { policy -> after(param, policy) }
        } finally {
            family.get()?.let { it[0] = (it[0] - 1).coerceAtLeast(0) }
        }
    }

    /** Hooks without an after-phase skip the second policy lookup entirely. */
    protected open val hasAfter: Boolean get() = false

    /** Cheap argument pre-filter for hot methods (non-family hooks only); runs before any lookup. */
    protected open fun accepts(param: MethodHookParam): Boolean = true

    private inline fun guarded(param: MethodHookParam, block: (EffectivePolicy) -> Unit) {
        if (!Reentry.enter()) return
        try {
            val policy = runtime.policy() ?: return
            block(policy)
        } catch (t: Throwable) {
            runtime.onHookError(feature, describe(param), t)
        } finally {
            Reentry.exit()
        }
    }

    protected open fun before(param: MethodHookParam, policy: EffectivePolicy) {}

    protected open fun after(param: MethodHookParam, policy: EffectivePolicy) {}

    protected fun describe(param: MethodHookParam): String {
        val m = param.method
        return m.declaringClass.simpleName + "." + m.name
    }

    companion object {
        private const val KEY_OUTER = "appguard.outer"
    }
}

/** Hooks every declared overload of [name]; returns how many were hooked. */
internal fun hookAllDeclared(cls: Class<*>, name: String, hook: XC_MethodHook): Int {
    var n = 0
    for (m: Method in cls.declaredMethods) {
        if (m.name != name || java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
        XposedBridge.hookMethod(m, hook)
        n++
    }
    return n
}
