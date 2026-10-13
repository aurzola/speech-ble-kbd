"""Keyboard layouts US/ES, port of esp32/main/layout.c.

Important: this daemon emits *physical key positions* (same model as the
firmware). The Wayland compositor maps them through the host's configured
keyboard layout, so the selected layout here MUST match the host layout:
  - US -> GNOME set to "English (US)"; accents are transliterated.
  - ES -> GNOME set to "Spanish (Spain)"; dead keys + AltGr.
"""

from evdev import ecodes as e

MOD_LSFT = 0x02
MOD_RALT = 0x40  # AltGr (right Alt)

# Physical key positions (same keys as the firmware, translated to evdev via
# the kernel's HID->evdev table: drivers/hid/hid-input.c hid_keyboard[]).
K_A = e.KEY_A          # 30
K_1 = e.KEY_1          # 2
K_0 = e.KEY_0          # 11
for _n in range(2, 10):
    globals()[f"K_{_n}"] = getattr(e, f"KEY_{_n}")
K_ENTER = e.KEY_ENTER  # 28
K_TAB = e.KEY_TAB      # 15
K_SPACE = e.KEY_SPACE  # 57
K_MINS = e.KEY_MINUS        # 12   (HID 0x2D ->)
K_EQL = e.KEY_EQUAL         # 13   (HID 0x2E ->)
K_LBRC = e.KEY_LEFTBRACE    # 26
K_RBRC = e.KEY_RIGHTBRACE   # 27
K_BSLS = e.KEY_BACKSLASH    # 43   (HID 0x31 ->)
K_NUHS = e.KEY_BACKSLASH    # 43   (HID 0x32, non-US # ~ -> also 43)
K_SCLN = e.KEY_SEMICOLON    # 39
K_QUOT = e.KEY_APOSTROPHE   # 40
K_GRV = e.KEY_GRAVE         # 41
K_COMM = e.KEY_COMMA        # 51
K_DOT = e.KEY_DOT           # 52
K_SLSH = e.KEY_SLASH        # 53
K_NUBS = e.KEY_102ND        # 86   (HID 0x64, ISO extra key)

# evdev keycodes are laid out by physical QWERTY position, NOT alphabetically
# (KEY_A=30, KEY_B=48, KEY_C=46, ...), so map letters explicitly.
LETTER_KC = {c: getattr(e, "KEY_" + c.upper()) for c in "abcdefghijklmnopqrstuvwxyz"}

# Accented vowel -> base vowel, for dead-key sequences.
_ACCENT_BASE = {
    0x00E1: 'a', 0x00E9: 'e', 0x00ED: 'i', 0x00F3: 'o', 0x00FA: 'u',
    0x00C1: 'a', 0x00C9: 'e', 0x00CD: 'i', 0x00D3: 'o', 0x00DA: 'u',
}

KEYCODES = [
    K_A, K_1, K_0, K_ENTER, K_TAB, K_SPACE, K_MINS, K_EQL, K_LBRC, K_RBRC,
    K_BSLS, K_NUHS, K_SCLN, K_QUOT, K_GRV, K_COMM, K_DOT, K_SLSH, K_NUBS,
    e.KEY_BACKSPACE,
    e.KEY_LEFTSHIFT, e.KEY_RIGHTALT,
] + list(LETTER_KC.values()) + list(range(K_1, K_1 + 10))


