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
 * Two-deck PCM playback engine. Reads media through MediaExtractor,
 * decodes with MediaCodec, converts to the AudioTrack's negotiated PCM16
 * sample rate/channel count, then writes audio off the UI thread.
 *
 * First-choice output: 44.1 kHz, stereo, signed PCM16.
 * Safe fallbacks: 48 kHz stereo, then 44.1/48 kHz mono, followed by
 * 32 kHz stereo. Decoder sample rate never goes directly to AudioTrack
 * without conversion.
 *
 * Android audio routing is handled separately by UsbAudioRouter.
 */
class UsbPcmPlayer(context: Context, uri: Uri) {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val track: AudioTrack
    private val outputSampleRate: Int
    private val outputChannels: Int
    private val sourceSampleRate: Int
    private val sourceChannels: Int
    private val decoderThread: Thread

    @Volatile private var released = false
    @Volatile private var playing = false
    @Volatile private var pendingSeekMs = -1
    @Volatile private var positionMs = 0

    @Volatile var onCompletion: (() -> Unit)? = null
    @Volatile var onError: ((String) -> Unit)? = null

    val duration: Int
    val currentPosition: Int get() =
        if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
    val isPlaying: Boolean get() = playing && !released
    val audioSessionId: Int get() = track.audioSessionId
    val outputDescription: String get() =
        "PCM16 " + outputSampleRate + " Hz " +
            (if (outputChannels == 2) "estéreo" else "mono seguro")
    val routedDevice: AudioDeviceInfo? get() =
        try { track.routedDevice } catch (_: Exception) { null }

    private data class OutputMode(val sampleRate: Int, val channels: Int)
    private data class OpenedTrack(val track: AudioTrack, val mode: OutputMode)

