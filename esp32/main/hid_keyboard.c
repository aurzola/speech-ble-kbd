/*
 * USB HID keyboard + text engine.
 *
 * Commands arrive as text on a FreeRTOS queue and are converted into USB HID
 * keyboard reports by a dedicated worker task, so slow BLE callbacks never
 * block on the per-keystroke timing.
 *
 * The engine keeps a mirror of the characters it has typed (in *logical* host
 * characters, independent of dead-key tap sequences) so partial dictation can
 * be corrected efficiently with minimal backspaces.
 */
#include "hid_keyboard.h"
#include "layout.h"

#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"

#include "esp_log.h"

#include "tinyusb.h"
#include "tinyusb_default_config.h"
#include "class/hid/hid_device.h"

static const char *TAG = "hid_kbd";

#define REPORT_ID_KEYBOARD 1
#define KEY_PRESS_MS       8
#define KEY_GAP_MS         6
#define CMD_QUEUE_LEN      64
#define TXT_BUF_MAX        256

/* -------------------------------------------------------------------------- */
/* USB descriptors                                                            */
/* -------------------------------------------------------------------------- */

#define TUSB_DESC_TOTAL_LEN (TUD_CONFIG_DESC_LEN + CFG_TUD_HID * TUD_HID_DESC_LEN)

const uint8_t hid_report_descriptor[] = {
    TUD_HID_REPORT_DESC_KEYBOARD(HID_REPORT_ID(REPORT_ID_KEYBOARD)),
};

const char *hid_string_descriptor[5] = {
    (char[]){0x09, 0x04},          // 0: language (English 0x0409)
    "speech-ble-kbd",              // 1: Manufacturer
    "ESP32-S3 Speech Keyboard",    // 2: Product
    "0001",                        // 3: Serial
    "HID Keyboard",                // 4: HID interface
};

static const uint8_t hid_configuration_descriptor[] = {
    TUD_CONFIG_DESCRIPTOR(1, 1, 0, TUSB_DESC_TOTAL_LEN, TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP, 100),
    TUD_HID_DESCRIPTOR(0, 4, false, sizeof(hid_report_descriptor), 0x81, 16, 10),
};

/* -------------------------------------------------------------------------- */
/* TinyUSB HID callbacks (required by the stack)                              */
/* -------------------------------------------------------------------------- */

uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance)
{
    (void)instance;
    return hid_report_descriptor;
}

uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id,
                               hid_report_type_t report_type, uint8_t *buffer, uint16_t reqlen)
{
    (void)instance; (void)report_id; (void)report_type; (void)buffer; (void)reqlen;
    return 0;
}

void tud_hid_set_report_cb(uint8_t instance, uint8_t report_id,
                           hid_report_type_t report_type, uint8_t const *buffer, uint16_t bufsize)
{
    (void)instance; (void)report_id; (void)report_type; (void)buffer; (void)bufsize;
}

/* -------------------------------------------------------------------------- */
/* Key helpers                                                                */
/* -------------------------------------------------------------------------- */

static void kbd_press(uint8_t modifier, uint8_t keycode)
{
    uint8_t keys[6] = { keycode, 0, 0, 0, 0, 0 };
    tud_hid_keyboard_report(REPORT_ID_KEYBOARD, modifier, keys);
}

static void kbd_release(void)
{
    tud_hid_keyboard_report(REPORT_ID_KEYBOARD, 0, NULL);
}

static void tap(uint8_t modifier, uint8_t keycode)
{
    kbd_press(modifier, keycode);
    vTaskDelay(pdMS_TO_TICKS(KEY_PRESS_MS));
    kbd_release();
    vTaskDelay(pdMS_TO_TICKS(KEY_GAP_MS));
}

static void emit_cp(uint32_t cp)
{
    key_tap_t taps[2];
    int n = layout_taps(cp, taps, 2);
    for (int i = 0; i < n; i++)
        tap(taps[i].modifier, taps[i].keycode);
}

static void emit_cps(const uint32_t *cps, size_t n)
{
    for (size_t i = 0; i < n; i++)
        emit_cp(cps[i]);
}

static void press_backspace(size_t n)
{
    for (size_t i = 0; i < n; i++)
        tap(0, HID_KEY_BACKSPACE);
}

/* -------------------------------------------------------------------------- */
/* Text engine                                                                */
/* -------------------------------------------------------------------------- */

