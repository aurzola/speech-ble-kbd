/*
 * Keyboard layout tables.
 *
 * IMPORTANT: USB HID sends *physical key positions* (US-labelled usages). The
 * host OS maps them through whatever layout the user has configured, so the
 * selected layout here MUST match the host's keyboard layout.
 *
 *   LAYOUT_US  -> host keyboard set to "English (US)"; accents are transliterated.
 *   LAYOUT_ES  -> host keyboard set to "Spanish (Spain)"; accented letters use
 *                 dead keys and symbols use AltGr.
 *
 * The ES table mirrors QMK's keymap_spanish.h (KC_* -> HID usage codes).
 */
#include "layout.h"

#include <string.h>
#include <strings.h>

#define MOD_LSFT 0x02
#define MOD_RALT 0x40  /* AltGr (right Alt) */

/* HID usage codes (physical positions, US labels). */
#define K_A     0x04
#define K_1     0x1E
#define K_2     0x1F
#define K_3     0x20
#define K_4     0x21
#define K_5     0x22
#define K_6     0x23
#define K_7     0x24
#define K_8     0x25
#define K_9     0x26
#define K_0     0x27
#define K_ENTER 0x28
#define K_TAB   0x2B
#define K_SPACE 0x2C
#define K_MINS  0x2D
#define K_EQL   0x2E
#define K_LBRC  0x2F
#define K_RBRC  0x30
#define K_BSLS  0x31  /* \ and | */
#define K_NUHS  0x32  /* non-US # and ~ */
#define K_SCLN  0x33
#define K_QUOT  0x34
#define K_GRV   0x35
#define K_COMM  0x36
#define K_DOT   0x37
#define K_SLSH  0x38
#define K_NUBS  0x64  /* non-US \ and | (ISO extra key) */

static keyboard_layout_t s_layout = LAYOUT_US;

/* -------------------------------------------------------------------------- */
/* US layout                                                                  */
/* -------------------------------------------------------------------------- */

static bool us_ascii(char c, uint8_t *mod, uint8_t *kc)
{
    *mod = 0;
    if (c >= 'a' && c <= 'z') { *kc = K_A + (c - 'a'); return true; }
    if (c >= 'A' && c <= 'Z') { *mod = MOD_LSFT; *kc = K_A + (c - 'A'); return true; }
    if (c >= '1' && c <= '9') { *kc = K_1 + (c - '1'); return true; }
    if (c == '0')             { *kc = K_0; return true; }

    switch (c) {
    case ' ':  *kc = K_SPACE; return true;
    case '\n': case '\r': *kc = K_ENTER; return true;
    case '\t': *kc = K_TAB; return true;
    case '-':  *kc = K_MINS; return true;
    case '=':  *kc = K_EQL; return true;
    case '[':  *kc = K_LBRC; return true;
    case ']':  *kc = K_RBRC; return true;
    case '\\': *kc = K_BSLS; return true;
    case ';':  *kc = K_SCLN; return true;
    case '\'': *kc = K_QUOT; return true;
    case '`':  *kc = K_GRV; return true;
    case ',':  *kc = K_COMM; return true;
    case '.':  *kc = K_DOT; return true;
    case '/':  *kc = K_SLSH; return true;
    case '!':  *mod = MOD_LSFT; *kc = K_1; return true;
    case '@':  *mod = MOD_LSFT; *kc = K_2; return true;
    case '#':  *mod = MOD_LSFT; *kc = K_3; return true;
    case '$':  *mod = MOD_LSFT; *kc = K_4; return true;
    case '%':  *mod = MOD_LSFT; *kc = K_5; return true;
    case '^':  *mod = MOD_LSFT; *kc = K_6; return true;
    case '&':  *mod = MOD_LSFT; *kc = K_7; return true;
    case '*':  *mod = MOD_LSFT; *kc = K_8; return true;
    case '(':  *mod = MOD_LSFT; *kc = K_9; return true;
    case ')':  *mod = MOD_LSFT; *kc = K_0; return true;
    case '_':  *mod = MOD_LSFT; *kc = K_MINS; return true;
    case '+':  *mod = MOD_LSFT; *kc = K_EQL; return true;
    case '{':  *mod = MOD_LSFT; *kc = K_LBRC; return true;
    case '}':  *mod = MOD_LSFT; *kc = K_RBRC; return true;
    case '|':  *mod = MOD_LSFT; *kc = K_BSLS; return true;
    case ':':  *mod = MOD_LSFT; *kc = K_SCLN; return true;
    case '"':  *mod = MOD_LSFT; *kc = K_QUOT; return true;
    case '~':  *mod = MOD_LSFT; *kc = K_GRV; return true;
    case '<':  *mod = MOD_LSFT; *kc = K_COMM; return true;
    case '>':  *mod = MOD_LSFT; *kc = K_DOT; return true;
    case '?':  *mod = MOD_LSFT; *kc = K_SLSH; return true;
    default:   return false;
    }
}

