#include "app_tts.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "bsp/esp-bsp.h"
#include "cJSON.h"
#include "esp_codec_dev.h"
#include "esp_crt_bundle.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_websocket_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "app_ui.h"
#include "app_voice.h"
#include "app_volc_protocol.h"

#ifndef CONFIG_SMART_POT_TTS_ENABLE
#define CONFIG_SMART_POT_TTS_ENABLE 0
#endif
#ifndef CONFIG_SMART_POT_VOLC_API_KEY
#define CONFIG_SMART_POT_VOLC_API_KEY ""
#endif
#ifndef CONFIG_SMART_POT_TTS_ENDPOINT
#define CONFIG_SMART_POT_TTS_ENDPOINT "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
#endif
#ifndef CONFIG_SMART_POT_VOLC_TTS_RESOURCE_ID
#define CONFIG_SMART_POT_VOLC_TTS_RESOURCE_ID "seed-tts-2.0"
#endif
#ifndef CONFIG_SMART_POT_VOLC_TTS_SPEAKER
#define CONFIG_SMART_POT_VOLC_TTS_SPEAKER "zh_female_yingtaowanzi_uranus_bigtts"
#endif
#ifndef CONFIG_SMART_POT_VOLC_TTS_SAMPLE_RATE
#define CONFIG_SMART_POT_VOLC_TTS_SAMPLE_RATE 24000
#endif
#ifndef CONFIG_SMART_POT_TTS_STARTUP_TEST
#define CONFIG_SMART_POT_TTS_STARTUP_TEST 0
#endif

#define TTS_QUEUE_DEPTH 20
#define TTS_TEXT_MAX 256
#define TTS_STYLE_MAX 160
#define TTS_PCM_CAPACITY (1024 * 1024)
#define TTS_PREBUFFER_BYTES (24 * 1024)
#define TTS_PLAY_CHUNK 2048
#define TTS_CONNECT_TIMEOUT_MS 8000
#define TTS_SESSION_TIMEOUT_MS 30000
#define TTS_VOLUME_CONVERSATION 100
#define TTS_VOLUME_COMMAND 100
#define TTS_VOLUME_AUTOMATION 100
#define CHIME_SAMPLE_RATE 22050
#define CHIME_CHUNK_SAMPLES 128

static uint8_t s_user_volume = 100;

static uint8_t apply_user_volume(uint8_t base)
{
    return (uint8_t)(((uint16_t)base * s_user_volume) / 100U);
}

void app_tts_set_volume(uint8_t volume_percent)
{
    s_user_volume = volume_percent > 100 ? 100 : volume_percent;
}

uint8_t app_tts_get_volume(void)
{
    return s_user_volume;
}

#define TTS_EVENT_WS_CONNECTED BIT0
#define TTS_EVENT_CONNECTION_STARTED BIT1
#define TTS_EVENT_SESSION_STARTED BIT2
#define TTS_EVENT_SESSION_FINISHED BIT3
#define TTS_EVENT_FAILED BIT4
#define TTS_EVENT_ALL (TTS_EVENT_WS_CONNECTED | TTS_EVENT_CONNECTION_STARTED | \
                       TTS_EVENT_SESSION_STARTED | TTS_EVENT_SESSION_FINISHED | \
                       TTS_EVENT_FAILED)

static const char *TAG = "app_tts";

typedef enum {
    TTS_CMD_ONE_SHOT = 0,
    TTS_CMD_PRECONNECT,
    TTS_CMD_STREAM_BEGIN,
    TTS_CMD_STREAM_TEXT,
    TTS_CMD_STREAM_FINISH,
    TTS_CMD_STREAM_ABORT,
} tts_command_t;

typedef struct {
    tts_command_t command;
    char text[TTS_TEXT_MAX];
    char style[TTS_STYLE_MAX];
    bool complete_conversation;
    uint8_t volume;
    int16_t speech_rate;
    int8_t pitch;
} tts_msg_t;

typedef struct {
    EventGroupHandle_t events;
    esp_websocket_client_handle_t client;
    esp_codec_dev_handle_t speaker;
    TaskHandle_t owner;
    TaskHandle_t playback_task;
    char session_id[37];
    char section_id[37];
    uint8_t *pcm;
    volatile size_t pcm_len;
    size_t pcm_cap;
    volatile bool session_done;
    volatile bool session_ok;
    volatile bool overflow;
    volatile bool playback_ok;
    volatile bool playback_started;
    volatile uint32_t tasks_sent;
    volatile uint32_t tasks_finished;
    uint8_t volume;
    int64_t first_audio_us;
    int64_t session_started_us;
    char error[384];
    uint8_t *fragment;
    size_t fragment_len;
    size_t fragment_cap;
} tts_runtime_t;

