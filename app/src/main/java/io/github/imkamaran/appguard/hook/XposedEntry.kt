package io.github.imkamaran.appguard.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.imkamaran.appguard.policy.PolicySchema
import io.github.imkamaran.appguard.policy.ProtectedPackages
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed entry point (assets/xposed_init). Runs in every process LSPosed injects the module into,
 * i.e. the apps the user put in AppGuard's scope.
 *
 * Nothing is hooked unless the process's own package has an enabled, valid policy at start-up, so
 * scoped apps without a policy keep their original behavior at zero runtime cost.
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            dispatch(lpparam)
        } catch (t: Throwable) {
            HookLog.w("initialization failed in ${lpparam.packageName}; app left unmodified", t)
        }
    }

    private fun dispatch(lp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lp.packageName ?: return
        if (pkg == PolicySchema.MODULE_PACKAGE) {
            if (lp.isFirstApplication) markModuleActive(lp.classLoader)
            return
        }
        // Other packages loaded into a host process (e.g. a WebView provider) never get policies.
        if (!lp.isFirstApplication) return
        if (ProtectedPackages.isProtected(pkg)) return
        if (!started.compareAndSet(false, true)) return

        val source = RemotePolicySource.open(pkg) ?: return
        val resolution = source.current()
        if (resolution == null) {
            source.lastProblem?.let { HookLog.w("no policy applied to $pkg: $it") }
            return
        }
        val runtime = HookRuntime(pkg, source, AuditClient(pkg))
        val guard = CrashGuard(lp.appInfo?.dataDir, resolution.effective.revision)
        if (guard.isTripped()) {
            HookLog.w("policy for $pkg suspended by crash guard; app left unmodified")
            runtime.reportCrashGuardTripped()
            return
        }
        guard.arm()
        runtime.reportCrashGuardArmed()
        HookLog.i("applying policy to $pkg (process ${lp.processName})")
        runtime.installAll(resolution)
    }

    private fun markModuleActive(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClass("io.github.imkamaran.appguard.ModuleStatus", classLoader)
        XposedHelpers.setStaticIntField(cls, "hookApiVersion", XposedBridge.getXposedVersion().coerceAtLeast(1))
    }

    private companion object {
        val started = AtomicBoolean()
    }
}
