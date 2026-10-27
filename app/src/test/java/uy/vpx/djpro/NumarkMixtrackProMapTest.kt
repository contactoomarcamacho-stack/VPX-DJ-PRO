package uy.vpx.djpro

import org.junit.Assert.*
import org.junit.Test

class NumarkMixtrackProMapTest {
    private fun check(status: Int, number: Int, action: NumarkMixtrackProMap.Action, deck: Int) {
        val binding = NumarkMixtrackProMap.lookup(status, number)
        assertNotNull(binding)
        assertEquals(action, binding!!.action)
        assertEquals(deck, binding.deck)
    }
    @Test fun transportBothDecks() {
        check(0x90, 0x3b, NumarkMixtrackProMap.Action.PLAY, 0)
        check(0x90, 0x42, NumarkMixtrackProMap.Action.PLAY, 1)
        check(0x90, 0x33, NumarkMixtrackProMap.Action.CUE, 0)
        check(0x90, 0x3c, NumarkMixtrackProMap.Action.CUE, 1)
        check(0x90, 0x40, NumarkMixtrackProMap.Action.SYNC, 0)
        check(0x90, 0x47, NumarkMixtrackProMap.Action.SYNC, 1)
        check(0x90, 0x4a, NumarkMixtrackProMap.Action.STUTTER, 0)
        check(0x90, 0x4c, NumarkMixtrackProMap.Action.STUTTER, 1)
    }
    @Test fun mixerAndJog() {
        check(0xb0, 0x08, NumarkMixtrackProMap.Action.VOLUME, 0)
        check(0xb0, 0x09, NumarkMixtrackProMap.Action.VOLUME, 1)
        check(0xb0, 0x0a, NumarkMixtrackProMap.Action.CROSSFADER, -1)
        check(0xb0, 0x10, NumarkMixtrackProMap.Action.HIGH, 0)
        check(0xb0, 0x11, NumarkMixtrackProMap.Action.HIGH, 1)
        check(0xb0, 0x19, NumarkMixtrackProMap.Action.JOG, 0)
        check(0xb0, 0x18, NumarkMixtrackProMap.Action.JOG, 1)
    }
    @Test fun ignoreUnknownAndOtherChannel() {
        assertNull(NumarkMixtrackProMap.lookup(0x91, 0x3b))
        assertNull(NumarkMixtrackProMap.lookup(0xb0, 0x7f))
    }
}
