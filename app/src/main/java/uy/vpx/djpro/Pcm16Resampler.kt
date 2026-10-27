package uy.vpx.djpro

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Stateful PCM16 resampler. Keeps the last input frame and interpolation
 * position across MediaCodec buffers to avoid gaps at buffer boundaries.
 *
 * The engine always feeds its AudioTrack with the OUTPUT device format,
 * even when the source file is 22.05/32/44.1/48/96 kHz or mono.
 *
 * Not a pitch-preserving time-stretcher: this is format conversion only.
 */
class Pcm16Resampler(
    private val targetRate: Int,
    private val targetChannels: Int,
    private var inputRate: Int,
    private var inputChannels: Int
) {
    init {
        require(targetRate > 0 && inputRate > 0)
        require(targetChannels in 1..2 && inputChannels in 1..2)
    }

    private var consumedFrames = 0L
    private var nextFrame = 0.0
    private var previousLeft = 0.0
    private var previousRight = 0.0
    private var hasPrevious = false

    fun setInputFormat(rate: Int, channels: Int) {
        require(rate > 0 && channels in 1..2)
        if (rate != inputRate || channels != inputChannels) {
            inputRate = rate
            inputChannels = channels
            reset()
        }
    }

    fun reset() {
        consumedFrames = 0
        nextFrame = 0.0
        previousLeft = 0.0
        previousRight = 0.0
        hasPrevious = false
    }

    fun convert(buffer: ByteBuffer): ByteArray {
        val source = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = inputChannels * 2
        val frames = source.remaining() / frameBytes
        if (frames == 0) return ByteArray(0)

        val start = consumedFrames
        val last = start + frames - 1L
        val estimatedFrames = ((frames + 2).toDouble() * targetRate / inputRate).toInt() + 8
        val result = ByteArrayOutputStream(estimatedFrames.coerceAtLeast(8) * targetChannels * 2)

        fun sample(index: Long, channel: Int): Double {
            if (index < start) {
                return if (channel == 0) previousLeft else previousRight
            }
            val frameIndex = (index - start).toInt()
            val byteOffset = frameIndex * frameBytes
            val left = source.getShort(byteOffset).toDouble()
            val right = if (inputChannels == 2) source.getShort(byteOffset + 2).toDouble() else left
            return if (channel == 0) left else right
        }

        val step = inputRate.toDouble() / targetRate.toDouble()
        val earliest = if (hasPrevious) start - 1 else start
        if (nextFrame < earliest.toDouble()) nextFrame = earliest.toDouble()

        // Require both interpolation samples, so the boundary interpolation
        // continues with the first frame of the NEXT decoder buffer.
        while (nextFrame < last.toDouble() - 1.0e-9) {
            val lower = floor(nextFrame).toLong()
            val fraction = nextFrame - lower.toDouble()
            val left = sample(lower, 0) * (1.0 - fraction) + sample(lower + 1, 0) * fraction
            val right = sample(lower, 1) * (1.0 - fraction) + sample(lower + 1, 1) * fraction

            fun put(value: Double) {
                val s = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                result.write(s and 0xff)
                result.write((s ushr 8) and 0xff)
            }

            if (targetChannels == 1) {
                put((left + right) / 2.0)
            } else {
                put(left)
                put(right)
            }
            nextFrame += step
        }

        previousLeft = sample(last, 0)
        previousRight = sample(last, 1)
        hasPrevious = true
        consumedFrames += frames.toLong()
        return result.toByteArray()
    }
}
