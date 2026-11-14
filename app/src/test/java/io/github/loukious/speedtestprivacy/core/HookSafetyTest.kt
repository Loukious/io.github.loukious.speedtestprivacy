package io.github.loukious.speedtestprivacy.core

import org.junit.Assert.*
import org.junit.Test

class HookSafetyTest {
    @Test fun obfuscatedMethodsRequireTheVerifiedArtifactEvenWhenSignaturesCouldStillMatch() {
        assertTrue(HookSafety.requiresVerifiedProfile("com.ookla.speedtest.ads.e", "h"))
        assertTrue(HookSafety.requiresVerifiedProfile("com.google.android.gms.internal.measurement.u1", "C"))
        assertTrue(HookSafety.requiresVerifiedProfile("com.google.firebase.analytics.FirebaseAnalytics", "a"))
        assertTrue(HookSafety.requiresVerifiedProfile("org.prebid.mobile.AdUnit", "h"))
    }

    @Test fun publicPlatformAndSdkGuardsContinueOnUnknownBuilds() {
        assertFalse(HookSafety.requiresVerifiedProfile("android.app.Activity", "requestPermissions"))
        assertFalse(HookSafety.requiresVerifiedProfile("java.net.URL", "openConnection"))
        assertFalse(HookSafety.requiresVerifiedProfile("okhttp3.internal.http.RealInterceptorChain", "proceed"))
        assertFalse(HookSafety.requiresVerifiedProfile("com.mapbox.common.TelemetryUtils", "setEventsCollectionState"))
        assertFalse(HookSafety.requiresVerifiedProfile("com.google.android.gms.ads.MobileAds", "initialize"))
    }

    @Test fun obsoleteReportsAndIncompleteCoverageNeverBecomeMatched() {
        assertEquals("initializing", StatusPolicy.summary(false, false, false, 0, 0, 0, 0))
        assertEquals("partial", StatusPolicy.summary(true, true, false, 0, 0, 0, 0))
        assertEquals("partial", StatusPolicy.summary(true, true, true, 1, 0, 0, 0))
        assertEquals("partial", StatusPolicy.summary(true, true, true, 0, 1, 0, 0))
        assertEquals("partial", StatusPolicy.summary(true, true, true, 0, 0, 1, 0))
        assertEquals("matched", StatusPolicy.summary(true, true, true, 0, 0, 0, 0))
        assertEquals("initializing", StatusPolicy.summary(true, true, true, 0, 0, 0, 0, 1, false))
        assertEquals("partial", StatusPolicy.summary(true, true, true, 0, 0, 0, 0, 1, true))
    }

    @Test fun otherAppsCannotPublishSpeedtestHealth() {
        assertTrue(StatusPolicy.mayPublish(10462, 10462))
        assertFalse(StatusPolicy.mayPublish(10463, 10462))
        assertFalse(StatusPolicy.mayPublish(2000, 10462))
        assertFalse(StatusPolicy.mayPublish(0, -1))
    }
}
