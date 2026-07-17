#include "app_motion.h"

#include <math.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include "bsp/esp-bsp.h"
#include "driver/i2c_master.h"
#include "esp_err.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#ifndef CONFIG_SMART_POT_MPU6050_ENABLE
#define CONFIG_SMART_POT_MPU6050_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_MPU6050_ADDRESS
#define CONFIG_SMART_POT_MPU6050_ADDRESS 0x68
#endif

#ifndef CONFIG_SMART_POT_MPU6050_DEBUG_LOG
#define CONFIG_SMART_POT_MPU6050_DEBUG_LOG 0
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TAP_DELTA_MG
#define CONFIG_SMART_POT_MPU6050_TAP_DELTA_MG 1500
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TAP_GYRO_MAX_DPS
#define CONFIG_SMART_POT_MPU6050_TAP_GYRO_MAX_DPS 120
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TAP_COOLDOWN_MS
#define CONFIG_SMART_POT_MPU6050_TAP_COOLDOWN_MS 120
#endif

#ifndef CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MIN_MS
#define CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MIN_MS 300
#endif

#ifndef CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MAX_MS
#define CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MAX_MS 800
#endif

#ifndef CONFIG_SMART_POT_MPU6050_SHAKE_ACCEL_MG
#define CONFIG_SMART_POT_MPU6050_SHAKE_ACCEL_MG 1800
#endif

#ifndef CONFIG_SMART_POT_MPU6050_SHAKE_GYRO_DPS
#define CONFIG_SMART_POT_MPU6050_SHAKE_GYRO_DPS 150
#endif

#ifndef CONFIG_SMART_POT_MPU6050_SHAKE_COOLDOWN_MS
#define CONFIG_SMART_POT_MPU6050_SHAKE_COOLDOWN_MS 1500
#endif

#ifndef CONFIG_SMART_POT_MPU6050_MOVE_TILT_DELTA_DEG
#define CONFIG_SMART_POT_MPU6050_MOVE_TILT_DELTA_DEG 15
#endif

#ifndef CONFIG_SMART_POT_MPU6050_MOVE_CONFIRM_MS
#define CONFIG_SMART_POT_MPU6050_MOVE_CONFIRM_MS 1000
#endif

#ifndef CONFIG_SMART_POT_MPU6050_MOVE_STABLE_MS
#define CONFIG_SMART_POT_MPU6050_MOVE_STABLE_MS 3000
#endif

#ifndef CONFIG_SMART_POT_MPU6050_STATIC_DELTA_MG
#define CONFIG_SMART_POT_MPU6050_STATIC_DELTA_MG 80
#endif

#ifndef CONFIG_SMART_POT_MPU6050_STATIC_GYRO_DPS
#define CONFIG_SMART_POT_MPU6050_STATIC_GYRO_DPS 5
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TILT_LIGHT_DEG
#define CONFIG_SMART_POT_MPU6050_TILT_LIGHT_DEG 15
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TILT_SEVERE_DEG
#define CONFIG_SMART_POT_MPU6050_TILT_SEVERE_DEG 45
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TILT_RECOVER_DEG
#define CONFIG_SMART_POT_MPU6050_TILT_RECOVER_DEG 10
#endif

#ifndef CONFIG_SMART_POT_MPU6050_TILT_CONFIRM_MS
#define CONFIG_SMART_POT_MPU6050_TILT_CONFIRM_MS 2000
#endif

#define MPU6050_REG_SMPLRT_DIV     0x19
#define MPU6050_REG_CONFIG         0x1a
#define MPU6050_REG_GYRO_CONFIG    0x1b
#define MPU6050_REG_ACCEL_CONFIG   0x1c
#define MPU6050_REG_ACCEL_XOUT_H   0x3b
#define MPU6050_REG_PWR_MGMT_1     0x6b
#define MPU6050_REG_WHO_AM_I       0x75

