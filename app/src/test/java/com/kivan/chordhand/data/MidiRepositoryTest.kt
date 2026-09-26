package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.midi.BleMidiManagerDataSource
import com.kivan.chordhand.data.midi.MidiRepositoryImpl
import com.kivan.chordhand.data.midi.MockMidiDataSource
import com.kivan.chordhand.domain.model.ConnectionState
import com.kivan.chordhand.domain.model.MidiEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MidiRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var bleDataSource: BleMidiManagerDataSource
    private lateinit var mockDataSource: MockMidiDataSource
    private lateinit var repository: MidiRepositoryImpl

    private val bleMidiEvents = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 64)
    private val bleConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.BluetoothOff)

    @Before
    fun setUp() {
        bleDataSource = mockk(relaxed = true) {
            every { midiEvents } returns bleMidiEvents
            every { connectionState } returns bleConnectionState
        }
        mockDataSource = MockMidiDataSource()
        repository = MidiRepositoryImpl(
            bleDataSource = bleDataSource,
            mockDataSource = mockDataSource,
            dispatcher = testDispatcher
        )
    }

    @Test
    fun `initial connection state initializes from bleDataSource`() {
        assertThat(repository.connectionState.value).isEqualTo(ConnectionState.BluetoothOff)
    }

    @Test
    fun `connection state updates as bleDataSource emits changes`() = runTest {
        bleConnectionState.value = ConnectionState.Disconnected
        assertThat(repository.connectionState.value).isEqualTo(ConnectionState.Disconnected)

        bleConnectionState.value = ConnectionState.Scanning
        assertThat(repository.connectionState.value).isEqualTo(ConnectionState.Scanning)

        bleConnectionState.value = ConnectionState.Connected(deviceName = "Roland FP-10", isSimulated = false, latencyMs = 3L)
        assertThat(repository.connectionState.value).isEqualTo(
            ConnectionState.Connected(deviceName = "Roland FP-10", isSimulated = false, latencyMs = 3L)
        )
    }

    @Test
    fun `startScanning delegates to bleDataSource`() {
        repository.startScanning()
        verify { bleDataSource.startScan() }
    }

    @Test
    fun `stopScanning delegates to bleDataSource`() {
        repository.stopScanning()
        verify { bleDataSource.stopScan() }
    }

    @Test
    fun `disconnect delegates to bleDataSource and syncs connection state`() {
        bleConnectionState.value = ConnectionState.BluetoothOff
        repository.disconnect()
        verify { bleDataSource.disconnect() }
        assertThat(repository.connectionState.value).isEqualTo(ConnectionState.BluetoothOff)
    }
}
