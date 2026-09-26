package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.midi.MidiByteParser
import com.kivan.chordhand.domain.model.MidiEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MidiByteParser] validating Android MidiManager raw MIDI streams
 * and MMA/Apple BLE-MIDI packets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MidiByteParserTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var parser: MidiByteParser

    @Before
    fun setUp() {
        parser = MidiByteParser(dispatcher = testDispatcher)
    }

    @Test
    fun `parsePacket decodes standard raw MIDI 1-0 Note On message from MidiManager`() = runTest {
        val packet = byteArrayOf(
            0x90.toByte(), // Note On (Ch 1)
            0x3C.toByte(), // Note 60 (C4)
            0x64.toByte()  // Velocity 100
        )

        val events = parser.parsePacket(packet)

        assertThat(events).hasSize(1)
        val event = events[0] as MidiEvent.NoteOn
        assertThat(event.note).isEqualTo(60)
        assertThat(event.velocity).isEqualTo(100)
    }

    @Test
    fun `parsePacket decodes standard raw MIDI Note Off message`() = runTest {
        val packet = byteArrayOf(
            0x80.toByte(), // Note Off (Ch 1)
            0x3C.toByte(), // Note 60 (C4)
            0x00.toByte()  // Velocity 0
        )

        val events = parser.parsePacket(packet)

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(MidiEvent.NoteOff::class.java)
        val event = events[0] as MidiEvent.NoteOff
        assertThat(event.note).isEqualTo(60)
    }

    @Test
    fun `parsePacket decodes raw MIDI Note On with velocity 0 as Note Off`() = runTest {
        val packet = byteArrayOf(
            0x90.toByte(),
            0x3C.toByte(),
            0x00.toByte()
        )

        val events = parser.parsePacket(packet)

        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(MidiEvent.NoteOff::class.java)
        val event = events[0] as MidiEvent.NoteOff
        assertThat(event.note).isEqualTo(60)
    }

    @Test
    fun `parsePacket decodes raw MIDI CC 64 Sustain Pedal event`() = runTest {
        val packet = byteArrayOf(
            0xB0.toByte(), // CC Status Ch 1
            0x40.toByte(), // Controller 64 (Damper/Sustain)
            0x7F.toByte()  // Value 127 (Pressed)
        )

        val events = parser.parsePacket(packet)

        assertThat(events).hasSize(1)
        val event = events[0] as MidiEvent.ControlChange
        assertThat(event.controller).isEqualTo(64)
        assertThat(event.value).isEqualTo(127)
        assertThat(event.isSustainPedal).isTrue()
        assertThat(event.isSustainPressed).isTrue()
    }

    @Test
    fun `parsePacket decodes raw MIDI running status stream`() = runTest {
        val packet = byteArrayOf(
            0x90.toByte(),
            0x3C.toByte(), 0x64.toByte(), // Note 60, Vel 100
            0x40.toByte(), 0x50.toByte(), // Note 64, Vel 80
            0x43.toByte(), 0x5A.toByte()  // Note 67, Vel 90
        )

        val events = parser.parsePacket(packet)

        assertThat(events).hasSize(3)
        assertThat((events[0] as MidiEvent.NoteOn).note).isEqualTo(60)
        assertThat((events[1] as MidiEvent.NoteOn).note).isEqualTo(64)
        assertThat((events[2] as MidiEvent.NoteOn).note).isEqualTo(67)
    }

    @Test
    fun `parsePacket preserves running status and pending data across multiple packet chunks`() = runTest {
        // Packet 1: Status 0x90 and Note 60
        val packet1 = byteArrayOf(0x90.toByte(), 0x3C.toByte())
        val events1 = parser.parsePacket(packet1)
        assertThat(events1).isEmpty()

        // Packet 2: Velocity 100 for Note 60, followed by Note 64 (running status)
        val packet2 = byteArrayOf(0x64.toByte(), 0x40.toByte())
        val events2 = parser.parsePacket(packet2)
        assertThat(events2).hasSize(1)
        val event1 = events2[0] as MidiEvent.NoteOn
        assertThat(event1.note).isEqualTo(60)
        assertThat(event1.velocity).isEqualTo(100)

        // Packet 3: Velocity 80 for Note 64
        val packet3 = byteArrayOf(0x50.toByte())
        val events3 = parser.parsePacket(packet3)
        assertThat(events3).hasSize(1)
        val event2 = events3[0] as MidiEvent.NoteOn
        assertThat(event2.note).isEqualTo(64)
        assertThat(event2.velocity).isEqualTo(80)
    }

    @Test
    fun `parsePacket transparently filters interleaved 0xFE Active Sensing bytes during chords`() = runTest {
        // Roland FP-10 sends 0xFE periodic active sensing
        val packetWithActiveSensing = byteArrayOf(
            0x90.toByte(),
            0x3C.toByte(),
            0xFE.toByte(), // Active sensing interleaved between data1 and data2
            0x64.toByte(),
            0xFE.toByte(),
            0x40.toByte(), 0x50.toByte(),
            0xFE.toByte()
        )

        val events = parser.parsePacket(packetWithActiveSensing)

        assertThat(events).hasSize(2)
        assertThat((events[0] as MidiEvent.NoteOn).note).isEqualTo(60)
        assertThat((events[0] as MidiEvent.NoteOn).velocity).isEqualTo(100)
        assertThat((events[1] as MidiEvent.NoteOn).note).isEqualTo(64)
        assertThat((events[1] as MidiEvent.NoteOn).velocity).isEqualTo(80)
    }

    @Test
    fun `parsePacket decodes timestamped MMA BLE Note On message`() = runTest {
        val packet = byteArrayOf(
            0x80.toByte(), // Header
            0x80.toByte(), // Timestamp
            0x90.toByte(), // Note On (Ch 1)
            0x3C.toByte(), // Note 60 (C4)
            0x64.toByte()  // Velocity 100
        )

        val events = parser.parsePacket(packet)

        assertThat(events).hasSize(1)
        val event = events[0] as MidiEvent.NoteOn
        assertThat(event.note).isEqualTo(60)
        assertThat(event.velocity).isEqualTo(100)
    }
}
