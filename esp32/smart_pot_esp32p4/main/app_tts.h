#pragma once

#include <stdbool.h>

typedef enum {
    APP_TTS_TONE_CHEERFUL = 0,
    APP_TTS_TONE_WORRIED,
    APP_TTS_TONE_SLEEPY,
    APP_TTS_TONE_FLUSTERED,
} app_tts_tone_t;

void app_tts_start(void);
bool app_tts_prepare_connection(void);
bool app_tts_stream_begin(const char *voice_instruction, bool enable_followup);
bool app_tts_stream_push(const char *text);
bool app_tts_stream_finish(void);
void app_tts_stream_abort(void);
bool app_tts_speak_text(const char *text);
bool app_tts_speak_text_no_followup(const char *text);
bool app_tts_speak_text_quietly(const char *text);
bool app_tts_speak_text_with_tone(const char *text, app_tts_tone_t tone);
bool app_tts_speak_stream_segment(const char *text);
bool app_tts_finish_stream(void);
bool app_tts_play_success_chime(void);
