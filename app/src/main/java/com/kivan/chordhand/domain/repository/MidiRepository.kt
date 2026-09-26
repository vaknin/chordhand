package com.kivan.chordhand.domain.repository

import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.domain.model.MidiEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface MidiRepository {
    val midiEvents: SharedFlow<MidiEvent>
    val connectionState: StateFlow<ConnectionState>

    fun startScanning()
    fun stopScanning()
    fun disconnect()
    fun enableSimulatedMode(enabled: Boolean)
    fun simulateMidiEvent(event: MidiEvent)

    fun sendSysEx(data: ByteArray)
    fun setMetronomeEnabled(enabled: Boolean)
    fun setMetronomeBpm(bpm: Int)
}

