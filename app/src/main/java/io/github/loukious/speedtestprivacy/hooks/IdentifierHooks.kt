package io.github.loukious.speedtestprivacy.hooks

import java.lang.reflect.Modifier
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.Settings

object IdentifierHooks {
    fun install(hooks: HookInstaller) {
        val feature = Settings.IDENTIFIERS
        hooks.hook("android.provider.Settings\$Secure", setOf("getString", "getStringForUser"), feature, {
            it.returnType == String::class.java && it.parameterCount >= 2 && it.parameterTypes[1] == String::class.java
        }) { chain -> if (chain.args[1] == "android_id") "0000000000000000" else chain.proceed() }
        hooks.constant("android.os.Build", setOf("getSerial"), feature, "unknown", "java.lang.String", 0)
        hooks.constant("android.telephony.TelephonyManager", setOf("getDeviceId", "getImei", "getMeid",
            "getSubscriberId", "getSimSerialNumber", "getLine1Number", "getMsisdn", "getNai"),
            feature, "", "java.lang.String")
        hooks.constant("android.telephony.TelephonyManager", setOf("getAllCellInfo"),
            feature, emptyList<Any>(), "java.util.List", 0)
        hooks.constant("android.telephony.TelephonyManager", setOf("getCellLocation"),
            feature, null, "android.telephony.CellLocation", 0)
        hooks.constant("android.net.wifi.WifiInfo", setOf("getSSID"), feature,
            "<unknown ssid>", "java.lang.String", 0)
        hooks.constant("android.net.wifi.WifiInfo", setOf("getBSSID", "getMacAddress"), feature,
            "02:00:00:00:00:00", "java.lang.String", 0)
        hooks.constant("java.net.NetworkInterface", setOf("getHardwareAddress"), feature, null, "[B", 0)
        val infoConstructor = runCatching {
            Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info", false, hooks.loader)
                .getConstructor(String::class.java, Boolean::class.javaPrimitiveType)
        }.getOrNull()
        if (infoConstructor != null) {
            hooks.hook("com.google.android.gms.ads.identifier.AdvertisingIdClient", setOf("getAdvertisingIdInfo", "getInfo"),
                feature, { method -> method.returnType == infoConstructor.declaringClass &&
                    ((method.name == "getAdvertisingIdInfo" && Modifier.isStatic(method.modifiers) && method.parameterCount == 1 &&
                        method.parameterTypes[0].name == "android.content.Context") ||
                        (method.name == "getInfo" && !Modifier.isStatic(method.modifiers) && method.parameterCount == 0)) }) {
                infoConstructor.newInstance("00000000-0000-0000-0000-000000000000", true)
            }
        } else {
            hooks.targetFailure("identifiers.gaid.factory", "Synthetic advertising Info constructor unavailable")
        }
        hooks.constant("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info", setOf("getId"),
            feature, "00000000-0000-0000-0000-000000000000", "java.lang.String", 0)
        hooks.constant("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
            setOf("isLimitAdTrackingEnabled"), feature, true, "boolean", 0)
    }
}
