#include "app_sensors.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

#include "bsp/esp-bsp.h"
#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nvs.h"

#include "app_time.h"
#include "app_tts.h"
#include "app_board_config.h"

#ifndef CONFIG_SMART_POT_SENSOR_HARDWARE_ENABLE
#define CONFIG_SMART_POT_SENSOR_HARDWARE_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_SENSOR_UPDATE_MS
#define CONFIG_SMART_POT_SENSOR_UPDATE_MS 2000
#endif

#ifndef CONFIG_SMART_POT_BH1750_ENABLE
#define CONFIG_SMART_POT_BH1750_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_BH1750_ADDR_SELECT
#define CONFIG_SMART_POT_BH1750_ADDR_SELECT 0
#endif

#ifndef CONFIG_SMART_POT_BH1750_USE_BSP_I2C
#define CONFIG_SMART_POT_BH1750_USE_BSP_I2C 1
#endif

#ifndef CONFIG_SMART_POT_BH1750_I2C_PORT
#define CONFIG_SMART_POT_BH1750_I2C_PORT 1
#endif

#ifndef CONFIG_SMART_POT_BH1750_SDA_GPIO
#define CONFIG_SMART_POT_BH1750_SDA_GPIO -1
#endif

#ifndef CONFIG_SMART_POT_BH1750_SCL_GPIO
#define CONFIG_SMART_POT_BH1750_SCL_GPIO -1
#endif

#ifndef CONFIG_SMART_POT_BH1750_MAX_LUX
#define CONFIG_SMART_POT_BH1750_MAX_LUX 1200
#endif

#ifndef CONFIG_SMART_POT_LIGHT_STRIP_ENABLE
#define CONFIG_SMART_POT_LIGHT_STRIP_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_LIGHT_STRIP_GPIO
#define CONFIG_SMART_POT_LIGHT_STRIP_GPIO 21
#endif

#ifndef CONFIG_SMART_POT_LIGHT_STRIP_ACTIVE_HIGH
#define CONFIG_SMART_POT_LIGHT_STRIP_ACTIVE_HIGH 1
#endif

#ifndef CONFIG_SMART_POT_TTP223_ENABLE
#define CONFIG_SMART_POT_TTP223_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_TTP223_GPIO
#define CONFIG_SMART_POT_TTP223_GPIO -1
#endif

#ifndef CONFIG_SMART_POT_TTP223_ACTIVE_HIGH
#define CONFIG_SMART_POT_TTP223_ACTIVE_HIGH 1
#endif

#ifndef CONFIG_SMART_POT_SOIL_555_ENABLE
#define CONFIG_SMART_POT_SOIL_555_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_SOIL_555_GPIO
#define CONFIG_SMART_POT_SOIL_555_GPIO -1
#endif

#ifndef CONFIG_SMART_POT_SOIL_555_SAMPLE_MS
#define CONFIG_SMART_POT_SOIL_555_SAMPLE_MS 250
#endif

#ifndef CONFIG_SMART_POT_SOIL_555_DRY_HZ
#define CONFIG_SMART_POT_SOIL_555_DRY_HZ 2500
#endif

#ifndef CONFIG_SMART_POT_SOIL_555_WET_HZ
#define CONFIG_SMART_POT_SOIL_555_WET_HZ 500
#endif

#ifndef CONFIG_SMART_POT_SOIL_MODULE_ENABLE
#define CONFIG_SMART_POT_SOIL_MODULE_ENABLE CONFIG_SMART_POT_SOIL_555_ENABLE
#endif

#ifndef CONFIG_SMART_POT_SOIL_MODULE_DO_GPIO
#define CONFIG_SMART_POT_SOIL_MODULE_DO_GPIO CONFIG_SMART_POT_SOIL_555_GPIO
#endif

#ifndef CONFIG_SMART_POT_SOIL_MODULE_AO_GPIO
#define CONFIG_SMART_POT_SOIL_MODULE_AO_GPIO 22
#endif

#ifndef CONFIG_SMART_POT_SOIL_ADC_SAMPLES
#define CONFIG_SMART_POT_SOIL_ADC_SAMPLES 8
#endif

#ifndef CONFIG_SMART_POT_SOIL_ADC_DRY_RAW
#define CONFIG_SMART_POT_SOIL_ADC_DRY_RAW 4095
#endif

#ifndef CONFIG_SMART_POT_SOIL_ADC_WET_RAW
#define CONFIG_SMART_POT_SOIL_ADC_WET_RAW 0
#endif

#define BH1750_ADDR_LOW 0x23
#define BH1750_ADDR_HIGH 0x5c
#define BH1750_POWER_ON 0x01
#define BH1750_ONE_TIME_HIGH_RES 0x20
#define BH1750_WAKE_DELAY_MS 15
#define BH1750_RECOVER_DELAY_MS 5
#define BH1750_RETRY_LOOPS 15
#define BH1750_ONLY_I2C_TEST 0
#define SOIL_LOW_PERCENT 20
#define SOIL_HIGH_PERCENT 60
#define LIGHT_LOW_PERCENT 25
#define LIGHT_HIGH_PERCENT 85
#define ENV_STATE_STABLE_US (5LL * 1000000LL)
#define ENV_ABNORMAL_REMINDER_US (2LL * 60LL * 60LL * 1000000LL)
#define REMINDER_START_MINUTE (9 * 60)
#define LOW_LIGHT_REMINDER_START_MINUTE (8 * 60)
#define REMINDER_END_MINUTE (19 * 60)

static const char *TAG = "app_sensors";

typedef struct {
    app_sensor_update_cb_t cb;
    void *user_ctx;
} sensor_task_ctx_t;

typedef struct {
    bool available;
    bool on;
    bool manual_mode;
    bool manual_on;
    bool off_period_enabled;
    uint16_t off_start_minute;
    uint16_t off_end_minute;
    uint8_t soil_min_percent;
    uint8_t soil_max_percent;
    uint32_t light_min_lux;
    uint32_t light_max_lux;
} light_strip_control_t;

typedef enum {
    ENV_ALERT_UNKNOWN = 0,
    ENV_ALERT_LOW_LOW,
    ENV_ALERT_LOW_NORMAL,
    ENV_ALERT_LOW_HIGH,
    ENV_ALERT_NORMAL_LOW,
    ENV_ALERT_NORMAL_NORMAL,
    ENV_ALERT_NORMAL_HIGH,
    ENV_ALERT_HIGH_LOW,
    ENV_ALERT_HIGH_NORMAL,
    ENV_ALERT_HIGH_HIGH,
} env_alert_state_t;

