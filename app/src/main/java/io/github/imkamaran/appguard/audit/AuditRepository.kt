package io.github.imkamaran.appguard.audit

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.imkamaran.appguard.policy.AuditAction
import io.github.imkamaran.appguard.policy.AuditEvent
import io.github.imkamaran.appguard.policy.Category
import io.github.imkamaran.appguard.policy.GlobalSettings
import io.github.imkamaran.appguard.policy.Mode
import io.github.imkamaran.appguard.policy.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuditFilter(
    val query: String = "",
    val categories: Set<Category> = emptySet(),
    val packageName: String? = null,
    val actions: Set<AuditAction> = emptySet(),
    /** Only events at or after this time; 0 = no limit. */
    val since: Long = 0L,
    val limit: Int = 500,
)

/** Last hook-initialization report for one (package, feature). */
data class HookStatus(
    val packageName: String,
    val feature: String,
    val installed: Boolean,
    val detail: String,
    val timestamp: Long,
    val pid: Int,
)

/**
 * Local, bounded audit store. Writes happen on binder threads of AuditProvider (never in hooked
 * apps); retention by age and count is enforced on every insert.
 */
class AuditRepository(context: Context) {
    private val helper = Helper(context.applicationContext)

    /** Incremented on every change so UIs can re-query. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun insert(events: List<AuditEvent>, settings: GlobalSettings) {
        if (events.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (e in events) {
                if (e.category == Category.SYSTEM && e.api.startsWith("hook:")) {
                    db.insertWithOnConflict(T_HOOKS, null, ContentValues().apply {
                        put("pkg", e.packageName)
                        put("feature", e.api.removePrefix("hook:"))
                        put("ok", if (e.action == AuditAction.HOOK_INSTALLED) 1 else 0)
                        put("detail", e.detail)
                        put("ts", e.timestamp)
                        put("pid", e.pid)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.insert(T_EVENTS, null, ContentValues().apply {
                    put("ts", e.timestamp)
                    put("pkg", e.packageName)
                    put("pid", e.pid)
                    put("category", e.category.name)
                    put("api", e.api)
                    put("policy", e.policy.name)
                    put("action", e.action.name)
                    put("outcome", e.outcome.name)
                    put("detail", e.detail)
                    put("count", e.count)
                })
            }
            enforceRetention(db, settings)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _version.value++
    }

    fun enforceRetention(settings: GlobalSettings) {
        val db = helper.writableDatabase
        enforceRetention(db, settings)
        _version.value++
    }

    private fun enforceRetention(db: SQLiteDatabase, settings: GlobalSettings) {
        val cutoff = System.currentTimeMillis() - settings.retentionDays * 86_400_000L
        db.delete(T_EVENTS, "ts < ?", arrayOf(cutoff.toString()))
        db.execSQL(
            "DELETE FROM $T_EVENTS WHERE id NOT IN (SELECT id FROM $T_EVENTS ORDER BY id DESC LIMIT ?)",
            arrayOf(settings.maxEvents),
        )
    }

    fun query(filter: AuditFilter): List<AuditEvent> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (filter.query.isNotBlank()) {
            where += "(pkg LIKE ? ESCAPE '\\' OR api LIKE ? ESCAPE '\\' OR detail LIKE ? ESCAPE '\\')"
            val like = "%" + filter.query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(3) { args += like }
        }
        if (filter.categories.isNotEmpty()) {
            where += "category IN (${filter.categories.joinToString { "?" }})"
            args += filter.categories.map { it.name }
        }
        if (filter.actions.isNotEmpty()) {
            where += "action IN (${filter.actions.joinToString { "?" }})"
            args += filter.actions.map { it.name }
        }
        filter.packageName?.let { where += "pkg = ?"; args += it }
        if (filter.since > 0) { where += "ts >= ?"; args += filter.since.toString() }

        val sql = buildString {
            append("SELECT ts, pkg, pid, category, api, policy, action, outcome, detail, count FROM $T_EVENTS")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY id DESC LIMIT ").append(filter.limit.coerceIn(1, 100_000))
        }
        return helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.toEvent()) }
        }
    }

    fun eventPackages(): List<String> =
        helper.readableDatabase.rawQuery("SELECT DISTINCT pkg FROM $T_EVENTS ORDER BY pkg", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun hookStatus(packageName: String): List<HookStatus> =
        helper.readableDatabase.rawQuery(
            "SELECT pkg, feature, ok, detail, ts, pid FROM $T_HOOKS WHERE pkg = ?", arrayOf(packageName),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(HookStatus(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getLong(4), c.getInt(5)))
            }
        }

    fun count(): Long = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_EVENTS", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    fun clear(packageName: String? = null) {
        val db = helper.writableDatabase
        if (packageName == null) {
            db.delete(T_EVENTS, null, null)
            db.delete(T_HOOKS, null, null)
        } else {
            db.delete(T_EVENTS, "pkg = ?", arrayOf(packageName))
            db.delete(T_HOOKS, "pkg = ?", arrayOf(packageName))
        }
        _version.value++
    }

    fun recentEventTimestamps(since: Long): List<Pair<Long, Category>> {
        val sql = "SELECT ts, category FROM $T_EVENTS WHERE ts >= ? AND category != 'SYSTEM'"
        return helper.readableDatabase.rawQuery(sql, arrayOf(since.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val cat = try { Category.valueOf(c.getString(1)) } catch (_: Exception) { null }
                    if (cat != null) add(c.getLong(0) to cat)
                }
            }
        }
    }

    fun lastHookReportTime(packageName: String): Long? =
        helper.readableDatabase.rawQuery("SELECT MAX(ts) FROM $T_HOOKS WHERE pkg = ?", arrayOf(packageName)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun allLastHookReportTimes(): Map<String, Long> =
        helper.readableDatabase.rawQuery("SELECT pkg, MAX(ts) FROM $T_HOOKS GROUP BY pkg", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    private fun Cursor.toEvent() = AuditEvent(
        timestamp = getLong(0),
        packageName = getString(1),
        pid = getInt(2),
        category = enumOr(getString(3), Category.SYSTEM),
        api = getString(4),
        policy = enumOr(getString(5), Mode.ALLOW),
        action = enumOr(getString(6), AuditAction.OBSERVED),
        outcome = enumOr(getString(7), Outcome.UNDETERMINED),
        detail = getString(8) ?: "",
        count = getInt(9),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private class Helper(context: Context) : SQLiteOpenHelper(context, "audit.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $T_EVENTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL, pkg TEXT NOT NULL, pid INTEGER NOT NULL,
                    category TEXT NOT NULL, api TEXT NOT NULL, policy TEXT NOT NULL,
                    action TEXT NOT NULL, outcome TEXT NOT NULL, detail TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 1)""",
            )
            db.execSQL("CREATE INDEX idx_events_ts ON $T_EVENTS(ts)")
            db.execSQL("CREATE INDEX idx_events_pkg ON $T_EVENTS(pkg)")
            db.execSQL(
                """CREATE TABLE $T_HOOKS (
                    pkg TEXT NOT NULL, feature TEXT NOT NULL, ok INTEGER NOT NULL,
                    detail TEXT NOT NULL, ts INTEGER NOT NULL, pid INTEGER NOT NULL,
                    PRIMARY KEY (pkg, feature))""",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $T_EVENTS")
            db.execSQL("DROP TABLE IF EXISTS $T_HOOKS")
            onCreate(db)
        }
    }

    private companion object {
        const val T_EVENTS = "events"
        const val T_HOOKS = "hook_status"
    }
}
