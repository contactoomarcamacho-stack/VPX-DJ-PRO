package uy.vpx.djpro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Resilient DJ deck audio output.
 *
 * First tries an independently decoded PCM16 stream written to AudioTrack.
 * If Android refuses AudioTrack even with fixed buffer sizes, uses Android's
 * MediaPlayer engine instead of failing to load the song.
 *
 * An AudioTrack decode/write failure during playback also switches to
 * MediaPlayer from the same position, preserving volume, rate, and playback.
 */
class UsbPcmPlayer(
    private val context: Context,
    private val uri: Uri
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pcm: PcmTrackEngine? = null
    private var media: MediaPlayer? = null
    private val listeners =
        linkedMapOf<AudioRouting.OnRoutingChangedListener, Handler>()

    @Volatile private var released = false
    private var gainLeft = 1f
    private var gainRight = 1f
    private var playbackSpeed = 1f
    private var preferred: AudioDeviceInfo? = null
    private var sourceError: String? = null
    private var transitioning = false
    private var playRequested = false

    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onEngineChanged: (() -> Unit)? = null

    val duration: Int get() = media?.duration ?: pcm?.duration ?: 0
    val currentPosition: Int get() =
        try { media?.currentPosition ?: pcm?.currentPosition ?: 0 }
        catch (_: Exception) { 0 }
    val isPlaying: Boolean get() =
        try { media?.isPlaying ?: pcm?.isPlaying ?: false }
        catch (_: Exception) { false }
    val audioSessionId: Int get() = media?.audioSessionId ?: pcm?.audioSessionId ?: 0
    val outputDescription: String get() =
        if (media != null) {
            "MediaPlayer (respaldo del sistema)" +
                (sourceError?.let { ": " + it.take(100) } ?: "")
        } else pcm?.outputDescription ?: "Motor de audio sin inicializar"

    val routedDevice: AudioDeviceInfo? get() =
        try { media?.routedDevice ?: pcm?.routedDevice }
        catch (_: Exception) { null }

    init {
        try {
            pcm = PcmTrackEngine(context, uri).apply {
                onCompletion = { handler.post {
                    if (!released) {
                        playRequested = false
                        this@UsbPcmPlayer.onCompletion?.invoke()
                    }
                } }
                onError = { error -> handler.post { if (!released) switchToBackup(error) } }
            }
        } catch (error: Exception) {
            sourceError = error.message ?: error.javaClass.simpleName
            try {
                media = openMediaPlayer()
            } catch (backup: Exception) {
                throw IllegalStateException(
                    "PCM no disponible: " + sourceError +
                        ". MediaPlayer también falló: " + backup.message, backup
                )
            }
        }
    }

    private fun openMediaPlayer(): MediaPlayer {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            p.setDataSource(context, uri)
            p.prepare()
            p.setOnCompletionListener {
                if (!released) {
                    playRequested = false
                    onCompletion?.invoke()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                if (!released) onError?.invoke("MediaPlayer: " + what + "/" + extra)
                true
            }
            p.setVolume(gainLeft, gainRight)
            preferred?.let { try { p.setPreferredDevice(it) } catch (_: Exception) {} }
            return p
        } catch (e: Exception) {
            p.release()
            throw e
        }
    }

    private fun switchToBackup(error: String) {
        if (released || media != null || transitioning) return
        transitioning = true
        val previous = pcm
        val previouslyPlaying = playRequested
        val position = previous?.currentPosition ?: 0
        try {
            val replacement = openMediaPlayer()
            listeners.forEach { (listener, _) ->
                try { previous?.removeOnRoutingChangedListener(listener) }
                catch (_: Exception) {}
            }
            previous?.release()
            pcm = null
            media = replacement
            sourceError = error
            listeners.forEach { (listener, eventHandler) ->
                try { replacement.addOnRoutingChangedListener(listener, eventHandler) }
                catch (_: Exception) {}
            }
            if (position > 0) replacement.seekTo(position)
            if (previouslyPlaying) replacement.start()
            setSpeed(playbackSpeed)
            setVolume(gainLeft, gainRight)
            onEngineChanged?.invoke()
        } catch (backup: Exception) {
            onError?.invoke(
                "AudioTrack falló: " + error + "; MediaPlayer también: " + backup.message
            )
        } finally {
            transitioning = false
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo): Boolean {
        if (released) return false
        preferred = device
        return try {
            media?.setPreferredDevice(device) ?: pcm?.setPreferredDevice(device) ?: false
        } catch (_: Exception) { false }
    }

    fun addOnRoutingChangedListener(
        listener: AudioRouting.OnRoutingChangedListener,
        eventHandler: Handler
    ) {
        if (released) return
        listeners[listener] = eventHandler
        try {
            media?.addOnRoutingChangedListener(listener, eventHandler)
                ?: pcm?.addOnRoutingChangedListener(listener, eventHandler)
        } catch (_: Exception) {}
    }

    fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) {
        listeners.remove(listener)
        try {
            media?.removeOnRoutingChangedListener(listener)
                ?: pcm?.removeOnRoutingChangedListener(listener)
        } catch (_: Exception) {}
    }

    fun setVolume(left: Float, right: Float) {
        gainLeft = left.coerceIn(0f, 1f)
        gainRight = right.coerceIn(0f, 1f)
        try {
            media?.setVolume(gainLeft, gainRight)
                ?: pcm?.setVolume(gainLeft, gainRight)
        } catch (_: Exception) {}
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 1.5f)
        try {
            val p = media
            if (p != null) {
                p.playbackParams = p.playbackParams.setSpeed(playbackSpeed)
            } else pcm?.setSpeed(playbackSpeed)
        } catch (_: Exception) {}
    }

    fun start() {
        if (released) return
        playRequested = true
        try {
            media?.start() ?: pcm?.start()
        } catch (error: Exception) {
            if (media == null) switchToBackup(error.message ?: "Error al iniciar AudioTrack")
            else onError?.invoke(error.message ?: "MediaPlayer no pudo reproducir")
        }
    }

    /** Fallback for phones whose AudioTrack initializes but produces silence. */
    fun forceSystemEngine() {
        if (released || media != null) return
        switchToBackup("Motor del sistema seleccionado manualmente")
    }

    fun pause() {
        playRequested = false
        try { media?.pause() ?: pcm?.pause() } catch (_: Exception) {}
    }

    fun seekTo(positionMs: Int) {
        try { media?.seekTo(positionMs.coerceAtLeast(0)) ?: pcm?.seekTo(positionMs) }
        catch (_: Exception) {}
    }

    fun release() {
        if (released) return
        released = true
        playRequested = false
        listeners.clear()
        try { pcm?.release() } catch (_: Exception) {}
        try { media?.release() } catch (_: Exception) {}
        pcm = null
        media = null
    }
}
