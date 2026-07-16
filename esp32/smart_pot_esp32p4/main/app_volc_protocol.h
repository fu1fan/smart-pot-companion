#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef enum {
    APP_VOLC_MSG_FULL_CLIENT = 0x1,
    APP_VOLC_MSG_AUDIO_CLIENT = 0x2,
    APP_VOLC_MSG_FULL_SERVER = 0x9,
    APP_VOLC_MSG_AUDIO_SERVER = 0xb,
    APP_VOLC_MSG_FRONTEND_SERVER = 0xc,
    APP_VOLC_MSG_ERROR = 0xf,
} app_volc_msg_type_t;

typedef enum {
    APP_VOLC_FLAG_NO_SEQ = 0,
    APP_VOLC_FLAG_POSITIVE_SEQ = 1,
    APP_VOLC_FLAG_LAST_NO_SEQ = 2,
    APP_VOLC_FLAG_NEGATIVE_SEQ = 3,
    APP_VOLC_FLAG_WITH_EVENT = 4,
} app_volc_msg_flag_t;

typedef enum {
    APP_VOLC_EVENT_NONE = 0,
    APP_VOLC_EVENT_START_CONNECTION = 1,
    APP_VOLC_EVENT_FINISH_CONNECTION = 2,
    APP_VOLC_EVENT_CONNECTION_STARTED = 50,
    APP_VOLC_EVENT_CONNECTION_FAILED = 51,
    APP_VOLC_EVENT_CONNECTION_FINISHED = 52,
    APP_VOLC_EVENT_START_SESSION = 100,
    APP_VOLC_EVENT_CANCEL_SESSION = 101,
    APP_VOLC_EVENT_FINISH_SESSION = 102,
    APP_VOLC_EVENT_SESSION_STARTED = 150,
    APP_VOLC_EVENT_SESSION_CANCELED = 151,
    APP_VOLC_EVENT_SESSION_FINISHED = 152,
    APP_VOLC_EVENT_SESSION_FAILED = 153,
    APP_VOLC_EVENT_USAGE_RESPONSE = 154,
    APP_VOLC_EVENT_TASK_REQUEST = 200,
    APP_VOLC_EVENT_TTS_SENTENCE_START = 350,
    APP_VOLC_EVENT_TTS_SENTENCE_END = 351,
    APP_VOLC_EVENT_TTS_RESPONSE = 352,
    APP_VOLC_EVENT_TTS_ENDED = 359,
    APP_VOLC_EVENT_TTS_SUBTITLE = 364,
} app_volc_event_t;

typedef struct {
    app_volc_msg_type_t type;
    app_volc_msg_flag_t flag;
    uint8_t serialization;
    uint8_t compression;
    int32_t event;
    int32_t sequence;
    uint32_t error_code;
    const uint8_t *session_id;
    size_t session_id_len;
    const uint8_t *connect_id;
    size_t connect_id_len;
    const uint8_t *payload;
    size_t payload_len;
} app_volc_message_t;

uint8_t *app_volc_build_event_frame(app_volc_event_t event,
                                    const char *session_id,
                                    const void *payload,
                                    size_t payload_len,
                                    size_t *frame_len);
uint8_t *app_volc_build_full_request(const void *payload, size_t payload_len, size_t *frame_len);
uint8_t *app_volc_build_audio_request(const void *payload,
                                      size_t payload_len,
                                      bool last,
                                      size_t *frame_len);
bool app_volc_parse_message(const uint8_t *data, size_t len, app_volc_message_t *message);

