#include "app_asr.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "cJSON.h"
#include "esp_crt_bundle.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_websocket_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"

#include "app_ui.h"
#include "app_voice.h"
#include "app_volc_protocol.h"

#ifndef CONFIG_SMART_POT_ASR_ENABLE
#define CONFIG_SMART_POT_ASR_ENABLE 0
#endif
#ifndef CONFIG_SMART_POT_VOLC_API_KEY
#define CONFIG_SMART_POT_VOLC_API_KEY ""
#endif
#ifndef CONFIG_SMART_POT_ASR_ENDPOINT
#define CONFIG_SMART_POT_ASR_ENDPOINT "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
#endif
#ifndef CONFIG_SMART_POT_VOLC_ASR_RESOURCE_ID
#define CONFIG_SMART_POT_VOLC_ASR_RESOURCE_ID "volc.seedasr.sauc.duration"
#endif
#ifndef CONFIG_SMART_POT_ASR_MAX_RECORD_MS
#define CONFIG_SMART_POT_ASR_MAX_RECORD_MS 8000
#endif
#ifndef CONFIG_SMART_POT_ASR_END_WINDOW_MS
#define CONFIG_SMART_POT_ASR_END_WINDOW_MS 700
#endif

#define ASR_SAMPLE_RATE 16000
#define ASR_PACKET_MS 100
#define ASR_PACKET_BYTES (ASR_SAMPLE_RATE * sizeof(int16_t) * ASR_PACKET_MS / 1000)
#define ASR_RESULT_CAP 768
#define ASR_ERROR_CAP 384
#define ASR_CONNECT_TIMEOUT_MS 8000
#define ASR_FINAL_TIMEOUT_MS 3500
#define ASR_SPEECH_START_LEVEL 30
#define ASR_SPEECH_CONTINUE_LEVEL 15
#define ASR_SPEECH_START_PACKETS 2
#define ASR_SPEECH_CONTINUE_PACKETS 2
#define ASR_LOCAL_SHORT_END_SILENCE_MS 700
#define ASR_LOCAL_LONG_END_SILENCE_MS 900
#define ASR_SHORT_UTTERANCE_MS 1800
#define ASR_NO_SPEECH_TIMEOUT_MS 2800

#define ASR_EVENT_CONNECTED BIT0
#define ASR_EVENT_FINAL BIT1
#define ASR_EVENT_FAILED BIT2

static const char *TAG = "app_asr";

typedef struct {
    EventGroupHandle_t events;
    char partial[ASR_RESULT_CAP];
    char final[ASR_RESULT_CAP];
    char error[ASR_ERROR_CAP];
    int64_t connected_us;
    int64_t first_partial_us;
    uint8_t *fragment;
    size_t fragment_len;
    size_t fragment_cap;
} asr_context_t;

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

static void copy_json_string(char *dst, size_t dst_size, const cJSON *item)
{
    if (dst_size == 0) {
        return;
    }
    dst[0] = '\0';
    if (cJSON_IsString(item) && item->valuestring != NULL) {
        snprintf(dst, dst_size, "%s", item->valuestring);
    }
}