typedef enum {
    SENSOR_LEVEL_LOW = 0,
    SENSOR_LEVEL_NORMAL,
    SENSOR_LEVEL_HIGH,
} sensor_level_t;

static SemaphoreHandle_t s_light_ctrl_lock;
static bool s_light_ctrl_loaded;
static light_strip_control_t s_light_ctrl = {
    .available = false,
    .on = false,
    .manual_mode = false,
    .manual_on = false,
    .off_period_enabled = APP_BOARD_LIGHT_STRIP_OFF_PERIOD_ENABLED,
    .off_start_minute = APP_BOARD_LIGHT_STRIP_OFF_START_MINUTE,
    .off_end_minute = APP_BOARD_LIGHT_STRIP_OFF_END_MINUTE,
    .soil_min_percent = SOIL_LOW_PERCENT,
    .soil_max_percent = SOIL_HIGH_PERCENT,
    .light_min_lux = APP_BOARD_LIGHT_STRIP_DEFAULT_LIGHT_MIN_LUX,
    .light_max_lux = APP_BOARD_LIGHT_STRIP_DEFAULT_LIGHT_MAX_LUX,
};

typedef struct {
    bool hardware_enabled;
    bool bh1750_ready;
    bool touch_ready;
    bool soil_ready;
    i2c_master_bus_handle_t sensor_bus;
    i2c_master_dev_handle_t bh1750;
    adc_oneshot_unit_handle_t soil_adc_unit;
    adc_unit_t soil_adc_unit_id;
    adc_channel_t soil_adc_channel;
    gpio_num_t touch_gpio;
    gpio_num_t soil_do_gpio;
    gpio_num_t soil_ao_gpio;
    gpio_num_t light_strip_gpio;
    bool light_strip_ready;
    bool light_strip_on;
    int64_t light_strip_changed_us;
    int64_t light_strip_low_since_us;
    int64_t light_strip_high_since_us;
    bool last_touch_active;
    uint32_t touch_count;
    uint32_t last_soil_raw;
    uint8_t last_soil_percent;
    bool last_soil_dry;
    uint32_t last_lux;
    uint32_t bh1750_fail_count;
    uint32_t soil_fail_count;
    uint32_t bh1750_retry_count;
    int64_t last_touch_speak_us;
    env_alert_state_t env_alert_state;
    int64_t env_state_since_us;
    int64_t last_abnormal_reminder_us;
    int reminder_day_id;
    bool normal_announced_today;
    bool state_change_pending;
} sensor_hw_t;

static bool read_touch_active(sensor_hw_t *hw);

static sensor_level_t classify_level(uint8_t value, uint8_t low, uint8_t high)
{
    if (value < low) {
        return SENSOR_LEVEL_LOW;
    }
    if (value > high) {
        return SENSOR_LEVEL_HIGH;
    }
    return SENSOR_LEVEL_NORMAL;
}

static env_alert_state_t classify_environment(uint8_t soil_percent, uint8_t light_percent)
{
    sensor_level_t soil = classify_level(soil_percent, SOIL_LOW_PERCENT, SOIL_HIGH_PERCENT);
    sensor_level_t light = classify_level(light_percent, LIGHT_LOW_PERCENT, LIGHT_HIGH_PERCENT);
    return (env_alert_state_t)(ENV_ALERT_LOW_LOW + soil * 3 + light);
}

static const char *environment_prompt(env_alert_state_t state)
{
    static const char *const prompts[][2] = {
        [ENV_ALERT_LOW_LOW] = {
            "又黑又干，小麦像在南极流浪，主人快给我水和阳光抱抱嘛！",
            "没光没水，我要委屈巴巴躺平啦，快来救救这株可怜小麦！",
        },
        [ENV_ALERT_LOW_NORMAL] = {
            "主人主人，我快要变成小干花啦，快给我喝两口水嘛，我还要长漂亮叶子呢！",
            "我掐指一算，上辈子可能是海绵宝宝，可现在干得连蟹堡都捏不出来啦！",
        },
        [ENV_ALERT_LOW_HIGH] = {
            "又干又晒，我是不是上了铁板烧呀，主人快加水再帮我遮一遮！",
            "太阳烤着我，土也干巴巴，小麦快变成盆栽小饼干啦！",
        },
        [ENV_ALERT_NORMAL_LOW] = {
            "主人，带我去晒晒太阳嘛，我想开开心心地光合作用！",
            "好黑呀，我再待下去都要偷偷长蘑菇啦，救救小麦的叶子吧！",
        },
        [ENV_ALERT_NORMAL_NORMAL] = {
            "主人你被评为今日最佳养植官啦，奖励你摸摸我的新叶子！",
            "报告主人，我今天绿到发亮，正在偷偷变得更漂亮哦！",
        },
        [ENV_ALERT_NORMAL_HIGH] = {
            "哎呀太阳太热情啦，我快被晒成小干花了，主人帮我遮一遮嘛！",
            "再晒下去小麦就要变成盆栽小饼干啦，快给我打一把伞！",
        },
        [ENV_ALERT_HIGH_LOW] = {
            "又湿又暗，我像一朵泡发的小木耳，再这样要长蘑菇啦！",
            "这里又潮又黑，小麦都想给自己挂除湿袋啦，快让我透气见光！",
        },
        [ENV_ALERT_HIGH_NORMAL] = {
            "主人主人，我的根根快学会游泳啦，水有点多，让我透透气嘛！",
            "水太多啦，我的小脚脚闷闷的，快帮我通通风呀！",
        },
        [ENV_ALERT_HIGH_HIGH] = {
            "又涝又热，小麦像泡在蒸笼里，主人快让我透气凉快一下！",
            "泡着水还晒太阳，听着浪漫，其实我要变成小包子啦！",
        },
    };

    if (state <= ENV_ALERT_UNKNOWN || state > ENV_ALERT_HIGH_HIGH) {
        return NULL;
    }
    return prompts[state][esp_random() & 1U];
}

static app_tts_tone_t environment_tone(env_alert_state_t state)
{
    switch (state) {
    case ENV_ALERT_NORMAL_NORMAL:
        return APP_TTS_TONE_CHEERFUL;
    case ENV_ALERT_NORMAL_LOW:
    case ENV_ALERT_HIGH_LOW:
        return APP_TTS_TONE_SLEEPY;
    case ENV_ALERT_LOW_LOW:
    case ENV_ALERT_LOW_NORMAL:
        return APP_TTS_TONE_WORRIED;
    case ENV_ALERT_LOW_HIGH:
    case ENV_ALERT_NORMAL_HIGH:
    case ENV_ALERT_HIGH_NORMAL:
    case ENV_ALERT_HIGH_HIGH:
        return APP_TTS_TONE_FLUSTERED;
    default:
        return APP_TTS_TONE_CHEERFUL;
    }
}

