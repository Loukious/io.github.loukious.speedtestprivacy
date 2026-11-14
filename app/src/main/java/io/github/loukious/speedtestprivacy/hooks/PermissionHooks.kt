package io.github.loukious.speedtestprivacy.hooks

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.loukious.speedtestprivacy.core.HookInstaller
import io.github.loukious.speedtestprivacy.core.PermissionPolicy
import io.github.loukious.speedtestprivacy.core.Settings
import io.github.loukious.speedtestprivacy.core.TelemetryDiscovery

object PermissionHooks {
    fun install(hooks: HookInstaller) {
        val feature = Settings.PERMISSIONS
        for (clazz in arrayOf("android.content.Context", "android.app.ContextImpl")) {
            hooks.hook(clazz, setOf("checkSelfPermission", "checkCallingOrSelfPermission"), feature, {
                it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }) { chain ->
                if (PermissionPolicy.blocked(chain.args[0] as? String)) PackageManager.PERMISSION_DENIED
                else chain.proceed()
            }
        }
        hooks.hook("android.app.ContextImpl", setOf("checkPermission"), feature, {
            it.returnType == Int::class.javaPrimitiveType && it.parameterCount >= 3 &&
                it.parameterTypes[0] == String::class.java && it.parameterTypes[2] == Int::class.javaPrimitiveType
        }) { chain ->
            if (chain.args[2] == Process.myUid() && PermissionPolicy.blocked(chain.args[0] as? String))
                PackageManager.PERMISSION_DENIED else chain.proceed()
        }
        hooks.hook("android.app.ApplicationPackageManager", setOf("checkPermission"), feature, {
            it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
        }) { chain ->
            if (chain.args[1] == PermissionPolicy.TARGET && PermissionPolicy.blocked(chain.args[0] as? String))
                PackageManager.PERMISSION_DENIED else chain.proceed()
        }
        hooks.hook("android.app.ApplicationPackageManager", setOf("getPackageInfo"), feature, {
            it.returnType == PackageInfo::class.java && it.parameterTypes.firstOrNull() == String::class.java
        }) { chain ->
            val info = chain.proceed()
            if (chain.args[0] == PermissionPolicy.TARGET && info is PackageInfo) {
                val flags = info.requestedPermissionsFlags?.clone()
                info.requestedPermissions?.forEachIndexed { index, permission ->
                    if (flags != null && index < flags.size && PermissionPolicy.blocked(permission))
                        flags[index] = flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED.inv()
                }
                if (flags != null) info.requestedPermissionsFlags = flags
            }
            info
        }
        hooks.hook("android.app.Activity", setOf("requestPermissions"), feature, {
            it.returnType == Void.TYPE && it.parameterCount == 2 &&
                it.parameterTypes[0] == Array<String>::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }) { chain ->
            val permissions = (chain.args[0] as? Array<*>)?.filterIsInstance<String>()?.toTypedArray()
            val activity = chain.thisObject as? Activity
            if (permissions == null || activity == null || permissions.none(PermissionPolicy::blocked)) {
                chain.proceed()
            } else {
                val code = chain.args[1] as Int
                val results = PermissionPolicy.results(permissions) { activity.checkSelfPermission(it) }
                // Preserve AndroidX ActivityResultRegistry dispatch; Back is never simulated.
                Handler(Looper.getMainLooper()).post {
                    if (!activity.isDestroyed) activity.onRequestPermissionsResult(code, permissions, results)
                }
                null
            }
        }
        // Preserve normal denial callbacks when the app's renamed Back-classification gate changes.
        hooks.discovered(TelemetryDiscovery.FIXED_DENIAL, feature) { false }

        hooks.hook("android.app.AppOpsManager", setOf("checkOp", "checkOpNoThrow", "noteOp",
            "noteOpNoThrow", "unsafeCheckOp", "unsafeCheckOpNoThrow", "unsafeCheckOpRawNoThrow"), feature, {
            it.returnType == Int::class.javaPrimitiveType && it.parameterCount >= 3
        }) { chain ->
            val permission = when (val operation = chain.args.firstOrNull()) {
                // These two location operation IDs have been stable since AppOps was introduced.
                is Int -> when (operation) {
                    0 -> "android.permission.ACCESS_COARSE_LOCATION"
                    1 -> "android.permission.ACCESS_FINE_LOCATION"
                    else -> null
                }
                is String -> when (operation) {
                    "android:coarse_location" -> "android.permission.ACCESS_COARSE_LOCATION"
                    "android:fine_location" -> "android.permission.ACCESS_FINE_LOCATION"
                    "android:read_phone_state" -> "android.permission.READ_PHONE_STATE"
                    "android:post_notification" -> "android.permission.POST_NOTIFICATIONS"
                    else -> null
                }
                else -> null
            }
            if (chain.args[1] == Process.myUid() && PermissionPolicy.blocked(permission))
                android.app.AppOpsManager.MODE_IGNORED else chain.proceed()
        }
        val privateAuthorities = setOf("contacts", "com.android.contacts", "com.android.calendar",
            "call_log", "sms", "mms", "mms-sms", "media")
        hooks.hook("android.content.ContentResolver", setOf("query"), feature, {
            it.returnType.name == "android.database.Cursor" && it.parameterTypes.firstOrNull() == Uri::class.java
        }) { chain ->
            val uri = chain.args[0] as? Uri
            if (uri?.authority in privateAuthorities) {
                val projection = (chain.args.getOrNull(1) as? Array<*>)?.filterIsInstance<String>()?.toTypedArray()
                MatrixCursor(projection ?: arrayOf("_id"))
            } else chain.proceed()
        }
        hooks.constant("android.location.LocationManager", setOf("getLastKnownLocation"), feature,
            null, "android.location.Location")
        hooks.blockVoid("android.location.LocationManager", setOf("requestLocationUpdates",
            "requestSingleUpdate", "addNmeaListener", "registerGnssMeasurementsCallback"), feature)
        // Camera, microphone, storage, contacts, Bluetooth, etc. stay denied by OS permissions.
    }
}
