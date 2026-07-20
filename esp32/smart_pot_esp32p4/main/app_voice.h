#pragma once

#include <stdbool.h>
#include <stdint.h>

void app_voice_start(void);
void app_voice_request_conversation(void);
void app_voice_conversation_complete(void);
bool app_voice_pause_microphone(uint32_t timeout_ms);
bool app_voice_resume_microphone(uint32_t timeout_ms);
