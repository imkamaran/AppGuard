package io.github.imkamaran.appguard.hook

import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences
import io.github.imkamaran.appguard.policy.PolicyResolver
import io.github.imkamaran.appguard.policy.PolicySchema
import io.github.imkamaran.appguard.policy.Resolution

/**
 * Reads this process's policy from the module's LSPosed-managed preferences. Only the record keyed
 * by the process's own package is ever decoded. Any read or parse failure yields null, which every
 * hook treats as "pass through" (fail open).
 */
internal class RemotePolicySource private constructor(
    private val packageName: String,
    private val prefs: XSharedPreferences,
) {
    private val lock = Any()

    @Volatile
    private var cached: Resolution? = null
    private var lastCheck = 0L

    /** Why the last load produced no policy, for diagnostics. */
    @Volatile
    var lastProblem: String? = null
        private set

    /**
     * Current resolution. Re-reads the file at most every [RELOAD_INTERVAL_MS] and only when it
     * changed, so policy edits reach running processes without a restart.
     */
    fun current(): Resolution? {
        val now = SystemClock.uptimeMillis()
        if (now - lastCheck >= RELOAD_INTERVAL_MS) {
            synchronized(lock) {
                if (now - lastCheck >= RELOAD_INTERVAL_MS) {
                    lastCheck = now
                    try {
                        if (prefs.hasFileChanged()) {
                            prefs.reload()
                            cached = load()
                        }
                    } catch (t: Throwable) {
                        lastProblem = "reload failed: $t"
                        cached = null
                    }
                }
            }
        }
        return cached
    }

    private fun load(): Resolution? {
        return try {
            val schema = prefs.getInt(PolicySchema.KEY_SCHEMA, 0)
            if (!PolicySchema.isSupportedSchema(schema)) {
                lastProblem = "unsupported schema $schema"
                return null
            }
            val global = PolicySchema.decodeGlobal(prefs.getString(PolicySchema.KEY_GLOBAL, null))
            if (global == null) {
                lastProblem = "corrupt global settings"
                return null
            }
            val raw = prefs.getString(PolicySchema.policyKey(packageName), null)
            val policy = PolicySchema.decodePolicy(packageName, raw)
            if (raw != null && policy == null) lastProblem = "corrupt policy record" else lastProblem = null
            PolicyResolver.resolve(packageName, policy, global)
        } catch (t: Throwable) {
            lastProblem = "load failed: $t"
            null
        }
    }

    companion object {
        const val RELOAD_INTERVAL_MS = 2_000L

        /** @return null when the preference file is not readable from this process */
        fun open(packageName: String): RemotePolicySource? {
            val prefs = XSharedPreferences(PolicySchema.MODULE_PACKAGE, PolicySchema.PREFS_NAME)
            if (!prefs.file.canRead()) {
                HookLog.w("cannot read ${prefs.file} in $packageName; open AppGuard once so it publishes its configuration")
                return null
            }
            return RemotePolicySource(packageName, prefs).apply {
                cached = load()
                lastCheck = SystemClock.uptimeMillis()
            }
        }
    }
}