/* Mirror of what is currently typed on the host (normalized code points). */
static uint32_t s_buf[TXT_BUF_MAX];
static size_t s_buf_len;

/* Decode one UTF-8 code point; returns number of bytes consumed. */
static int utf8_decode(const char *s, uint32_t *cp)
{
    const unsigned char *p = (const unsigned char *)s;
    if (p[0] < 0x80) {
        *cp = p[0];
        return 1;
    }
    if ((p[0] & 0xE0) == 0xC0 && (p[1] & 0xC0) == 0x80) {
        *cp = ((uint32_t)(p[0] & 0x1F) << 6) | (p[1] & 0x3F);
        return 2;
    }
    if ((p[0] & 0xF0) == 0xE0 && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80) {
        *cp = ((uint32_t)(p[0] & 0x0F) << 12) | ((uint32_t)(p[1] & 0x3F) << 6) | (p[2] & 0x3F);
        return 3;
    }
    *cp = 0xFFFD;
    return 1;
}

/* Normalize a UTF-8 string into (mappable) code points. Returns the count. */
static size_t normalize_utf8(const char *s, uint32_t *out, size_t max)
{
    size_t n = 0;
    while (*s && n < max) {
        uint32_t cp;
        s += utf8_decode(s, &cp);
        uint32_t norm = layout_normalize(cp);
        if (norm)
            out[n++] = norm;
    }
    return n;
}

/* S:<text> — set the document to exactly <text>, applying a minimal diff. */
static void cmd_set(const char *text)
{
    uint32_t target[TXT_BUF_MAX];
    size_t tlen = normalize_utf8(text, target, TXT_BUF_MAX);

    size_t prefix = 0;
    while (prefix < tlen && prefix < s_buf_len && s_buf[prefix] == target[prefix])
        prefix++;

    press_backspace(s_buf_len - prefix);
    emit_cps(target + prefix, tlen - prefix);

    memcpy(s_buf, target, tlen * sizeof(uint32_t));
    s_buf_len = tlen;
}

/* T:<text> — append text at the cursor. */
static void cmd_type(const char *text)
{
    uint32_t add[TXT_BUF_MAX];
    size_t alen = normalize_utf8(text, add, TXT_BUF_MAX);
    if (s_buf_len + alen > TXT_BUF_MAX)
        alen = TXT_BUF_MAX - s_buf_len;

    emit_cps(add, alen);
    memcpy(s_buf + s_buf_len, add, alen * sizeof(uint32_t));
    s_buf_len += alen;
}

/* B:<n> — delete n characters before the cursor. */
static void cmd_backspace(int n)
{
    if (n < 0)
        n = 0;
    if ((size_t)n > s_buf_len)
        n = (int)s_buf_len;

    press_backspace((size_t)n);
    s_buf_len -= (size_t)n;
}

/* R:<start>:<count>:<text> — replace count characters at start with text. */
static void cmd_replace(int start, int count, const char *text)
{
    if (start < 0)
        start = 0;
    if ((size_t)start > s_buf_len)
        start = (int)s_buf_len;
    if (count < 0)
        count = 0;
    if ((size_t)(start + count) > s_buf_len)
        count = (int)s_buf_len - start;

    uint32_t rep[TXT_BUF_MAX];
    size_t rlen = normalize_utf8(text, rep, TXT_BUF_MAX);

    size_t tail = s_buf_len - (size_t)(start + count);

    /* Physically: delete from `start` to the end, then retype rep + tail. */
    press_backspace(s_buf_len - (size_t)start);
    emit_cps(rep, rlen);
    emit_cps(s_buf + start + count, tail);

    /* Rebuild mirror: head + rep + tail. */
    uint32_t newbuf[TXT_BUF_MAX];
    size_t nl = 0;
    memcpy(newbuf + nl, s_buf, (size_t)start * sizeof(uint32_t));
    nl += (size_t)start;
    memcpy(newbuf + nl, rep, rlen * sizeof(uint32_t));
    nl += rlen;
    memcpy(newbuf + nl, s_buf + start + count, tail * sizeof(uint32_t));
    nl += tail;

    memcpy(s_buf, newbuf, nl * sizeof(uint32_t));
    s_buf_len = nl;
}