#define MPU6050_SAMPLE_PERIOD_MS       10
#define MPU6050_RETRY_MS               5000
#define MPU6050_CALIBRATION_SAMPLES    100
#define MPU6050_ACCEL_LSB_PER_G        8192.0f
#define MPU6050_GYRO_LSB_PER_DPS       65.5f
#define MPU6050_RAD_TO_DEG             57.2957795f

#define MPU6050_TAP_ACCEL_G            ((float)CONFIG_SMART_POT_MPU6050_TAP_DELTA_MG / 1000.0f)
#define MPU6050_TAP_GYRO_MAX_DPS       ((float)CONFIG_SMART_POT_MPU6050_TAP_GYRO_MAX_DPS)
#define MPU6050_SHAKE_ACCEL_G          ((float)CONFIG_SMART_POT_MPU6050_SHAKE_ACCEL_MG / 1000.0f)
#define MPU6050_SHAKE_GYRO_DPS         ((float)CONFIG_SMART_POT_MPU6050_SHAKE_GYRO_DPS)
#define MPU6050_MOVE_TILT_DELTA_DEG    ((float)CONFIG_SMART_POT_MPU6050_MOVE_TILT_DELTA_DEG)
#define MPU6050_STATIC_ACCEL_DELTA_G   ((float)CONFIG_SMART_POT_MPU6050_STATIC_DELTA_MG / 1000.0f)
#define MPU6050_STATIC_GYRO_DPS        ((float)CONFIG_SMART_POT_MPU6050_STATIC_GYRO_DPS)
#define MPU6050_TILT_LIGHT_DEG         ((float)CONFIG_SMART_POT_MPU6050_TILT_LIGHT_DEG)
#define MPU6050_TILT_SEVERE_DEG        ((float)CONFIG_SMART_POT_MPU6050_TILT_SEVERE_DEG)
#define MPU6050_TILT_RECOVER_DEG       ((float)CONFIG_SMART_POT_MPU6050_TILT_RECOVER_DEG)
#define MPU6050_DEBUG_LOG_PERIOD_US    1000000LL

typedef struct {
    i2c_master_bus_handle_t bus;
    i2c_master_dev_handle_t dev;
    app_motion_event_cb_t cb;
    void *user_ctx;
    float gyro_bias_x;
    float gyro_bias_y;
    float gyro_bias_z;
    float roll;
    float pitch;
    float base_roll;
    float base_pitch;
    int64_t last_sample_us;
    int64_t last_log_us;
    int64_t last_tap_us;
    int64_t last_impact_us;
    int64_t last_shake_us;
    int64_t move_since_us;
    int64_t stable_since_us;
    int64_t tilt_since_us;
    uint8_t tilt_candidate;
    bool moving;
    uint8_t tilt_level;
} motion_ctx_t;

static const char *TAG = "app_motion";
static SemaphoreHandle_t s_state_lock;
static app_motion_state_t s_state;
static bool s_state_valid;

static int16_t read_be16(const uint8_t *data)
{
    return (int16_t)(((uint16_t)data[0] << 8) | data[1]);
}

static esp_err_t write_reg(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t value)
{
    const uint8_t data[2] = {reg, value};
    return i2c_master_transmit(dev, data, sizeof(data), 100);
}

static esp_err_t read_regs(i2c_master_dev_handle_t dev, uint8_t reg, uint8_t *data, size_t len)
{
    return i2c_master_transmit_receive(dev, &reg, 1, data, len, 100);
}

#if CONFIG_SMART_POT_MPU6050_DEBUG_LOG
static void scan_i2c_bus(i2c_master_bus_handle_t bus)
{
    if (bus == NULL) {
        return;
    }

    char found[128] = {0};
    size_t offset = 0;
    for (uint8_t address = 0x08; address <= 0x77; address++) {
        if (i2c_master_probe(bus, address, 50) != ESP_OK) {
            continue;
        }

        int written = snprintf(found + offset, sizeof(found) - offset,
                               "%s0x%02x", offset == 0 ? "" : " ", address);
        if (written < 0) {
            break;
        }
        if ((size_t)written >= sizeof(found) - offset) {
            offset = sizeof(found) - 1;
            break;
        }
        offset += (size_t)written;
    }

    ESP_LOGI(TAG, "I2C scan found: %s", offset > 0 ? found : "none");
}

