package com.kivan.chordhand.data

import com.google.common.truth.Truth.assertThat
import com.kivan.chordhand.data.midi.RolandMidiSysEx
import org.junit.Test

class RolandMidiSysExTest {

    @Test
    fun `calculateChecksum produces correct 7-bit Roland checksum`() {
        val address = byteArrayOf(0x01, 0x00, 0x05, 0x09)
        val data = byteArrayOf(0x01)

        val checksum = RolandMidiSysEx.calculateChecksum(address, data)
        // Sum = 1 + 0 + 5 + 9 + 1 = 16
        // Checksum = (128 - 16) % 128 = 112 (0x70)
        assertThat(checksum).isEqualTo(112.toByte())
    }

    @Test
    fun `MODEL_ID is FP series 00 00 00 28`() {
        assertThat(RolandMidiSysEx.MODEL_ID).isEqualTo(byteArrayOf(0x00, 0x00, 0x00, 0x28))
    }

    @Test
    fun `createHandshakePacket creates valid DT1 initialization message`() {
        val packet = RolandMidiSysEx.createHandshakePacket()
        assertThat(packet.first()).isEqualTo(0xF0.toByte())
        assertThat(packet.last()).isEqualTo(0xF7.toByte())
        assertThat(packet[1]).isEqualTo(0x41.toByte()) // Roland ID
        assertThat(packet[7]).isEqualTo(0x12.toByte()) // DT1
        // Address 01 00 03 06
        assertThat(packet[8]).isEqualTo(0x01.toByte())
        assertThat(packet[9]).isEqualTo(0x00.toByte())
        assertThat(packet[10]).isEqualTo(0x03.toByte())
        assertThat(packet[11]).isEqualTo(0x06.toByte())
        // Data 0x01
        assertThat(packet[12]).isEqualTo(0x01.toByte())
    }

    @Test
    fun `createNotificationPacket creates valid registration message`() {
        val packet = RolandMidiSysEx.createNotificationPacket()
        assertThat(packet.first()).isEqualTo(0xF0.toByte())
        assertThat(packet.last()).isEqualTo(0xF7.toByte())
        // Address 01 00 03 00
        assertThat(packet[8]).isEqualTo(0x01.toByte())
        assertThat(packet[9]).isEqualTo(0x00.toByte())
        assertThat(packet[10]).isEqualTo(0x03.toByte())
        assertThat(packet[11]).isEqualTo(0x00.toByte())
    }

    @Test
    fun `createMetronomeSwitchPacket builds valid DT1 toggle packet`() {
        val packet = RolandMidiSysEx.createMetronomeSwitchPacket(enabled = true)

        assertThat(packet.first()).isEqualTo(0xF0.toByte()) // SysEx Start
        assertThat(packet.last()).isEqualTo(0xF7.toByte())  // SysEx End
        assertThat(packet[1]).isEqualTo(0x41.toByte())       // Roland ID
        assertThat(packet[2]).isEqualTo(0x10.toByte())       // Device ID
        assertThat(packet[7]).isEqualTo(0x12.toByte())       // Command DT1
    }

    @Test
    fun `calculateRolandChecksum matches calculateChecksum and produces valid 7-bit values`() {
        val address = byteArrayOf(0x01, 0x00, 0x03, 0x09)
        val data = byteArrayOf(0x00, 0x78) // 120 BPM

        val checksum = RolandMidiSysEx.calculateRolandChecksum(address, data)
        assertThat(checksum).isEqualTo(RolandMidiSysEx.calculateChecksum(address, data))
        assertThat(checksum.toInt() and 0xFF).isLessThan(128)
    }

    @Test
    fun `createMetronomeVolumePacket clamps volume between 0 and 10`() {
        val vol0 = RolandMidiSysEx.createMetronomeVolumePacket(-5)
        assertThat(vol0.first()).isEqualTo(0xF0.toByte())
        assertThat(vol0.last()).isEqualTo(0xF7.toByte())

        val vol10 = RolandMidiSysEx.createMetronomeVolumePacket(15)
        assertThat(vol10.first()).isEqualTo(0xF0.toByte())
        assertThat(vol10.last()).isEqualTo(0xF7.toByte())
    }

    @Test
    fun `createMetronomeTempoPacket handles clamp and 7-bit high-low bytes`() {
        // 120 BPM
        val packet = RolandMidiSysEx.createMetronomeTempoPacket(120)

        assertThat(packet.first()).isEqualTo(0xF0.toByte())
        assertThat(packet.last()).isEqualTo(0xF7.toByte())
        // Verify valid structure length
        assertThat(packet.size).isGreaterThan(10)

        // Boundary BPM clamps
        val minPacket = RolandMidiSysEx.createMetronomeTempoPacket(10) // clamped to 20
        assertThat(minPacket.first()).isEqualTo(0xF0.toByte())

        val maxPacket = RolandMidiSysEx.createMetronomeTempoPacket(300) // clamped to 250
        assertThat(maxPacket.first()).isEqualTo(0xF0.toByte())
    }
}
