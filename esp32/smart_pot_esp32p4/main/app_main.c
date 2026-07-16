#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "bsp/esp-bsp.h"
#include "bsp/display.h"

#include "app_llm.h"
#include "app_cloud.h"
#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
#include "app_motion.h"
#endif
#include "app_sensors.h"
#include "app_time.h"
#include "app_ui.h"
#include "app_tts.h"
#include "app_voice.h"
#include "app_wifi.h"

#define BH1750_ONLY_I2C_TEST 0

static const char *TAG = "smart_pot";

#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
static void motion_event_cb(app_motion_event_t event,
                            const app_motion_state_t *state,
                            void *user_ctx)
{
    (void)user_ctx;
    ESP_LOGI(TAG, "Motion event=%d roll=%.1f pitch=%.1f moving=%d tilt=%u",
             event, state->roll_deg, state->pitch_deg,
             state->moving, state->tilt_level);
    app_cloud_update_motion(event, state);
}
#endif

static void log_memory_stats(const char *stage)
{
    ESP_LOGI(TAG,
             "Memory[%s]: internal free=%u largest=%u minimum=%u; PSRAM free=%u largest=%u",
             stage,
             (unsigned int)heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT),
             (unsigned int)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT),
             (unsigned int)heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT),
             (unsigned int)heap_caps_get_free_size(MALLOC_CAP_SPIRAM),
             (unsigned int)heap_caps_get_largest_free_block(MALLOC_CAP_SPIRAM));
}

static void sensor_update_cb(const app_plant_state_t *state, void *user_ctx)
{
    (void)user_ctx;
    static uint32_t last_touch_count;
    static int64_t last_touch_reaction_us;

    app_ui_update(state);
    app_llm_update_plant_state(state);
    app_cloud_update_plant_state(state);

    if (state->touch_count != last_touch_count) {
        int64_t now_us = esp_timer_get_time();
        if (now_us - last_touch_reaction_us > 3000000) {
            app_ui_play_touch_reaction();
            if (!app_tts_speak_text_no_followup("哎哟。")) {
                ESP_LOGW(TAG, "Touch reaction TTS queue failed");
            }
            last_touch_reaction_us = now_us;
        }
        last_touch_count = state->touch_count;
    }
}

static void init_nvs(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);
}

static void init_display(void)
{
    bsp_display_cfg_t cfg = {
        .lv_adapter_cfg = ESP_LV_ADAPTER_DEFAULT_CONFIG(),
        .rotation = ESP_LV_ADAPTER_ROTATE_90,
        .tear_avoid_mode = ESP_LV_ADAPTER_TEAR_AVOID_MODE_TRIPLE_PARTIAL,
        .touch_flags = {
            .swap_xy = 1,
            .mirror_x = 1,
            .mirror_y = 0,
        },
    };

    bsp_display_start_with_config(&cfg);
    bsp_display_backlight_on();

    ESP_ERROR_CHECK(bsp_display_lock(1000));
    app_ui_init();
    bsp_display_unlock();
}

void app_main(void)
{
    ESP_LOGI(TAG, "Starting ESP32-P4 smart emotional plant companion");
    init_nvs();
    log_memory_stats("after_nvs");
#if BH1750_ONLY_I2C_TEST
    ESP_LOGW(TAG, "BH1750-only I2C test mode: display touch, audio, voice, Wi-Fi, and LLM are disabled");
    app_sensors_start(NULL, NULL);
#else
    init_display();

    app_wifi_start();
    app_cloud_start();
    app_time_start();
    app_tts_start();
    app_llm_start();
    app_voice_start();
    app_sensors_start(sensor_update_cb, NULL);
#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
    app_motion_start(motion_event_cb, NULL);
#endif
#endif

    uint32_t uptime_seconds = 0;
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        uptime_seconds++;
        if (uptime_seconds == 15) {
            log_memory_stats("services_ready");
        }
    }
}
