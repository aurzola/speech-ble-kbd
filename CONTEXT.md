# CONTEXT — estado del proyecto y del entorno

Documento de contexto para retomar el trabajo sin perder decisiones ni datos
verificados. Última actualización: P2 (Vosk offline) implementado y compilado
(rama `feature/p2-streaming-asr`), pendiente de probar en teléfono.

## Objetivo

Dictar en un **teléfono Android** y que el texto aparezca como **pulsaciones de
teclado** en una computadora. El proyecto tenía dos módulos (Android + ESP32-S3
por USB HID); esta sesión agrega un tercer modo: **un daemon en Linux que actúa
de periférico BLE NUS** (sin ESP32) y escribe con un teclado virtual `uinput`.

## Decisiones tomadas

- **BLE directo, app Android sin cambios** (opción elegida): el daemon emula el
  periférico NUS; *no* se modifica `NusClient.kt`. La app filtra por el UUID del
  servicio, no por nombre, así que da igual cómo se llame el anunciante.
- Inyección vía **`python-evdev` + `UInput`** emitiendo **keycodes evdev** (el
  layout lo aplica GNOME, igual que con ESP32+HID). Se descartó `ydotool`.
- El daemon reusa el **mismo protocolo** que el firmware (`S/T/B/R/C/L`, `\n`).

## Entorno de esta máquina

- OS Linux, sesión **Wayland + GNOME**.
- **BlueZ 5.72**; adaptador `hci0` (`F0:68:E3:AA:36:A1`) con `Roles: central` **y
  `peripheral`**, `GattManager1` y `LEAdvertisingManager1` presentes.
- `/dev/uinput` = `crw-rw---- root:input`; el usuario está en el grupo `input`.
- `python3` 3.12.3.
- Android SDK en `~/Android/Sdk` (platform-34, build-tools 34.0.0, platform-tools).
- ESP-IDF **v5.4.4** en `~/esp/esp-idf` (toolchains en `~/.espressif`).
- `adb` en `~/Android/Sdk/platform-tools/adb`.
- Venv del daemon: `linux/.venv` con `bluez-peripheral 0.1.7`, `dbus-next 0.2.3`,
  `evdev 2.0.0`.

## Estado por módulo

### Android (`android/`)
- **Construido**: `android/app/build/outputs/apk/debug/app-debug.apk` (5.8 MB).
- `applicationId=com.example.speechblekbd`, `minSdk=26`, `targetSdk=34`.
- Se repusieron `gradlew`, `gradlew.bat` y `gradle/wrapper/gradle-wrapper.jar`
  (no venían en el repo) y se creó `android/local.properties` con
  `sdk.dir=/home/alexander_urzola/Android/Sdk`.
- Rebuild: `cd android && ./gradlew assembleDebug` (usa JDK 17).
- **P2 (rama `feature/p2-streaming-asr`)**: dictado **Vosk offline** detrás de la
  interfaz `speech/DictationEngine.kt`. Nuevos archivos: `VoskSpeechEngine.kt`
  (`AudioRecord` 16 kHz mono + `Recognizer` streaming + `EnergyVad` + nivel),
  `VoskModelStore.kt` (descarga/descompresión de modelos, `filesDir/models/…`),
  `EnergyVad.kt`. `ContinuousSpeech` refactorizado para implementar la interfaz
  (mismos extras, ahora `id="system"`). UI: spinners de motor e idioma de
  dictado, estado del modelo y botón *Descargar modelo*, `LinearProgressIndicator`
  de nivel. Persistencia en `SharedPreferences` (`engine`, `dictation_lang`,
  `layout`). Dep: `com.alphacephei:vosk-android:0.3.47` (+ JNA transitivo).
  APK con Vosk ≈ 43 MB (libs nativas arm64-v8a/armeabi-v7a/x86/x86_64).
  Modelos: `vosk-model-small-es-0.42` / `vosk-model-small-en-us-0.15`.
  Probado en teléfono: descarga del modelo OK, motor arranca y se detiene sin
  crash (ver "Hechos verificados").

