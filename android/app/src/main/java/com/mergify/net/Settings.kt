package com.mergify.net

import android.content.Context

/**
 * The two preferences of the *Paramètres* screen: a cellular budget and the multi-flux policy of
 * the Go scheduler. This data class is the single vocabulary both sides read — the Compose screen
 * to render them, [MergifyVpnService] to apply them — so neither can disagree about what the user
 * asked for. Everything is plain values: persistence lives in [SettingsRepository].
 */
data class AppSettings(
    /** The cellular budget the slider points at: always an in-range value, in bytes. */
    val mobileLimitBytes: Long = DEFAULT_MOBILE_LIMIT_BYTES,

    /** "Illimité": the budget exists but is not enforced (the slider is then dimmed). */
    val unlimitedMobileData: Boolean = true,

    /** "Forcer le trafic multi-flux": spread flows over both links instead of the fastest one. */
    val forceMultiFlow: Boolean = false,
) {
    /** Whether the cumulative cellular volume has reached the budget (never while unlimited). */
    fun limitReached(cellBytes: Long): Boolean =
        !unlimitedMobileData && cellBytes >= mobileLimitBytes

    /** The slider's read-out: "Illimité" without a cap, the budget's volume otherwise. */
    val mobileLimitLabel: String
        get() = if (unlimitedMobileData) "Illimité" else formatBytes(mobileLimitBytes)

    companion object {
        /** The slider's out-of-the-box position: 1 Go. */
        const val DEFAULT_MOBILE_LIMIT_BYTES = 1_000_000_000L
    }
}

/**
 * The slider's scale, in Mo: 100 Mo … 20 Go with a notch every 100 Mo. SI units throughout
 * (1 Go = 1000 Mo) so the bounds and [formatBytes] agree, and Mo so the whole range is a plain
 * integer count rather than a float walk.
 */
const val MOBILE_LIMIT_MIN_MB = 100L
const val MOBILE_LIMIT_MAX_MB = 20_000L
private const val MOBILE_LIMIT_STEP_MB = 100L

/**
 * Compose's Slider `steps` counts the notches **between** both ends, the ends being implicit:
 * `(20 000 − 100) / 100 − 1 = 198` steps → 200 positions, one every 100 Mo.
 */
internal val MOBILE_LIMIT_SLIDER_STEPS: Int =
    ((MOBILE_LIMIT_MAX_MB - MOBILE_LIMIT_MIN_MB) / MOBILE_LIMIT_STEP_MB - 1).toInt()

/** Slider position (Mo) → bytes, snapped to the scale and never outside it. */
internal fun mobileLimitBytes(megabytes: Long): Long =
    megabytes.coerceIn(MOBILE_LIMIT_MIN_MB, MOBILE_LIMIT_MAX_MB) * 1_000_000L

/** Bytes → slider position (Mo): a value persisted outside the scale still lands on it. */
internal fun mobileLimitMb(bytes: Long): Long =
    (bytes / 1_000_000L).coerceIn(MOBILE_LIMIT_MIN_MB, MOBILE_LIMIT_MAX_MB)

/**
 * The settings' own `SharedPreferences` — distinct from `data_usage`, which holds counters rather
 * than choices, so neither file is ever rewritten by the other's writes.
 */
class SettingsRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        // Clamped: a value written by an older or future scale still fits the current slider.
        mobileLimitBytes = mobileLimitMb(
            prefs.getLong(KEY_MOBILE_LIMIT, AppSettings.DEFAULT_MOBILE_LIMIT_BYTES),
        ),
        unlimitedMobileData = prefs.getBoolean(KEY_UNLIMITED, true),
        forceMultiFlow = prefs.getBoolean(KEY_FORCE_MULTI_FLOW, false),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putLong(KEY_MOBILE_LIMIT, settings.mobileLimitBytes)
            .putBoolean(KEY_UNLIMITED, settings.unlimitedMobileData)
            .putBoolean(KEY_FORCE_MULTI_FLOW, settings.forceMultiFlow)
            .apply() // async: the caller is the main thread, the write is small and rare
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_MOBILE_LIMIT = "mobile_limit_bytes"
        const val KEY_UNLIMITED = "mobile_limit_unlimited"
        const val KEY_FORCE_MULTI_FLOW = "force_multi_flow"
    }
}
