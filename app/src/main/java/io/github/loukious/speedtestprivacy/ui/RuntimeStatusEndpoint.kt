package io.github.loukious.speedtestprivacy.ui

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject
import io.github.loukious.speedtestprivacy.BuildConfig
import io.github.loukious.speedtestprivacy.core.PermissionPolicy
import io.github.loukious.speedtestprivacy.core.RuntimeStatusChannel
import io.github.loukious.speedtestprivacy.core.StatusPolicy

/** Only a live Binder transaction from Speedtest's installed UID may publish health metadata. */
class RuntimeStatusEndpoint(context: Context) : Binder() {
    private val appContext = checkNotNull(context.applicationContext)

    init { attachInterface(null, RuntimeStatusChannel.DESCRIPTOR) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != RuntimeStatusChannel.TRANSACTION_REPORT) return super.onTransact(code, data, reply, flags)

        // Capture kernel identity here, before unmarshalling or asynchronous work.
        val callerUid = Binder.getCallingUid()
        val targetUid = runCatching {
            appContext.packageManager.getApplicationInfo(PermissionPolicy.TARGET, 0).uid
        }.getOrDefault(-1)
        if (!StatusPolicy.mayPublish(callerUid, targetUid))
            throw SecurityException("Only Speedtest may publish its runtime status (caller=$callerUid target=$targetUid)")

        require(reply != null && (flags and IBinder.FLAG_ONEWAY) == 0) { "Synchronous runtime status transaction required" }
        require(data.dataSize() in 1..RuntimeStatusChannel.MAX_PARCEL_BYTES) { "Runtime status parcel too large" }
        data.enforceInterface(RuntimeStatusChannel.DESCRIPTOR)
        require(data.readInt() == RuntimeStatusChannel.WIRE_VERSION) { "Unsupported runtime status protocol" }
        require(data.readInt() == BuildConfig.VERSION_CODE) { "Runtime status module version mismatch" }
        val report = requireNotNull(data.readString()) { "Missing runtime status report" }
        require(report.length <= StatusPolicy.MAX_REPORT_LENGTH && data.dataAvail() == 0) { "Invalid runtime status payload size" }
        val json = try { JSONObject(report) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid runtime status JSON")
        }
        require(json.optString("package") == PermissionPolicy.TARGET &&
            json.optString("moduleApplicationId") == BuildConfig.APPLICATION_ID &&
            json.optInt("moduleVersionCode", -1) == BuildConfig.VERSION_CODE) { "Runtime status report identity mismatch" }

        // apply publishes the validated value in this process before replying; disk write follows.
        appContext.getSharedPreferences(StatusPolicy.PREFS, 0).edit().putString(StatusPolicy.KEY, report).apply()
        reply.writeNoException()
        reply.writeInt(1)
        return true
    }
}
