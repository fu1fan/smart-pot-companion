#pragma once

#include "app_types.h"

typedef void (*app_sensor_update_cb_t)(const app_plant_state_t *state, void *user_ctx);

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
} app_light_strip_state_t;

void app_sensors_start(app_sensor_update_cb_t cb, void *user_ctx);
void app_sensors_set_light_strip_manual_mode(bool enabled);
void app_sensors_set_light_strip_manual_on(bool on);
bool app_sensors_set_light_strip_off_period(bool enabled, uint16_t start_minute, uint16_t end_minute);
bool app_sensors_set_plant_thresholds(uint8_t soil_min_percent, uint8_t soil_max_percent,
                                      uint32_t light_min_lux, uint32_t light_max_lux);
void app_sensors_get_light_strip_state(app_light_strip_state_t *out);
