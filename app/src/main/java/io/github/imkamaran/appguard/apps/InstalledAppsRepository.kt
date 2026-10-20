package io.github.imkamaran.appguard.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import io.github.imkamaran.appguard.policy.PolicySchema
import io.github.imkamaran.appguard.policy.ProtectedPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** Non-null when AppGuard refuses to apply policies to this package. */
    val protectedReason: String?,
)

class InstalledAppsRepository(context: Context) {
    private val pm: PackageManager = context.applicationContext.packageManager
    private val icons = LruCache<String, Bitmap>(256)

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != PolicySchema.MODULE_PACKAGE }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = it.loadLabel(pm).toString().ifBlank { it.packageName },
                    isSystem = it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    protectedReason = ProtectedPackages.reason(it.packageName),
                )
            }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()
    }

    suspend fun find(packageName: String): InstalledApp? = withContext(Dispatchers.IO) {
        try {
            val info = pm.getApplicationInfo(packageName, 0)
            InstalledApp(
                packageName = packageName,
                label = info.loadLabel(pm).toString().ifBlank { packageName },
                isSystem = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                protectedReason = ProtectedPackages.reason(packageName),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    suspend fun icon(packageName: String, sizePx: Int): Bitmap? {
        icons.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val d = pm.getApplicationIcon(packageName)
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, sizePx, sizePx)
                d.draw(Canvas(bmp))
                icons.put(packageName, bmp)
                bmp
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}

/** Optional root helper: restarts a target app so start-up-time policies take effect. */
object RootShell {
    suspend fun forceStop(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!PolicySchema.isValidPackageName(packageName)) return@withContext Result.failure(IllegalArgumentException("invalid package"))
        runCatching {
            val p = ProcessBuilder("su", "-c", "am force-stop $packageName").redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroy()
                error("timed out waiting for root")
            }
            if (p.exitValue() != 0) error(out.ifBlank { "su exited with ${p.exitValue()}" })
        }
    }
}
