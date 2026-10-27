package uy.vpx.djpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MidiStreamParserTest {
    @Test fun fragmentedNote() {
        val result = mutableListOf<List<Int>>()
        val parser = MidiStreamParser { status, number, value -> result.add(listOf(status, number, value)) }
        parser.feed(byteArrayOf(0x90.toByte(), 0x3b))
        parser.feed(byteArrayOf(0x7f))
        assertEquals(listOf(listOf(0x90, 0x3b, 0x7f)), result)
    }
    @Test fun runningStatusAndRealtime() {
        val result = mutableListOf<List<Int>>()
        val parser = MidiStreamParser { status, number, value -> result.add(listOf(status, number, value)) }
        parser.feed(byteArrayOf(0xb0.toByte(), 0x08, 0x7f, 0xf8.toByte(), 0x09, 0x40))
        assertEquals(listOf(listOf(0xb0, 0x08, 0x7f), listOf(0xb0, 0x09, 0x40)), result)
    }
    @Test fun noteOffAndCC() {
        val result = mutableListOf<List<Int>>()
        val parser = MidiStreamParser { status, number, value -> result.add(listOf(status, number, value)) }
        parser.feed(byteArrayOf(0x80.toByte(), 0x33, 0x00, 0xb0.toByte(), 0x0a, 0x64))
        assertEquals(listOf(listOf(0x80, 0x33, 0x00), listOf(0xb0, 0x0a, 0x64)), result)
    }
    @Test fun silenceAndOutOfRangeMessages() {
        val result = mutableListOf<List<Int>>()
        val parser = MidiStreamParser { status, number, value -> result.add(listOf(status, number, value)) }
        parser.feed(byteArrayOf(0xf0.toByte(), 0x02, 0x03, 0xf7.toByte(), 0xc0.toByte(), 0x04))
        assertEquals(0, result.size)
    }
}
