package com.mergify.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Pure-JVM checks of the data-usage helpers the Compose screen relies on. */
class DataUsageTest {

    /**
     * The separator follows the device locale (the UI is French on the target device), so the
     * expectation is built from it rather than hard-coded: the test pins the *scale*, not the
     * punctuation.
     */
    private val sep =
        DecimalFormatSymbols(Locale.getDefault()).decimalSeparator.toString()

    @Test
    fun `formatBytes uses one decimal from kilo up and bytes below`() {
        assertEquals("0 o", formatBytes(0))
        assertEquals("842 o", formatBytes(842))
        assertEquals("1${sep}2 ko", formatBytes(1_200))
        assertEquals("12${sep}4 Mo", formatBytes(12_400_000))
        assertEquals("1${sep}5 Go", formatBytes(1_500_000_000))
        assertEquals("2${sep}0 To", formatBytes(2_000_000_000_000))
    }

    @Test
    fun `formatBytes never prints a negative volume`() {
        assertEquals("0 o", formatBytes(-42))
    }

    @Test
    fun `session growth is the difference while a session runs`() {
        assertEquals(300L, sessionGrowth(before = 500L, now = 800L))
        assertEquals(0L, sessionGrowth(before = 500L, now = 500L))
    }

    @Test
    fun `a restarted session counts from zero without a negative delta`() {
        assertEquals(40L, sessionGrowth(before = 1_000L, now = 40L)) // engine restarted
        assertEquals(0L, sessionGrowth(before = 1_000L, now = 0L))
    }

    @Test
    fun `usage totals are the per-transport sums`() {
        val usage = DataUsage(wifiRx = 10L, wifiTx = 2L, cellRx = 30L, cellTx = 4L)
        assertEquals(12L, usage.wifi)
        assertEquals(34L, usage.cell)
        assertEquals(46L, usage.total)

        val sum = usage + DataUsage(wifiRx = 1L, cellTx = 2L)
        assertEquals(DataUsage(wifiRx = 11L, wifiTx = 2L, cellRx = 30L, cellTx = 6L), sum)
    }
}