static void log_tuning_sample(motion_ctx_t *ctx, const app_motion_state_t *state, int64_t now_us)
{
    if (now_us - ctx->last_log_us < MPU6050_DEBUG_LOG_PERIOD_US) {
        return;
    }

    ctx->last_log_us = now_us;
    float accel_mag = sqrtf(state->accel_x_g * state->accel_x_g +
                            state->accel_y_g * state->accel_y_g +
                            state->accel_z_g * state->accel_z_g);
    float gyro_mag = sqrtf(state->gyro_x_dps * state->gyro_x_dps +
                           state->gyro_y_dps * state->gyro_y_dps +
                           state->gyro_z_dps * state->gyro_z_dps);
    float tilt_delta = fmaxf(fabsf(state->roll_deg - ctx->base_roll),
                             fabsf(state->pitch_deg - ctx->base_pitch));
    ESP_LOGI(TAG,
             "sample ax=%.2f ay=%.2f az=%.2f |g|=%.2f gx=%.1f gy=%.1f gz=%.1f |gyro|=%.1f roll=%.1f pitch=%.1f tiltDelta=%.1f moving=%d tilt=%u",
             state->accel_x_g, state->accel_y_g, state->accel_z_g, accel_mag,
             state->gyro_x_dps, state->gyro_y_dps, state->gyro_z_dps, gyro_mag,
             state->roll_deg, state->pitch_deg, tilt_delta, state->moving, state->tilt_level);
}
#endif

static void publish_state(const app_motion_state_t *state)
{
    if (s_state_lock != NULL && xSemaphoreTake(s_state_lock, pdMS_TO_TICKS(5)) == pdTRUE) {
        s_state = *state;
        s_state_valid = true;
        xSemaphoreGive(s_state_lock);
    }
}

static void emit_event(motion_ctx_t *ctx, app_motion_event_t event,
                       const app_motion_state_t *state)
{
    ESP_LOGI(TAG, "event=%d roll=%.1f pitch=%.1f accel=%.2f/%.2f/%.2f gyro=%.1f/%.1f/%.1f",
             event, state->roll_deg, state->pitch_deg,
             state->accel_x_g, state->accel_y_g, state->accel_z_g,
             state->gyro_x_dps, state->gyro_y_dps, state->gyro_z_dps);
    if (ctx->cb != NULL) {
        ctx->cb(event, state, ctx->user_ctx);
    }
}

static esp_err_t read_sample(motion_ctx_t *ctx, app_motion_state_t *state)
{
    uint8_t data[14];
    esp_err_t err = read_regs(ctx->dev, MPU6050_REG_ACCEL_XOUT_H, data, sizeof(data));
    if (err != ESP_OK) {
        return err;
    }

    state->accel_x_g = read_be16(&data[0]) / MPU6050_ACCEL_LSB_PER_G;
    state->accel_y_g = read_be16(&data[2]) / MPU6050_ACCEL_LSB_PER_G;
    state->accel_z_g = read_be16(&data[4]) / MPU6050_ACCEL_LSB_PER_G;
    state->gyro_x_dps = read_be16(&data[8]) / MPU6050_GYRO_LSB_PER_DPS - ctx->gyro_bias_x;
    state->gyro_y_dps = read_be16(&data[10]) / MPU6050_GYRO_LSB_PER_DPS - ctx->gyro_bias_y;
    state->gyro_z_dps = read_be16(&data[12]) / MPU6050_GYRO_LSB_PER_DPS - ctx->gyro_bias_z;
    return ESP_OK;
}