static bool environment_has_low_light(env_alert_state_t state)
{
    return state == ENV_ALERT_LOW_LOW ||
           state == ENV_ALERT_NORMAL_LOW ||
           state == ENV_ALERT_HIGH_LOW;
}

static bool reminder_time_allowed(const struct tm *local_time, env_alert_state_t state)
{
    int minute_of_day = local_time->tm_hour * 60 + local_time->tm_min;
    int start_minute = environment_has_low_light(state) ?
                       LOW_LIGHT_REMINDER_START_MINUTE : REMINDER_START_MINUTE;
    return minute_of_day >= start_minute && minute_of_day < REMINDER_END_MINUTE;
}

static int reminder_day_id(const struct tm *local_time)
{
    return (local_time->tm_year + 1900) * 366 + local_time->tm_yday;
}

static void update_environment_voice_reminder(sensor_hw_t *hw, uint8_t soil_percent,
                                              uint8_t light_percent, bool soil_ok, bool light_ok)
{
    if (hw == NULL || !soil_ok || !light_ok) {
        return;
    }

    int64_t now_us = esp_timer_get_time();
    env_alert_state_t next_state = classify_environment(soil_percent, light_percent);
    if (next_state != hw->env_alert_state) {
        env_alert_state_t previous_state = hw->env_alert_state;
        hw->env_alert_state = next_state;
        hw->env_state_since_us = now_us;
        hw->state_change_pending = true;
        ESP_LOGI(TAG, "Environment reminder state changed: %d -> %d (soil=%u%% light=%u%%)",
                 previous_state, next_state, soil_percent, light_percent);
        return;
    }

    if (now_us - hw->env_state_since_us < ENV_STATE_STABLE_US) {
        return;
    }

    struct tm local_time = { 0 };
    if (!app_time_get_local(&local_time) || !reminder_time_allowed(&local_time, next_state)) {
        return;
    }

    int day_id = reminder_day_id(&local_time);
    if (hw->reminder_day_id != day_id) {
        hw->reminder_day_id = day_id;
        hw->normal_announced_today = false;
    }

    bool all_normal = next_state == ENV_ALERT_NORMAL_NORMAL;
    bool should_speak = false;
    if (all_normal) {
        should_speak = hw->state_change_pending || !hw->normal_announced_today;
    } else {
        should_speak = hw->last_abnormal_reminder_us == 0 ||
                       now_us - hw->last_abnormal_reminder_us >= ENV_ABNORMAL_REMINDER_US;
    }

    if (should_speak) {
        const char *text = environment_prompt(next_state);
        if (app_tts_speak_text_with_tone(text, environment_tone(next_state))) {
            hw->state_change_pending = false;
            if (all_normal) {
                hw->normal_announced_today = true;
            } else {
                hw->last_abnormal_reminder_us = now_us;
            }
            ESP_LOGI(TAG, "Environment reminder queued: state=%d soil=%u%% light=%u%%",
                     next_state, soil_percent, light_percent);
        } else {
            ESP_LOGI(TAG, "Environment reminder deferred because TTS is busy");
        }
    }
}

static app_mood_t calculate_mood(uint8_t soil_percent, uint8_t light_percent)
{
    bool thirsty = soil_percent < 35;
    bool dark = light_percent < 25;

    if (thirsty && dark) {
        return APP_MOOD_WEAK;
    }
    if (thirsty) {
        return APP_MOOD_THIRSTY;
    }
    if (dark) {
        return APP_MOOD_DARK;
    }
    return APP_MOOD_HAPPY;
}

static uint8_t clamp_percent(int32_t value)
{
    if (value < 0) {
        return 0;
    }
    if (value > 100) {
        return 100;
    }
    return (uint8_t)value;
}

static uint8_t simulated_percent(uint8_t min_value, uint8_t span)
{
    return (uint8_t)(min_value + (esp_random() % span));
}

static bool gpio_configured(int gpio_num)
{
    return gpio_num >= 0 && gpio_num < GPIO_NUM_MAX;
}

static bool gpio_reserved_for_console(int gpio_num)
{
    return gpio_num == 37 || gpio_num == 38;
}

static uint8_t lux_to_percent(uint32_t lux)
{
    uint32_t max_lux = CONFIG_SMART_POT_BH1750_MAX_LUX > 0 ?
                       CONFIG_SMART_POT_BH1750_MAX_LUX : 1200;
    return clamp_percent((int32_t)(lux * 100U / max_lux));
}

static int light_strip_level(bool on)
{
    bool active_high = APP_BOARD_LIGHT_STRIP_ACTIVE_HIGH;
    return on == active_high ? 1 : 0;
}

static int64_t light_strip_ms_to_us(int ms)
{
    return ms > 0 ? (int64_t)ms * 1000LL : 0;
}

static bool light_strip_elapsed(int64_t since_us, int64_t now_us, int ms)
{
    return ms <= 0 || (since_us > 0 && now_us - since_us >= light_strip_ms_to_us(ms));
}

#define LIGHT_CTRL_NVS_NAMESPACE "light_ctrl"

static void light_ctrl_load_locked(void)
{
    if (s_light_ctrl_loaded) {
        return;
    }

    nvs_handle_t nvs = 0;
    esp_err_t err = nvs_open(LIGHT_CTRL_NVS_NAMESPACE, NVS_READONLY, &nvs);
    if (err == ESP_OK) {
        uint8_t u8 = 0;
        uint16_t u16 = 0;
        uint32_t u32 = 0;
        if (nvs_get_u8(nvs, "manual_mode", &u8) == ESP_OK) s_light_ctrl.manual_mode = u8 != 0;
        if (nvs_get_u8(nvs, "manual_on", &u8) == ESP_OK) s_light_ctrl.manual_on = u8 != 0;
        if (nvs_get_u8(nvs, "off_en", &u8) == ESP_OK) s_light_ctrl.off_period_enabled = u8 != 0;
        if (nvs_get_u16(nvs, "off_start", &u16) == ESP_OK && u16 < 1440) s_light_ctrl.off_start_minute = u16;
        if (nvs_get_u16(nvs, "off_end", &u16) == ESP_OK && u16 < 1440) s_light_ctrl.off_end_minute = u16;
        if (nvs_get_u8(nvs, "soil_min", &u8) == ESP_OK && u8 <= 100) s_light_ctrl.soil_min_percent = u8;
        if (nvs_get_u8(nvs, "soil_max", &u8) == ESP_OK && u8 <= 100) s_light_ctrl.soil_max_percent = u8;
        if (nvs_get_u32(nvs, "light_min", &u32) == ESP_OK && u32 > 0) s_light_ctrl.light_min_lux = u32;
        if (nvs_get_u32(nvs, "light_max", &u32) == ESP_OK && u32 > 0) s_light_ctrl.light_max_lux = u32;
        nvs_close(nvs);
    } else if (err != ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "Light strip settings load skipped: %s", esp_err_to_name(err));
    }

    if (s_light_ctrl.light_max_lux < s_light_ctrl.light_min_lux) {
        s_light_ctrl.light_max_lux = s_light_ctrl.light_min_lux;
    }
    if (s_light_ctrl.soil_max_percent < s_light_ctrl.soil_min_percent) {
        s_light_ctrl.soil_max_percent = s_light_ctrl.soil_min_percent;
    }
    s_light_ctrl_loaded = true;
}

