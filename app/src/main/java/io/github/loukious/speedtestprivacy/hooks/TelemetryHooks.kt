package io.github.loukious.speedtestprivacy.hooks

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.LegacySubmissionMembers
import io.github.loukious.speedtestprivacy.core.Settings
import io.github.loukious.speedtestprivacy.core.SingleResultFactory
import io.github.loukious.speedtestprivacy.core.TelemetryDiscovery
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.Modifier

/** Semantic critical targets; remaining version-specific adapters are profile gated. */
object TelemetryHooks {
    private const val ANALYTICS = "com.google.firebase.analytics.FirebaseAnalytics"
    private const val MEASUREMENT = "com.google.android.gms.internal.measurement.u1"
    private val identifiers = setOf(
        "gaid", "gaidOptOut", "androidId", "android_id", "deviceId", "deviceGuid",
        "imei", "imsi", "meid", "telephonyDeviceId", "telephonyImei", "telephonyMeid",
        "fingerprint", "deviceIpAddress", "wifiSsid", "wifiBssid", "ssid", "bssid"
    )

    fun install(hooks: HookInstaller) {
        analytics(hooks)
        legacyResultSubmission(hooks)
        background(hooks)
        identifiers(hooks)
    }

    private fun legacyResultSubmission(hooks: HookInstaller) {
        // The initial stable-hook pass runs before discovery; it installs this role on the second pass.
        val upload = hooks.resolvedMethods(TelemetryDiscovery.LEGACY_UPLOAD).singleOrNull() ?: return
        val className = upload.declaringClass.name
        val members = runCatching { LegacySubmissionMembers.resolve(upload) }.getOrElse {
            hooks.targetFailure(TelemetryDiscovery.LEGACY_UPLOAD, "Completion contract failed: ${it.message}")
            return
        }
        hooks.hookMethod(upload, TelemetryDiscovery.LEGACY_UPLOAD, Settings.TELEMETRY,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            // This method starts the legacy POST after the local save finishes. Its
            // discovered completion contract closes the normal test flow without uploading.
            val context = chain.thisObject
            var callback: Any? = null
            var callbackStarted = false
            try {
                checkNotNull(context)
                synchronized(context) {
                    if (members.finished.getBoolean(context)) return@hookMethod null
                    callback = members.callback.get(context)
                    members.setRemoteId.invoke(context, -1L)
                    members.finished.setBoolean(context, true)
                }
                callback?.let {
                    callbackStarted = true
                    members.complete.invoke(it, -1L)
                    runCatching {
                        hooks.record("internal.localResultCompleted", "$className.${upload.name}")
                        hooks.log("legacy result submission suppressed; local result finish callback completed")
                    }
                }
            } catch (error: Throwable) {
                // Catch inside the hook so protective exception handling cannot execute
                // the original POST. Never invoke a callback twice after it has begun.
                runCatching { hooks.log("legacy result finish failed: ${error.javaClass.simpleName}") }
                if (!callbackStarted && callback != null) runCatching {
                    if (context != null) members.finished.setBoolean(context, true)
                    members.fail.invoke(callback, Exception("Legacy result upload disabled", error))
                }.onFailure { runCatching { hooks.log("legacy result failure callback failed: ${it.javaClass.simpleName}") } }
            }
            null
        }
    }

    private fun signature(method: Method, result: String, vararg parameters: String): Boolean =
        method.returnType.name == result && method.parameterTypes.map { it.name } == parameters.toList()

    private fun falseArgument(chain: XposedInterface.Chain, index: Int = 0): Any? {
        val arguments = chain.args.toTypedArray()
        arguments[index] = false
        return chain.proceed(arguments)
    }

