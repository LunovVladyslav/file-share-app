package com.lunov.flyshare.android

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Build
import com.lunov.flyshare.core.MulticastPermit

/**
 * The one thing discovery needs from Android.
 *
 * Without a held multicast lock the system drops multicast and broadcast
 * datagrams before they reach the socket: everything looks healthy, packets
 * leave, and nothing ever arrives. It costs battery, so it is held only while
 * the device list is on screen.
 */
class AndroidMulticastPermit(context: Context) : MulticastPermit {

    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.MulticastLock? = null

    override fun acquire() {
        if (lock != null) return
        lock = wifi.createMulticastLock("flyshare-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }
}

/**
 * The device's display name. Its *identity* — the key and the id — lives in the
 * core's Identity, so that the same code produces the same values on a JVM and
 * on a phone.
 */
object DeviceIdentity {

    /**
     * What the person called their phone, when they have called it anything.
     * Falls back to the marketing name rather than a model code, since this is
     * what appears on someone else's screen.
     */
    fun deviceName(context: Context): String {
        val chosen = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return chosen?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android device" }
    }
}
