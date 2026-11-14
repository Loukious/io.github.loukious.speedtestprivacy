package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.*
import org.junit.Test

class PrivacyPolicyTest {
    @Test fun preservesNetworkTestPermissions() {
        for (permission in listOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE",
            "ACCESS_LOCAL_NETWORK", "WAKE_LOCK", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC")) {
            assertFalse(permission, PermissionPolicy.blocked("android.permission.$permission"))
        }
    }

    @Test fun deniedCallbackPreservesOrderAndCurrentNetworkGrant() {
        val permissions = arrayOf("android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.INTERNET", "android.permission.READ_PHONE_STATE",
            "android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA")
        val lookedUp = mutableListOf<String>()
        val results = PermissionPolicy.results(permissions) { lookedUp += it; 0 }
        assertArrayEquals(intArrayOf(-1, 0, -1, -1, -1), results)
        assertEquals(listOf("android.permission.INTERNET"), lookedUp)
    }

    @Test fun unknownOptionalPermissionsAreDenied() {
        assertTrue(PermissionPolicy.blocked("android.permission.FUTURE_SENSOR_PERMISSION"))
        assertTrue(PermissionPolicy.blocked("com.google.android.gms.permission.AD_ID"))
        assertTrue(PermissionPolicy.blocked("android.permission.ACCESS_ADSERVICES_TOPICS"))
    }

    @Test fun domainsMatchOnDnsBoundariesOnly() {
        assertEquals(Settings.ADS, NetworkPolicy.hostFeature("GOOGLEADS.G.DOUBLECLICK.NET."))
        assertEquals(Settings.BACKGROUND, NetworkPolicy.hostFeature("metricreceiver.cellrebel.com"))
        assertNull(NetworkPolicy.hostFeature("notdoubleclick.net"))
        assertNull(NetworkPolicy.hostFeature("doubleclick.net.example.org"))
        assertNull(NetworkPolicy.hostFeature("api.speedtest.net"))
    }

    @Test fun blocksReportEndpointsAndKeepsActualTestPaths() {
        assertEquals(Settings.TELEMETRY, NetworkPolicy.urlFeature("https://mapi.speedtest.net/reports"))
        assertEquals(Settings.TELEMETRY, NetworkPolicy.urlFeature("https://results.speedtest.net/reports/42?x=1"))
        assertNull(NetworkPolicy.urlFeature("https://mapi.speedtest.net/reports-example"))
        assertNull(NetworkPolicy.urlFeature("https://mapi.speedtest.net/servers"))
        assertNull(NetworkPolicy.urlFeature("https://api.speedtest.net/config"))
        assertNull(NetworkPolicy.urlFeature("https://testserver.example:8080/upload.php"))
        assertNull(NetworkPolicy.urlFeature("https://testserver.example:8080/download"))
        assertNull(NetworkPolicy.urlFeature("invalid url"))
    }

    @Test fun blocksOnlyExactLegacySubmissionRoutes() {
        assertEquals(Settings.TELEMETRY, NetworkPolicy.urlFeature("https://WWW.SPEEDTEST.NET./api/android.php?x=1"))
        assertEquals(Settings.TELEMETRY, NetworkPolicy.urlFeature("https://mobileapi.speedtest.net/androidextra"))
        assertEquals(Settings.TELEMETRY, NetworkPolicy.urlFeature("https://mobileapi.speedtest.net/androidextra/?x=1"))
        for (url in listOf(
            "https://www.speedtest.net/speedtest-config.php",
            "https://www.speedtest.net/api/android.php-extra",
            "https://mobileapi.speedtest.net/androidextra-config",
            "https://www.speedtest.net.example.org/api/android.php",
            "https://testserver.example/api/android.php",
            "https://testserver.example/androidextra"
        )) assertNull(url, NetworkPolicy.urlFeature(url))
    }

    @Test fun blocksMapboxReportingWithoutBlockingMapResources() {
        for (url in listOf(
            "https://api.mapbox.com/sdk-sessions/v1?access_token=example",
            "https://api.mapbox.com/sdk-sessions/v1/session",
            "https://config.mapbox.com/events-config"
        )) assertEquals(url, Settings.TELEMETRY, NetworkPolicy.urlFeature(url))
        for (url in listOf(
            "https://api.mapbox.com/styles/v1/ookla/style",
            "https://api.mapbox.com/fonts/v1/mapbox/font/0-255.pbf",
            "https://api.mapbox.com/v4/mapbox.streets/4/8/6.vector.pbf",
            "https://api.mapbox.com/sdk-sessions/v10",
            "https://config.mapbox.com/events-config-example",
            "https://api.mapbox.com.example.org/sdk-sessions/v1",
            "https://testserver.example/sdk-sessions/v1"
        )) assertNull(url, NetworkPolicy.urlFeature(url))
    }
}
