package uy.vpx.djpro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Two local audio decks with controls attached directly to the original
 * Numark Mixtrack Pro MIDI map. MIDI inputs are routed through
 * NumarkMidiConnection, which owns and retains the USB MIDI ports.
 */
class MainActivity : Activity() {
    private class Deck {
        var player: MediaPlayer? = null
        var equalizer: Equalizer? = null
        var name: TextView? = null
        var position: SeekBar? = null
        var time: TextView? = null
        var play: Button? = null
        var volumeBar: SeekBar? = null
        var pitchBar: SeekBar? = null
        var gainBar: SeekBar? = null
        val eqBars = arrayOfNulls<SeekBar>(3)
        val eqValues = floatArrayOf(0.5f, 0.5f, 0.5f) // high, mid, low
        var channelVolume = 1f
        var gain = 1f
        var pitch = 1f
        var bend = 0f
        var cueMs = 0
        var touchingJog = false
        var lastJogAt = 0L
    }

    private val decks = arrayOf(Deck(), Deck())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connector: NumarkMidiConnection
    private lateinit var audioRouter: UsbAudioRouter
    private lateinit var audioStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var midiMonitor: TextView
    private lateinit var crossBar: SeekBar
    private lateinit var masterBar: SeekBar
    private var cross = 0.5f
    private var master = 1f
    private var messages = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(12, 17, 30))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 10, 18, 10)
        }
        scroller.addView(root)
        root.addView(text("VPX DJ PRO   |   NUMARK MIXTRACK PRO", 21f, Color.WHITE))
        connectionStatus = text("Buscando controladora USB MIDI...", 13f, Color.CYAN)
        root.addView(connectionStatus)
        audioStatus = text("AUDIO USB: buscando tarjeta de sonido...", 13f, Color.GREEN)
        root.addView(audioStatus)

        val decksRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (deck in 0..1) {
            val column = buildDeck(deck)
            decksRow.addView(column, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(decksRow)

        val mixer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val crossColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        crossColumn.addView(text("CROSSFADER A  /  B", 12f))
        crossBar = newBar(crossColumn, 64) { value ->
            cross = value / 127f
            updateVolumes()
        }
        mixer.addView(crossColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        val masterColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        masterColumn.addView(text("MASTER", 12f))
        masterBar = newBar(masterColumn, 127) { value ->
            master = value / 127f
            updateVolumes()
        }
        mixer.addView(masterColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(mixer)

        midiMonitor = text("MIDI: sin mensajes. Mové un control de la Numark.", 12f, Color.YELLOW)
        root.addView(midiMonitor)
        val connectionActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectionActions.addView(Button(this).apply {
            text = "RECONEXIÓN MIDI"
            setOnClickListener { connector.scan() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectionActions.addView(Button(this).apply {
            text = "USB DIRECTO"
            setOnClickListener { connector.useDirectUsb() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(connectionActions)
        root.addView(Button(this).apply {
            text = "DETECTAR SALIDA USB AUDIO"
            setOnClickListener { audioRouter.refresh() }
        })
        root.addView(text("Si aparece conectada pero no registra controles, probá USB DIRECTO. El permiso lo solicita Android.", 11f, Color.LTGRAY))
        setContentView(scroller)

        audioRouter = UsbAudioRouter(this, handler,
            players = { decks.map { it.player } },
            onStatus = { audioStatus.text = it }
        )
        audioRouter.start()

        connector = NumarkMidiConnection(this,
            onStatus = { connectionStatus.text = it },
            onEvent = { status, number, value, source ->
                onMidi(status, number, value, source)
            }
        )
        connector.scan()
        handler.post(object : Runnable {
            override fun run() {
                refreshProgress()
                handler.postDelayed(this, 100)
            }
        })
    }

    private fun buildDeck(index: Int): LinearLayout {
        val color = if (index == 0) Color.rgb(80, 214, 234) else Color.rgb(228, 128, 245)
        val d = decks[index]
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 0, 8, 0)
        }
        column.addView(text(if (index == 0) "DECK A" else "DECK B", 21f, color))
        d.name = text("Sin canción", 12f, Color.LTGRAY).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(d.name)
        val load = Button(this).apply {
            text = "CARGAR TEMA"
            setOnClickListener { selectTrack(index) }
        }
        column.addView(load)
        d.time = text("00:00 / 00:00", 12f, color)
        column.addView(d.time)
        d.position = newBar(column, 0, 1000) { value ->
            d.player?.let { player ->
                if (player.duration > 0) player.seekTo(player.duration * value / 1000)
            }
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        d.play = Button(this).apply {
            text = "PLAY"
            setOnClickListener { togglePlay(index) }
        }
        buttons.addView(d.play, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "CUE"
            setOnClickListener { cue(index) }
            setOnLongClickListener {
                d.cueMs = d.player?.currentPosition ?: 0
                Toast.makeText(this@MainActivity, "Cue guardado", Toast.LENGTH_SHORT).show()
                true
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "SYNC"
            setOnClickListener { sync(index) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons)

        val levels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        v.addView(text("CANAL", 11f))
        d.volumeBar = newBar(v, 127) { value ->
            d.channelVolume = value / 127f; updateVolumes()
        }
        levels.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(text("PITCH", 11f))
        d.pitchBar = newBar(p, 64) { value ->
            d.pitch = pitchSpeed(value); applySpeed(index)
        }
        levels.addView(p, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(levels)

        val eqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (band in 0..2) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            b.addView(text(arrayOf("HIGH", "MID", "LOW")[band], 11f))
            d.eqBars[band] = newBar(b, 64) { value ->
                d.eqValues[band] = value / 127f
                updateEq(index)
            }
            eqRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        column.addView(eqRow)

        column.addView(text("GANANCIA", 11f))
        d.gainBar = newBar(column, 64) { value ->
            d.gain = value / 64f
            updateVolumes()
        }
        return column
    }

    private fun text(content: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = content
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(3, 2, 3, 2)
        }

    private fun newBar(parent: LinearLayout, initial: Int, maxValue: Int = 127, onUser: (Int) -> Unit): SeekBar {
        val bar = SeekBar(this).apply {
            max = maxValue
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onUser(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return bar
    }

    private fun selectTrack(index: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, 100 + index)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in 100..101) return
        val uri: Uri = data?.data ?: return
        val index = requestCode - 100
        val d = decks[index]
        try {
            d.equalizer?.release()
            d.equalizer = null
            audioRouter.detach(d.player)
            d.player?.release()
            d.player = null
            val player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(this@MainActivity, uri)
                prepare()
                setOnCompletionListener { updatePlayButton(index) }
            }
            d.player = player
            audioRouter.attach(player)
            d.name?.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Tema cargado"
            d.position?.progress = 0
            d.cueMs = 0
            try {
                d.equalizer = Equalizer(0, player.audioSessionId).apply { enabled = true }
                updateEq(index)
            } catch (_: Exception) { }
            updateVolumes()
            updatePlayButton(index)
            Toast.makeText(this, "Tema cargado en Deck " + (if (index == 0) "A" else "B"), Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            audioRouter.detach(d.player)
            d.player?.release()
            d.player = null
            d.name?.text = "Error al cargar audio"
            Toast.makeText(this, "Error de audio: " + error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlay(index: Int) {
        val p = decks[index].player
        if (p == null) {
            Toast.makeText(this, "Primero cargá un tema en Deck " + (if (index == 0) "A" else "B"), Toast.LENGTH_SHORT).show()
            return
        }
        if (p.isPlaying) p.pause() else {
            if (!audioRouter.readyForPlayback(p)) {
                Toast.makeText(this, "No se detecta salida de audio USB. Revisá el indicador verde.", Toast.LENGTH_LONG).show()
                return
            }
            p.start()
            applySpeed(index)
            audioRouter.verifyAfterStart()
        }
        updatePlayButton(index)
    }

    private fun updatePlayButton(index: Int) {
        val playing = decks[index].player?.isPlaying == true
        decks[index].play?.text = if (playing) "PAUSA" else "PLAY"
        if (::connector.isInitialized) connector.sendLed(if (index == 0) 0x3b else 0x42, playing)
    }

    private fun cue(index: Int) {
        val d = decks[index]
        d.player?.apply { pause(); seekTo(d.cueMs) }
        updatePlayButton(index)
    }

    private fun stutter(index: Int) {
        val d = decks[index]
        val player = d.player ?: return
        if (!audioRouter.readyForPlayback(player)) return
        player.seekTo(d.cueMs)
        player.start()
        applySpeed(index)
        audioRouter.verifyAfterStart()
        updatePlayButton(index)
    }

    private fun sync(index: Int) {
        // Tempo only: Android MediaPlayer does not provide beat analysis / phase sync.
        decks[index].pitch = decks[1 - index].pitch
        decks[index].pitchBar?.progress = decks[1 - index].pitchBar?.progress ?: 64
        applySpeed(index)
    }

    private fun pitchSpeed(value: Int): Float =
        (1f + (value - 64f) * 0.08f / if (value < 64) 64f else 63f).coerceIn(0.92f, 1.08f)

    private fun applySpeed(index: Int) {
        val d = decks[index]
        try {
            val p = d.player ?: return
            p.playbackParams = p.playbackParams.setSpeed((d.pitch + d.bend).coerceIn(0.5f, 1.5f))
        } catch (_: Exception) { }
    }

    private fun updateVolumes() {
        val left = cos(cross * Math.PI / 2).toFloat()
        val right = sin(cross * Math.PI / 2).toFloat()
        decks.forEachIndexed { index, deck ->
            val value = (deck.channelVolume * deck.gain * master *
                if (index == 0) left else right).coerceIn(0f, 1f)
            deck.player?.setVolume(value, value)
        }
    }

    private fun updateEq(index: Int) {
        val d = decks[index]
        val effect = d.equalizer ?: return
        try {
            val range = effect.bandLevelRange
            for (i in 0 until effect.numberOfBands.toInt()) {
                val hz = effect.getCenterFreq(i.toShort()) / 1000
                val mapped = if (hz < 400) 2 else if (hz < 4000) 1 else 0
                val value = d.eqValues[mapped]
                val level = (range[0] + (range[1] - range[0]) * value).roundToInt().toShort()
                effect.setBandLevel(i.toShort(), level)
            }
        } catch (_: Exception) { }
    }

    private fun onMidi(statusByte: Int, number: Int, value: Int, source: String) {
        messages++
        val binding = NumarkMixtrackProMap.lookup(statusByte, number)
        midiMonitor.text = String.format(Locale.US,
            "MIDI RECIBIDO #%d  %02X %02X %02X  |  %s  |  %s",
            messages, statusByte, number, value, source,
            binding?.action?.name ?: "CONTROL NO MAPEADO")
        if (binding == null) return
        val index = binding.deck
        val press = (statusByte and 0xf0) == 0x90 && value > 0
        val d = if (index in 0..1) decks[index] else null
        when (binding.action) {
            NumarkMixtrackProMap.Action.PLAY -> if (press) togglePlay(index)
            NumarkMixtrackProMap.Action.CUE -> if (press) cue(index)
            NumarkMixtrackProMap.Action.SYNC -> if (press) sync(index)
            NumarkMixtrackProMap.Action.STUTTER -> if (press) stutter(index)
            NumarkMixtrackProMap.Action.VOLUME -> {
                d!!.channelVolume = value / 127f
                d.volumeBar?.progress = value
                updateVolumes()
            }
            NumarkMixtrackProMap.Action.PITCH -> {
                d!!.pitch = pitchSpeed(value)
                d.pitchBar?.progress = value
                applySpeed(index)
            }
            NumarkMixtrackProMap.Action.HIGH,
            NumarkMixtrackProMap.Action.MID,
            NumarkMixtrackProMap.Action.LOW -> {
                val b = when (binding.action) {
                    NumarkMixtrackProMap.Action.HIGH -> 0
                    NumarkMixtrackProMap.Action.MID -> 1
                    else -> 2
                }
                d!!.eqValues[b] = value / 127f
                d.eqBars[b]?.progress = value
                updateEq(index)
            }
            NumarkMixtrackProMap.Action.GAIN -> {
                // Mixtrack Pro gain knobs are relative/two's-complement MIDI CC.
                val step = if (value > 63) value - 128 else value
                d!!.gain = (d.gain + step / 100f).coerceIn(0f, 2f)
                d.gainBar?.progress = (d.gain * 64).roundToInt().coerceIn(0, 127)
                updateVolumes()
            }
            NumarkMixtrackProMap.Action.CROSSFADER -> {
                cross = 1f - value / 127f // inverted in original Mixxx mapping
                crossBar.progress = (cross * 127).roundToInt()
                updateVolumes()
            }
            NumarkMixtrackProMap.Action.MASTER -> {
                master = value / 127f
                masterBar.progress = value
                updateVolumes()
            }
            NumarkMixtrackProMap.Action.BEND_UP -> {
                d!!.bend = if (press) 0.04f else 0f
                applySpeed(index)
            }
            NumarkMixtrackProMap.Action.BEND_DOWN -> {
                d!!.bend = if (press) -0.04f else 0f
                applySpeed(index)
            }
            NumarkMixtrackProMap.Action.JOG_TOUCH -> {
                d!!.touchingJog = press
                if (!press) { d.bend = 0f; applySpeed(index) }
            }
            NumarkMixtrackProMap.Action.JOG -> {
                val diff = if (value > 63) value - 128 else value
                if (diff != 0) {
                    val p = d!!.player
                    if (p != null) {
                        if (d.touchingJog || !p.isPlaying) {
                            p.seekTo((p.currentPosition + diff * 40)
                                .coerceIn(0, p.duration.coerceAtLeast(0)))
                        } else {
                            d.bend = (diff * 0.007f).coerceIn(-0.10f, 0.10f)
                            d.lastJogAt = SystemClock.uptimeMillis()
                            applySpeed(index)
                        }
                    }
                }
            }
        }
    }

    private fun refreshProgress() {
        val now = SystemClock.uptimeMillis()
        for (index in 0..1) {
            val d = decks[index]
            if (d.lastJogAt > 0 && now - d.lastJogAt >= 125 && d.bend != 0f) {
                d.bend = 0f
                d.lastJogAt = 0
                applySpeed(index)
            }
            d.player?.let { p ->
                if (p.duration > 0) {
                    d.position?.progress = (p.currentPosition.toLong() * 1000 / p.duration).toInt()
                    d.time?.text = formatTime(p.currentPosition) + " / " + formatTime(p.duration)
                }
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::connector.isInitialized) connector.close()
        if (::audioRouter.isInitialized) audioRouter.close()
        decks.forEach {
            it.equalizer?.release()
            it.player?.release()
        }
        super.onDestroy()
    }
}
