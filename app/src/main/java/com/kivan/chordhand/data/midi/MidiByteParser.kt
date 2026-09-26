package com.kivan.chordhand.data.midi

import com.kivan.chordhand.domain.model.MidiEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Low-latency, zero-allocation parser for MIDI byte packets.
 * Accurately parses standard raw MIDI 1.0 byte streams (from Android MidiManager)
 * and MMA BLE-MIDI timestamped packets.
 *
 * Supports Note On (0x90), Note Off (0x80), and Control Change (0xB0 / CC 64 Sustain Pedal).
 */
class MidiByteParser(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var runningStatus: Int = 0
    private var pendingData1: Int = -1

    suspend fun parsePacket(
        packet: ByteArray,
        offset: Int = 0,
        count: Int = packet.size
    ): List<MidiEvent> = withContext(dispatcher) {
        parsePacketSync(packet, offset, count)
    }

    /**
     * Parses a byte array synchronously.
     * Guaranteed to be thread-safe when called sequentially on a single thread/worker.
     */
    @Synchronized
    fun parsePacketSync(
        packet: ByteArray,
        offset: Int = 0,
        count: Int = packet.size
    ): List<MidiEvent> {
        if (count <= 0 || offset < 0 || offset + count > packet.size) return emptyList()

        val end = offset + count
        val events = ArrayList<MidiEvent>(4)
        var index = offset

        // Detect if this is an MMA BLE-MIDI packet format:
        // Starts with Header byte (0x80..0xBF) followed by low Timestamp byte (0x80..0xFF)
        val firstByte = packet[index].toInt() and 0xFF
        val secondByte = if (count >= 2) packet[index + 1].toInt() and 0xFF else 0
        val isBleMmaPacket = count >= 3 &&
                (firstByte in 0x80..0xBF) &&
                (secondByte in 0x80..0xFF)

        if (isBleMmaPacket) {
            parseBleMmaStream(packet, index, end, events)
        } else {
            parseRawMidiStream(packet, index, end, events)
        }

        return events
    }

    private fun parseRawMidiStream(
        packet: ByteArray,
        startIndex: Int,
        endIndex: Int,
        events: MutableList<MidiEvent>
    ) {
        var index = startIndex

        while (index < endIndex) {
            val b = packet[index].toInt() and 0xFF
            index++

            // Filter real-time bytes (0xF8..0xFF, e.g. 0xFE Active Sensing, 0xF8 Clock)
            // Real-time messages can be interleaved anywhere and do not affect status or pending data.
            if (b >= 0xF8) {
                continue
            }

            // Status byte (0x80..0xEF)
            if ((b and 0x80) != 0) {
                if (b in 0x80..0xEF) {
                    runningStatus = b
                    pendingData1 = -1
                } else if (b in 0xF0..0xF7) {
                    // System Common clears running status
                    runningStatus = 0
                    pendingData1 = -1
                }
                continue
            }

            // Data byte (0x00..0x7F)
            if (runningStatus == 0) continue

            val statusType = runningStatus and 0xF0
            when (statusType) {
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> {
                    // 2-data-byte messages
                    if (pendingData1 == -1) {
                        pendingData1 = b
                    } else {
                        val data1 = pendingData1
                        val data2 = b
                        pendingData1 = -1
                        processMidiMessage(runningStatus, data1, data2, events)
                    }
                }
                0xC0, 0xD0 -> {
                    // 1-data-byte messages
                    processMidiMessage(runningStatus, b, 0, events)
                }
            }
        }
    }

    private fun parseBleMmaStream(
        packet: ByteArray,
        startIndex: Int,
        endIndex: Int,
        events: MutableList<MidiEvent>
    ) {
        var index = startIndex
        // Skip BLE Header byte (0x80..0xBF)
        index++

        while (index < endIndex) {
            val b = packet[index].toInt() and 0xFF
            index++

            // Filter real-time bytes
            if (b >= 0xF8) continue

            if ((b and 0x80) != 0) {
                // Could be a Timestamp byte (0x80..0xFF) or a Status byte (0x80..0xEF)
                if (b in 0x80..0xEF) {
                    if (index < endIndex && (packet[index].toInt() and 0x80) == 0) {
                        runningStatus = b
                        pendingData1 = -1
                    } else {
                        // Timestamp byte; retain runningStatus
                        pendingData1 = -1
                    }
                } else if (b in 0xF0..0xF7) {
                    runningStatus = 0
                    pendingData1 = -1
                }
                continue
            }

            // Data byte (< 0x80)
            if (runningStatus == 0) continue

            val statusType = runningStatus and 0xF0
            when (statusType) {
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> {
                    if (pendingData1 == -1) {
                        if (index < endIndex && (packet[index].toInt() and 0x80) == 0) {
                            val data2 = packet[index].toInt() and 0xFF
                            index++
                            processMidiMessage(runningStatus, b, data2, events)
                        } else {
                            pendingData1 = b
                        }
                    } else {
                        val data1 = pendingData1
                        pendingData1 = -1
                        processMidiMessage(runningStatus, data1, b, events)
                    }
                }
                0xC0, 0xD0 -> {
                    processMidiMessage(runningStatus, b, 0, events)
                }
            }
        }
    }

    private fun processMidiMessage(
        status: Int,
        data1: Int,
        data2: Int,
        events: MutableList<MidiEvent>
    ) {
        val type = status and 0xF0
        val channel = status and 0x0F

        when (type) {
            0x90 -> {
                if (data2 == 0) {
                    events.add(MidiEvent.NoteOff(note = data1, channel = channel))
                } else {
                    events.add(MidiEvent.NoteOn(note = data1, velocity = data2, channel = channel))
                }
            }
            0x80 -> {
                events.add(MidiEvent.NoteOff(note = data1, channel = channel))
            }
            0xB0 -> {
                events.add(MidiEvent.ControlChange(controller = data1, value = data2, channel = channel))
            }
        }
    }

    @Synchronized
    fun reset() {
        runningStatus = 0
        pendingData1 = -1
    }
}
