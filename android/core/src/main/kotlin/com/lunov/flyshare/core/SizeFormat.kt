package com.lunov.flyshare.core

/**
 * How a byte count is written for a person — the same rule as `bytes()` in
 * ui/app.js on the desktop.
 *
 * It lives here, with a test, because the two halves disagreeing is worse than
 * either convention being wrong. The desktop once divided by 1024 while
 * calling the result GB, so a transfer the phone described as 81.6 GB appeared
 * there as 76 GB: the same bytes, a thirteenth apart, and no way for anyone
 * comparing the two screens to read that as anything but files going missing.
 *
 * A kilobyte is 1000 bytes. That is what the unit means, what storage is sold
 * in, and what the file managers on macOS and Android report.
 */
object SizeFormat {

    /** The scaled number and which unit it belongs to. */
    data class Scaled(val value: Double, val unitIndex: Int, val digits: Int)

    /**
     * One decimal below ten, none above: "3.8 GB" carries information, and
     * "81.6 GB" is noise next to "82 GB" when the total is that large.
     */
    fun scale(bytes: Long, unitCount: Int): Scaled {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1000 && unit < unitCount - 1) {
            value /= 1000
            unit += 1
        }
        return Scaled(value, unit, if (value < 10 && unit > 0) 1 else 0)
    }
}
