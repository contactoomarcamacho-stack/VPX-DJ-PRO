package uy.vpx.djpro

import org.junit.Assert.*
import org.junit.Test

class PcmBufferSizingTest {
    @Test fun negativeMinBufferStillProducesUsableCandidates() {
        val result = PcmBufferSizing.sizes(44100, 2, -1)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it >= 16384 })
        assertTrue(result.all { it <= 524288 })
        assertTrue(result.all { it % 4 == 0 })
    }
    @Test fun dynamicMinBufferIsHonored() {
        val result = PcmBufferSizing.sizes(48000, 2, 24576)
        assertTrue(result.first() >= 49152)
    }
    @Test fun safeMonoFallback() {
        val result = PcmBufferSizing.sizes(44100, 1, -1)
        assertTrue(result.all { it % 2 == 0 })
        assertTrue(result.first() > 0)
    }
    @Test fun stableAcrossCalls() {
        assertEquals(PcmBufferSizing.sizes(44100, 2, -1),
                     PcmBufferSizing.sizes(44100, 2, -1))
    }
}
