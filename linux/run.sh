#!/usr/bin/env bash
# Arranca el daemon Linux (periférico BLE NUS + teclado virtual).
#
#   ./run.sh            # logs normales
#   ./run.sh -v         # logs detallados (DEBUG)
#
# Cualquier argumento extra se pasa a ble_kbd.py (ver: ./run.sh --help).
# La primera vez crea el venv e instala requirements.txt automáticamente.
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

VENV=".venv"
PY="$VENV/bin/python"

if [[ ! -x "$PY" ]]; then
    echo "Creando entorno virtual en $VENV ..."
    python3 -m venv "$VENV"
    "$PY" -m pip install --quiet --upgrade pip
    "$PY" -m pip install --quiet -r requirements.txt
fi

if [[ ! -w /dev/uinput ]]; then
    echo "Aviso: sin acceso de escritura a /dev/uinput." >&2
    echo "Añade tu usuario al grupo 'input' y vuelve a iniciar sesión:" >&2
    echo "  sudo usermod -aG input \"\$USER\"" >&2
fi

exec "$PY" ble_kbd.py "$@"