static void parse_asr_result(asr_context_t *ctx, const uint8_t *payload, size_t payload_len)
{
    char *json = malloc(payload_len + 1);
    if (json == NULL) {
        return;
    }
    memcpy(json, payload, payload_len);
    json[payload_len] = '\0';

    cJSON *root = cJSON_Parse(json);
    if (root == NULL) {
        snprintf(ctx->error, sizeof(ctx->error), "invalid ASR JSON: %.240s", json);
        free(json);
        return;
    }

    cJSON *result = cJSON_GetObjectItem(root, "result");
    cJSON *text = cJSON_GetObjectItem(result, "text");
    char latest[ASR_RESULT_CAP] = { 0 };
    copy_json_string(latest, sizeof(latest), text);

    bool definite = false;
    cJSON *utterances = cJSON_GetObjectItem(result, "utterances");
    if (cJSON_IsArray(utterances)) {
        cJSON *utterance = NULL;
        cJSON_ArrayForEach(utterance, utterances) {
            cJSON *utterance_text = cJSON_GetObjectItem(utterance, "text");
            cJSON *utterance_definite = cJSON_GetObjectItem(utterance, "definite");
            if (cJSON_IsString(utterance_text) && utterance_text->valuestring != NULL) {
                copy_json_string(latest, sizeof(latest), utterance_text);
            }
            if (cJSON_IsTrue(utterance_definite)) {
                definite = true;
            }
        }
    }

    if (latest[0] != '\0') {
        snprintf(ctx->partial, sizeof(ctx->partial), "%s", latest);
        app_ui_set_dialog_status(ctx->partial);
        if (ctx->first_partial_us == 0) {
            ctx->first_partial_us = esp_timer_get_time();
            ESP_LOGI(TAG, "Volc ASR first partial after %lld ms",
                     (ctx->first_partial_us - ctx->connected_us) / 1000);
        }
        ESP_LOGI(TAG, "Volc ASR partial definite=%d text=%s", definite, ctx->partial);
    }
    if (definite && latest[0] != '\0') {
        snprintf(ctx->final, sizeof(ctx->final), "%s", latest);
        xEventGroupSetBits(ctx->events, ASR_EVENT_FINAL);
    }

    cJSON_Delete(root);
    free(json);
}

static void process_ws_frame(asr_context_t *ctx, const uint8_t *data, size_t len)
{
    app_volc_message_t message;
    if (!app_volc_parse_message(data, len, &message)) {
        snprintf(ctx->error, sizeof(ctx->error), "malformed Volc ASR frame (%u bytes)",
                 (unsigned int)len);
        ESP_LOGW(TAG, "%s", ctx->error);
        return;
    }
    if (message.type == APP_VOLC_MSG_ERROR) {
        size_t copy = message.payload_len;
        if (copy >= sizeof(ctx->error)) {
            copy = sizeof(ctx->error) - 1;
        }
        memcpy(ctx->error, message.payload, copy);
        ctx->error[copy] = '\0';
        ESP_LOGE(TAG, "Volc ASR protocol error code=%u payload=%s",
                 (unsigned int)message.error_code, ctx->error);
        xEventGroupSetBits(ctx->events, ASR_EVENT_FAILED);
        return;
    }
    if (message.payload_len > 0 && message.serialization == 1) {
        parse_asr_result(ctx, message.payload, message.payload_len);
    }
}

