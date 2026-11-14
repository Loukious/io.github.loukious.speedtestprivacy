package io.github.loukious.speedtestprivacy.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

/** Semantic targets verified against raw Speedtest 7.1.4 DEX, never JADX aliases. */
object AdDiscovery {
    private const val ADS_FREE = "isCoreAdsFree:Boolean"
    private const val BANNER_UNIT = "/6692/speedtest.net/st_mobile_banner"
    private const val LIFECYCLE = "android.app.Application\$ActivityLifecycleCallbacks"
    private const val PREBID = "org.prebid.mobile."
    private const val GOOGLE_INFO = "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info"

    val targets = listOf(
        SemanticTarget(
            key = "ads.state.compute",
            description = "Advertising state calculator",
            query = ::stateCalculators,
            validate = { method ->
                instance(method, "int") && Modifier.isPrivate(method.modifiers) && adsStateOwner(method.declaringClass)
            }
        ),
        SemanticTarget(
            key = "ads.state.get",
            description = "Published advertising state",
            query = { bridge ->
                val calculator = stateCalculators(bridge).singleOrNull()
                calculator?.declaredClass?.methods.orEmpty().filter {
                    instance(it, "int") && Modifier.isPublic(it.modifiers) && readsOwnField(it, "int")
                }
            },
            validate = { method ->
                instance(method, "int") && Modifier.isPublic(method.modifiers) && adsStateOwner(method.declaringClass) &&
                    method.declaringClass.declaredMethods.count { instance(it, "int") && Modifier.isPublic(it.modifiers) } == 1
            }
        ),
        SemanticTarget(
            key = "ads.banner.allowed",
            description = "Banner eligibility policy",
            query = { bridge ->
                bridge.findClass { matcher { usingEqStrings(BANNER_UNIT, "BannerAd", "%s: loadAd", "%s: moveToIdle") } }
                    .filter { appOwner(it.name) && hasFields(it, "android.app.Activity", "android.view.ViewGroup",
                        "com.google.android.gms.ads.admanager.AdManagerAdView") }
                    .flatMap { owner -> owner.methods.filter {
                        instance(it, "boolean") && Modifier.isPublic(it.modifiers) && readsOwnField(it, "boolean")
                    } }
            },
            validate = { method ->
                instance(method, "boolean") && Modifier.isPublic(method.modifiers) && appOwner(method.declaringClass.name) &&
                    hasFields(method.declaringClass, "android.app.Activity", "android.view.ViewGroup",
                        "com.google.android.gms.ads.admanager.AdManagerAdView") &&
                    method.declaringClass.declaredMethods.count { instance(it, "boolean") && Modifier.isPublic(it.modifiers) } == 1
            }
        ),
        SemanticTarget(
            key = "ads.amazon.initialize",
            description = "App Amazon advertising initializer",
            query = { bridge ->
                bridge.findMethod {
                    matcher {
                        returnType = "void"
                        paramCount = 0
                        addInvoke("Lcom/amazon/device/ads/AdRegistration;->getInstance(Ljava/lang/String;Landroid/content/Context;)Lcom/amazon/device/ads/AdRegistration;")
                        addInvoke("Lcom/amazon/device/ads/AdRegistration;->useGeoLocation(Z)V")
                    }
                }.filter { instance(it, "void") && Modifier.isProtected(it.modifiers) && appOwner(it.declaredClassName) }
            },
            validate = { instance(it, "void") && Modifier.isProtected(it.modifiers) && appOwner(it.declaringClass.name) }
        ),
        SemanticTarget(
            key = "ads.prebid.initialize",
            description = "App Prebid advertising initializer",
            query = { bridge ->
                bridge.findMethod { matcher { usingEqStrings("Prebid SDK is already initialized."); returnType = "void"; paramCount = 0 } }
                    .filter { method -> instance(method, "void") && Modifier.isPublic(method.modifiers) && appOwner(method.declaredClassName) &&
                        method.invokes.any { it.declaredClassName.startsWith(PREBID) && prebidInitializeShape(it) } }
            },
            validate = { instance(it, "void") && Modifier.isPublic(it.modifiers) && appOwner(it.declaringClass.name) }
        ),
        SemanticTarget(
            key = "ads.prebid.fetch",
            description = "Prebid demand request with completion callback",
            query = { bridge ->
                bridge.findMethod { matcher { usingEqStrings("Fetch demand called", "Empty account id.", "Empty config id.") } }
                    .filter { instance(it, "void", "java.lang.Object", null) && Modifier.isPublic(it.modifiers) &&
                        it.declaredClassName.startsWith(PREBID) }
            },
            validate = { method ->
                instance(method, "void", "java.lang.Object", null) && Modifier.isPublic(method.modifiers) &&
                    method.declaringClass.name.startsWith(PREBID) && enumCallback(method.parameterTypes[1]) != null
            }
        ),
        SemanticTarget(
            key = "ads.prebid.sdkInitialize",
            description = "Prebid SDK initializer with completion callback",
            query = { bridge ->
                bridge.findMethod { matcher { usingEqStrings("initializeSdk: serverURL is null.") } }
                    .filter { prebidInitializeShape(it) && Modifier.isPublic(it.modifiers) && it.declaredClassName.startsWith(PREBID) }
            },
            validate = { method ->
                shape(method, "void", "android.content.Context", "java.lang.String", null) &&
                    Modifier.isStatic(method.modifiers) && Modifier.isPublic(method.modifiers) &&
                    method.declaringClass.name.startsWith(PREBID) && enumCallback(method.parameterTypes[2]) != null
            }
        ),
        SemanticTarget(
            key = "identifiers.config.enrich",
            description = "Configuration advertising identifier collector",
            query = ::configCollectors,
            validate = { method ->
                instance(method, "void", "java.util.Map") && Modifier.isPrivate(method.modifiers) && configOwner(method.declaringClass)
            }
        ),
        SemanticTarget(
            key = "identifiers.config.params",
            description = "Final configuration parameter map",
            query = { bridge ->
                configCollectors(bridge).singleOrNull()?.declaredClass?.methods.orEmpty().filter {
                    instance(it, "java.util.Map") && Modifier.isPublic(it.modifiers)
                }
            },
            validate = { method ->
                instance(method, "java.util.Map") && Modifier.isPublic(method.modifiers) && configOwner(method.declaringClass) &&
                    method.declaringClass.declaredMethods.count { instance(it, "java.util.Map") && Modifier.isPublic(it.modifiers) } == 1
            }
        ),
        SemanticTarget(
            key = "identifiers.report.allowField",
            description = "Report field inclusion policy",
            query = { bridge -> denyListOwners(bridge).flatMap { it.methods.filter { method ->
                instance(method, "boolean", "java.lang.String") && Modifier.isPublic(method.modifiers)
            } } },
            validate = { instance(it, "boolean", "java.lang.String") && Modifier.isPublic(it.modifiers) && denyListOwner(it.declaringClass) }
        ),
        SemanticTarget(
            key = "identifiers.report.allowPath",
            description = "Report path inclusion policy",
            query = { bridge -> denyListOwners(bridge).flatMap { it.methods.filter { method ->
                instance(method, "boolean", null) && Modifier.isPublic(method.modifiers) &&
                    method.paramTypeNames.single() in setOf("java.lang.String[]", "[Ljava.lang.String;")
            } } },
            validate = { instance(it, "boolean", "[Ljava.lang.String;") && Modifier.isPublic(it.modifiers) && denyListOwner(it.declaringClass) }
        ),
        SemanticTarget(
            key = "identifiers.report.serialize",
            description = "Final report batch serializer",
            query = { bridge ->
                bridge.findMethod { matcher { usingEqStrings("user", "gaid", "gaidOptOut") } }
                    .filter { instance(it, "org.json.JSONObject", "org.json.JSONObject") && appOwner(it.declaredClassName) }
                    .mapNotNull { it.declaredClass }.distinctBy { it.name }
                    .filter { owner -> hasFields(owner, "java.util.List", "okhttp3.OkHttpClient") &&
                        owner.methods.any { "BatchReportProcessor" in it.usingStrings } }
                    .flatMap { it.methods.filter { method -> instance(method, "org.json.JSONArray") } }
            },
            validate = { method ->
                instance(method, "org.json.JSONArray") && appOwner(method.declaringClass.name) &&
                    hasFields(method.declaringClass, "java.util.List", "okhttp3.OkHttpClient") &&
                    method.declaringClass.declaredMethods.count { instance(it, "org.json.JSONArray") } == 1
            }
        )
    )

