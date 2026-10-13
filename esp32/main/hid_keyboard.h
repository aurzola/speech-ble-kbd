#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Install TinyUSB HID keyboard and start the worker task. */
void hid_keyboard_init(void);

/* True when the device is enumerated and mounted by a USB host. */
bool hid_keyboard_mounted(void);

/*
 * Queue a command (same wire protocol as the BLE NUS commands):
 *   "T:<text>"  -> type the text
 *   "B:<n>"     -> press Backspace n times
 *   "C"         -> release all keys
 * The string is copied; the caller keeps ownership.
 */
void hid_keyboard_submit(const char *command);

/* Release every held modifier / key (call on BLE disconnect). */
void hid_keyboard_release_all(void);

#ifdef __cplusplus
}
#endif