    private fun analytics(hooks: HookInstaller) {
        val feature = Settings.TELEMETRY
        hooks.blockVoid("com.ookla.tools.logging.android.FirebaseAnalyticsEventLogger",
            setOf("a", "f", "g", "h"), feature) {
            when (it.name) {
                "a", "g" -> signature(it, "void", "java.lang.String")
                "f" -> signature(it, "void", "java.lang.String", "java.lang.String")
                "h" -> signature(it, "void", "java.lang.String", "java.util.Map")
                else -> false
            }
        }
        hooks.blockVoid(ANALYTICS, setOf("a", "d", "e", "setCurrentScreen"), feature) {
            when (it.name) {
                "a" -> signature(it, "void", "java.lang.String", "android.os.Bundle")
                "d" -> signature(it, "void", "java.lang.String")
                "e" -> signature(it, "void", "java.lang.String", "java.lang.String")
                "setCurrentScreen" -> signature(it, "void", "android.app.Activity", "java.lang.String", "java.lang.String")
                else -> false
            }
        }
        hooks.hook(ANALYTICS, setOf("b"), feature,
            { signature(it, "void", "boolean") }) { falseArgument(it) }
        hooks.hook(ANALYTICS, setOf("c"), feature,
            { signature(it, "void", "java.util.Map") }) { chain ->
            val arguments = chain.args.toTypedArray()
            val consentType = Class.forName("$ANALYTICS\$b", false, hooks.loader)
            val consentStatus = Class.forName("$ANALYTICS\$a", false, hooks.loader)
            val denied = consentStatus.enumConstants.first { (it as Enum<*>).name == "DENIED" }
            arguments[0] = consentType.enumConstants.associateWith { denied }
            chain.proceed(arguments)
        }
        // This factory also supplies the measurement frontend used by automatic analytics.
        hooks.hook(MEASUREMENT, setOf("o"), feature, {
            signature(it, MEASUREMENT, "android.content.Context", "android.os.Bundle")
        }) { chain ->
            val result = chain.proceed()
            if (result != null) runCatching {
                result.javaClass.getDeclaredMethod("C", java.lang.Boolean::class.java).invoke(result, false)
            }.onFailure { hooks.log("analytics disable failed: ${it.javaClass.simpleName}") }
            result
        }
        hooks.hook(MEASUREMENT, setOf("C"), feature,
            { signature(it, "void", "java.lang.Boolean") }) { falseArgument(it) }
        hooks.hook(MEASUREMENT, setOf("D"), feature,
            { signature(it, "void", "android.os.Bundle") }) { chain ->
            val arguments = chain.args.toTypedArray()
            val consent = (arguments[0] as? Bundle)?.let(::Bundle) ?: Bundle()
            for (key in arrayOf("ad_storage", "analytics_storage", "ad_user_data", "ad_personalization")) {
                consent.putString(key, "denied")
            }
            arguments[0] = consent
            chain.proceed(arguments)
        }
        hooks.blockVoid(MEASUREMENT, setOf("t", "u", "v", "w", "A", "B", "x", "y"), feature) {
            when (it.name) {
                "t" -> signature(it, "void", "java.lang.String", "android.os.Bundle")
                "u", "y" -> signature(it, "void", "java.lang.String", "java.lang.String", "android.os.Bundle")
                "v" -> signature(it, "void", "java.lang.String", "java.lang.String", "android.os.Bundle", "long")
                "w" -> signature(it, "void", "java.lang.String", "java.lang.String", "java.lang.Object", "boolean")
                "A" -> signature(it, "void", "java.lang.String")
                "B" -> signature(it, "void", "com.google.android.gms.internal.measurement.zzdf", "java.lang.String", "java.lang.String")
                "x" -> signature(it, "void", "android.os.Bundle")
                else -> false
            }
        }
        // Preserve Firebase components and remote configuration; only disable Crashlytics collection.
        hooks.constant("com.google.firebase.crashlytics.internal.common.E", setOf("d"),
            feature, false, "boolean", 0)
        hooks.constant("com.google.firebase.perf.e", setOf("d"), feature, false, "boolean", 0)
        hooks.constant("com.google.firebase.perf.config.a", setOf("K"), feature, false, "boolean", 0)
        hooks.constant("com.google.firebase.perf.config.a", setOf("j"), feature, false, "java.lang.Boolean", 0)
        hooks.hook("com.google.firebase.crashlytics.internal.common.E", setOf("h"), feature,
            { signature(it, "void", "java.lang.Boolean") }) { falseArgument(it) }
        hooks.blockVoid("com.google.firebase.crashlytics.internal.common.E", setOf("c"), feature,
            { signature(it, "void", "boolean") })
        hooks.hook("com.google.firebase.crashlytics.h", setOf("e"), feature,
            { signature(it, "void", "boolean") }) { falseArgument(it) }
        hooks.blockVoid("com.google.firebase.crashlytics.h", setOf("d"), feature,
            { signature(it, "void", "java.lang.Throwable") })
        hooks.hook("com.ookla.tools.logging.android.CrashlyticsDevMetricsLogger", setOf("j"), feature,
            { signature(it, "void", "boolean") }) { falseArgument(it) }
        hooks.blockVoid("com.ookla.tools.logging.android.CrashlyticsDevMetricsLogger", setOf("d"), feature,
            { signature(it, "void", "java.lang.Throwable") })
        // ca.c initializes Comscore after consent. ca.f updates its labels and sends a hidden event.
        hooks.blockVoid("com.ookla.mobile4.app.ca", setOf("c", "f"), feature) {
            (it.name == "c" && it.parameterCount == 0) || signature(it, "void", "boolean")
        }
        hooks.blockVoid("com.comscore.Analytics", setOf("start"), feature,
            { signature(it, "void", "android.content.Context") })
        hooks.blockVoid("com.comscore.Analytics", setOf("notifyHiddenEvent", "notifyViewEvent",
            "notifyEnterForeground", "notifyExitForeground", "notifyUxActive", "notifyUxInactive"), feature)
        mapboxNativeCollection(hooks)
        val mapTelemetry = "com.mapbox.maps.module.telemetry.MapTelemetryImpl"
        hooks.blockVoid(mapTelemetry, setOf("onAppUserTurnstileEvent", "onPerformanceEvent", "sendEvent"), feature) {
            when (it.name) {
                "onAppUserTurnstileEvent" -> signature(it, "void")
                "onPerformanceEvent" -> signature(it, "void", "android.os.Bundle")
                "sendEvent" -> signature(it, "void", "java.lang.String")
                else -> false
            }
        }
        hooks.hook(mapTelemetry, setOf("enableTelemetryCollection", "setUserTelemetryRequestState"), feature,
            { signature(it, "void", "boolean") }) { falseArgument(it) }
        hooks.hook("com.mapbox.common.TelemetryUtils", setOf("setEventsCollectionState"), feature,
            { signature(it, "void", "boolean", "com.mapbox.common.TelemetryUtilsResponseCallback") }) {
            falseArgument(it)
        }
        batchReports(hooks)
    }

