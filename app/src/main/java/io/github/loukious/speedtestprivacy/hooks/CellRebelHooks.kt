package io.github.loukious.speedtestprivacy.hooks

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import io.github.loukious.speedtestprivacy.core.CellRebelCallbacks
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.Settings

/** Preserve failure callbacks when suppressing the SDK's alternative entry points. */
object CellRebelHooks {
    private const val ALIAS = "com.cellrebel.sdk.CellRebelSDK"
    private const val MEASUREMENT = "com.cellrebel.sdk.CRMeasurementSDK"
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun install(hooks: HookInstaller, trackingContext: () -> Any?) {
        hooks.blockVoid(ALIAS, setOf("init"), Settings.BACKGROUND) { method ->
            publicStatic(method) && (parameters(method, Context::class.java, String::class.java) ||
                parameters(method, Context::class.java, String::class.java, String::class.java))
        }
        hooks.hook(ALIAS, setOf("setBackgroundMeasurementsEnabled"), Settings.BACKGROUND, {
            publicStatic(it) && it.returnType == Void.TYPE && parameters(it, java.lang.Boolean.TYPE)
        }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            try {
                if (trackingContext() == null) null else {
                    val arguments = chain.args.toTypedArray()
                    arguments[0] = false
                    chain.proceed(arguments)
                }
            } catch (error: Throwable) {
                hooks.targetFailure("background.cellrebel.aliasCancellation", error.javaClass.simpleName)
                null
            }
        }
        callbackEntry(hooks, MEASUREMENT, "background.cellrebel.completion") { type ->
            CellRebelCallbacks.completion(type)?.let { it to false }
        }
        callbackEntry(hooks, ALIAS, "background.cellrebel.eventCancellation") { type ->
            CellRebelCallbacks.cancellation(type)?.let { it.method to it.error }
        }
    }

    private fun callbackEntry(
        hooks: HookInstaller,
        className: String,
        key: String,
        resolve: (Class<*>) -> Pair<Method, Any>?
    ) {
        val sdk = runCatching { Class.forName(className, false, hooks.loader) }.getOrNull() ?: return
        val candidates = runCatching { sdk.declaredMethods.filter {
            publicStatic(it) && it.name == "startMeasuring" && it.returnType == Void.TYPE &&
                it.parameterCount == 2 && it.parameterTypes[0] == Context::class.java
        } }.getOrElse {
            hooks.targetFailure(key, "startMeasuring member validation failed: ${it.javaClass.simpleName}")
            return
        }
        if (candidates.isEmpty()) return
        val method = candidates.singleOrNull()
        val completion = runCatching {
            method?.let { resolve(it.parameterTypes[1]) }?.also { it.first.isAccessible = true }
        }.getOrNull()
        if (method == null || completion == null) {
            hooks.targetFailure(key, "Unique startMeasuring completion contract unavailable")
            return
        }
        val (callback, failure) = completion
        hooks.hookMethod(method, key, Settings.BACKGROUND,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            try {
                val listener = chain.args.getOrNull(1)
                if (listener != null && !main.post {
                    runCatching { callback.invoke(listener, failure) }.onFailure {
                        hooks.targetFailure(key, "Failure callback invocation failed: ${it.javaClass.simpleName}")
                    }
                }) hooks.targetFailure(key, "Failure callback dispatch rejected")
            } catch (error: Throwable) {
                hooks.targetFailure(key, "Failure callback dispatch failed: ${error.javaClass.simpleName}")
            }
            null
        }
    }

    private fun publicStatic(method: Method): Boolean = Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers)
    private fun parameters(method: Method, vararg types: Class<*>): Boolean = method.parameterTypes.contentEquals(types)
}
