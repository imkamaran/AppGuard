package io.github.imkamaran.appguard.hook

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File

/**
 * Crash-loop protection. If an app crashes (uncaught Java exception) within [EARLY_MS] of start-up
 * [MAX_EARLY_CRASHES] times in a row while the same policy revision is applied, the policy is
 * suspended for that app until the user saves it again (which changes the revision).
 *
 * The existing default handler is chained, never replaced, so the app's normal crash handling and
 * reporting still run. State is a one-line file in the app's own no_backup directory.
 */
internal class CrashGuard(dataDir: String?, private val revision: Long) {
    private val file: File? = dataDir?.let { File(File(it, "no_backup"), "appguard_crashguard") }
    private val startedAt = SystemClock.uptimeMillis()

    /** True when this policy revision already crashed the app [MAX_EARLY_CRASHES] times. */
    fun isTripped(): Boolean {
        val (rev, count) = read() ?: return false
        return rev == revision && count >= MAX_EARLY_CRASHES
    }

    /** Starts counting early crashes for this start and clears the counter once the app is stable. */
    fun arm() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (SystemClock.uptimeMillis() - startedAt < EARLY_MS) {
                    val (rev, count) = read() ?: (revision to 0)
                    write(if (rev == revision) count + 1 else 1)
                }
            } catch (_: Throwable) {
                // Never interfere with the app's own crash path.
            }
            previous?.uncaughtException(thread, error)
        }
        val timer = HandlerThread("AppGuard-crashguard").apply { start() }
        Handler(timer.looper).postDelayed({
            if (read()?.second != 0) write(0)
            timer.quitSafely()
        }, EARLY_MS)
    }

    private fun read(): Pair<Long, Int>? = try {
        val parts = file?.takeIf { it.exists() }?.readText()?.trim()?.split(';') ?: emptyList()
        if (parts.size == 2) parts[0].toLong() to parts[1].toInt() else null
    } catch (_: Exception) {
        null
    }

    private fun write(count: Int) {
        try {
            val f = file ?: return
            f.parentFile?.mkdirs()
            f.writeText("$revision;$count")
        } catch (_: Exception) {
            // Credential storage locked (direct boot) or read-only: the guard simply does nothing.
        }
    }

    companion object {
        const val MAX_EARLY_CRASHES = 3
        const val EARLY_MS = 60_000L
    }
}
