#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    APP_MOOD_HAPPY = 0,
    APP_MOOD_THIRSTY,
    APP_MOOD_DARK,
    APP_MOOD_WEAK,
} app_mood_t;

typedef struct {
    uint8_t soil_percent;
    uint8_t light_percent;
    uint32_t touch_count;
    bool touch_active;
    uint32_t light_lux;
    uint32_t soil_frequency_hz;
    uint32_t soil_adc_raw;
    bool soil_digital_dry;
    app_mood_t mood;
} app_plant_state_t;
