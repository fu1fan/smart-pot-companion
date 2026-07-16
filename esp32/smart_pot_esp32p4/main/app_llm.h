#pragma once

#include <stdbool.h>
#include "app_types.h"

void app_llm_start(void);
void app_llm_update_plant_state(const app_plant_state_t *state);
void app_llm_request_care_tip(const char *trigger);
bool app_llm_request_voice_reply(const char *user_text);
