package com.mergify.net

import android.util.Log
import org.json.JSONObject

/**
 * One network as the Go scheduler sees it (`GetStatsJSON` → `nics[]`). Shared by the dashboard
 * and [MergifyVpnService]'s notification poll loop, so both read the same snapshot through the
 * same parser and can never disagree about which link is primary or how lossy it is.
 */
internal data class NicStats(
    val name: String,
    val ip: String,
    val ip6: String,
    val state: String,
    val state6: String,
    val active: Long,
    val rateIn: Double,
    val rateOut: Double,
    val rtt: Double, // smoothed probe latency in ms; -1 when never measured
    val loss: Double, // smoothed probe failure rate, 0..1 (packet loss as Go sees it)
    val primary: Boolean, // the engine currently prefers this NIC (lowest latency, has room)
)

/** A full snapshot of the running engine. */
internal data class Stats(
    val running: Boolean,
    val v6: String,
    val nics: List<NicStats>,
    val usage: DataUsage, // session data usage, per transport, straight from the engine
) {
    /** The interface the scheduler currently prefers, if it has elected one yet. */
    val primaryNic: NicStats? get() = nics.firstOrNull { it.primary }
}

/**
 * Parses GetStatsJSON's output (Go's SlotStats field names, no JSON tags), including the four
 * data-usage counters — `wifi_rx_bytes`, `wifi_tx_bytes`, `cell_rx_bytes`, `cell_tx_bytes`.
 * `total_bytes` is the engine's own sum of those four; [DataUsage.total] recomputes it so the
 * UI never risks the two drifting apart. Any malformed payload just shows nothing rather than
 * crashing the caller.
 */
internal fun parseStats(json: String): Stats? = runCatching {
    val root = JSONObject(json)
    val usage = DataUsage(
        wifiRx = root.optLong("wifi_rx_bytes"),
        wifiTx = root.optLong("wifi_tx_bytes"),
        cellRx = root.optLong("cell_rx_bytes"),
        cellTx = root.optLong("cell_tx_bytes"),
    )
    val nicsArray = root.optJSONArray("nics")
        ?: return Stats(root.optBoolean("running"), root.optString("v6"), emptyList(), usage)
    val nics = buildList {
        for (i in 0 until nicsArray.length()) {
            val n = nicsArray.getJSONObject(i)
            add(
                NicStats(
                    name = n.optString("Name"),
                    ip = n.optString("IP"),
                    ip6 = n.optString("IP6"),
                    state = n.optString("State"),
                    state6 = n.optString("State6"),
                    active = n.optLong("Active"),
                    rateIn = n.optDouble("RateIn", 0.0),
                    rateOut = n.optDouble("RateOut", 0.0),
                    rtt = n.optDouble("RTT", -1.0),
                    loss = n.optDouble("Loss", 0.0),
                    primary = n.optBoolean("Primary", false),
                ),
            )
        }
    }
    Stats(root.optBoolean("running"), root.optString("v6"), nics, usage)
}.onFailure {
    Log.w(TAG, "stats JSON unparsable: ${json.take(160)}")
}.getOrNull()

/**
 * Instantaneous throughput in bits per second — the unit a status capsule speaks: `"850 Mb/s"`,
 * `"12 Mb/s"`, `"640 kb/s"`. The engine reports bytes per second, hence the ×8; SI units
 * throughout, like [formatBytes].
 */
internal fun formatBitrate(bytesPerSecond: Double): String {
    val bits = bytesPerSecond.coerceAtLeast(0.0) * 8
    return when {
        bits >= 1_000_000_000 -> "%.1f Gb/s".format(bits / 1e9)
        bits >= 1_000_000 -> "%.0f Mb/s".format(bits / 1e6)
        bits >= 1_000 -> "%.0f kb/s".format(bits / 1e3)
        else -> "%.0f b/s".format(bits)
    }
}