static void light_ctrl_save_locked(void)
{
    nvs_handle_t nvs = 0;
    esp_err_t err = nvs_open(LIGHT_CTRL_NVS_NAMESPACE, NVS_READWRITE, &nvs);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Light strip settings save skipped: %s", esp_err_to_name(err));
        return;
    }

    nvs_set_u8(nvs, "manual_mode", s_light_ctrl.manual_mode ? 1 : 0);
    nvs_set_u8(nvs, "manual_on", s_light_ctrl.manual_on ? 1 : 0);
    nvs_set_u8(nvs, "off_en", s_light_ctrl.off_period_enabled ? 1 : 0);
    nvs_set_u16(nvs, "off_start", s_light_ctrl.off_start_minute);
    nvs_set_u16(nvs, "off_end", s_light_ctrl.off_end_minute);
    nvs_set_u8(nvs, "soil_min", s_light_ctrl.soil_min_percent);
    nvs_set_u8(nvs, "soil_max", s_light_ctrl.soil_max_percent);
    nvs_set_u32(nvs, "light_min", s_light_ctrl.light_min_lux);
    nvs_set_u32(nvs, "light_max", s_light_ctrl.light_max_lux);
    err = nvs_commit(nvs);
    nvs_close(nvs);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Light strip settings commit failed: %s", esp_err_to_name(err));
    }
}

static void light_ctrl_ensure_loaded(void)
{
    if (s_light_ctrl_lock == NULL) {
        s_light_ctrl_lock = xSemaphoreCreateMutex();
    }
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        light_ctrl_load_locked();
        xSemaphoreGive(s_light_ctrl_lock);
    } else if (!s_light_ctrl_loaded) {
        light_ctrl_load_locked();
    }
}

static light_strip_control_t light_ctrl_snapshot(void)
{
    light_ctrl_ensure_loaded();
    light_strip_control_t snapshot = s_light_ctrl;
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        snapshot = s_light_ctrl;
        xSemaphoreGive(s_light_ctrl_lock);
    }
    return snapshot;
}

static void light_ctrl_update_runtime(bool available, bool on)
{
    light_ctrl_ensure_loaded();
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_light_ctrl.available = available;
        s_light_ctrl.on = on;
        xSemaphoreGive(s_light_ctrl_lock);
    } else {
        s_light_ctrl.available = available;
        s_light_ctrl.on = on;
    }
}

static bool minute_in_period(uint16_t now_minute, uint16_t start_minute, uint16_t end_minute)
{
    if (start_minute == end_minute) {
        return false;
    }
    if (start_minute < end_minute) {
        return now_minute >= start_minute && now_minute < end_minute;
    }
    return now_minute >= start_minute || now_minute < end_minute;
}

static bool light_strip_in_off_period(const light_strip_control_t *ctrl)
{
    if (ctrl == NULL || !ctrl->off_period_enabled) {
        return false;
    }

    struct tm now = { 0 };
    if (!app_time_get_local(&now)) {
        return false;
    }

    uint16_t minute = (uint16_t)(now.tm_hour * 60 + now.tm_min);
    return minute_in_period(minute, ctrl->off_start_minute, ctrl->off_end_minute);
}

static void light_ctrl_project_output_locked(void)
{
    bool quiet_now = light_strip_in_off_period(&s_light_ctrl);
    if (s_light_ctrl.manual_mode) {
        s_light_ctrl.on = quiet_now ? false : s_light_ctrl.manual_on;
    } else if (quiet_now) {
        s_light_ctrl.on = false;
    }
}

static void set_light_strip(sensor_hw_t *hw, bool on, uint32_t light_lux, const char *reason)
{
    if (hw == NULL || !hw->light_strip_ready) {
        return;
    }

    if (gpio_set_level(hw->light_strip_gpio, light_strip_level(on)) != ESP_OK) {
        ESP_LOGW(TAG, "Light strip GPIO%d set failed", hw->light_strip_gpio);
        return;
    }

    if (hw->light_strip_on != on) {
        ESP_LOGI(TAG, "Light strip %s: light=%ulux reason=%s GPIO%d level=%d",
                 on ? "on" : "off", (unsigned int)light_lux, reason != NULL ? reason : "",
                 hw->light_strip_gpio, light_strip_level(on));
        hw->light_strip_changed_us = esp_timer_get_time();
    }
    hw->light_strip_on = on;
    light_ctrl_update_runtime(true, on);
}