static esp_err_t calibrate_gyro(motion_ctx_t *ctx)
{
    float sum_x = 0.0f;
    float sum_y = 0.0f;
    float sum_z = 0.0f;
    app_motion_state_t sample = {0};

    ESP_LOGI(TAG, "Keep the PCB still for MPU-6050 gyro calibration");
    for (uint32_t i = 0; i < MPU6050_CALIBRATION_SAMPLES; i++) {
        esp_err_t err = read_sample(ctx, &sample);
        if (err != ESP_OK) {
            return err;
        }
        sum_x += sample.gyro_x_dps;
        sum_y += sample.gyro_y_dps;
        sum_z += sample.gyro_z_dps;
        vTaskDelay(pdMS_TO_TICKS(MPU6050_SAMPLE_PERIOD_MS));
    }
    ctx->gyro_bias_x = sum_x / MPU6050_CALIBRATION_SAMPLES;
    ctx->gyro_bias_y = sum_y / MPU6050_CALIBRATION_SAMPLES;
    ctx->gyro_bias_z = sum_z / MPU6050_CALIBRATION_SAMPLES;
    ESP_LOGI(TAG, "Gyro bias calibrated: %.2f %.2f %.2f dps",
             ctx->gyro_bias_x, ctx->gyro_bias_y, ctx->gyro_bias_z);
    return ESP_OK;
}

static esp_err_t init_mpu6050(motion_ctx_t *ctx)
{
    esp_err_t err = bsp_i2c_init();
    if (err != ESP_OK) {
        return err;
    }
    ctx->bus = bsp_i2c_get_handle();
    if (ctx->bus == NULL) {
        return ESP_ERR_INVALID_STATE;
    }

    err = i2c_master_probe(ctx->bus, CONFIG_SMART_POT_MPU6050_ADDRESS, 200);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "MPU-6050 probe failed at 0x%02x: %s",
                 CONFIG_SMART_POT_MPU6050_ADDRESS, esp_err_to_name(err));
#if CONFIG_SMART_POT_MPU6050_DEBUG_LOG
        scan_i2c_bus(ctx->bus);
#endif
        return err;
    }

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = CONFIG_SMART_POT_MPU6050_ADDRESS,
        .scl_speed_hz = 100000,
        .scl_wait_us = 20000,
    };
    err = i2c_master_bus_add_device(ctx->bus, &dev_cfg, &ctx->dev);
    if (err != ESP_OK) {
        return err;
    }

    uint8_t who_am_i = 0;
    err = read_regs(ctx->dev, MPU6050_REG_WHO_AM_I, &who_am_i, 1);
    if (err != ESP_OK || who_am_i != 0x68) {
        ESP_LOGW(TAG, "Unexpected MPU-6050 WHO_AM_I=0x%02x", who_am_i);
        i2c_master_bus_rm_device(ctx->dev);
        ctx->dev = NULL;
        return err == ESP_OK ? ESP_ERR_NOT_FOUND : err;
    }
    ESP_LOGI(TAG, "MPU-6050 WHO_AM_I=0x%02x", who_am_i);

#if CONFIG_SMART_POT_MPU6050_DEBUG_LOG
    ESP_LOGI(TAG,
             "thresholds tap=%dmg gyro<%ddps rearm=%dms double=%d-%dms shake=%dmg/%ddps moveTilt=%ddeg confirm=%dms static=%dmg/%ddps/%dms tilt=%d/%d recover=%d confirm=%dms",
             CONFIG_SMART_POT_MPU6050_TAP_DELTA_MG,
             CONFIG_SMART_POT_MPU6050_TAP_GYRO_MAX_DPS,
             CONFIG_SMART_POT_MPU6050_TAP_COOLDOWN_MS,
             CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MIN_MS,
             CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MAX_MS,
             CONFIG_SMART_POT_MPU6050_SHAKE_ACCEL_MG,
             CONFIG_SMART_POT_MPU6050_SHAKE_GYRO_DPS,
             CONFIG_SMART_POT_MPU6050_MOVE_TILT_DELTA_DEG,
             CONFIG_SMART_POT_MPU6050_MOVE_CONFIRM_MS,
             CONFIG_SMART_POT_MPU6050_STATIC_DELTA_MG,
             CONFIG_SMART_POT_MPU6050_STATIC_GYRO_DPS,
             CONFIG_SMART_POT_MPU6050_MOVE_STABLE_MS,
             CONFIG_SMART_POT_MPU6050_TILT_LIGHT_DEG,
             CONFIG_SMART_POT_MPU6050_TILT_SEVERE_DEG,
             CONFIG_SMART_POT_MPU6050_TILT_RECOVER_DEG,
             CONFIG_SMART_POT_MPU6050_TILT_CONFIRM_MS);
