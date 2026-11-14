package io.github.loukious.speedtestprivacy.core

/** Reviewed named APIs remain usable; obfuscated names require the verified artifact. */
object HookSafety {
    fun requiresVerifiedProfile(className: String, methodName: String): Boolean = when {
        className.startsWith("com.ookla.") -> true
        className.startsWith("com.google.android.gms.internal.") -> true
        className.startsWith("com.google.android.gms.ads.internal.") -> true
        className.startsWith("com.google.firebase.") ->
            className.substringAfterLast('.').length <= 2 || methodName.length <= 2
        className.startsWith("org.prebid.") -> methodName.length <= 2
        else -> false
    }
}
