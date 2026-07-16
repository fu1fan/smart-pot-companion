#pragma once

#include "sdkconfig.h"
#include "app_types.h"
#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
#include "app_motion.h"
#endif

void app_cloud_start(void);
void app_cloud_update_plant_state(const app_plant_state_t *state);
#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
void app_cloud_update_motion(app_motion_event_t event, const app_motion_state_t *state);
#endif
