package com.mergify.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Keeps track of the two networks mergify-net can egress through — Wi-Fi and cellular (4G/5G) —
 * and notifies whenever one appears, changes or disappears.
 *
 * Go only ever sees opaque "network handles" ([Network.getNetworkHandle], API 31+). On older
 * versions we build the very same value from the network id (the handle is `netId << 32` on
 * every version), so the Kotlin side can always map a handle back to a [Network] with
 * [networkFor], whatever the Go side passes back.
 */
class NetworkMonitor(context: Context) {

    fun interface Listener { fun onNetworksChanged() }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var wifi: Network? = null
    private var cell: Network? = null
    private var listener: Listener? = null
    private var registered = false

    private val wifiCallback = callback(isWifi = true)
    private val cellCallback = callback(isWifi = false)

    /** Starts watching both transports; [l] is called on the main thread after every change. */
    fun start(l: Listener) {
        synchronized(lock) { listener = l }
        if (registered) {
            return
        }
        registered = true
        seed() // synchronous snapshot, so StartVpn doesn't race the async callbacks
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build(),
            wifiCallback,
        )
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build(),
            cellCallback,
        )
    }

    fun stop() {
        if (!registered) {
            return
        }
        registered = false
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(cellCallback) }
        synchronized(lock) {
            wifi = null
            cell = null
            listener = null
        }
    }

    /**
     * Picks up the networks already up right now. registerNetworkCallback also reports the
     * currently matching networks, but only asynchronously — too late for a service that wants
     * to start immediately.
     */
    @Suppress("DEPRECATION") // allNetworks is deprecated but still the only synchronous way
    private fun seed() {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> set(n, isWifi = true)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> set(n, isWifi = false)
            }
        }
    }

    private fun callback(isWifi: Boolean) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            set(network, isWifi)
            notifyChanged()
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                val current = if (isWifi) wifi else cell
                if (current == null || current != network) {
                    return // another network of the same transport took over
                }
                if (isWifi) wifi = null else cell = null
            }
            notifyChanged()
        }
    }

    private fun set(network: Network, isWifi: Boolean) {
        synchronized(lock) { if (isWifi) wifi = network else cell = network }
    }

    private fun notifyChanged() {
        val l = synchronized(lock) { listener }
        mainHandler.post { l?.onNetworksChanged() } // the listener talks to Go and the service
    }

    /** The current networks as (Go-side name, network), Wi-Fi first — Go's UpdateNetworks order. */
    fun networks(): List<Pair<String, Network>> = synchronized(lock) {
        buildList {
            wifi?.let { add("wifi" to it) }
            cell?.let { add("cell" to it) }
        }
    }

    /** The two handles in the order Go expects them (0 = network absent). */
    fun handles(): Pair<Long, Long> = synchronized(lock) {
        Pair(wifi?.let(::handle) ?: 0L, cell?.let(::handle) ?: 0L)
    }

    /** Maps a handle received from Go back to the live Network it was created from. */
    fun networkFor(handle: Long): Network? = synchronized(lock) {
        val w = wifi
        if (w != null && handle(w) == handle) {
            return@synchronized w
        }
        val c = cell
        if (c != null && handle(c) == handle) {
            return@synchronized c
        }
        null
    }

    companion object {
        /**
         * Network.getNetworkHandle() (API 31+) returns `netId << 32`. Before 31 the method
         * doesn't exist, but Network.toString() ends with the netId ("Network 102"), so the
         * same value can be rebuilt and stays a valid lookup key.
         */
        fun handle(network: Network): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            network.networkHandle
        } else {
            (network.toString().substringAfterLast(' ').toLongOrNull() ?: 0L) shl 32
        }
    }
}
