package uy.vpx.djpro

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler

/**
 * Requests USB audio output on both decks and verifies the REAL routed
 * device. Android MIDI and Android USB audio are separate subsystems.
 */
class UsbAudioRouter(
    context: Context,
    private val handler: Handler,
    private val players: () -> List<MediaPlayer?>,
    private val onStatus: (String) -> Unit
) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var selected: AudioDeviceInfo? = null
    private var closed = false
    private var lastStatus = ""
    private val routingListeners =
        mutableMapOf<MediaPlayer, AudioRouting.OnRoutingChangedListener>()

    private val devicesCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }
    }

    fun start() {
        manager.registerAudioDeviceCallback(devicesCallback, handler)
        refresh()
    }

    private fun isUsbOutput(device: AudioDeviceInfo): Boolean =
        device.isSink && (device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY)

    private fun name(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB Audio"

    private fun findOutput(): AudioDeviceInfo? {
        val all = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return all.filter { isUsbOutput(it) }.sortedWith(
            compareByDescending<AudioDeviceInfo> {
                name(it).contains("mixtrack", ignoreCase = true) ||
                    name(it).contains("numark", ignoreCase = true)
            }.thenBy { it.id }
        ).firstOrNull()
    }

    fun refresh() {
        if (closed) return
        val target = findOutput()
        selected = target
        if (target == null) {
            players().forEach {
                try { if (it?.isPlaying == true) it.pause() } catch (_: Exception) {}
            }
            publish("AUDIO USB: Android NO detecta la tarjeta de sonido USB. La conexión MIDI es independiente.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            publish("AUDIO USB: este enrutamiento de MediaPlayer requiere Android 9 o posterior.")
            return
        }
        var accepted = 0
        players().forEach { p -> if (p != null && applyTo(p, target)) accepted++ }
        publish("AUDIO USB: " + name(target) + " detectada. Rutas solicitadas: " + accepted + ". Salida MASTER RCA Output 1.")
    }

    fun attach(player: MediaPlayer) {
        if (closed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val callback = AudioRouting.OnRoutingChangedListener {
                handler.postDelayed({ if (!closed) verifyRouting() }, 250)
            }
            player.addOnRoutingChangedListener(callback, handler)
            routingListeners[player] = callback
        }
        selected?.let { applyTo(player, it) }
    }

    fun detach(player: MediaPlayer?) {
        if (player == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            routingListeners.remove(player)?.let {
                try { player.removeOnRoutingChangedListener(it) } catch (_: Exception) {}
            }
        }
    }

    private fun applyTo(player: MediaPlayer, output: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try { player.setPreferredDevice(output) } catch (_: Exception) { false }
    }

    fun readyForPlayback(player: MediaPlayer): Boolean {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            publish("AUDIO USB: enrutamiento no soportado por esta versión de Android.")
            return false
        }
        val output = findOutput()
        if (output == null) {
            selected = null
            publish("AUDIO USB: sin salida USB. Revisá OTG y la opción de Android 'Inhabilitar enrutamiento de audio USB'.")
            return false
        }
        selected = output
        if (!applyTo(player, output)) {
            publish("AUDIO USB: Android rechazó seleccionar " + name(output) + ".")
            return false
        }
        publish("AUDIO USB: salida " + name(output) + " solicitada, comprobando reproducción...")
        return true
    }

    fun verifyAfterStart() {
        handler.postDelayed({ if (!closed) verifyRouting() }, 500)
    }

    private fun verifyRouting() {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val output = selected ?: return
        val playing = players().filter {
            try { it?.isPlaying == true } catch (_: Exception) { false }
        }
        if (playing.isEmpty()) return
        var confirmed = 0
        for (player in playing) {
            if (player == null) continue
            val route = try { player.routedDevice } catch (_: Exception) { null }
            if (route == null) continue // Not yet reported by the audio framework
            if (isUsbOutput(route) && route.id == output.id) {
                confirmed++
            } else {
                // Never silently play via tablet speaker when USB was requested.
                try { player.pause() } catch (_: Exception) {}
                publish("AUDIO USB: Android envió sonido a " + name(route) +
                    " en vez de " + name(output) + ". Se pausó para evitar usar el altavoz.")
                return
            }
        }
        if (confirmed > 0) {
            publish("AUDIO USB ACTIVO: " + name(output) +
                " | " + confirmed + " deck(s) con salida USB verificada.")
        }
    }

    private fun publish(text: String) {
        if (text != lastStatus) {
            lastStatus = text
            onStatus(text)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        manager.unregisterAudioDeviceCallback(devicesCallback)
        players().forEach { detach(it) }
        routingListeners.clear()
        selected = null
    }
}