static void websocket_event_handler(void *handler_args, esp_event_base_t base,
                                    int32_t event_id, void *event_data)
{
    (void)base;
    asr_context_t *ctx = (asr_context_t *)handler_args;
    esp_websocket_event_data_t *event = (esp_websocket_event_data_t *)event_data;
    if (ctx == NULL) {
        return;
    }
    switch (event_id) {
    case WEBSOCKET_EVENT_CONNECTED:
        ctx->connected_us = esp_timer_get_time();
        xEventGroupSetBits(ctx->events, ASR_EVENT_CONNECTED);
        ESP_LOGI(TAG, "Volc ASR WebSocket connected");
        break;
    case WEBSOCKET_EVENT_DATA:
        if (event == NULL || event->data_ptr == NULL || event->data_len <= 0 || event->op_code != 0x2) {
            break;
        }
        if (event->payload_offset == 0 && event->data_len == event->payload_len) {
            process_ws_frame(ctx, (const uint8_t *)event->data_ptr, event->data_len);
            break;
        }
        if (event->payload_offset == 0) {
            free(ctx->fragment);
            ctx->fragment = malloc(event->payload_len);
            ctx->fragment_cap = ctx->fragment != NULL ? event->payload_len : 0;
            ctx->fragment_len = 0;
        }
        if (ctx->fragment != NULL &&
            (size_t)event->payload_offset + event->data_len <= ctx->fragment_cap) {
            memcpy(ctx->fragment + event->payload_offset, event->data_ptr, event->data_len);
            ctx->fragment_len += event->data_len;
            if (ctx->fragment_len >= ctx->fragment_cap) {
                process_ws_frame(ctx, ctx->fragment, ctx->fragment_cap);
                free(ctx->fragment);
                ctx->fragment = NULL;
                ctx->fragment_len = 0;
                ctx->fragment_cap = 0;
            }
        }
        break;
    case WEBSOCKET_EVENT_ERROR:
        if (event != NULL) {
            snprintf(ctx->error, sizeof(ctx->error),
                     "WebSocket error type=%d http=%d tls=%s stack=-0x%x cert=0x%x errno=%d",
                     (int)event->error_handle.error_type,
                     event->error_handle.esp_ws_handshake_status_code,
                     esp_err_to_name(event->error_handle.esp_tls_last_esp_err),
                     (unsigned int)(-event->error_handle.esp_tls_stack_err),
                     (unsigned int)event->error_handle.esp_tls_cert_verify_flags,
                     event->error_handle.esp_transport_sock_errno);
        } else {
            snprintf(ctx->error, sizeof(ctx->error), "WebSocket transport error without details");
        }
        ESP_LOGE(TAG, "%s", ctx->error);
        xEventGroupSetBits(ctx->events, ASR_EVENT_FAILED);
        break;
    case WEBSOCKET_EVENT_DISCONNECTED:
        if ((xEventGroupGetBits(ctx->events) & ASR_EVENT_FINAL) == 0) {
            xEventGroupSetBits(ctx->events, ASR_EVENT_FAILED);
        }
        ESP_LOGI(TAG, "Volc ASR WebSocket disconnected");
        break;
    default:
        break;
    }
}

static char *make_full_request_json(void)
{
    cJSON *root = cJSON_CreateObject();
    cJSON *user = cJSON_AddObjectToObject(root, "user");
    cJSON *audio = cJSON_AddObjectToObject(root, "audio");
    cJSON *request = cJSON_AddObjectToObject(root, "request");
    if (root == NULL || user == NULL || audio == NULL || request == NULL) {
        cJSON_Delete(root);
        return NULL;
    }
    cJSON_AddStringToObject(user, "uid", "smart-pot-esp32p4");
    cJSON_AddStringToObject(user, "platform", "ESP-IDF");
    cJSON_AddStringToObject(audio, "format", "pcm");
    cJSON_AddStringToObject(audio, "codec", "raw");
    cJSON_AddNumberToObject(audio, "rate", ASR_SAMPLE_RATE);
    cJSON_AddNumberToObject(audio, "bits", 16);
    cJSON_AddNumberToObject(audio, "channel", 1);
    cJSON_AddStringToObject(audio, "language", "zh-CN");
    cJSON_AddStringToObject(request, "model_name", "bigmodel");
    cJSON_AddBoolToObject(request, "enable_nonstream", true);
    cJSON_AddBoolToObject(request, "enable_itn", true);
    cJSON_AddBoolToObject(request, "enable_punc", true);
    cJSON_AddBoolToObject(request, "enable_ddc", true);
    cJSON_AddBoolToObject(request, "show_utterances", true);
    cJSON_AddStringToObject(request, "result_type", "full");
    cJSON_AddBoolToObject(request, "enable_accelerate_text", true);
    cJSON_AddNumberToObject(request, "accelerate_score", 5);
    cJSON_AddNumberToObject(request, "end_window_size", CONFIG_SMART_POT_ASR_END_WINDOW_MS);
    cJSON_AddNumberToObject(request, "force_to_speech_time", 1000);
    cJSON_AddBoolToObject(request, "enable_emotion_detection", true);
    cJSON *context = cJSON_AddObjectToObject(request, "context");
    cJSON_AddStringToObject(context, "context_type", "dialog_ctx");
    cJSON *data = cJSON_AddArrayToObject(context, "context_data");
    cJSON *words = cJSON_CreateObject();
    cJSON_AddStringToObject(words, "text",
                            "智能盆栽小麦，浇花，浇水，土壤湿度，光照，提醒我，添加日程，番茄钟，口头报告");
    cJSON_AddItemToArray(data, words);
    char *json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return json;
}

