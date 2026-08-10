#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

void app_voice_start(void);
void app_voice_request_conversation(void);
void app_voice_conversation_complete(void);
bool app_voice_conversation_is_active(void);
bool app_voice_pause_microphone(uint32_t timeout_ms);
bool app_voice_resume_microphone(uint32_t timeout_ms);
bool app_voice_audio_lock(TickType_t timeout);
void app_voice_audio_unlock(void);
