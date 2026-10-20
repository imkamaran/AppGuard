package io.github.imkamaran.appguard.policy

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Serializable
enum class AuditAction {
    /** The call was seen and passed through unmodified. */
    OBSERVED,
    /** The policy restricted the call (e.g. returned nothing). */
    RESTRICTED,
    /** The policy replaced the result with a synthetic value. */
    SIMULATED,
    /** A hook was installed in the process. */
    HOOK_INSTALLED,
    /** A hook could not be installed; the feature is inactive in that process. */
    HOOK_FAILED,
    /** A hook threw at runtime; original behavior was preserved. */
    HOOK_ERROR,
}

/**
 * Whether the [AuditAction] verifiably took effect. SUCCEEDED is only reported when the hook
 * itself produced the value the caller receives (or read back the change it made); a call that
 * leaves the outcome to code the hook does not control is UNDETERMINED.
 */
@Serializable
enum class Outcome { SUCCEEDED, FAILED, UNDETERMINED }

@Serializable
data class AuditEvent(
    val timestamp: Long,
    val packageName: String,
    val pid: Int,
    val category: Category,
    /** Intercepted API or event type, e.g. "LocationManager.getLastKnownLocation". */
    val api: String,
    val policy: Mode,
    val action: AuditAction,
    val outcome: Outcome,
    /** Non-sensitive context; redacted values only, never clipboard contents or real identifiers. */
    val detail: String = "",
    /** Number of identical events coalesced into this one. */
    val count: Int = 1,
)

object AuditCodec {
    const val MAX_BATCH = 100
    const val MAX_API_CHARS = 128
    const val MAX_DETAIL_CHARS = 256
    const val MAX_PAYLOAD_CHARS = 128 * 1024

    private val listSerializer = ListSerializer(AuditEvent.serializer())

    fun encodeBatch(events: List<AuditEvent>): String =
        PolicySchema.json.encodeToString(listSerializer, events.take(MAX_BATCH))

    /** Decodes and clamps a batch; returns an empty list for anything malformed. */
    fun decodeBatch(raw: String?): List<AuditEvent> {
        if (raw.isNullOrEmpty() || raw.length > MAX_PAYLOAD_CHARS) return emptyList()
        val events = try {
            PolicySchema.json.decodeFromString(listSerializer, raw)
        } catch (_: SerializationException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        return events.take(MAX_BATCH).map { sanitize(it) }
    }

    fun sanitize(e: AuditEvent): AuditEvent = e.copy(
        api = e.api.take(MAX_API_CHARS),
        detail = e.detail.take(MAX_DETAIL_CHARS),
        count = e.count.coerceIn(1, 1_000_000),
    )
}

/** Redaction helpers used when composing audit details. */
object Redact {
    const val PLACEHOLDER = "[redacted]"

    /** Coordinates are only ever shown rounded to ~1 km, and only when redaction is off. */
    fun coordinates(lat: Double, lon: Double, redact: Boolean): String =
        if (redact) PLACEHOLDER else String.format(Locale.US, "%.2f,%.2f", lat, lon)

    fun value(value: String?, redact: Boolean): String =
        if (redact || value == null) PLACEHOLDER else value.take(64)
}

/** Formats audit events for export. */
object AuditExport {
    val CSV_HEADER = listOf(
        "timestamp_utc", "timestamp_ms", "package", "pid", "category", "api", "policy", "action", "outcome", "count", "detail",
    )

    fun toJson(events: List<AuditEvent>): String =
        PolicySchema.json.encodeToString(ListSerializer(AuditEvent.serializer()), events)

    fun toCsv(events: List<AuditEvent>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER.joinToString(",")).append("\r\n")
        val fmt = isoFormat()
        for (e in events) {
            val row = listOf(
                fmt.format(Date(e.timestamp)), e.timestamp.toString(), e.packageName, e.pid.toString(),
                e.category.name, e.api, e.policy.name, e.action.name, e.outcome.name, e.count.toString(), e.detail,
            )
            sb.append(row.joinToString(",") { csvField(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** RFC 4180 quoting, plus a leading quote for cells a spreadsheet would treat as a formula. */
    fun csvField(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
