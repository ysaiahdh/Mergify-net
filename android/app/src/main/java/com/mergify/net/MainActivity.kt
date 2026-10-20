package com.mergify.net

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mergifynet.mobile.Mobile
import org.json.JSONObject

/** Minimal UI: one button to start/stop the VPN, live stats from the Go engine below. */
class MainActivity : ComponentActivity() {

    // The system consent dialog (first start only): onActivityResult RESULT_OK = accepted.
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startVpn()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VpnScreen(
                    onToggle = { if (MergifyVpnService.isRunning) stopVpn() else requestVpnConsent() },
                )
            }
        }
    }

    private fun requestVpnConsent() {
        // prepare() returns null when the consent was already given for this app.
        val consent = VpnService.prepare(this)
        if (consent != null) {
            consentLauncher.launch(consent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        startForegroundService(
            Intent(this, MergifyVpnService::class.java).setAction(MergifyVpnService.ACTION_START),
        )
    }

    private fun stopVpn() {
        startService(
            Intent(this, MergifyVpnService::class.java).setAction(MergifyVpnService.ACTION_STOP),
        )
    }
}

private data class NicStats(
    val name: String,
    val ip: String,
    val ip6: String,
    val state: String,
    val state6: String,
    val active: Long,
    val rateIn: Double,
    val rateOut: Double,
    val rtt: Double, // smoothed probe latency in ms; -1 when never measured
    val primary: Boolean, // the engine currently prefers this NIC (lowest latency, has room)
)

private data class Stats(val running: Boolean, val v6: String, val nics: List<NicStats>)

@Composable
private fun VpnScreen(onToggle: () -> Unit) {
    var running by remember { mutableStateOf(MergifyVpnService.isRunning) }
    var stats by remember { mutableStateOf<Stats?>(null) }

    // Poll the Go engine once a second: GetStatsJSON is a cheap JNI call into the scheduler.
    LaunchedEffect(Unit) {
        while (true) {
            running = MergifyVpnService.isRunning
            stats = if (running) {
                parseStats(withContext(Dispatchers.IO) { Mobile.getStatsJSON() })
            } else {
                null
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = onToggle) {
            Text(if (running) "Arrêter le VPN" else "Démarrer le VPN")
        }
        val s = stats ?: return@Column
        Text(text = "IPv6 : ${s.v6}", style = MaterialTheme.typography.bodyMedium)
        if (s.nics.isEmpty()) {
            Text("Aucun réseau actif", style = MaterialTheme.typography.bodyMedium)
        }
        s.nics.forEach { nic ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            nic.name + if (nic.primary) "  ★ prioritaire" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("${nic.active} flux actifs", style = MaterialTheme.typography.bodySmall)
                    }
                    val addr = listOf(nic.ip, nic.ip6).filter { it.isNotEmpty() }.joinToString("  ")
                    Text(addr, style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("IPv4 : ${nic.state}", style = MaterialTheme.typography.bodySmall)
                        if (nic.ip6.isNotEmpty()) {
                            Text("IPv6 : ${nic.state6}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "↓ ${fmtRate(nic.rateIn)}   ↑ ${fmtRate(nic.rateOut)}" +
                            (if (nic.rtt >= 0) "   · RTT %.0f ms".format(nic.rtt) else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun fmtRate(bytesPerSec: Double): String = when {
    bytesPerSec >= 1_000_000 -> "%.1f Mo/s".format(bytesPerSec / 1_000_000)
    bytesPerSec >= 1_000 -> "%.0f ko/s".format(bytesPerSec / 1_000)
    else -> "%.0f o/s".format(bytesPerSec)
}

/**
 * Parses GetStatsJSON's output (Go's SlotStats field names, no JSON tags). Any malformed
 * payload just shows nothing rather than crashing the UI.
 */
private fun parseStats(json: String): Stats? = runCatching {
    val root = JSONObject(json)
    val nicsArray = root.optJSONArray("nics") ?: return Stats(root.optBoolean("running"), root.optString("v6"), emptyList())
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
                    primary = n.optBoolean("Primary", false),
                ),
            )
        }
    }
    Stats(root.optBoolean("running"), root.optString("v6"), nics)
}.getOrNull()
