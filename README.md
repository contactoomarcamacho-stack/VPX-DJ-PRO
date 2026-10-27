# VPX DJ PRO
Aplicación Android experimental para tableta HONOR Pad X8b y controladora Numark Mixtrack Pro (primera generación).

## Estado
Versión inicial de diagnóstico: interfaz horizontal de dos decks y búsqueda de dispositivos Android MIDI.
**Todavía no reproduce ni mezcla audio, no implementa mapeo MIDI ni garantiza compatibilidad con la Mixtrack Pro.** Los botones son marcadores visuales.

## Compilar
GitHub Actions > Build Android APK > Run workflow. Descargar el artefacto VPX-DJ-PRO-debug.

## Prueba
Conectar la controladora mediante adaptador USB-C OTG (idealmente hub alimentado). Abrir la app y pulsar BUSCAR CONTROLADORA MIDI. Informar el texto de diagnóstico. Si Android MIDI no la enumera, se investigará acceso USB Host directo y su protocolo.

## Próximas etapas
1. Identificación USB VID/PID y captura segura de eventos MIDI.
2. Mapeo de jog wheels, pitch, crossfader, EQ y transporte.
3. Motor de audio de dos decks, selección de canciones y cue.
4. Enrutamiento de audio USB y latencia, con pruebas en hardware real.