    private fun batchReports(hooks: HookInstaller) {
        val upload = hooks.resolvedMethods(TelemetryDiscovery.BATCH_UPLOAD).singleOrNull()
        if (upload != null) {
            val completed = runCatching { SingleResultFactory.resolve(upload.returnType).invoke(null, 0) }.getOrElse {
                hooks.targetFailure(TelemetryDiscovery.BATCH_UPLOAD, "Completed Single contract failed: ${it.message}")
                return
            }
            hooks.hookMethod(upload, TelemetryDiscovery.BATCH_UPLOAD, Settings.TELEMETRY,
                exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) {
                completed
            }
        }
        hooks.discovered(TelemetryDiscovery.BATCH_AUTOMATIC, Settings.TELEMETRY) { null }
    }

    private fun mapboxNativeCollection(hooks: HookInstaller) {
        val properties = "com.mapbox.common.MmeTelemetryProperties"
        for (name in listOf(properties, "$properties\$Companion")) hooks.constant(name,
            setOf("shouldDisableEventsCollection", "shouldDisableEventsCollectionPermanently"),
            Settings.TELEMETRY, true, "boolean", 0)
        val members = runCatching {
            val utils = Class.forName("com.mapbox.common.TelemetryUtils", false, hooks.loader)
            val callback = Class.forName("com.mapbox.common.TelemetryUtilsResponseCallback", false, hooks.loader)
            val setter = utils.getDeclaredMethod("setEventsCollectionState", java.lang.Boolean.TYPE, callback)
            val getter = utils.getDeclaredMethod("getEventsCollectionState")
            check(setter.returnType == Void.TYPE && getter.returnType == java.lang.Boolean.TYPE)
            Triple(callback, setter, getter)
        }.getOrElse {
            hooks.log("Mapbox native member validation failed: ${it.javaClass.simpleName}")
            return
        }
        val (callbackType, setter, getter) = members
        fun verifyState() {
            runCatching {
                val disabled = getter.invoke(null) == false
                hooks.check("mapboxNativeCollectionDisabled", disabled)
                if (disabled) hooks.record("internal.mapboxNativeCollectionDisabled", "TelemetryUtils native state")
            }.onFailure { hooks.log("Mapbox native state check failed: ${it.javaClass.simpleName}") }
        }
        // Core.create has already supplied Context and loaded libmapbox-common here.
        // Run the real native mutation with a real callback; do not start the service.
        hooks.hook("com.mapbox.common.CoreInitializer", setOf("initializeTelemetryService"), Settings.TELEMETRY,
            { signature(it, "void") }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) {
            runCatching {
                val callback = Proxy.newProxyInstance(hooks.loader, arrayOf(callbackType)) { proxy, method, arguments ->
                    when (method.name) {
                        "run" -> { verifyState(); null }
                        "toString" -> "Speedtest Privacy native collection callback"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.firstOrNull()
                        else -> null
                    }
                }
                setter.invoke(null, false, callback)
                verifyState()
            }.onFailure {
                hooks.check("mapboxNativeCollectionDisabled", false)
                hooks.log("Mapbox native collection disable failed: ${it.javaClass.simpleName}")
            }
            null
        }
    }

