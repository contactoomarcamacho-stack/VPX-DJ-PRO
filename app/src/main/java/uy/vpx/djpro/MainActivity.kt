package uy.vpx.djpro

import android.app.Activity
import android.media.midi.MidiManager
import android.media.midi.MidiDevice
import android.media.midi.MidiReceiver
import android.media.MediaPlayer
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
   deck.addView(Button(this).apply { text = "APRENDER PLAY MIDI"; setOnClickListener { learnDeck = index; Toast.makeText(this@MainActivity, "Pulsá PLAY en la Numark", Toast.LENGTH_SHORT).show() } })
   val pitch = SeekBar(this).apply { max = 200; progress = 100 }
   deck.addView(pitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
   pitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) try { players[index]?.let { it.playbackParams = it.playbackParams.setSpeed((0.5f + value / 200f).coerceIn(0.5f, 1.5f)) } } catch (_: Exception) {} }
    override fun onStartTrackingTouch(bar: SeekBar?) {}
    override fun onStopTrackingTouch(bar: SeekBar?) {}
   })
   decks.addView(deck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
  }
  root.addView(decks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
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
   playButtons[index]?.text = "PLAY"
   Toast.makeText(this, "Audio cargado", Toast.LENGTH_SHORT).show()
  } catch (e: Exception) { players[index] = null; Toast.makeText(this, "Error de audio: " + e.message, Toast.LENGTH_LONG).show() }
 }
 private val receiver = object : MidiReceiver() {
  override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
   if (count < 3) return
   val command = msg[offset].toInt() and 0xF0
   val channel = msg[offset].toInt() and 0x0F
   val number = msg[offset + 1].toInt() and 0x7F
   val velocity = msg[offset + 2].toInt() and 0x7F
   if (velocity == 0) return
   val key = (command shl 16) or (channel shl 8) or number
   runOnUiThread {
    if (learnDeck >= 0) { mappedPlay[key] = learnDeck; learnDeck = -1; Toast.makeText(this@MainActivity, "PLAY MIDI asignado", Toast.LENGTH_SHORT).show() }
    else mappedPlay[key]?.let { togglePlay(it) }
   }
  }
 }
 private fun scanMidi() {
  val devices = midi.devices
  status.text = if (devices.isEmpty()) "No se detectaron dispositivos MIDI. Conectá la consola por USB OTG." else
   devices.joinToString("\n") { MidiDiagnostics.deviceLabel(it.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME), it.inputPortCount, it.outputPortCount) }
  val info = devices.firstOrNull { it.outputPortCount > 0 } ?: return
  midiDevice?.close()
  midi.openDevice(info, { device ->
   midiDevice = device
   device?.openOutputPort(0)?.connect(receiver)
  }, android.os.Handler(mainLooper))
 }
 override fun onDestroy() { midiDevice?.close(); players.forEach { it?.release() }; super.onDestroy() }
}
