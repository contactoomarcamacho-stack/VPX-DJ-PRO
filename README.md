# VPX DJ PRO for Android

Android Studio project (Kotlin, Android MIDI API) for **Numark Mixtrack Pro original / first generation**.

## Build
- Android Studio with JDK 17
- Android SDK 35
- Open the repository root, allow Gradle sync, run `app` on Android 8+.
- GitHub Actions builds a debug APK and runs unit tests automatically.

## Implemented
- Native USB OTG MIDI discovery using `MidiManager`; connects to the MIDI device output port to receive controller events.
- Two local audio players (Android MediaPlayer) with track loading via Android Storage Access Framework, play/pause, cue-to-start, playback position indicators and pitch-speed changes.
- Preconfigured original Mixtrack Pro MIDI control mapping in `NumarkMixtrackProMap.kt`: PLAY, CUE, SYNC, STUTTER, channel volumes, EQ high/mid/low, relative gain, master volume, crossfader, tempo sliders, bend buttons and jog wheels.
- Crossfader equal-power volume curve and platform Equalizer effects where supported.
- MIDI event monitor and JUnit tests for control codes.

## Hardware mapping source
Original Mixxx mapping:
https://github.com/mixxxdj/mixxx/blob/main/res/controllers/Numark%20Mixtrack%20Pro.midi.xml

Numark hardware guide:
https://support.serato.com/hc/en-us/articles/10173741515663-Numark-Mixtrack-Pro-Quickstart-Guide

## Known limitations
This is not a full professional DJ engine. SYNC currently copies the other deck's speed and **does not analyze BPM or align beats**. Jogs use a simple seek/bend approximation, **not true scratch audio**. EQ depends on Android audio effects availability. Audio plays through Android's selected output, and independent USB master/headphone routing is **not implemented**. Real hardware compatibility and APK build must be verified before claiming production readiness. UI seekbar positions do not yet mirror every physical knob.

No MIDI runtime permission dialog is required for Android MidiManager; USB MIDI access is provided by the Android MIDI service when the device is exposed. USB audio routing is a separate concern.
