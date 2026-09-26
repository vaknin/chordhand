package com.kivan.chordhand.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object BluetoothOff : ConnectionState
    data object Scanning : ConnectionState
    data class Connected(
        val deviceName: String,
        val isSimulated: Boolean = false,
        val latencyMs: Long = 0L
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
