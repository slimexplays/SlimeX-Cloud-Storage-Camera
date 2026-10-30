package com.slimex.cloudcamera.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkStatus(
    val online: Boolean = false,
    val wifi: Boolean = false,
    val metered: Boolean = true,
)

interface ConnectivityObserver {
    val status: Flow<NetworkStatus>
    fun current(): NetworkStatus
}

@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityObserver {

    private val manager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override val status: Flow<NetworkStatus> = callbackFlow {
        val cm = manager
        if (cm == null) {
            trySend(NetworkStatus(online = false))
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(read(cm))
            }

            override fun onLost(network: Network) {
                trySend(read(cm))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(read(cm))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        trySend(read(cm))
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    override fun current(): NetworkStatus = manager?.let(::read) ?: NetworkStatus()

    private fun read(cm: ConnectivityManager): NetworkStatus {
        val active = cm.activeNetwork ?: return NetworkStatus()
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkStatus()
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val metered = !wifi || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkStatus(online = online, wifi = wifi, metered = metered)
    }
}
