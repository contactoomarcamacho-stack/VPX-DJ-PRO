package uy.vpx.djpro

import android.app.Activity
import android.media.midi.MidiManager
import android.media.midi.MidiDevice
import android.media.midi.MidiReceiver
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import kotlin.math.cos
import kotlin.math.sin
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.view.ViewGroup

class MainActivity : Activity() {
 private lateinit var status: TextView
 private lateinit var midi: MidiManager
 private val players = arrayOfNulls<MediaPlayer>(2)
 private val playButtons = arrayOfNulls<Button>(2)
 private var midiDevice: MidiDevice? = null
 private val mappedPlay = mutableMapOf<Int, Int>()
 private var learnDeck = -1
 private val eq = arrayOfNulls<Equalizer>(2)
 private val volumes = floatArrayOf(1f, 1f)
 private val gains = floatArrayOf(1f, 1f)
 private val speeds = floatArrayOf(1f, 1f)
 private val bends = floatArrayOf(0f, 0f)
 private var cross = 0.5f
 private var master = 1f
 private val eqValues = Array(2) { floatArrayOf(0.5f, 0.5f, 0.5f) }
 private val progressBars = arrayOfNulls<SeekBar>(2)
 private var learnAction = "play"
 private val mappedCue = mutableMapOf<Int, Int>()
 private val mappedPitch = mutableMapOf<Int, Int>()
 private val pitchBars = arrayOfNulls<SeekBar>(2)
 private lateinit var midiMonitor: TextView
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  midi = getSystemService(MIDI_SERVICE) as MidiManager
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(13,18,29)); setPadding(20,14,20,14) }
  val heading = TextView(this).apply { text = "VPX DJ PRO  •  USB MIDI"; textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
  root.addView(heading)
  status = TextView(this).apply { textSize = 15f; setTextColor(Color.CYAN); gravity = Gravity.CENTER; setPadding(0,15,0,15) }
  root.addView(status)
  val decks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  for (name in listOf("DECK A", "DECK B")) {
   val deck = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(12,12,12,12) }
   val title = TextView(this).apply { text = name; textSize = 24f; setTextColor(Color.WHITE) }
   deck.addView(title)
   val load = Button(this).apply { text = "CARGAR AUDIO"; setOnClickListener { val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }; startActivityForResult(intent, 100 + if (name == "DECK A") 0 else 1) } }
   deck.addView(load)
   val index = if (name == "DECK A") 0 else 1
   val cue = Button(this).apply { text = "CUE"; setOnClickListener { players[index]?.pause(); players[index]?.seekTo(0); playButtons[index]?.text = "PLAY" } }
   val play = Button(this).apply { text = "PLAY / PAUSE"; setOnClickListener { togglePlay(index) } }
   playButtons[index] = play
   deck.addView(cue); deck.addView(play)
   deck.addView(Button(this).apply { text = "APRENDER PLAY MIDI"; setOnClickListener { learnDeck = index; learnAction = "play"; Toast.makeText(this@MainActivity, "Pulsá PLAY en la Numark", Toast.LENGTH_SHORT).show() } })
   deck.addView(Button(this).apply { text = "APRENDER CUE MIDI"; setOnClickListener { learnDeck = index; learnAction = "cue"; Toast.makeText(this@MainActivity, "Pulsá CUE en la Numark", Toast.LENGTH_SHORT).show() } })
   deck.addView(Button(this).apply { text = "APRENDER PITCH MIDI"; setOnClickListener { learnDeck = index; learnAction = "pitch"; Toast.makeText(this@MainActivity, "Mové el pitch en la Numark", Toast.LENGTH_SHORT).show() } })
   val progress = SeekBar(this).apply { max = 1000; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) players[index]?.let { if (it.duration > 0) it.seekTo(it.duration * value / 1000) } }
    override fun onStartTrackingTouch(bar: SeekBar?) {}
    override fun onStopTrackingTouch(bar: SeekBar?) {}
   }) }
   progressBars[index] = progress
   deck.addView(progress)
   deck.addView(Button(this).apply { text = "SYNC"; setOnClickListener { speeds[index] = speeds[1 - index]; applySpeed(index) } })
   val pitch = SeekBar(this).apply { max = 200; progress = 100 }
   pitchBars[index] = pitch
   deck.addView(pitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
   pitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) try { players[index]?.let { it.playbackParams = it.playbackParams.setSpeed((0.5f + value / 200f).coerceIn(0.5f, 1.5f)) } } catch (_: Exception) {} }
    override fun onStartTrackingTouch(bar: SeekBar?) {}
    override fun onStopTrackingTouch(bar: SeekBar?) {}
   })
   decks.addView(deck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
  }
  root.addView(decks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  midiMonitor = TextView(this).apply { text = "MIDI: esperando controles físicos"; setTextColor(Color.YELLOW); gravity = Gravity.CENTER }
  root.addView(midiMonitor)
  val crossBar = SeekBar(this).apply { max = 127; progress = 64; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
   override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) { cross = value / 127f; updateVolumes() } }
   override fun onStartTrackingTouch(bar: SeekBar?) {}
   override fun onStopTrackingTouch(bar: SeekBar?) {}
  }) }
  root.addView(TextView(this).apply { text = "CROSSFADER"; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
  root.addView(crossBar)
  val refresh = Button(this).apply { text = "BUSCAR CONTROLADORA MIDI"; setOnClickListener { scanMidi() } }
  root.addView(refresh)
  setContentView(root)
  scanMidi()
 }
 private fun togglePlay(index: Int) {
  val player = players[index] ?: run { Toast.makeText(this, "Cargá una canción primero", Toast.LENGTH_SHORT).show(); return }
  if (player.isPlaying) player.pause() else player.start()
  playButtons[index]?.text = if (player.isPlaying) "PAUSA" else "PLAY"
 }
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (resultCode != RESULT_OK || requestCode !in 100..101) return
  val uri: Uri = data?.data ?: return
  val index = requestCode - 100
  try {
   players[index]?.release()
   val player = MediaPlayer()
   player.setDataSource(this, uri)
   player.prepare()
   player.setOnCompletionListener { playButtons[index]?.text = "PLAY" }
   players[index] = player
   try { eq[index]?.release(); eq[index] = Equalizer(0, player.audioSessionId).apply { enabled = true }; updateEq(index) } catch (_: Exception) {}
   updateVolumes()
   playButtons[index]?.text = "PLAY"
   Toast.makeText(this, "Audio cargado", Toast.LENGTH_SHORT).show()
  } catch (e: Exception) { players[index] = null; Toast.makeText(this, "Error de audio: " + e.message, Toast.LENGTH_LONG).show() }
 }
 private fun updateVolumes() {
  for (i in 0..1) {
   val curve = if (i == 0) cos(cross * Math.PI / 2) else sin(cross * Math.PI / 2)
   val v = (volumes[i] * gains[i] * master * curve.toFloat()).coerceIn(0f, 1f)
   players[i]?.setVolume(v, v)
  }
 }
 private fun applySpeed(i: Int) { try { players[i]?.let { it.playbackParams = it.playbackParams.setSpeed((speeds[i] + bends[i]).coerceIn(0.5f, 1.5f)) } } catch (_: Exception) {} }
 private fun updateEq(i: Int) {
  val effect = eq[i] ?: return
  try {
   val range = effect.bandLevelRange
   for (band in 0 until effect.numberOfBands.toInt()) {
    val freq = effect.getCenterFreq(band.toShort()) / 1000
    val idx = if (freq < 400) 2 else if (freq < 4000) 1 else 0
    effect.setBandLevel(band.toShort(), (range[0] + (range[1] - range[0]) * eqValues[i][idx]).toInt().toShort())
   }
  } catch (_: Exception) {}
 }
 private val receiver = object : MidiReceiver() {
  override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
   var p = offset
   while (p + 2 < offset + count) {
    val statusByte = msg[p].toInt() and 255
    if (statusByte < 128 || statusByte >= 240) { p++; continue }
    val number = msg[p + 1].toInt() and 127
    val value = msg[p + 2].toInt() and 127
    val binding = NumarkMixtrackProMap.lookup(statusByte, number)
    runOnUiThread { handleMidi(statusByte, number, value, binding) }
    p += 3
   }
  }
 }
 private fun handleMidi(statusByte: Int, number: Int, value: Int, binding: NumarkMixtrackProMap.Binding?) {
  midiMonitor.text = "MIDI " + statusByte.toString(16) + ":" + number.toString(16) + "=" + value + " / " + (binding?.action ?: "SIN MAPA")
  if (binding == null) return
  val i = binding.deck
  val pressed = (statusByte and 240) == 144 && value > 0
  when (binding.action) {
   NumarkMixtrackProMap.Action.PLAY -> if (pressed) togglePlay(i)
   NumarkMixtrackProMap.Action.CUE -> if (pressed) { players[i]?.pause(); players[i]?.seekTo(0); playButtons[i]?.text = "PLAY" }
   NumarkMixtrackProMap.Action.SYNC -> if (pressed) { speeds[i] = speeds[1 - i]; applySpeed(i) }
   NumarkMixtrackProMap.Action.STUTTER -> if (pressed) { players[i]?.seekTo(0); players[i]?.start() }
   NumarkMixtrackProMap.Action.VOLUME -> { volumes[i] = value / 127f; updateVolumes() }
   NumarkMixtrackProMap.Action.GAIN -> { gains[i] = (gains[i] + (if (value > 64) value - 128 else value) / 128f).coerceIn(0f, 2f); updateVolumes() }
   NumarkMixtrackProMap.Action.HIGH -> { eqValues[i][0] = value / 127f; updateEq(i) }
   NumarkMixtrackProMap.Action.MID -> { eqValues[i][1] = value / 127f; updateEq(i) }
   NumarkMixtrackProMap.Action.LOW -> { eqValues[i][2] = value / 127f; updateEq(i) }
   NumarkMixtrackProMap.Action.PITCH -> { speeds[i] = (1f + (value - 64) / 640f); applySpeed(i) }
   NumarkMixtrackProMap.Action.CROSSFADER -> { cross = 1f - value / 127f; updateVolumes() }
   NumarkMixtrackProMap.Action.MASTER -> { master = value / 127f; updateVolumes() }
   NumarkMixtrackProMap.Action.BEND_UP -> { bends[i] = if (pressed) 0.03f else 0f; applySpeed(i) }
   NumarkMixtrackProMap.Action.BEND_DOWN -> { bends[i] = if (pressed) -0.03f else 0f; applySpeed(i) }
   NumarkMixtrackProMap.Action.JOG -> {
    val delta = if (value > 64) value - 128 else value
    players[i]?.let { player ->
     if (player.isPlaying) { bends[i] = (delta / 500f).coerceIn(-0.08f, 0.08f); applySpeed(i) }
     else player.seekTo((player.currentPosition + delta * 35).coerceIn(0, player.duration.coerceAtLeast(0)))
    }
   }
   NumarkMixtrackProMap.Action.JOG_TOUCH -> {}
  }
 }
 private fun scanMidi() {
  val devices = midi.devices
  status.text = if (devices.isEmpty()) "No se detectaron dispositivos MIDI. Conectá la consola por USB OTG." else
   devices.joinToString("\n") { MidiDiagnostics.deviceLabel(it.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME), it.inputPortCount, it.outputPortCount) }
  val info = devices.firstOrNull { it.outputPortCount > 0 && (it.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: "").contains("mixtrack", true) } ?: devices.firstOrNull { it.outputPortCount > 0 } ?: return
  midiDevice?.close()
  midi.openDevice(info, { device ->
   midiDevice = device
   device?.openOutputPort(0)?.connect(receiver)
  }, android.os.Handler(mainLooper))
 }
 override fun onDestroy() { midiDevice?.close(); eq.forEach { it?.release() }; players.forEach { it?.release() }; super.onDestroy() }
}
