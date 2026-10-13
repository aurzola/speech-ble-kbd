/*
 * speech-ble-kbd — ESP32-S3 bridge.
 *
 * USB HID is brought up first so the host enumerates the keyboard, then the BLE
 * NUS peripheral starts advertising. Text received over BLE is typed over USB.
 */
#include "esp_log.h"

#include "hid_keyboard.h"
#include "ble_nus.h"

static const char *TAG = "app";

void app_main(void)
{
    ESP_LOGI(TAG, "speech-ble-kbd starting (ESP32-S3)");

    hid_keyboard_init();
    ble_nus_init();

    ESP_LOGI(TAG, "ready: pair over BLE NUS, text will be typed over USB HID");
}