    /** Prebid preserves callback contracts while renaming their methods and types. */
    fun enumCallback(type: Class<*>): Method? {
        if (!type.isInterface) return null
        val method = type.methods.filter { Modifier.isAbstract(it.modifiers) }.singleOrNull() ?: return null
        return method.takeIf { it.returnType == Void.TYPE && it.parameterCount == 1 && it.parameterTypes[0].isEnum }
    }

    private fun stateCalculators(bridge: DexKitBridge): List<MethodData> =
        bridge.findMethod { matcher { usingEqStrings(ADS_FREE); returnType = "int"; paramCount = 0 } }
            .filter { instance(it, "int") && Modifier.isPrivate(it.modifiers) && appOwner(it.declaredClassName) &&
                it.declaredClass?.interfaces?.any { iface -> iface.name == LIFECYCLE } == true }

    private fun configCollectors(bridge: DexKitBridge): List<MethodData> =
        bridge.findMethod { matcher { usingEqStrings("gaid", "gaidOptOut"); returnType = "void"; paramTypes("java.util.Map") } }
            .filter { method -> instance(method, "void", "java.util.Map") && Modifier.isPrivate(method.modifiers) &&
                appOwner(method.declaredClassName) && method.invokes.any { it.declaredClassName == GOOGLE_INFO &&
                    it.name == "getId" && it.returnTypeName == "java.lang.String" && it.paramCount == 0 } &&
                method.invokes.any { it.declaredClassName == GOOGLE_INFO && it.name == "isLimitAdTrackingEnabled" &&
                    it.returnTypeName == "boolean" && it.paramCount == 0 } }

