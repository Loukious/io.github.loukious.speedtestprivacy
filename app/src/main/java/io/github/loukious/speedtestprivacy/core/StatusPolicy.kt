package io.github.loukious.speedtestprivacy.core

/** Status reporting never authorizes hooks or changes protection switches. */
object StatusPolicy {
    const val PREFS = "runtime_health"
    const val KEY = "snapshot"
    const val MAX_REPORT_LENGTH = 64 * 1024

    fun mayPublish(callingUid: Int, targetUid: Int): Boolean = targetUid >= 0 && callingUid == targetUid

    fun summary(prepared: Boolean, complete: Boolean, verified: Boolean, criticalMissing: Int,
                runtimeErrors: Int, failedChecks: Int, skippedFamilies: Int,
                pendingChecks: Int = 0, checksTimedOut: Boolean = false): String = when {
        !prepared || !complete -> "initializing"
        criticalMissing > 0 || runtimeErrors > 0 || failedChecks > 0 -> "partial"
        !verified || skippedFamilies > 0 -> "partial"
        pendingChecks > 0 -> if (checksTimedOut) "partial" else "initializing"
        else -> "matched"
    }
}
