package io.github.loukious.speedtestprivacy.ui

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.loukious.speedtestprivacy.BuildConfig
import io.github.loukious.speedtestprivacy.core.PermissionPolicy
import io.github.loukious.speedtestprivacy.core.Settings
import io.github.loukious.speedtestprivacy.core.StatusPolicy
import io.github.loukious.speedtestprivacy.core.RuntimeStatusChannel
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private var preferences: SharedPreferences? = null
    private val switches = LinkedHashMap<String, Switch>()
    private lateinit var status: TextView
    private lateinit var compatibility: TextView
    private val healthHandler = Handler(Looper.getMainLooper())
    private val statusEndpoint by lazy { RuntimeStatusEndpoint(applicationContext) }
    private val healthRefresh = object : Runnable {
        override fun run() {
            runCatching { RuntimeStatusChannel.request(applicationContext, statusEndpoint) }
            refreshHealth()
            healthHandler.postDelayed(this, 1500)
        }
    }
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(Color.rgb(16, 21, 30))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(text("Speedtest Privacy", 28f, Color.WHITE, true))
        content.addView(text("Version ${BuildConfig.VERSION_NAME}", 13f, Color.rgb(67, 217, 170)))
        content.addView(text("Control what Speedtest can access and send. Internet access stays available for speed tests.", 16f))
        status = text("Enable this module for Speedtest in Vector or LSPosed to connect settings.", 14f)
        content.addView(status)
        compatibility = text("Open Speedtest to check app compatibility.", 14f)
        content.addView(compatibility)
        addSwitch(content, Settings.PERMISSIONS, "Block optional permissions",
            "Deny location, phone, notifications, storage and other optional requests.")
        addSwitch(content, Settings.ADS, "Block ads", "Suppress banner and native ads and their SDK requests.")
        addSwitch(content, Settings.TELEMETRY, "Block telemetry and report uploads",
            "Stop analytics, crash uploads, optional result reports and map telemetry.")
        addSwitch(content, Settings.BACKGROUND, "Block background measurements",
            "Stop coverage sampling and background reports, including scheduled work.")
        addSwitch(content, Settings.IDENTIFIERS, "Hide device identifiers",
            "Redact advertising ID, Android ID, phone identifiers and Wi-Fi identifiers.")
        content.addView(text("Restart Speedtest after changing a switch.", 14f))
        content.addView(text("Test servers still receive your IP address and test traffic. Account sync and result sharing may be unavailable with report uploads blocked.", 14f))
        content.addView(Button(this).apply {
            text = "Open Speedtest"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(PermissionPolicy.TARGET)?.let(::startActivity)
            }
        })
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                preferences = runCatching { service.getRemotePreferences(Settings.GROUP) }.getOrNull()
                runOnUiThread { if (!isDestroyed) refresh() }
            }
            override fun onServiceDied(service: XposedService) {
                preferences = null
                runOnUiThread { if (!isDestroyed) refresh() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        healthHandler.post(healthRefresh)
    }

    override fun onPause() {
        healthHandler.removeCallbacks(healthRefresh)
        super.onPause()
    }

    private fun refreshHealth() {
        val cached = getSharedPreferences(StatusPolicy.PREFS, 0).getString(StatusPolicy.KEY, null)
        val report = runCatching { JSONObject(cached ?: "") }.getOrNull()
        val currentInfo = runCatching { packageManager.getPackageInfo(PermissionPolicy.TARGET, 0) }.getOrNull()
        val host = report?.optJSONObject("host")
        val currentPaths = currentInfo?.applicationInfo?.let { listOf(it.sourceDir) + it.splitSourceDirs.orEmpty().sorted() }
        val reportedPaths = host?.optJSONArray("apks")
        val sourceMatches = runCatching { reportedPaths != null && currentPaths != null && reportedPaths.length() == currentPaths.size &&
            currentPaths.indices.all { index ->
                val entry = reportedPaths.optJSONObject(index) ?: return@all false
                val file = File(currentPaths[index])
                entry.optString("path") == file.canonicalPath && entry.optLong("length", -1) == file.length() &&
                    entry.optLong("modified", -1) == file.lastModified()
            }
        }.getOrDefault(false)
        val currentVersion = currentInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val fresh = report?.optString("moduleApplicationId") == BuildConfig.APPLICATION_ID &&
            report?.optInt("moduleVersionCode", -1) == BuildConfig.VERSION_CODE && sourceMatches &&
            host?.optLong("versionCode", -1) == currentVersion
        val message = if (!fresh) {
            "Compatibility not checked for this app build. Open or restart Speedtest."
        } else {
            val name = host?.optString("versionName").orEmpty()
            val state = report?.optString("compatibility")
            val headline = when (state) {
                "matched" -> "Speedtest $name · protections matched"
                "initializing" -> "Speedtest $name · checking protections"
                else -> "Speedtest $name · partial compatibility"
            }
            val targets = report?.optJSONObject("discovery")?.optJSONArray("targets")
            val guards = report?.optJSONArray("requiredGuards")
            val missing = (0 until (targets?.length() ?: 0)).mapNotNull { index ->
                targets?.optJSONObject(index)?.takeIf { it.optBoolean("enabled", true) && it.optBoolean("critical") && !it.optBoolean("installed") }
                    ?.optString("description")
            } + (0 until (guards?.length() ?: 0)).mapNotNull { index ->
                guards?.optJSONObject(index)?.takeIf { it.optBoolean("enabled") && !it.optBoolean("installed") }
                    ?.optString("description")
            }
            val disabled = report?.optJSONArray("skippedVersionSpecific")?.length() ?: 0
            buildString {
                append(headline)
                if (state == "partial") {
                    if (missing.isNotEmpty()) append("\nUnavailable: ").append(missing.joinToString(", "))
                    if (disabled > 0) append("\nSome version-specific protections were skipped safely.")
                    if ((report?.optJSONArray("pendingChecks")?.length() ?: 0) > 0)
                        append("\nSome runtime checks have not completed.")
                    append("\nKeep sensitive permissions denied; full coverage needs verification.")
                }
            }
        }
        compatibility.text = message
        compatibility.setTextColor(if (fresh && report?.optString("compatibility") == "matched")
            Color.rgb(67, 217, 170) else Color.rgb(241, 192, 95))
    }

    private fun refresh() {
        updating = true
        switches.forEach { (key, switch) ->
            switch.isChecked = runCatching { preferences?.getBoolean(key, true) ?: true }.getOrDefault(true)
            switch.isEnabled = preferences != null
        }
        updating = false
        status.text = if (preferences != null) "Framework connected · restart Speedtest to apply changes"
            else "Enable this module for Speedtest in Vector or LSPosed to connect settings."
    }

    private fun addSwitch(parent: LinearLayout, key: String, title: String, description: String) {
        parent.addView(View(this).apply { setBackgroundColor(Color.rgb(40, 50, 63)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(20); bottomMargin = dp(12)
            })
        parent.addView(Switch(this).apply {
            text = title; textSize = 17f; setTextColor(Color.WHITE)
            isChecked = true; isEnabled = false
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                if (!updating) preferences?.edit()?.putBoolean(key, checked)?.apply()
            }
            switches[key] = this
        })
        parent.addView(text(description, 14f))
    }

    private fun text(value: String, size: Float, color: Int = Color.rgb(187, 198, 210), bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(0, dp(8), 0, dp(8))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