static void update_light_strip(sensor_hw_t *hw, uint32_t light_lux, bool light_ok)
{
    if (hw == NULL || !hw->light_strip_ready) {
        return;
    }

    light_strip_control_t ctrl = light_ctrl_snapshot();
    bool off_period = light_strip_in_off_period(&ctrl);

    int64_t now_us = esp_timer_get_time();
    if (ctrl.manual_mode) {
        set_light_strip(hw, off_period ? false : ctrl.manual_on, light_lux,
                        off_period ? "manual blocked inside quiet period" : "app manual mode outside quiet period");
        hw->light_strip_low_since_us = 0;
        hw->light_strip_high_since_us = 0;
        return;
    }

    if (off_period) {
        set_light_strip(hw, false, light_lux, "quiet off period");
        hw->light_strip_low_since_us = 0;
        hw->light_strip_high_since_us = 0;
        return;
    }

    if (!light_ok) {
        ESP_LOGW(TAG, "Light strip unchanged because BH1750 reading is unavailable");
        return;
    }

    uint32_t min_lux = ctrl.light_min_lux > 0 ? ctrl.light_min_lux : APP_BOARD_LIGHT_STRIP_DEFAULT_LIGHT_MIN_LUX;
    bool low_light = light_lux < min_lux;
    bool enough_light = light_lux >= min_lux;

    if (low_light) {
        if (hw->light_strip_low_since_us == 0) {
            hw->light_strip_low_since_us = now_us;
        }
    } else {
        hw->light_strip_low_since_us = 0;
    }

    if (enough_light) {
        if (hw->light_strip_high_since_us == 0) {
            hw->light_strip_high_since_us = now_us;
        }
    } else {
        hw->light_strip_high_since_us = 0;
    }

    if (!hw->light_strip_on &&
        low_light &&
        light_strip_elapsed(hw->light_strip_low_since_us, now_us,
                            APP_BOARD_LIGHT_STRIP_ON_CONFIRM_MS) &&
        light_strip_elapsed(hw->light_strip_changed_us, now_us,
                            APP_BOARD_LIGHT_STRIP_MIN_OFF_MS)) {
        set_light_strip(hw, true, light_lux, "below target lux");
        hw->light_strip_low_since_us = 0;
        hw->light_strip_high_since_us = 0;
    } else if (hw->light_strip_on &&
               enough_light &&
               light_strip_elapsed(hw->light_strip_high_since_us, now_us,
                                   APP_BOARD_LIGHT_STRIP_OFF_CONFIRM_MS) &&
               light_strip_elapsed(hw->light_strip_changed_us, now_us,
                                   APP_BOARD_LIGHT_STRIP_MIN_ON_MS)) {
        set_light_strip(hw, false, light_lux, "target lux reached");
        hw->light_strip_low_since_us = 0;
        hw->light_strip_high_since_us = 0;
    }
}

static uint8_t soil_raw_to_percent(uint32_t raw)
{
    int32_t dry = CONFIG_SMART_POT_SOIL_ADC_DRY_RAW;
    int32_t wet = CONFIG_SMART_POT_SOIL_ADC_WET_RAW;
    if (dry == wet) {
        return 50;
    }

    int32_t percent = (dry - (int32_t)raw) * 100 / (dry - wet);
    return clamp_percent(percent);
}

static esp_err_t bh1750_command(i2c_master_dev_handle_t dev, uint8_t command)
{
    return i2c_master_transmit(dev, &command, sizeof(command), 100);
}

static void recover_bh1750_i2c(sensor_hw_t *hw, esp_err_t err, const char *phase)
{
    if (hw == NULL || hw->sensor_bus == NULL || err != ESP_ERR_INVALID_STATE) {
        return;
    }

    esp_err_t reset_err = i2c_master_bus_reset(hw->sensor_bus);
    ESP_LOGW(TAG, "BH1750 I2C invalid state during %s; bus reset: %s",
             phase, esp_err_to_name(reset_err));
    vTaskDelay(pdMS_TO_TICKS(BH1750_RECOVER_DELAY_MS));
}

static esp_err_t init_bh1750(sensor_hw_t *hw)
{
    if (!CONFIG_SMART_POT_BH1750_ENABLE) {
        return ESP_ERR_NOT_SUPPORTED;
    }

    esp_err_t err = ESP_OK;
    if (CONFIG_SMART_POT_BH1750_USE_BSP_I2C) {
        err = bsp_i2c_init();
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "BSP I2C init failed for BH1750: %s", esp_err_to_name(err));
            return err;
        }

        hw->sensor_bus = bsp_i2c_get_handle();
        if (hw->sensor_bus == NULL) {
            ESP_LOGW(TAG, "BSP I2C bus handle is NULL");
            return ESP_ERR_INVALID_STATE;
        }
    } else {
        if (!gpio_configured(CONFIG_SMART_POT_BH1750_SDA_GPIO) ||
            !gpio_configured(CONFIG_SMART_POT_BH1750_SCL_GPIO)) {
            ESP_LOGW(TAG, "BH1750 custom I2C GPIOs are not configured");
            return ESP_ERR_INVALID_ARG;
        }
        i2c_master_bus_config_t bus_cfg = {
            .clk_source = I2C_CLK_SRC_DEFAULT,
            .i2c_port = CONFIG_SMART_POT_BH1750_I2C_PORT,
            .sda_io_num = (gpio_num_t)CONFIG_SMART_POT_BH1750_SDA_GPIO,
            .scl_io_num = (gpio_num_t)CONFIG_SMART_POT_BH1750_SCL_GPIO,
            .glitch_ignore_cnt = 7,
            .flags.enable_internal_pullup = true,
        };
        err = i2c_new_master_bus(&bus_cfg, &hw->sensor_bus);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "BH1750 custom I2C bus init failed: %s", esp_err_to_name(err));
            return err;
        }
    }

    uint8_t preferred = CONFIG_SMART_POT_BH1750_ADDR_SELECT ? BH1750_ADDR_HIGH : BH1750_ADDR_LOW;
    uint8_t alternate = CONFIG_SMART_POT_BH1750_ADDR_SELECT ? BH1750_ADDR_LOW : BH1750_ADDR_HIGH;
    uint8_t addresses[2] = {
        preferred,
        alternate,
    };

    for (size_t i = 0; i < 2; i++) {
        uint8_t address = addresses[i];
        err = i2c_master_probe(hw->sensor_bus, address, 200);
        if (err != ESP_OK) {
            continue;
        }

        i2c_master_dev_handle_t dev = NULL;
        i2c_device_config_t dev_cfg = {
            .dev_addr_length = I2C_ADDR_BIT_LEN_7,
            .device_address = address,
            .scl_speed_hz = 100000,
            .scl_wait_us = 20000,
        };
        err = i2c_master_bus_add_device(hw->sensor_bus, &dev_cfg, &dev);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "BH1750 add device failed at 0x%02x: %s",
                     address, esp_err_to_name(err));
            continue;
        }

        err = bh1750_command(dev, BH1750_POWER_ON);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "BH1750 did not ACK wake command at 0x%02x: %s",
                     address, esp_err_to_name(err));
            i2c_master_bus_rm_device(dev);
            continue;
        }

        vTaskDelay(pdMS_TO_TICKS(BH1750_WAKE_DELAY_MS));

        err = i2c_master_probe(hw->sensor_bus, address, 200);
        if (err == ESP_OK) {
            hw->bh1750 = dev;
            hw->bh1750_ready = true;
            hw->bh1750_retry_count = 0;
            ESP_LOGI(TAG, "BH1750 ready at I2C address 0x%02x on %s I2C bus",
                     address,
                     CONFIG_SMART_POT_BH1750_USE_BSP_I2C ? "BSP" : "custom");
            return ESP_OK;
        }

        ESP_LOGW(TAG, "BH1750 did not ACK probe after wake at 0x%02x: %s",
                 address, esp_err_to_name(err));
        i2c_master_bus_rm_device(dev);
    }

    hw->bh1750_retry_count++;
    if ((hw->bh1750_retry_count % 3) == 1) {
        ESP_LOGW(TAG, "BH1750 not found at 0x%02x or 0x%02x",
                 preferred, alternate);
    }
    return err;
}