### ESP32 (`esp32/`)
- **Compila**: `esp32/build/speech_ble_kbd.bin` (505 KB). Fix aplicado en
  `esp32/main/ble_nus.c`: en IDF ≥5.1 `ble_store_config_init()` ya no se declara en
  su header; se añadió una declaración local (`void ble_store_config_init(void);`).
- Rebuild: `source ~/esp/esp-idf/export.sh && cd esp32 && idf.py set-target esp32s3 && idf.py build`.

### Linux (`linux/`) — NUEVO en esta sesión
- **Implementado y probado sin teléfono** (lógica + registro BLE).
- Archivos: `ble_kbd.py`, `engine.py`, `layout.py`, `injector.py`,
  `requirements.txt`, `README.md`.
- Pruebas realizadas:
  - Lógica `US`/`ES` con inyector falso (incluye dead keys ES, `ñ`, `ç`, AltGr).
  - Reensamblado de líneas NUS y `StartNotify`.
  - `uinput` crea el teclado y teclea de verdad.
  - El daemon arranca y registra GATT + advertising: `advertising as 'ESP32-S3-KBD'`.

## Hechos verificados (no volver a investigar)

- **Protocolo** (UTF-8, termina en `\n`): `S:` set (diff), `T:` append, `B:<n>`
  backspace, `R:<ini>:<n>:<texto>` replace, `C` clear, `E` enter (pulsa Enter y
  resetea el espejo de ambos lados), `L:US|ES` layout. La app responde `OK` por TX.
  `MAX_DOC_LEN=200` en la app; espejo del daemon = 256.
- **UUIDs NUS**: servicio `6e400001-…`, RX `6e400002-…` (write), TX `6e400003-…`
  (notify).
- **HID→evdev** (índice = usage HID; de `hid_input.c`): `0x04..0x1D`→`KEY_A..Z`
  pero **los keycodes evdev están por posición física, no alfabética**. Mapas
  puntuales usados: `0x28`→Enter(28), `0x2B`→Tab(15), `0x2C`→Space(57),
  `0x2D`→Minus(12), `0x2E`→Equal(13), `0x2F`→LeftBrace(26), `0x30`→RightBrace(27),
  `0x31`→Backslash(43), `0x32`→Backslash(43), `0x33`→Semicolon(39),
  `0x34`→Apostrophe(40), `0x35`→Grave(41), `0x36`→Comma(51), `0x37`→Dot(52),
  `0x38`→Slash(53), `0x64`→102ND(86). Letras: A=30,B=48,C=46,D=32,E=18,F=33,G=34,
  H=35,I=23,J=36,K=37,L=38,M=50,N=49,O=24,P=25,Q=16,R=19,S=31,T=20,U=22,V=47,W=17,
  X=45,Y=21,Z=44.
- **Layout ES** validado contra `/usr/share/X11/xkb/symbols/es`: `ñ`=KEY_SEMICOLON,
  `´`(dead acute)=KEY_APOSTROPHE, `ç`=KEY_BACKSLASH, `<`=KEY_102ND,
  `'`=KEY_MINUS, `?`=Shift+KEY_MINUS, `+`=KEY_RIGHTBRACE, `<`/`>`=KEY_102ND,
  y AltGr (`<AD11>`=bracketleft, `<AD12>`=bracketright, `<AC11>`=braceleft,
  `<BKSL>`=braceright, `<AE01>`=bar, `<AE02>`=at, `<AE03>`=numbersign,
  `<AE04>`=asciitilde).
- **bluez-peripheral 0.1.7**: no existe `peripheral.py`; se usa `Service` +
  `characteristic` + `Advertisement` + `Adapter` (de `util`). El setter recibe
  `(service, data, options)`. `Advertisement.register()` **no** tiene `unregister`.
  `Adapter` expone `get_powered()`, `get_name()` (son métodos, no atributos).
