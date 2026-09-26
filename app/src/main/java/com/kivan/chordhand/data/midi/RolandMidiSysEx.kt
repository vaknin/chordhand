package com.kivan.chordhand.data.midi

/**
 * Utility for constructing Roland Data Set 1 (DT1) System Exclusive (SysEx) messages
 * to control the Roland FP-10 hardware metronome, tempo, and settings over BLE MIDI.
 */
object RolandMidiSysEx {

    const val SYSEX_START: Byte = 0xF0.toByte()
    const val SYSEX_END: Byte = 0xF7.toByte()
    const val ROLAND_ID: Byte = 0x41.toByte()
    const val DEVICE_ID: Byte = 0x10.toByte()
    const val COMMAND_DT1: Byte = 0x12.toByte()

    // Roland Digital Piano Model ID (FP-10 / FP-30 / Piano Partner 2 protocol)
    val MODEL_ID = byteArrayOf(0x00, 0x00, 0x00, 0x28)

    // Roland Memory Addresses for Communication Handshake and Metronome
    val ADDR_HANDSHAKE_COMM = byteArrayOf(0x01, 0x00, 0x03, 0x06)
    val ADDR_NOTIFICATIONS = byteArrayOf(0x01, 0x00, 0x03, 0x00)
    val ADDR_METRONOME_SWITCH = byteArrayOf(0x01, 0x00, 0x05, 0x09)
    val ADDR_METRONOME_TEMPO = byteArrayOf(0x01, 0x00, 0x03, 0x09)
    val ADDR_METRONOME_VOLUME = byteArrayOf(0x01, 0x00, 0x05, 0x0A)

    /**
     * Calculates the standard Roland 7-bit checksum for address and data payloads:
     * sum = (sum of all address bytes + data bytes) % 128
     * checksum = (128 - sum) % 128
     */
    fun calculateChecksum(address: ByteArray, data: ByteArray): Byte {
        var sum = 0
        for (b in address) {
            sum += (b.toInt() and 0xFF)
        }
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        val remainder = sum % 128
        val checksum = (128 - remainder) % 128
        return checksum.toByte()
    }

    /**
     * Alias for [calculateChecksum] adhering to Roland DT1 specification naming.
     */
    fun calculateRolandChecksum(address: ByteArray, data: ByteArray): Byte =
        calculateChecksum(address, data)

    /**
     * Builds a complete DT1 SysEx message for a given target address and data payload.
     */
    fun buildDt1Message(address: ByteArray, data: ByteArray): ByteArray {
        val checksum = calculateChecksum(address, data)
        val length = 1 + 1 + 1 + MODEL_ID.size + 1 + address.size + data.size + 1 + 1
        val result = ByteArray(length)

        var idx = 0
        result[idx++] = SYSEX_START
        result[idx++] = ROLAND_ID
        result[idx++] = DEVICE_ID

        for (b in MODEL_ID) {
            result[idx++] = b
        }

        result[idx++] = COMMAND_DT1

        for (b in address) {
            result[idx++] = b
        }

        for (b in data) {
            result[idx++] = b
        }

        result[idx++] = checksum
        result[idx++] = SYSEX_END

        return result
    }

    /**
     * Constructs a SysEx packet to initialize Roland DT1 communication reception.
     * Must be sent upon connection to unlock parameter write operations on the FP-10.
     */
    fun createHandshakePacket(): ByteArray {
        val data = byteArrayOf(0x01)
        return buildDt1Message(ADDR_HANDSHAKE_COMM, data)
    }

    /**
     * Constructs a SysEx packet to enable notifications from the Roland FP-10.
     */
    fun createNotificationPacket(): ByteArray {
        val data = byteArrayOf(0x00, 0x01)
        return buildDt1Message(ADDR_NOTIFICATIONS, data)
    }

    /**
     * Constructs a SysEx packet to toggle the Roland FP-10 metronome.
     * The FP-10 handles metronome on/off as a toggle trigger on ADDR_METRONOME_SWITCH with value 0x00.
     */
    fun createMetronomeTogglePacket(): ByteArray {
        val data = byteArrayOf(0x00)
        return buildDt1Message(ADDR_METRONOME_SWITCH, data)
    }

    /**
     * Constructs a SysEx packet to turn the Roland FP-10 metronome ON or OFF.
     */
    fun createMetronomeSwitchPacket(enabled: Boolean): ByteArray {
        return createMetronomeTogglePacket()
    }

    /**
     * Constructs a SysEx packet to set the Roland FP-10 metronome tempo (BPM).
     * Valid BPM range: 20..250.
     */
    fun createMetronomeTempoPacket(bpm: Int): ByteArray {
        val clampedBpm = bpm.coerceIn(20, 250)
        // High 7 bits and Low 7 bits representation
        val highByte = ((clampedBpm shr 7) and 0x7F).toByte()
        val lowByte = (clampedBpm and 0x7F).toByte()
        val data = byteArrayOf(highByte, lowByte)
        return buildDt1Message(ADDR_METRONOME_TEMPO, data)
    }

    /**
     * Constructs a SysEx packet to set the Roland FP-10 metronome volume.
     * Valid Volume range: 0..10.
     */
    fun createMetronomeVolumePacket(volume: Int): ByteArray {
        val clampedVol = volume.coerceIn(0, 10).toByte()
        val data = byteArrayOf(clampedVol)
        return buildDt1Message(ADDR_METRONOME_VOLUME, data)
    }
}
