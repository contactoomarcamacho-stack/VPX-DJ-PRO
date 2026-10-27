package uy.vpx.djpro

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler

/**
 * Requests USB audio output on both decks and verifies the REAL routed
 * device. Android MIDI and Android USB audio are separate subsystems.
 */
class UsbAudioRouter(
    context: Context,
    private val handler: Handler,
    private val players: () -> List<UsbPcmPlayer?>,
    private val onStatus: (String) -> Unit
) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var selected: AudioDeviceInfo? = null
    private var closed = false
    private var lastStatus = ""
    private val routingListeners =
        mutableMapOf<UsbPcmPlayer, AudioRouting.OnRoutingChangedListener>()

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

    private fun diagnostic(): String {
        val all = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val outputs = all.joinToString(", ") { name(it) + " [tipo=" + it.type + "]" }
        var audioStreaming = 0
        val usbDevices = usbManager.deviceList.values
        for (device in usbDevices) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2) audioStreaming++
            }
        }
        return if (audioStreaming > 0) {
            "USB informa " + audioStreaming + " interfaz(es) AudioStreaming, pero Android no habilita ninguna salida. " +
                "Salidas Android: " + (if (outputs.isEmpty()) "ninguna" else outputs) +
                ". Desactivá 'Inhabilitar enrutamiento de audio USB' en Opciones de desarrollador."
        } else {
            "La consola responde MIDI, pero Android no expone interfaz USB AudioStreaming. " +
                "Salidas Android: " + (if (outputs.isEmpty()) "ninguna" else outputs) +
                ". Verificá OTG y configuración USB del sistema."
        }
    }

    fun refresh() {
        if (closed) return
        val target = findOutput()
        selected = target
        if (target == null) {
            publish("AUDIO: modo automático del sistema (parlante/auriculares). " +
                "USB no enumerado. " + diagnostic())
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            publish("AUDIO USB: este enrutamiento requiere Android 7 o posterior.")
            return
        }
        var accepted = 0
        players().forEach { p -> if (p != null && applyTo(p, target)) accepted++ }
        publish("AUDIO USB: " + name(target) + " detectada. Rutas solicitadas: " + accepted + ". Salida MASTER RCA Output 1.")
    }

    fun attach(player: UsbPcmPlayer) {
        if (closed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = AudioRouting.OnRoutingChangedListener {
                handler.postDelayed({ if (!closed) verifyRouting() }, 250)
            }
            player.addOnRoutingChangedListener(callback, handler)
            routingListeners[player] = callback
        }
        selected?.let { applyTo(player, it) }
    }

    fun detach(player: UsbPcmPlayer?) {
        if (player == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            routingListeners.remove(player)?.let {
                try { player.removeOnRoutingChangedListener(it) } catch (_: Exception) {}
            }
        }
    }

    private fun applyTo(player: UsbPcmPlayer, output: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try { player.setPreferredDevice(output) } catch (_: Exception) { false }
    }

    fun readyForPlayback(player: UsbPcmPlayer): Boolean {
        if (closed) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            publish("AUDIO: reproducción por ruta automática de Android.")
            return true
        }
        val output = findOutput()
        if (output == null) {
            selected = null
            publish("AUDIO: reproducción habilitada por salida automática del sistema. " +
                "USB no enumerado.")
            return true
        }
        selected = output
        if (!applyTo(player, output)) {
            publish("AUDIO: Android no aceptó " + name(output) +
                ". Usando la salida automática (sin bloquear PLAY).")
            return true
        }
        publish("AUDIO USB: salida " + name(output) + " solicitada, comprobando reproducción...")
        return true
    }

    fun verifyAfterStart() {
        handler.postDelayed({ if (!closed) verifyRouting() }, 500)
    }

    private fun verifyRouting() {
        if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
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
                publish("AUDIO: salida actual " + name(route) +
                    " (preferida USB: " + name(output) + "). La reproducción continúa.")
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
