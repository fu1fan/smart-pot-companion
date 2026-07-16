#pragma once

#include <stdbool.h>
#include "cJSON.h"

typedef enum {
    APP_MEMORY_COMMAND_NONE = 0,
    APP_MEMORY_COMMAND_CLEAR_DIALOGUE,
    APP_MEMORY_COMMAND_CLEAR_PROFILE,
    APP_MEMORY_COMMAND_CLEAR_ALL,
} app_memory_command_t;

void app_memory_init(void);
void app_memory_append_profile_message(cJSON *messages);
void app_memory_append_messages(cJSON *messages);
void app_memory_add_exchange(const char *user_text, const char *assistant_text);
void app_memory_learn_profile(const char *user_text);
void app_memory_clear_dialogue(void);
void app_memory_clear_profile(void);
void app_memory_clear_all(void);
app_memory_command_t app_memory_get_command(const char *text);