static esp_err_t read_bh1750(sensor_hw_t *hw, uint32_t *lux)
{
    if (!hw->bh1750_ready || lux == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    esp_err_t err = bh1750_command(hw->bh1750, BH1750_POWER_ON);
    if (err != ESP_OK) {
        hw->bh1750_fail_count++;
        recover_bh1750_i2c(hw, err, "power-on");
        if ((hw->bh1750_fail_count % 5) == 1) {
            ESP_LOGW(TAG, "BH1750 power-on failed: %s", esp_err_to_name(err));
        }
        return err;
    }

    vTaskDelay(pdMS_TO_TICKS(10));
    err = bh1750_command(hw->bh1750, BH1750_ONE_TIME_HIGH_RES);
    if (err != ESP_OK) {
        hw->bh1750_fail_count++;
        recover_bh1750_i2c(hw, err, "measurement");
        if ((hw->bh1750_fail_count % 5) == 1) {
            ESP_LOGW(TAG, "BH1750 measurement command failed: %s", esp_err_to_name(err));
        }
        return err;
    }

    vTaskDelay(pdMS_TO_TICKS(180));
    uint8_t data[2] = { 0 };
    err = i2c_master_receive(hw->bh1750, data, sizeof(data), 100);
    if (err != ESP_OK) {
        hw->bh1750_fail_count++;
        recover_bh1750_i2c(hw, err, "receive");
        if ((hw->bh1750_fail_count % 5) == 1) {
            ESP_LOGW(TAG, "BH1750 receive failed: %s", esp_err_to_name(err));
        }
        return err;
    }

    uint32_t raw = ((uint32_t)data[0] << 8) | data[1];
    *lux = raw * 10U / 12U;
    hw->bh1750_fail_count = 0;
    return ESP_OK;
}

static void init_light_strip(sensor_hw_t *hw)
{
    light_ctrl_ensure_loaded();
    if (!APP_BOARD_LIGHT_STRIP_ENABLE ||
        !gpio_configured(APP_BOARD_LIGHT_STRIP_GPIO)) {
        light_ctrl_update_runtime(false, false);
        return;
    }
    if (gpio_reserved_for_console(APP_BOARD_LIGHT_STRIP_GPIO)) {
        ESP_LOGE(TAG, "Light strip GPIO%d is reserved for console UART; light strip output disabled",
                 APP_BOARD_LIGHT_STRIP_GPIO);
        light_ctrl_update_runtime(false, false);
        return;
    }

    if (CONFIG_SMART_POT_LIGHT_STRIP_GPIO != APP_BOARD_LIGHT_STRIP_GPIO) {
        ESP_LOGW(TAG, "Ignoring local sdkconfig light-strip GPIO%d; using board GPIO%d",
                 CONFIG_SMART_POT_LIGHT_STRIP_GPIO, APP_BOARD_LIGHT_STRIP_GPIO);
    }

    hw->light_strip_gpio = (gpio_num_t)APP_BOARD_LIGHT_STRIP_GPIO;
    gpio_set_level(hw->light_strip_gpio, light_strip_level(false));
    gpio_config_t cfg = {
        .pin_bit_mask = 1ULL << hw->light_strip_gpio,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = APP_BOARD_LIGHT_STRIP_ACTIVE_HIGH ? GPIO_PULLUP_DISABLE : GPIO_PULLUP_ENABLE,
        .pull_down_en = APP_BOARD_LIGHT_STRIP_ACTIVE_HIGH ? GPIO_PULLDOWN_ENABLE : GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&cfg));
    hw->light_strip_ready = true;
    set_light_strip(hw, false, 0, "startup default");
    hw->light_strip_changed_us = esp_timer_get_time();
    light_strip_control_t ctrl = light_ctrl_snapshot();
    ESP_LOGI(TAG, "Light strip breaker configured on GPIO%d active_%s target=%lu-%lulux confirm=%d/%dms",
             hw->light_strip_gpio,
             APP_BOARD_LIGHT_STRIP_ACTIVE_HIGH ? "high" : "low",
             (unsigned long)ctrl.light_min_lux,
             (unsigned long)ctrl.light_max_lux,
             APP_BOARD_LIGHT_STRIP_ON_CONFIRM_MS,
             APP_BOARD_LIGHT_STRIP_OFF_CONFIRM_MS);
}

static void init_touch(sensor_hw_t *hw)
{
    if (!CONFIG_SMART_POT_TTP223_ENABLE || !gpio_configured(CONFIG_SMART_POT_TTP223_GPIO)) {
        return;
    }

    hw->touch_gpio = (gpio_num_t)CONFIG_SMART_POT_TTP223_GPIO;
    gpio_config_t cfg = {
        .pin_bit_mask = 1ULL << hw->touch_gpio,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&cfg));
    hw->touch_ready = true;
    hw->last_touch_active = read_touch_active(hw);
    ESP_LOGI(TAG, "TTP223 touch input configured on GPIO%d, active_%s",
             hw->touch_gpio, CONFIG_SMART_POT_TTP223_ACTIVE_HIGH ? "high" : "low");
}

static bool read_touch_active(sensor_hw_t *hw)
{
    if (!hw->touch_ready) {
        return false;
    }

    int level = gpio_get_level(hw->touch_gpio);
    return CONFIG_SMART_POT_TTP223_ACTIVE_HIGH ? level == 1 : level == 0;
}

