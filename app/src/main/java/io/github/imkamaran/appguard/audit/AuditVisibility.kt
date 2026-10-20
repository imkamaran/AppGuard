package io.github.imkamaran.appguard.audit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.imkamaran.appguard.AppGuardApp

/**
 * Since Android 11, an app cannot resolve another app's ContentProvider unless that app is visible
 * to it, so hooked apps get "Unknown authority" when reporting audit events. Granting a URI
 * permission on our provider is one of the documented ways to make AppGuard visible to a package.
 *
 * The grant is read-only on an authority whose query() returns nothing; the only effect that
 * matters is visibility. Grants do not survive a reboot, so they are renewed on every publish and
 * after boot.
 */
object AuditVisibility {
    private val uri: Uri = Uri.parse("content://io.github.imkamaran.appguard.audit")

    fun grant(context: Context, packages: Collection<String>) {
        for (pkg in packages) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: RuntimeException) {
                // Package uninstalled or not visible to us; nothing to report to.
                Log.w("AppGuard", "audit visibility grant to $pkg failed: $e")
            }
        }
    }
}

/** Renews visibility grants after boot so audit events flow before AppGuard is opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        (context.applicationContext as? AppGuardApp)?.policies?.republish()
    }
}