    private fun background(hooks: HookInstaller) {
        val feature = Settings.BACKGROUND
        // Stop registration of the CellRebel SDK and its test/lifecycle listeners.
        hooks.blockVoid("com.ookla.mobile4.app.ba", setOf("a", "b"), feature) {
            when (it.name) {
                "a" -> signature(it, "void", "android.app.Application", "com.ookla.speedtestengine.B0",
                    "com.ookla.speedtest.video.G", "com.ookla.speedtestengine.n0",
                    "com.ookla.speedtestengine.reporting.bgreports.p")
                "b" -> signature(it, "void", "io.reactivex.Observable", "com.ookla.mobile4.app.U9\$a")
                else -> false
            }
        }
        hooks.blockVoid("com.cellrebel.sdk.CRMeasurementSDK", setOf("init", "startMeasuring"), feature) {
            val types = it.parameterTypes.map { p -> p.name }
            (it.name == "init" && types in listOf(listOf("android.content.Context", "java.lang.String"),
                listOf("android.content.Context", "java.lang.String", "java.lang.String"))) ||
                (it.name == "startMeasuring" && types == listOf("android.content.Context"))
        }
        val trackingContext = runCatching {
            val tracking = Class.forName("com.cellrebel.sdk.workers.TrackingManager", false, hooks.loader)
            val accessor = tracking.declaredMethods.singleOrNull {
                it.name == "applicationContext" && Modifier.isStatic(it.modifiers) &&
                    it.returnType == android.content.Context::class.java && it.parameterCount == 0
            }
            if (accessor != null) {
                accessor.isAccessible = true
                val readContext: () -> Any? = { accessor.invoke(null) }
                readContext
            } else {
                val field = tracking.declaredFields.single {
                    Modifier.isStatic(it.modifiers) && it.type == android.content.Context::class.java
                }.apply { isAccessible = true }
                val readContext: () -> Any? = { field.get(null) }
                readContext
            }
        }.getOrElse {
            hooks.targetFailure("background.cellrebelCancellation", "Tracking context contract unavailable")
            val unavailable: () -> Any? = { null }
            unavailable
        }
        hooks.hook("com.cellrebel.sdk.CRMeasurementSDK", setOf("setBackgroundMeasurementsEnabled"), feature,
            { signature(it, "void", "boolean") }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            // Cancellation needs an initialized context. Never replay an enabling argument after an error.
            try {
                if (trackingContext() == null) null else falseArgument(chain)
            } catch (error: Throwable) {
                hooks.log("CellRebel cancellation failed: ${error.javaClass.simpleName}")
                null
            }
        }
        CellRebelHooks.install(hooks, trackingContext)
        // WorkManager can restore work scheduled before the module was installed.
        for (worker in arrayOf("BackgroundWorker", "ForegroundWorker", "CoverageMetricsWorker",
            "CollectConnectionMetricsWorker", "SendConnectionMetricsWorker")) {
            hooks.hook("com.cellrebel.sdk.workers.$worker", setOf("doWork"), feature,
                { signature(it, "androidx.work.ListenableWorker\$Result") }) {
                Class.forName("androidx.work.ListenableWorker\$Result", false, hooks.loader)
                    .getDeclaredMethod("success").invoke(null)
            }
        }
        val manager = "com.ookla.speedtestengine.reporting.bgreports.p"
        hooks.constant(manager, setOf("j", "l"), feature, false, "boolean", 0)
        hooks.hook(manager, setOf("A"), feature,
            { signature(it, "void", "boolean") }) { falseArgument(it) }
        hooks.blockVoid(manager, setOf("h"), feature,
            { signature(it, "void", "java.lang.String", "int", "com.ookla.speedtestengine.reporting.F0") })
        for (service in arrayOf("j", "NetworkConnectJobService")) {
            hooks.hook("com.ookla.speedtestengine.reporting.bgreports.$service", setOf("onStartJob"), feature,
                { signature(it, "boolean", "android.app.job.JobParameters") }) { false }
        }
    }

