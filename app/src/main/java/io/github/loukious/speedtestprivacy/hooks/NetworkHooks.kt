package io.github.loukious.speedtestprivacy.hooks

import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
import java.lang.reflect.Modifier
import io.github.libxposed.api.XposedInterface
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.NetworkPolicy
import io.github.loukious.speedtestprivacy.core.Settings

object NetworkHooks {
    fun install(hooks: HookInstaller) {
        mapboxBridge(hooks)
        // Use a dedicated always-on gate so each endpoint's owning feature is checked separately.
        hooks.hook("java.net.InetAddress", setOf("getAllByName", "getByName"), NETWORK_GATE, {
            it.parameterTypes.firstOrNull() == String::class.java
        }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            val host = chain.args.firstOrNull() as? String
            val feature = host?.let(NetworkPolicy::hostFeature)
            if (feature != null && Settings.enabled(feature)) {
                hooks.record(feature, "network DNS")
                throw UnknownHostException("Endpoint blocked by Speedtest Privacy")
            }
            chain.proceed()
        }
        hooks.hook("java.net.URL", setOf("openConnection", "openStream"), NETWORK_GATE,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            val url = chain.thisObject as? URL
            val feature = url?.toExternalForm()?.let(NetworkPolicy::urlFeature)
            if (feature != null && Settings.enabled(feature)) {
                hooks.record(feature, "network URL")
                throw IOException("Endpoint blocked by Speedtest Privacy")
            }
            chain.proceed()
        }
        // Throw within the interceptor chain so RealCall performs normal timeout/exchange cleanup.
        hooks.hook("okhttp3.internal.http.RealInterceptorChain", setOf("proceed"),
            NETWORK_GATE, { it.parameterCount == 1 && it.parameterTypes[0].name == "okhttp3.Request" &&
                it.returnType.name == "okhttp3.Response" },
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            val request = chain.args.firstOrNull()
            val url = request?.javaClass?.getMethod("url")?.invoke(request)?.toString()
            val feature = url?.let(NetworkPolicy::urlFeature)
            if (feature != null && Settings.enabled(feature)) {
                hooks.record(feature, "network OkHttp")
                throw IOException("Endpoint blocked by Speedtest Privacy")
            }
            chain.proceed()
        }
    }

    private fun mapboxBridge(hooks: HookInstaller) {
        val serviceName = "com.mapbox.common.module.okhttp.MapboxOkHttpService"
        val members = runCatching {
            val service = Class.forName(serviceName, false, hooks.loader)
            val request = Class.forName("com.mapbox.common.HttpRequest", false, hooks.loader)
            val callback = Class.forName("com.mapbox.common.HttpResponseCallback", false, hooks.loader)
            val expected = Class.forName("com.mapbox.bindgen.Expected", false, hooks.loader)
            val errorType = Class.forName("com.mapbox.common.HttpRequestErrorType", false, hooks.loader)
            val error = Class.forName("com.mapbox.common.HttpRequestError", false, hooks.loader)
            val ids = Class.forName("com.mapbox.common.module.okhttp.IdGenerator", false, hooks.loader)
                .getDeclaredMethod("getNewId").apply { isAccessible = true }
            val handleResponse = service.getDeclaredMethod("handleResponse", callback, request, expected)
                .apply { isAccessible = true }
            val url = request.getDeclaredMethod("getUrl")
            val expectedError = Class.forName("com.mapbox.bindgen.ExpectedFactory", false, hooks.loader)
                .getDeclaredMethod("createError", Any::class.java)
            check(url.returnType == String::class.java && !Modifier.isStatic(url.modifiers))
            check(ids.returnType == java.lang.Long.TYPE && Modifier.isStatic(ids.modifiers))
            check(handleResponse.returnType == Void.TYPE && !Modifier.isStatic(handleResponse.modifiers))
            check(expectedError.returnType == expected && Modifier.isStatic(expectedError.modifiers))
            val connectionError = errorType.enumConstants?.singleOrNull { (it as? Enum<*>)?.name == "CONNECTION_ERROR" }
                ?: error("Connection error enum contract unavailable")
            MapboxMembers(url, ids, connectionError,
                error.getDeclaredConstructor(errorType, String::class.java),
                expectedError, handleResponse)
        }.getOrElse {
            hooks.log("Mapbox HTTP bridge validation failed: ${it.javaClass.simpleName}")
            return
        }
        hooks.hook(serviceName, setOf("request"), NETWORK_GATE, {
            it.returnType == java.lang.Long.TYPE && it.parameterTypes.map { p -> p.name } ==
                listOf("com.mapbox.common.HttpRequest", "com.mapbox.common.HttpResponseCallback")
        }, exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH) { chain ->
            val url = members.url.invoke(chain.args[0]) as String
            val feature = NetworkPolicy.urlFeature(url)
            if (feature == null || !Settings.enabled(feature)) return@hook chain.proceed()
            val id = members.newId.invoke(null) as Long
            val error = members.error.newInstance(members.connectionError, "Endpoint blocked by Speedtest Privacy")
            val expected = members.expected.invoke(null, error)
            // Shipped offline handling calls this callback before returning the request ID.
            members.response.invoke(chain.thisObject, chain.args[1], chain.args[0], expected)
            hooks.record(feature, "Mapbox native HTTP bridge")
            val key = if (url.startsWith("https://api.mapbox.com/sdk-sessions/v1"))
                "internal.mapboxSessionBlocked" else "internal.mapboxTelemetryRequestBlocked"
            hooks.record(key, "Mapbox native HTTP bridge")
            id
        }
    }

    private data class MapboxMembers(
        val url: java.lang.reflect.Method,
        val newId: java.lang.reflect.Method,
        val connectionError: Any,
        val error: java.lang.reflect.Constructor<*>,
        val expected: java.lang.reflect.Method,
        val response: java.lang.reflect.Method
    )
    private const val NETWORK_GATE = "internal.networkGate"
}