static void init_soil_module(sensor_hw_t *hw)
{
    if (!CONFIG_SMART_POT_SOIL_MODULE_ENABLE ||
        !gpio_configured(CONFIG_SMART_POT_SOIL_MODULE_AO_GPIO)) {
        return;
    }

    hw->soil_ao_gpio = (gpio_num_t)CONFIG_SMART_POT_SOIL_MODULE_AO_GPIO;
    hw->soil_do_gpio = (gpio_num_t)CONFIG_SMART_POT_SOIL_MODULE_DO_GPIO;

    esp_err_t err = adc_oneshot_io_to_channel(hw->soil_ao_gpio,
                                              &hw->soil_adc_unit_id,
                                              &hw->soil_adc_channel);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Soil AO GPIO%d is not an ADC input: %s",
                 hw->soil_ao_gpio, esp_err_to_name(err));
        return;
    }

    adc_oneshot_unit_init_cfg_t adc_init = {
        .unit_id = hw->soil_adc_unit_id,
        .ulp_mode = ADC_ULP_MODE_DISABLE,
    };
    err = adc_oneshot_new_unit(&adc_init, &hw->soil_adc_unit);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Soil ADC unit init failed: %s", esp_err_to_name(err));
        return;
    }

    adc_oneshot_chan_cfg_t adc_chan = {
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    err = adc_oneshot_config_channel(hw->soil_adc_unit, hw->soil_adc_channel, &adc_chan);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Soil ADC channel config failed: %s", esp_err_to_name(err));
        return;
    }

    if (gpio_configured(hw->soil_do_gpio)) {
        gpio_config_t cfg = {
            .pin_bit_mask = 1ULL << hw->soil_do_gpio,
            .mode = GPIO_MODE_INPUT,
            .pull_up_en = GPIO_PULLUP_DISABLE,
            .pull_down_en = GPIO_PULLDOWN_DISABLE,
            .intr_type = GPIO_INTR_DISABLE,
        };
        ESP_ERROR_CHECK(gpio_config(&cfg));
    }

    hw->soil_ready = true;
    ESP_LOGI(TAG, "Soil module ready: AO=GPIO%d ADC%d_CH%d DO=GPIO%d dry_raw=%d wet_raw=%d do_level=%d",
             hw->soil_ao_gpio, hw->soil_adc_unit_id + 1, hw->soil_adc_channel,
             hw->soil_do_gpio,
             CONFIG_SMART_POT_SOIL_ADC_DRY_RAW, CONFIG_SMART_POT_SOIL_ADC_WET_RAW,
             gpio_configured(hw->soil_do_gpio) ? gpio_get_level(hw->soil_do_gpio) : -1);
}

static esp_err_t sample_soil_module(sensor_hw_t *hw, uint32_t *raw, uint8_t *percent, bool *dry)
{
    if (!hw->soil_ready || hw->soil_adc_unit == NULL || raw == NULL || percent == NULL || dry == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    uint32_t samples = CONFIG_SMART_POT_SOIL_ADC_SAMPLES > 0 ?
                       CONFIG_SMART_POT_SOIL_ADC_SAMPLES : 8;
    uint32_t sum = 0;
    for (uint32_t i = 0; i < samples; i++) {
        int value = 0;
        esp_err_t err = adc_oneshot_read(hw->soil_adc_unit, hw->soil_adc_channel, &value);
        if (err != ESP_OK) {
            hw->soil_fail_count++;
            if ((hw->soil_fail_count % 5) == 1) {
                ESP_LOGW(TAG, "Soil ADC read failed on GPIO%d: %s",
                         hw->soil_ao_gpio, esp_err_to_name(err));
            }
            return err;
        }
        sum += (uint32_t)value;
        vTaskDelay(pdMS_TO_TICKS(2));
    }

    *raw = sum / samples;
    *percent = soil_raw_to_percent(*raw);
    *dry = gpio_configured(hw->soil_do_gpio) ? gpio_get_level(hw->soil_do_gpio) == 1 : *percent < 35;
    hw->soil_fail_count = 0;
    return ESP_OK;
}

static void init_hardware(sensor_hw_t *hw)
{
    hw->hardware_enabled = CONFIG_SMART_POT_SENSOR_HARDWARE_ENABLE;
    hw->touch_gpio = GPIO_NUM_NC;
    hw->soil_do_gpio = GPIO_NUM_NC;
    hw->soil_ao_gpio = GPIO_NUM_NC;
    hw->light_strip_gpio = GPIO_NUM_NC;
    if (!hw->hardware_enabled) {
        ESP_LOGI(TAG, "Sensor hardware disabled; using simulated readings");
        return;
    }

    init_bh1750(hw);
#if !BH1750_ONLY_I2C_TEST
    init_light_strip(hw);
    init_touch(hw);
    init_soil_module(hw);
#endif

    ESP_LOGI(TAG, "Sensor hardware summary: BH1750=%d light_strip=%d TTP223=%d soil_module=%d",
             hw->bh1750_ready, hw->light_strip_ready, hw->touch_ready, hw->soil_ready);
}

static void update_touch(sensor_hw_t *hw)
{
    bool active = read_touch_active(hw);
    if (active && !hw->last_touch_active) {
        hw->touch_count++;
        ESP_LOGI(TAG, "Touch detected, count=%u", (unsigned int)hw->touch_count);
        int64_t now_us = esp_timer_get_time();
        if (now_us - hw->last_touch_speak_us > 3000000) {
            ESP_LOGI(TAG, "Touch reaction will be handled by the app callback");
            hw->last_touch_speak_us = now_us;
        }
#if 0
        if (now_us - hw->last_touch_speak_us > 3000000) {
            if (!app_wifi_is_connected()) {
                ESP_LOGW(TAG, "Touch greeting skipped: Wi-Fi offline");
            } else if (app_tts_speak_text("我在呢。")) {
                ESP_LOGI(TAG, "Touch greeting queued");
            } else {
                ESP_LOGW(TAG, "Touch greeting queue failed");
            }
            hw->last_touch_speak_us = now_us;
        }
#endif
    }
    hw->last_touch_active = active;
}

static void sensor_task(void *arg)
{
    sensor_task_ctx_t *ctx = (sensor_task_ctx_t *)arg;
    sensor_hw_t hw = { 0 };
    uint32_t loop_count = 0;

    init_hardware(&hw);
    while (true) {
#if !BH1750_ONLY_I2C_TEST
        update_touch(&hw);
#endif

        if (hw.hardware_enabled && CONFIG_SMART_POT_BH1750_ENABLE && !hw.bh1750_ready &&
            loop_count > 0 && (loop_count % BH1750_RETRY_LOOPS) == 0) {
            init_bh1750(&hw);
        }

        uint32_t lux = 0;
        uint32_t soil_raw = 0;
        uint8_t soil_percent = 0;
        bool soil_dry = false;
        bool light_ok = read_bh1750(&hw, &lux) == ESP_OK;
#if BH1750_ONLY_I2C_TEST
        bool soil_ok = false;
#else
        bool soil_ok = sample_soil_module(&hw, &soil_raw, &soil_percent, &soil_dry) == ESP_OK;
#endif

        if (light_ok) {
            hw.last_lux = lux;
        }
        if (soil_ok) {
            hw.last_soil_raw = soil_raw;
            hw.last_soil_percent = soil_percent;
            hw.last_soil_dry = soil_dry;
        }

        app_plant_state_t state = {
            .soil_percent = soil_ok ? soil_percent : simulated_percent(30, 55),
            .light_percent = light_ok ? lux_to_percent(lux) : simulated_percent(18, 72),
            .touch_count = hw.touch_count,
            .touch_active = hw.last_touch_active,
            .light_lux = light_ok ? lux : 0,
            .soil_frequency_hz = 0,
            .soil_adc_raw = soil_ok ? soil_raw : 0,
            .soil_digital_dry = soil_ok ? soil_dry : false,
        };
        state.mood = calculate_mood(state.soil_percent, state.light_percent);
        update_light_strip(&hw, state.light_lux, light_ok);
        update_environment_voice_reminder(&hw, state.soil_percent, state.light_percent,
                                          soil_ok, light_ok);

        if ((loop_count++ % 5) == 0) {
            ESP_LOGI(TAG, "Sensor state: soil=%u%% (raw=%u dry=%d%s) light=%u%% (%ulux%s) touch=%u active=%d",
                     state.soil_percent, (unsigned int)hw.last_soil_raw, hw.last_soil_dry,
                     soil_ok ? "" : ", sim",
                     state.light_percent, (unsigned int)hw.last_lux, light_ok ? "" : ", sim",
                     (unsigned int)state.touch_count, state.touch_active);
        }

        if (ctx->cb != NULL) {
            ctx->cb(&state, ctx->user_ctx);
        }

        vTaskDelay(pdMS_TO_TICKS(CONFIG_SMART_POT_SENSOR_UPDATE_MS));
    }
}

void app_sensors_start(app_sensor_update_cb_t cb, void *user_ctx)
{
    static sensor_task_ctx_t ctx;
    ctx.cb = cb;
    ctx.user_ctx = user_ctx;

    xTaskCreate(sensor_task, "smart_pot_sensors", 4096, &ctx, 5, NULL);
}

void app_sensors_set_light_strip_manual_mode(bool enabled)
{
    light_ctrl_ensure_loaded();
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_light_ctrl.manual_mode = enabled;
        light_ctrl_project_output_locked();
        light_ctrl_save_locked();
        xSemaphoreGive(s_light_ctrl_lock);
    } else {
        s_light_ctrl.manual_mode = enabled;
        light_ctrl_project_output_locked();
    }
    ESP_LOGI(TAG, "Light strip manual mode: %s", enabled ? "on" : "off");
}

