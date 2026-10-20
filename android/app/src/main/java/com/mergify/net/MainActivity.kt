package com.mergify.net

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mergifynet.mobile.Mobile
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Mergify design system — absolute dark: deep background, hairline borders,
// no shadows, monospaced figures, one single signal colour (the live dot).
// ---------------------------------------------------------------------------
private object Ui {
    val Background = Color(0xFF0A0A0C) // deep, near-black
    val Surface = Color(0xFF111114) // cards, barely lifted
    val Hairline = Color(0x14FFFFFF) // 8% white: borders and dividers
    val Outline = Color(0x2EFFFFFF) // 18% white: the idle toggle border
    val TextPrimary = Color(0xFFF2F2F5)
    val TextSecondary = Color(0xFF8A8A93)
    val TextDim = Color(0x4DFFFFFF) // 30% white: addresses, footer
    val Live = Color(0xFF3DD68C) // the only accent, reserved for the active dot
    val Mono = FontFamily.Monospace
    val MonoStyle = TextStyle(fontFamily = Mono)
}

private val MergifyColors = darkColorScheme(
    background = Ui.Background,
    surface = Ui.Surface,
    onBackground = Ui.TextPrimary,
    onSurface = Ui.TextPrimary,
)

/**
 * Mergify-Net dashboard: one toggle to start/stop the VPN engine, live metrics from
 * the Go scheduler below. The service itself ([MergifyVpnService]) is unchanged — this
 * file only restyles the experience.
 */
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
            MaterialTheme(colorScheme = MergifyColors) {
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

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

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
            .background(Ui.Background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(52.dp))

        // Wordmark: letterspaced mono, optically centred (letterSpacing adds a
        // trailing gap after the last glyph, compensated by the start padding).
        Text(
            text = "MERGIFY · NET",
            modifier = Modifier.padding(start = 6.dp),
            color = Ui.TextSecondary,
            fontSize = 11.sp,
            letterSpacing = 6.sp,
            fontFamily = Ui.Mono,
        )
        Spacer(Modifier.height(12.dp))
        StatusLine(running)

        Spacer(Modifier.height(44.dp))
        ToggleButton(running = running, onClick = onToggle)

        Spacer(Modifier.height(48.dp))
        AnimatedVisibility(
            visible = running,
            enter = fadeIn(tween(350)) + expandVertically(tween(400, easing = FastOutSlowInEasing)),
        ) {
            NetworksPanel(stats)
        }

        Spacer(Modifier.height(28.dp))
        val v6 = stats?.v6.orEmpty()
        if (v6.isNotEmpty()) {
            Text(
                text = "IPv6 · $v6",
                color = Ui.TextDim,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontFamily = Ui.Mono,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusLine(running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot(active = running)
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (running) "TUNNEL ACTIF" else "TUNNEL INACTIF",
            color = Ui.TextSecondary,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontFamily = Ui.Mono,
        )
    }
}

/** The single restrained accent of the whole UI: a slow-breathing green dot while live. */
@Composable
private fun LiveDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "live-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "live-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (active) Ui.Live.copy(alpha = alpha) else Ui.TextDim),
    )
}

/** Centre of the experience: a pill that fills with light when the tunnel is up. */
@Composable
private fun ToggleButton(running: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (running) Ui.TextPrimary else Color.Transparent,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "toggle-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (running) Ui.Background else Ui.TextPrimary,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "toggle-fg",
    )
    val outline by animateColorAsState(
        targetValue = if (running) Color.Transparent else Ui.Outline,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "toggle-outline",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),
        label = "toggle-scale",
    )

    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, outline, RoundedCornerShape(50))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (running) "VPN ACTIF" else "ACTIVER LE VPN",
            color = foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
            fontFamily = Ui.Mono,
        )
    }
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

/** One hairline-bordered card listing every network the engine balances. */
@Composable
private fun NetworksPanel(stats: Stats?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ui.Surface)
            .border(1.dp, Ui.Hairline, RoundedCornerShape(20.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(
            text = "RÉSEAUX",
            color = Ui.TextSecondary,
            fontSize = 10.sp,
            letterSpacing = 4.sp,
            fontFamily = Ui.Mono,
        )
        Spacer(Modifier.height(18.dp))
        val nics = stats?.nics
        if (nics.isNullOrEmpty()) {
            Text("Aucun réseau actif", color = Ui.TextDim, fontSize = 13.sp)
        } else {
            nics.forEachIndexed { index, nic ->
                if (index > 0) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Ui.Hairline),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                NicRow(nic)
            }
        }
    }
}

/** A network: three clean text lines — identity/flows, throughput/latency, state/address. */
@Composable
private fun NicRow(nic: NicStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nic.name,
                    color = Ui.TextPrimary,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    fontFamily = Ui.Mono,
                )
                if (nic.primary) {
                    Spacer(Modifier.width(8.dp))
                    Text("★", color = Ui.TextPrimary, fontSize = 11.sp)
                }
            }
            Text(
                text = "${nic.active} flux",
                color = Ui.TextSecondary,
                fontSize = 12.sp,
                fontFamily = Ui.Mono,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↓ ${fmtRate(nic.rateIn)}   ↑ ${fmtRate(nic.rateOut)}",
                color = Ui.TextPrimary,
                fontSize = 13.sp,
                fontFamily = Ui.Mono,
            )
            if (nic.rtt >= 0) {
                Text(
                    text = "%.0f ms".format(nic.rtt),
                    color = Ui.TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = Ui.Mono,
                )
            }
        }
        val states =
            if (nic.ip6.isNotEmpty()) "ipv4 ${nic.state} · ipv6 ${nic.state6}" else "ipv4 ${nic.state}"
        Text(
            text = states,
            color = Ui.TextDim,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontFamily = Ui.Mono,
        )
        val addr = listOf(nic.ip, nic.ip6).filter { it.isNotEmpty() }.joinToString("   ")
        if (addr.isNotEmpty()) {
            Text(
                text = addr,
                color = Ui.TextDim,
                fontSize = 10.sp,
                fontFamily = Ui.Mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

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
