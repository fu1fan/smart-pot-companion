#include "app_volc_protocol.h"

#include <stdlib.h>
#include <string.h>

#define VOLC_VERSION 1
#define VOLC_HEADER_WORDS 1
#define VOLC_SERIALIZATION_RAW 0
#define VOLC_SERIALIZATION_JSON 1
#define VOLC_COMPRESSION_NONE 0

static void write_be32(uint8_t *dst, uint32_t value)
{
    dst[0] = (uint8_t)(value >> 24);
    dst[1] = (uint8_t)(value >> 16);
    dst[2] = (uint8_t)(value >> 8);
    dst[3] = (uint8_t)value;
}

static uint32_t read_be32(const uint8_t *src)
{
    return ((uint32_t)src[0] << 24) |
           ((uint32_t)src[1] << 16) |
           ((uint32_t)src[2] << 8) |
           (uint32_t)src[3];
}

static bool event_has_no_session(int32_t event)
{
    return event == APP_VOLC_EVENT_START_CONNECTION ||
           event == APP_VOLC_EVENT_FINISH_CONNECTION ||
           event == APP_VOLC_EVENT_CONNECTION_STARTED ||
           event == APP_VOLC_EVENT_CONNECTION_FAILED ||
           event == APP_VOLC_EVENT_CONNECTION_FINISHED;
}

static bool event_has_connect_id(int32_t event)
{
    return event == APP_VOLC_EVENT_CONNECTION_STARTED ||
           event == APP_VOLC_EVENT_CONNECTION_FAILED ||
           event == APP_VOLC_EVENT_CONNECTION_FINISHED;
}

static uint8_t *build_frame(app_volc_msg_type_t type,
                            app_volc_msg_flag_t flag,
                            uint8_t serialization,
                            int32_t event,
                            const char *session_id,
                            const void *payload,
                            size_t payload_len,
                            size_t *frame_len)
{
    size_t session_len = session_id != NULL ? strlen(session_id) : 0;
    size_t extra = 0;
    if (flag == APP_VOLC_FLAG_WITH_EVENT) {
        extra += 4;
        if (!event_has_no_session(event)) {
            extra += 4 + session_len;
        }
    }
    size_t total = 4 + extra + 4 + payload_len;
    uint8_t *frame = malloc(total);
    if (frame == NULL) {
        return NULL;
    }

    frame[0] = (VOLC_VERSION << 4) | VOLC_HEADER_WORDS;
    frame[1] = ((uint8_t)type << 4) | (uint8_t)flag;
    frame[2] = (serialization << 4) | VOLC_COMPRESSION_NONE;
    frame[3] = 0;

    size_t offset = 4;
    if (flag == APP_VOLC_FLAG_WITH_EVENT) {
        write_be32(frame + offset, (uint32_t)event);
        offset += 4;
        if (!event_has_no_session(event)) {
            write_be32(frame + offset, (uint32_t)session_len);
            offset += 4;
            if (session_len > 0) {
                memcpy(frame + offset, session_id, session_len);
                offset += session_len;
            }
        }
    }
    write_be32(frame + offset, (uint32_t)payload_len);
    offset += 4;
    if (payload_len > 0 && payload != NULL) {
        memcpy(frame + offset, payload, payload_len);
    }
    *frame_len = total;
    return frame;
}

uint8_t *app_volc_build_event_frame(app_volc_event_t event,
                                    const char *session_id,
                                    const void *payload,
                                    size_t payload_len,
                                    size_t *frame_len)
{
    return build_frame(APP_VOLC_MSG_FULL_CLIENT, APP_VOLC_FLAG_WITH_EVENT,
                       VOLC_SERIALIZATION_JSON, event, session_id,
                       payload, payload_len, frame_len);
}

uint8_t *app_volc_build_full_request(const void *payload, size_t payload_len, size_t *frame_len)
{
    return build_frame(APP_VOLC_MSG_FULL_CLIENT, APP_VOLC_FLAG_NO_SEQ,
                       VOLC_SERIALIZATION_JSON, APP_VOLC_EVENT_NONE, NULL,
                       payload, payload_len, frame_len);
}

uint8_t *app_volc_build_audio_request(const void *payload,
                                      size_t payload_len,
                                      bool last,
                                      size_t *frame_len)
{
    return build_frame(APP_VOLC_MSG_AUDIO_CLIENT,
                       last ? APP_VOLC_FLAG_LAST_NO_SEQ : APP_VOLC_FLAG_NO_SEQ,
                       VOLC_SERIALIZATION_RAW, APP_VOLC_EVENT_NONE, NULL,
                       payload, payload_len, frame_len);
}

static bool take_bytes(const uint8_t *data, size_t len, size_t *offset,
                       size_t count, const uint8_t **out)
{
    if (*offset > len || count > len - *offset) {
        return false;
    }
    if (out != NULL) {
        *out = data + *offset;
    }
    *offset += count;
    return true;
}

static bool take_u32(const uint8_t *data, size_t len, size_t *offset, uint32_t *value)
{
    const uint8_t *p = NULL;
    if (!take_bytes(data, len, offset, 4, &p)) {
        return false;
    }
    *value = read_be32(p);
    return true;
}

bool app_volc_parse_message(const uint8_t *data, size_t len, app_volc_message_t *message)
{
    if (data == NULL || message == NULL || len < 4) {
        return false;
    }
    memset(message, 0, sizeof(*message));
    uint8_t header_words = data[0] & 0x0f;
    size_t header_len = (size_t)header_words * 4;
    if ((data[0] >> 4) != VOLC_VERSION || header_len < 4 || header_len > len) {
        return false;
    }

    message->type = (app_volc_msg_type_t)(data[1] >> 4);
    message->flag = (app_volc_msg_flag_t)(data[1] & 0x0f);
    message->serialization = data[2] >> 4;
    message->compression = data[2] & 0x0f;
    if (message->compression != VOLC_COMPRESSION_NONE) {
        return false;
    }

    size_t offset = header_len;
    uint32_t value = 0;
    if (message->type == APP_VOLC_MSG_ERROR) {
        if (!take_u32(data, len, &offset, &message->error_code)) {
            return false;
        }
    } else if (message->flag == APP_VOLC_FLAG_POSITIVE_SEQ ||
               message->flag == APP_VOLC_FLAG_NEGATIVE_SEQ) {
        if (!take_u32(data, len, &offset, &value)) {
            return false;
        }
        message->sequence = (int32_t)value;
    }

    if (message->flag == APP_VOLC_FLAG_WITH_EVENT) {
        if (!take_u32(data, len, &offset, &value)) {
            return false;
        }
        message->event = (int32_t)value;
        if (!event_has_no_session(message->event)) {
            if (!take_u32(data, len, &offset, &value) ||
                !take_bytes(data, len, &offset, value, &message->session_id)) {
                return false;
            }
            message->session_id_len = value;
        }
        if (event_has_connect_id(message->event)) {
            if (!take_u32(data, len, &offset, &value) ||
                !take_bytes(data, len, &offset, value, &message->connect_id)) {
                return false;
            }
            message->connect_id_len = value;
        }
    }

    if (!take_u32(data, len, &offset, &value) ||
        !take_bytes(data, len, &offset, value, &message->payload)) {
        return false;
    }
    message->payload_len = value;
    return offset == len;
}
