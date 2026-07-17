#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "app_types.h"

typedef enum {
    APP_UI_MOTION_REACTION_TAP = 0,
    APP_UI_MOTION_REACTION_SHAKE,
    APP_UI_MOTION_REACTION_CARRIED,
} app_ui_motion_reaction_t;

typedef struct {
    bool valid;
    float accel_x_g;
    float accel_y_g;
    float accel_z_g;
    float accel_mag_g;
    float gyro_x_dps;
    float gyro_y_dps;
    float gyro_z_dps;
    float gyro_mag_dps;
    float roll_deg;
    float pitch_deg;
    float tilt_delta_deg;
    float tilt_trigger_deg;
    uint32_t event_count;
    uint8_t tilt_level;
} app_ui_motion_debug_state_t;

void app_ui_init(void);
void app_ui_update(const app_plant_state_t *state);
void app_ui_set_network_status(const char *status);
void app_ui_set_dialog_status(const char *status);
void app_ui_set_voice_status(const char *status);
void app_ui_refresh_long_mode(void);
void app_ui_play_touch_reaction(void);
void app_ui_play_motion_reaction(app_ui_motion_reaction_t reaction, uint32_t duration_ms);
void app_ui_clear_motion_reaction(void);
void app_ui_update_motion_debug(const app_ui_motion_debug_state_t *state);
void app_ui_set_motion_debug_event(const char *event, const char *reaction);
void app_ui_show_remote_content(const char *text, uint32_t duration_ms);
bool app_ui_start_pomodoro(void);
void app_ui_stop_pomodoro(void);
void app_ui_show_schedule_page(void);
void app_ui_add_schedule(const char *item, const char *deadline);
