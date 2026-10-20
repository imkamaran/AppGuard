package io.github.imkamaran.appguard

import android.app.Application
import io.github.imkamaran.appguard.apps.InstalledAppsRepository
import io.github.imkamaran.appguard.audit.AuditRepository
import io.github.imkamaran.appguard.config.PolicyRepository
import io.github.imkamaran.appguard.service.NotificationHelper

class AppGuardApp : Application() {
    // Lazy: AuditProvider can receive binder calls before onCreate() finishes.
    val policies: PolicyRepository by lazy { PolicyRepository(this) }
    val audit: AuditRepository by lazy { AuditRepository(this) }
    val apps: InstalledAppsRepository by lazy { InstalledAppsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        policies.republish()
    }
}
