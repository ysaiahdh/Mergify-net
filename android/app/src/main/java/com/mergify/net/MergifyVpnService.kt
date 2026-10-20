package com.mergify.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
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
 */
class MergifyVpnService : VpnService(), mergifynet.mobile.AndroidNetworkBridge {

    private lateinit var monitor: NetworkMonitor
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var started = false

    override fun onCreate() {
        super.onCreate()
        monitor = NetworkMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
            stopSelf()
            return
        }

        // 2. The networks: register what is up right now, then keep Go posted on every change.
        //    StartVpn refuses to run with no network registered, hence the synchronous
        //    syncNetworksToGo() right after start().
        monitor.start { syncNetworksToGo() }
        syncNetworksToGo()

        // 3. The fd's ownership goes to Go: detachFd() prevents the ParcelFileDescriptor (or
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
        // Lets the system score connectivity (captive-portal checks, etc.) on the real networks.
        setUnderlyingNetworks(monitor.networks().map { it.second }.toTypedArray())
        Log.i(TAG, "mergify-net started (Wi-Fi=${monitor.handles().first}, cell=${monitor.handles().second})")
    }

    private fun stopVpn() {
        if (!started) {
            return
        }
        started = false
        isRunning = false
        runCatching { Mobile.stopVpn() } // closes the TUN fd Go owns
        monitor.stop()
        setUnderlyingNetworks(null)
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
            Mobile.updateNetworks(wifiHandle, cellHandle)
        } catch (e: Exception) {
            Log.e(TAG, "sync networks to Go: $e")
        }
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

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
        // Service.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED is a system API, absent from the
        // public SDK; its value is stable since Android 10 and the manifest declares the same
        // type (VPN apps are explicitly allowed to use systemExempted).
        startForeground(NOTIFICATION_ID, notification, FGS_TYPE_SYSTEM_EXEMPTED)
    }

    companion object {
        private const val TAG = "MergifyNet"
        const val ACTION_START = "com.mergify.net.action.START"
        const val ACTION_STOP = "com.mergify.net.action.STOP"
        const val MTU = 1500 // must match Mobile.StartVpn's stack MTU
        private const val WEIGHT = 1.0
        private const val CHANNEL_ID = "mergify-net"
        private const val NOTIFICATION_ID = 1

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
