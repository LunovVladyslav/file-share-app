package com.lunov.flyshare.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lunov.flyshare.core.IncomingUi
import com.lunov.flyshare.core.OutgoingUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a transfer alive while the person is doing something else.
 *
 * Without this, switching apps mid-transfer means the system is free to stop
 * the process, and 400 MB of progress disappears with it. The notification is
 * not decoration — a foreground service is required to have one, and it is
 * also the only way to see how far along a transfer is once the app is hidden.
 *
 * It also holds a high-performance Wi-Fi lock. Wi-Fi power saving parks the
 * radio between packets on an otherwise idle link, which costs far more
 * throughput than the lock costs battery over the seconds a transfer lasts.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = FlyShareApp.engineOf(this)

        // Within a few seconds of being started, or the system kills us.
        startForegroundNow(notification(getString(R.string.notification_preparing), null))
        acquireWifiLock()

        scope.launch {
            combine(engine.peers.incoming.state, engine.peers.outgoing.state) { incoming, outgoing ->
                describe(incoming, outgoing)
            }.collect { state ->
                if (state == null) {
                    stop()
                } else {
                    notify(notification(state.first, state.second))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Title and percentage, or null when nothing is moving any more. */
    private fun describe(incoming: IncomingUi, outgoing: OutgoingUi): Pair<String, Int?>? = when {
        incoming is IncomingUi.Busy -> getString(
            R.string.notification_receiving, incoming.progress.peerName,
        ) to (incoming.progress.fraction * 100).toInt()

        outgoing is OutgoingUi.Busy -> getString(
            R.string.notification_sending, outgoing.progress.peerName,
        ) to (outgoing.progress.fraction * 100).toInt()

        else -> null
    }

    private fun notification(title: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .also { builder ->
                if (percent != null) {
                    builder.setProgress(100, percent, false)
                    builder.setContentText("$percent%")
                }
            }
            .build()
    }

    private fun startForegroundNow(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notify(notification: Notification) {
        // Silently ignored when notifications are refused; the service keeps
        // running either way, which is the part that matters.
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flyshare-transfer")
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW, // a progress bar should not make noise
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 1

        /**
         * Started from the foreground, always: the person has just tapped
         * accept or send. Android forbids starting a foreground service from
         * the background, and there would be nothing to show if it did.
         */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