/* C — delete the whole document and reset the mirror. */
static void cmd_clear(void)
{
    press_backspace(s_buf_len);
    s_buf_len = 0;
}

/* -------------------------------------------------------------------------- */
/* Command queue + worker                                                     */
/* -------------------------------------------------------------------------- */

static QueueHandle_t s_cmd_queue;

static void process_command(const char *cmd)
{
    /* Layout can be set even when the USB host is not mounted. */
    if (cmd[0] == 'L' && cmd[1] == ':') {
        keyboard_layout_t layout;
        if (layout_from_code(cmd + 2, &layout)) {
            layout_set(layout);
            ESP_LOGI(TAG, "layout set to %s", layout_code(layout));
        } else {
            ESP_LOGW(TAG, "unknown layout: %s", cmd + 2);
        }
        return;
    }

    if (!hid_keyboard_mounted()) {
        ESP_LOGW(TAG, "USB host not mounted, dropping: %s", cmd);
        return;
    }

    if (cmd[0] == 'S' && cmd[1] == ':') {
        cmd_set(cmd + 2);
    } else if (cmd[0] == 'T' && cmd[1] == ':') {
        cmd_type(cmd + 2);
    } else if (cmd[0] == 'B' && cmd[1] == ':') {
        cmd_backspace(atoi(cmd + 2));
    } else if (cmd[0] == 'R' && cmd[1] == ':') {
        const char *p = cmd + 2;
        char *colon1 = strchr(p, ':');
        if (colon1) {
            int start = atoi(p);
            char *colon2 = strchr(colon1 + 1, ':');
            int count = atoi(colon1 + 1);
            const char *text = colon2 ? colon2 + 1 : "";
            cmd_replace(start, count, text);
        }
    } else if (cmd[0] == 'C') {
        cmd_clear();
    } else {
        ESP_LOGW(TAG, "unknown command: %s", cmd);
    }
}

static void hid_task(void *arg)
{
    (void)arg;
    char *cmd = NULL;
    while (1) {
        if (xQueueReceive(s_cmd_queue, &cmd, portMAX_DELAY) == pdTRUE) {
            if (cmd) {
                process_command(cmd);
                free(cmd);
                cmd = NULL;
            }
        }
    }
}

void hid_keyboard_submit(const char *command)
{
    if (!command || !s_cmd_queue)
        return;

    char *copy = strdup(command);
    if (!copy)
        return;

    /* Streaming updates (S:) are idempotent and supersede older ones, so a brief
     * back-pressure wait is fine; manual commands should never block the caller. */
    TickType_t wait = (command[0] == 'S' && command[1] == ':') ? pdMS_TO_TICKS(100) : 0;
    if (xQueueSend(s_cmd_queue, &copy, wait) != pdTRUE) {
        ESP_LOGW(TAG, "command queue full, dropping: %s", command);
        free(copy);
    }
}

void hid_keyboard_release_all(void)
{
    /* Reset the mirror without backspacing: the host keeps whatever it has. */
    s_buf_len = 0;
    if (hid_keyboard_mounted())
        kbd_release();
}

bool hid_keyboard_mounted(void)
{
    return tud_mounted();
}

/* -------------------------------------------------------------------------- */
/* Init                                                                       */
/* -------------------------------------------------------------------------- */

void hid_keyboard_init(void)
{
    s_cmd_queue = xQueueCreate(CMD_QUEUE_LEN, sizeof(char *));
    configASSERT(s_cmd_queue);

    ESP_LOGI(TAG, "installing TinyUSB HID keyboard");
    tinyusb_config_t tusb_cfg = TINYUSB_DEFAULT_CONFIG();
    tusb_cfg.descriptor.device = NULL;
    tusb_cfg.descriptor.full_speed_config = hid_configuration_descriptor;
    tusb_cfg.descriptor.string = hid_string_descriptor;
    tusb_cfg.descriptor.string_count = sizeof(hid_string_descriptor) / sizeof(hid_string_descriptor[0]);
#if (TUD_OPT_HIGH_SPEED)
    tusb_cfg.descriptor.high_speed_config = hid_configuration_descriptor;
#endif
    ESP_ERROR_CHECK(tinyusb_driver_install(&tusb_cfg));

    xTaskCreate(hid_task, "hid_kbd", 8192, NULL, 5, NULL);
    ESP_LOGI(TAG, "HID keyboard ready");
}
