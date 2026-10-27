package uy.vpx.djpro

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiDiagnosticsTest {
    @Test fun displaysNamedController() {
        assertEquals(
            "MIDI detectado: Mixtrack Pro • entradas: 1 • salidas: 1",
            MidiDiagnostics.deviceLabel("Mixtrack Pro", 1, 1)
        )
    }

    @Test fun handlesMissingName() {
        assertEquals(
            "MIDI detectado: Dispositivo USB • entradas: 0 • salidas: 2",
            MidiDiagnostics.deviceLabel(null, 0, 2)
        )
    }

    @Test fun handlesBlankName() {
        assertEquals(
            "MIDI detectado: Dispositivo USB • entradas: 0 • salidas: 0",
            MidiDiagnostics.deviceLabel("  ", 0, 0)
        )
    }
}