/* Fold common Latin accents to ASCII for non-ES hosts. */
static char us_translit(uint32_t cp)
{
    switch (cp) {
    case 0x00E1: case 0x00E0: case 0x00E4: case 0x00E2: case 0x00E5: return 'a';
    case 0x00E9: case 0x00E8: case 0x00EB: case 0x00EA: return 'e';
    case 0x00ED: case 0x00EC: case 0x00EF: case 0x00EE: return 'i';
    case 0x00F3: case 0x00F2: case 0x00F6: case 0x00F4: return 'o';
    case 0x00FA: case 0x00F9: case 0x00FC: case 0x00FB: return 'u';
    case 0x00F1: return 'n';
    case 0x00E7: return 'c';
    case 0x00C1: case 0x00C0: case 0x00C4: case 0x00C2: return 'A';
    case 0x00C9: case 0x00C8: case 0x00CB: case 0x00CA: return 'E';
    case 0x00CD: case 0x00CC: case 0x00CF: case 0x00CE: return 'I';
    case 0x00D3: case 0x00D2: case 0x00D6: case 0x00D4: return 'O';
    case 0x00DA: case 0x00D9: case 0x00DC: case 0x00DB: return 'U';
    case 0x00D1: return 'N';
    case 0x00C7: return 'C';
    case 0x00BF: return '?';   /* ¿ */
    case 0x00A1: return '!';   /* ¡ */
    case 0x2018: case 0x2019: return '\'';
    case 0x201C: case 0x201D: return '"';
    case 0x2013: case 0x2014: return '-';
    case 0x2026: return '.';
    default: return 0;
    }
}

static uint32_t us_normalize(uint32_t cp)
{
    uint8_t mod, kc;
    if (cp < 0x80 && us_ascii((char)cp, &mod, &kc))
        return cp;
    char t = us_translit(cp);
    if (t && us_ascii(t, &mod, &kc))
        return (uint32_t)(unsigned char)t;
    return 0;
}

static int us_taps(uint32_t cp, key_tap_t *out, int max)
{
    uint8_t mod, kc;
    if (max < 1 || cp >= 0x80 || !us_ascii((char)cp, &mod, &kc))
        return 0;
    out[0].modifier = mod;
    out[0].keycode = kc;
    return 1;
}

/* -------------------------------------------------------------------------- */
/* ES (Spain) layout                                                          */
/* -------------------------------------------------------------------------- */