void app_sensors_set_light_strip_manual_on(bool on)
{
    light_ctrl_ensure_loaded();
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_light_ctrl.manual_on = on;
        light_ctrl_project_output_locked();
        light_ctrl_save_locked();
        xSemaphoreGive(s_light_ctrl_lock);
    } else {
        s_light_ctrl.manual_on = on;
        light_ctrl_project_output_locked();
    }
    ESP_LOGI(TAG, "Light strip manual output: %s", on ? "on" : "off");
}

bool app_sensors_set_light_strip_off_period(bool enabled, uint16_t start_minute, uint16_t end_minute)
{
    if (start_minute >= 1440 || end_minute >= 1440 || (enabled && start_minute == end_minute)) {
        return false;
    }

    light_ctrl_ensure_loaded();
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_light_ctrl.off_period_enabled = enabled;
        s_light_ctrl.off_start_minute = start_minute;
        s_light_ctrl.off_end_minute = end_minute;
        light_ctrl_project_output_locked();
        light_ctrl_save_locked();
        xSemaphoreGive(s_light_ctrl_lock);
    } else {
        s_light_ctrl.off_period_enabled = enabled;
        s_light_ctrl.off_start_minute = start_minute;
        s_light_ctrl.off_end_minute = end_minute;
        light_ctrl_project_output_locked();
    }
    ESP_LOGI(TAG, "Light strip quiet period: enabled=%d start=%u end=%u",
             enabled, start_minute, end_minute);
    return true;
}

bool app_sensors_set_plant_thresholds(uint8_t soil_min_percent, uint8_t soil_max_percent,
                                      uint32_t light_min_lux, uint32_t light_max_lux)
{
    if (soil_min_percent > soil_max_percent || soil_max_percent > 100 ||
        light_min_lux == 0 || light_min_lux > light_max_lux) {
        return false;
    }

    light_ctrl_ensure_loaded();
    light_strip_control_t current = light_ctrl_snapshot();
    if (current.soil_min_percent == soil_min_percent &&
        current.soil_max_percent == soil_max_percent &&
        current.light_min_lux == light_min_lux &&
        current.light_max_lux == light_max_lux) {
        return true;
    }
    if (s_light_ctrl_lock != NULL && xSemaphoreTake(s_light_ctrl_lock, pdMS_TO_TICKS(100)) == pdTRUE) {
        s_light_ctrl.soil_min_percent = soil_min_percent;
        s_light_ctrl.soil_max_percent = soil_max_percent;
        s_light_ctrl.light_min_lux = light_min_lux;
        s_light_ctrl.light_max_lux = light_max_lux;
        light_ctrl_save_locked();
        xSemaphoreGive(s_light_ctrl_lock);
    } else {
        s_light_ctrl.soil_min_percent = soil_min_percent;
        s_light_ctrl.soil_max_percent = soil_max_percent;
        s_light_ctrl.light_min_lux = light_min_lux;
        s_light_ctrl.light_max_lux = light_max_lux;
    }
    ESP_LOGI(TAG, "Plant thresholds synced: soil=%u-%u%% light=%lu-%lulux",
             soil_min_percent, soil_max_percent,
             (unsigned long)light_min_lux, (unsigned long)light_max_lux);
    return true;
}

void app_sensors_get_light_strip_state(app_light_strip_state_t *out)
{
    if (out == NULL) {
        return;
    }
    light_strip_control_t ctrl = light_ctrl_snapshot();
    out->available = ctrl.available;
    out->on = ctrl.on;
    out->manual_mode = ctrl.manual_mode;
    out->manual_on = ctrl.manual_on;
    out->off_period_enabled = ctrl.off_period_enabled;
    out->off_start_minute = ctrl.off_start_minute;
    out->off_end_minute = ctrl.off_end_minute;
    out->soil_min_percent = ctrl.soil_min_percent;
    out->soil_max_percent = ctrl.soil_max_percent;
    out->light_min_lux = ctrl.light_min_lux;
    out->light_max_lux = ctrl.light_max_lux;
}
