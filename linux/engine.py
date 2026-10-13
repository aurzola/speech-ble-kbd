"""Text engine: turns protocol commands into keystrokes.

Faithful port of esp32/main/hid_keyboard.c. Protocol (newline terminated):
  S:<text>   set whole document (minimal diff via backspace + retype)
  T:<text>   append text
  B:<n>      press backspace n times
  R:<start>:<count>:<text>   replace a range
  C          clear (backspace everything)
  L:US|ES    select layout
"""

import logging
import queue
import threading

import layout as layout_mod

log = logging.getLogger("engine")

TXT_BUF_MAX = 256
CMD_BUF_MAX = 512


class Engine:
    def __init__(self, injector):
        self._inj = injector
        self._layout = layout_mod.Layout()
        self._buf = []  # mirror of normalized code points already typed
        self._q = queue.Queue()
        self._thread = threading.Thread(target=self._worker, daemon=True)
        self._thread.start()

    def submit(self, cmd):
        if len(cmd) >= CMD_BUF_MAX:
            log.warning("command too long, dropped")
            return
        self._q.put(cmd)

    def _worker(self):
        while True:
            cmd = self._q.get()
            try:
                self._process(cmd)
            except Exception:
                log.exception("failed processing %r", cmd)

    # ---- normalization --------------------------------------------------
    def _normalize(self, text):
        out = []
        for ch in text:
            cp = ord(ch)
            norm = self._layout.normalize(cp)
            if norm:
                out.append(norm)
            if len(out) >= TXT_BUF_MAX:
                break
        return out

    # ---- keystroke emission --------------------------------------------
    def _emit(self, cps):
        for cp in cps:
            for mod, kc in self._layout.taps(cp):
                self._inj.tap(mod, kc)

    def _backspaces(self, n):
        if n > 0:
            self._inj.backspace(n)

    # ---- command handling ----------------------------------------------
    def _process(self, cmd):
        cmd = cmd.strip('\r\n')
        if not cmd:
            return

        if cmd.startswith('L:'):
            code = cmd[2:]
            ok = self._layout.set_code(code)
            log.info("layout -> %s (%s)", self._layout.code(), "ok" if ok else "rejected")
            return

        if cmd == 'C':
            self._backspaces(len(self._buf))
            self._buf = []
            return

        if cmd == 'E':
            # Enter / newline. Treated as a line boundary: both sides drop the
            # tracked document so later diffs stay consistent (app resets its
            # mirror on Enter too).
            self._emit([0x0A])
            self._buf = []
            return

        if cmd.startswith('B:'):
            try:
                n = int(cmd[2:])
            except ValueError:
                n = 0
            if n > len(self._buf):
                n = len(self._buf)
            self._backspaces(n)
            self._buf = self._buf[:len(self._buf) - n]
            return

        if cmd.startswith('S:'):
            target = self._normalize(cmd[2:])
            prefix = 0
            while (prefix < len(self._buf) and prefix < len(target)
                   and self._buf[prefix] == target[prefix]):
                prefix += 1
            self._backspaces(len(self._buf) - prefix)
            self._emit(target[prefix:])
            self._buf = target
            return

        if cmd.startswith('T:'):
            add = self._normalize(cmd[2:])
            room = TXT_BUF_MAX - len(self._buf)
            if room <= 0:
                return
            add = add[:room]
            self._emit(add)
            self._buf.extend(add)
            return

        if cmd.startswith('R:'):
            parts = cmd[2:].split(':', 2)
            if len(parts) != 3:
                return
            try:
                start = int(parts[0])
                count = int(parts[1])
            except ValueError:
                return
            if start > len(self._buf):
                start = len(self._buf)
            if start + count > len(self._buf):
                count = len(self._buf) - start
            tail = self._buf[start + count:]
            rep = self._normalize(parts[2])
            self._backspaces(len(self._buf) - start)
            self._buf = self._buf[:start]
            self._emit(rep)
            self._buf.extend(rep)
            self._buf.extend(tail)
            self._emit(tail)
            if len(self._buf) > TXT_BUF_MAX:
                self._buf = self._buf[:TXT_BUF_MAX]
            return

        log.warning("unknown command: %r", cmd)