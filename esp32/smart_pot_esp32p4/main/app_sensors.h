#pragma once

#include "app_types.h"

typedef void (*app_sensor_update_cb_t)(const app_plant_state_t *state, void *user_ctx);

void app_sensors_start(app_sensor_update_cb_t cb, void *user_ctx);
