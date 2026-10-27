# VPX DJ PRO: Numark Mixtrack Pro (original)

Native Android/Kotlin dual-deck music player with Numark Mixtrack Pro USB MIDI integration.

## Build in GitHub Actions
Each push to main runs JUnit tests and the Android debug build (Java 17, Android SDK 35).
Download the artifact **VPX-DJ-PRO-debug** from the green workflow run.

## Hardware MIDI support
- Numark Mixtrack Pro first generation (not Mixtrack Pro II / FX).
- Actual MIDI CC/note numbers sourced from the original Mixxx mapping:
  https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Numark%20Mixtrack%20Pro.midi.xml
- **Android native MIDI** is the default and automatically opens *every output port* (hardware -> app).
  Ports and device handles are retained to avoid premature garbage collection.
- USB hotplug callback reconnects automatically.
- Streaming MIDI parser handles fragmented packets, running status and realtime messages.
- **USB DIRECTO** provides a second input method using Android USB Host, explicit permission
  and class-compliant USB-MIDI 4-byte event packets. Choose it when Android detects the
  controller but the MIDI monitor shows no data.
- Visible monitor displays status, controller number, value, action and source.
- Native outgoing MIDI LED updates for PLAY/Pause (when MIDI output is available).

## Audio/UI
Two decks: Android file picker, playback/pause, Cue, temporary pitch bend, pitch speed,
three-band Android Equalizer (when available), relative gain, channel volume,
equal-power crossfader, master volume, and progress/time indicators.
MIDI faders and knobs update their visual SeekBars.

## Important limitations
Successful compilation **does not prove hardware behavior** without a physical Numark/Android test.
This app does not have BPM detection or beat-aligned SYNC (SYNC copies pitch speed only).
The jog interaction is seek/bend, not low-latency time-stretch scratching.
USB master/cue separate channel routing, professional waveform decoding, and DJ-grade
audio engine are not implemented. Android hardware EQ support varies by device.

## Troubleshooting
1. Connect the controller with a data-capable USB OTG cable and external power if needed.
2. The top line should show an open MIDI port. Load audio into each deck.
3. Move hardware PLAY, volume and crossfader. The yellow monitor should show
   "MIDI RECIBIDO" and the corresponding note/CC value.
4. If the device name appears but no messages arrive, press USB DIRECTO and grant
   Android's USB permission; check that the monitor source changes to "USB DIRECTO".
5. If neither works, the USB interface may not be exposed to Android or there may be
   a host/USB power issue; connect the controller to a powered hub.

USB permissions are acquired through Android's USB Host API in direct mode.
The Android MIDI service does not require an application-level MIDI permission.
