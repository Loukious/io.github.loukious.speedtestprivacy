package io.github.loukious.speedtestprivacy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.DiscoveryNative
import io.github.loukious.speedtestprivacy.core.PermissionPolicy
import io.github.loukious.speedtestprivacy.core.PrivacyMetadata
import io.github.loukious.speedtestprivacy.core.Settings
import io.github.loukious.speedtestprivacy.hooks.*

class ModuleMain : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, HookInstaller.TAG, "module=${BuildConfig.VERSION_NAME} process=${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != PermissionPolicy.TARGET) return
        Settings.initialize(this)
        val hooks = HookInstaller(this, param.classLoader)
        val groups = listOf(
            "permissions" to { PermissionHooks.install(hooks) },
            "identifiers" to { IdentifierHooks.install(hooks) },
            "advertising" to { AdsHooks.install(hooks) },
            "telemetry" to { TelemetryHooks.install(hooks) },
            "network" to { NetworkHooks.install(hooks) }
        )
        fun installGroups() {
            for ((name, install) in groups) runCatching(install).onFailure {
                hooks.log("group failed $name: ${it.javaClass.simpleName}")
            }
        }
        hooks.hook("android.app.ApplicationPackageManager", setOf("getApplicationInfo"), Settings.TELEMETRY, {
            it.returnType == ApplicationInfo::class.java && it.parameterTypes.firstOrNull() == String::class.java
        }) { chain ->
            val info = chain.proceed()
            if (chain.args[0] == PermissionPolicy.TARGET && info is ApplicationInfo) PrivacyMetadata.apply(info)
            info
        }
        // Attach runs before ContentProvider initialization and Application.onCreate.
        hook(ApplicationAttach.method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                (chain.args.firstOrNull() as? Context)?.takeIf { it.packageName == PermissionPolicy.TARGET }?.let {
                    hooks.bindDiagnostics(it)
                    disableAutomaticCollection(it, hooks)
                    runCatching { DiscoveryNative.ensureLoaded() }.onFailure { error ->
                        hooks.log("DexKit native load failed: ${error.javaClass.simpleName}")
                    }
                    hooks.prepareDiscovery(it)
                    installGroups()
                    hooks.finishInstallation()
                }
                return chain.proceed()
            }
        })
        // Install stable platform/SDK protections immediately, before context/discovery.
        installGroups()
        hooks.log("ready version=${BuildConfig.VERSION_NAME} installed=${hooks.count} target=${param.packageName}")
    }

    private fun disableAutomaticCollection(context: Context, hooks: HookInstaller) {
        if (!Settings.enabled(Settings.TELEMETRY)) return
        runCatching {
            PrivacyMetadata.apply(context.applicationInfo)
            hooks.log("automatic Firebase collection disabled before app initialization")
        }.onFailure { hooks.log("metadata setup failed: ${it.javaClass.simpleName}") }
    }

    private object ApplicationAttach {
        val method = android.app.Application::class.java.getDeclaredMethod("attach", Context::class.java)
    }
}
