package com.kivan.chordhand.data.midi

import com.kivan.chordhand.domain.model.MidiEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Synthetic MIDI event generator for manual touch input and automated scale demonstrations.
 */
class MockMidiDataSource(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _events = MutableSharedFlow<MidiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MidiEvent> = _events.asSharedFlow()

    private var autoPlayJob: Job? = null

    fun sendDirectMidiEvent(event: MidiEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    fun startAutoImprovSimulation(
        scaleNotes: List<Int> = listOf(60, 62, 64, 65, 67, 69, 71, 72),
        hazardNotes: List<Int> = listOf(61, 63, 66, 68, 70),
        targetAccuracy: Float = 0.90f
    ) {
        stopAutoSimulation()
        autoPlayJob = scope.launch {
            var noteCount = 0
            while (true) {
                val isSafe = Random.nextFloat() <= targetAccuracy
                val note = if (isSafe || hazardNotes.isEmpty()) {
                    scaleNotes.random()
                } else {
                    hazardNotes.random()
                }
                val velocity = Random.nextInt(60, 115)
                val durationMs = Random.nextLong(150L, 350L)

                _events.emit(MidiEvent.NoteOn(note = note, velocity = velocity))

                // Periodically toggle sustain pedal
                noteCount++
                if (noteCount % 8 == 0) {
                    _events.emit(MidiEvent.ControlChange(controller = 64, value = 127)) // Sustain down
                } else if (noteCount % 8 == 4) {
                    _events.emit(MidiEvent.ControlChange(controller = 64, value = 0))   // Sustain up
                }

                delay(durationMs)
                _events.emit(MidiEvent.NoteOff(note = note))
                delay(Random.nextLong(40L, 160L))
            }
        }
    }

    fun stopAutoSimulation() {
        autoPlayJob?.cancel()
        autoPlayJob = null
    }
}
