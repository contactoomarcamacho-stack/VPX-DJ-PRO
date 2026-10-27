package uy.vpx.djpro

import android.app.Activity
import android.media.midi.MidiManager
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import android.view.ViewGroup

class MainActivity : Activity() {
 private lateinit var status: TextView
 private lateinit var midi: MidiManager
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
   val cue = Button(this).apply { text = "CUE" }
   val play = Button(this).apply { text = "PLAY / PAUSE" }
   deck.addView(cue); deck.addView(play)
   val pitch = SeekBar(this).apply { max = 200; progress = 100 }
   deck.addView(pitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
   decks.addView(deck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
  }
  root.addView(decks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  val refresh = Button(this).apply { text = "BUSCAR CONTROLADORA MIDI"; setOnClickListener { scanMidi() } }
  root.addView(refresh)
  setContentView(root)
  scanMidi()
 }
 private fun scanMidi() {
  val devices = midi.devices
  status.text = if (devices.isEmpty()) "No se detectaron dispositivos MIDI. Conectá la consola por USB OTG." else
   devices.joinToString("\n") { MidiDiagnostics.deviceLabel(it.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME), it.inputPortCount, it.outputPortCount) }
 }
}
