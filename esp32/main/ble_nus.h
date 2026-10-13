#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/* Bring up NimBLE, advertise the Nordic UART Service and forward received
 * commands to the HID keyboard. Blocking-free; runs its own host task. */
void ble_nus_init(void);

#ifdef __cplusplus
}
#endif
