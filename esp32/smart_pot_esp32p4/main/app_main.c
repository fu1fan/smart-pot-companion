#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_random.h"
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

#include <math.h>

#define BH1750_ONLY_I2C_TEST 0

static const char *TAG = "smart_pot";

#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
#ifndef CONFIG_SMART_POT_MPU6050_FALL_DEG
#define CONFIG_SMART_POT_MPU6050_FALL_DEG 50
#endif

static volatile uint32_t s_motion_event_count;

static const char *motion_event_name(app_motion_event_t event)
{
    switch (event) {
    case APP_MOTION_EVENT_TAP:
        return "tap";
    case APP_MOTION_EVENT_SHAKE:
        return "shake";
    case APP_MOTION_EVENT_FALLEN:
        return "fallen";
    case APP_MOTION_EVENT_FALL_RECOVERED:
        return "fall recovered";
    default:
        return "unknown";
    }
}

static const char *motion_reaction_name(app_motion_event_t event)
{
    switch (event) {
    case APP_MOTION_EVENT_TAP:
        return "voice only";
    case APP_MOTION_EVENT_SHAKE:
        return "swirl eyes";
    case APP_MOTION_EVENT_FALLEN:
        return "voice only";
    case APP_MOTION_EVENT_FALL_RECOVERED:
        return "voice only";
    default:
        return "none";
    }
}

static const char *motion_voice_text(app_motion_event_t event)
{
    switch (event) {
    case APP_MOTION_EVENT_TAP:
        return "轻轻敲到我啦。";
    case APP_MOTION_EVENT_SHAKE:
        return "有点晕啦，慢一点。";
    case APP_MOTION_EVENT_FALLEN:
        return "我摔倒啦快把我扶起来！";
    case APP_MOTION_EVENT_FALL_RECOVERED:
        return "我站稳啦。";
    default:
        return "我感觉到动了一下。";
    }
}

static void motion_debug_task(void *arg)
{
    (void)arg;

    while (true) {
        app_motion_state_t motion = {0};
        app_ui_motion_debug_state_t ui_state = {0};
        ui_state.valid = app_motion_get_state(&motion);
        if (ui_state.valid) {
            ui_state.accel_x_g = motion.accel_x_g;
            ui_state.accel_y_g = motion.accel_y_g;
            ui_state.accel_z_g = motion.accel_z_g;
            ui_state.gyro_x_dps = motion.gyro_x_dps;
            ui_state.gyro_y_dps = motion.gyro_y_dps;
            ui_state.gyro_z_dps = motion.gyro_z_dps;
            ui_state.roll_deg = motion.roll_deg;
            ui_state.pitch_deg = motion.pitch_deg;
            ui_state.tilt_delta_deg = motion.tilt_delta_deg;
            ui_state.tilt_trigger_deg = (float)CONFIG_SMART_POT_MPU6050_FALL_DEG;
            ui_state.event_count = s_motion_event_count;
            ui_state.tilt_level = motion.tilt_level;
            ui_state.accel_mag_g = sqrtf(motion.accel_x_g * motion.accel_x_g +
                                         motion.accel_y_g * motion.accel_y_g +
                                         motion.accel_z_g * motion.accel_z_g);
            ui_state.gyro_mag_dps = sqrtf(motion.gyro_x_dps * motion.gyro_x_dps +
                                          motion.gyro_y_dps * motion.gyro_y_dps +
                                          motion.gyro_z_dps * motion.gyro_z_dps);
        }
        app_ui_update_motion_debug(&ui_state);
        vTaskDelay(pdMS_TO_TICKS(250));
    }
}