#endif

    err = write_reg(ctx->dev, MPU6050_REG_PWR_MGMT_1, 0x80);
    if (err != ESP_OK) {
        goto init_failed;
    }
    vTaskDelay(pdMS_TO_TICKS(100));
    err = write_reg(ctx->dev, MPU6050_REG_PWR_MGMT_1, 0x01);
    if (err != ESP_OK) {
        goto init_failed;
    }
    vTaskDelay(pdMS_TO_TICKS(10));
    err = write_reg(ctx->dev, MPU6050_REG_CONFIG, 0x03);
    if (err != ESP_OK) {
        goto init_failed;
    }
    err = write_reg(ctx->dev, MPU6050_REG_SMPLRT_DIV, 0x09);
    if (err != ESP_OK) {
        goto init_failed;
    }
    err = write_reg(ctx->dev, MPU6050_REG_GYRO_CONFIG, 0x08);
    if (err != ESP_OK) {
        goto init_failed;
    }
    err = write_reg(ctx->dev, MPU6050_REG_ACCEL_CONFIG, 0x08);
    if (err != ESP_OK) {
        goto init_failed;
    }

    ctx->gyro_bias_x = 0.0f;
    ctx->gyro_bias_y = 0.0f;
    ctx->gyro_bias_z = 0.0f;
    err = calibrate_gyro(ctx);
    if (err != ESP_OK) {
        goto init_failed;
    }

    app_motion_state_t baseline = {0};
    err = read_sample(ctx, &baseline);
    if (err != ESP_OK) {
        goto init_failed;
    }
    ctx->roll = atan2f(baseline.accel_y_g, baseline.accel_z_g) * MPU6050_RAD_TO_DEG;
    ctx->pitch = atan2f(-baseline.accel_x_g,
                        sqrtf(baseline.accel_y_g * baseline.accel_y_g +
                              baseline.accel_z_g * baseline.accel_z_g)) * MPU6050_RAD_TO_DEG;
    ctx->base_roll = ctx->roll;
    ctx->base_pitch = ctx->pitch;
    ctx->last_sample_us = esp_timer_get_time();
    ESP_LOGI(TAG, "MPU-6050 ready at 0x%02x on BSP I2C SDA=GPIO7 SCL=GPIO8",
             CONFIG_SMART_POT_MPU6050_ADDRESS);
    return ESP_OK;

init_failed:
    i2c_master_bus_rm_device(ctx->dev);
    ctx->dev = NULL;
    return err;
}

static void update_orientation(motion_ctx_t *ctx, app_motion_state_t *state, float dt)
{
    float acc_roll = atan2f(state->accel_y_g, state->accel_z_g) * MPU6050_RAD_TO_DEG;
    float acc_pitch = atan2f(-state->accel_x_g,
                            sqrtf(state->accel_y_g * state->accel_y_g +
                                  state->accel_z_g * state->accel_z_g)) * MPU6050_RAD_TO_DEG;
    const float alpha = 0.96f;
    ctx->roll = alpha * (ctx->roll + state->gyro_x_dps * dt) + (1.0f - alpha) * acc_roll;
    ctx->pitch = alpha * (ctx->pitch + state->gyro_y_dps * dt) + (1.0f - alpha) * acc_pitch;
    state->roll_deg = ctx->roll;
    state->pitch_deg = ctx->pitch;
}

