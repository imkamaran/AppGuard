package io.github.imkamaran.appguard.audit

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import io.github.imkamaran.appguard.AppGuardApp
import io.github.imkamaran.appguard.policy.AuditCodec
import io.github.imkamaran.appguard.service.NotificationHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives audit batches from hooked processes via ContentResolver.call("append").
 *
 * Calls are authenticated by binder identity: an event is stored only if its package belongs to
 * the calling uid and that package currently has an enabled policy. The pid is replaced with the
 * caller's real pid. Per-uid rate limiting bounds what a misbehaving process can write.
 */
class AuditProvider : ContentProvider() {

    private class Budget(var windowStart: Long, var used: Int)

    private val budgets = ConcurrentHashMap<Int, Budget>()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_APPEND) return null
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val ctx = context ?: return null
        val app = ctx.applicationContext as? AppGuardApp ?: return null

        val events = AuditCodec.decodeBatch(extras?.getString(EXTRA_EVENTS))
        if (events.isEmpty()) return result(0)

        val ownPackages = Binder.clearCallingIdentity().let { token ->
            try {
                ctx.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
        val config = app.policies.state.value
        val accepted = events
            .filter { it.packageName in ownPackages && config.policies[it.packageName]?.enabled == true }
            .map { it.copy(pid = pid) }
        if (accepted.isEmpty() || !consumeBudget(uid, accepted.size)) return result(0)

        app.audit.insert(accepted, config.global)
        if (config.global.notifyRestricted) NotificationHelper.notifyRestricted(ctx, accepted)
        return result(accepted.size)
    }

    private fun consumeBudget(uid: Int, n: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val b = budgets.getOrPut(uid) { Budget(now, 0) }
        synchronized(b) {
            if (now - b.windowStart > 60_000) {
                b.windowStart = now
                b.used = 0
            }
            if (b.used + n > MAX_EVENTS_PER_MINUTE) return false
            b.used += n
            return true
        }
    }

    private fun result(n: Int) = Bundle().apply { putInt("accepted", n) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_APPEND = "append"
        const val EXTRA_EVENTS = "events"
        private const val MAX_EVENTS_PER_MINUTE = 600
    }
}
