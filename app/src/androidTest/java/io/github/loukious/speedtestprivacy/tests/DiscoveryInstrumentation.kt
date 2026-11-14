package io.github.loukious.speedtestprivacy.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import io.github.loukious.speedtestprivacy.BuildConfig
import io.github.loukious.speedtestprivacy.core.DiscoveryEngine
import io.github.loukious.speedtestprivacy.core.DiscoveryNative
import io.github.loukious.speedtestprivacy.core.DiscoverySnapshot
import io.github.loukious.speedtestprivacy.core.DiscoveryStatus
import io.github.loukious.speedtestprivacy.core.PermissionPolicy
import io.github.loukious.speedtestprivacy.core.RuntimeStatusChannel
import io.github.loukious.speedtestprivacy.core.SemanticTarget
import io.github.loukious.speedtestprivacy.core.StatusPolicy
import io.github.loukious.speedtestprivacy.ui.RuntimeStatusEndpoint
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.util.UUID

/** Real ART/DEX/cache integration without AndroidX, JUnit, hooks, or a Speedtest data directory. */
class DiscoveryInstrumentation : Instrumentation() {
    private var verifyLiveStatus = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        verifyLiveStatus = arguments?.getString("verifyLiveStatus")?.equals("true", ignoreCase = true) == true
        start()
    }

    override fun onStart() {
        val cases = JSONArray()
        val logs = mutableListOf<String>()
        val results = Bundle()
        var failures = 0
        var directory: File? = null
        fun runCase(name: String, action: () -> JSONObject) {
            sendStatus(0, Bundle().apply { putString("stream", "\nRunning $name\n") })
            val started = System.nanoTime()
            val result = JSONObject().put("name", name)
            try {
                result.put("passed", true).put("details", action())
            } catch (error: Throwable) {
                failures++
                result.put("passed", false).put("error", error.toString()).put("trace", error.stackTraceToString())
            }
            result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            cases.put(result)
        }

        try {
            DiscoveryNative.ensureLoaded()
            val runDirectory = File(targetContext.cacheDir, "discovery-instrumentation/${UUID.randomUUID()}")
            check(runDirectory.mkdirs()) { "Cannot create isolated instrumentation workspace" }
            directory = runDirectory
            val a = copyFixture("fixture-a.apk", runDirectory)
            val b = copyFixture("fixture-b.apk", runDirectory)
            val c = copyFixture("fixture-c.apk", runDirectory)
            val duplicate = copyFixture("fixture-duplicate.apk", runDirectory)
            val shadowBase = copyFixture("fixture-d.apk", runDirectory)
            val shadowSplit = copyFixture("fixture-e.apk", runDirectory)
            val sharedCache = File(runDirectory, "shared-cache")
            val contextA = FixtureContext(targetContext, a, emptyList(), sharedCache)
            val contextB = FixtureContext(targetContext, b, emptyList(), sharedCache)
            val contextC = FixtureContext(targetContext, c, emptyList(), File(runDirectory, "ambiguous-cache"))
            val contextSplit = FixtureContext(targetContext, a, listOf(duplicate), File(runDirectory, "split-cache"))
            val contextShadow = FixtureContext(targetContext, shadowBase, listOf(shadowSplit), File(runDirectory, "shadow-cache"))
            val loaderA = fixtureLoader(contextA, runDirectory, "a")
            val loaderB = fixtureLoader(contextB, runDirectory, "b")
            val loaderC = fixtureLoader(contextC, runDirectory, "c")
            val loaderSplit = fixtureLoader(contextSplit, runDirectory, "split")
            val loaderShadow = fixtureLoader(contextShadow, runDirectory, "shadow")
            val target = SemanticTarget(TARGET, "Same semantic marker across renamed DEX fixtures", query = { bridge ->
                bridge.findMethod {
                    matcher {
                        addUsingString(MARKER, StringMatchType.Equals)
                        returnType("java.lang.String")
                        paramTypes("java.lang.String")
                    }
                }.filter { Modifier.isStatic(it.modifiers) && it.isMethod }
            }, validate = { method ->
                Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isNative(method.modifiers) && method.returnType == String::class.java &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java))
            })
            fun prepare(context: Context, loader: ClassLoader): DiscoverySnapshot =
                DiscoveryEngine(loader, listOf(target)) { logs += it }.prepare(context)

            var firstA: DiscoverySnapshot? = null
            var firstB: DiscoverySnapshot? = null
            runCase("fresh A discovers and invokes") {
                prepare(contextA, loaderA).also { firstA = it }.let { snapshot ->
                    assertFound(snapshot, false, "fixture.before.Endpoint", "transmit", ":A:")
                    check(positiveCache(contextA).isFile) { "Positive A snapshot was not cached" }
                    describe(snapshot)
                }
            }
            runCase("A cache resolves the same executable method") {
                val previous = checkNotNull(firstA) { "Fresh A did not complete" }
                val snapshot = prepare(contextA, loaderA)
                assertFound(snapshot, true, "fixture.before.Endpoint", "transmit", ":A:")
                check(snapshot.identity.fingerprint == previous.identity.fingerprint)
                describe(snapshot)
            }
            runCase("renamed B invalidates A despite unchanged package version") {
                val previous = checkNotNull(firstA) { "Fresh A did not complete" }
                val snapshot = prepare(contextB, loaderB).also { firstB = it }
                check(snapshot.identity.versionCode == previous.identity.versionCode) { "Fixture test changed the package version" }
                check(snapshot.identity.fingerprint != previous.identity.fingerprint) { "Different APK bytes have the same identity" }
                assertFound(snapshot, false, "fixture.renamed.R9", "q", ":B:")
                check(positiveCache(contextB).isFile) { "Positive B snapshot was not cached" }
                describe(snapshot)
            }
            runCase("B cache preserves its renamed class and method") {
                val previous = checkNotNull(firstB) { "Fresh B did not complete" }
                val snapshot = prepare(contextB, loaderB)
                assertFound(snapshot, true, "fixture.renamed.R9", "q", ":B:")
                check(snapshot.identity.fingerprint == previous.identity.fingerprint)
                describe(snapshot)
            }
            runCase("ambiguous C yields no method and no positive cache") {
                val snapshot = prepare(contextC, loaderC)
                assertAmbiguous(snapshot, contextC, 2)
                describe(snapshot)
            }
            runCase("ambiguous C is rediscovered instead of negative cached") {
                val snapshot = prepare(contextC, loaderC)
                assertAmbiguous(snapshot, contextC, 2)
                describe(snapshot)
            }
            runCase("duplicate descriptor in an executable split is rejected") {
                val snapshot = prepare(contextSplit, loaderSplit)
                check(snapshot.identity.apkPaths.size == 2) { "Executable split was omitted from the APK identity" }
                assertAmbiguous(snapshot, contextSplit, null)
                describe(snapshot)
            }
            runCase("duplicate split rejection is not cached") {
                val snapshot = prepare(contextSplit, loaderSplit)
                assertAmbiguous(snapshot, contextSplit, null)
                describe(snapshot)
            }
            runCase("unanchored base shadows an anchored split and is rejected") {
                val loadedValue = loaderShadow.loadClass("fixture.shadowing.Endpoint")
                    .getDeclaredMethod("transmit", String::class.java).invoke(null, "payload")
                check(loadedValue == "shadow-base:payload") { "Fixture loader did not select the unanchored base: $loadedValue" }
                val snapshot = prepare(contextShadow, loaderShadow)
                check(snapshot.identity.apkPaths.size == 2) { "Shadowing split was omitted from the APK identity" }
                assertAmbiguous(snapshot, contextShadow, null)
                describe(snapshot).put("loadedValue", loadedValue)
            }
            runCase("base-first shadowing rejection is not cached") {
                val snapshot = prepare(contextShadow, loaderShadow)
                assertAmbiguous(snapshot, contextShadow, null)
                describe(snapshot)
            }
            runCase("module UID cannot publish a forged Speedtest status") {
                rejectForgedPublication()
            }
            if (verifyLiveStatus) runCase("real Speedtest Binder reply verifies the current module namespace") {
                receiveLiveStatus()
            }
        } catch (error: Throwable) {
            failures++
            cases.put(JSONObject().put("name", "instrumentation setup").put("passed", false)
                .put("error", error.toString()).put("trace", error.stackTraceToString()))
        }

        val report = JSONObject().put("passed", failures == 0).put("tests", cases.length())
            .put("failures", failures).put("moduleUid", Process.myUid()).put("cases", cases)
            .put("discoveryLogs", JSONArray(logs))
        runCatching {
            directory?.let { output ->
                val reportFile = File(output, "report.json")
                reportFile.writeText(report.toString(2))
                results.putString("reportPath", reportFile.absolutePath)
            }
        }.onFailure { report.put("reportWriteError", it.toString()) }
        results.putInt("tests", cases.length())
        results.putInt("failures", failures)
        results.putString("discoveryReport", report.toString())
        results.putString("stream", "\nDiscovery instrumentation: ${cases.length() - failures}/${cases.length()} passed\n" +
            if (failures == 0) "" else report.toString(2) + "\n")
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun copyFixture(name: String, directory: File): File {
        val file = File(directory, name)
        // Android 14+ requires dynamically loaded code to be read-only. Set that state
        // immediately after creation, then write through the already-open descriptor.
        FileOutputStream(file).use { output ->
            check(file.setReadOnly()) { "Cannot make DEX fixture read-only" }
            context.assets.open("discovery-fixtures/$name").use { it.copyTo(output) }
        }
        check(file.length() > 0) { "Empty DEX fixture: $name" }
        return file
    }

    private fun fixtureLoader(context: FixtureContext, directory: File, name: String): ClassLoader {
        val optimized = File(directory, "optimized-$name")
        check(optimized.mkdirs())
        return DexClassLoader(context.apkPaths.joinToString(File.pathSeparator), optimized.path, null, targetContext.classLoader)
    }

    private fun assertFound(snapshot: DiscoverySnapshot, cacheHit: Boolean, owner: String, method: String, suffix: String) {
        check(snapshot.cacheHit == cacheHit) { "cacheHit=${snapshot.cacheHit}, expected $cacheHit" }
        val report = snapshot.reports.single()
        check(report.status == DiscoveryStatus.FOUND) { "Discovery failed: ${report.status}: ${report.detail}" }
        val resolved = snapshot.methods.getValue(TARGET).single()
        check(resolved.declaringClass.name == owner && resolved.name == method) { "Wrong discovered member: $resolved" }
        check(resolved.invoke(null, "payload") == "$MARKER${suffix}payload") { "Discovered DEX method returned the wrong value" }
        check(!snapshot.identity.verifiedProfile) { "Synthetic fixture was marked as the verified Speedtest APK" }
    }

    private fun assertAmbiguous(snapshot: DiscoverySnapshot, context: Context, count: Int?) {
        check(!snapshot.cacheHit) { "Ambiguous target unexpectedly loaded a positive cache" }
        val report = snapshot.reports.single()
        check(report.status == DiscoveryStatus.NOT_UNIQUE) { "Ambiguity was accepted: ${report.status}: ${report.detail}" }
        if (count != null) check(report.candidateCount == count) { "Wrong ambiguity candidate count: ${report.candidateCount}" }
        check(snapshot.methods.isEmpty() && report.methods.isEmpty()) { "Ambiguity exposed a method for hooking" }
        check(!positiveCache(context).exists()) { "Ambiguity created a positive cache" }
    }

    private fun positiveCache(context: Context) = File(context.cacheDir, "speedtest-privacy-discovery/methods.properties")

    private fun describe(snapshot: DiscoverySnapshot): JSONObject = JSONObject()
        .put("cacheHit", snapshot.cacheHit).put("fingerprint", snapshot.identity.fingerprint)
        .put("versionCode", snapshot.identity.versionCode).put("elapsedMs", snapshot.elapsedMs)
        .put("status", snapshot.reports.single().status.name)
        .put("candidates", snapshot.reports.single().candidateCount)
        .put("detail", snapshot.reports.single().detail)

    private fun rejectForgedPublication(): JSONObject {
        val moduleUid = targetContext.applicationInfo.uid
        check(Process.myUid() == moduleUid) { "Instrumentation is not running with the module's UID" }
        val speedtestUid = runCatching {
            targetContext.packageManager.getApplicationInfo(PermissionPolicy.TARGET, 0).uid
        }.getOrDefault(-1)
        check(speedtestUid >= 0) { "Speedtest must be installed to exercise status UID authentication" }
        check(moduleUid != speedtestUid) { "Module and Speedtest unexpectedly share a UID" }
        val preferences = targetContext.getSharedPreferences(StatusPolicy.PREFS, Context.MODE_PRIVATE)
        val previous = preferences.getString(StatusPolicy.KEY, null)
        val forged = JSONObject().put("package", PermissionPolicy.TARGET)
            .put("moduleApplicationId", BuildConfig.APPLICATION_ID)
            .put("moduleVersionCode", BuildConfig.VERSION_CODE).put("uid", speedtestUid)
            .put("compatibility", "matched").put("forgedByInstrumentation", true).toString()
        val error = runCatching {
            val endpoint = RuntimeStatusEndpoint(targetContext)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            val identity = Binder.clearCallingIdentity()
            try {
                data.writeInterfaceToken(RuntimeStatusChannel.DESCRIPTOR)
                data.writeInt(RuntimeStatusChannel.WIRE_VERSION)
                data.writeInt(BuildConfig.VERSION_CODE)
                data.writeString(forged)
                check(endpoint.transact(RuntimeStatusChannel.TRANSACTION_REPORT, data, reply, 0)) {
                    "Forged status transaction was not handled"
                }
                // A local Binder can throw directly; a remote one reports through this reply.
                reply.readException()
                check(reply.readInt() == 1) { "Forged status transaction returned an invalid response" }
            } finally {
                Binder.restoreCallingIdentity(identity)
                reply.recycle()
                data.recycle()
            }
        }.exceptionOrNull()
        val denial = error as? SecurityException ?: error?.cause as? SecurityException
        check(denial != null) { "Forged publication was not rejected with SecurityException: $error" }
        check(denial.message?.contains("caller=$moduleUid target=$speedtestUid") == true) {
            "Forged publication was not rejected by the real caller UID: $denial"
        }
        check(preferences.getString(StatusPolicy.KEY, null) == previous) { "Rejected publication changed the runtime status" }
        return JSONObject().put("callingUid", Process.myUid()).put("moduleUid", moduleUid)
            .put("speedtestUid", speedtestUid).put("claimedUid", speedtestUid).put("transport", "Binder")
            .put("securityException", true).put("statusUnchanged", true)
    }

    /** Optional live receipt clears only this module's snapshot, never Speedtest data. */
    private fun receiveLiveStatus(): JSONObject {
        check(targetContext.packageName == BuildConfig.APPLICATION_ID &&
            Process.myUid() == targetContext.applicationInfo.uid) { "Live status test is outside the module sandbox" }
        val preferences = targetContext.getSharedPreferences(StatusPolicy.PREFS, Context.MODE_PRIVATE)
        check(preferences.edit().remove(StatusPolicy.KEY).commit()) { "Cannot clear the module's previous status snapshot" }
        check(preferences.getString(StatusPolicy.KEY, null) == null) { "Previous status snapshot was retained" }

        val endpoint = RuntimeStatusEndpoint(targetContext)
        val started = SystemClock.elapsedRealtime()
        val requestedAt = System.currentTimeMillis()
        val deadline = started + 10_000
        var nextRequest = started
        var requests = 0
        var lastReport: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextRequest) {
                RuntimeStatusChannel.request(targetContext, endpoint)
                requests++
                nextRequest = now + 1000
            }
            preferences.getString(StatusPolicy.KEY, null)?.let { raw ->
                val report = JSONObject(raw)
                lastReport = report
                check(report.getString("moduleApplicationId") == BuildConfig.APPLICATION_ID) { "Live report came from another module namespace" }
                check(report.getString("package") == PermissionPolicy.TARGET) { "Live report identifies another target package" }
                check(report.getInt("moduleVersionCode") == BuildConfig.VERSION_CODE) { "Live report identifies another module version" }
                if (report.optString("compatibility") == "matched") {
                    val checks = report.getJSONObject("checks")
                    check(checks.length() == LIVE_CHECKS.size && LIVE_CHECKS.all { checks.getBoolean(it) }) {
                        "Live runtime checks did not all pass: $checks"
                    }
                    val targets = report.getJSONObject("discovery").getJSONArray("targets")
                    check(targets.length() == 16) { "Live report has ${targets.length()} semantic targets instead of 16" }
                    val keys = (0 until targets.length()).map { index ->
                        val target = targets.getJSONObject(index)
                        check(target.getBoolean("installed") && target.getString("status") == "FOUND") {
                            "Live semantic target was not installed: $target"
                        }
                        target.getString("key")
                    }
                    check(keys.distinct().size == 16) { "Live report repeats semantic targets" }
                    check(report.getJSONArray("errors").length() == 0) { "Live report contains runtime errors" }
                    check(report.getJSONArray("pendingChecks").length() == 0) { "Live report still has pending checks" }
                    check(report.getJSONObject("host").getBoolean("verifiedProfile")) { "Live host was not the verified Speedtest APK" }
                    check(report.getLong("updated") > 0) { "Live report has no source timestamp" }
                    return JSONObject().put("remoteBinder", true).put("responseAfterClear", true)
                        .put("moduleApplicationId", report.getString("moduleApplicationId"))
                        .put("package", report.getString("package")).put("compatibility", "matched")
                        .put("requests", requests).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("requestedAt", requestedAt).put("receivedAt", System.currentTimeMillis())
                        .put("sourceUpdated", report.getLong("updated")).put("checks", checks)
                        .put("targetsInstalled", keys.size).put("targetKeys", JSONArray(keys))
                        .put("installedHooks", report.getInt("installed"))
                }
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0) Thread.sleep(minOf(100L, remaining))
        }
        error("No matched live Binder reply within 10 seconds; requests=$requests, lastCompatibility=" +
            (lastReport?.optString("compatibility") ?: "no reply"))
    }

    /** Only the fixture APK paths and test-owned cache change; package/version data stay real. */
    private class FixtureContext(base: Context, baseApk: File, splitApks: List<File>, private val isolatedCache: File) : ContextWrapper(base) {
        val apkPaths = listOf(baseApk.path) + splitApks.map { it.path }
        private val fixtureInfo = ApplicationInfo(base.applicationInfo).apply {
            sourceDir = baseApk.path
            publicSourceDir = baseApk.path
            splitSourceDirs = splitApks.map { it.path }.toTypedArray()
            splitPublicSourceDirs = splitSourceDirs
        }
        init { check(isolatedCache.isDirectory || isolatedCache.mkdirs()) { "Cannot create fixture cache" } }
        override fun getApplicationInfo(): ApplicationInfo = fixtureInfo
        override fun getCacheDir(): File = isolatedCache
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val MARKER = "speedtest.discovery.fixture.marker.v1"
        private const val TARGET = "fixture.staticString"
        private val LIVE_CHECKS = setOf("optionalPermissionDenied", "androidIdRedacted", "advertisingIdRedacted",
            "javaUrlBlocked", "legacyUrlsBlocked", "mapboxReportUrlsBlocked", "mapboxNativeCollectionDisabled")
    }
}