static void update_events(motion_ctx_t *ctx, app_motion_state_t *state, int64_t now_us)
{
    state->moving = ctx->moving;
    state->tilt_level = ctx->tilt_level;
    float accel_mag = sqrtf(state->accel_x_g * state->accel_x_g +
                            state->accel_y_g * state->accel_y_g +
                            state->accel_z_g * state->accel_z_g);
    float accel_delta = fabsf(accel_mag - 1.0f);
    float gyro_mag = sqrtf(state->gyro_x_dps * state->gyro_x_dps +
                           state->gyro_y_dps * state->gyro_y_dps +
                           state->gyro_z_dps * state->gyro_z_dps);
    float roll_delta = fabsf(state->roll_deg - ctx->base_roll);
    float pitch_delta = fabsf(state->pitch_deg - ctx->base_pitch);
    float tilt = fmaxf(roll_delta, pitch_delta);
    bool impact = accel_mag >= MPU6050_TAP_ACCEL_G && gyro_mag < MPU6050_TAP_GYRO_MAX_DPS;
    bool shake_now = accel_mag >= MPU6050_SHAKE_ACCEL_G && gyro_mag >= MPU6050_SHAKE_GYRO_DPS;
    bool move_now = tilt >= MPU6050_MOVE_TILT_DELTA_DEG;
    bool static_now = accel_delta <= MPU6050_STATIC_ACCEL_DELTA_G && gyro_mag <= MPU6050_STATIC_GYRO_DPS;

    if (shake_now &&
        now_us - ctx->last_shake_us >= CONFIG_SMART_POT_MPU6050_SHAKE_COOLDOWN_MS * 1000LL) {
        emit_event(ctx, APP_MOTION_EVENT_SHAKE, state);
        ctx->last_shake_us = now_us;
    }

    if (impact &&
        now_us - ctx->last_impact_us >= CONFIG_SMART_POT_MPU6050_TAP_COOLDOWN_MS * 1000LL) {
        int64_t tap_interval_ms = ctx->last_tap_us == 0 ? 0 :
                                  (now_us - ctx->last_tap_us) / 1000LL;
        if (tap_interval_ms >= CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MIN_MS &&
            tap_interval_ms <= CONFIG_SMART_POT_MPU6050_DOUBLE_TAP_MAX_MS) {
            emit_event(ctx, APP_MOTION_EVENT_DOUBLE_TAP, state);
            ctx->last_tap_us = 0;
        } else {
            emit_event(ctx, APP_MOTION_EVENT_TAP, state);
            ctx->last_tap_us = now_us;
        }
        ctx->last_impact_us = now_us;
    }

    if (move_now) {
        ctx->stable_since_us = 0;
        if (ctx->move_since_us == 0) {
            ctx->move_since_us = now_us;
        }
        if (!ctx->moving && now_us - ctx->move_since_us >= CONFIG_SMART_POT_MPU6050_MOVE_CONFIRM_MS * 1000LL) {
            ctx->moving = true;
            state->moving = true;
            emit_event(ctx, APP_MOTION_EVENT_MOVE_STARTED, state);
        }
    } else {
        ctx->move_since_us = 0;
    }

    if (ctx->moving && static_now) {
        if (ctx->stable_since_us == 0) {
            ctx->stable_since_us = now_us;
        }
        if (now_us - ctx->stable_since_us >= CONFIG_SMART_POT_MPU6050_MOVE_STABLE_MS * 1000LL) {
            ctx->moving = false;
            ctx->stable_since_us = 0;
            ctx->base_roll = state->roll_deg;
            ctx->base_pitch = state->pitch_deg;
            state->moving = false;
            emit_event(ctx, APP_MOTION_EVENT_MOVE_STOPPED, state);
        }
    } else if (!static_now) {
        ctx->stable_since_us = 0;
    }

    uint8_t candidate = tilt >= MPU6050_TILT_SEVERE_DEG ? 2 :
                        tilt >= MPU6050_TILT_LIGHT_DEG ? 1 : 0;
    if (ctx->tilt_level > 0 && tilt <= MPU6050_TILT_RECOVER_DEG) {
        candidate = 0;
    } else if (ctx->tilt_level > 0 && candidate == 0) {
        candidate = ctx->tilt_level;
    }

    if (candidate != ctx->tilt_candidate) {
        ctx->tilt_candidate = candidate;
        ctx->tilt_since_us = now_us;
    } else if (candidate != ctx->tilt_level &&
               now_us - ctx->tilt_since_us >= CONFIG_SMART_POT_MPU6050_TILT_CONFIRM_MS * 1000LL) {
        ctx->tilt_level = candidate;
        state->tilt_level = candidate;
        if (candidate == 2) {
            emit_event(ctx, APP_MOTION_EVENT_TILT_SEVERE, state);
        } else if (candidate == 1) {
            emit_event(ctx, APP_MOTION_EVENT_TILT_LIGHT, state);
        } else {
            emit_event(ctx, APP_MOTION_EVENT_TILT_RECOVERED, state);
        }
    }

    state->moving = ctx->moving;
    state->tilt_level = ctx->tilt_level;
}

