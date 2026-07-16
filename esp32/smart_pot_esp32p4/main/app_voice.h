#pragma once

#include <stdbool.h>
#include <stdint.h>

void app_voice_start(void);
void app_voice_request_conversation(void);
bool app_voice_toggle_long_conversation(void);
bool app_voice_set_long_conversation(bool enabled);
bool app_voice_long_conversation_enabled(void);
void app_voice_conversation_complete(void);
void app_voice_conversation_complete_with_followup(bool enable_followup);
bool app_voice_pause_microphone(uint32_t timeout_ms);
bool app_voice_resume_microphone(uint32_t timeout_ms);
