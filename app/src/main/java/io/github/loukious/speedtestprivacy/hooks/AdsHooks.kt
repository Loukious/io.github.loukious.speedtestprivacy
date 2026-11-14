package io.github.loukious.speedtestprivacy.hooks

import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import io.github.libxposed.api.XposedInterface
import io.github.loukious.speedtestprivacy.core.AdDiscovery
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.Settings

/** Zero means ads disabled; state publication and listener cleanup still run. */
object AdsHooks {
    private const val GOOGLE = "com.google.android.gms.ads"
    private const val BLOCKED = "Blocked by Speedtest Privacy"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    fun install(hooks: HookInstaller) {
        // Keep normal state publication and cleanup; do not fake an ad-free purchase.
        hooks.discovered("ads.state.compute", Settings.ADS) { 0 }
        hooks.discovered("ads.state.get", Settings.ADS) { 0 }
        hooks.constant("com.ookla.speedtest.nativead.j", setOf("c", "e"), Settings.ADS, false, "boolean", 0)
        hooks.discovered("ads.banner.allowed", Settings.ADS) { false }
        hooks.discovered("ads.amazon.initialize", Settings.ADS) { null }
        hooks.discovered("ads.prebid.initialize", Settings.ADS) { null }

        // This provider performs metadata validation in attachInfo. Preserve framework attachment.
        hooks.constant("$GOOGLE.MobileAdsInitProvider", setOf("onCreate"), Settings.ADS, true, "boolean", 0)
        hooks.hook("$GOOGLE.MobileAds", setOf("initialize"), Settings.ADS, {
            it.returnType == Void.TYPE && (signature(it, "android.content.Context") ||
                signature(it, "android.content.Context", "$GOOGLE.initialization.OnInitializationCompleteListener"))
        }) { chain ->
            val listener = chain.args.getOrNull(1)
            if (listener != null) post(hooks) {
                val statusClass = type(hooks, "$GOOGLE.initialization.InitializationStatus")
                val status = Proxy.newProxyInstance(hooks.loader, arrayOf(statusClass)) { proxy, method, args ->
                    when (method.name) {
                        "getAdapterStatusMap" -> emptyMap<String, Any>()
                        "toString" -> "SpeedtestPrivacy: no advertising adapters"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                }
                callback(listener, "onInitializationComplete", status)
            }
            null
        }

        for (clazz in listOf("$GOOGLE.BaseAdView", "$GOOGLE.admanager.AdManagerAdView")) {
            hooks.hook(clazz, setOf("loadAd"), Settings.ADS, {
                it.returnType == Void.TYPE && (signature(it, "$GOOGLE.AdRequest") ||
                    signature(it, "$GOOGLE.admanager.AdManagerAdRequest"))
            }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
                val view = chain.thisObject
                runCatching { (view as? View)?.visibility = View.GONE }
                val listener = runCatching { view?.let { callZero(it, "getAdListener") } }.getOrElse {
                    hooks.log("ad listener lookup failed: ${it.javaClass.simpleName}")
                    null
                }
                failGoogle(hooks, listener)
                null
            }
            hooks.constant(clazz, setOf("isLoading"), Settings.ADS, false, "boolean", 0)
        }
        hooks.blockVoid("$GOOGLE.admanager.AdManagerAdView", setOf("recordManualImpression"), Settings.ADS) {
            it.parameterCount == 0
        }

        // Retain AdLoader listeners so suppressed loads still report failure asynchronously.
        hooks.hook("$GOOGLE.AdLoader\$Builder", setOf("withAdListener"), Settings.ADS, {
            signature(it, "$GOOGLE.AdListener") && it.returnType.name == "$GOOGLE.AdLoader\$Builder"
        }) { chain ->
            val builder = chain.thisObject
            val listener = chain.args.firstOrNull()
            if (builder != null && listener != null) listeners[builder] = listener
            chain.proceed()
        }
        hooks.hook("$GOOGLE.AdLoader\$Builder", setOf("build"), Settings.ADS, {
            it.parameterCount == 0 && it.returnType.name == "$GOOGLE.AdLoader"
        }) { chain ->
            val result = chain.proceed()
            val listener = chain.thisObject?.let { listeners[it] }
            if (result != null && listener != null) listeners[result] = listener
            result
        }
        hooks.hook("$GOOGLE.AdLoader", setOf("loadAd", "loadAds"), Settings.ADS, {
            it.returnType == Void.TYPE && (signature(it, "$GOOGLE.AdRequest") ||
                signature(it, "$GOOGLE.admanager.AdManagerAdRequest") || signature(it, "$GOOGLE.AdRequest", "int"))
        }) { chain ->
            failGoogle(hooks, chain.thisObject?.let { listeners[it] })
            null
        }
        hooks.constant("$GOOGLE.AdLoader", setOf("isLoading"), Settings.ADS, false, "boolean", 0)
        for (clazz in listOf(
            "$GOOGLE.interstitial.InterstitialAd", "$GOOGLE.admanager.AdManagerInterstitialAd",
            "$GOOGLE.rewarded.RewardedAd", "$GOOGLE.appopen.AppOpenAd"
        )) {
            hooks.hook(clazz, setOf("load"), Settings.ADS, {
                it.returnType == Void.TYPE && it.parameterCount == 4 &&
                    it.parameterTypes[0].name == "android.content.Context" &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2].name in setOf("$GOOGLE.AdRequest", "$GOOGLE.admanager.AdManagerAdRequest") &&
                    it.parameterTypes[3].name.endsWith("AdLoadCallback")
            }) { chain -> failGoogle(hooks, chain.args.getOrNull(3)); null }
            hooks.constant(clazz, setOf("isAdAvailable"), Settings.ADS, false, "boolean", 2)
        }
        hooks.hook("$GOOGLE.nativead.NativeAdView", setOf("setNativeAd"), Settings.ADS, {
            it.returnType == Void.TYPE && signature(it, "$GOOGLE.nativead.NativeAd")
        }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            (chain.thisObject as? View)?.visibility = View.GONE
            chain.args.firstOrNull()?.let { runCatching { callZero(it, "destroy") } }
            null
        }

        hooks.hook("com.amazon.device.ads.DTBAdRequest", setOf("loadAd", "loadSmartBanner"), Settings.ADS, {
            it.returnType == Void.TYPE && signature(it, "com.amazon.device.ads.DTBAdCallback")
        }) { chain ->
            val listener = chain.args.firstOrNull()
            if (listener != null) post(hooks) {
                val errorClass = type(hooks, "com.amazon.device.ads.AdError")
                val codeClass = type(hooks, "com.amazon.device.ads.AdError\$ErrorCode")
                val noFill = codeClass.enumConstants.first { (it as Enum<*>).name == "NO_FILL" }
                val error = errorClass.getConstructor(codeClass, String::class.java).newInstance(noFill, BLOCKED)
                callback(listener, "onFailure", error)
            }
            null
        }
        hooks.blockVoid("com.amazon.device.ads.AdRegistration", setOf("useGeoLocation"), Settings.ADS) {
            signature(it, "boolean")
        }
        prebid(hooks, "ads.prebid.fetch", 1, "NO_BIDS")
        prebid(hooks, "ads.prebid.sdkInitialize", 2, "FAILED")
    }

