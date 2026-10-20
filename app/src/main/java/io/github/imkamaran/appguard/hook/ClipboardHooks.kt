package io.github.imkamaran.appguard.hook

import android.content.ClipboardManager
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome

/**
 * android.content.ClipboardManager read-side controls. These public methods run in the app process
 * and query the clipboard service over binder; Restrict answers in a before-hook, so the clip is
 * never fetched into the process and the system's "app pasted" notice is not triggered.
 *
 * Write methods (setPrimaryClip, setText, clearPrimaryClip) are deliberately not hooked.
 * Clipboard contents are never inspected or logged.
 */
internal object ClipboardHooks {
    private val depth = ThreadLocal<IntArray>()

    /** Read method → what it returns when restricted (an empty clipboard). */
    private val READS: Map<String, Any?> = linkedMapOf(
        "getPrimaryClip" to null,
        "getPrimaryClipDescription" to null,
        "getText" to null,
        "hasPrimaryClip" to false,
        "hasText" to false,
    )

    fun install(rt: HookRuntime): String {
        val cls = ClipboardManager::class.java
        val counts = linkedMapOf<String, Int>()
        for ((name, restricted) in READS) counts[name] = hookAllDeclared(cls, name, Read(rt, name, restricted))
        check(counts.getValue("getPrimaryClip") > 0) { "ClipboardManager.getPrimaryClip not found" }
        return "overloads hooked: " + counts.entries.joinToString { "${it.key}=${it.value}" }
    }

    private class Read(rt: HookRuntime, name: String, private val restricted: Any?) : GuardedHook(rt, Feature.CLIPBOARD, depth) {
        private val api = "ClipboardManager.$name"

        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            if (policy.clipboard == Mode.RESTRICT) {
                param.result = restricted
                runtime.record(policy, Category.CLIPBOARD, api, Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "returned empty clipboard")
            } else {
                runtime.record(policy, Category.CLIPBOARD, api, policy.clipboard, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
            }
        }
    }
}
