# speech-ble-kbd (proof of concept)

Dicta con tu **teléfono Android** y el texto aparece como **pulsaciones de teclado** en
otra computadora. Hay **dos maneras** de recibir el texto en el host:

- **ESP32-S3** → teclado **USB HID** (funciona en cualquier SO, sin instalar nada).
- **Daemon Linux** → periférico **BLE** + teclado virtual `uinput` (sin hardware).

Inspirado en [SensorsIot/YouTube · ios-keyboard](https://github.com/SensorsIot/YouTube/tree/main/ios-keyboard),
pero re-implementado para **Android** como prueba de concepto.

```
Modo A (ESP32)
┌──────────┐   BLE NUS    ┌───────────┐   USB HID    ┌──────────┐
│ Android  │ ───────────▶ │  ESP32-S3 │ ───────────▶ │ Computer │
│ (dictado)│  S:hola\n    │  (esta PoC)│  keystrokes  │ (any OS) │
└──────────┘              └───────────┘              └──────────┘

Modo B (daemon Linux)
┌──────────┐   BLE NUS    ┌──────────────┐   uinput     ┌──────────────┐
│ Android  │ ───────────▶ │ linux/ble_kbd │ ───────────▶ │ Sesión Linux │
│ (dictado)│  S:hola\n    │ (periférico)  │  keycodes    │ (GNOME/Wayland)│
└──────────┘              └──────────────┘              └──────────────┘
```

En ambos modos la **app Android es la misma** y el protocolo es idéntico.

## Módulos

| Módulo   | Ruta      | Qué hace                                                            |
|----------|-----------|---------------------------------------------------------------------|
| Android  | `android/`| Dictado continuo (Vosk **offline** o `SpeechRecognizer`) + cliente BLE NUS |
| ESP32-S3 | `esp32/`  | Recibe NUS por BLE y lo convierte en USB HID (TinyUSB, teclado 6KRO)|
| Linux    | `linux/`  | Periférico NUS (BlueZ) + teclado virtual `uinput` (sin hardware)    |

## Protocolo (basado en el original, terminado en `\n`)

Todas las órdenes viajan como texto UTF-8 por la característica **RX** del NUS
y terminan con `\n`:

| Comando                        | Descripción                                              | Ejemplo              |
|--------------------------------|----------------------------------------------------------|----------------------|
| `S:<texto>`                    | Ajusta el documento (diff mínimo: borra lo cambiado y escribe) | `S:Hola mundo\n` |
| `T:<texto>`                    | Añade texto al final                                     | `T: adiós\n`         |
| `B:<n>`                        | Borra `n` caracteres (Backspace)                         | `B:5\n`              |
| `R:<ini>:<n>:<texto>`          | Reemplaza `n` caracteres desde `ini`                     | `R:0:5:Mundo\n`      |
| `C`                            | Borra todo el documento y resetea el espejo              | `C\n`                |
| `L:<US\|ES>`                    | Cambia el layout del teclado del host                    | `L:ES\n`             |

`S:` es la orden principal del dictado: la app envía el documento completo
(comprometido + parcial) y el host calcula el **diff**, así el dictado en vivo
solo pulsa las teclas necesarias para corregir.

El host responde (notify) con `OK` por la característica **TX** (la app lo muestra).

UUIDs estándar **Nordic UART Service (NUS)**:

```
Servicio : 6e400001-b5a3-f393-e0a9-e50e24dcca9e
RX (write): 6e400002-b5a3-f393-e0a9-e50e24dcca9e
TX (notify):6e400003-b5a3-f393-e0a9-e50e24dcca9e
```

## Puesta en marcha

Hay que elegir **un** host: el ESP32-S3 (Modo A) **o** el daemon Linux (Modo B).
La app Android es idéntica en ambos.

### 1. Host Modo A — ESP32-S3

```bash
cd esp32
idf.py set-target esp32s3
idf.py build
idf.py -p /dev/ttyUSB0 flash monitor
```

- Conecta el **puerto USB nativo** del ESP32-S3 (el de datos) a la computadora *host*:
  ese es el que actúa como teclado.
- Alimenta/monitorea por el otro puerto (UART), si tu placa tiene dos.
- Requiere **ESP-IDF v5.1+** y el componente gestionado `espressif/esp_tinyusb`
  (se descarga solo vía `main/idf_component.yml`).

### 2. Host Modo B — daemon Linux (sin ESP32)

Requiere BlueZ con rol *peripheral* + `LEAdvertisingManager1`, y acceso a
`/dev/uinput` (usuario en el grupo `input`).

```bash
cd linux
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
sudo usermod -aG input "$USER"      # una vez; luego cierra y abre sesión
.venv/bin/python ble_kbd.py         # anuncia NUS como 'ESP32-S3-KBD'
```

Detalles, layout del host y resolución de problemas: [`linux/README.md`](linux/README.md).

### 3. App Android (igual en ambos modos)

APK ya compilado: `android/app/build/outputs/apk/debug/app-debug.apk`.

Requisitos: **Android 8.0+ (API 26)**. Para las vías con cable, activa
**Depuración USB** (Ajustes → Opciones de desarrollador) y **autoriza** el equipo.

**Instalar por cable (recomendado):**
```bash
~/Android/Sdk/platform-tools/adb devices     # debe aparecer "device" (no "unauthorized")
~/Android/Sdk/platform-tools/adb install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
```

**Otras vías:**
- Con el teléfono conectado y autorizado: `cd android && ./gradlew installDebug`.
- Con **Android Studio**: abrir la carpeta `android/` y pulsar *Run*.
- **Sideload manual** (sin cable): copia el `.apk` al teléfono (USB, nube o
  `adb push`), ábrelo desde el gestor de archivos y permite *"Instalar apps
  desconocidas"* para esa app. Es un APK **debug** sin firmar; el aviso es normal.

**Compilar desde cero** (opcional): abre `android/` en Android Studio, o
`cd android && ./gradlew assembleDebug` (usa JDK 17; la ruta del SDK está en
`android/local.properties`).

Pasos en el teléfono:
1. Otorga permisos (micrófono + Bluetooth).
2. Pulsa **Conectar** → busca el host (ESP32 o daemon) por el servicio NUS.
3. Elige el **layout del PC** en el desplegable (por defecto `ES`).
4. Elige el **motor de dictado** y el **idioma del dictado** (reconocimiento).
5. Pulsa **Iniciar dictado** y habla: el texto parcial se escribe en vivo y se va
   **corrigiendo** mientras hablas; al terminar cada frase se confirma.

### Motores de dictado

- **Vosk (offline)** — reconocimiento en el propio teléfono, sin red, con
  streaming continuo (sin cortes entre frases). Necesita un modelo (~40 MB) que
  se descarga con el botón **Descargar modelo** (o a mano:
  `adb push vosk-model-small-es-0.42 /data/data/com.example.speechblekbd/files/models/`).
  Idiomas: **español** (`vosk-model-small-es-0.42`) e **inglés**
  (`vosk-model-small-en-us-0.15`).
- **Sistema (online)** — el `SpeechRecognizer` de Android (más preciso, requiere
  red). Se reinicia tras cada frase.

Ambos motores comparten la interfaz `DictationEngine`, así que el resto de la app
(protocolo `S:`, teclado del host) no cambia. La barra bajo **Iniciar dictado**
es un medidor de nivel del micrófono.

## Layouts de teclado

El host (ESP32 o daemon) envía **posiciones físicas** (usages HID / keycodes evdev);
el sistema operativo del PC las traduce según **su** layout. Por eso hay que
**configurar el PC con el mismo layout** que se elige en la app:

| Código | Host (PC)          | Acentos / símbolos                                        |
|--------|--------------------|-----------------------------------------------------------|
| `US`   | English (US)       | Translitera (á→a, é→e, ñ→n, ¿→?)                          |
| `ES`   | Español (España)   | Dead keys (`´`+vocal → á…) y AltGr (`@ # [ ] { } ~ \ \|`) |

La tabla ES sigue el `keymap_spanish.h` de QMK.

## Limitaciones de la PoC (a propósito)

- Solo dos layouts (`US`, `ES`). La PoC de streaming (`S:`) mantiene un espejo del
  texto en el host de hasta 256 caracteres; documentos más largos se reinician.
- Sin OTA, WiFi, ni portal cautivo (eso está en el proyecto original).
- Sin emparejamiento/seguridad BLE (NUS abierto, igual que el original).

## Estructura

```
speech-ble-kbd/
├── android/                       # app Android (Kotlin)
│   └── app/src/main/java/com/example/speechblekbd/
│       ├── MainActivity.kt        # UI + dictado continuo (streaming S:)
│       ├── ble/NusClient.kt       # cliente BLE NUS
│       └── speech/                # motores de dictado (DictationEngine)
│           ├── ContinuousSpeech.kt  # SpeechRecognizer de Android (online)
│           ├── VoskSpeechEngine.kt  # Vosk (offline) + AudioRecord
│           ├── EnergyVad.kt         # VAD por energía + medidor de nivel
│           └── VoskModelStore.kt    # descarga/instalación del modelo Vosk
├── esp32/                         # firmware ESP-IDF (C) — Modo A
│   └── main/
│       ├── app_main.c
│       ├── ble_nus.c/.h           # periférico NUS (NimBLE)
│       ├── hid_keyboard.c/.h      # cola + motor de texto (S:/T:/B:/R:/C/L:)
│       └── layout.c/.h            # layouts US/ES (dead keys + AltGr)
└── linux/                         # daemon Python — Modo B (sin ESP32)
    ├── ble_kbd.py                 # periférico NUS (bluez-peripheral) + advertising
    ├── engine.py                  # motor de texto (port de hid_keyboard.c)
    ├── layout.py                  # layouts US/ES → keycodes evdev
    ├── injector.py                # teclado virtual /dev/uinput
    └── requirements.txt
```
