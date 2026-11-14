package io.github.loukious.speedtestprivacy.core

object PermissionPolicy {
    const val TARGET = "org.zwanoo.android.speedtest"
    // The user's explicit exception: retain network tests and their foreground execution.
    val allowed = setOf(
        "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE", "android.permission.ACCESS_LOCAL_NETWORK",
        "android.permission.WAKE_LOCK", "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "$TARGET.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    )
    fun blocked(permission: String?): Boolean = permission != null && permission !in allowed
    fun results(permissions: Array<String>, current: (String) -> Int): IntArray =
        IntArray(permissions.size) { if (blocked(permissions[it])) -1 else current(permissions[it]) }
}
