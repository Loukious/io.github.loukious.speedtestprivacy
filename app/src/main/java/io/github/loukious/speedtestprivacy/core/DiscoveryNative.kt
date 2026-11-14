package io.github.loukious.speedtestprivacy.core

/** Loads DexKit with the production class loader that owns its native-library search path. */
object DiscoveryNative {
    private var loaded = false

    // Keep this method non-inline: Android resolves libraries using the caller's class loader.
    // The module loader supports both extracted libraries and APK!/lib direct loading.
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("dexkit")
        loaded = true
    }
}