static void motion_task(void *arg)
{
    motion_ctx_t *ctx = (motion_ctx_t *)arg;
    while (true) {
        if (ctx->dev == NULL) {
            esp_err_t err = init_mpu6050(ctx);
            if (err != ESP_OK) {
                ESP_LOGW(TAG, "MPU-6050 init failed at 0x%02x: %s; retrying",
                         CONFIG_SMART_POT_MPU6050_ADDRESS, esp_err_to_name(err));
                vTaskDelay(pdMS_TO_TICKS(MPU6050_RETRY_MS));
                continue;
            }
        }

        app_motion_state_t state = {0};
        esp_err_t err = read_sample(ctx, &state);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "MPU-6050 read failed: %s", esp_err_to_name(err));
            i2c_master_bus_rm_device(ctx->dev);
            ctx->dev = NULL;
            vTaskDelay(pdMS_TO_TICKS(MPU6050_RETRY_MS));
            continue;
        }

        int64_t now_us = esp_timer_get_time();
        float dt = (now_us - ctx->last_sample_us) / 1000000.0f;
        if (dt <= 0.0f || dt > 0.1f) {
            dt = MPU6050_SAMPLE_PERIOD_MS / 1000.0f;
        }
        ctx->last_sample_us = now_us;
        update_orientation(ctx, &state, dt);
        update_events(ctx, &state, now_us);
        publish_state(&state);
#if CONFIG_SMART_POT_MPU6050_DEBUG_LOG
        log_tuning_sample(ctx, &state, now_us);
#endif
        vTaskDelay(pdMS_TO_TICKS(MPU6050_SAMPLE_PERIOD_MS));
    }
}

void app_motion_start(app_motion_event_cb_t cb, void *user_ctx)
{
#if CONFIG_SMART_POT_MPU6050_ENABLE
    if (s_state_lock == NULL) {
        s_state_lock = xSemaphoreCreateMutex();
    }
    motion_ctx_t *ctx = calloc(1, sizeof(*ctx));
    if (ctx == NULL) {
        ESP_LOGE(TAG, "Failed to allocate MPU-6050 context");
        return;
    }
    ctx->cb = cb;
    ctx->user_ctx = user_ctx;
    if (xTaskCreate(motion_task, "smart_pot_motion", 4096, ctx, 5, NULL) != pdPASS) {
        ESP_LOGE(TAG, "Failed to create MPU-6050 task");
        free(ctx);
    }
#else
    (void)cb;
    (void)user_ctx;
    ESP_LOGI(TAG, "MPU-6050 support disabled");
#endif
}

bool app_motion_get_state(app_motion_state_t *state)
{
    if (state == NULL || s_state_lock == NULL ||
        xSemaphoreTake(s_state_lock, pdMS_TO_TICKS(20)) != pdTRUE) {
        return false;
    }
    bool valid = s_state_valid;
    if (valid) {
        *state = s_state;
    }
    xSemaphoreGive(s_state_lock);
    return valid;
}