- **Aviso benigno**: BlueZ pide `TxPower` en `RegisterAdvertisement` y la librería
  no lo define → dbus-next imprime un traceback. **No** rompe el advertising.
- **Android: los callbacks de `BluetoothGattCallback` llegan en un hilo *binder***,
  no en el hilo principal. Tocar vistas (`listener.onStatus`) ahí lanza
  `CalledFromWrongThreadException` y **aborta el callback** (por eso nunca llegaba
  `onServicesDiscovered`). Solución aplicada: pasar el `Handler` del hilo principal
  a `connectGatt(context, false, cb, TRANSPORT_LE, PHY_LE_1M_MASK, handler)`
  (overload de 6 args, API 26+). Los callbacks de `ScanCallback` sí llegan en el
  hilo principal (por eso el escaneo sí actualizaba la UI).
- **End-to-end verificado con teléfono real** (2026-10-12): escaneo → `stateChange
  status=0 newState=2` → `discoverServices returned true` → `servicesDiscovered
  status=0` → `MTU=517 usable=514` → la app envía `L:US`, el daemon responde `OK`
  (UI: "BLE listo: NUS conectado / ESP32 → OK"). Envío manual de "Hola Mundo":
  el daemon recibe `S:Hola Mundo` y capturando `/dev/input/event*` del teclado
  virtual `speech-ble-kbd` se observan exactamente las teclas `Shift+H o l a Space
  Shift+M u n d o`. El diff del motor también quedó probado: un segundo `S:` que
  extiende el documento sólo emitió el delta (`Hola MundoHola Mundo`).
- **Android: una sola operación GATT pendiente a la vez**. Lanzar `requestMtu` +
  `writeDescriptor` (CCCD) + `writeCharacteristic` seguidos perdía el write (y
  dejaba `writeInProgress=true`, bloqueando la cola). Solución: encadenar
  `onServicesDiscovered → requestMtu` → `onMtuChanged → enableNotifications` →
  `onDescriptorWrite → onConnected` (que ya envía `L:<layout>`).
- **Enter por dictado**: la app detecta palabras clave en el resultado final
  (`enter`, `intro`, `nueva línea`, `salto de línea`, `línea nueva`; sin
  acentos/mayúsculas) y emite `E` en lugar de escribir esas palabras. También hay
  botón **Enter**. En el daemon `E` pulsa Enter y resetea el espejo (la app hace
  lo mismo con `committed`). Verificado: "uno enter dos" → teclas `u n o Enter d o s`.
- **Rotación**: `MainActivity` usa `android:configChanges="orientation|screenSize|
  screenLayout|keyboardHidden|smallestScreenSize"` para no recrearse al girar
  (conserva la conexión BLE y el espejo `committed`).
- **UI**: botones de acción como `MaterialButton` con `app:icon` + texto
  (`app:iconGravity="textStart"`), iconos vectoriales en `res/drawable/ic_*.xml`.
  Evita el desalineado del glifo `⏎` (fuente de respaldo con métricas distintas).
- **Vosk (P2)**: artefacto `com.alphacephei:vosk-android:0.3.47` (AAR 12.3 MB;
  libs arm64-v8a/armeabi-v7a/x86/x86_64) depende de `net.java.dev.jna:jna:5.13.0`
  (type `aar`, se resuelve transitivamente). API (verificada con `javap`):
  `Model(String)`, `Recognizer(Model,float)`, `acceptWaveForm(byte[],int)->boolean`,
  `getResult/getPartialResult/getFinalResult`, `setWords`, `setMaxAlternatives`,
  `close`; `LibVosk.setLogLevel(LogLevel)`; `org.vosk.android.StorageService`.
  URLs de modelos (HTTP 200): `https://alphacephei.com/vosk/models/` +
  `vosk-model-small-es-0.42.zip` (39.8 MB) / `vosk-model-small-en-us-0.15.zip`
  (41.2 MB). `assembleDebug` con Vosk OK (43 MB).
