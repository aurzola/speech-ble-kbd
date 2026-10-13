idf_component_register(
    SRCS
        "app_main.c"
        "ble_nus.c"
        "hid_keyboard.c"
        "layout.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash
)
