package com.kivan.chordhand.domain.model

/**
 * Clean MIDI event emitted after parsing raw BLE MIDI packets from Roland FP-10.
 */
sealed interface MidiEvent {
    val timestampMs: Long

    data class NoteOn(
        val note: Int,
        val velocity: Int,
        val channel: Int = 0,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : MidiEvent

    data class NoteOff(
        val note: Int,
        val channel: Int = 0,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : MidiEvent

    data class ControlChange(
        val controller: Int,
        val value: Int,
        val channel: Int = 0,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : MidiEvent {
        val isSustainPedal: Boolean get() = controller == 64
        val isSustainPressed: Boolean get() = controller == 64 && value >= 64
    }
}
