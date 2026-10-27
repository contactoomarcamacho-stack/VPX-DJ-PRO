package uy.vpx.djpro

/**
 * PCM16 streaming-buffer sizing isolated from Android APIs so it can be
 * unit-tested even when AudioTrack.getMinBufferSize returns ERROR (-1).
 */
object PcmBufferSizing {
    fun sizes(sampleRate: Int, channels: Int, minimumBytes: Int): List<Int> {
        require(sampleRate in 8000..192000)
        require(channels == 1 || channels == 2)
        val bytesPerFrame = channels * 2L
        val fixed = (sampleRate.toLong() * bytesPerFrame / 5L)
            .coerceAtLeast(16384L)
            .coerceAtMost(262144L)
            .toInt()
        val doubledFixed = (fixed.toLong() * 2L).coerceAtMost(524288L).toInt()
        return if (minimumBytes > 0) {
            listOf(
                maxOf(minimumBytes.toLong() * 2L, fixed.toLong())
                    .coerceAtMost(524288L).toInt(),
                fixed,
                doubledFixed
            ).distinct()
        } else {
            // -1 is a failed *query*, not grounds to disable audio playback.
            listOf(fixed, doubledFixed).distinct()
        }
    }
}