class Layout:
    def __init__(self):
        self.es = False

    # ---- US -------------------------------------------------------------
    @staticmethod
    def us_ascii(c):
        if c.isascii() and c.isalpha():
            return (MOD_LSFT if c.isupper() else 0, LETTER_KC[c.lower()])
        if '1' <= c <= '9':
            return (0, K_1 + int(c) - 1)
        if c == '0':
            return (0, K_0)
        table = {
            ' ': K_SPACE, '\n': K_ENTER, '\r': K_ENTER, '\t': K_TAB,
            '-': K_MINS, '=': K_EQL, '[': K_LBRC, ']': K_RBRC, '\\': K_BSLS,
            ';': K_SCLN, "'": K_QUOT, '`': K_GRV, ',': K_COMM, '.': K_DOT,
            '/': K_SLSH, '!': (K_1, True), '@': (K_2, True), '#': (K_3, True),
            '$': (K_4, True), '%': (K_5, True), '^': (K_6, True),
            '&': (K_7, True), '*': (K_8, True), '(': (K_9, True),
            ')': (K_0, True), '_': (K_MINS, True), '+': (K_EQL, True),
            '{': (K_LBRC, True), '}': (K_RBRC, True), '|': (K_BSLS, True),
            ':': (K_SCLN, True), '"': (K_QUOT, True), '~': (K_GRV, True),
            '<': (K_COMM, True), '>': (K_DOT, True), '?': (K_SLSH, True),
        }
        if c in table:
            entry = table[c]
            if isinstance(entry, tuple):
                kc, shift = entry
                return (MOD_LSFT if shift else 0, kc)
            return (0, entry)
        return None

    # Fold common Latin accents to ASCII for non-ES hosts.
    _TRANSLIT = {
        0x00E1: 'a', 0x00E0: 'a', 0x00E4: 'a', 0x00E2: 'a', 0x00E5: 'a',
        0x00E9: 'e', 0x00E8: 'e', 0x00EB: 'e', 0x00EA: 'e',
        0x00ED: 'i', 0x00EC: 'i', 0x00EF: 'i', 0x00EE: 'i',
        0x00F3: 'o', 0x00F2: 'o', 0x00F6: 'o', 0x00F4: 'o',
        0x00FA: 'u', 0x00F9: 'u', 0x00FC: 'u', 0x00FB: 'u',
        0x00F1: 'n', 0x00E7: 'c',
        0x00C1: 'A', 0x00C0: 'A', 0x00C4: 'A', 0x00C2: 'A',
        0x00C9: 'E', 0x00C8: 'E', 0x00CB: 'E', 0x00CA: 'E',
        0x00CD: 'I', 0x00CC: 'I', 0x00CF: 'I', 0x00CE: 'I',
        0x00D3: 'O', 0x00D2: 'O', 0x00D6: 'O', 0x00D4: 'O',
        0x00DA: 'U', 0x00D9: 'U', 0x00DC: 'U', 0x00DB: 'U',
        0x00D1: 'N', 0x00C7: 'C',
        0x00BF: '?', 0x00A1: '!',
        0x2018: "'", 0x2019: "'", 0x201C: '"', 0x201D: '"',
        0x2013: '-', 0x2014: '-', 0x2026: '.',
    }

    def us_normalize(self, cp):
        ch = chr(cp)
        if cp < 0x80 and self.us_ascii(ch):
            return cp
        t = self._TRANSLIT.get(cp)
        if t and self.us_ascii(t):
            return ord(t)
        return 0

    # ---- ES (Spain) -----------------------------------------------------
    def es_emit(self, cp):
        taps = []

        def add(mod, kc):
            taps.append((mod, kc))

        if cp >= 0x80:
            if cp in (0x00E1, 0x00E9, 0x00ED, 0x00F3, 0x00FA):
                add(0, K_QUOT)                       # dead acute
                add(0, LETTER_KC[_ACCENT_BASE[cp]])
            elif cp in (0x00C1, 0x00C9, 0x00CD, 0x00D3, 0x00DA):
                add(0, K_QUOT)
                add(MOD_LSFT, LETTER_KC[_ACCENT_BASE[cp]])
            elif cp == 0x00FC:
                add(MOD_LSFT, K_QUOT)                # dead diaeresis
                add(0, LETTER_KC['u'])
            elif cp == 0x00DC:
                add(MOD_LSFT, K_QUOT)
                add(MOD_LSFT, LETTER_KC['u'])
            elif cp == 0x00F1:
                add(0, K_SCLN)
            elif cp == 0x00D1:
                add(MOD_LSFT, K_SCLN)
            elif cp == 0x00E7:
                add(0, K_NUHS)
            elif cp == 0x00C7:
                add(MOD_LSFT, K_NUHS)
            elif cp == 0x00A1:
                add(0, K_EQL)                  # ¡
            elif cp == 0x00BF:
                add(MOD_LSFT, K_EQL)           # ¿
            elif cp == 0x00B4:
                add(0, K_QUOT)                 # ´
            elif cp == 0x00A8:
                add(MOD_LSFT, K_QUOT)          # ¨
            elif cp == 0x00BA:
                add(0, K_GRV)                  # º
            elif cp == 0x00AA:
                add(MOD_LSFT, K_GRV)           # ª
            elif cp == 0x20AC:
                add(MOD_RALT, K_5)             # €
            elif cp == 0x00AC:
                add(MOD_RALT, K_6)             # ¬
            else:
                return []
            return taps

        c = chr(cp)
        if c.isalpha():
            return [(MOD_LSFT if c.isupper() else 0, LETTER_KC[c.lower()])]
        if '1' <= c <= '9':
            return [(0, K_1 + int(c) - 1)]
        if c == '0':
            return [(0, K_0)]

        table = {
            ' ': K_SPACE, '\n': K_ENTER, '\r': K_ENTER, '\t': K_TAB,
            "'": (0, K_MINS),                                                  # ES_QUOT
            '+': (0, K_RBRC),                                                  # ES_PLUS
            '-': (0, K_SLSH),                                                  # ES_MINS
            ',': (0, K_COMM), '.': (0, K_DOT),
            ';': (MOD_LSFT, K_COMM), ':' : (MOD_LSFT, K_DOT),
            '_': (MOD_LSFT, K_SLSH), '"': (MOD_LSFT, K_2),
            '(': (MOD_LSFT, K_8), ')': (MOD_LSFT, K_9), '=': (MOD_LSFT, K_0),
            '&': (MOD_LSFT, K_6), '/': (MOD_LSFT, K_7),
            '?': (MOD_LSFT, K_MINS), '<': (0, K_NUBS), '>': (MOD_LSFT, K_NUBS),
            '!': (MOD_LSFT, K_1), '*': (MOD_LSFT, K_RBRC),
            '$': (MOD_LSFT, K_4), '%': (MOD_LSFT, K_5),
            '^': (MOD_LSFT, K_LBRC),
            '@': (MOD_RALT, K_2), '#': (MOD_RALT, K_3), '|': (MOD_RALT, K_1),
            '~': (MOD_RALT, K_4), '[': (MOD_RALT, K_LBRC),
            ']': (MOD_RALT, K_RBRC), '{': (MOD_RALT, K_QUOT),
            '}': (MOD_RALT, K_NUHS), '\\': (MOD_RALT, K_GRV),
        }
        if c in table:
            entry = table[c]
            if isinstance(entry, tuple):
                add(entry[0], entry[1])
            else:
                add(0, entry)
        else:
            return []
        return taps

    def es_normalize(self, cp):
        return cp if self.es_emit(cp) else 0

    # ---- Public API -----------------------------------------------------
    def set_code(self, code):
        code = (code or '').strip().upper()
        if code == 'US':
            self.es = False
            return True
        if code == 'ES':
            self.es = True
            return True
        return False

    def code(self):
        return 'ES' if self.es else 'US'

    def normalize(self, cp):
        return self.es_normalize(cp) if self.es else self.us_normalize(cp)

    def taps(self, cp):
        if self.es:
            return self.es_emit(cp)
        if cp < 0x80:
            r = self.us_ascii(chr(cp))
            return [r] if r else []
        return []