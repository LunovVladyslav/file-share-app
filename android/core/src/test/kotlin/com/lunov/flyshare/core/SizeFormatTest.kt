package com.lunov.flyshare.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rule the desktop's `bytes()` follows, pinned so the two cannot drift.
 *
 * The case that matters is the one that was reported: a 1287-file transfer
 * where the phone said 81.6 GB and the laptop said 76 GB. Both were counting
 * the same bytes; only the divisor differed.
 */
class SizeFormatTest {

    private val units = listOf("B", "KB", "MB", "GB", "TB")

    private fun format(bytes: Long): String {
        val (value, unit, digits) = SizeFormat.scale(bytes, units.size)
        return "%.${digits}f %s".format(java.util.Locale.ROOT, value, units[unit])
    }

    @Test
    fun `a kilobyte is a thousand bytes, not a kibibyte`() {
        assertEquals("1.0 KB", format(1_000))
        assertEquals("1.0 KB", format(1_024))
        assertEquals("1.0 MB", format(1_000_000))
    }

    @Test
    fun `the transfer that started this reads the same on both screens`() {
        // What 76 GiB actually is. The desktop used to print "76 GB" for it.
        val bytes = 76L * 1024 * 1024 * 1024
        assertEquals(81_604_378_624, bytes)
        assertEquals("82 GB", format(bytes))
    }

    @Test
    fun `one decimal below ten, none above`() {
        assertEquals("3.8 GB", format(3_800_000_000))
        assertEquals("82 GB", format(81_604_378_624))
        assertEquals("999 MB", format(999_000_000))
    }

    @Test
    fun `plain bytes carry no decimal, and nothing goes below zero`() {
        assertEquals("0 B", format(0))
        assertEquals("512 B", format(512))
        assertEquals("0 B", format(-1))
    }

    @Test
    fun `it stops at the largest unit it was given`() {
        // Nobody sends a petabyte over Wi-Fi, but the loop must not run off
        // the end of the list trying.
        assertEquals("9000 TB", format(9_000_000_000_000_000L))
    }
}
