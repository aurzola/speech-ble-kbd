"""Inject keystrokes as a virtual keyboard via /dev/uinput (evdev)."""

import threading
import time

from evdev import UInput, ecodes as e

from layout import KEYCODES, MOD_LSFT, MOD_RALT

PRESS_MS = 12
RELEASE_MS = 40

MODIFIER_EV = {MOD_LSFT: e.KEY_LEFTSHIFT, MOD_RALT: e.KEY_RIGHTALT}


class Injector:
    def __init__(self, name="speech-ble-kbd"):
        self._lock = threading.Lock()
        self._ui = UInput({e.EV_KEY: KEYCODES}, name=name)

    def close(self):
        with self._lock:
            self._ui.close()

    def _report(self, mod_mask, keycode, pressed):
        for bit, key in MODIFIER_EV.items():
            if mod_mask & bit:
                self._ui.write(e.EV_KEY, key, 1 if pressed else 0)
        self._ui.write(e.EV_KEY, keycode, 1 if pressed else 0)
        self._ui.syn()

    def _sleep(self, ms):
        time.sleep(ms / 1000.0)

    def tap(self, mod_mask, keycode):
        with self._lock:
            self._report(mod_mask, keycode, True)
            self._sleep(PRESS_MS)
            self._report(mod_mask, keycode, False)
            self._sleep(RELEASE_MS)

    def backspace(self, n):
        for _ in range(n):
            self.tap(0, e.KEY_BACKSPACE)