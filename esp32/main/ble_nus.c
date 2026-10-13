/*
 * Nordic UART Service (NUS) peripheral over NimBLE.
 *
 * Incoming writes on RX are assembled line-by-line (terminated by '\n') and
 * forwarded to the HID keyboard command queue. A short "OK" is notified back on
 * TX for each processed line.
 */
#include "ble_nus.h"

#include <string.h>

#include "esp_log.h"
#include "esp_err.h"
#include "nvs_flash.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"

#include "hid_keyboard.h"

/* Defined in the NimBLE store/config component but no longer declared in its
 * public header (ESP-IDF >= 5.1). Declared here, as the bleprph example does. */
void ble_store_config_init(void);

static const char *TAG = "ble_nus";

#define DEVICE_NAME  "ESP32-S3-KBD"
#define RX_BUF_SIZE  512

/* NUS UUIDs (little-endian byte order for NimBLE). */
static const ble_uuid128_t nus_svc_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x4d, 0xe5, 0x0a, 0xe9,
                     0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e);
static const ble_uuid128_t nus_rx_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x4d, 0xe5, 0x0a, 0xe9,
                     0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e);
static const ble_uuid128_t nus_tx_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x4d, 0xe5, 0x0a, 0xe9,
                     0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e);

static uint8_t own_addr_type;
static uint16_t nus_tx_val_handle;
static uint16_t conn_handle = BLE_HS_CONN_HANDLE_NONE;

static char rx_buf[RX_BUF_SIZE];
static size_t rx_len;

static int nus_gatt_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                              struct ble_gatt_access_ctxt *ctxt, void *arg);
static int gap_event(struct ble_gap_event *event, void *arg);

static const struct ble_gatt_chr_def nus_chrs[] = {
    {
        .uuid = &nus_rx_uuid.u,
        .access_cb = nus_gatt_access_cb,
        .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
    },
    {
        .uuid = &nus_tx_uuid.u,
        .access_cb = nus_gatt_access_cb,
        .flags = BLE_GATT_CHR_F_NOTIFY,
        .val_handle = &nus_tx_val_handle,
    },
    { 0 },
};

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &nus_svc_uuid.u,
        .characteristics = nus_chrs,
    },
    { 0 },
};

static void notify_tx(const char *msg)
{
    if (conn_handle == BLE_HS_CONN_HANDLE_NONE || !nus_tx_val_handle)
        return;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(msg, strlen(msg));
    if (om) {
        ble_gatts_notify_custom(conn_handle, nus_tx_val_handle, om);
    }
}

static void handle_line(char *line)
{
    ESP_LOGI(TAG, "RX command: %s", line);
    hid_keyboard_submit(line);
    notify_tx("OK");
}

static int nus_gatt_access_cb(uint16_t ch, uint16_t attr,
                              struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)ch; (void)attr; (void)arg;

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR)
        return BLE_ATT_ERR_UNLIKELY;

    uint16_t om_len = OS_MBUF_PKTLEN(ctxt->om);
    uint8_t chunk[256];
    if (om_len > sizeof(chunk))
        om_len = sizeof(chunk);
    os_mbuf_copydata(ctxt->om, 0, om_len, chunk);

    for (uint16_t i = 0; i < om_len; i++) {
        char c = (char)chunk[i];
        if (c == '\n' || c == '\r') {
            if (rx_len > 0) {
                rx_buf[rx_len] = '\0';
                handle_line(rx_buf);
                rx_len = 0;
            }
            continue;
        }
        if (rx_len < RX_BUF_SIZE - 1) {
            rx_buf[rx_len++] = c;
        } else {
            ESP_LOGW(TAG, "RX buffer overflow, resetting");
            rx_len = 0;
        }
    }
    return 0;
}

static int gatt_svr_init(void)
{
    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_gatts_count_cfg(gatt_svcs);
    if (rc != 0)
        return rc;
    return ble_gatts_add_svcs(gatt_svcs);
}

static void start_advertising(void)
{
    struct ble_hs_adv_fields adv = {0};
    adv.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    adv.uuids128 = (ble_uuid128_t *)&nus_svc_uuid;
    adv.num_uuids128 = 1;
    adv.uuids128_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&adv);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_set_fields rc=%d", rc);
        return;
    }

    /* Name goes in the scan response (adv payload is full with the 128-bit UUID). */
    struct ble_hs_adv_fields rsp = {0};
    rsp.name = (uint8_t *)DEVICE_NAME;
    rsp.name_len = strlen(DEVICE_NAME);
    rsp.name_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_rsp_set_fields rc=%d", rc);
        return;
    }

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params,
                           gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_start rc=%d", rc);
        return;
    }
    ESP_LOGI(TAG, "advertising as \"%s\"", DEVICE_NAME);
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG, "central connected");
        } else {
            ESP_LOGW(TAG, "connect failed, status=%d", event->connect.status);
            start_advertising();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "central disconnected (reason=%d)", event->disconnect.reason);
        conn_handle = BLE_HS_CONN_HANDLE_NONE;
        rx_len = 0;
        hid_keyboard_release_all();
        start_advertising();
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        start_advertising();
        return 0;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU updated: %d", event->mtu.value);
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "subscribe: handle=%d notify=%d",
                 event->subscribe.attr_handle, event->subscribe.cur_notify);
        return 0;

    default:
        return 0;
    }
}

static void on_stack_reset(int reason)
{
    ESP_LOGE(TAG, "NimBLE reset, reason=%d", reason);
}

static void on_stack_sync(void)
{
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &own_addr_type);
    start_advertising();
}

static void nimble_host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void ble_nus_init(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(nimble_port_init());

    ble_hs_cfg.reset_cb = on_stack_reset;
    ble_hs_cfg.sync_cb = on_stack_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

    if (gatt_svr_init() != 0) {
        ESP_LOGE(TAG, "GATT init failed");
        return;
    }
    ble_svc_gap_device_name_set(DEVICE_NAME);
    ble_store_config_init();

    nimble_port_freertos_init(nimble_host_task);
    ESP_LOGI(TAG, "NimBLE started");
}