static int average_level(const uint8_t *pcm, size_t bytes)
{
    const int16_t *samples = (const int16_t *)pcm;
    int64_t sum = 0;
    size_t count = bytes / sizeof(*samples);
    for (size_t i = 0; i < count; i++) {
        int32_t sample = samples[i];
        sum += sample < 0 ? -sample : sample;
    }
    return count > 0 ? (int)(sum / count) : 0;
}

static void update_speech_state(const uint8_t *pcm, size_t bytes,
                                int *speech_start_packets, bool *speech_detected,
                                int *silence_ms, int *speech_ms,
                                int *speech_continue_packets)
{
    int level = average_level(pcm, bytes);
    if (!*speech_detected) {
        *speech_start_packets = level >= ASR_SPEECH_START_LEVEL ?
                                *speech_start_packets + 1 : 0;
        if (*speech_start_packets >= ASR_SPEECH_START_PACKETS) {
            *speech_detected = true;
            *silence_ms = 0;
            *speech_ms = ASR_SPEECH_START_PACKETS * ASR_PACKET_MS;
            *speech_continue_packets = 0;
        }
    } else if (level >= ASR_SPEECH_CONTINUE_LEVEL) {
        *speech_ms += ASR_PACKET_MS;
        *speech_continue_packets += 1;
        /* A single noisy frame must not keep the recorder alive forever. */
        if (*speech_continue_packets >= ASR_SPEECH_CONTINUE_PACKETS) {
            *silence_ms = 0;
        }
    } else {
        *speech_continue_packets = 0;
        *silence_ms += ASR_PACKET_MS;
    }
}

static int endpoint_silence_ms(int speech_ms)
{
    return speech_ms <= ASR_SHORT_UTTERANCE_MS ?
           ASR_LOCAL_SHORT_END_SILENCE_MS : ASR_LOCAL_LONG_END_SILENCE_MS;
}

static bool capture_should_stop(bool speech_detected, int speech_ms,
                                int silence_ms, int packets_captured)
{
    if (speech_detected) {
        return silence_ms >= endpoint_silence_ms(speech_ms);
    }
    return packets_captured * ASR_PACKET_MS >= ASR_NO_SPEECH_TIMEOUT_MS;
}

static bool send_frame(esp_websocket_client_handle_t client, uint8_t *frame, size_t frame_len)
{
    if (frame == NULL) {
        return false;
    }
    int sent = esp_websocket_client_send_bin(client, (const char *)frame, frame_len,
                                              pdMS_TO_TICKS(2500));
    free(frame);
    return sent == (int)frame_len;
}

