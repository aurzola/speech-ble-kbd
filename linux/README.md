# Linux BLE NUS → teclado virtual (alternativa sin ESP32)

Daemon que hace que **esta computadora Linux** se comporte como el firmware del
ESP32: anuncia el servicio **Nordic UART Service (NUS)** por Bluetooth LE, recibe
las mismas órdenes de texto de la app Android y las escribe como pulsaciones de
**teclado virtual** (`/dev/uinput`).

La app Android **no cambia**: sigue escaneando por el UUID del servicio NUS.

```
┌──────────┐   BLE NUS    ┌──────────────────┐   uinput     ┌──────────────┐
│ Android  │ ───────────▶ │  linux/ble_kbd.py │ ───────────▶ │ Sesión Linux │
│ (dictado)│  S:hola\n    │  (periférico GATT)│  keycodes    │ (GNOME/Wayland)│
└──────────┘              └──────────────────┘              └──────────────┘
```

## Archivos

| Archivo           | Rol                                                              |
|-------------------|------------------------------------------------------------------|
| `ble_kbd.py`      | Punto de entrada: servidor GATT NUS + advertising (bluez-peripheral) |
| `engine.py`       | Motor de texto (port de `esp32/main/hid_keyboard.c`): `S/T/B/R/C/E/L` |
| `layout.py`       | Layouts `US`/`ES` (port de `esp32/main/layout.c`) → keycodes evdev |
| `injector.py`     | Teclado virtual `/dev/uinput` (python-evdev)                     |
| `requirements.txt`| `bluez-peripheral==0.1.7`, `evdev==2.0.0`                        |

## Requisitos

- **BlueZ** en ejecución con rol *peripheral* y `LEAdvertisingManager1`. Comprueba:
  ```bash
  bluetoothctl show        # busca "Roles: ... peripheral" y "GattManager1"
  ```
- Adaptador encendido: `bluetoothctl power on`.
- Acceso a `/dev/uinput` (pertenece a `root:input`, `crw-rw----`): tu usuario debe
  estar en el grupo `input`.
- `python3` ≥ 3.9.
- Sesión **Wayland o X11**; el host aplica su propio layout de teclado.

## Instalación

```bash
cd linux
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Permiso para crear el teclado virtual (una sola vez; luego cierra y abre sesión):

```bash
sudo usermod -aG input "$USER"
# verifica tras reiniciar sesión:
id | grep -q input && echo "grupo input OK"
```

## Uso

**Con el guion** (crea el venv e instala dependencias la primera vez):

```bash
./linux/run.sh          # o: cd linux && ./run.sh
./linux/run.sh -v       # logs detallados; ./linux/run.sh --help para más opciones
```

**Manualmente:**

```bash
source linux/.venv/bin/activate    # o usa linux/.venv/bin/python directamente
python linux/ble_kbd.py            # añade -v para logs detallados
```

Salida esperada:

```
... INFO ble-kbd: advertising as 'ESP32-S3-KBD' (NUS 6e400001-...) on <host>
... INFO ble-kbd: ready - connect with the app
```

> **Aviso benigno:** verás un error
> `interface "org.bluez.LEAdvertisement1" does not have property "TxPower"` y un
> traceback de dbus-next. Es un fallo conocido de `bluez-peripheral 0.1.7` con
> BlueZ reciente: **no impide el advertising** (el registro termina bien). Ignóralo.

Luego, en el teléfono: abre la app, pulsa **Conectar** y **Iniciar dictado**.

Para introducir un **Enter**, di una de estas palabras al final de una frase:
`enter`, `intro`, `nueva línea`, `salto de línea`, `línea nueva` (sin distinguir
acentos ni mayúsculas). También puedes usar el botón **Enter** de la app.

## Layout del host

El daemon envía **posiciones físicas**; el SO las traduce con **su** layout. Por eso
el layout de GNOME debe coincidir con el elegido en la app:

| App  | GNOME                    | Acentos / símbolos                                        |
|------|--------------------------|-----------------------------------------------------------|
| `US` | English (US)             | Translitera (`á→a`, `é→e`, `ñ→n`, `¿→?`)                  |
| `ES` | Español (España)         | Dead keys (`´`+vocal → `á`) y AltGr (`@ # [ ] { } ~ \ \|`) |

La tabla `ES` está validada contra `/usr/share/X11/xkb/symbols/es`: `ñ`=`KEY_SEMICOLON`,
`ç`=`KEY_BACKSLASH`, `<`=`KEY_102ND`, `'`=`KEY_MINUS`, `+`=`KEY_RIGHTBRACE`, etc.

## Notas técnicas (para mantenimiento)

- **Keycodes evdev no son alfabéticos**: `KEY_A=30, KEY_B=48, KEY_C=46, …`. El
  mapeo letra→keycode está explícito en `layout.py` (`LETTER_KC`), derivado de la
  tabla `hid_keyboard[256]` del kernel (`drivers/hid/hid-input.c`).
- **Modificadores**: `MOD_LSFT` (`0x02`) → `KEY_LEFTSHIFT`; `MOD_RALT` (`0x40`,
  AltGr) → `KEY_RIGHTALT`.
- **API bluez-peripheral 0.1.7**: el *setter* de una característica recibe
  `(service, data, options)`; `char.changed(bytes)` solo notifica si el cliente
  hizo `StartNotify` (CCCD). Las características son atributos **de clase** y se
  descubren con `inspect.getmembers` (orden alfabético → `rx`=char0, `tx`=char1).
- El daemon responde `OK` por TX tras encolar cada comando (igual que el firmware).

## Limitaciones

- Solo layouts `US` y `ES`; espejo de documento de hasta 256 caracteres.
- Sin emparejamiento/seguridad BLE (NUS abierto, como el original).
- `bluez-peripheral 0.1.7` no expone eventos de conexión/desconexión (no hay
  "release all" al desconectar; el espejo persiste, lo cual mantiene la consistencia
  porque el texto sigue en el host).