    init {
        val format: MediaFormat
        val mime: String
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("El archivo no contiene audio reproducible")
            extractor.selectTrack(index)
            format = extractor.getTrackFormat(index)
            mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Formato de audio desconocido")
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 8000..192000 && channels in 1..2) {
                "El archivo debe tener audio mono o estéreo compatible"
            }
            sourceSampleRate = rate
            sourceChannels = channels
            duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1000L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            } else 0
        } catch (e: Exception) {
            extractor.release()
            throw e
        }

        try {
            val output = openCompatibleAudioTrack()
            track = output.track
            outputSampleRate = output.mode.sampleRate
            outputChannels = output.mode.channels
        } catch (e: Exception) {
            extractor.release()
            throw e
        }

        try {
            codec = MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            track.release()
            extractor.release()
            throw e
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            try { codec.release() } catch (_: Exception) {}
            track.release()
            extractor.release()
            throw e
        }

        decoderThread = Thread({ decode() }, "VPX-PCM-Decoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun openCompatibleAudioTrack(): OpenedTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val modes = listOf(
            OutputMode(44100, 2),
            OutputMode(48000, 2),
            OutputMode(44100, 1),
            OutputMode(48000, 1),
            OutputMode(32000, 2)
        )
        val errors = ArrayList<String>()
        for (mode in modes) {
            val channelMask = if (mode.channels == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else AudioFormat.CHANNEL_OUT_MONO

            val minBytes = try {
                AudioTrack.getMinBufferSize(
                    mode.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                errors.add(mode.sampleRate.toString() + "/" + mode.channels + ": " + e.javaClass.simpleName)
                continue
            }
            if (minBytes <= 0) {
                errors.add(mode.sampleRate.toString() + "/" + mode.channels + ": buffer " + minBytes)
                continue
            }

            val bytes = (minBytes.toLong() * 2L)
                .coerceAtLeast(16384L)
                .coerceAtMost(512 * 1024L).toInt()
            var candidate: AudioTrack? = null
            try {
                candidate = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(mode.sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bytes)
                    .build()

                if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                    return OpenedTrack(candidate, mode)
                }
                errors.add(mode.sampleRate.toString() + "/" + mode.channels + ": no inicializado")
            } catch (e: Exception) {
                errors.add(mode.sampleRate.toString() + "/" + mode.channels + ": " + e.javaClass.simpleName)
            }
            try { candidate?.release() } catch (_: Exception) {}
        }
        throw IllegalStateException(
            "Android rechazó todos los modos PCM16 seguros. " + errors.joinToString("; ")
        )
    }

    fun setPreferredDevice(device: AudioDeviceInfo): Boolean =
        try { !released && track.setPreferredDevice(device) } catch (_: Exception) { false }

    fun addOnRoutingChangedListener(
        listener: AudioRouting.OnRoutingChangedListener,
        handler: Handler
    ) {
        try { track.addOnRoutingChangedListener(listener, handler) } catch (_: Exception) {}
    }

    fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) {
        try { track.removeOnRoutingChangedListener(listener) } catch (_: Exception) {}
    }

    fun setVolume(left: Float, right: Float) {
        try {
            track.setStereoVolume(
                left.coerceIn(0f, 1f),
                right.coerceIn(0f, 1f)
            )
        } catch (_: Exception) {}
    }

    fun setSpeed(speed: Float) {
        // Playback-rate change is distinct from PCM format conversion.
        try {
            track.playbackRate = (outputSampleRate * speed.coerceIn(0.5f, 1.5f)).toInt()
        } catch (_: Exception) {}
    }

    fun start() {
        if (released) return
        playing = true
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        } catch (e: Exception) {
            playing = false
            onError?.invoke("No se pudo iniciar el AudioTrack: " + e.message)
        }
    }

    fun pause() {
        playing = false
        try { track.pause() } catch (_: Exception) {}
    }

    fun seekTo(ms: Int) {
        pendingSeekMs = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        positionMs = pendingSeekMs
    }

    fun release() {
        if (released) return
        playing = false
        released = true
        try { track.pause() } catch (_: Exception) {}
        try { track.flush() } catch (_: Exception) {}
        decoderThread.interrupt()
        // Codec/track/extractor are owned and disposed by the worker thread.
    }

    private fun decode() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
        val info = MediaCodec.BufferInfo()
        var decoderRate = sourceSampleRate
        var decoderChannels = sourceChannels
        val resampler = Pcm16Resampler(
            outputSampleRate, outputChannels, decoderRate, decoderChannels
        )

        var inputEnded = false
        var outputEnded = false
        try {
            while (!released) {
                val requestedSeek = pendingSeekMs
                if (requestedSeek >= 0) {
                    pendingSeekMs = -1
                    try {
                        track.pause()
                        track.flush()
                        extractor.seekTo(requestedSeek.toLong() * 1000L,
                            MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        resampler.reset()
                        inputEnded = false
                        outputEnded = false
                        positionMs = requestedSeek
                        if (playing) track.play()
                    } catch (e: Exception) {
                        throw IllegalStateException("Error al buscar la canción: " + e.message, e)
                    }
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
                        val input = codec.getInputBuffer(inputIndex)
                        if (input != null) {
                            input.clear()
                            val length = extractor.readSampleData(input, 0)
                            if (length < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex, 0, length,
                                    extractor.sampleTime.coerceAtLeast(0L), 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val index = codec.dequeueOutputBuffer(info, 5000L)
                if (index >= 0) {
                    val output: ByteBuffer? = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm16 = resampler.convert(output)
                        var cursor = 0
                        while (!released && playing && pendingSeekMs < 0 && cursor < pcm16.size) {
                            val length = (pcm16.size - cursor).coerceAtMost(8192)
                            val written = track.write(
                                pcm16, cursor, length, AudioTrack.WRITE_BLOCKING
                            )
                            if (written < 0) {
                                throw IllegalStateException("Error AudioTrack.write: " + written)
                            }
                            if (written == 0) break
                            cursor += written
                        }
                        positionMs = (info.presentationTimeUs / 1000L)
                            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEnded = true
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = codec.outputFormat
                    decoderRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    decoderChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) !=
                        AudioFormat.ENCODING_PCM_16BIT
                    ) {
                        throw IllegalStateException("El decodificador no entregó PCM16")
                    }
                    if (decoderRate !in 8000..192000 || decoderChannels !in 1..2) {
                        throw IllegalStateException("El decodificador entregó audio no compatible")
                    }
                    resampler.setInputFormat(decoderRate, decoderChannels)
                }
            }
        } catch (e: InterruptedException) {
            if (!released) onError?.invoke("Decodificación interrumpida")
        } catch (e: Exception) {
            playing = false
            if (!released) onError?.invoke("Motor PCM: " + e.message)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
