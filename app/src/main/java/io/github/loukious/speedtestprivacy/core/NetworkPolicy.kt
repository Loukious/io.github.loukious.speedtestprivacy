package io.github.loukious.speedtestprivacy.core

import java.net.URI
import java.util.Locale

/** Explicit endpoint matching; no substring matching against legitimate test servers. */
object NetworkPolicy {
    private val adDomains = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "amazon-adsystem.com", "adsrvr.org", "adnxs.com")
    private val telemetryDomains = setOf("app-measurement.com", "google-analytics.com",
        "firebasecrashlytics.googleapis.com", "firebaselogging-pa.googleapis.com",
        "firebase-settings.crashlytics.com", "crashlytics.com", "sb.scorecardresearch.com",
        "scorecardresearch.com", "events.mapbox.com")
    private val backgroundDomains = setOf("cellrebel.com")

    private fun normalize(host: String) = host.lowercase(Locale.ROOT).trimEnd('.')
    private fun matches(host: String, domains: Set<String>): Boolean =
        domains.any { host == it || host.endsWith(".$it") }

    fun hostFeature(host: String): String? {
        val normalized = normalize(host)
        return when {
            matches(normalized, adDomains) -> Settings.ADS
            matches(normalized, telemetryDomains) -> Settings.TELEMETRY
            matches(normalized, backgroundDomains) -> Settings.BACKGROUND
            else -> null
        }
    }

    fun urlFeature(url: String): String? = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return@runCatching null
        hostFeature(host)?.let { return@runCatching it }
        val normalized = normalize(host)
        val path = uri.path ?: ""
        when {
            normalized in setOf("mapi.speedtest.net", "results.speedtest.net") &&
                (path == "/reports" || path.startsWith("/reports/")) -> Settings.TELEMETRY
            normalized == "www.speedtest.net" && path == "/api/android.php" -> Settings.TELEMETRY
            normalized == "mobileapi.speedtest.net" && path in setOf("/androidextra", "/androidextra/") -> Settings.TELEMETRY
            normalized == "api.mapbox.com" &&
                (path == "/sdk-sessions/v1" || path.startsWith("/sdk-sessions/v1/")) -> Settings.TELEMETRY
            normalized == "config.mapbox.com" && path in setOf("/events-config", "/events-config/") -> Settings.TELEMETRY
            else -> null
        }
    }.getOrNull()
}
