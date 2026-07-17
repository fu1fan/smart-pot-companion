#pragma once

#include <stdint.h>

#include "app_types.h"

typedef enum {
    APP_UI_MOTION_REACTION_TAP = 0,
    APP_UI_MOTION_REACTION_SHAKE,
    APP_UI_MOTION_REACTION_CARRIED,
} app_ui_motion_reaction_t;

void app_ui_init(void);
void app_ui_update(const app_plant_state_t *state);
void app_ui_set_network_status(const char *status);
void app_ui_set_dialog_status(const char *status);
void app_ui_set_voice_status(const char *status);
void app_ui_refresh_long_mode(void);
void app_ui_play_touch_reaction(void);
void app_ui_play_motion_reaction(app_ui_motion_reaction_t reaction, uint32_t duration_ms);
void app_ui_clear_motion_reaction(void);
void app_ui_show_remote_content(const char *text, uint32_t duration_ms);
bool app_ui_start_pomodoro(void);
void app_ui_stop_pomodoro(void);
void app_ui_show_schedule_page(void);
void app_ui_add_schedule(const char *item, const char *deadline);
