package com.mergify.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks of the settings vocabulary: the slider's scale, the labels, the cap rule. */
class SettingsTest {

    @Test
    fun `the slider spans 100 Mo to 20 Go in 100 Mo notches`() {
        assertEquals(100L, MOBILE_LIMIT_MIN_MB)
        assertEquals(20_000L, MOBILE_LIMIT_MAX_MB)

        // Compose counts the notches *between* both ends: 198 steps → 200 positions, i.e. a
        // value every 100 Mo from 100 Mo to 20 000 Mo inclusive.
        assertEquals(198, MOBILE_LIMIT_SLIDER_STEPS)
        assertEquals(200, MOBILE_LIMIT_SLIDER_STEPS + 2)

        // Both ends are reachable, and the unit conversion is the SI one formatBytes uses.
        assertEquals(MOBILE_LIMIT_MIN_MB * 1_000_000L, mobileLimitBytes(MOBILE_LIMIT_MIN_MB))
        assertEquals(MOBILE_LIMIT_MAX_MB * 1_000_000L, mobileLimitBytes(MOBILE_LIMIT_MAX_MB))
        assertEquals(1_500_000_000L, mobileLimitBytes(1_500L)) // 1500 Mo = 1.5 Go
    }

    @Test
    fun `a value outside the scale still lands on the slider`() {
        assertEquals(MOBILE_LIMIT_MIN_MB, mobileLimitMb(1L)) // a byte written by an older scale
        assertEquals(MOBILE_LIMIT_MIN_MB, mobileLimitMb(0L))
        assertEquals(MOBILE_LIMIT_MAX_MB, mobileLimitMb(50_000_000_000L)) // 50 Go, capped at 20
        assertEquals(MOBILE_LIMIT_MIN_MB * 1_000_000L, mobileLimitBytes(5L)) // below the floor
        assertEquals(MOBILE_LIMIT_MAX_MB * 1_000_000L, mobileLimitBytes(99_999L)) // above the top
    }

    @Test
    fun `the read-out says Illimité without a cap, the volume otherwise`() {
        assertEquals("Illimité", AppSettings().mobileLimitLabel)
        assertEquals(
            formatBytes(2_500_000_000L),
            AppSettings(
                mobileLimitBytes = 2_500_000_000L,
                unlimitedMobileData = false,
            ).mobileLimitLabel,
        )
    }

    @Test
    fun `the cap bites exactly at the budget, not before`() {
        val settings = AppSettings(mobileLimitBytes = 500_000_000L, unlimitedMobileData = false)
        assertFalse(settings.limitReached(499_999_999L))
        assertTrue(settings.limitReached(500_000_000L)) // reached: cellular is cut
        assertTrue(settings.limitReached(500_000_001L))
    }

    @Test
    fun `an unlimited budget is never reached, whatever the volume`() {
        val settings = AppSettings(mobileLimitBytes = 100_000_000L, unlimitedMobileData = true)
        assertFalse(settings.limitReached(Long.MAX_VALUE))
    }

    @Test
    fun `out of the box there is no cap and balancing stays latency-first`() {
        val defaults = AppSettings()
        assertTrue(defaults.unlimitedMobileData)
        assertFalse(defaults.forceMultiFlow)
        assertEquals(AppSettings.DEFAULT_MOBILE_LIMIT_BYTES, defaults.mobileLimitBytes)
    }
}