- **P2 verificado en el teléfono** (2026-10-17, Xiaomi `24117RN76L` Android 16):
  instalado el APK; la app arranca con los controles nuevos; al elegir
  *Motor = Vosk (offline)* el botón *Descargar modelo* aparece con "ausente"; la
  descarga llegó a 100% y el descomprimido dejó `files/models/vosk-model-small-es-0.42`
  (`am/ conf/ graph/ ivector/ README`); el estado pasó a "instalado". *Iniciar
  dictado* → `Cargando modelo…` → `AudioRecord` con `inputSource 6`
  (VOICE_RECOGNITION), 16 kHz mono, `capturing from built-in mic`, `Escuchando…`;
  *Detener* libera el `AudioRecord` sin errores (estado `Dictado detenido`). Sin
  crash. **Falta** probar reconocimiento con voz real (no se pudo hacer por adb).

## Cómo instalar la app en el teléfono

Requisitos: Android **8.0+** (API 26) y, para las vías con cable, **Depuración USB**
activada (Ajustes → Opciones de desarrollador) y el teléfono **autorizado**.

1. **Con cable (recomendado), instalar el APK ya compilado:**
   ```bash
   ~/Android/Sdk/platform-tools/adb devices          # confirma que aparece "device"
   ~/Android/Sdk/platform-tools/adb install -r \
     android/app/build/outputs/apk/debug/app-debug.apk
   ```
   (o `cd android && ./gradlew installDebug` con el teléfono conectado.)

2. **Con Android Studio:** abrir la carpeta `android/` y pulsar *Run*.

3. **Sin cable (sideload manual):** copia
   `android/app/build/outputs/apk/debug/app-debug.apk` al teléfono (USB, nube,
   `adb push`), ábrelo desde el gestor de archivos y permite
   *"Instalar apps desconocidas"* para esa app. Es un APK **debug** sin firmar
   para producción; es normal el aviso.

Tras instalar: concede **micrófono** y **Bluetooth**, empareja/conecta el daemon
(`Conectar`) y elige el mismo layout que en el host.

## Próximos pasos

1. **P2**: probar en el teléfono el motor **Vosk offline** (`feature/p2-streaming-asr`):
   descargar el modelo en la app (o `adb push`), elegir *Motor = Vosk (offline)* y
   verificar dictado continuo + medidor de nivel. Ajustar `EnergyVad.threshold`
   si el nivel no se mueve. (Compilado y APK verificado, device no conectado.)
2. **Hecho**: probado con teléfono real (dictado/escribir en el host) — ver
   "Hechos verificados". Falta solo afinar el layout de GNOME (`US` o `Español
   (España)`) para que coincida con el de la app.
3. Arrancar el daemon: `cd linux && .venv/bin/python ble_kbd.py` (o como unidad
   transitoria de usuario:
   `systemd-run --user --unit=speech-ble-kbd --collect --working-directory=$PWD/linux $PWD/linux/.venv/bin/python ble_kbd.py -v`;
   logs con `journalctl --user -u speech-ble-kbd -f`). Comprobar advertising con
   `busctl --system get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances` (debe ser `y 1`).
4. Si el advertising falla en otra máquina, activar `Experimental = true` en
   `/etc/bluetooth/main.conf` y reiniciar `bluetoothd`.
5. (Opcional) Detectar desconexión para hacer *release-all*; hoy no es necesario
   porque el espejo del daemon sigue siendo consistente con el host.

## Estructura

```
speech-ble-kbd/
├── android/   # app Kotlin (dictado: Vosk offline / sistema + cliente BLE NUS)
├── esp32/     # firmware ESP-IDF (periférico NUS + USB HID)
├── linux/     # daemon Python (periférico NUS + teclado uinput)
└── README.md  # visión general de los tres modos
```
