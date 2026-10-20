package com.mergify.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import mergifynet.mobile.Mobile
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * The VPN front-end. Establishes the virtual adapter (10.0.0.2/24, all traffic routed in),
 * hands its file descriptor to the Go engine — which never opens /dev/net/tun, impossible on
 * Android without root — and keeps the Go side in sync with the networks available on the
 * device. The service itself is the [mergifynet.mobile.AndroidNetworkBridge] Go calls on every outbound
 * socket: protect() keeps our own flows out of the TUN (no routing loop) and bindSocket
 * pins each flow to the network (Wi-Fi or 5G) its balancing picked.
 *
 * The service also owns the two notifications of the product: the ongoing one, refreshed once
 * a second from `GetStatsJSON` with live throughput/latency (and shaped for One UI's Now Bar),
 * and a high-importance alert when the active link degrades or Wi-Fi drops and cellular takes
 * over. Both channels and the [NotificationManager] are created once — see [ensureChannels] and
 * [notificationManager].
 */
class MergifyVpnService : VpnService(), mergifynet.mobile.AndroidNetworkBridge {

    private lateinit var monitor: NetworkMonitor
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    @Volatile
    private var started = false

    // --- Notifications -------------------------------------------------------

    /**
     * One manager for the whole service lifetime: [getSystemService] is cheap but the channels
     * and the two PendingIntents below are even cheaper to keep, and re-creating them on every
     * stats tick would allocate for nothing.
     */
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var channelsReady = false

    /** The ongoing builder is reused, so only the two changing lines are rebuilt each tick. */
    private var foregroundBuilder: NotificationCompat.Builder? = null
    private var lastTitle: String? = null
    private var lastText: String? = null

    private val openAppIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The notification's direct action: same path as [ACTION_STOP] from the UI. */
    private val stopVpnIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, MergifyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // --- Network degradation detection --------------------------------------

    /** Handle last published to Go; a non-zero → 0 transition is the Wi-Fi drop we alert on. */
    private var lastWifiHandle = 0L

    /** What the scheduler preferred last tick, to catch an emergency move to cellular. */
    private var previousPrimary: String? = null
    private var degradedTicks = 0
    private var healthyTicks = 0
    private var degradationAlerted = false

    /** Guards [lastAlertAt] only: alerts are emitted from both the stats and the main thread. */
    private val alertLock = Any()
    private var lastAlertAt = 0L

    // --- Stats loop ----------------------------------------------------------

    /**
     * `GetStatsJSON` is a cheap JNI call, but it is still I/O-ish work: it runs on its own
     * thread, never on the main one, and stops with the tunnel. It is what keeps the ongoing
     * notification alive when the dashboard is closed.
     */
    private var statsThread: HandlerThread? = null
    private var statsHandler: Handler? = null
    private var statsTick: Runnable? = null

    /**
     * Cellular cap state: once the user's cumulative budget is reached, cellular is published to
     * Go as absent (`UpdateNetworks(wifiHandle, 0)`) so the scheduler stops handing new flows to
     * 4G/5G — flows already on it finish on their own. Read by [syncNetworksToGo], written by
     * [applyMobileDataCap]; mirrored into [mobileDataCapped] for the UI.
     */
    private var mobileCapped = false

    override fun onCreate() {
        super.onCreate()
        monitor = NetworkMonitor(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                // Notification action, or the dashboard's "Déconnecter": tear the Go engine and
                // the TUN down first, then remove the foreground notification even if the
                // service had already lost its `started` state (process restart).
                stopVpn()
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (started) {
            return
        }
        startForegroundNotification()

        // 1. The adapter. The kernel side of the tunnel is fully configured here (address,
        //    routes, MTU): Go only reads/writes raw packets on the fd. The 1500 MTU matches
        //    the one the Go stack was built with (Mobile.StartVpn's androidMTU).
        val pfd = Builder()
            .setSession("mergify-net")
            .setMtu(MTU)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .establish()
        if (pfd == null) {
            // The user revoked the consent, or another VPN took the slot.
            Log.e(TAG, "establish() refused")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // 2. The settings: the multi-flux switch goes to Go before the engine exists (StartVpn
        //    applies it to the scheduler it builds, and Go keeps it for the next sessions), and
        //    the cellular cap is re-evaluated against the *cumulative* volume — a restart that
        //    starts over budget must not hand cellular a free second while the UI polls. Setting
        //    it here means the sync below already publishes cellular as absent when it holds.
        val settings = SettingsRepository(this).load()
        Mobile.setForceMultiFlow(settings.forceMultiFlow)
        applyMobileDataCap(settings.limitReached(DataUsageTracker.peek(this).cell), notify = true)

        // 3. The networks: register what is up right now, then keep Go posted on every change.
        //    StartVpn refuses to run with no network registered, hence the synchronous
        //    syncNetworksToGo() right after start().
        monitor.start { syncNetworksToGo() }
        syncNetworksToGo()

        // 4. The fd's ownership goes to Go: detachFd() prevents the ParcelFileDescriptor (or
        //    its finalizer, when the GC collects it) from closing a descriptor Go is using.
        //    From here on Go closes it — on StopVpn, or on any start error.
        val tunFd = pfd.detachFd()
        try {
            Mobile.startVpn(tunFd.toLong(), this)
        } catch (e: Exception) {
            Log.e(TAG, "Go engine refused to start: $e")
            monitor.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        started = true
        isRunning = true
        startStatsLoop()
        // Lets the system score connectivity (captive-portal checks, etc.) on the real networks.
        setUnderlyingNetworks(monitor.networks().map { it.second }.toTypedArray())
        Log.i(TAG, "mergify-net started (Wi-Fi=${monitor.handles().first}, cell=${monitor.handles().second})")
        // What this session runs with, so `adb logcat -s MergifyNet` shows the policy and the cap
        // state at startup as well, not only when they move.
        Log.i(TAG, "settings: multi-flux=${settings.forceMultiFlow}")
        Log.i(
            TAG,
            "settings: seuil mobile=${settings.mobileLimitLabel}" +
                if (mobileCapped) " (atteint, cell coupée)" else "",
        )
    }

    private fun stopVpn() {
        if (!started) {
            return
        }
        started = false
        isRunning = false
        stopStatsLoop()
        runCatching { Mobile.stopVpn() } // closes the TUN fd Go owns
        monitor.stop()
        setUnderlyingNetworks(null)
        // Nothing runs through the tunnel anymore, so nothing is being cut: the next start
        // re-evaluates the budget against the cumulative volume anyway.
        mobileCapped = false
        mobileDataCapped = false
        cancelMobileDataCapNotification()
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundBuilder = null
        lastTitle = null
        lastText = null
        previousPrimary = null
        degradedTicks = 0
        healthyTicks = 0
        degradationAlerted = false
        lastWifiHandle = 0L
        Log.i(TAG, "mergify-net stopped")
    }

    /**
     * Pushes the current networks to Go: registered Wi-Fi first, cellular second, because
     * Mobile.updateNetworks maps its two handles by that order. The source addresses of each
     * network come from its LinkProperties — the scheduler pins every flow to them.
     */
    private fun syncNetworksToGo() {
        try {
            Mobile.clearManualNics()
            for ((name, network) in monitor.networks()) {
                val lp = cm.getLinkProperties(network) ?: continue
                var ip4 = ""
                var ip6 = ""
                for (la in lp.linkAddresses) {
                    val address = la.address ?: continue
                    when {
                        address is Inet4Address && ip4.isEmpty() ->
                            ip4 = address.hostAddress ?: ""

                        address is Inet6Address && ip6.isEmpty() && !address.isLinkLocalAddress ->
                            ip6 = address.hostAddress ?: ""
                    }
                }
                if (ip4.isEmpty() && ip6.isEmpty()) {
                    continue
                }
                Mobile.addManualNic(name, ip4, ip6, NetworkMonitor.handle(network), WEIGHT)
            }
            Mobile.applyManualNics()
            val (wifiHandle, cellHandle) = monitor.handles()
            // 0 tells Go the network is unavailable (or, here, not allowed anymore): while the
            // cap holds cellular stays unpublished, so no new flow leaves through 4G/5G.
            Mobile.updateNetworks(wifiHandle, if (mobileCapped) 0L else cellHandle)
            observeTransportChange(wifiHandle, cellHandle)
        } catch (e: Exception) {
            Log.e(TAG, "sync networks to Go: $e")
        }
    }

    // --- Ongoing notification ------------------------------------------------

    /**
     * The permanent notification: public (it shows on the lock screen), ongoing, a service, and
     * silent on refresh (`setOnlyAlertOnce` + a LOW channel). [transportLabel] and
     * [refreshForegroundNotification] rewrite only the title and the metrics line once a second.
     *
     * The two extras are what One UI's Now Bar and Android 16's Live Updates read to promote an
     * ongoing notification: `EXTRA_SHORT_CRITICAL_TEXT` and `EXTRA_REQUEST_PROMOTED_ONGOING`.
     * They are framework extras introduced with API 36 and this module compiles against 35, so
     * they are set by their stable AOSP key rather than through NotificationCompat (whose
     * `setShortCriticalText`/`setRequestPromotedOngoing` only arrive in core 1.17, which needs
     * compileSdk 36). Unknown extras are ignored by older releases. Promotion also requires
     * `setColorized(true)` on the request: the framework only sets `FLAG_PROMOTED_ONGOING`
     * itself when the ongoing notification has promotable characteristics.
     *
     * [NotificationCompat.BigTextStyle] is deliberate: the framework's
     * `hasPromotableCharacteristics()` accepts a null style, BigTextStyle, CallStyle or
     * ProgressStyle, but *not* MediaStyle. MediaStyle would also require a MediaSession and
     * would present a VPN as a media player, so it is not used here.
     */
    private fun buildForegroundNotification(
        title: String,
        text: String,
        shortText: String,
    ): Notification {
        val builder = foregroundBuilder ?: NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_disconnect), stopVpnIntent)
            .also {
                it.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                foregroundBuilder = it
            }
        builder.extras.putString(EXTRA_SHORT_CRITICAL_TEXT, shortText)
        // Android 16+ only promotes an ongoing notification to the Now Bar / Live Updates when
        // it is both ongoing and *colorized* (Notification.hasPromotableCharacteristics). The
        // colorized background itself needs a signature permission, but the request bit is what
        // the promotion check reads. On older releases this is a harmless no-op for a builder
        // whose color is left at COLOR_DEFAULT.
        return builder
            // One UI renders a colorized notification with the app's color; without an explicit
            // color it falls back to a pale surface that clashes with the dashboard's dark
            // theme. The deep surface keeps the shade/lock-screen card dark and legible.
            .setColor(0xFF111114.toInt())
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(text)
            // Expanded lock-screen / shade text. BigTextStyle is one of the styles the
            // framework still considers promotable (unlike MediaStyle).
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    private fun startForegroundNotification() {
        ensureChannels()
        val notification = buildForegroundNotification(
            getString(R.string.notification_connecting),
            getString(R.string.notification_measuring),
            getString(R.string.transport_offline),
        )
        // Service.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED is a system API, absent from the
        // public SDK; its value is stable since Android 10 and the manifest declares the same
        // type (VPN apps are explicitly allowed to use systemExempted).
        startForeground(NOTIFICATION_ID, notification, FGS_TYPE_SYSTEM_EXEMPTED)
    }

    /** Pushes the latest snapshot into the ongoing notification, once per stats tick. */
    private fun refreshForegroundNotification(stats: Stats?) {
        if (!started) {
            return
        }
        val transport = transportLabel(stats)
        val title = getString(R.string.notification_connected, transport)
        val text = metricsLine(stats)
        if (title == lastTitle && text == lastText) {
            return // nothing moved: avoid an identical notify() every second
        }
        lastTitle = title
        lastText = text
        notificationManager.notify(
            NOTIFICATION_ID,
            buildForegroundNotification(title, text, transport),
        )
    }

    /** The transport the scheduler currently prefers, with the UI's star; falls back to what
     *  the ConnectivityManager sees before the first probe snapshot lands. */
    private fun transportLabel(stats: Stats?): String {
        val primary = stats?.primaryNic
        if (primary != null) {
            return nicLabel(primary.name)
        }
        val (wifi, cell) = monitor.handles()
        return when {
            wifi != 0L && cell != 0L -> getString(R.string.transport_multi)
            wifi != 0L -> getString(R.string.transport_wifi)
            cell != 0L -> getString(R.string.transport_cell)
            else -> getString(R.string.transport_offline)
        }
    }

    private fun nicLabel(name: String): String = when (name) {
        "wifi" -> getString(R.string.transport_wifi)
        "cell" -> getString(R.string.transport_cell)
        else -> getString(R.string.transport_named, name)
    }

    private fun transportName(name: String): String = when (name) {
        "wifi" -> getString(R.string.transport_name_wifi)
        "cell" -> getString(R.string.transport_name_cell)
        else -> name
    }

    /** `"↓ 850 Mb/s  ↑ 400 Mb/s · Ping 18 ms"` — the primary link's live figures. */
    private fun metricsLine(stats: Stats?): String {
        if (stats == null || stats.nics.isEmpty()) {
            return getString(R.string.notification_measuring)
        }
        val primary = stats.primaryNic
        val down = primary?.rateIn ?: stats.nics.sumOf { it.rateIn }
        val up = primary?.rateOut ?: stats.nics.sumOf { it.rateOut }
        val rtt = primary?.rtt?.takeIf { it >= 0 }
            ?: stats.nics.map { it.rtt }.filter { it >= 0 }.minOrNull()
        return buildString {
            append("↓ ").append(formatBitrate(down))
            append("  ↑ ").append(formatBitrate(up))
            if (rtt != null) {
                append(" · Ping ").append("%.0f ms".format(rtt))
            }
        }
    }

    // --- Alerts --------------------------------------------------------------

    /**
     * One high-importance notification, shared by every alert: a degradation of the primary
     * link, a Wi-Fi drop with cellular taking over, or the scheduler moving the primary to
     * cellular. [ALERT_MIN_INTERVAL_MS] keeps a flapping link from turning into a stream of
     * heads-up banners; the alert is ephemeral (`setTimeoutAfter`) and tap-to-open.
     */
    private fun notifyAlert(title: String, text: String) {
        synchronized(alertLock) {
            val now = System.currentTimeMillis()
            if (lastAlertAt != 0L && now - lastAlertAt < ALERT_MIN_INTERVAL_MS) {
                return
            }
            lastAlertAt = now
        }
        ensureChannels()
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
            .setContentIntent(openAppIntent)
            .build()
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        Log.w(TAG, "alert: $title — $text")
    }

    /**
     * Flags a transport transition. Only the Wi-Fi → cellular case matters here: a non-zero
     * Wi-Fi handle becoming 0 while cellular is still up is exactly the emergency failover the
     * user must know about. The first sync (before [started]) only seeds the handles.
     */
    private fun observeTransportChange(wifiHandle: Long, cellHandle: Long) {
        val lostWifi = lastWifiHandle != 0L && wifiHandle == 0L
        lastWifiHandle = wifiHandle
        if (!started || !lostWifi || cellHandle == 0L) {
            return
        }
        notifyAlert(
            getString(R.string.alert_failover_title),
            getString(R.string.alert_failover_text),
        )
    }

    /**
     * Looks for a primary link that went bad. The Go scheduler already stops sending new flows
     * to a link whose smoothed loss passes 25 % (`primaryLossMax`), and probes report latency;
     * both are read here, two ticks in a row so a single noisy probe never vibrates the phone.
     * The alert re-arms only after five healthy ticks.
     */
    private fun detectDegradation(stats: Stats?) {
        if (stats == null || !stats.running) {
            return
        }
        val primary = stats.primaryNic ?: return
        val slow = primary.rtt >= 0 && primary.rtt > LATENCY_ALERT_MS
        val lossy = primary.loss >= LOSS_ALERT_THRESHOLD
        if (slow || lossy) {
            healthyTicks = 0
            degradedTicks++
            if (degradedTicks == DEGRADED_TICKS_TO_ALERT && !degradationAlerted) {
                degradationAlerted = true
                val (_, cell) = monitor.handles()
                val title = getString(R.string.alert_degradation_title, transportName(primary.name))
                val summary = degradationSummary(primary)
                val canFailOver = cell != 0L && primary.name != "cell"
                notifyAlert(
                    title,
                    if (canFailOver) {
                        getString(R.string.alert_degradation_failover, summary)
                    } else {
                        summary
                    },
                )
            }
        } else {
            degradedTicks = 0
            healthyTicks++
            if (healthyTicks >= HEALTHY_TICKS_TO_CLEAR) {
                degradationAlerted = false
            }
        }
        detectPrimaryHandover(primary)
    }

    /** "Latence 210 ms · pertes 32 %" — only the figures that actually tripped. */
    private fun degradationSummary(nic: NicStats): String = buildList {
        if (nic.rtt >= 0 && nic.rtt > LATENCY_ALERT_MS) {
            add(getString(R.string.alert_latency, "%.0f ms".format(nic.rtt)))
        }
        if (nic.loss >= LOSS_ALERT_THRESHOLD) {
            add(getString(R.string.alert_loss, "%.0f %%".format(nic.loss * 100)))
        }
    }.joinToString(" · ")

    /** Wi-Fi was primary and the scheduler has just moved to cellular: emergency failover. */
    private fun detectPrimaryHandover(primary: NicStats) {
        val previous = previousPrimary
        previousPrimary = primary.name
        if (previous == "wifi" && primary.name == "cell") {
            notifyAlert(
                getString(R.string.alert_failover_title),
                getString(R.string.alert_failover_text),
            )
        }
    }

    // --- Channels ------------------------------------------------------------

    /** Creates the three channels exactly once per service instance; re-creating them is a
     *  no-op for the system but an allocation and a Binder round-trip we don't need. Synchronized
     *  because the stats thread may raise the first alert before the main thread starts the loop. */
    @Synchronized
    private fun ensureChannels() {
        if (channelsReady) {
            return
        }
        channelsReady = true
        notificationManager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_description)
                    setShowBadge(false)
                    // The ongoing tunnel card is meant to be readable from the lock screen;
                    // the notification itself is also VISIBILITY_PUBLIC, and One UI keeps
                    // showing it when the device is locked.
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.alert_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.alert_channel_description)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(
                    CAP_CHANNEL_ID,
                    getString(R.string.mobile_cap_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    // --- Stats loop ----------------------------------------------------------

    private fun startStatsLoop() {
        if (statsHandler != null) {
            return
        }
        val thread = HandlerThread("mergify-stats").also { it.start() }
        val handler = Handler(thread.looper)
        statsThread = thread
        statsHandler = handler
        val tick = object : Runnable {
            override fun run() {
                runCatching { pollStats() }.onFailure { Log.w(TAG, "stats tick: $it") }
                handler.postDelayed(this, STATS_INTERVAL_MS)
            }
        }
        statsTick = tick
        handler.post(tick)
    }

    private fun stopStatsLoop() {
        val handler = statsHandler
        val tick = statsTick
        if (handler != null && tick != null) {
            handler.removeCallbacks(tick)
        }
        statsHandler = null
        statsTick = null
        statsThread?.quitSafely()
        statsThread = null
    }

    /** One tick: read the engine, refresh the ongoing notification, look for an alert. */
    private fun pollStats() {
        if (!started) {
            return
        }
        val stats = parseStats(Mobile.getStatsJSON())
        refreshForegroundNotification(stats)
        detectDegradation(stats)
    }

    /**
     * Applies or lifts the cellular cap. The only thing it changes is what [syncNetworksToGo]
     * publishes for cellular (`0` while capped) — the same path every network change already
     * takes, so a Wi-Fi handover during a capped session cannot silently re-open the route.
     * Notifies the user when it trips, cancels that notification when the budget is raised or
     * the tunnel stops; idempotent, so the stats poll loop can call it every tick.
     */
    private fun applyMobileDataCap(capped: Boolean, notify: Boolean = false) {
        if (mobileCapped == capped) {
            return
        }
        mobileCapped = capped
        mobileDataCapped = capped
        if (started) {
            syncNetworksToGo()
        }
        if (capped) {
            Log.w(TAG, "cellular budget reached: new flows stay on Wi-Fi")
            if (notify) {
                notifyMobileDataCap()
            }
        } else {
            Log.i(TAG, "cellular budget raised: 4G/5G is back in the balance")
            cancelMobileDataCapNotification()
        }
    }

    /** The one-off alert shown when the cellular budget is reached (its own channel: low for
     *  the ongoing VPN notification would mute it, and this one must be heard). */
    private fun notifyMobileDataCap() {
        ensureChannels()
        notificationManager.notify(
            CAP_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CAP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.mobile_cap_reached))
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun cancelMobileDataCapNotification() {
        runCatching { notificationManager.cancel(CAP_NOTIFICATION_ID) }
    }

    // --- mergifynet.mobile.AndroidNetworkBridge: called by Go, per outbound socket, right after its
    // --- creation and before connect() (net.Dialer.Control → RawConn.Control).

    /** VpnService.protect takes a raw fd and is public API: nothing special to do. */
    override fun protectSocket(socketFd: Long): Boolean = protect(socketFd.toInt())

    override fun bindSocketToNetwork(socketFd: Long, networkHandle: Long) {
        val network = monitor.networkFor(networkHandle)
            ?: throw IllegalArgumentException("no live network for handle $networkHandle")
        if (!bindFdToNetwork(network, socketFd.toInt(), networkHandle)) {
            throw IllegalStateException("could not bind socket to network $networkHandle")
        }
    }

    companion object {
        private const val TAG = "MergifyNet"
        const val ACTION_START = "com.mergify.net.action.START"
        const val ACTION_STOP = "com.mergify.net.action.STOP"
        const val MTU = 1500 // must match Mobile.StartVpn's stack MTU
        private const val WEIGHT = 1.0
        private const val CHANNEL_ID = "mergify-net"
        private const val NOTIFICATION_ID = 1
        private const val CAP_CHANNEL_ID = "mergify-net-cap"
        private const val CAP_NOTIFICATION_ID = 2

        /** Dedicated high-importance channel: degradation and failover alerts are heads-up. */
        private const val ALERT_CHANNEL_ID = "mergify-net-alerts"
        private const val ALERT_NOTIFICATION_ID = 3

        /** The ongoing notification's figures follow the Go scheduler once a second. */
        private const val STATS_INTERVAL_MS = 1_000L

        // Degradation thresholds, aligned on the scheduler's own primary selection: a primary
        // RTT above 100 ms or smoothed probe loss at/above primaryLossMax (0.25) is "bad".
        private const val LATENCY_ALERT_MS = 100.0
        private const val LOSS_ALERT_THRESHOLD = 0.25
        private const val DEGRADED_TICKS_TO_ALERT = 2 // two consecutive bad snapshots (~2 s)
        private const val HEALTHY_TICKS_TO_CLEAR = 5 // re-arm only after ~5 s of good link
        private const val ALERT_MIN_INTERVAL_MS = 15_000L // one heads-up per episode
        private const val ALERT_TIMEOUT_MS = 10_000L

        // Framework extras behind One UI's Now Bar / Android 16 Live Updates (API 36). Their
        // values are the public Notification.EXTRA_* strings; setting them as extras keeps the
        // code compiling against SDK 35 and harmless on older releases.
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        // Service.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED is a system API, absent from the
        // public SDK; its flag value is 1024 (the foregroundServiceType table in the SDK's
        // attrs) and the manifest declares the same type. There is no "vpn" foreground
        // service type in Android: VPN apps are covered by systemExempted.
        private const val FGS_TYPE_SYSTEM_EXEMPTED = 1024

        /** What the UI polls to know whether the tunnel is up. */
        @Volatile
        var isRunning = false
            private set

        /**
         * Whether cellular is currently cut by the user's budget. The dashboard reads it to show
         * the cap; [setMobileDataCap] is what changes it (through the running service).
         */
        @Volatile
        var mobileDataCapped = false
            private set

        @Volatile
        private var instance: MergifyVpnService? = null

        /**
         * Applies or lifts the cellular cap on the running tunnel. The stats poll loop calls it
         * whenever the cumulative cellular volume crosses the threshold — in either direction,
         * so raising the budget in the settings restores the route within a second. A no-op when
         * nothing runs: [startVpn] re-evaluates the budget on the next start anyway.
         */
        fun setMobileDataCap(capped: Boolean) {
            instance?.applyMobileDataCap(capped, notify = true)
        }

        /**
         * Binds an already-open socket fd to [network].
         *
         * There is no public API for this — Network.bindSocket(Socket) needs a java.net.Socket
         * the Go runtime owns, not us — so we go through the platform's own hidden helper, the
         * same route every production VPN app takes. [ParcelFileDescriptor.adoptFd] adopts the
         * raw fd so its FileDescriptor can be handed to the hidden method, and detachFd()
         * afterwards hands ownership back **without** closing it: without that, the PFD's
         * finalizer (or any early GC) would close a socket Go is still using.
         */
        private fun bindFdToNetwork(network: Network, fd: Int, handle: Long): Boolean {
            val pfd = ParcelFileDescriptor.adoptFd(fd)
            try {
                runCatching {
                    val bindSocket = Network::class.java
                        .getDeclaredMethod("bindSocket", FileDescriptor::class.java)
                    bindSocket.isAccessible = true
                    bindSocket.invoke(network, pfd.fileDescriptor)
                }.onSuccess { return true }

                // Fallback: NetworkUtils.bindSocketToNetwork(fd, netId). The handle Go passes
                // around is the netId shifted left by 32 on every Android version.
                val netId = (handle ushr 32).toInt()
                val bind = Class.forName("android.net.NetworkUtils")
                    .getDeclaredMethod(
                        "bindSocketToNetwork",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                bind.isAccessible = true
                bind.invoke(null, fd, netId)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "bindSocketToNetwork($handle) failed: $t")
                return false
            } finally {
                runCatching { pfd.detachFd() } // back to Go, unclosed
            }
        }
    }
}
