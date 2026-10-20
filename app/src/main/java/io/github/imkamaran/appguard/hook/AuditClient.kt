package io.github.imkamaran.appguard.hook

import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.app.AndroidAppHelper
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditCodec
import io.github.imkamaran.appguard.policy.AuditEvent
import io.github.imkamaran.appguard.policy.AuditLevel
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends audit events from a hooked process to AppGuard's AuditProvider.
 *
 * Hooked threads only touch an in-memory map: identical events are coalesced into a counter and
 * the map is bounded, so recording never blocks or allocates without limit. A single daemon thread
 * wakes only when there is something to send, waits [FLUSH_DELAY_MS] to batch, then makes one
 * provider call. Events are dropped (and counted) rather than ever slowing the app down.
 */
internal class AuditClient(private val packageName: String) {

    private class Pending(val event: AuditEvent, val count: AtomicInteger = AtomicInteger(1))

    /** Coalescing key; a data class avoids building a string on every hooked call. */
    private data class Key(
        val category: Category,
        val api: String,
        val policy: Mode,
        val action: AuditAction,
        val outcome: Outcome,
        val detail: String,
    )

    @Volatile
    private var pending = ConcurrentHashMap<Key, Pending>()
    private val kick = ArrayBlockingQueue<Unit>(1)
    private val dropped = AtomicInteger()
    private val retry = ArrayList<AuditEvent>()
    private var worker: Thread? = null
    private var deliveryProblemLogged = false

    /**
     * Records an event if [level] allows it. Hook-status events (SYSTEM category) are always kept so
     * the companion app can show whether hooks initialized, even with auditing off.
     */
    fun record(
        level: AuditLevel,
        category: Category,
        api: String,
        policy: Mode,
        action: AuditAction,
        outcome: Outcome,
        detail: String = "",
    ) {
        val keep = when {
            category == Category.SYSTEM -> true
            level == AuditLevel.OFF -> false
            level == AuditLevel.ACTIONS -> action != AuditAction.OBSERVED
            else -> true
        }
        if (!keep) return
        val key = Key(category, api, policy, action, outcome, detail)
        val map = pending
        val existing = map[key]
        if (existing != null) {
            existing.count.incrementAndGet()
            return
        }
        if (map.size >= MAX_PENDING_KEYS) {
            dropped.incrementAndGet()
            return
        }
        val event = AuditEvent(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            pid = Process.myPid(),
            category = category,
            api = api,
            policy = policy,
            action = action,
            outcome = outcome,
            detail = detail,
        )
        if (map.putIfAbsent(key, Pending(event)) != null) map[key]?.count?.incrementAndGet()
        ensureWorker()
        kick.offer(Unit)
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker != null) return
        worker = Thread({ loop() }, "AppGuard-audit").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun loop() {
        while (true) {
            try {
                kick.take()
                Thread.sleep(FLUSH_DELAY_MS)
                flush()
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                HookLog.limited("audit-loop", "audit worker error", t)
            }
        }
    }

    private fun flush() {
        val batch = ArrayList<AuditEvent>(retry)
        retry.clear()
        val taken = pending
        pending = ConcurrentHashMap()
        for (p in taken.values) batch += p.event.copy(count = p.count.get())
        val lost = dropped.getAndSet(0)
        if (lost > 0) {
            batch += AuditEvent(
                System.currentTimeMillis(), packageName, Process.myPid(), Category.SYSTEM, "audit.dropped",
                Mode.ALLOW, AuditAction.HOOK_ERROR, Outcome.FAILED, "$lost events dropped (buffer full)", lost,
            )
        }
        if (batch.isEmpty()) return

        val app = AndroidAppHelper.currentApplication()
        if (app == null) {
            // Too early in process start; keep a bounded backlog and try again shortly.
            keepForRetry(batch)
            kick.offer(Unit)
            return
        }
        for (chunk in batch.chunked(AuditCodec.MAX_BATCH)) {
            try {
                val extras = Bundle().apply { putString(EXTRA_EVENTS, AuditCodec.encodeBatch(chunk)) }
                app.contentResolver.call(AUDIT_URI, METHOD_APPEND, null, extras)
            } catch (t: Throwable) {
                if (!deliveryProblemLogged) {
                    deliveryProblemLogged = true
                    HookLog.w("cannot deliver audit events from $packageName to AppGuard", t)
                }
                return
            }
        }
    }

    private fun keepForRetry(batch: List<AuditEvent>) {
        retry.addAll(batch.takeLast(MAX_RETRY))
    }

    companion object {
        const val AUTHORITY = "io.github.imkamaran.appguard.audit"
        val AUDIT_URI: Uri = Uri.parse("content://$AUTHORITY")
        const val METHOD_APPEND = "append"
        const val EXTRA_EVENTS = "events"

        private const val FLUSH_DELAY_MS = 1_500L
        private const val MAX_PENDING_KEYS = 256
        private const val MAX_RETRY = 200
    }
}
