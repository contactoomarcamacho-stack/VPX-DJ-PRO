package uy.vpx.djpro

/**
 * Decodes a MIDI byte stream, including running status and messages split across
 * MidiReceiver.onSend calls. One instance is used per physical input port.
 * System messages and realtime clock bytes are deliberately ignored.
 */
class MidiStreamParser(private val onMessage: (Int, Int, Int) -> Unit) {
    private var command = 0
    private var first = -1
    private var sysex = false

    @Synchronized
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 0xff
            if (value >= 0xf8) continue // MIDI realtime can interrupt any message
            if (value == 0xf0) { sysex = true; command = 0; first = -1; continue }
            if (value == 0xf7) { sysex = false; command = 0; first = -1; continue }
            if (sysex) continue
            if (value >= 0x80) {
                first = -1
                command = if (value in 0x80..0xef) value else 0
                continue
            }
            if (command == 0) continue
            val kind = command and 0xf0
            if (kind == 0xc0 || kind == 0xd0) {
                command = 0 // no mapped one-data-byte messages
                continue
            }
            if (first < 0) first = value else {
                onMessage(command, first, value)
                first = -1 // preserve running status
            }
        }
    }
}
