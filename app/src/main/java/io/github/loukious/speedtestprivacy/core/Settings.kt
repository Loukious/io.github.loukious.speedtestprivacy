package io.github.loukious.speedtestprivacy.core

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

object Settings {
    const val GROUP = "speedtest_privacy"
    const val PERMISSIONS = "block.permissions"
    const val ADS = "block.ads"
    const val TELEMETRY = "block.telemetry"
    const val BACKGROUND = "block.background"
    const val IDENTIFIERS = "block.identifiers"
    private var preferences: SharedPreferences? = null

    fun initialize(module: XposedInterface) {
        preferences = runCatching { module.getRemotePreferences(GROUP) }.getOrNull()
    }

    fun enabled(feature: String): Boolean =
        runCatching { preferences?.getBoolean(feature, true) ?: true }.getOrDefault(true)
}
