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

## v0.4.0 USB AUDIO: Mixtrack Pro audio output

- USB MIDI and USB audio are separate Android devices. Successful MIDI connection does not guarantee that Android recognizes the audio output.
- Use AudioManager.getDevices(GET_DEVICES_OUTPUTS) to detect an actual USB audio sink, preferring a device named Mixtrack/Numark.
- On both MediaPlayers, request the USB audio device via setPreferredDevice when loading tracks, starting playback and reconnecting USB audio.
- Verify the actual audio destination via MediaPlayer.getRoutedDevice when playback starts, rather than assuming an accepted request implies that audio is routed.
- Pause if Android routes to a different output, instead of inadvertently playing through the tablet speaker.
- Green UI status and manual "DETECTAR SALIDA USB AUDIO" button provide routing diagnostics. USB audio hotplug re-applies routing.
- Android 9+ required for explicit MediaPlayer route preference; no separate app-level USB Audio permission is needed when Android exposes it as an AudioDeviceInfo.
- Connect speakers to the original Mixtrack Pro rear **MASTER RCA OUTPUT 1**; USB audio channels 1/2 are master. Cue/headphones use the other stereo pair (3/4), which cannot be separated with this MediaPlayer-based prototype.
- If output is not detected, check USB OTG and turn OFF Android developer option "Disable USB audio routing". Android's audio policy may refuse unsupported USB audio devices; an app cannot override that.


## v0.5.0: audio USB mediante AudioTrack (compilación #20)
- El motor PCM **ya no reproduce audio con MediaPlayer**. Usa MediaExtractor y MediaCodec para decodificar los archivos seleccionados a PCM de 16 bits y AudioTrack (modo streaming) para transmitirlo.
- Hay una instancia AudioTrack para cada deck. La mezcla la realiza el mezclador de audio de Android; volumen, crossfader, pitch (cambio de frecuencia), y EQ están asociados a las sesiones del motor PCM.
- UsbAudioRouter consulta AudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS), prioriza TYPE_USB_DEVICE / TYPE_USB_HEADSET de Numark y llama a AudioTrack.setPreferredDevice. También verifica AudioTrack.getRoutedDevice durante la reproducción; no envía el sonido al altavoz de la tablet silenciosamente.
- El diagnóstico de audio inspecciona además UsbManager.deviceList para buscar interfaces USB Audio Class / AudioStreaming (clase 1, subclase 2).
- Si la interfaz aparece en el bus USB **pero no está habilitada como salida por el sistema Android**, AudioTrack no puede forzarla ni habilitar interfaces USB de audio que no tengan driver del sistema. En ese caso la app muestra el motivo; desactivar la opción de desarrollador «Inhabilitar enrutamiento de audio USB» puede ayudar.
- Los dos reproductores requieren archivos de audio mono o estéreo que puedan decodificarse a PCM16 con el decodificador del dispositivo. Esta versión no implementa ASIO, salida exclusiva ni canales USB 3/4 para preescucha independiente.
- Un BUILD SUCCESSFUL verifica el compilado, **no** la presencia de salida de audio USB ni el sonido en la consola real.


## Compilación #21: PCM16 estéreo 44100 Hz y modos seguros
Se corrigió la creación de AudioTrack: antes intentaba utilizar directamente el sample rate y el número de canales del archivo, lo que daba ERROR_BAD_VALUE en algunos dispositivos.

Ahora se prueban, en orden, 44100 Hz estéreo PCM16, 48000 Hz estéreo PCM16, 44100 Hz mono PCM16, 48000 Hz mono PCM16 y 32000 Hz estéreo PCM16. Cada modo se valida con AudioTrack.getMinBufferSize y AudioTrack.STATE_INITIALIZED. Si Android rechaza uno, se libera la instancia y se prueba el siguiente sin bloquear la carga.

MediaExtractor + MediaCodec decodifican el archivo; Pcm16Resampler convierte los frames al formato PCM16 y a la frecuencia de salida seleccionados, manteniendo la interpolación entre bloques. El mensaje de carga muestra el formato efectivo. Hay pruebas unitarias para resampleo 48 kHz -> 44.1 kHz, entrada mono, salida mono de respaldo y continuidad entre buffers. Los fallos del decodificador se muestran en el indicador AUDIO PCM.

NOTA: estos cambios corrigen el error de formato y de búfer PCM. NO hacen aparecer automáticamente una salida USB que Android no publica en AudioManager. AudioTrack.setPreferredDevice sólo funciona con una salida AudioDeviceInfo real, y no permite ignorar la política de audio del sistema. Compilación verde tampoco confirma audio físico sin pruebas en la tablet.

## v0.7.0, compilacion #22: playback failover and auto audio routing

- PcmTrackEngine: MediaExtractor + MediaCodec + PCM16 AudioTrack. When getMinBufferSize returns ERROR (-1), do not abort: try bounded fixed buffers for 44100 and 48000 Hz, stereo and mono, both AudioTrack.Builder and legacy STREAM_MUSIC constructor.
- UsbPcmPlayer: wraps the PCM engine and transparently falls back to standard Android MediaPlayer if AudioTrack is unavailable or decoding fails. Keeps two independent decks, transport, pitch, volume, device preference, and playback position.
- A "MOTOR DEL SISTEMA (RESPALDO)" button manually switches loaded decks to Android's MediaPlayer to diagnose failure even when AudioTrack initialized but is silent.
- UsbAudioRouter: USB is preferred, not mandatory. If Android enumerates no USB sink, the application allows playback using the device's default output, usually tablet speakers. No automatic pause merely because an output device differs from USB.
- PcmBufferSizingTest checks that a -1 minimum buffer still produces usable bounded candidates.

Limitations: BUILD SUCCESSFUL verifies compilation and unit tests, not that physical audio outputs produce sound. If Android's audio service has no functioning output at all, neither AudioTrack nor MediaPlayer can override the OS. MIDI mapping from previous versions remains unchanged.
