package io.github.loukious.speedtestprivacy.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import org.json.JSONArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import io.github.loukious.speedtestprivacy.BuildConfig

/** Only instantiated inside the scoped Speedtest process. */
class HookInstaller(val module: XposedInterface, val loader: ClassLoader) {
    private val installed = ConcurrentHashMap.newKeySet<Method>()
    private data class Binding(val key: String, val feature: String, val mode: XposedInterface.ExceptionMode)
    private val bindings = ConcurrentHashMap<Method, Binding>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private var diagnosticFile: File? = null
    private val diagnosticPending = AtomicBoolean()
    private val checks = ConcurrentHashMap<String, Boolean>()
    private val errors = ConcurrentHashMap.newKeySet<String>()
    private val skipped = ConcurrentHashMap.newKeySet<String>()
    private val targetErrors = ConcurrentHashMap<String, String>()
    private val targetMethods = ConcurrentHashMap<String, MutableSet<Method>>()
    private var discovery: DiscoverySnapshot? = null
    private var installationComplete = false
    private var checksTimedOut = false
    val count: Int get() = installed.size
    val verifiedProfile: Boolean get() = discovery?.identity?.verifiedProfile == true

    fun prepareDiscovery(context: Context) {
        val targets = AdDiscovery.targets + TelemetryDiscovery.targets
        discovery = try {
            DiscoveryEngine(loader, targets, ::log).prepare(context)
        } catch (error: Throwable) {
            log("Discovery preparation failed: ${error.javaClass.simpleName}")
            DiscoverySnapshot(HostIdentity("", -1, "", false, emptyList()), targets.map {
                DiscoveryReport(it.key, it.description, it.critical, DiscoveryStatus.QUERY_FAILED,
                    "Preparation failed: ${error.javaClass.simpleName}")
            }, emptyMap(), false, 0)
        }
        skipped.clear()
        scheduleDiagnostic()
    }

    fun finishInstallation() {
        installationComplete = true
        scheduleDiagnostic()
    }

    fun resolvedMethods(key: String): List<Method> = discovery?.methods?.get(key).orEmpty()

    fun targetFailure(key: String, detail: String) {
        targetErrors[key] = detail
        log("target failed $key: $detail")
        scheduleDiagnostic()
    }

    fun discovered(
        key: String, feature: String,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PROTECTIVE,
        replacement: (XposedInterface.Chain) -> Any?
    ): Int = resolvedMethods(key).sumOf { hookMethod(it, key, feature, exceptionMode, replacement) }

    fun check(name: String, passed: Boolean) {
        checks[name] = passed
        scheduleDiagnostic()
    }

