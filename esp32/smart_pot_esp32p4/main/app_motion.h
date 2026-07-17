#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    APP_MOTION_EVENT_TAP = 0,
    APP_MOTION_EVENT_SHAKE,
    APP_MOTION_EVENT_FALLEN,
    APP_MOTION_EVENT_FALL_RECOVERED,
} app_motion_event_t;

typedef struct {
    float accel_x_g;
    float accel_y_g;
    float accel_z_g;
    float gyro_x_dps;
    float gyro_y_dps;
    float gyro_z_dps;
    float roll_deg;
    float pitch_deg;
    float tilt_delta_deg;
    uint8_t tilt_level;
} app_motion_state_t;

typedef void (*app_motion_event_cb_t)(app_motion_event_t event,
                                      const app_motion_state_t *state,
                                      void *user_ctx);

void app_motion_start(app_motion_event_cb_t cb, void *user_ctx);
bool app_motion_get_state(app_motion_state_t *state);
