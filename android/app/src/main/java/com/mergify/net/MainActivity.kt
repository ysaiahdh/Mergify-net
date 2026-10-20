package com.mergify.net

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
// no shadows, monospaced figures, and only two signal colours: the live dot,
// plus a restrained amber for an interface the user's own budget cut off.
// ---------------------------------------------------------------------------
private object Ui {
    val Background = Color(0xFF0A0A0C) // deep, near-black
    val Surface = Color(0xFF111114) // cards, barely lifted
    val Hairline = Color(0x14FFFFFF) // 8% white: borders and dividers
    val Outline = Color(0x2EFFFFFF) // 18% white: the idle toggle border
    val TextPrimary = Color(0xFFF2F2F5)
    val TextSecondary = Color(0xFF8A8A93)
    val TextDim = Color(0x4DFFFFFF) // 30% white: addresses, footer
    val Live = Color(0xFF3DD68C) // accent, reserved for the active dot
    val Alert = Color(0xFFE2A93B) // amber: the cellular cap reached its budget
    val Mono = FontFamily.Monospace
    val MonoStyle = TextStyle(fontFamily = Mono)
}

private val MergifyColors = darkColorScheme(
    background = Ui.Background,
    surface = Ui.Surface,
    onBackground = Ui.TextPrimary,
    onSurface = Ui.TextPrimary,
)

/** How often the poll loop writes its one-line data-usage summary to logcat. */
private const val USAGE_LOG_MS = 15_000L

/**
 * Mergify-Net dashboard: one toggle to start/stop the VPN engine, live metrics from the Go
 * scheduler below, and the *Paramètres* screen behind the gear — a cellular budget and the
 * multi-flux policy, both persisted in SharedPreferences and pushed to the engine through
 * [MergifyVpnService] / `Mobile`.
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
                val context = LocalContext.current
                val repository = remember(context) { SettingsRepository(context) }
                var settings by remember { mutableStateOf(repository.load()) }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                // Where every settings change lands, in order: state (the screen redraws), disk
                // (SharedPreferences — only once a gesture is over, see onCommit), then the
                // engine (multi-flux goes straight to Go, which keeps it for the next session
                // even when the tunnel is down). The cellular cap needs no push: the stats loop
                // below re-evaluates it against the new budget on its next tick. Go is only
                // touched when the switch actually moved — a slider drag would otherwise make
                // the same no-op JNI call on every frame.
                val update: (AppSettings, Boolean) -> Unit = { new, persist ->
                    if (new.forceMultiFlow != settings.forceMultiFlow) {
                        Mobile.setForceMultiFlow(new.forceMultiFlow)
                        Log.i(TAG, "multi-flux → ${new.forceMultiFlow}")
                    }
                    settings = new
                    if (persist) repository.save(new)
                }

                if (showSettings) {
                    SettingsScreen(
                        settings = settings,
                        onBack = { showSettings = false },
                        onChange = { update(it, false) },
                        onLimitChange = { bytes ->
                            update(settings.copy(mobileLimitBytes = bytes), false)
                        },
                        onCommit = { update(settings, true) },
                    )
                } else {
                    VpnScreen(
                        settings = settings,
                        onToggle = { if (MergifyVpnService.isRunning) stopVpn() else requestVpnConsent() },
                        onOpenSettings = { showSettings = true },
                    )
                }
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
private fun VpnScreen(
    settings: AppSettings,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var running by remember { mutableStateOf(MergifyVpnService.isRunning) }
    var stats by remember { mutableStateOf<Stats?>(null) }

    // Cellular cut by the user's budget: mirrored from the service each tick, so the CONSOMMATION
    // card shows it even when the loop itself was the one that asked for the cut.
    var mobileCapped by remember { mutableStateOf(MergifyVpnService.mobileDataCapped) }

    // Session counters come from the engine (zeroed at every start); the tracker folds their
    // growth into cumulative totals kept in SharedPreferences across sessions.
    val context = LocalContext.current
    val tracker = remember(context) { DataUsageTracker(context) }
    var cumulative by remember { mutableStateOf(tracker.cumulative) }

    // The loop below runs for as long as the screen exists, while the settings can move under it
    // (the cap may be raised from the other screen): rememberUpdatedState keeps it reading the
    // current budget instead of the one captured at launch.
    val currentSettings by rememberUpdatedState(settings)

    // Poll the Go engine once a second: GetStatsJSON is a cheap JNI call into the scheduler.
    LaunchedEffect(Unit) {
        var lastUsageLog = 0L
        var capApplied = false // what we last told the service, so the crossing is pushed once
        while (true) {
            running = MergifyVpnService.isRunning
            val parsed = if (running) {
                parseStats(withContext(Dispatchers.IO) { Mobile.getStatsJSON() })
            } else {
                null
            }
            stats = parsed
            if (running) {
                // Only a payload that parsed is folded in: a failed parse must not reset the
                // baseline, or the next good poll would re-count the whole session.
                parsed?.usage?.let { cumulative = tracker.update(it) }

                // Data cap: the moment the cumulative cellular volume reaches the budget, the
                // service publishes cellular as absent (UpdateNetworks(wifiHandle, 0)) so no new
                // flow leaves through 4G/5G, and notifies. Raising the budget undoes it here,
                // and lowering it below the current volume cuts the route on this very tick.
                val reached = currentSettings.limitReached(cumulative.cell)
                if (reached != capApplied) {
                    capApplied = reached
                    MergifyVpnService.setMobileDataCap(reached)
                }
                mobileCapped = MergifyVpnService.mobileDataCapped

                val now = System.currentTimeMillis()
                if (parsed != null && now - lastUsageLog >= USAGE_LOG_MS) {
                    lastUsageLog = now
                    val u = parsed.usage
                    Log.d(
                        TAG,
                        "usage: session ${formatBytes(u.total)} " +
                            "(wifi ${formatBytes(u.wifi)} / mobile ${formatBytes(u.cell)}) · " +
                            "cumulé ${formatBytes(cumulative.total)}",
                    )
                }
            } else {
                tracker.reset() // the tunnel stopped: the next session starts from a fresh baseline
                capApplied = false // the service forgets the cap too: it re-checks on the next start
                mobileCapped = false
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

        // Header: wordmark centred (letterspaced mono, optically compensated by the start
        // padding), settings entry point at the end of the same line.
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "MERGIFY · NET",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 6.dp),
                color = Ui.TextSecondary,
                fontSize = 11.sp,
                letterSpacing = 6.sp,
                fontFamily = Ui.Mono,
            )
            SettingsButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
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

        Spacer(Modifier.height(20.dp))
        DataUsageCard(session = stats?.usage, cumulative = cumulative, mobileCapped = mobileCapped)

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
// Settings
// ---------------------------------------------------------------------------

/**
 * The settings entry point: a hairline gear at the end of the wordmark's line. Drawn instead of
 * borrowed from an icon set so the header keeps exactly the strokes the rest of the UI uses, and
 * the app pulls in no icon dependency for one glyph.
 */
