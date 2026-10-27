package uy.vpx.djpro

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Pcm16ResamplerTest {
    private fun pcm(vararg values: Short): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it) }
            flip()
        }

    @Test fun stereo44100PreservesFrames() {
        val c = Pcm16Resampler(44100, 2, 44100, 2)
        val out = c.convert(pcm(100, (-100).toShort(), 200, (-200).toShort(), 300, (-300).toShort()))
        assertEquals(8, out.size)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100.toShort(), b.short)
        assertEquals((-100).toShort(), b.short)
        assertEquals(200.toShort(), b.short)
        assertEquals((-200).toShort(), b.short)
    }

    @Test fun monoCreatesStereo() {
        val c = Pcm16Resampler(44100, 2, 44100, 1)
        val out = ByteBuffer.wrap(c.convert(pcm(200, 500, 800))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, out.remaining())
        assertEquals(200.toShort(), out.short)
        assertEquals(200.toShort(), out.short)
        assertEquals(500.toShort(), out.short)
        assertEquals(500.toShort(), out.short)
    }

    @Test fun resamplesAcrossBuffers() {
        val c = Pcm16Resampler(44100, 2, 48000, 2)
        val first = c.convert(pcm(0, 0, 1000, 1000, 2000, 2000))
        val second = c.convert(pcm(3000, 3000, 4000, 4000, 5000, 5000))
        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
        assertEquals(0, first.size % 4)
        assertEquals(0, second.size % 4)
    }

    @Test fun resetsAfterSeeking() {
        val c = Pcm16Resampler(44100, 2, 48000, 1)
        c.convert(pcm(1, 2, 3, 4))
        c.reset()
        val out = c.convert(pcm(7, 8, 9, 10))
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7.toShort(), b.short)
        assertEquals(7.toShort(), b.short)
    }

    @Test fun stereoToMonoFallback() {
        val c = Pcm16Resampler(48000, 1, 48000, 2)
        val out = ByteBuffer.wrap(c.convert(pcm(100, 300, 500, 700, 900, 1100)))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4, out.remaining())
        assertEquals(200.toShort(), out.short)
        assertEquals(600.toShort(), out.short)
    }
}