char *app_asr_transcribe_from_mic(esp_codec_dev_handle_t mic)
{
    if (!CONFIG_SMART_POT_ASR_ENABLE || mic == NULL ||
        CONFIG_SMART_POT_VOLC_API_KEY[0] == '\0') {
        ESP_LOGW(TAG, "Volc ASR disabled or API key missing");
        return NULL;
    }

    asr_context_t ctx = { 0 };
    ctx.events = xEventGroupCreate();
    if (ctx.events == NULL) {
        return NULL;
    }
    char connect_id[37];
    char request_id[37];
    make_uuid(connect_id);
    make_uuid(request_id);
    char headers[512];
    snprintf(headers, sizeof(headers),
             "X-Api-Key: %s\r\n"
             "X-Api-Resource-Id: %s\r\n"
             "X-Api-Request-Id: %s\r\n"
             "X-Api-Sequence: -1\r\n"
             "X-Api-Connect-Id: %s\r\n",
             CONFIG_SMART_POT_VOLC_API_KEY, CONFIG_SMART_POT_VOLC_ASR_RESOURCE_ID,
             request_id, connect_id);
    esp_websocket_client_config_t config = {
        .uri = CONFIG_SMART_POT_ASR_ENDPOINT,
        .headers = headers,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .network_timeout_ms = ASR_CONNECT_TIMEOUT_MS,
        .reconnect_timeout_ms = 2000,
        .disable_auto_reconnect = true,
        .buffer_size = 4096,
        .task_stack = 5120,
    };
    esp_websocket_client_handle_t client = esp_websocket_client_init(&config);
    if (client == NULL) {
        vEventGroupDelete(ctx.events);
        return NULL;
    }
    esp_websocket_register_events(client, WEBSOCKET_EVENT_ANY, websocket_event_handler, &ctx);
    int max_packets = CONFIG_SMART_POT_ASR_MAX_RECORD_MS / ASR_PACKET_MS;
    uint8_t *pcm = malloc(ASR_PACKET_BYTES);
    uint8_t *prefetch = heap_caps_malloc((size_t)max_packets * ASR_PACKET_BYTES,
                                         MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (pcm == NULL || prefetch == NULL) {
        ESP_LOGE(TAG, "Failed to allocate ASR capture/prefetch buffers");
        free(pcm);
        free(prefetch);
        esp_websocket_client_destroy(client);
        vEventGroupDelete(ctx.events);
        return NULL;
    }

    /* Start capturing immediately. TLS/DNS/WebSocket setup now happens in the
     * background while the user speaks, instead of presenting several seconds
     * of dead time before recording begins. */
    app_ui_set_voice_status("ASR: listening");
    esp_err_t start_err = esp_websocket_client_start(client);
    if (start_err != ESP_OK) {
        ESP_LOGE(TAG, "Volc ASR WebSocket start failed: %s", esp_err_to_name(start_err));
        free(pcm);
        free(prefetch);
        esp_websocket_client_destroy(client);
        vEventGroupDelete(ctx.events);
        return NULL;
    }

    EventBits_t bits = 0;
    int speech_start_packets = 0;
    int speech_continue_packets = 0;
    int speech_ms = 0;
    int silence_ms = 0;
    bool speech_detected = false;
    bool stream_failed = false;
    int prefetched_packets = 0;
    int64_t connect_deadline = esp_timer_get_time() +
                               (int64_t)ASR_CONNECT_TIMEOUT_MS * 1000;
    while (prefetched_packets < max_packets && esp_timer_get_time() < connect_deadline) {
        bits = xEventGroupGetBits(ctx.events);
        if ((bits & (ASR_EVENT_CONNECTED | ASR_EVENT_FAILED)) != 0) break;
        if (esp_codec_dev_read(mic, pcm, ASR_PACKET_BYTES) != ESP_CODEC_DEV_OK) {
            ESP_LOGE(TAG, "Microphone read failed while ASR was connecting");
            stream_failed = true;
            break;
        }
        memcpy(prefetch + (size_t)prefetched_packets * ASR_PACKET_BYTES,
               pcm, ASR_PACKET_BYTES);
        update_speech_state(pcm, ASR_PACKET_BYTES, &speech_start_packets,
                            &speech_detected, &silence_ms, &speech_ms,
                            &speech_continue_packets);
        prefetched_packets++;
    }
    bits = xEventGroupGetBits(ctx.events);
    if ((bits & ASR_EVENT_CONNECTED) == 0) {
        ESP_LOGE(TAG, "Volc ASR connection failed bits=0x%lx connected=%d detail=%s",
                 (unsigned long)bits, esp_websocket_client_is_connected(client), ctx.error);
        goto cleanup;
    }
    ESP_LOGI(TAG, "Volc ASR connected with %d buffered packets (%d ms)",
             prefetched_packets, prefetched_packets * ASR_PACKET_MS);

    char *json = make_full_request_json();
    size_t frame_len = 0;
    uint8_t *frame = json != NULL ?
                     app_volc_build_full_request(json, strlen(json), &frame_len) : NULL;
    free(json);
    if (!send_frame(client, frame, frame_len)) {
        ESP_LOGE(TAG, "Failed to send Volc ASR full request");
        goto cleanup;
    }

    int packets_sent = 0;
    for (int i = 0; i < prefetched_packets; i++) {
        frame = app_volc_build_audio_request(
            prefetch + (size_t)i * ASR_PACKET_BYTES, ASR_PACKET_BYTES, false, &frame_len);
        if (!send_frame(client, frame, frame_len)) {
            ESP_LOGE(TAG, "Volc ASR buffered PCM send failed at packet %d", i);
            stream_failed = true;
            break;
        }
        packets_sent++;
    }
    while (!stream_failed && packets_sent < max_packets &&
           !capture_should_stop(speech_detected, speech_ms, silence_ms, packets_sent)) {
        if (esp_codec_dev_read(mic, pcm, ASR_PACKET_BYTES) != ESP_CODEC_DEV_OK) {
            ESP_LOGE(TAG, "Microphone read failed during streaming ASR");
            stream_failed = true;
            break;
        }
        update_speech_state(pcm, ASR_PACKET_BYTES, &speech_start_packets,
                            &speech_detected, &silence_ms, &speech_ms,
                            &speech_continue_packets);

        frame = app_volc_build_audio_request(pcm, ASR_PACKET_BYTES, false, &frame_len);
        if (!send_frame(client, frame, frame_len)) {
            ESP_LOGE(TAG, "Volc ASR PCM send failed at packet %d", packets_sent);
            stream_failed = true;
            break;
        }
        packets_sent++;
        bits = xEventGroupGetBits(ctx.events);
        if ((bits & ASR_EVENT_FAILED) != 0) {
            stream_failed = true;
            break;
        }
        if ((bits & ASR_EVENT_FINAL) != 0 ||
            capture_should_stop(speech_detected, speech_ms, silence_ms, packets_sent)) {
            break;
        }
    }
    frame = app_volc_build_audio_request(NULL, 0, true, &frame_len);
    (void)send_frame(client, frame, frame_len);

    if (!stream_failed && speech_detected &&
        (xEventGroupGetBits(ctx.events) & ASR_EVENT_FINAL) == 0) {
        app_ui_set_voice_status("ASR: finalizing");
        bits = xEventGroupWaitBits(ctx.events, ASR_EVENT_FINAL | ASR_EVENT_FAILED,
                                   pdFALSE, pdFALSE,
                                   pdMS_TO_TICKS(ASR_FINAL_TIMEOUT_MS));
    }
    ESP_LOGI(TAG, "Volc ASR stream complete packets=%d speech=%d speech_ms=%d silence_ms=%d endpoint_ms=%d final=%d",
             packets_sent, speech_detected, speech_ms, silence_ms,
             endpoint_silence_ms(speech_ms),
             (xEventGroupGetBits(ctx.events) & ASR_EVENT_FINAL) != 0);

cleanup:
    esp_websocket_client_stop(client);
    esp_websocket_client_destroy(client);
    free(pcm);
    free(prefetch);
    free(ctx.fragment);
    char *result = ctx.final[0] != '\0' ? strdup(ctx.final) :
                   (ctx.partial[0] != '\0' ? strdup(ctx.partial) : NULL);
    if (result == NULL && ctx.error[0] != '\0') {
        ESP_LOGW(TAG, "Volc ASR failed: %s", ctx.error);
    }
    vEventGroupDelete(ctx.events);
    return result;
}