    private fun denyListOwners(bridge: DexKitBridge): List<ClassData> =
        bridge.findClass { matcher { usingEqStrings("ReportDenyListImpl(deniedFields=", ", subReportDenyLists=") } }
            .filter { appOwner(it.name) && Modifier.isFinal(it.modifiers) && hasFields(it, "java.util.Set", "java.util.Map") }

    private fun adsStateOwner(type: Class<*>): Boolean = appOwner(type.name) && type.interfaces.any { it.name == LIFECYCLE }
    private fun configOwner(type: Class<*>): Boolean = appOwner(type.name) && hasFields(type, "android.content.Context", "java.util.concurrent.CountDownLatch")
    private fun denyListOwner(type: Class<*>): Boolean = appOwner(type.name) && Modifier.isFinal(type.modifiers) && hasFields(type, "java.util.Set", "java.util.Map")
    private fun appOwner(name: String): Boolean = name.startsWith("com.ookla.")
    private fun hasFields(type: Class<*>, vararg names: String): Boolean = names.all { name -> type.declaredFields.any { it.type.name == name && !Modifier.isStatic(it.modifiers) } }
    private fun hasFields(type: ClassData, vararg names: String): Boolean = names.all { name -> type.fields.any { it.typeName == name && !Modifier.isStatic(it.modifiers) } }
    private fun readsOwnField(method: MethodData, type: String): Boolean = method.usingFields.singleOrNull()?.let {
        it.usingType == FieldUsingType.Read && it.field.declaredClassName == method.declaredClassName && it.field.typeName == type
    } == true
    private fun prebidInitializeShape(method: MethodData): Boolean = shape(method, "void", "android.content.Context", "java.lang.String", null) && Modifier.isStatic(method.modifiers)
    private fun instance(method: Method, result: String, vararg params: String?): Boolean = shape(method, result, *params) && !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers)
    private fun instance(method: MethodData, result: String, vararg params: String?): Boolean = shape(method, result, *params) && !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers)
    private fun shape(method: Method, result: String, vararg params: String?): Boolean = method.returnType.name == result && method.parameterCount == params.size && params.indices.all { params[it] == null || method.parameterTypes[it].name == params[it] }
    private fun shape(method: MethodData, result: String, vararg params: String?): Boolean = method.isMethod && method.returnTypeName == result && method.paramCount == params.size && params.indices.all { params[it] == null || method.paramTypeNames[it] == params[it] }
}