@Composable
private fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp) // the drawn gear is 20 dp; the rest is unmarked touch target
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GearIcon()
    }
}

/** Eight teeth around a ring and a hub — a gear at 20 dp, stroked in the current text colour. */
@Composable
private fun GearIcon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.5.dp.toPx()
        val radius = size.minDimension / 2f - stroke / 2f // strokes are centred: stay inside
        val body = radius * 0.62f
        val hub = radius * 0.26f
        repeat(8) { i ->
            rotate(degrees = 45f * i, pivot = center) {
                drawLine(
                    color = Ui.TextSecondary,
                    start = Offset(center.x, center.y - body + stroke / 2),
                    end = Offset(center.x, center.y - radius),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        val circle = Stroke(width = stroke, cap = StrokeCap.Round)
        drawCircle(color = Ui.TextSecondary, radius = body, style = circle)
        drawCircle(color = Ui.TextSecondary, radius = hub, style = circle)
    }
}

/**
 * *Paramètres*: the two choices the app persists ([SettingsRepository]) — a cellular budget the
 * stats loop of [VpnScreen] enforces, and the multi-flux policy pushed straight to Go. Same
 * surface, hairlines and monospaced labels as the dashboard. [onLimitChange] fires on every move
 * with the budget snapped to the scale; [onCommit] fires when a gesture ends, which is when that
 * value is written to disk.
 */
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChange: (AppSettings) -> Unit,
    onLimitChange: (Long) -> Unit,
    onCommit: () -> Unit,
) {
    // The thumb follows the finger from this local copy: a drag never waits for the settings
    // state (and the recomposition it schedules) to come back around before it can move. The
    // persisted budget is only the key that re-seeds it, so re-entering the screen, toggling
    // Illimité or a change made elsewhere all land on the right position again.
    var sliderPosition by remember(settings.mobileLimitBytes) {
        mutableFloatStateOf(settings.mobileLimitBytes.toFloat())
    }

    // The read-out tracks the thumb rather than the round trip through the parent's state.
    val limitLabel = if (settings.unlimitedMobileData) {
        settings.mobileLimitLabel
    } else {
        formatBytes(snapToScale(sliderPosition.toLong()))
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

        Text(
            text = "‹ RETOUR",
            modifier = Modifier
                .align(Alignment.Start)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            color = Ui.TextSecondary,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontFamily = Ui.Mono,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "PARAMÈTRES",
            modifier = Modifier.padding(start = 6.dp),
            color = Ui.TextSecondary,
            fontSize = 11.sp,
            letterSpacing = 6.sp,
            fontFamily = Ui.Mono,
        )

        Spacer(Modifier.height(44.dp))
        SettingsCard(label = "CONSOMMATION MOBILE") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "SEUIL MOBILE",
                    color = Ui.TextSecondary,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontFamily = Ui.Mono,
                )
                Text(
                    text = limitLabel,
                    color = Ui.TextPrimary,
                    fontSize = 15.sp,
                    fontFamily = Ui.Mono,
                )
            }

            Spacer(Modifier.height(16.dp))
            Slider(
                value = sliderPosition,
                onValueChange = { position ->
                    sliderPosition = position
                    onLimitChange(snapToScale(position.toLong()))
                },
                onValueChangeFinished = onCommit,
                enabled = !settings.unlimitedMobileData,
                valueRange = MOBILE_LIMIT_MIN_BYTES_FLOAT..MOBILE_LIMIT_MAX_BYTES_FLOAT,
                steps = MOBILE_LIMIT_SLIDER_STEPS,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Ui.TextPrimary,
                    activeTrackColor = Ui.TextPrimary,
                    inactiveTrackColor = Ui.Hairline,
                    disabledThumbColor = Ui.TextDim,
                    disabledActiveTrackColor = Ui.TextDim,
                    disabledInactiveTrackColor = Ui.Hairline,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${MOBILE_LIMIT_MIN_MB} Mo",
                    color = Ui.TextDim,
                    fontSize = 9.sp,
                    fontFamily = Ui.Mono,
                )
                Text(
                    text = "${MOBILE_LIMIT_MAX_MB / 1000} Go", // the scale's top, in SI units
                    color = Ui.TextDim,
                    fontSize = 9.sp,
                    fontFamily = Ui.Mono,
                )
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Ui.Hairline),
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ILLIMITÉ",
                        color = Ui.TextPrimary,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        fontFamily = Ui.Mono,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Aucun plafond : le trafic mobile n'est jamais coupé",
                        color = Ui.TextDim,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = settings.unlimitedMobileData,
                    onCheckedChange = { unlimited ->
                        onChange(settings.copy(unlimitedMobileData = unlimited))
                        onCommit()
                    },
                    colors = settingsSwitchColors(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingsCard(label = "MULTI-FLUX") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Forcer le trafic multi-flux",
                        color = Ui.TextPrimary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Répartit agressivement les connexions entre Wi-Fi et mobile",
                        color = Ui.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = settings.forceMultiFlow,
                    onCheckedChange = { force ->
                        onChange(settings.copy(forceMultiFlow = force))
                        onCommit()
                    },
                    colors = settingsSwitchColors(),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "POLITIQUE · " +
                    if (settings.forceMultiFlow) "RÉPARTITION FORCÉE" else "LATENCE D'ABORD",
                color = Ui.TextDim,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontFamily = Ui.Mono,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** One hairline-bordered block: the dashboard's card vocabulary, reused for a setting. */
@Composable
private fun SettingsCard(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ui.Surface)
            .border(1.dp, Ui.Hairline, RoundedCornerShape(20.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(
            text = label,
            color = Ui.TextSecondary,
            fontSize = 10.sp,
            letterSpacing = 4.sp,
            fontFamily = Ui.Mono,
        )
        Spacer(Modifier.height(18.dp))
        content()
    }
}

/** Both switches, styled like the VPN toggle: a pill that fills with light when on. */
@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Ui.Background,
    checkedTrackColor = Ui.TextPrimary,
    checkedBorderColor = Ui.TextPrimary,
    uncheckedThumbColor = Ui.TextSecondary,
    uncheckedTrackColor = Ui.Background,
    uncheckedBorderColor = Ui.Outline,
)

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

