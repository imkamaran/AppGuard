package io.github.imkamaran.appguard.hook

import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.ContactSources
import io.github.imkamaran.appguard.policy.EffectivePolicy
import io.github.imkamaran.appguard.policy.Feature
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome

/**
 * Contacts, call log and calendar reads. Apps read these providers through ContentResolver.query,
 * which runs in the app process. A restricted query is answered with an empty cursor that has the
 * columns the app asked for, so code that looks up column indexes keeps working.
 *
 * When the app passes no projection the columns are unknown up front: the real query runs, only
 * its column names are kept and the cursor is closed before the app can read a row.
 */
internal object ContactsHooks {
    private val depth = ThreadLocal<IntArray>()
    private val WATCHED: Set<String> = ContactSources.authoritiesOf(ContactSources.ALL.map { it.id }.toSet())
    private const val KEY_EMPTY_AFTER = "appguard.contacts.empty"

    fun install(rt: HookRuntime): String {
        val n = hookAllDeclared(ContentResolver::class.java, "query", Query(rt))
        check(n > 0) { "ContentResolver.query not found" }
        return "ContentResolver.query overloads hooked: $n"
    }

    private class Query(rt: HookRuntime) : GuardedHook(rt, Feature.CONTACTS, depth) {
        override fun before(param: MethodHookParam, policy: EffectivePolicy) {
            val authority = (param.args.getOrNull(0) as? Uri)?.authority ?: return
            if (authority !in WATCHED) return
            val api = "ContentResolver.query($authority)"
            if (policy.contacts != Mode.RESTRICT || authority !in policy.restrictedContactAuthorities) {
                runtime.record(policy, Category.CONTACTS, api, policy.contacts, AuditAction.OBSERVED, Outcome.SUCCEEDED, "passed through")
                return
            }
            @Suppress("UNCHECKED_CAST")
            val projection = param.args.getOrNull(1) as? Array<String>
            if (projection != null) {
                param.result = MatrixCursor(projection)
                runtime.record(policy, Category.CONTACTS, api, Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "returned no rows")
            } else {
                param.setObjectExtra(KEY_EMPTY_AFTER, api)
            }
        }

        override fun after(param: MethodHookParam, policy: EffectivePolicy) {
            val api = param.getObjectExtra(KEY_EMPTY_AFTER) as? String ?: return
            val cursor = param.result as? Cursor ?: return
            val columns = cursor.columnNames
            cursor.close()
            param.result = MatrixCursor(columns)
            runtime.record(policy, Category.CONTACTS, api, Mode.RESTRICT, AuditAction.RESTRICTED, Outcome.SUCCEEDED, "returned no rows")
        }
    }
}