    private fun identifiers(hooks: HookInstaller) {
        val feature = Settings.IDENTIFIERS
        // Config requests still run with their original server/test/connection parameters.
        hooks.discovered("identifiers.config.enrich", feature) { null }
        hooks.discovered("identifiers.config.params", feature) { chain ->
            val original = chain.proceed()
            if (original !is Map<*, *>) original
            else LinkedHashMap(original).apply { identifiers.forEach { remove(it) } }
        }
        hooks.discovered("identifiers.report.allowField", feature) { chain ->
            if (chain.args[0] in identifiers) false else chain.proceed()
        }
        hooks.discovered("identifiers.report.allowPath", feature) { chain ->
            val path = chain.args[0] as? Array<*>
            if (path?.any { it in identifiers } == true) false else chain.proceed()
        }
        // Final batch payload covers legacy and newer report formats. Preserve result GUIDs and metrics.
        hooks.discovered("identifiers.report.serialize", feature) { chain ->
            val payload = chain.proceed()
            if (payload !is JSONArray) payload else JSONArray(payload.toString()).also(::redact)
        }
    }

    private fun redact(value: Any?) {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) redact(value.opt(index))
            is JSONObject -> {
                for (key in value.keys().asSequence().toList()) {
                    if (key in identifiers) value.remove(key)
                    else {
                        val child = value.opt(key)
                        // This GUID identifies a device; top-level GUID identifies the individual result.
                        if (key == "device" && child is JSONObject) {
                            child.remove("id")
                            child.remove("guid")
                        }
                        redact(child)
                    }
                }
            }
        }
    }
}
