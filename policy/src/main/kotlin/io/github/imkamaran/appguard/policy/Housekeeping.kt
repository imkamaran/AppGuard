package io.github.imkamaran.appguard.policy

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.TimeZone

/** Whole-configuration backup file: `{"format":"appguard-backup","version":1,...,"config":{...}}`. */
@Serializable
data class ConfigBackup(
    // No defaults: an arbitrary JSON object must not decode as an empty (wiping) backup.
    val format: String,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val config: ConfigState,
) {
    companion object {
        const val FORMAT = "appguard-backup"
        const val VERSION = 1
        const val MAX_CHARS = 2 * 1024 * 1024
    }
}

sealed interface ImportResult {
    /** [config] is safe to adopt; [notes] lists anything that was dropped or adjusted. */
    data class Ok(val config: ConfigState, val notes: List<String>) : ImportResult
    data class Rejected(val reason: String) : ImportResult
}

object BackupCodec {
    private val pretty = Json(PolicySchema.json) { prettyPrint = true }

    fun export(config: ConfigState, now: Long, appVersion: String): String =
        pretty.encodeToString(ConfigBackup.serializer(), ConfigBackup(format = ConfigBackup.FORMAT, exportedAt = now, appVersion = appVersion, config = config))

    /** Validates a backup. Never partially applies: either a complete, sanitized config or a rejection. */
    fun import(raw: String?): ImportResult {
        if (raw.isNullOrBlank()) return ImportResult.Rejected("The file is empty.")
        if (raw.length > ConfigBackup.MAX_CHARS) return ImportResult.Rejected("The file is too large to be an AppGuard backup.")
        val backup = try {
            PolicySchema.json.decodeFromString(ConfigBackup.serializer(), raw)
        } catch (_: SerializationException) {
            return ImportResult.Rejected("The file is not a valid AppGuard backup.")
        } catch (_: IllegalArgumentException) {
            return ImportResult.Rejected("The file is not a valid AppGuard backup.")
        }
        if (backup.format != ConfigBackup.FORMAT) return ImportResult.Rejected("The file is not an AppGuard backup.")
        if (backup.version > ConfigBackup.VERSION) return ImportResult.Rejected("The backup was made by a newer AppGuard (format ${backup.version}).")
        if (!PolicySchema.isSupportedSchema(backup.config.schema)) {
            return ImportResult.Rejected("The backup uses policy schema ${backup.config.schema}, which this version does not support.")
        }

        val notes = mutableListOf<String>()
        val policies = LinkedHashMap<String, AppPolicy>()
        for ((key, p) in backup.config.policies) {
            when {
                key != p.packageName -> notes += "Skipped a policy stored under a mismatched key ($key)."
                !PolicySchema.isValidPackageName(key) -> notes += "Skipped a policy with an invalid package name."
                ProtectedPackages.isProtected(key) -> notes += "Skipped $key: AppGuard never applies policies to it."
                else -> policies[key] = p
            }
        }
        val templates = backup.config.templates
            .filter { !it.builtIn }
            .distinctBy { it.id }
            .take(Templates.MAX_USER_TEMPLATES)
            .map { Templates.sanitize(it) }
        if (templates.size < backup.config.templates.count { !it.builtIn }) notes += "Some duplicate or excess templates were skipped."

        return ImportResult.Ok(
            backup.config.copy(
                schema = PolicySchema.SCHEMA_VERSION,
                global = PolicySchema.sanitize(backup.config.global),
                policies = policies,
                templates = templates,
            ),
            notes,
        )
    }
}

/** Decides whether an app must be restarted for its saved policy to be fully in effect. */
object RestartAdvisor {
    /**
     * Settings read only at process start: whether hooks are installed at all (enabled), the
     * android.os.Build profile, and the policy revision the crash guard tracks.
     */
    fun startupFieldsDiffer(old: AppPolicy?, new: AppPolicy): Boolean {
        if (old == null) return new.enabled
        if (!old.enabled && new.enabled) return true
        val o = old.identifiers
        val n = new.identifiers
        return o.deviceInfo != n.deviceInfo || (n.deviceInfo != Mode.ALLOW && o.deviceProfile != n.deviceProfile)
    }

    /** [lastStartReportAt]: newest hook report from the app's process, or null if none. */
    fun needsRestart(policy: AppPolicy, lastStartReportAt: Long?): Boolean {
        if (!policy.enabled) return false
        if (lastStartReportAt == null) return true
        return lastStartReportAt < policy.startupChangedAt
    }
}

/** Limits access notifications to one per (package, category) per [windowMs]. */
class NotificationThrottle(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun allow(packageName: String, category: Category, now: Long): Boolean {
        val key = "$packageName|$category"
        val previous = last[key]
        if (previous != null && now - previous < windowMs) return false
        last[key] = now
        if (last.size > 512) last.entries.removeIf { now - it.value >= windowMs }
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
    }
}

/** Calendar-day bucketing for the privacy report (local time, DST-safe). */
object ReportMath {
    fun startOfDay(ts: Long, tz: TimeZone): Long = Calendar.getInstance(tz).run {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** Start of the first of [days] calendar days ending today. */
    fun windowStart(now: Long, days: Int, tz: TimeZone): Long = Calendar.getInstance(tz).run {
        timeInMillis = startOfDay(now, tz)
        add(Calendar.DAY_OF_YEAR, -(days - 1))
        timeInMillis
    }

    /** 0 = first day of the window … days-1 = today; -1 when outside the window. */
    fun dayIndex(ts: Long, now: Long, days: Int, tz: TimeZone): Int {
        val target = startOfDay(ts, tz)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = windowStart(now, days, tz) }
        for (i in 0 until days) {
            if (cal.timeInMillis == target) return i
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return -1
    }
}
