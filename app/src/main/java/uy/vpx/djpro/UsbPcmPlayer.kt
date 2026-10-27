package uy.vpx.djpro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Process
import java.nio.ByteBuffer

/**
 * Real PCM streaming engine. MediaCodec decodes the audio and AudioTrack
 * sends decoded PCM to the USB device selected by UsbAudioRouter.
 * Both decks are mixed by Android's audio mixer on the same USB sink.
 */
class UsbPcmPlayer(context: Context, uri: Uri) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val track: AudioTrack
    private val sampleRate: Int
    private val channels: Int
    private val worker: Thread

    @Volatile private var released = false
    @Volatile private var playing = false
    @Volatile private var pendingSeekMs = -1
    @Volatile private var positionMs = 0
    @Volatile var onCompletion: (() -> Unit)? = null

    val duration: Int
    val currentPosition: Int get() = positionMs.coerceIn(0, duration.coerceAtLeast(positionMs))
    val isPlaying: Boolean get() = playing && !released
    val audioSessionId: Int get() = track.audioSessionId
    val routedDevice: AudioDeviceInfo? get() =
        try { track.routedDevice } catch (_: Exception) { null }

    init {
        extractor.setDataSource(context, uri, null)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No hay pista de audio compatible")
        val format = extractor.getTrackFormat(index)
        extractor.selectTrack(index)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Formato de audio desconocido")
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(channels == 1 || channels == 2) { "Se requiere audio mono o estéreo" }
        require(sampleRate in 8000..192000) { "Frecuencia de muestreo no compatible" }
        duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } else 0

        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBytes > 0) { "AudioTrack rechazó el formato PCM" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes((minBytes * 2).coerceAtLeast(sampleRate * channels * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack no pudo inicializarse")
        }
        codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            track.release()
            extractor.release()
            throw e
        }
        worker = Thread({ runDecoder() }, "VPX-PCM-Decoder").apply {
            isDaemon = true
            start()
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo): Boolean =
        try { !released && track.setPreferredDevice(device) } catch (_: Exception) { false }

    fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler) {
        try { track.addOnRoutingChangedListener(listener, handler) } catch (_: Exception) {}
    }

    fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) {
        try { track.removeOnRoutingChangedListener(listener) } catch (_: Exception) {}
    }

    fun setVolume(left: Float, right: Float) {
        try { track.setStereoVolume(left.coerceIn(0f, 1f), right.coerceIn(0f, 1f)) }
        catch (_: Exception) {}
    }

    fun setSpeed(speed: Float) {
        try { track.playbackRate = (sampleRate * speed.coerceIn(0.5f, 1.5f)).toInt() }
        catch (_: Exception) {}
    }

    fun start() {
        if (released) return
        playing = true
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        } catch (_: Exception) { playing = false }
    }

    fun pause() {
        playing = false
        try { track.pause() } catch (_: Exception) {}
    }

    fun seekTo(ms: Int) {
        val destination = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        pendingSeekMs = destination
        positionMs = destination
    }

    fun release() {
        if (released) return
        playing = false
        released = true
        try { track.pause() } catch (_: Exception) {}
        try { track.flush() } catch (_: Exception) {}
        worker.interrupt()
    }

    private fun runDecoder() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        try {
            while (!released) {
                val seek = pendingSeekMs
                if (seek >= 0) {
                    pendingSeekMs = -1
                    track.pause()
                    track.flush()
                    extractor.seekTo(seek * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    codec.flush()
                    inputEnded = false
                    outputEnded = false
                    positionMs = seek
                    if (playing) track.play()
                }
                if (outputEnded) {
                    if (playing) {
                        playing = false
                        try { track.pause() } catch (_: Exception) {}
                        onCompletion?.invoke()
                    }
                    Thread.sleep(15)
                    continue
                }
                if (!playing) {
                    Thread.sleep(15)
                    continue
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(2000L)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)
                        if (buffer != null) {
                            buffer.clear()
                            val len = extractor.readSampleData(buffer, 0)
                            if (len < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, len, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5000L)
                if (outIndex >= 0) {
                    val samples: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (samples != null && info.size > 0) {
                        samples.position(info.offset)
                        samples.limit(info.offset + info.size)
                        while (!released && playing && pendingSeekMs < 0 && samples.hasRemaining()) {
                            val written = track.write(samples, samples.remaining(), AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) break
                        }
                        positionMs = (info.presentationTimeUs / 1000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = codec.outputFormat
                    if (decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sampleRate ||
                        decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels) {
                        throw IllegalStateException("El decodificador cambió el formato de audio")
                    }
                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                        throw IllegalStateException("El decodificador no ofrece PCM16")
                    }
                }
            }
        } catch (_: Exception) {
            playing = false
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