static int es_emit(uint32_t cp, key_tap_t *out, int max)
{
    int n = 0;
#define ADD(m, k) do { if (n < max) { out[n].modifier = (m); out[n].keycode = (k); n++; } } while (0)

    if (cp >= 0x80) {
        switch (cp) {
        /* dead acute (KC_QUOT) + base vowel */
        case 0x00E1: ADD(0, K_QUOT);        ADD(0, K_A);       return n;
        case 0x00E9: ADD(0, K_QUOT);        ADD(0, K_A + 4);   return n;
        case 0x00ED: ADD(0, K_QUOT);        ADD(0, K_A + 8);   return n;
        case 0x00F3: ADD(0, K_QUOT);        ADD(0, K_A + 14);  return n;
        case 0x00FA: ADD(0, K_QUOT);        ADD(0, K_A + 20);  return n;
        case 0x00C1: ADD(0, K_QUOT);        ADD(MOD_LSFT, K_A);      return n;
        case 0x00C9: ADD(0, K_QUOT);        ADD(MOD_LSFT, K_A + 4);  return n;
        case 0x00CD: ADD(0, K_QUOT);        ADD(MOD_LSFT, K_A + 8);  return n;
        case 0x00D3: ADD(0, K_QUOT);        ADD(MOD_LSFT, K_A + 14); return n;
        case 0x00DA: ADD(0, K_QUOT);        ADD(MOD_LSFT, K_A + 20); return n;
        /* dead diaeresis (Shift+KC_QUOT) + u */
        case 0x00FC: ADD(MOD_LSFT, K_QUOT); ADD(0, K_A + 20);        return n;
        case 0x00DC: ADD(MOD_LSFT, K_QUOT); ADD(MOD_LSFT, K_A + 20); return n;
        /* dedicated keys */
        case 0x00F1: ADD(0, K_SCLN);        return n;
        case 0x00D1: ADD(MOD_LSFT, K_SCLN); return n;
        case 0x00E7: ADD(0, K_NUHS);        return n;
        case 0x00C7: ADD(MOD_LSFT, K_NUHS); return n;
        case 0x00A1: ADD(0, K_EQL);         return n;  /* ¡ */
        case 0x00BF: ADD(MOD_LSFT, K_EQL);  return n;  /* ¿ */
        case 0x00B4: ADD(0, K_QUOT);        return n;  /* ´ */
        case 0x00A8: ADD(MOD_LSFT, K_QUOT); return n;  /* ¨ */
        case 0x00BA: ADD(0, K_GRV);         return n;  /* º */
        case 0x00AA: ADD(MOD_LSFT, K_GRV);  return n;  /* ª */
        case 0x20AC: ADD(MOD_RALT, K_5);    return n;  /* € */
        case 0x00AC: ADD(MOD_RALT, K_6);    return n;  /* ¬ */
        default: return 0;
        }
    }

    char c = (char)cp;
    if (c >= 'a' && c <= 'z') { ADD(0, K_A + (c - 'a')); return n; }
    if (c >= 'A' && c <= 'Z') { ADD(MOD_LSFT, K_A + (c - 'A')); return n; }
    if (c >= '1' && c <= '9') { ADD(0, K_1 + (c - '1')); return n; }
    if (c == '0')             { ADD(0, K_0); return n; }

    switch (c) {
    case ' ':  ADD(0, K_SPACE); break;
    case '\n': case '\r': ADD(0, K_ENTER); break;
    case '\t': ADD(0, K_TAB); break;
    case '\'': ADD(0, K_MINS); break;          /* ES_QUOT */
    case '+':  ADD(0, K_RBRC); break;          /* ES_PLUS */
    case '-':  ADD(0, K_SLSH); break;          /* ES_MINS */
    case ',':  ADD(0, K_COMM); break;
    case '.':  ADD(0, K_DOT); break;
    case ';':  ADD(MOD_LSFT, K_COMM); break;   /* ES_SCLN */
    case ':':  ADD(MOD_LSFT, K_DOT); break;    /* ES_COLN */
    case '_':  ADD(MOD_LSFT, K_SLSH); break;   /* ES_UNDS */
    case '"':  ADD(MOD_LSFT, K_2); break;
    case '(':  ADD(MOD_LSFT, K_8); break;
    case ')':  ADD(MOD_LSFT, K_9); break;
    case '=':  ADD(MOD_LSFT, K_0); break;
    case '&':  ADD(MOD_LSFT, K_6); break;
    case '/':  ADD(MOD_LSFT, K_7); break;
    case '?':  ADD(MOD_LSFT, K_MINS); break;   /* ES_QUES */
    case '<':  ADD(0, K_NUBS); break;
    case '>':  ADD(MOD_LSFT, K_NUBS); break;
    case '!':  ADD(MOD_LSFT, K_1); break;
    case '*':  ADD(MOD_LSFT, K_RBRC); break;   /* ES_ASTR */
    case '$':  ADD(MOD_LSFT, K_4); break;
    case '%':  ADD(MOD_LSFT, K_5); break;
    case '^':  ADD(MOD_LSFT, K_LBRC); break;   /* ES_CIRC */
    case '@':  ADD(MOD_RALT, K_2); break;      /* ES_AT */
    case '#':  ADD(MOD_RALT, K_3); break;      /* ES_HASH */
    case '|':  ADD(MOD_RALT, K_1); break;      /* ES_PIPE */
    case '~':  ADD(MOD_RALT, K_4); break;      /* ES_TILD */
    case '[':  ADD(MOD_RALT, K_LBRC); break;   /* ES_LBRC */
    case ']':  ADD(MOD_RALT, K_RBRC); break;   /* ES_RBRC */
    case '{':  ADD(MOD_RALT, K_QUOT); break;   /* ES_LCBR */
    case '}':  ADD(MOD_RALT, K_NUHS); break;   /* ES_RCBR */
    case '\\': ADD(MOD_RALT, K_GRV); break;    /* ES_BSLS */
    default: return 0;
    }
    return n;
#undef ADD
}

static uint32_t es_normalize(uint32_t cp)
{
    key_tap_t tmp[2];
    return es_emit(cp, tmp, 2) > 0 ? cp : 0;
}

/* -------------------------------------------------------------------------- */
/* Public API                                                                 */
/* -------------------------------------------------------------------------- */

void layout_set(keyboard_layout_t layout)
{
    if (layout >= 0 && layout < LAYOUT_COUNT)
        s_layout = layout;
}

keyboard_layout_t layout_get(void)
{
    return s_layout;
}

bool layout_from_code(const char *code, keyboard_layout_t *out)
{
    if (!code || !out)
        return false;
    if (strcasecmp(code, "US") == 0) { *out = LAYOUT_US; return true; }
    if (strcasecmp(code, "ES") == 0) { *out = LAYOUT_ES; return true; }
    return false;
}

const char *layout_code(keyboard_layout_t layout)
{
    return layout == LAYOUT_ES ? "ES" : "US";
}

uint32_t layout_normalize(uint32_t cp)
{
    return s_layout == LAYOUT_ES ? es_normalize(cp) : us_normalize(cp);
}

int layout_taps(uint32_t cp, key_tap_t *out, int max)
{
    return s_layout == LAYOUT_ES ? es_emit(cp, out, max) : us_taps(cp, out, max);
}
