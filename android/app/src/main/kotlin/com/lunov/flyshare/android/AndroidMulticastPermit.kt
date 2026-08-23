package com.lunov.flyshare.android

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Build
import androidx.core.content.edit
import com.lunov.flyshare.core.MulticastPermit
import com.lunov.flyshare.core.SelfDescription
import java.security.SecureRandom

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
 * This device's stable identity on the network.
 *
 * The id must survive restarts — it is how peers recognise us and how a pairing
 * stays attached to something. Settings will move to DataStore per SPEC.md; the
 * id is one string written once, so preferences are the right size of tool.
 */
object DeviceIdentity {

    private const val PREFS = "flyshare"
    private const val KEY_ID = "deviceId"

    fun describe(context: Context): SelfDescription {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: newId().also { fresh ->
            prefs.edit { putString(KEY_ID, fresh) }
        }
        return SelfDescription(id = id, name = deviceName(context), os = "android")
    }

    /** 8 random bytes as 16 hex characters, matching docs/PROTOCOL.md §3. */
    private fun newId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * What the person called their phone, when they have called it anything.
     * Falls back to the marketing name rather than a model code, since this is
     * what appears on someone else's screen.
     */
    private fun deviceName(context: Context): String {
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