/**
 * Session and cumulative volume, plus the numerical split between what went over Wi-Fi and
 * what went over the cellular network (4G/5G).
 *
 * The split columns are `session / cumulative`; the bar follows the live session while one is
 * running and the cumulative totals otherwise. Both come from the engine's per-interface atomic
 * counters, so it only ever counts traffic carried through mergify-net. [mobileCapped] adds the
 * one line the settings asked for: cellular is out of the balance until the budget is raised.
 */
@Composable
private fun DataUsageCard(session: DataUsage?, cumulative: DataUsage, mobileCapped: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ui.Surface)
            .border(1.dp, Ui.Hairline, RoundedCornerShape(20.dp))
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(
            text = "CONSOMMATION",
            color = Ui.TextSecondary,
            fontSize = 10.sp,
            letterSpacing = 4.sp,
            fontFamily = Ui.Mono,
        )
        Spacer(Modifier.height(18.dp))
        UsageRow(label = "SESSION", value = session?.let { formatBytes(it.total) } ?: "—", emphasised = true)
        Spacer(Modifier.height(10.dp))
        UsageRow(label = "CUMULÉ", value = formatBytes(cumulative.total), emphasised = true)

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ui.Hairline),
        )
        Spacer(Modifier.height(16.dp))

        val live = session != null && session.total > 0
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "RÉPARTITION",
                color = Ui.TextDim,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontFamily = Ui.Mono,
            )
            Text(
                text = "SESSION / CUMULÉ",
                color = Ui.TextDim,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                fontFamily = Ui.Mono,
            )
        }
        Spacer(Modifier.height(12.dp))
        TransportRow("WI-FI", session?.wifi, cumulative.wifi)
        Spacer(Modifier.height(8.dp))
        TransportRow("MOBILE 4G/5G", session?.cell, cumulative.cell)
        Spacer(Modifier.height(14.dp))
        ShareBar(if (live) session else cumulative)

        if (mobileCapped) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "MOBILE COUPÉ · SEUIL ATTEINT",
                color = Ui.Alert,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontFamily = Ui.Mono,
            )
        }
    }
}

