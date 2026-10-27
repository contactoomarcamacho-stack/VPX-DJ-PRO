package uy.vpx.djpro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps all opened MIDI ports alive and automatically reconnects when Android
 * announces a device change. USB-MIDI bulk input is an optional fallback for
 * USB MIDI interfaces that the Android MIDI service cannot read correctly.
 */
class NumarkMidiConnection(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onEvent: (Int, Int, Int, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val midi = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: MidiDevice? = null
    private var openedId: Int = -1
    private val outputs = mutableListOf<MidiOutputPort>()
    private var input: MidiInputPort? = null
    private var fallbackConnection: UsbDeviceConnection? = null
    private var fallbackInterface: UsbInterface? = null
    private var fallbackThread: Thread? = null
    private val fallbackActive = AtomicBoolean(false)
    private var closed = false
    private var receiverRegistered = false
    private var eventCount = 0

    private val permissionAction = "uy.vpx.djpro.USB_MIDI_ACCESS"
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                @Suppress("DEPRECATION")
                val target = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && target != null) {
                    openDirect(target)
                } else emit("Permiso USB denegado. Probá reconectar la controladora.")
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (fallbackActive.get()) closeDirect()
                if (!closed) handler.postDelayed({ scan() }, 500)
            }
        }
    }
    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (!closed && !fallbackActive.get()) handler.postDelayed({ scan() }, 350)
        }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == openedId) {
                closeNative()
                if (!closed) {
                    emit("Numark desconectada. Esperando USB...")
                    handler.postDelayed({ scan() }, 400)
                }
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        midi.registerDeviceCallback(deviceCallback, handler)
    }

    private fun emit(message: String) = handler.post { if (!closed) onStatus(message) }

    fun scan() {
        if (closed || fallbackActive.get()) return
        val devices = midi.devices.filter { it.outputPortCount > 0 }
        val target = devices.firstOrNull {
            val name = it.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()
            name.contains("mixtrack", true) || name.contains("numark", true)
        } ?: devices.firstOrNull { it.type == MidiDeviceInfo.TYPE_USB }
        if (target == null) {
            closeNative()
            emit("Android no expone la consola como MIDI. Usá USB DIRECTO o conectá OTG.")
            return
        }
        if (target.id == openedId && outputs.isNotEmpty()) return
        closeNative()
        emit("Abriendo MIDI: " + target.properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty())
        midi.openDevice(target, { d ->
            if (closed || fallbackActive.get()) { d?.close(); return@openDevice }
            if (d == null) {
                emit("Android no pudo abrir MIDI. Probá USB DIRECTO.")
                return@openDevice
            }
            device = d
            openedId = target.id
            for (index in 0 until target.outputPortCount) {
                try {
                    val port = d.openOutputPort(index) ?: continue
                    val parser = MidiStreamParser { status, number, value ->
                        eventCount++
                        handler.post {
                            if (!closed) onEvent(status, number, value, "MIDI puerto " + index)
                        }
                    }
                    port.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            parser.feed(msg, offset, count)
                        }
                    })
                    outputs.add(port) // Strong reference: never let GC close the port
                } catch (_: Exception) { }
            }
            try { if (target.inputPortCount > 0) input = d.openInputPort(0) } catch (_: Exception) {}
            if (outputs.isEmpty()) emit("No se pudo abrir ningún puerto. Probá USB DIRECTO.")
            else emit("Numark conectada: " + outputs.size + " puerto(s) MIDI. Mensajes: " + eventCount)
        }, handler)
    }

    fun sendLed(note: Int, enabled: Boolean) {
        try {
            val bytes = byteArrayOf(0x90.toByte(), note.toByte(), (if (enabled) 0x64 else 0).toByte())
            input?.send(bytes, 0, 3)
        } catch (_: Exception) { }
    }

    private fun closeNative() {
        outputs.forEach { try { it.close() } catch (_: Exception) {} }
        outputs.clear()
        try { input?.close() } catch (_: Exception) {}
        input = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        openedId = -1
    }

    private data class UsbMidiEndpoint(val device: UsbDevice, val iface: UsbInterface, val endpoint: UsbEndpoint)

    private fun findUsbMidi(): UsbMidiEndpoint? {
        val possibilities = mutableListOf<UsbMidiEndpoint>()
        for (d in usb.deviceList.values) for (i in 0 until d.interfaceCount) {
            val iface = d.getInterface(i)
            // USB Audio class 1, MIDI streaming subclass 3
            if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO || iface.interfaceSubclass != 3) continue
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.direction == UsbConstants.USB_DIR_IN &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    possibilities.add(UsbMidiEndpoint(d, iface, endpoint))
                }
            }
        }
        return possibilities.firstOrNull {
            it.device.productName.orEmpty().contains("mixtrack", true) ||
                it.device.manufacturerName.orEmpty().contains("numark", true)
        } ?: possibilities.firstOrNull()
    }

    fun useDirectUsb() {
        if (closed) return
        val found = findUsbMidi()
        if (found == null) {
            emit("No hay interfaz USB-MIDI compatible. Verificá el adaptador OTG.")
            return
        }
        if (!usb.hasPermission(found.device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val permission = PendingIntent.getBroadcast(context, 0,
                Intent(permissionAction).setPackage(context.packageName), flags)
            emit("Solicitando permiso para leer USB-MIDI directamente...")
            usb.requestPermission(found.device, permission)
            return
        }
        openDirect(found.device)
    }

    private fun openDirect(target: UsbDevice) {
        if (closed) return
        val info = findUsbMidi()?.takeIf { it.device.deviceId == target.deviceId }
        if (info == null) { emit("La interfaz USB-MIDI ya no está conectada."); return }
        closeNative()
        closeDirect()
        val connection = usb.openDevice(target)
        if (connection == null) { emit("Android no permitió abrir USB directo."); return }
        if (!connection.claimInterface(info.iface, true)) {
            connection.close()
            emit("No se pudo reservar el puerto USB-MIDI.")
            return
        }
        fallbackConnection = connection
        fallbackInterface = info.iface
        fallbackActive.set(true)
        emit("USB-MIDI DIRECTO conectado: " + target.productName.orEmpty())
        val parser = MidiStreamParser { status, number, value ->
            handler.post { if (!closed) onEvent(status, number, value, "USB DIRECTO") }
        }
        fallbackThread = Thread({
            val buffer = ByteArray(512)
            while (fallbackActive.get()) {
                val count = try { connection.bulkTransfer(info.endpoint, buffer, buffer.size, 150) }
                    catch (_: Exception) { -1 }
                if (count > 0) {
                    // USB MIDI event packet = cable/CIN, MIDI byte 1, 2, 3
                    var p = 0
                    while (p + 3 < count) {
                        val cin = buffer[p].toInt() and 0x0f
                        val length = when (cin) {
                            0x5, 0xf -> 1
                            0x2, 0x6, 0xc, 0xd -> 2
                            0x3, 0x4, 0x7, 0x8, 0x9, 0xa, 0xb, 0xe -> 3
                            else -> 0
                        }
                        if (length > 0) parser.feed(buffer, p + 1, length)
                        p += 4
                    }
                }
            }
        }, "Numark-USB-MIDI").apply { isDaemon = true; start() }
    }

    private fun closeDirect() {
        fallbackActive.set(false)
        val connection = fallbackConnection
        fallbackConnection = null
        val iface = fallbackInterface
        fallbackInterface = null
        if (connection != null) {
            try { if (iface != null) connection.releaseInterface(iface) } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
        fallbackThread = null
    }

    fun close() {
        if (closed) return
        closed = true
        try { midi.unregisterDeviceCallback(deviceCallback) } catch (_: Exception) {}
        if (receiverRegistered) {
            try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        closeDirect()
        closeNative()
        handler.removeCallbacksAndMessages(null)
    }
}