static void motion_event_cb(app_motion_event_t event,
                            const app_motion_state_t *state,
                            void *user_ctx)
{
    (void)user_ctx;
    uint32_t event_count = ++s_motion_event_count;
    ESP_LOGI(TAG, "Motion event=%d count=%lu roll=%.1f pitch=%.1f tiltDelta=%.1f tilt=%u",
             event, (unsigned long)event_count, state->roll_deg, state->pitch_deg,
             state->tilt_delta_deg, state->tilt_level);

    switch (event) {
    case APP_MOTION_EVENT_TAP:
        app_ui_clear_motion_reaction();
        app_ui_set_dialog_status("Motion: tap");
        break;
    case APP_MOTION_EVENT_SHAKE:
        app_ui_play_motion_reaction(APP_UI_MOTION_REACTION_SHAKE, 2400);
        app_ui_set_dialog_status("Motion: shake");
        break;
    case APP_MOTION_EVENT_FALLEN:
        app_ui_clear_motion_reaction();
        app_ui_set_dialog_status("Motion: fallen");
        break;
    case APP_MOTION_EVENT_FALL_RECOVERED:
        app_ui_clear_motion_reaction();
        app_ui_set_dialog_status("Motion: fall recovered");
        break;
    default:
        break;
    }

    if (!app_tts_speak_text_no_followup(motion_voice_text(event))) {
        ESP_LOGW(TAG, "Motion voice feedback queue failed for event=%d", event);
    }
    app_ui_set_motion_debug_event(motion_event_name(event), motion_reaction_name(event));
    app_cloud_update_motion(event, state);
}
#endif

typedef struct {
    const char *text;
    app_tts_tone_t tone;
} touch_feedback_t;

static const touch_feedback_t *select_touch_feedback(app_mood_t mood)
{
    static const touch_feedback_t happy[] = {
        { "哎哟，痒痒的。", APP_TTS_TONE_CHEERFUL },
        { "嘿嘿，被你碰到啦。", APP_TTS_TONE_CHEERFUL },
        { "小麦收到摸摸啦。", APP_TTS_TONE_CHEERFUL },
    };
    static const touch_feedback_t thirsty[] = {
        { "哎哟，我有点渴啦。", APP_TTS_TONE_WORRIED },
        { "轻一点嘛，我想喝点水。", APP_TTS_TONE_WORRIED },
        { "摸摸收到，可以顺便看看水吗？", APP_TTS_TONE_WORRIED },
    };
    static const touch_feedback_t dark[] = {
        { "哎哟，有点困困的。", APP_TTS_TONE_SLEEPY },
        { "我想晒晒光啦。", APP_TTS_TONE_SLEEPY },
        { "黑乎乎的，小麦有点懵。", APP_TTS_TONE_SLEEPY },
    };
    static const touch_feedback_t weak[] = {
        { "哎哟，我现在有点虚弱。", APP_TTS_TONE_WORRIED },
        { "轻一点嘛，我想要水和光。", APP_TTS_TONE_FLUSTERED },
        { "小麦有点晕，帮我看看水和光。", APP_TTS_TONE_FLUSTERED },
    };

    const touch_feedback_t *options = happy;
    size_t count = sizeof(happy) / sizeof(happy[0]);
    switch (mood) {
    case APP_MOOD_THIRSTY:
        options = thirsty;
        count = sizeof(thirsty) / sizeof(thirsty[0]);
        break;
    case APP_MOOD_DARK:
        options = dark;
        count = sizeof(dark) / sizeof(dark[0]);
        break;
    case APP_MOOD_WEAK:
        options = weak;
        count = sizeof(weak) / sizeof(weak[0]);
        break;
    case APP_MOOD_HAPPY:
    default:
        break;
    }
    return &options[esp_random() % count];
}

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
            const touch_feedback_t *feedback = select_touch_feedback(state->mood);
            if (!app_tts_speak_text_with_tone(feedback->text, feedback->tone)) {
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
    if (xTaskCreate(motion_debug_task, "smart_pot_motion_ui", 4096, NULL, 4, NULL) != pdPASS) {
        ESP_LOGW(TAG, "Failed to create MPU-6050 debug UI task");
    }
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
