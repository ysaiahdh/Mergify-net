package com.mergify.net

import android.content.Context
import android.util.Log

/**
 * One tag for every log line the app emits, so `adb logcat -s MergifyNet` shows the service,
 * the JSON parsing and the data-usage tracker together.
 */
internal const val TAG = "MergifyNet"

/**
 * Data usage counters reported by the Go engine (`GetStatsJSON`), split per transport:
 * Rx is what came in from that network, Tx what was sent through it. All four are **session**
 * counters — the engine starts from zero on every `StartVpn` — [DataUsageTracker] is what
 * turns them into cumulative ones.
 */
data class DataUsage(
    val wifiRx: Long = 0,
    val wifiTx: Long = 0,
    val cellRx: Long = 0,
    val cellTx: Long = 0,
) {
    /** Everything that went through Wi-Fi (rx + tx). */
    val wifi: Long get() = wifiRx + wifiTx

    /** Everything that went through the cellular network, 4G/5G (rx + tx). */
    val cell: Long get() = cellRx + cellTx

    /** Everything that went through mergify-net (Wi-Fi + cellular). */
    val total: Long get() = wifi + cell

    /** Component-wise sum of the two snapshots. */
    operator fun plus(other: DataUsage) = DataUsage(
        wifiRx + other.wifiRx,
        wifiTx + other.wifiTx,
        cellRx + other.cellRx,
        cellTx + other.cellTx,
    )
}

/**
 * Human-readable volume — `"842 o"`, `"1.2 ko"`, `"12.4 Mo"`, `"1.5 Go"`, `"2.0 To"`.
 *
 * One decimal from kilo upwards (the values that move on a 1 s poll), plain bytes below, SI
 * units (1 Go = 1000 Mo) so it matches [fmtRate]'s speeds. Locale-driven decimal separator,
 * like the rest of the figures in the UI.
 */
fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value >= 1_000_000_000_000 -> "%.1f To".format(value / 1e12)
        value >= 1_000_000_000 -> "%.1f Go".format(value / 1e9)
        value >= 1_000_000 -> "%.1f Mo".format(value / 1e6)
        value >= 1_000 -> "%.1f ko".format(value / 1e3)
        else -> "$value o"
    }
}

/**
 * Bytes the session counters gained since [before]. A session restart puts the engine's counters
 * back below [before], in which case everything counted so far is new (and [reset] is what makes
 * that baseline go back to zero on a clean stop) — never a negative delta, so nothing is ever
 * counted twice.
 */
internal fun sessionGrowth(before: Long, now: Long): Long = if (now >= before) now - before else now

/**
 * Cumulative data usage, across sessions.
 *
 * The engine only reports the current session: its counters are fresh atomics every time the
 * VPN starts. Each call to [update] therefore folds the **growth** of the session counters into
 * totals persisted in `SharedPreferences`:
 *
 *  - normal poll: growth = now − before, nothing is counted twice;
 *  - session restarted (counters back to 0): growth = now, so the new session starts accumulating
 *    again from the last persisted totals;
 *  - [reset] on stop clears the baseline, so a restart never compares against the old session.
 *
 * Persisted with `apply()` (async) only when something actually moved, i.e. never on idle polls.
 */
class DataUsageTracker(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Totals from every session so far, persisted. */
    var cumulative: DataUsage = peek(context)
        private set

    init {
        Log.i(TAG, "data usage: ${formatBytes(cumulative.total)} cumulative on disk")
    }

    /** Last session snapshot seen: the growth since then is what gets added to [cumulative]. */
    private var previous = DataUsage()

    /** Folds the engine's session counters into the cumulative totals and returns them. */
    fun update(session: DataUsage): DataUsage {
        val gained = DataUsage(
            wifiRx = sessionGrowth(previous.wifiRx, session.wifiRx),
            wifiTx = sessionGrowth(previous.wifiTx, session.wifiTx),
            cellRx = sessionGrowth(previous.cellRx, session.cellRx),
            cellTx = sessionGrowth(previous.cellTx, session.cellTx),
        )
        if (gained.total > 0L) {
            cumulative += gained
            persist()
        }
        previous = session
        return cumulative
    }

    /** Forgets the session baseline (the tunnel stopped): the next one starts from zero. */
    fun reset() {
        if (previous.total > 0L) {
            Log.i(TAG, "data usage: session ended at ${formatBytes(previous.total)}")
        }
        previous = DataUsage()
    }

    private fun persist() {
        prefs.edit()
            .putLong(KEY_WIFI_RX, cumulative.wifiRx)
            .putLong(KEY_WIFI_TX, cumulative.wifiTx)
            .putLong(KEY_CELL_RX, cumulative.cellRx)
            .putLong(KEY_CELL_TX, cumulative.cellTx)
            .apply()
    }

    companion object {
        private const val PREFS = "data_usage"
        private const val KEY_WIFI_RX = "wifi_rx_bytes"
        private const val KEY_WIFI_TX = "wifi_tx_bytes"
        private const val KEY_CELL_RX = "cell_rx_bytes"
        private const val KEY_CELL_TX = "cell_tx_bytes"

        /**
         * The persisted totals, read without starting a tracker. [MergifyVpnService] uses it once
         * at startup to know whether the cellular cap already holds; it never folds anything in,
         * so there stays exactly one writer of these keys ([update]).
         */
        fun peek(context: Context): DataUsage {
            val prefs =
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DataUsage(
                wifiRx = prefs.getLong(KEY_WIFI_RX, 0L),
                wifiTx = prefs.getLong(KEY_WIFI_TX, 0L),
                cellRx = prefs.getLong(KEY_CELL_RX, 0L),
                cellTx = prefs.getLong(KEY_CELL_TX, 0L),
            )
        }
    }
}