    fun bindDiagnostics(context: Context) {
        diagnosticFile = File(context.cacheDir, "speedtest-privacy-status.json")
        if (!RuntimeStatusChannel.register(context, checkNotNull(diagnosticFile)))
            log("Runtime status receiver setup failed")
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                if (Settings.enabled(Settings.PERMISSIONS)) checks["optionalPermissionDenied"] =
                    // This normal permission is granted on the test phone; denial proves hook dispatch.
                    context.checkSelfPermission("android.permission.READ_BASIC_PHONE_STATE") == PackageManager.PERMISSION_DENIED
                if (Settings.enabled(Settings.IDENTIFIERS)) checks["androidIdRedacted"] =
                    android.provider.Settings.Secure.getString(context.contentResolver, "android_id") == "0000000000000000"
                if (Settings.enabled(Settings.IDENTIFIERS)) {
                    val client = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient", false, loader)
                    val provider = client.getDeclaredMethod("getAdvertisingIdInfo", Context::class.java)
                    // Never run a real ID lookup if its protection failed to install.
                    checks["advertisingIdRedacted"] = if (provider !in installed) false else {
                        val info = provider.invoke(null, context)
                        info != null && info.javaClass.getMethod("getId").invoke(info) == "00000000-0000-0000-0000-000000000000" &&
                            info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) == true
                    }
                }
                if (Settings.enabled(Settings.TELEMETRY)) checks["javaUrlBlocked"] = try {
                    // openConnection alone does not connect; this safely tests actual hook dispatch.
                    URL("https://mapi.speedtest.net/reports").openConnection()
                    false
                } catch (error: IOException) {
                    error.message == "Endpoint blocked by Speedtest Privacy"
                }
                if (Settings.enabled(Settings.TELEMETRY)) checks["legacyUrlsBlocked"] = listOf(
                    "https://www.speedtest.net/api/android.php", "https://mobileapi.speedtest.net/androidextra"
                ).all { address ->
                    try {
                        URL(address).openConnection()
                        false
                    } catch (error: IOException) {
                        error.message == "Endpoint blocked by Speedtest Privacy"
                    }
                }
                if (Settings.enabled(Settings.TELEMETRY)) checks["mapboxReportUrlsBlocked"] = listOf(
                    "https://api.mapbox.com/sdk-sessions/v1", "https://config.mapbox.com/events-config"
                ).all { address ->
                    try {
                        URL(address).openConnection()
                        false
                    } catch (error: IOException) {
                        error.message == "Endpoint blocked by Speedtest Privacy"
                    }
                }
            }.onFailure { errors += "runtime check: ${it.javaClass.simpleName}" }
            scheduleDiagnostic()
        }, 3000)
        Handler(Looper.getMainLooper()).postDelayed({
            checksTimedOut = true
            scheduleDiagnostic()
        }, 12000)
        scheduleDiagnostic()
    }

    private fun expectedChecks(): Set<String> = buildSet {
        if (Settings.enabled(Settings.PERMISSIONS)) add("optionalPermissionDenied")
        if (Settings.enabled(Settings.IDENTIFIERS)) addAll(listOf("androidIdRedacted", "advertisingIdRedacted"))
        if (Settings.enabled(Settings.TELEMETRY)) addAll(listOf("javaUrlBlocked", "legacyUrlsBlocked",
            "mapboxReportUrlsBlocked", "mapboxNativeCollectionDisabled"))
    }

    private fun requiredGuards(): List<JSONObject> {
        val network = Settings.enabled(Settings.ADS) || Settings.enabled(Settings.TELEMETRY) || Settings.enabled(Settings.BACKGROUND)
        fun guard(key: String, description: String, enabled: Boolean, predicate: (Method) -> Boolean) =
            JSONObject().put("key", key).put("description", description).put("enabled", enabled)
                .put("installed", installed.any(predicate))
        return listOf(
            guard("permissions.check", "Android permission checks", Settings.enabled(Settings.PERMISSIONS)) {
                it.declaringClass.name in setOf("android.content.Context", "android.app.ContextImpl") && it.name == "checkSelfPermission"
            },
            guard("permissions.request", "Android permission request denial", Settings.enabled(Settings.PERMISSIONS)) {
                it.declaringClass.name == "android.app.Activity" && it.name == "requestPermissions"
            },
            guard("identifiers.androidId", "Android identifier redaction", Settings.enabled(Settings.IDENTIFIERS)) {
                it.declaringClass.name == "android.provider.Settings\$Secure" && it.name == "getString"
            },
            guard("identifiers.gaid", "Advertising identifier redaction", Settings.enabled(Settings.IDENTIFIERS)) {
                it.declaringClass.name == "com.google.android.gms.ads.identifier.AdvertisingIdClient" && it.name == "getAdvertisingIdInfo"
            },
            guard("identifiers.gaidInstance", "Instance advertising identifier redaction", Settings.enabled(Settings.IDENTIFIERS)) {
                it.declaringClass.name == "com.google.android.gms.ads.identifier.AdvertisingIdClient" && it.name == "getInfo"
            },
            guard("network.url", "Java URL report guard", network) {
                it.declaringClass.name == "java.net.URL" && it.name == "openConnection" && it.parameterCount == 0
            },
            guard("network.okhttp", "OkHttp report guard", network) {
                it.declaringClass.name == "okhttp3.internal.http.RealInterceptorChain" && it.name == "proceed"
            },
            guard("network.mapbox", "Mapbox native HTTP guard", Settings.enabled(Settings.TELEMETRY)) {
                it.declaringClass.name == "com.mapbox.common.module.okhttp.MapboxOkHttpService" && it.name == "request"
            },
            guard("telemetry.mapbox", "Mapbox native collection control", Settings.enabled(Settings.TELEMETRY)) {
                it.declaringClass.name == "com.mapbox.common.CoreInitializer" && it.name == "initializeTelemetryService"
            }
        )
    }

    private fun targetEnabled(key: String): Boolean = Settings.enabled(when {
        key.startsWith("ads.") -> Settings.ADS
        key.startsWith("identifiers.") -> Settings.IDENTIFIERS
        key.startsWith("permissions.") -> Settings.PERMISSIONS
        else -> Settings.TELEMETRY
    })

    private fun scheduleDiagnostic() {
        if (diagnosticFile == null || !diagnosticPending.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).postDelayed({
            diagnosticPending.set(false)
            runCatching {
                val json = JSONObject().put("package", PermissionPolicy.TARGET)
                    .put("moduleApplicationId", BuildConfig.APPLICATION_ID)
                    .put("moduleVersionCode", BuildConfig.VERSION_CODE).put("moduleVersion", BuildConfig.VERSION_NAME)
                    .put("installed", count).put("updated", System.currentTimeMillis())
                val counters = JSONObject()
                hits.forEach { (key, value) -> counters.put(key, value.get()) }
                json.put("intercepts", counters)
                json.put("checks", JSONObject(checks as Map<*, *>))
                val pendingChecks = expectedChecks().filter { !checks.containsKey(it) }
                json.put("pendingChecks", JSONArray(pendingChecks)).put("checksTimedOut", checksTimedOut)
                val guards = requiredGuards()
                json.put("requiredGuards", JSONArray(guards))
                json.put("installedMethods", JSONArray(installed.map { "${it.declaringClass.name}.${it.name}" }.sorted()))
                json.put("errors", JSONArray(errors.toList()))
                discovery?.let { snapshot ->
                    val host = snapshot.identity
                    json.put("host", JSONObject().put("versionName", host.versionName).put("versionCode", host.versionCode)
                        .put("fingerprint", host.fingerprint).put("verifiedProfile", host.verifiedProfile)
                        .put("apks", JSONArray(host.apkPaths.map { path ->
                            val file = File(path)
                            JSONObject().put("path", path).put("length", file.length()).put("modified", file.lastModified())
                        })))
                    val reports = snapshot.reports.map { report ->
                        val matched = report.status == DiscoveryStatus.FOUND &&
                            targetMethods[report.key]?.containsAll(report.methods) == true && !targetErrors.containsKey(report.key)
                        JSONObject().put("key", report.key).put("description", report.description)
                            .put("critical", report.critical).put("enabled", targetEnabled(report.key)).put("status", report.status.name)
                            .put("installed", matched).put("detail", targetErrors[report.key] ?: report.detail)
                    }
                    json.put("discovery", JSONObject().put("cacheHit", snapshot.cacheHit).put("elapsedMs", snapshot.elapsedMs)
                        .put("targets", JSONArray(reports)))
                    val missing = reports.count { it.getBoolean("critical") && it.getBoolean("enabled") && !it.getBoolean("installed") } +
                        guards.count { it.getBoolean("enabled") && !it.getBoolean("installed") }
                    json.put("compatibility", StatusPolicy.summary(true, installationComplete, host.verifiedProfile,
                        missing, errors.size, checks.values.count { !it }, skipped.size, pendingChecks.size, checksTimedOut))
                    json.put("criticalMissing", missing)
                } ?: json.put("compatibility", "initializing")
                json.put("skippedVersionSpecific", JSONArray(skipped.sorted()))
                diagnosticFile?.writeText(json.toString())
            }
        }, 1000)
    }

    fun log(message: String) {
        if ("failed" in message) errors += message
        runCatching { Log.i(TAG, message) }
        runCatching { module.log(Log.INFO, TAG, message) }
    }

    fun record(feature: String, method: String) {
        val hit = hits.getOrPut(feature) { AtomicInteger() }.incrementAndGet()
        if (hit == 1 || hit == 10 || hit == 100) log("intercepted feature=$feature hits=$hit method=$method")
        scheduleDiagnostic()
    }

    fun hook(
        className: String,
        names: Set<String>,
        feature: String,
        filter: (Method) -> Boolean = { true },
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PROTECTIVE,
        replacement: (XposedInterface.Chain) -> Any?
    ): Int {
        val allowedNames = names.filterTo(mutableSetOf()) { name ->
            val allowed = verifiedProfile || !HookSafety.requiresVerifiedProfile(className, name)
            if (!allowed) skipped += "$className.$name"
            allowed
        }
        if (allowedNames.isEmpty()) return 0
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: return 0
        var count = 0
        for (method in clazz.declaredMethods) {
            if (method.name !in allowedNames || Modifier.isAbstract(method.modifiers) || !filter(method)) continue
            count += hookMethod(method, "named:$className.${method.name}", feature, exceptionMode, replacement)
        }
        if (count > 0) log("installed feature=$feature class=$className count=$count")
        return count
    }

    fun hookMethod(
        method: Method, key: String, feature: String,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PROTECTIVE,
        replacement: (XposedInterface.Chain) -> Any?
    ): Int {
        if (!installed.add(method)) {
            if (bindings[method] != Binding(key, feature, exceptionMode)) {
                targetFailure(key, "Method already belongs to a different hook role")
                return 0
            }
            targetMethods.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(method)
            return 0
        }
        return try {
            module.hook(method).setExceptionMode(exceptionMode).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (!Settings.enabled(feature)) return chain.proceed()
                    record(feature, "${method.declaringClass.name}.${method.name}")
                    return replacement(chain)
                }
            })
            bindings[method] = Binding(key, feature, exceptionMode)
            targetMethods.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(method)
            1
        } catch (error: Throwable) {
            installed.remove(method)
            targetFailure(key, error.javaClass.simpleName)
            0
        }
    }

    fun constant(
        className: String, names: Set<String>, feature: String, value: Any?,
        returnType: String, parameterCount: Int? = null
    ): Int = hook(className, names, feature, {
        it.returnType.name == returnType && (parameterCount == null || it.parameterCount == parameterCount)
    }) { value }

    fun blockVoid(
        className: String, names: Set<String>, feature: String,
        filter: (Method) -> Boolean = { true }
    ): Int = hook(className, names, feature, { it.returnType == Void.TYPE && filter(it) }) { null }

    fun completedRx(): Any = Class.forName("io.reactivex.Completable", false, loader)
        .getDeclaredMethod("r").invoke(null)!!

    companion object { const val TAG = "SpeedtestPrivacy" }
}
