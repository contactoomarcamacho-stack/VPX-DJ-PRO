package uy.vpx.djpro

/** Pure helper for safe, testable MIDI device labels. */
object MidiDiagnostics {
    fun deviceLabel(name: String?, inputPorts: Int, outputPorts: Int): String {
        val safeName = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Dispositivo USB"
        return "MIDI detectado: $safeName • entradas: $inputPorts • salidas: $outputPorts"
    }
}