    private fun prebid(hooks: HookInstaller, key: String, listenerIndex: Int, resultName: String) {
        val method = hooks.resolvedMethods(key).singleOrNull()
        if (method == null) {
            hooks.discovered(key, Settings.ADS) { null }
            return
        }
        val callback = AdDiscovery.enumCallback(method.parameterTypes[listenerIndex])
        val result = callback?.parameterTypes?.singleOrNull()?.enumConstants
            ?.singleOrNull { (it as? Enum<*>)?.name == resultName }
        if (callback == null || result == null) {
            hooks.targetFailure(key, "Completion callback contract or $resultName result missing")
            return
        }
        callback.isAccessible = true
        hooks.hookMethod(method, key, Settings.ADS) { chain ->
            val listener = chain.args.getOrNull(listenerIndex)
            if (listener != null) post(hooks) { callback.invoke(listener, result) }
            null
        }
    }

    private fun signature(method: Method, vararg params: String): Boolean =
        method.parameterTypes.map { it.name } == params.toList()

    private fun type(hooks: HookInstaller, name: String): Class<*> = Class.forName(name, false, hooks.loader)

    private fun post(hooks: HookInstaller, action: () -> Unit) {
        main.post { runCatching(action).onFailure { hooks.log("ad callback failed: ${it.javaClass.simpleName}") } }
    }

    private fun callZero(target: Any, name: String): Any? = target.javaClass.getMethod(name).apply {
        isAccessible = true
    }.invoke(target)

    private fun callback(target: Any, name: String, argument: Any) {
        target.javaClass.methods.first {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0].isInstance(argument)
        }.apply { isAccessible = true }.invoke(target, argument)
    }

    private fun failGoogle(hooks: HookInstaller, listener: Any?) {
        if (listener == null) return
        post(hooks) {
            val errorClass = type(hooks, "$GOOGLE.LoadAdError")
            val error = errorClass.getConstructor(Int::class.javaPrimitiveType, String::class.java,
                String::class.java, type(hooks, "$GOOGLE.AdError"), type(hooks, "$GOOGLE.ResponseInfo"))
                .newInstance(3, BLOCKED, "speedtestprivacy", null, null)
            callback(listener, "onAdFailedToLoad", error)
        }
    }
}
