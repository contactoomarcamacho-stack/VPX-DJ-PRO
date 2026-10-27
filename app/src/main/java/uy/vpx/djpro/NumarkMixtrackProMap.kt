package uy.vpx.djpro

/**
 * Numark Mixtrack Pro (original, not Pro II / FX).
 * MIDI codes based on the published Mixxx mapping:
 * https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Numark%20Mixtrack%20Pro.midi.xml
 * 0x90 = note-on, 0xB0 = continuous controller, channel 0.
 */
object NumarkMixtrackProMap {
    enum class Action { PLAY, CUE, SYNC, STUTTER, VOLUME, PITCH, HIGH, MID, LOW, GAIN, BEND_UP, BEND_DOWN, JOG, JOG_TOUCH, CROSSFADER, MASTER }
    data class Binding(val action: Action, val deck: Int = -1)
    private val notes = mapOf(
        0x3b to Binding(Action.PLAY, 0), 0x42 to Binding(Action.PLAY, 1),
        0x33 to Binding(Action.CUE, 0), 0x3c to Binding(Action.CUE, 1),
        0x40 to Binding(Action.SYNC, 0), 0x47 to Binding(Action.SYNC, 1),
        0x4a to Binding(Action.STUTTER, 0), 0x4c to Binding(Action.STUTTER, 1),
        0x44 to Binding(Action.BEND_UP, 0), 0x46 to Binding(Action.BEND_UP, 1),
        0x43 to Binding(Action.BEND_DOWN, 0), 0x45 to Binding(Action.BEND_DOWN, 1),
        0x4e to Binding(Action.JOG_TOUCH, 0), 0x4d to Binding(Action.JOG_TOUCH, 1)
    )
    private val controls = mapOf(
        0x08 to Binding(Action.VOLUME, 0), 0x09 to Binding(Action.VOLUME, 1),
        0x0d to Binding(Action.PITCH, 0), 0x0e to Binding(Action.PITCH, 1),
        0x10 to Binding(Action.HIGH, 0), 0x11 to Binding(Action.HIGH, 1),
        0x12 to Binding(Action.MID, 0), 0x13 to Binding(Action.MID, 1),
        0x14 to Binding(Action.LOW, 0), 0x15 to Binding(Action.LOW, 1),
        0x1d to Binding(Action.GAIN, 0), 0x20 to Binding(Action.GAIN, 1),
        0x19 to Binding(Action.JOG, 0), 0x18 to Binding(Action.JOG, 1),
        0x0a to Binding(Action.CROSSFADER), 0x17 to Binding(Action.MASTER)
    )
    fun lookup(status: Int, number: Int): Binding? {
        if ((status and 0x0f) != 0) return null
        return when (status and 0xf0) {
            0x90, 0x80 -> notes[number]
            0xb0 -> controls[number]
            else -> null
        }
    }
}
