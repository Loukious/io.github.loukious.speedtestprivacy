package io.github.loukious.speedtestprivacy.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.io.File
import io.github.loukious.speedtestprivacy.BuildConfig

/** The module requests one report; Speedtest replies through the supplied Binder capability. */
object RuntimeStatusChannel {
    const val ACTION = "io.github.loukious.speedtestprivacy.REQUEST_RUNTIME_STATUS"
    const val REQUEST_PERMISSION = "io.github.loukious.speedtestprivacy.permission.REQUEST_RUNTIME_STATUS"
    const val DESCRIPTOR = "io.github.loukious.speedtestprivacy.runtime.IStatusSink.v1"
    const val TRANSACTION_REPORT = IBinder.FIRST_CALL_TRANSACTION
    const val WIRE_VERSION = 1
    const val MAX_PARCEL_BYTES = StatusPolicy.MAX_REPORT_LENGTH * 2 + 1024
    private const val ENDPOINT = "statusEndpoint"
    private const val TAG = "SpeedtestPrivacy"
    private var registeredReceiver: BroadcastReceiver? = null
    @Volatile private var currentReportFile: File? = null

    /** System server requires the sender to hold the module's signature permission. */
    @Synchronized
    fun register(context: Context, reportFile: File): Boolean {
        currentReportFile = reportFile
        if (registeredReceiver != null) return true
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                runCatching {
                    val endpoint = intent.extras?.getBinder(ENDPOINT) ?: return@runCatching
                    val file = currentReportFile ?: return@runCatching
                    if (!file.isFile || file.length() > StatusPolicy.MAX_REPORT_LENGTH * 4L) return@runCatching
                    val report = file.readText(Charsets.UTF_8)
                    if (report.isEmpty() || report.length > StatusPolicy.MAX_REPORT_LENGTH) return@runCatching
                    deliver(endpoint, report)
                }.onFailure { Log.w(TAG, "Runtime status reply failed: ${it.javaClass.simpleName}") }
            }
        }
        return runCatching {
            val handler = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, IntentFilter(ACTION), REQUEST_PERMISSION,
                    handler, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, IntentFilter(ACTION), REQUEST_PERMISSION, handler)
            }
            registeredReceiver = receiver
            true
        }.getOrElse {
            Log.w(TAG, "Runtime status receiver registration failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /** Called only by the module UI, whose UID holds REQUEST_PERMISSION. */
    fun request(context: Context, endpoint: IBinder) {
        val extras = Bundle().apply { putBinder(ENDPOINT, endpoint) }
        context.sendBroadcast(Intent(ACTION).setPackage(PermissionPolicy.TARGET)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(extras))
    }

    private fun deliver(endpoint: IBinder, report: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(WIRE_VERSION)
            data.writeInt(BuildConfig.VERSION_CODE)
            data.writeString(report)
            check(data.dataSize() <= MAX_PARCEL_BYTES) { "Runtime status parcel too large" }
            check(endpoint.transact(TRANSACTION_REPORT, data, reply, 0)) { "Runtime status transaction rejected" }
            reply.readException()
            check(reply.readInt() == 1) { "Runtime status report not accepted" }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