/** One label / figure line — the card's whole vocabulary. */
@Composable
private fun UsageRow(label: String, value: String, emphasised: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colour = if (emphasised) Ui.TextPrimary else Ui.TextSecondary
        Text(
            text = label,
            color = colour,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontFamily = Ui.Mono,
        )
        Text(
            text = value,
            color = colour,
            fontSize = 13.sp,
            fontFamily = Ui.Mono,
        )
    }
}

/** One transport: its session volume (—" if no session) over its cumulative volume. */
@Composable
private fun TransportRow(label: String, session: Long?, cumulative: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Ui.TextPrimary,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontFamily = Ui.Mono,
        )
        Text(
            text = "${session?.let { formatBytes(it) } ?: "—"} / ${formatBytes(cumulative)}",
            color = Ui.TextSecondary,
            fontSize = 12.sp,
            fontFamily = Ui.Mono,
        )
    }
}

/** Wi-Fi (bright) versus cellular (dim) share of [base], in one hairline track. */
@Composable
private fun ShareBar(base: DataUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Ui.Hairline),
    ) {
        if (base.total > 0) {
            val total = base.total.toFloat()
            Box(
                modifier = Modifier
                    .weight(base.wifi / total)
                    .fillMaxHeight()
                    .background(Ui.TextPrimary),
            )
            Box(
                modifier = Modifier
                    .weight(base.cell / total)
                    .fillMaxHeight()
                    .background(Ui.TextDim),
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

private data class Stats(
    val running: Boolean,
    val v6: String,
    val nics: List<NicStats>,
    val usage: DataUsage, // session data usage, per transport, straight from the engine
)

private fun fmtRate(bytesPerSec: Double): String = when {
    bytesPerSec >= 1_000_000 -> "%.1f Mo/s".format(bytesPerSec / 1_000_000)
    bytesPerSec >= 1_000 -> "%.0f ko/s".format(bytesPerSec / 1_000)
    else -> "%.0f o/s".format(bytesPerSec)
}

/**
 * Parses GetStatsJSON's output (Go's SlotStats field names, no JSON tags), including the four
 * data-usage counters — `wifi_rx_bytes`, `wifi_tx_bytes`, `cell_rx_bytes`, `cell_tx_bytes`.
 * `total_bytes` is the engine's own sum of those four; [DataUsage.total] recomputes it so the
 * UI never risks the two drifting apart. Any malformed payload just shows nothing rather than
 * crashing the UI.
 */
private fun parseStats(json: String): Stats? = runCatching {
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
                    primary = n.optBoolean("Primary", false),
                ),
            )
        }
    }
    Stats(root.optBoolean("running"), root.optString("v6"), nics, usage)
}.onFailure {
    Log.w(TAG, "stats JSON unparsable: ${json.take(160)}")
}.getOrNull()
