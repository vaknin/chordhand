package com.kivan.chordhand.data.midi

import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.domain.model.MidiEvent
import com.kivan.chordhand.domain.repository.MidiRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of [MidiRepository] managing BLE connection and simulated MIDI fallbacks.
 */
class MidiRepositoryImpl(
    private val bleDataSource: BleMidiManagerDataSource,
    private val mockDataSource: MockMidiDataSource = MockMidiDataSource(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : MidiRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _midiEvents = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 64)
    override val midiEvents: SharedFlow<MidiEvent> = _midiEvents.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(bleDataSource.connectionState.value)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var isSimulatedModeActive = false

    init {
        // Forward BLE events
        scope.launch {
            bleDataSource.midiEvents.collect { event ->
                if (!isSimulatedModeActive) {
                    _midiEvents.emit(event)
                }
            }
        }

        // Forward Mock events
        scope.launch {
            mockDataSource.events.collect { event ->
                if (isSimulatedModeActive) {
                    _midiEvents.emit(event)
                }
            }
        }

        // Forward BLE connection state
        scope.launch {
            bleDataSource.connectionState.collect { state ->
                if (!isSimulatedModeActive) {
                    _connectionState.value = state
                }
            }
        }
    }

    override fun startScanning() {
        if (isSimulatedModeActive) {
            enableSimulatedMode(false)
        }
        bleDataSource.startScan()
    }

    override fun stopScanning() {
        bleDataSource.stopScan()
    }

    override fun disconnect() {
        setMetronomeEnabled(false)
        if (isSimulatedModeActive) {
            mockDataSource.stopAutoSimulation()
            isSimulatedModeActive = false
        }
        bleDataSource.disconnect()
        _connectionState.value = bleDataSource.connectionState.value
    }

    override fun enableSimulatedMode(enabled: Boolean) {
        isSimulatedModeActive = enabled
        if (enabled) {
            bleDataSource.disconnect()
            _connectionState.value = ConnectionState.Connected(
                deviceName = "Virtual FP-10 Engine",
                isSimulated = true,
                latencyMs = 1L
            )
        } else {
            mockDataSource.stopAutoSimulation()
            _connectionState.value = bleDataSource.connectionState.value
        }
    }

    override fun simulateMidiEvent(event: MidiEvent) {
        mockDataSource.sendDirectMidiEvent(event)
    }

    override fun sendSysEx(data: ByteArray) {
        if (!isSimulatedModeActive) {
            bleDataSource.sendSysEx(data)
        }
    }

    override fun setMetronomeEnabled(enabled: Boolean) {
        val packet = RolandMidiSysEx.createMetronomeSwitchPacket(enabled)
        sendSysEx(packet)
    }

    override fun setMetronomeBpm(bpm: Int) {
        val packet = RolandMidiSysEx.createMetronomeTempoPacket(bpm)
        sendSysEx(packet)
    }
}

