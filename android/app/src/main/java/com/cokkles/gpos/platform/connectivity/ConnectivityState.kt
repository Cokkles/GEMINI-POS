package com.cokkles.gpos.platform.connectivity

sealed interface ConnectivityState {
    data object Unknown : ConnectivityState
    data object Offline : ConnectivityState
    data class Online(val validated: Boolean) : ConnectivityState
}

interface ConnectivityObserver {
    fun current(): ConnectivityState
}