static QueueHandle_t s_tts_queue;
static volatile bool s_tts_busy;
static volatile bool s_stream_requested;
static volatile bool s_preconnect_requested;
static volatile bool s_chime_busy;
static esp_codec_dev_handle_t s_chime_spk;

static const int16_t s_sine_lut[32] = {
    0, 6393, 12539, 18204, 23170, 27245, 30273, 32137,
    32767, 32137, 30273, 27245, 23170, 18204, 12539, 6393,
    0, -6393, -12539, -18204, -23170, -27245, -30273, -32137,
    -32767, -32137, -30273, -27245, -23170, -18204, -12539, -6393
};

static bool allocate_pcm_buffer(tts_runtime_t *rt)
{
    if (rt->pcm != NULL) return true;
    rt->pcm_cap = TTS_PCM_CAPACITY;
    rt->pcm = heap_caps_malloc(rt->pcm_cap, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (rt->pcm == NULL) {
        rt->pcm_cap = 0;
        snprintf(rt->error, sizeof(rt->error), "TTS PCM allocation failed");
        return false;
    }
    ESP_LOGI(TAG, "Allocated %u-byte TTS PCM buffer in PSRAM",
             (unsigned int)rt->pcm_cap);
    return true;
}

static void release_pcm_buffer(tts_runtime_t *rt)
{
    if (rt->pcm == NULL) return;
    heap_caps_free(rt->pcm);
    rt->pcm = NULL;
    rt->pcm_cap = 0;
    rt->pcm_len = 0;
    rt->session_id[0] = '\0';
    ESP_LOGI(TAG, "Released TTS PCM buffer; PSRAM free=%u",
             (unsigned int)heap_caps_get_free_size(MALLOC_CAP_SPIRAM));
}

static void make_uuid(char out[37])
{
    uint8_t b[16];
    for (size_t i = 0; i < sizeof(b); i += 4) {
        uint32_t r = esp_random();
        memcpy(b + i, &r, 4);
    }
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    snprintf(out, 37,
             "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
             b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
             b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
}

static size_t utf8_sequence_size(unsigned char lead)
{
    if (lead < 0x80) return 1;
    if (lead >= 0xc2 && lead <= 0xdf) return 2;
    if (lead >= 0xe0 && lead <= 0xef) return 3;
    if (lead >= 0xf0 && lead <= 0xf4) return 4;
    return 0;
}

static void utf8_strlcpy(char *dst, const char *src, size_t dst_size)
{
    if (dst_size == 0) return;
    size_t si = 0;
    size_t di = 0;
    while (src != NULL && src[si] != '\0' && di + 1 < dst_size) {
        size_t count = utf8_sequence_size((unsigned char)src[si]);
        if (count == 0) {
            si++;
            continue;
        }
        bool valid = true;
        for (size_t i = 1; i < count; i++) {
            unsigned char byte = (unsigned char)src[si + i];
            if (byte == '\0' || (byte & 0xc0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (!valid) {
            si++;
            continue;
        }
        if (di + count >= dst_size) break;
        memcpy(dst + di, src + si, count);
        di += count;
        si += count;
    }
    dst[di] = '\0';
}

static bool session_matches(const app_volc_message_t *message, const char *session_id)
{
    size_t expected = strlen(session_id);
    return message->session_id_len == 0 ||
           (message->session_id_len == expected &&
            memcmp(message->session_id, session_id, expected) == 0);
}

static void process_ws_frame(tts_runtime_t *rt, const uint8_t *data, size_t len)
{
    app_volc_message_t message;
    if (!app_volc_parse_message(data, len, &message)) {
        snprintf(rt->error, sizeof(rt->error), "malformed TTS frame (%u bytes)", (unsigned int)len);
        ESP_LOGW(TAG, "%s", rt->error);
        return;
    }
    if (message.type == APP_VOLC_MSG_ERROR) {
        size_t copy = message.payload_len < sizeof(rt->error) - 1 ?
                      message.payload_len : sizeof(rt->error) - 1;
        memcpy(rt->error, message.payload, copy);
        rt->error[copy] = '\0';
        ESP_LOGE(TAG, "Volc TTS protocol error code=%u body=%s",
                 (unsigned int)message.error_code, rt->error);
        xEventGroupSetBits(rt->events, TTS_EVENT_FAILED);
        return;
    }
    if (message.event != APP_VOLC_EVENT_TTS_RESPONSE) {
        int preview_len = message.payload_len < 192 ? (int)message.payload_len : 192;
        ESP_LOGI(TAG, "Volc TTS server event=%d type=%d payload_len=%u body=%.*s",
                 message.event, message.type, (unsigned int)message.payload_len,
                 preview_len, preview_len > 0 ? (const char *)message.payload : "");
    }
    if (message.event == APP_VOLC_EVENT_CONNECTION_STARTED) {
        xEventGroupSetBits(rt->events, TTS_EVENT_CONNECTION_STARTED);
        ESP_LOGI(TAG, "Volc TTS connection started");
    } else if (message.event == APP_VOLC_EVENT_CONNECTION_FAILED ||
               message.event == APP_VOLC_EVENT_SESSION_FAILED) {
        size_t copy = message.payload_len < sizeof(rt->error) - 1 ?
                      message.payload_len : sizeof(rt->error) - 1;
        memcpy(rt->error, message.payload, copy);
        rt->error[copy] = '\0';
        ESP_LOGE(TAG, "Volc TTS event failed event=%d body=%s", message.event, rt->error);
        xEventGroupSetBits(rt->events, TTS_EVENT_FAILED);
    } else if (message.event == APP_VOLC_EVENT_SESSION_STARTED &&
               session_matches(&message, rt->session_id)) {
        rt->session_started_us = esp_timer_get_time();
        xEventGroupSetBits(rt->events, TTS_EVENT_SESSION_STARTED);
        ESP_LOGI(TAG, "Volc TTS session started id=%s", rt->session_id);
    } else if (message.event == APP_VOLC_EVENT_TTS_RESPONSE &&
               session_matches(&message, rt->session_id)) {
        if (rt->pcm_len + message.payload_len > rt->pcm_cap) {
            rt->overflow = true;
            snprintf(rt->error, sizeof(rt->error), "TTS PCM buffer overflow");
            xEventGroupSetBits(rt->events, TTS_EVENT_FAILED);
            return;
        }
        if (rt->first_audio_us == 0) {
            rt->first_audio_us = esp_timer_get_time();
            ESP_LOGI(TAG, "Volc TTS first audio after %lld ms",
                     (rt->first_audio_us - rt->session_started_us) / 1000);
        }
        memcpy(rt->pcm + rt->pcm_len, message.payload, message.payload_len);
        rt->pcm_len += message.payload_len;
    } else if (message.event == APP_VOLC_EVENT_TTS_SENTENCE_END &&
               session_matches(&message, rt->session_id)) {
        rt->tasks_finished++;
        ESP_LOGI(TAG, "Volc TTS sentence complete %u/%u",
                 (unsigned int)rt->tasks_finished, (unsigned int)rt->tasks_sent);
    } else if (message.event == APP_VOLC_EVENT_TTS_ENDED &&
               session_matches(&message, rt->session_id)) {
        rt->tasks_finished = rt->tasks_sent;
    } else if (message.event == APP_VOLC_EVENT_SESSION_FINISHED &&
               session_matches(&message, rt->session_id)) {
        rt->session_ok = !rt->overflow && rt->pcm_len > 0;
        rt->session_done = true;
        xEventGroupSetBits(rt->events, TTS_EVENT_SESSION_FINISHED);
        ESP_LOGI(TAG, "Volc TTS session finished bytes=%u", (unsigned int)rt->pcm_len);
    }
}

static void websocket_event_handler(void *handler_args, esp_event_base_t base,
                                    int32_t event_id, void *event_data)
{
    (void)base;
    tts_runtime_t *rt = (tts_runtime_t *)handler_args;
    esp_websocket_event_data_t *event = (esp_websocket_event_data_t *)event_data;
    if (rt == NULL) return;
    switch (event_id) {
    case WEBSOCKET_EVENT_CONNECTED:
        xEventGroupSetBits(rt->events, TTS_EVENT_WS_CONNECTED);
        break;
    case WEBSOCKET_EVENT_DATA:
        if (event == NULL || event->data_ptr == NULL || event->data_len <= 0 || event->op_code != 0x2) break;
        if (event->payload_offset == 0 && event->data_len == event->payload_len) {
            process_ws_frame(rt, (const uint8_t *)event->data_ptr, event->data_len);
            break;
        }
        if (event->payload_offset == 0) {
            free(rt->fragment);
            rt->fragment = malloc(event->payload_len);
            rt->fragment_cap = rt->fragment != NULL ? event->payload_len : 0;
            rt->fragment_len = 0;
        }
        if (rt->fragment != NULL &&
            (size_t)event->payload_offset + event->data_len <= rt->fragment_cap) {
            memcpy(rt->fragment + event->payload_offset, event->data_ptr, event->data_len);
            rt->fragment_len += event->data_len;
            if (rt->fragment_len >= rt->fragment_cap) {
                process_ws_frame(rt, rt->fragment, rt->fragment_cap);
                free(rt->fragment);
                rt->fragment = NULL;
                rt->fragment_len = 0;
                rt->fragment_cap = 0;
            }
        }
        break;
    case WEBSOCKET_EVENT_ERROR:
        snprintf(rt->error, sizeof(rt->error), "TTS WebSocket transport error");
        xEventGroupSetBits(rt->events, TTS_EVENT_FAILED);
        break;
    case WEBSOCKET_EVENT_DISCONNECTED:
        if (!rt->session_done) xEventGroupSetBits(rt->events, TTS_EVENT_FAILED);
        xEventGroupClearBits(rt->events, TTS_EVENT_WS_CONNECTED | TTS_EVENT_CONNECTION_STARTED);
        break;
    default:
        break;
    }
}

static bool send_event(tts_runtime_t *rt, app_volc_event_t event,
                       const char *session_id, const char *json)
{
    size_t frame_len = 0;
    const char *payload = json != NULL ? json : "{}";
    uint8_t *frame = app_volc_build_event_frame(event, session_id, payload,
                                                 strlen(payload), &frame_len);
    if (frame == NULL) return false;
    int sent = esp_websocket_client_send_bin(rt->client, (const char *)frame, frame_len,
                                              pdMS_TO_TICKS(3000));
    free(frame);
    return sent == (int)frame_len;
}

static void close_connection(tts_runtime_t *rt)
{
    if (rt->client != NULL) {
        if ((xEventGroupGetBits(rt->events) & TTS_EVENT_CONNECTION_STARTED) != 0) {
            (void)send_event(rt, APP_VOLC_EVENT_FINISH_CONNECTION, NULL, "{}");
        }
        esp_websocket_client_stop(rt->client);
        esp_websocket_client_destroy(rt->client);
        rt->client = NULL;
    }
    /* FreeRTOS reserves the upper byte of EventBits_t for internal control.
     * Clearing 0xffffffff therefore trips configASSERT on ESP-IDF. */
    xEventGroupClearBits(rt->events, TTS_EVENT_ALL);
}

static bool ensure_connection(tts_runtime_t *rt)
{
    EventBits_t bits = xEventGroupGetBits(rt->events);
    if (rt->client != NULL &&
        (bits & (TTS_EVENT_WS_CONNECTED | TTS_EVENT_CONNECTION_STARTED)) ==
        (TTS_EVENT_WS_CONNECTED | TTS_EVENT_CONNECTION_STARTED)) {
        return true;
    }
    close_connection(rt);
    char connect_id[37];
    make_uuid(connect_id);
    char headers[448];
    snprintf(headers, sizeof(headers),
             "X-Api-Key: %s\r\n"
             "X-Api-Resource-Id: %s\r\n"
             "X-Api-Connect-Id: %s\r\n"
             "X-Control-Require-Usage-Tokens-Return: *\r\n",
             CONFIG_SMART_POT_VOLC_API_KEY, CONFIG_SMART_POT_VOLC_TTS_RESOURCE_ID, connect_id);
    esp_websocket_client_config_t cfg = {
        .uri = CONFIG_SMART_POT_TTS_ENDPOINT,
        .headers = headers,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .network_timeout_ms = TTS_CONNECT_TIMEOUT_MS,
        .reconnect_timeout_ms = 2000,
        .disable_auto_reconnect = true,
        .buffer_size = 4096,
        .task_stack = 5120,
    };
    rt->client = esp_websocket_client_init(&cfg);
    if (rt->client == NULL) return false;
    esp_websocket_register_events(rt->client, WEBSOCKET_EVENT_ANY, websocket_event_handler, rt);
    if (esp_websocket_client_start(rt->client) != ESP_OK) {
        close_connection(rt);
        return false;
    }
    bits = xEventGroupWaitBits(rt->events, TTS_EVENT_WS_CONNECTED | TTS_EVENT_FAILED,
                               pdFALSE, pdFALSE, pdMS_TO_TICKS(TTS_CONNECT_TIMEOUT_MS));
    if ((bits & TTS_EVENT_WS_CONNECTED) == 0 ||
        !send_event(rt, APP_VOLC_EVENT_START_CONNECTION, NULL, "{}")) {
        close_connection(rt);
        return false;
    }
    bits = xEventGroupWaitBits(rt->events, TTS_EVENT_CONNECTION_STARTED | TTS_EVENT_FAILED,
                               pdFALSE, pdFALSE, pdMS_TO_TICKS(TTS_CONNECT_TIMEOUT_MS));
    if ((bits & TTS_EVENT_CONNECTION_STARTED) == 0) {
        close_connection(rt);
        return false;
    }
    return true;
}

static void playback_task(void *arg)
{
    tts_runtime_t *rt = (tts_runtime_t *)arg;
    while (!rt->session_done && !rt->overflow && rt->pcm_len < TTS_PREBUFFER_BYTES) {
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    if (rt->overflow || (rt->session_done && !rt->session_ok)) goto done;
    if (!app_voice_pause_microphone(10000)) {
        snprintf(rt->error, sizeof(rt->error), "microphone pause timeout");
        goto done;
    }
    if (rt->speaker == NULL) rt->speaker = bsp_audio_codec_speaker_init();
    if (rt->speaker == NULL) goto resume;
    esp_codec_dev_sample_info_t fs = {
        .sample_rate = CONFIG_SMART_POT_VOLC_TTS_SAMPLE_RATE,
        .channel = 1,
        .bits_per_sample = 16,
    };
    if (esp_codec_dev_open(rt->speaker, &fs) != ESP_CODEC_DEV_OK) goto resume;
    esp_codec_dev_set_out_vol(rt->speaker, rt->volume);
    rt->playback_started = true;
    app_ui_set_voice_status("TTS: speaking");
    ESP_LOGI(TAG, "Volc TTS playback starting buffered=%u", (unsigned int)rt->pcm_len);
    size_t offset = 0;
    while (!rt->overflow) {
        size_t available = rt->pcm_len;
        if (offset < available) {
            size_t chunk = available - offset;
            if (chunk > TTS_PLAY_CHUNK) chunk = TTS_PLAY_CHUNK;
            chunk &= ~((size_t)1);
            if (chunk > 0) {
                if (esp_codec_dev_write(rt->speaker, rt->pcm + offset, chunk) != ESP_CODEC_DEV_OK) break;
                offset += chunk;
                continue;
            }
        }
        if (rt->session_done) {
            rt->playback_ok = rt->session_ok && offset == (rt->pcm_len & ~((size_t)1));
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(5));
    }
    ESP_LOGI(TAG, "Volc TTS playback complete bytes=%u ok=%d",
             (unsigned int)offset, rt->playback_ok);
    esp_codec_dev_close(rt->speaker);
resume:
    if (!app_voice_resume_microphone(2500)) {
        ESP_LOGW(TAG, "Timed out resuming microphone after Volc TTS");
    }
done:
    rt->playback_task = NULL;
    xTaskNotifyGive(rt->owner);
    vTaskDelete(NULL);
}

static char *build_start_session_json(const tts_msg_t *msg, const char *section_id)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "namespace", "BidirectionalTTS");
    cJSON *user = cJSON_AddObjectToObject(root, "user");
    cJSON_AddStringToObject(user, "uid", "smart-pot-esp32p4");
    cJSON *params = cJSON_AddObjectToObject(root, "req_params");
    cJSON_AddStringToObject(params, "speaker", CONFIG_SMART_POT_VOLC_TTS_SPEAKER);
    cJSON *audio = cJSON_AddObjectToObject(params, "audio_params");
    cJSON_AddStringToObject(audio, "format", "pcm");
    cJSON_AddNumberToObject(audio, "sample_rate", CONFIG_SMART_POT_VOLC_TTS_SAMPLE_RATE);
    cJSON_AddNumberToObject(audio, "speech_rate", msg->speech_rate);
    cJSON_AddNumberToObject(audio, "loudness_rate", 0);
    cJSON *post = cJSON_AddObjectToObject(params, "post_process");
    cJSON_AddNumberToObject(post, "pitch", msg->pitch);
    cJSON_AddStringToObject(params, "section_id", section_id);
    if (msg->style[0] != '\0') {
        cJSON *contexts = cJSON_AddArrayToObject(params, "context_texts");
        cJSON_AddItemToArray(contexts, cJSON_CreateString(msg->style));
    }
    char *json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return json;
}

static bool start_session(tts_runtime_t *rt, const tts_msg_t *msg)
{
    if (!ensure_connection(rt)) return false;
    if (!allocate_pcm_buffer(rt)) return false;
    xEventGroupClearBits(rt->events, TTS_EVENT_SESSION_STARTED |
                                    TTS_EVENT_SESSION_FINISHED | TTS_EVENT_FAILED);
    make_uuid(rt->session_id);
    make_uuid(rt->section_id);
    rt->pcm_len = 0;
    rt->session_done = false;
    rt->session_ok = false;
    rt->overflow = false;
    rt->playback_ok = false;
    rt->playback_started = false;
    rt->tasks_sent = 0;
    rt->tasks_finished = 0;
    rt->first_audio_us = 0;
    rt->session_started_us = 0;
    rt->volume = msg->volume;
    rt->error[0] = '\0';
    char *json = build_start_session_json(msg, rt->section_id);
    bool sent = json != NULL && send_event(rt, APP_VOLC_EVENT_START_SESSION, rt->session_id, json);
    free(json);
    if (!sent) return false;
    EventBits_t bits = xEventGroupWaitBits(rt->events, TTS_EVENT_SESSION_STARTED | TTS_EVENT_FAILED,
                                           pdFALSE, pdFALSE,
                                           pdMS_TO_TICKS(TTS_CONNECT_TIMEOUT_MS));
    if ((bits & TTS_EVENT_SESSION_STARTED) == 0) return false;
    rt->owner = xTaskGetCurrentTaskHandle();
    if (xTaskCreate(playback_task, "smart_pot_tts_play", 4096, rt, 5, &rt->playback_task) != pdPASS) {
        rt->playback_task = NULL;
        return false;
    }
    return true;
}

static bool send_text(tts_runtime_t *rt, const char *text)
{
    cJSON *root = cJSON_CreateObject();
    /* The current Seed-TTS 2.0 production endpoint reads TaskRequest text
     * from req_params. A top-level text field is accepted but produces an
     * empty TTSSentenceStart and zero PCM bytes. */
    cJSON *params = cJSON_AddObjectToObject(root, "req_params");
    cJSON_AddStringToObject(params, "text", text);
    char *json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    ESP_LOGI(TAG, "Volc TTS TaskRequest text_bytes=%u text=%.160s",
             (unsigned int)strlen(text), text);
    bool ok = json != NULL && send_event(rt, APP_VOLC_EVENT_TASK_REQUEST, rt->session_id, json);
    if (ok) rt->tasks_sent++;
    free(json);
    return ok;
}

static bool finish_session(tts_runtime_t *rt)
{
    /* FinishSession is the end-of-input marker. The server emits
     * TTSSentenceEnd only after receiving it, while audio is streamed back in
     * parallel. Waiting for TTSSentenceEnd here creates a 30-second deadlock. */
    if (!send_event(rt, APP_VOLC_EVENT_FINISH_SESSION, rt->session_id, "{}")) {
        rt->session_done = true;
        return false;
    }
    EventBits_t bits = xEventGroupWaitBits(rt->events, TTS_EVENT_SESSION_FINISHED | TTS_EVENT_FAILED,
                                           pdFALSE, pdFALSE,
                                           pdMS_TO_TICKS(TTS_SESSION_TIMEOUT_MS));
    if ((bits & TTS_EVENT_SESSION_FINISHED) == 0) {
        rt->session_done = true;
        rt->session_ok = false;
    }
    if (rt->playback_task != NULL) {
        (void)ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(TTS_SESSION_TIMEOUT_MS));
    }
    return rt->session_ok && rt->playback_ok;
}

static void cancel_session(tts_runtime_t *rt)
{
    if (rt->session_id[0] != '\0' && rt->client != NULL) {
        (void)send_event(rt, APP_VOLC_EVENT_CANCEL_SESSION, rt->session_id, "{}");
    }
    rt->session_done = true;
    rt->session_ok = false;
    if (rt->playback_task != NULL) {
        (void)ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(3000));
    }
}

static bool play_one_shot(tts_runtime_t *rt, const tts_msg_t *msg)
{
    if (!start_session(rt, msg)) return false;
    if (!send_text(rt, msg->text)) {
        cancel_session(rt);
        return false;
    }
    return finish_session(rt);
}

static void tts_task(void *arg)
{
    (void)arg;
    tts_runtime_t rt = { 0 };
    rt.events = xEventGroupCreate();
    if (rt.events == NULL) {
        ESP_LOGE(TAG, "Failed to allocate Volc TTS runtime");
        vTaskDelete(NULL);
        return;
    }

    bool streaming = false;
    tts_msg_t msg;
    while (xQueueReceive(s_tts_queue, &msg, portMAX_DELAY) == pdTRUE) {
        if (msg.command == TTS_CMD_PRECONNECT) {
            bool ok = ensure_connection(&rt);
            s_preconnect_requested = false;
            ESP_LOGI(TAG, "Volc TTS connection prewarm %s", ok ? "ready" : "failed");
        } else if (msg.command == TTS_CMD_ONE_SHOT) {
            s_tts_busy = true;
            bool ok = play_one_shot(&rt, &msg);
            if (!ok) {
                ESP_LOGW(TAG, "Volc TTS one-shot failed: %s", rt.error);
                close_connection(&rt);
                app_ui_set_voice_status("TTS: failed");
            }
            if (msg.complete_conversation) {
                app_voice_conversation_complete();
            }
            release_pcm_buffer(&rt);
            s_tts_busy = false;
        } else if (msg.command == TTS_CMD_STREAM_BEGIN) {
            if (streaming) {
                cancel_session(&rt);
                release_pcm_buffer(&rt);
            }
            s_tts_busy = true;
            streaming = start_session(&rt, &msg);
            if (!streaming) {
                ESP_LOGW(TAG, "Volc TTS stream start failed: %s", rt.error);
                close_connection(&rt);
                release_pcm_buffer(&rt);
                s_tts_busy = false;
                s_stream_requested = false;
            }
        } else if (msg.command == TTS_CMD_STREAM_TEXT) {
            if (streaming && !send_text(&rt, msg.text)) {
                ESP_LOGW(TAG, "Volc TTS stream text send failed");
                cancel_session(&rt);
                streaming = false;
                close_connection(&rt);
                release_pcm_buffer(&rt);
            }
        } else if (msg.command == TTS_CMD_STREAM_FINISH) {
            bool ok = streaming && finish_session(&rt);
            streaming = false;
            s_stream_requested = false;
            s_tts_busy = false;
            if (!ok) {
                ESP_LOGW(TAG, "Volc TTS stream finish failed: %s", rt.error);
                close_connection(&rt);
            }
            release_pcm_buffer(&rt);
            app_voice_conversation_complete();
        } else if (msg.command == TTS_CMD_STREAM_ABORT) {
            if (streaming) cancel_session(&rt);
            release_pcm_buffer(&rt);
            streaming = false;
            s_stream_requested = false;
            s_tts_busy = false;
        }
    }
}

static void write_chime_note(esp_codec_dev_handle_t spk, uint32_t frequency,
                             uint32_t duration_ms, uint16_t volume)
{
    int16_t chunk[CHIME_CHUNK_SAMPLES];
    uint32_t phase = 0;
    uint32_t step = (frequency * 32U * 65536U) / CHIME_SAMPLE_RATE;
    uint32_t total = CHIME_SAMPLE_RATE * duration_ms / 1000;
    for (uint32_t written = 0; written < total;) {
        uint32_t count = total - written;
        if (count > CHIME_CHUNK_SAMPLES) count = CHIME_CHUNK_SAMPLES;
        for (uint32_t i = 0; i < count; i++) {
            uint32_t fade = written + i;
            uint32_t envelope = volume;
            if (fade < 180) envelope = envelope * fade / 180;
            uint32_t tail = total - fade;
            if (tail < 240) envelope = envelope * tail / 240;
            chunk[i] = (int16_t)((s_sine_lut[(phase >> 16) & 31] * (int32_t)envelope) / 32768);
            phase += step;
        }
        (void)esp_codec_dev_write(spk, chunk, count * sizeof(chunk[0]));
        written += count;
    }
}

static void chime_task(void *arg)
{
    (void)arg;
    if (s_tts_busy) goto done;
    s_chime_busy = true;
    if (!app_voice_pause_microphone(500)) goto done;
    if (s_chime_spk == NULL) s_chime_spk = bsp_audio_codec_speaker_init();
    if (s_chime_spk != NULL) {
        esp_codec_dev_sample_info_t fs = {
            .sample_rate = CHIME_SAMPLE_RATE, .channel = 1, .bits_per_sample = 16,
        };
        if (esp_codec_dev_open(s_chime_spk, &fs) == ESP_CODEC_DEV_OK) {
            esp_codec_dev_set_out_vol(s_chime_spk, 58);
            write_chime_note(s_chime_spk, 880, 95, 13500);
            write_chime_note(s_chime_spk, 1320, 135, 14500);
            esp_codec_dev_close(s_chime_spk);
        }
    }
    (void)app_voice_resume_microphone(500);
done:
    s_chime_busy = false;
    vTaskDelete(NULL);
}

static bool queue_message(const tts_msg_t *msg, TickType_t wait)
{
    if (!CONFIG_SMART_POT_TTS_ENABLE || s_tts_queue == NULL) return false;
    bool ok = xQueueSend(s_tts_queue, msg, wait) == pdTRUE;
    if (!ok) ESP_LOGW(TAG, "Volc TTS command queue full type=%d", msg->command);
    return ok;
}

static bool queue_one_shot(const char *text, bool complete,
                           uint8_t volume, int16_t rate, int8_t pitch,
                           const char *style, bool low_priority)
{
    if (s_tts_queue == NULL || text == NULL || text[0] == '\0') return false;
    if (low_priority && (s_tts_busy || uxQueueMessagesWaiting(s_tts_queue) > 0)) {
        ESP_LOGI(TAG, "Dropping low-priority TTS while busy");
        return false;
    }
    tts_msg_t msg = {
        .command = TTS_CMD_ONE_SHOT,
        .complete_conversation = complete,
        .volume = apply_user_volume(volume),
        .speech_rate = rate,
        .pitch = pitch,
    };
    utf8_strlcpy(msg.text, text, sizeof(msg.text));
    utf8_strlcpy(msg.style, style, sizeof(msg.style));
    TickType_t wait = low_priority ? 0 : pdMS_TO_TICKS(500);
    return queue_message(&msg, wait);
}

void app_tts_start(void)
{
    if (!CONFIG_SMART_POT_TTS_ENABLE || CONFIG_SMART_POT_VOLC_API_KEY[0] == '\0') {
        ESP_LOGI(TAG, "Volc TTS disabled or API key missing");
        return;
    }
    if (s_tts_queue != NULL) return;
    s_tts_queue = xQueueCreate(TTS_QUEUE_DEPTH, sizeof(tts_msg_t));
    if (s_tts_queue == NULL ||
        xTaskCreate(tts_task, "smart_pot_volc_tts", 9216, NULL, 4, NULL) != pdPASS) {
        app_ui_set_voice_status("TTS: task failed");
        return;
    }
    if (CONFIG_SMART_POT_TTS_STARTUP_TEST) {
        (void)queue_one_shot("你好，我是小麦。", false,
                             TTS_VOLUME_COMMAND, 5, 1,
                             "请用自然、亲切、活泼的语气说话。", false);
    }
}

bool app_tts_prepare_connection(void)
{
    if (!CONFIG_SMART_POT_TTS_ENABLE || s_tts_queue == NULL) return false;
    if (s_preconnect_requested || s_tts_busy || s_stream_requested) return true;
    tts_msg_t msg = { .command = TTS_CMD_PRECONNECT };
    s_preconnect_requested = queue_message(&msg, 0);
    return s_preconnect_requested;
}

bool app_tts_stream_begin(const char *voice_instruction)
{
    if (s_stream_requested || s_tts_busy) return false;
    tts_msg_t msg = {
        .command = TTS_CMD_STREAM_BEGIN,
        .volume = apply_user_volume(TTS_VOLUME_CONVERSATION),
        .speech_rate = 5,
        .pitch = 1,
    };
    utf8_strlcpy(msg.style,
                 voice_instruction != NULL && voice_instruction[0] != '\0' ?
                 voice_instruction : "请用自然、亲切、像朋友一样的语气说话。",
                 sizeof(msg.style));
    s_stream_requested = queue_message(&msg, pdMS_TO_TICKS(5000));
    return s_stream_requested;
}

bool app_tts_stream_push(const char *text)
{
    if (!s_stream_requested || text == NULL || text[0] == '\0') return false;
    tts_msg_t msg = { .command = TTS_CMD_STREAM_TEXT };
    utf8_strlcpy(msg.text, text, sizeof(msg.text));
    return queue_message(&msg, pdMS_TO_TICKS(5000));
}

bool app_tts_stream_finish(void)
{
    if (!s_stream_requested) return false;
    tts_msg_t msg = { .command = TTS_CMD_STREAM_FINISH };
    return queue_message(&msg, pdMS_TO_TICKS(5000));
}

void app_tts_stream_abort(void)
{
    if (!s_stream_requested) return;
    tts_msg_t msg = { .command = TTS_CMD_STREAM_ABORT };
    (void)queue_message(&msg, pdMS_TO_TICKS(1000));
}

bool app_tts_speak_text(const char *text)
{
    return queue_one_shot(text, true, TTS_VOLUME_CONVERSATION, 5, 1,
                          "请用自然、亲切、像朋友一样的语气说话。", false);
}

bool app_tts_speak_text_with_instruction(const char *text, const char *voice_instruction)
{
    const char *style = voice_instruction != NULL && voice_instruction[0] != '\0' ?
                        voice_instruction : "请用自然、亲切、像朋友一样的语气说话。";
    return queue_one_shot(text, true, TTS_VOLUME_CONVERSATION, 5, 1, style, false);
}

bool app_tts_speak_once(const char *text)
{
    return queue_one_shot(text, true, TTS_VOLUME_COMMAND, 8, 2,
                          "请用清楚、轻快的语气说话。", false);
}

bool app_tts_speak_text_quietly(const char *text)
{
    return queue_one_shot(text, true, TTS_VOLUME_AUTOMATION, 5, 1,
                          "请用温柔、俏皮的植物伙伴语气说话。", true);
}

bool app_tts_speak_text_with_tone(const char *text, app_tts_tone_t tone)
{
    const char *style = "请用温柔、俏皮的植物伙伴语气说话。";
    int16_t rate = 5;
    int8_t pitch = 1;
    switch (tone) {
    case APP_TTS_TONE_CHEERFUL:
        style = "请用开心、活泼、亲切、略带雀跃的语气说话。";
        rate = 10; pitch = 2;
        break;
    case APP_TTS_TONE_WORRIED:
        style = "请用有点委屈、担心、像小植物撒娇的语气说话。";
        rate = -5; pitch = 1;
        break;
    case APP_TTS_TONE_SLEEPY:
        style = "请用困倦、低落、稍微慢一点的语气说话。";
        rate = -15; pitch = -2;
        break;
    case APP_TTS_TONE_FLUSTERED:
        style = "请用着急、慌张但仍然可爱的语气说话。";
        rate = 15; pitch = 2;
        break;
    default:
        break;
    }
    return queue_one_shot(text, true, TTS_VOLUME_AUTOMATION,
                          rate, pitch, style, true);
}

bool app_tts_speak_stream_segment(const char *text)
{
    return app_tts_stream_push(text);
}

bool app_tts_finish_stream(void)
{
    return app_tts_stream_finish();
}

bool app_tts_play_success_chime(void)
{
    if (s_tts_busy || s_chime_busy) return false;
    return xTaskCreate(chime_task, "smart_pot_chime", 3072, NULL, 5, NULL) == pdPASS;
}
