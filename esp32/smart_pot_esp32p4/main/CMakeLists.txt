set(SMART_POT_SRCS
        app_main.c
        app_ui.c
        cat_planter_img.c
        home_static_bg_img.c
        schedule_static_bg_img.c
        emoji_stickers_img.c
        app_sensors.c
        app_wifi.c
        app_time.c
        app_memory.c
        app_volc_protocol.c
        app_llm.c
        app_asr.c
        app_tts.c
        app_voice.c
        app_cloud.c
)

if(CONFIG_SMART_POT_MPU6050_ENABLE)
    list(APPEND SMART_POT_SRCS app_motion.c)
endif()

idf_component_register(
    SRCS ${SMART_POT_SRCS}
    INCLUDE_DIRS .
    PRIV_REQUIRES esp_http_client esp_websocket_client mqtt json esp_wifi esp_netif esp_event esp-tls nvs_flash driver esp_timer esp_adc espressif__esp-sr esp32_p4_wifi6_touch_lcd_4_3
    EMBED_FILES assets/NotoSansSC-GB2312.ttf assets/Inkfree-ToDo.ttf
)
