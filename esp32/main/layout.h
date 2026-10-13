#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* A single keyboard report action (one key press + release). */
typedef struct {
    uint8_t modifier;
    uint8_t keycode;
} key_tap_t;

typedef enum {
    LAYOUT_US = 0,
    LAYOUT_ES = 1,
    LAYOUT_COUNT
} keyboard_layout_t;

void layout_set(keyboard_layout_t layout);
keyboard_layout_t layout_get(void);

/* Parse "US"/"ES" (case-insensitive). Returns false on unknown code. */
bool layout_from_code(const char *code, keyboard_layout_t *out);
const char *layout_code(keyboard_layout_t layout);

/*
 * Map an input code point to the code point that will actually appear on the
 * host (US transliterates accents to ASCII; ES keeps them for dead keys).
 * Returns 0 when the character cannot be produced -> it must be dropped.
 */
uint32_t layout_normalize(uint32_t cp);

/*
 * Fill `out` with the tap sequence that produces a *normalized* code point.
 * Most characters need 1 tap; ES dead keys need 2. Returns the tap count (0 =
 * unmappable).
 */
int layout_taps(uint32_t cp, key_tap_t *out, int max);

#ifdef __cplusplus
}
#endif
