package com.navplus.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkConnectivityManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _state.value = caps.toConnectivityState()
        }
        override fun onLost(network: Network) {
            _state.value = ConnectivityState.OFFLINE
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    private fun currentState(): ConnectivityState {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return ConnectivityState.OFFLINE
        return caps.toConnectivityState()
    }

    private fun NetworkCapabilities.toConnectivityState(): ConnectivityState {
        if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return ConnectivityState.OFFLINE
        if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return ConnectivityState.OFFLINE
        return if (
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !isMetered())
        ) ConnectivityState.FULL else ConnectivityState.LIMITED
    }

    private fun NetworkCapabilities.isMetered(): Boolean =
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
