package io.github.loukious.speedtestprivacy.core

import android.content.pm.ApplicationInfo
import android.os.Bundle

object PrivacyMetadata {
    fun apply(info: ApplicationInfo) {
        if (!Settings.enabled(Settings.TELEMETRY)) return
        info.metaData = (info.metaData?.let(::Bundle) ?: Bundle()).apply {
            putBoolean("com.mapbox.EnableEvents", false)
            putBoolean("firebase_analytics_collection_enabled", false)
            putBoolean("firebase_analytics_collection_deactivated", true)
            putBoolean("firebase_crashlytics_collection_enabled", false)
            putBoolean("firebase_performance_collection_enabled", false)
            putBoolean("firebase_performance_collection_deactivated", true)
            putBoolean("firebase_messaging_auto_init_enabled", false)
            putBoolean("firebase_sessions_enabled", false)
            putBoolean("google_analytics_adid_collection_enabled", false)
            putBoolean("google_analytics_default_allow_analytics_storage", false)
            putBoolean("google_analytics_default_allow_ad_storage", false)
            putBoolean("google_analytics_default_allow_ad_user_data", false)
            putBoolean("google_analytics_default_allow_ad_personalization_signals", false)
        }
    }
}
