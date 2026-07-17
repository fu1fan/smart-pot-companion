#include "app_llm.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "cJSON.h"
#include "esp_crt_bundle.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "app_tts.h"
#include "app_ui.h"
#include "app_voice.h"
#include "app_memory.h"
#include "app_time.h"

#ifndef CONFIG_SMART_POT_LLM_ENABLE
#define CONFIG_SMART_POT_LLM_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_LLM_ENDPOINT
#define CONFIG_SMART_POT_LLM_ENDPOINT ""
#endif

#ifndef CONFIG_SMART_POT_DEEPSEEK_API_KEY
#define CONFIG_SMART_POT_DEEPSEEK_API_KEY ""
#endif

#ifndef CONFIG_SMART_POT_DEEPSEEK_MODEL
#define CONFIG_SMART_POT_DEEPSEEK_MODEL "deepseek-v4-flash"
#endif

#define LLM_RESPONSE_CAPACITY 4096
#define LLM_SSE_LINE_CAPACITY 1024
#define LLM_VOICE_HEADER_CAPACITY 192
#define LLM_TTS_SEGMENT_CAPACITY 240
#define LLM_TTS_FIRST_SEGMENT_BYTES 36
#define LLM_TTS_NEXT_SEGMENT_BYTES 72
#define SCHEDULE_CONFIDENCE_MIN 0.50
#define SCHEDULE_PENDING_TIMEOUT_US 90000000LL

static const char *TAG = "app_llm";

static void utf8_strlcpy(char *dst, const char *src, size_t dst_size);
static app_plant_state_t s_latest_state = {
    .soil_percent = 50,
    .light_percent = 50,
    .touch_count = 0,
    .touch_active = false,
    .light_lux = 0,
    .soil_frequency_hz = 0,
    .soil_adc_raw = 0,
    .soil_digital_dry = false,
    .mood = APP_MOOD_HAPPY,
};
static SemaphoreHandle_t s_state_lock;
static SemaphoreHandle_t s_schedule_pending_lock;
static bool s_request_running;
static esp_http_client_handle_t s_http_client;

typedef struct {
    bool active;
    char event[96];
    char datetime[64];
    int64_t updated_us;
} pending_schedule_t;

static pending_schedule_t s_pending_schedule;

typedef struct {
    char *buf;
    int len;
    int cap;
    char line[LLM_SSE_LINE_CAPACITY];
    int line_len;
    char raw_error[512];
    int raw_error_len;
    char voice_header[LLM_VOICE_HEADER_CAPACITY];
    int voice_header_len;
    bool voice_header_done;
    bool tts_started;
    bool tts_failed;
    char tts_segment[LLM_TTS_SEGMENT_CAPACITY];
    int tts_segment_len;
    uint32_t tts_segments_sent;
    int64_t request_started_us;
    int64_t first_delta_us;
} http_resp_t;

static bool contains_strong_punctuation(const char *text)
{
    return strstr(text, "。") != NULL || strstr(text, "！") != NULL ||
           strstr(text, "？") != NULL || strchr(text, '!') != NULL ||
           strchr(text, '?') != NULL || strstr(text, "；") != NULL ||
           strchr(text, ';') != NULL;
}

static void stream_flush_tts(http_resp_t *resp, bool force)
{
    if (!resp->tts_started || resp->tts_failed || resp->tts_segment_len <= 0) {
        return;
    }
    int threshold = resp->tts_segments_sent == 0 ?
                    LLM_TTS_FIRST_SEGMENT_BYTES : LLM_TTS_NEXT_SEGMENT_BYTES;
    if (!force && resp->tts_segment_len < threshold &&
        !contains_strong_punctuation(resp->tts_segment)) {
        return;
    }
    resp->tts_segment[resp->tts_segment_len] = '\0';
    if (!app_tts_stream_push(resp->tts_segment)) {
        ESP_LOGW(TAG, "Failed to queue DeepSeek stream segment for TTS");
        resp->tts_failed = true;
        app_tts_stream_abort();
        return;
    }
    ESP_LOGI(TAG, "DeepSeek -> Seed-TTS segment %u: %s",
             (unsigned int)(resp->tts_segments_sent + 1), resp->tts_segment);
    resp->tts_segments_sent++;
    resp->tts_segment_len = 0;
    resp->tts_segment[0] = '\0';
}

static void append_visible_text(http_resp_t *resp, const char *text)
{
    if (resp == NULL || text == NULL || text[0] == '\0') {
        return;
    }

    size_t delta_len = strlen(text);
    int full_copy = (int)delta_len;
    if (resp->len + full_copy >= resp->cap) {
        full_copy = resp->cap - resp->len - 1;
    }
    if (full_copy > 0) {
        memcpy(resp->buf + resp->len, text, full_copy);
        resp->len += full_copy;
        resp->buf[resp->len] = '\0';
        app_ui_set_dialog_status(resp->buf);
    }

    const char *cursor = text;
    while (*cursor != '\0' && !resp->tts_failed) {
        size_t remaining = strlen(cursor);
        size_t space = sizeof(resp->tts_segment) - resp->tts_segment_len - 1;
        size_t copy = remaining < space ? remaining : space;
        if (copy == 0) {
            stream_flush_tts(resp, true);
            if (resp->tts_failed) break;
            continue;
        }
        while (copy > 0 && (((unsigned char)cursor[copy]) & 0xc0) == 0x80) {
            copy--;
        }
        if (copy == 0) break;
        memcpy(resp->tts_segment + resp->tts_segment_len, cursor, copy);
        resp->tts_segment_len += copy;
        resp->tts_segment[resp->tts_segment_len] = '\0';
        cursor += copy;
        stream_flush_tts(resp, false);
    }
}

static void start_voice_stream(http_resp_t *resp, const char *tone)
{
    char instruction[LLM_VOICE_HEADER_CAPACITY];
    const char *safe_tone = tone != NULL && tone[0] != '\0' ? tone : "自然、亲切";
    snprintf(instruction, sizeof(instruction),
             "请使用%s的语气自然表达，保持樱桃小丸子活泼、亲切的角色感。", safe_tone);
    resp->tts_started = app_tts_stream_begin(instruction, true);
    if (!resp->tts_started) {
        ESP_LOGW(TAG, "Seed-TTS stream could not start; will use complete-response fallback");
    } else {
        ESP_LOGI(TAG, "DeepSeek voice instruction: %s", instruction);
    }
}

static void stream_append_delta(http_resp_t *resp, const char *delta)
{
    if (resp == NULL || delta == NULL || delta[0] == '\0') {
        return;
    }
    if (resp->first_delta_us == 0) {
        resp->first_delta_us = esp_timer_get_time();
        ESP_LOGI(TAG, "DeepSeek first SSE delta after %lld ms",
                 (resp->first_delta_us - resp->request_started_us) / 1000);
    }
    if (resp->voice_header_done) {
        append_visible_text(resp, delta);
        return;
    }

    const char *cursor = delta;
    while (*cursor != '\0' && !resp->voice_header_done) {
        if (resp->voice_header_len + 1 >= (int)sizeof(resp->voice_header)) {
            resp->voice_header_done = true;
            start_voice_stream(resp, "自然、亲切");
            append_visible_text(resp, resp->voice_header);
            resp->voice_header_len = 0;
            break;
        }
        char ch = *cursor++;
        resp->voice_header[resp->voice_header_len++] = ch;
        resp->voice_header[resp->voice_header_len] = '\0';
        if (ch == ']') {
            char tone[96] = { 0 };
            const char *prefix = "[语气:";
            size_t prefix_len = strlen(prefix);
            if (strncmp(resp->voice_header, prefix, prefix_len) == 0 &&
                resp->voice_header_len > (int)prefix_len + 1) {
                size_t tone_len = resp->voice_header_len - prefix_len - 1;
                if (tone_len >= sizeof(tone)) tone_len = sizeof(tone) - 1;
                memcpy(tone, resp->voice_header + prefix_len, tone_len);
                tone[tone_len] = '\0';
                start_voice_stream(resp, tone);
            } else {
                start_voice_stream(resp, "自然、亲切");
                append_visible_text(resp, resp->voice_header);
            }
            resp->voice_header_done = true;
            while (*cursor == '\r' || *cursor == '\n' || *cursor == ' ') cursor++;
        }
    }
    if (resp->voice_header_done && *cursor != '\0') {
        append_visible_text(resp, cursor);
    }
}

static void stream_process_sse_line(http_resp_t *resp)
{
    if (resp == NULL || resp->line_len <= 0) {
        return;
    }
    resp->line[resp->line_len] = '\0';
    while (resp->line_len > 0 &&
           (resp->line[resp->line_len - 1] == '\r' || resp->line[resp->line_len - 1] == '\n')) {
        resp->line[--resp->line_len] = '\0';
    }

    if (strncmp(resp->line, "data:", 5) != 0) {
        resp->line_len = 0;
        return;
    }
    const char *payload = resp->line + 5;
    while (*payload == ' ') {
        payload++;
    }
    if (strcmp(payload, "[DONE]") == 0) {
        resp->line_len = 0;
        return;
    }

    cJSON *root = cJSON_Parse(payload);
    if (root != NULL) {
        cJSON *choices = cJSON_GetObjectItem(root, "choices");
        cJSON *choice0 = cJSON_GetArrayItem(choices, 0);
        cJSON *delta_obj = cJSON_GetObjectItem(choice0, "delta");
        cJSON *content = cJSON_GetObjectItem(delta_obj, "content");
        if (cJSON_IsString(content) && content->valuestring != NULL) {
            stream_append_delta(resp, content->valuestring);
        }
        cJSON_Delete(root);
    }
    resp->line_len = 0;
}

static esp_err_t http_event_handler(esp_http_client_event_t *evt)
{
    if (evt->event_id != HTTP_EVENT_ON_DATA || evt->data == NULL || evt->data_len <= 0) {
        return ESP_OK;
    }

    http_resp_t *resp = (http_resp_t *)evt->user_data;
    if (resp == NULL || resp->buf == NULL) {
        return ESP_OK;
    }

    int raw_copy = evt->data_len;
    if (resp->raw_error_len + raw_copy >= (int)sizeof(resp->raw_error)) {
        raw_copy = (int)sizeof(resp->raw_error) - resp->raw_error_len - 1;
    }
    if (raw_copy > 0) {
        memcpy(resp->raw_error + resp->raw_error_len, evt->data, raw_copy);
        resp->raw_error_len += raw_copy;
        resp->raw_error[resp->raw_error_len] = '\0';
    }

    const char *data = (const char *)evt->data;
    for (int i = 0; i < evt->data_len; i++) {
        if (data[i] == '\n') {
            stream_process_sse_line(resp);
            continue;
        }
        if (resp->line_len + 1 < (int)sizeof(resp->line)) {
            resp->line[resp->line_len++] = data[i];
        } else {
            ESP_LOGW(TAG, "DeepSeek SSE line too long; dropping line");
            resp->line_len = 0;
        }
    }
    return ESP_OK;
}

static const char *mood_to_text(app_mood_t mood)
{
    switch (mood) {
    case APP_MOOD_HAPPY:
        return "happy";
    case APP_MOOD_THIRSTY:
        return "thirsty";
    case APP_MOOD_DARK:
        return "needs light";
    case APP_MOOD_WEAK:
        return "thirsty and needs light";
    default:
        return "happy";
    }
}

static char *make_request_body(const app_plant_state_t *state, const char *trigger)
{
    char safe_trigger[256];
    char user_prompt[1024];
    bool long_mode = app_voice_long_conversation_enabled();
    utf8_strlcpy(safe_trigger, trigger, sizeof(safe_trigger));
    int prompt_len = snprintf(user_prompt, sizeof(user_prompt),
             "The user's transcribed speech is: \"%s\". "
             "Answer that speech directly. If it is unclear, ask the user to repeat. "
             "If the user asks multiple questions, answer every question in the same order. "
             "Plant context, use only when the user asks about the plant or care: "
             "soil=%u%%, light=%u%%, mood=%s, light_lux=%u, soil_adc_raw=%u, soil_digital_dry=%s, touch_count=%u, touch_active=%s. "
             "Reply as 小麦 in concise Simplified Chinese. Your name is 小麦, not 小薯. %s",
              safe_trigger, state->soil_percent, state->light_percent, mood_to_text(state->mood),
             (unsigned int)state->light_lux, (unsigned int)state->soil_adc_raw,
             state->soil_digital_dry ? "true" : "false",
             (unsigned int)state->touch_count, state->touch_active ? "true" : "false",
              long_mode ? "Long conversation is enabled: answer naturally within 120 Chinese characters."
                        : "Answer naturally: a single answer may use up to 48 Chinese characters, or multiple answers together up to 72 Chinese characters.");
    if (prompt_len < 0 || prompt_len >= (int)sizeof(user_prompt)) {
        ESP_LOGW(TAG, "DeepSeek user prompt was truncated: required=%d capacity=%u",
                 prompt_len, (unsigned int)sizeof(user_prompt));
    }

    cJSON *root = cJSON_CreateObject();
    if (root == NULL) {
        return NULL;
    }

    cJSON_AddStringToObject(root, "model", CONFIG_SMART_POT_DEEPSEEK_MODEL);
    cJSON_AddNumberToObject(root, "max_tokens", long_mode ? 160 : 112);
    cJSON_AddNumberToObject(root, "temperature", 0.7);
    cJSON_AddBoolToObject(root, "stream", true);
    cJSON *thinking = cJSON_AddObjectToObject(root, "thinking");
    cJSON_AddStringToObject(thinking, "type", "disabled");
    cJSON *messages = cJSON_AddArrayToObject(root, "messages");

    cJSON *system_msg = cJSON_CreateObject();
    cJSON_AddStringToObject(system_msg, "role", "system");
    cJSON_AddStringToObject(system_msg, "content",
                            "You are 小麦, a warm desktop smart-pot companion. Your name is 小麦, not 小薯. Answer the user's speech directly. "
                            "When the user asks multiple questions, answer every question in order. "
                            "Do not volunteer sensor readings or care advice unless asked. "
                            "Use the previous dialogue messages to preserve context and remember user details. "
                            "If the speech is unclear, ask the user to repeat. "
                            "Every answer MUST begin with exactly one control line in this format: [语气:开心、亲切、轻快]. "
                            "Choose two to four concise Chinese tone words that match the content and plant mood. "
                            "After that line, output only the concise Simplified Chinese answer. Do not explain the control line.");
    cJSON_AddItemToArray(messages, system_msg);

    app_memory_append_profile_message(messages);
    app_memory_append_messages(messages);

    cJSON *user_msg = cJSON_CreateObject();
    cJSON_AddStringToObject(user_msg, "role", "user");
    cJSON_AddStringToObject(user_msg, "content", user_prompt);
    cJSON_AddItemToArray(messages, user_msg);

    char *body = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return body;
}

static void finish_voice_with_fallback(const char *ui_status, const char *spoken_text)
{
    app_ui_set_dialog_status(ui_status);
    if (!app_tts_speak_text(spoken_text)) {
        app_voice_conversation_complete();
    }
}

static void finish_local_command_with_voice(const char *spoken_text)
{
    if (!app_tts_speak_text_no_followup(spoken_text)) {
        ESP_LOGW(TAG, "Local command feedback could not enter TTS queue");
        app_voice_conversation_complete();
    }
}

static void llm_request_task(void *arg)
{
    char *trigger = (char *)arg;
    app_plant_state_t state;

    if (s_state_lock != NULL && xSemaphoreTake(s_state_lock, pdMS_TO_TICKS(200)) == pdTRUE) {
        state = s_latest_state;
        xSemaphoreGive(s_state_lock);
    } else {
        state = s_latest_state;
    }

    if (!CONFIG_SMART_POT_LLM_ENABLE || strlen(CONFIG_SMART_POT_DEEPSEEK_API_KEY) == 0) {
        finish_voice_with_fallback("DeepSeek: configure API key", "模型还没配置好。");
        goto done;
    }

    char *body = make_request_body(&state, trigger ? trigger : "voice");
    char *response = calloc(1, LLM_RESPONSE_CAPACITY);
    if (body == NULL || response == NULL) {
        free(body);
        free(response);
        finish_voice_with_fallback("DeepSeek: memory not enough", "我内存有点紧，再试一次。");
        goto done;
    }

    http_resp_t resp = {
        .buf = response,
        .cap = LLM_RESPONSE_CAPACITY,
        .request_started_us = esp_timer_get_time(),
    };

    app_ui_set_dialog_status("DeepSeek: thinking...");
    if (s_http_client == NULL) {
        esp_http_client_config_t cfg = {
            .url = CONFIG_SMART_POT_LLM_ENDPOINT,
            .method = HTTP_METHOD_POST,
            .event_handler = http_event_handler,
            .crt_bundle_attach = esp_crt_bundle_attach,
            .timeout_ms = 30000,
            .keep_alive_enable = true,
            .keep_alive_idle = 15,
            .keep_alive_interval = 5,
            .keep_alive_count = 3,
        };
        s_http_client = esp_http_client_init(&cfg);
    }
    esp_http_client_handle_t client = s_http_client;
    if (client == NULL) {
        free(body);
        free(response);
        finish_voice_with_fallback("DeepSeek: HTTP init failed", "我联网请求没启动成功。");
        goto done;
    }

    esp_http_client_set_user_data(client, &resp);
    char auth_header[192];
    snprintf(auth_header, sizeof(auth_header), "Bearer %s", CONFIG_SMART_POT_DEEPSEEK_API_KEY);
    esp_http_client_set_header(client, "Content-Type", "application/json");
    esp_http_client_set_header(client, "Authorization", auth_header);
    esp_http_client_set_post_field(client, body, strlen(body));

    esp_err_t err = esp_http_client_perform(client);
    int status = esp_http_client_get_status_code(client);
    esp_http_client_set_user_data(client, NULL);
    ESP_LOGI(TAG, "DeepSeek HTTP result err=%s status=%d", esp_err_to_name(err), status);
    if (err == ESP_OK && status >= 200 && status < 300) {
        if (resp.line_len > 0) {
            stream_process_sse_line(&resp);
        }
        if (!resp.voice_header_done && resp.voice_header_len > 0) {
            resp.voice_header_done = true;
            start_voice_stream(&resp, "自然、亲切");
            append_visible_text(&resp, resp.voice_header);
        }
        if (resp.len > 0) {
            app_ui_set_dialog_status(response);
            app_memory_add_exchange(trigger, response);
            bool stream_finish_queued = false;
            if (resp.tts_started && !resp.tts_failed) {
                stream_flush_tts(&resp, true);
                if (!resp.tts_failed) {
                    stream_finish_queued = app_tts_stream_finish();
                }
            }
            if (!stream_finish_queued) {
                if (resp.tts_started) {
                    app_tts_stream_abort();
                }
                if (!app_tts_speak_text(response)) {
                    ESP_LOGW(TAG, "Failed to queue complete DeepSeek response for TTS fallback");
                    app_voice_conversation_complete();
                }
            }
            ESP_LOGI(TAG, "DeepSeek streamed response: %.512s", response);
        } else {
            finish_voice_with_fallback("DeepSeek: empty reply", "我刚才没组织好，再问我一次。");
        }
    } else {
        if (resp.tts_started) {
            app_tts_stream_abort();
        }
        ESP_LOGW(TAG, "DeepSeek request failed err=%s status=%d body=%.512s",
                 esp_err_to_name(err), status, resp.raw_error);
        finish_voice_with_fallback("DeepSeek: request failed", "我刚才联网慢了，再问我一次。");
    }

    if (err != ESP_OK) {
        /* A transport failure can leave the reusable handle in a bad state. */
        esp_http_client_cleanup(client);
        s_http_client = NULL;
    }
    free(body);
    free(response);

done:
    free(trigger);
    s_request_running = false;
    vTaskDelete(NULL);
}

void app_llm_start(void)
{
    s_state_lock = xSemaphoreCreateMutex();
    s_schedule_pending_lock = xSemaphoreCreateMutex();
    app_memory_init();
    if (CONFIG_SMART_POT_LLM_ENABLE) {
        app_ui_set_dialog_status("DeepSeek: ready");
    } else {
        app_ui_set_dialog_status("DeepSeek: disabled");
    }
}

void app_llm_update_plant_state(const app_plant_state_t *state)
{
    if (state == NULL) {
        return;
    }

    if (s_state_lock != NULL && xSemaphoreTake(s_state_lock, pdMS_TO_TICKS(50)) == pdTRUE) {
        s_latest_state = *state;
        xSemaphoreGive(s_state_lock);
    } else {
        s_latest_state = *state;
    }
}

static bool start_llm_request(const char *trigger)
{
    if (s_request_running) {
        app_ui_set_dialog_status("DeepSeek: still thinking...");
        return false;
    }

    char *trigger_copy = strdup(trigger ? trigger : "voice");
    if (trigger_copy == NULL) {
        app_ui_set_dialog_status("DeepSeek: memory not enough");
        return false;
    }

    s_request_running = true;
    if (xTaskCreate(llm_request_task, "deepseek_req", 8192, trigger_copy, 5, NULL) != pdPASS) {
        free(trigger_copy);
        s_request_running = false;
        app_ui_set_dialog_status("DeepSeek: task start failed");
        return false;
    }
    return true;
}

void app_llm_request_care_tip(const char *trigger)
{
    start_llm_request(trigger);
}

static bool text_contains_any(const char *text, const char *const *markers, size_t marker_count)
{
    if (text == NULL) {
        return false;
    }
    for (size_t i = 0; i < marker_count; i++) {
        if (strstr(text, markers[i]) != NULL) {
            return true;
        }
    }
    return false;
}

static void trim_command_text(char *text)
{
    if (text == NULL) {
        return;
    }

    char *start = text;
    while (*start != '\0' && (isspace((unsigned char)*start) || *start == ':' || *start == ',' || *start == ';' ||
                              *start == '.' || *start == '-' || *start == '_')) {
        start++;
    }
    if (start != text) {
        memmove(text, start, strlen(start) + 1);
    }

    size_t len = strlen(text);
    while (len > 0) {
        unsigned char ch = (unsigned char)text[len - 1];
        if (!isspace(ch) && ch != ':' && ch != ',' && ch != ';' && ch != '.' && ch != '-' && ch != '_') {
            break;
        }
        text[--len] = '\0';
    }

    static const char *const punctuation[] = { "，", "。", "；", "：", "！", "？" };
    bool changed;
    do {
        changed = false;
        for (size_t i = 0; i < sizeof(punctuation) / sizeof(punctuation[0]); i++) {
            size_t punctuation_size = strlen(punctuation[i]);
            if (strncmp(text, punctuation[i], punctuation_size) == 0) {
                memmove(text, text + punctuation_size, strlen(text + punctuation_size) + 1);
                changed = true;
            }
        }
    } while (changed);

    len = strlen(text);
    do {
        changed = false;
        for (size_t i = 0; i < sizeof(punctuation) / sizeof(punctuation[0]); i++) {
            size_t punctuation_size = strlen(punctuation[i]);
            if (len >= punctuation_size && strcmp(text + len - punctuation_size, punctuation[i]) == 0) {
                len -= punctuation_size;
                text[len] = '\0';
                changed = true;
            }
        }
    } while (changed && len > 0);
}

static void utf8_strlcpy(char *dst, const char *src, size_t dst_size)
{
    if (dst == NULL || src == NULL || dst_size == 0) {
        return;
    }

    size_t src_offset = 0;
    size_t dst_offset = 0;
    while (src[src_offset] != '\0') {
        unsigned char lead = (unsigned char)src[src_offset];
        size_t sequence_size = lead < 0x80 ? 1 :
                               (lead >= 0xc2 && lead <= 0xdf) ? 2 :
                               (lead >= 0xe0 && lead <= 0xef) ? 3 :
                               (lead >= 0xf0 && lead <= 0xf4) ? 4 : 0;
        if (sequence_size == 0) {
            src_offset++;
            continue;
        }
        bool valid = true;
        for (size_t i = 1; i < sequence_size; i++) {
            unsigned char byte = (unsigned char)src[src_offset + i];
            if (byte == '\0' || (byte & 0xc0) != 0x80) {
                valid = false;
                break;
            }
        }
        if (valid && sequence_size == 3) {
            unsigned char second = (unsigned char)src[src_offset + 1];
            valid = !((lead == 0xe0 && second < 0xa0) ||
                      (lead == 0xed && second >= 0xa0));
        } else if (valid && sequence_size == 4) {
            unsigned char second = (unsigned char)src[src_offset + 1];
            valid = !((lead == 0xf0 && second < 0x90) ||
                      (lead == 0xf4 && second >= 0x90));
        }
        if (!valid) {
            src_offset++;
            continue;
        }
        if (dst_offset + sequence_size >= dst_size) {
            break;
        }
        memcpy(dst + dst_offset, src + src_offset, sequence_size);
        src_offset += sequence_size;
        dst_offset += sequence_size;
    }
    dst[dst_offset] = '\0';
}

static char *find_last_separator(char *text)
{
    char *last = strrchr(text, ',');
    char *chinese = NULL;
    for (char *scan = strstr(text, "，"); scan != NULL; scan = strstr(scan + strlen("，"), "，")) {
        chinese = scan;
    }
    return chinese != NULL && (last == NULL || chinese > last) ? chinese : last;
}

static bool starts_with_text(const char *text, const char *prefix)
{
    return text != NULL && prefix != NULL && strncmp(text, prefix, strlen(prefix)) == 0;
}

static bool ends_with_text(const char *text, const char *suffix)
{
    if (text == NULL || suffix == NULL) {
        return false;
    }
    size_t text_len = strlen(text);
    size_t suffix_len = strlen(suffix);
    return text_len >= suffix_len && strcmp(text + text_len - suffix_len, suffix) == 0;
}

static void clean_schedule_event_text(char *event)
{
    if (event == NULL) {
        return;
    }
    trim_command_text(event);

    static const char *const leading_noise[] = {
        "请帮我提醒一下", "请帮我提醒", "帮我提醒一下", "帮我提醒",
        "给我提醒一下", "给我提醒", "提醒我一下", "提醒我",
        "提醒一下", "设置提醒", "设个提醒", "帮我设个提醒",
        "添加日程", "新增日程", "创建日程", "新建日程", "记录日程", "安排日程",
        "添加待办", "新增待办", "到时候提醒我", "到点提醒我",
        "请帮我", "帮我", "请", "记得", "到时候", "到点", "要",
    };
    bool changed = true;
    while (changed) {
        changed = false;
        for (size_t i = 0; i < sizeof(leading_noise) / sizeof(leading_noise[0]); i++) {
            if (starts_with_text(event, leading_noise[i])) {
                size_t size = strlen(leading_noise[i]);
                memmove(event, event + size, strlen(event + size) + 1);
                trim_command_text(event);
                changed = true;
                break;
            }
        }
    }

    static const char *const trailing_noise[] = {
        "提醒我", "提醒一下", "提醒", "的提醒", "的日程", "日程", "待办", "任务",
    };
    changed = true;
    while (changed) {
        changed = false;
        for (size_t i = 0; i < sizeof(trailing_noise) / sizeof(trailing_noise[0]); i++) {
            size_t size = strlen(trailing_noise[i]);
            if (ends_with_text(event, trailing_noise[i]) && strlen(event) > size) {
                event[strlen(event) - size] = '\0';
                trim_command_text(event);
                changed = true;
                break;
            }
        }
    }
}

static char *find_first_text(char *text, const char *const *needles, size_t needle_count, const char **matched)
{
    char *first = NULL;
    const char *first_match = NULL;
    for (size_t i = 0; i < needle_count; i++) {
        char *pos = strstr(text, needles[i]);
        if (pos != NULL && (first == NULL || pos < first)) {
            first = pos;
            first_match = needles[i];
        }
    }
    if (matched != NULL) {
        *matched = first_match;
    }
    return first;
}

static bool text_contains_any_token(const char *text, const char *const *tokens, size_t token_count)
{
    if (text == NULL) {
        return false;
    }
    for (size_t i = 0; i < token_count; i++) {
        if (strstr(text, tokens[i]) != NULL) {
            return true;
        }
    }
    return false;
}

static bool text_has_schedule_time_hint(const char *text)
{
    static const char *const time_hint_tokens[] = {
        "今天", "明天", "后天", "大后天", "今晚", "周", "星期",
        "上午", "中午", "下午", "晚上", "早上", "凌晨", "傍晚", "清晨",
        "月", "号", "日", "点", "点钟", "时", "分", "半", "一刻", "三刻", ":", "：",
    };
    return text_contains_any_token(text, time_hint_tokens,
                                   sizeof(time_hint_tokens) / sizeof(time_hint_tokens[0]));
}

static bool text_cancels_pending_schedule(const char *text)
{
    static const char *const cancel_tokens[] = {
        "取消", "算了", "不用了", "不要了", "先不", "别提醒", "取消日程", "取消提醒",
    };
    return text_contains_any_token(text, cancel_tokens,
                                   sizeof(cancel_tokens) / sizeof(cancel_tokens[0]));
}

static void schedule_pending_clear(void)
{
    if (s_schedule_pending_lock == NULL ||
        xSemaphoreTake(s_schedule_pending_lock, pdMS_TO_TICKS(50)) != pdTRUE) {
        return;
    }
    memset(&s_pending_schedule, 0, sizeof(s_pending_schedule));
    xSemaphoreGive(s_schedule_pending_lock);
}

static bool schedule_pending_is_active(void)
{
    if (s_schedule_pending_lock == NULL ||
        xSemaphoreTake(s_schedule_pending_lock, pdMS_TO_TICKS(50)) != pdTRUE) {
        return false;
    }
    bool active = s_pending_schedule.active &&
                  esp_timer_get_time() - s_pending_schedule.updated_us <= SCHEDULE_PENDING_TIMEOUT_US;
    xSemaphoreGive(s_schedule_pending_lock);
    return active;
}

static void schedule_pending_store(const char *event, const char *datetime)
{
    if (s_schedule_pending_lock == NULL ||
        ((event == NULL || event[0] == '\0') && (datetime == NULL || datetime[0] == '\0')) ||
        xSemaphoreTake(s_schedule_pending_lock, pdMS_TO_TICKS(50)) != pdTRUE) {
        return;
    }
    memset(&s_pending_schedule, 0, sizeof(s_pending_schedule));
    s_pending_schedule.active = true;
    utf8_strlcpy(s_pending_schedule.event, event != NULL ? event : "",
                 sizeof(s_pending_schedule.event));
    utf8_strlcpy(s_pending_schedule.datetime, datetime != NULL ? datetime : "",
                 sizeof(s_pending_schedule.datetime));
    s_pending_schedule.updated_us = esp_timer_get_time();
    ESP_LOGI(TAG, "Pending schedule saved: event=%s datetime=%s",
             s_pending_schedule.event, s_pending_schedule.datetime);
    xSemaphoreGive(s_schedule_pending_lock);
}

static bool schedule_pending_build_request(const char *user_text, char *out, size_t out_size)
{
    if (user_text == NULL || out == NULL || out_size == 0 ||
        s_schedule_pending_lock == NULL) {
        return false;
    }
    out[0] = '\0';
    if (text_cancels_pending_schedule(user_text)) {
        schedule_pending_clear();
        return false;
    }

    pending_schedule_t pending = { 0 };
    if (xSemaphoreTake(s_schedule_pending_lock, pdMS_TO_TICKS(50)) != pdTRUE) {
        return false;
    }
    if (s_pending_schedule.active &&
        esp_timer_get_time() - s_pending_schedule.updated_us <= SCHEDULE_PENDING_TIMEOUT_US) {
        pending = s_pending_schedule;
    } else if (s_pending_schedule.active) {
        memset(&s_pending_schedule, 0, sizeof(s_pending_schedule));
    }
    xSemaphoreGive(s_schedule_pending_lock);

    if (!pending.active) {
        return false;
    }

    bool has_time = text_has_schedule_time_hint(user_text);
    if (pending.event[0] != '\0' && pending.datetime[0] == '\0' && has_time) {
        snprintf(out, out_size, "提醒我%s，时间%s", pending.event, user_text);
        return true;
    }
    if (pending.datetime[0] != '\0' && pending.event[0] == '\0' && !has_time) {
        snprintf(out, out_size, "提醒我%s，时间%s", user_text, pending.datetime);
        return true;
    }
    if (pending.event[0] != '\0' && pending.datetime[0] != '\0') {
        snprintf(out, out_size, "提醒我%s，时间%s。补充说明：%s",
                 pending.event, pending.datetime, user_text);
        return true;
    }
    return false;
}

static char *previous_utf8_char(char *start, char *pos)
{
    if (pos <= start) {
        return start;
    }
    char *prev = pos - 1;
    while (prev > start && (((unsigned char)*prev & 0xc0) == 0x80)) {
        prev--;
    }
    return prev;
}

static bool is_time_number_char(const char *text, size_t size)
{
    static const char *const chars[] = {
        "零", "〇", "一", "二", "两", "三", "四", "五", "六", "七", "八", "九", "十",
    };
    if (size == 1 && text[0] >= '0' && text[0] <= '9') {
        return true;
    }
    for (size_t i = 0; i < sizeof(chars) / sizeof(chars[0]); i++) {
        if (strlen(chars[i]) == size && strncmp(text, chars[i], size) == 0) {
            return true;
        }
    }
    return false;
}

static char *include_time_number_prefix(char *work, char *time_start, const char *matched_marker)
{
    if (work == NULL || time_start == NULL || matched_marker == NULL ||
        (strcmp(matched_marker, "点") != 0 && strcmp(matched_marker, "点钟") != 0 &&
         strcmp(matched_marker, "时") != 0 && strcmp(matched_marker, ":") != 0 &&
         strcmp(matched_marker, "：") != 0)) {
        return time_start;
    }

    char *start = time_start;
    while (start > work) {
        char *prev = previous_utf8_char(work, start);
        if (!is_time_number_char(prev, (size_t)(start - prev))) {
            break;
        }
        start = prev;
    }
    return start;
}

static bool extract_inline_schedule_time(char *work, char *deadline, size_t deadline_size)
{
    static const char *const time_markers[] = {
        "今天", "明天", "后天", "大后天", "今晚", "今早", "今下午", "今晚上",
        "上午", "中午", "下午", "晚上", "早上", "凌晨", "傍晚", "清晨",
        "这周", "本周", "下周", "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天",
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天",
        "下个月", "月底", "月", "号", "日", "点", "点钟", "点半", "时", "分", "一刻", "三刻", "半", ":", "：",
    };
    static const char *const task_connectors[] = {
        "的时候要", "的时候", "时候", "时要", "时",
        "提交", "完成", "参加", "准备", "复习", "提醒", "开会",
        "交", "做", "写", "背", "买", "取", "拿", "发", "看", "去", "上", "的",
    };
    static const char *const task_noun_starts[] = {
        "口头报告", "报告", "作业", "考试", "会议", "实验", "论文", "答辩",
        "项目", "任务", "PPT", "ppt", "课程", "课", "学习", "浇花", "浇水",
        "吃药", "开会", "起床", "睡觉", "出门", "打卡", "运动", "锻炼", "取快递",
    };

    if (work == NULL || deadline == NULL || deadline_size == 0 || deadline[0] != '\0') {
        return false;
    }

    const char *matched_marker = NULL;
    char *time_start = find_first_text(work, time_markers,
                                       sizeof(time_markers) / sizeof(time_markers[0]),
                                       &matched_marker);
    if (time_start == NULL || matched_marker == NULL) {
        return false;
    }
    time_start = include_time_number_prefix(work, time_start, matched_marker);

    char *time_end = work + strlen(work);
    const char *connector = NULL;
    char *connector_pos = NULL;
    if (time_start == work) {
        connector_pos = find_first_text(time_start + strlen(matched_marker), task_connectors,
                                        sizeof(task_connectors) / sizeof(task_connectors[0]),
                                        &connector);
        if (connector_pos == NULL) {
            connector_pos = find_first_text(time_start + strlen(matched_marker), task_noun_starts,
                                            sizeof(task_noun_starts) / sizeof(task_noun_starts[0]),
                                            &connector);
            connector = NULL;
        }
        if (connector_pos == NULL) {
            return false;
        }
        if (connector_pos != NULL) {
            time_end = connector_pos;
        }
    }

    char saved = *time_end;
    *time_end = '\0';
    trim_command_text(time_start);
    if (!text_has_schedule_time_hint(time_start)) {
        *time_end = saved;
        return false;
    }
    utf8_strlcpy(deadline, time_start, deadline_size);
    trim_command_text(deadline);
    *time_end = saved;

    if (deadline[0] == '\0') {
        return false;
    }

    if (time_start == work) {
        char tail[256] = "";
        const char *task_start = time_end;
        if (connector != NULL &&
            (strcmp(connector, "的") == 0 || strcmp(connector, "的时候") == 0 ||
             strcmp(connector, "的时候要") == 0 || strcmp(connector, "时候") == 0 ||
             strcmp(connector, "时") == 0 || strcmp(connector, "时要") == 0)) {
            task_start = time_end + strlen(connector);
        }
        utf8_strlcpy(tail, task_start, sizeof(tail));
        trim_command_text(tail);
        /* Keep only the actual task in natural phrases such as “明天下午五点的时候要浇花”. */
        static const char *const tail_fillers[] = { "的时候", "时候", "时", "要" };
        bool removed_tail_filler = true;
        while (removed_tail_filler) {
            removed_tail_filler = false;
            for (size_t i = 0; i < sizeof(tail_fillers) / sizeof(tail_fillers[0]); i++) {
                if (starts_with_text(tail, tail_fillers[i])) {
                    size_t filler_size = strlen(tail_fillers[i]);
                    memmove(tail, tail + filler_size, strlen(tail + filler_size) + 1);
                    trim_command_text(tail);
                    removed_tail_filler = true;
                    break;
                }
            }
        }
        utf8_strlcpy(work, tail, 256);
    } else {
        *time_start = '\0';
        trim_command_text(work);
        static const char *const time_leaders[] = { "在", "于", "到", "截至", "截止", "最晚" };
        for (size_t i = 0; i < sizeof(time_leaders) / sizeof(time_leaders[0]); i++) {
            if (ends_with_text(work, time_leaders[i])) {
                work[strlen(work) - strlen(time_leaders[i])] = '\0';
                trim_command_text(work);
                break;
            }
        }
    }
    return true;
}

static bool parse_schedule_command(const char *user_text, char *item, size_t item_size,
                                   char *deadline, size_t deadline_size)
{
    static const char *const add_actions[] = {
        "帮我添加日程", "添加日程", "新增日程", "增加日程", "创建日程", "新建日程", "记录日程", "安排日程",
        "帮我添加", "添加一个", "新增一个", "增加一个", "创建一个", "新建一个", "记录一个", "安排一个",
        "帮我提醒一下", "帮我提醒", "给我提醒一下", "给我提醒", "提醒我一下", "提醒我",
        "提醒一下", "设置提醒", "设个提醒", "帮我设个提醒", "记得", "帮我记一下", "帮我记",
        "记一下", "记一个", "加一个",
        "加入日程", "加入一个", "加上一个", "加到日程", "加到一个", "列入一个", "放到日程", "放进日程", "放到一个",
        "添加", "新增", "增加", "创建", "新建", "记录", "安排", "加入", "加上", "加到", "列入", "放到",
    };
    static const char *const schedule_terms[] = {
        "日程", "待办事项", "待办", "任务", "提醒",
    };
    static const char *const schedule_suffixes[] = {
        "的日程", "的待办事项", "的待办", "的任务", "的提醒",
    };
    static const char *const deadline_markers[] = {
        "截止时间", "截止日期", "完成时间", "到期时间", "截止到", "截至", "截止", "到期", "最晚",
        "deadline", "due time", "due date", "due",
    };

    if (user_text == NULL || item == NULL || deadline == NULL || item_size == 0 || deadline_size == 0) {
        return false;
    }

    const char *action_pos = NULL;
    const char *action = NULL;
    for (size_t i = 0; i < sizeof(add_actions) / sizeof(add_actions[0]); i++) {
        const char *pos = strstr(user_text, add_actions[i]);
        if (pos != NULL && (action_pos == NULL || pos < action_pos)) {
            action_pos = pos;
            action = add_actions[i];
        }
    }
    if (action_pos == NULL || action == NULL) {
        return false;
    }

    char pre_action[128] = "";
    if (action_pos > user_text) {
        size_t prefix_size = (size_t)(action_pos - user_text);
        if (prefix_size >= sizeof(pre_action)) {
            prefix_size = sizeof(pre_action) - 1;
        }
        memcpy(pre_action, user_text, prefix_size);
        pre_action[prefix_size] = '\0';
        trim_command_text(pre_action);
    }

    char work[256];
    utf8_strlcpy(work, action_pos + strlen(action), sizeof(work));
    trim_command_text(work);

    static const char *const leading_fillers[] = {
        "一个新的", "一项新的", "一条新的", "一个", "一项", "一条", "一下", "新的", "个", "项", "条",
    };
    bool removed_filler = true;
    while (removed_filler) {
        removed_filler = false;
        for (size_t i = 0; i < sizeof(leading_fillers) / sizeof(leading_fillers[0]); i++) {
            if (starts_with_text(work, leading_fillers[i])) {
                size_t filler_size = strlen(leading_fillers[i]);
                memmove(work, work + filler_size, strlen(work + filler_size) + 1);
                trim_command_text(work);
                removed_filler = true;
                break;
            }
        }
    }

    const char *prefix_term = NULL;
    for (size_t i = 0; i < sizeof(schedule_terms) / sizeof(schedule_terms[0]); i++) {
        if (starts_with_text(work, schedule_terms[i])) {
            prefix_term = schedule_terms[i];
            break;
        }
    }
    bool prefix_form = prefix_term != NULL;
    char *schedule_suffix = NULL;
    const char *suffix_term = NULL;
    for (size_t i = 0; i < sizeof(schedule_suffixes) / sizeof(schedule_suffixes[0]); i++) {
        char *pos = strstr(work, schedule_suffixes[i]);
        if (pos != NULL && (schedule_suffix == NULL || pos < schedule_suffix)) {
            schedule_suffix = pos;
            suffix_term = schedule_suffixes[i];
        }
    }
    if (!prefix_form && schedule_suffix == NULL && work[0] == '\0') {
        return false;
    }

    char deadline_work[128] = "";
    if (prefix_form) {
        size_t prefix_size = strlen(prefix_term);
        memmove(work, work + prefix_size, strlen(work + prefix_size) + 1);
    } else if (schedule_suffix != NULL && suffix_term != NULL) {
        utf8_strlcpy(deadline_work, schedule_suffix + strlen(suffix_term), sizeof(deadline_work));
        *schedule_suffix = '\0';
    }
    trim_command_text(work);
    trim_command_text(deadline_work);

    char *deadline_pos = NULL;
    const char *deadline_marker = NULL;
    char *deadline_source = (prefix_form || deadline_work[0] == '\0') ? work : deadline_work;
    for (size_t i = 0; i < sizeof(deadline_markers) / sizeof(deadline_markers[0]); i++) {
        char *pos = strstr(deadline_source, deadline_markers[i]);
        if (pos != NULL && (deadline_pos == NULL || pos < deadline_pos)) {
            deadline_pos = pos;
            deadline_marker = deadline_markers[i];
        }
    }

    if (deadline_pos != NULL && deadline_marker != NULL) {
        char *after_marker = deadline_pos + strlen(deadline_marker);
        trim_command_text(after_marker);
        if (after_marker[0] != '\0') {
            utf8_strlcpy(deadline, after_marker, deadline_size);
            *deadline_pos = '\0';
        } else if (!prefix_form) {
            *deadline_pos = '\0';
            trim_command_text(deadline_source);
            utf8_strlcpy(deadline, deadline_source, deadline_size);
        } else {
            *deadline_pos = '\0';
            char *separator = find_last_separator(deadline_source);
            if (separator != NULL) {
                const size_t separator_size = strncmp(separator, "，", strlen("，")) == 0 ? strlen("，") : 1;
                utf8_strlcpy(deadline, separator + separator_size, deadline_size);
                *separator = '\0';
            } else {
                deadline[0] = '\0';
            }
        }
        trim_command_text(work);
        trim_command_text(deadline);
    } else {
        deadline[0] = '\0';
    }

    if (deadline[0] == '\0' && pre_action[0] != '\0' && text_has_schedule_time_hint(pre_action)) {
        utf8_strlcpy(deadline, pre_action, deadline_size);
        trim_command_text(deadline);
    }
    extract_inline_schedule_time(work, deadline, deadline_size);
    trim_command_text(work);
    for (size_t i = 0; i < sizeof(schedule_terms) / sizeof(schedule_terms[0]); i++) {
        if (ends_with_text(work, schedule_terms[i]) && strlen(work) > strlen(schedule_terms[i])) {
            work[strlen(work) - strlen(schedule_terms[i])] = '\0';
            trim_command_text(work);
            break;
        }
    }
    static const char *const trailing_fillers[] = {
        "添加到", "加入到", "放到", "加到", "加入", "添加", "日程里", "日程里面",
        "列表里", "列表里面", "里", "里面", "中", "到",
    };
    bool removed_trailing = true;
    while (removed_trailing) {
        removed_trailing = false;
        for (size_t i = 0; i < sizeof(trailing_fillers) / sizeof(trailing_fillers[0]); i++) {
            if (ends_with_text(work, trailing_fillers[i]) && strlen(work) > strlen(trailing_fillers[i])) {
                work[strlen(work) - strlen(trailing_fillers[i])] = '\0';
                trim_command_text(work);
                removed_trailing = true;
                break;
            }
        }
    }
    if (work[0] == '\0') {
        return false;
    }

    /* Final type check: a time expression must never be displayed as the task
     * while plain task text is placed in the Time column.  Some natural word
     * orders can reach this point with the two temporary fields reversed. */
    const bool item_looks_like_time = text_has_schedule_time_hint(work);
    const bool deadline_looks_like_time = text_has_schedule_time_hint(deadline);
    if (item_looks_like_time && deadline[0] != '\0' && !deadline_looks_like_time) {
        char swapped_task[256];
        char swapped_deadline[128];
        utf8_strlcpy(swapped_task, deadline, sizeof(swapped_task));
        utf8_strlcpy(swapped_deadline, work, sizeof(swapped_deadline));
        utf8_strlcpy(work, swapped_task, sizeof(work));
        utf8_strlcpy(deadline, swapped_deadline, deadline_size);
        trim_command_text(work);
        trim_command_text(deadline);
        ESP_LOGW(TAG, "Corrected reversed schedule fields: item=%s deadline=%s", work, deadline);
    }
    utf8_strlcpy(item, work[0] != '\0' ? work : "Untitled", item_size);
    return true;
}

typedef struct {
    char *buf;
    size_t len;
    size_t cap;
} schedule_http_resp_t;

static esp_err_t schedule_http_event_handler(esp_http_client_event_t *evt)
{
    if (evt == NULL || evt->event_id != HTTP_EVENT_ON_DATA || evt->data == NULL || evt->data_len <= 0) {
        return ESP_OK;
    }
    schedule_http_resp_t *resp = (schedule_http_resp_t *)evt->user_data;
    if (resp == NULL || resp->buf == NULL || resp->cap == 0) {
        return ESP_OK;
    }
    size_t available = resp->cap - resp->len - 1;
    size_t copy = (size_t)evt->data_len < available ? (size_t)evt->data_len : available;
    if (copy > 0) {
        memcpy(resp->buf + resp->len, evt->data, copy);
        resp->len += copy;
        resp->buf[resp->len] = '\0';
    }
    return ESP_OK;
}

static char *make_schedule_extract_body(const char *user_text, const struct tm *now_tm)
{
    char now_text[40];
    char prompt[1200];
    snprintf(now_text, sizeof(now_text), "%04d-%02d-%02d %02d:%02d",
             now_tm->tm_year + 1900, now_tm->tm_mon + 1, now_tm->tm_mday,
             now_tm->tm_hour, now_tm->tm_min);
    snprintf(prompt, sizeof(prompt),
             "当前北京时间是%s。用户语音原文：\"%s\"。"
             "请理解用户是否要添加提醒或日程，并纠正常见同音识别错误。"
             "只返回一个JSON对象，不要Markdown："
             "{\"intent\":\"add_schedule或none\",\"event\":\"纯事件\","
             "\"datetime\":\"YYYY-MM-DD HH:MM\",\"confidence\":0到1,"
             "\"clarification\":\"缺信息时的简短追问\"}。"
             "规则：event必须删除提醒词和全部时间词；datetime必须把今天、明天、后天、周几、"
             "过几小时等换算为未来的北京时间。只说几点且今天已过时取次日。"
             "如果事件和时间都清楚，confidence至少0.85；如果只有ASR同音小错但可纠正，confidence至少0.70。"
             "没有明确事件或无法确定具体时间时，datetime或event留空并填写clarification，禁止猜测。"
             "示例：用户说“后天早上八点提醒我吃饭”，event必须是“吃饭”，"
             "不能是“提醒我”或“提醒我吃饭”。",
             now_text, user_text != NULL ? user_text : "");

    cJSON *root = cJSON_CreateObject();
    if (root == NULL) return NULL;
    cJSON_AddStringToObject(root, "model", CONFIG_SMART_POT_DEEPSEEK_MODEL);
    cJSON_AddNumberToObject(root, "max_tokens", 180);
    cJSON_AddNumberToObject(root, "temperature", 0.0);
    cJSON_AddBoolToObject(root, "stream", false);
    cJSON *thinking = cJSON_AddObjectToObject(root, "thinking");
    cJSON_AddStringToObject(thinking, "type", "disabled");
    cJSON *messages = cJSON_AddArrayToObject(root, "messages");
    cJSON *system_msg = cJSON_CreateObject();
    cJSON_AddStringToObject(system_msg, "role", "system");
    cJSON_AddStringToObject(system_msg, "content",
                            "你是中文日程指令解析器。严格输出JSON，不回答问题，不添加用户没有说过的事件。"
                            "结合当前时间解析口语时间，并将ASR近音错字按上下文做最小纠正。");
    cJSON_AddItemToArray(messages, system_msg);
    cJSON *user_msg = cJSON_CreateObject();
    cJSON_AddStringToObject(user_msg, "role", "user");
    cJSON_AddStringToObject(user_msg, "content", prompt);
    cJSON_AddItemToArray(messages, user_msg);
    char *body = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return body;
}

static bool parse_schedule_extract_response(const char *http_body,
                                            char *event, size_t event_size,
                                            char *datetime, size_t datetime_size,
                                            char *clarification, size_t clarification_size,
                                            double *confidence)
{
    cJSON *outer = cJSON_Parse(http_body);
    if (outer == NULL) return false;
    cJSON *choices = cJSON_GetObjectItem(outer, "choices");
    cJSON *choice0 = cJSON_GetArrayItem(choices, 0);
    cJSON *message = cJSON_GetObjectItem(choice0, "message");
    cJSON *content = cJSON_GetObjectItem(message, "content");
    if (!cJSON_IsString(content) || content->valuestring == NULL) {
        cJSON_Delete(outer);
        return false;
    }

    const char *begin = strchr(content->valuestring, '{');
    const char *end = strrchr(content->valuestring, '}');
    if (begin == NULL || end == NULL || end < begin || (size_t)(end - begin + 1) >= 1024) {
        cJSON_Delete(outer);
        return false;
    }
    char json[1024];
    size_t json_len = (size_t)(end - begin + 1);
    memcpy(json, begin, json_len);
    json[json_len] = '\0';
    cJSON *result = cJSON_Parse(json);
    cJSON_Delete(outer);
    if (result == NULL) return false;

    cJSON *intent_obj = cJSON_GetObjectItem(result, "intent");
    cJSON *event_obj = cJSON_GetObjectItem(result, "event");
    cJSON *datetime_obj = cJSON_GetObjectItem(result, "datetime");
    cJSON *confidence_obj = cJSON_GetObjectItem(result, "confidence");
    cJSON *clarification_obj = cJSON_GetObjectItem(result, "clarification");
    bool is_schedule = cJSON_IsString(intent_obj) &&
                       strcmp(intent_obj->valuestring, "add_schedule") == 0;
    utf8_strlcpy(event, cJSON_IsString(event_obj) ? event_obj->valuestring : "", event_size);
    utf8_strlcpy(datetime, cJSON_IsString(datetime_obj) ? datetime_obj->valuestring : "", datetime_size);
    utf8_strlcpy(clarification,
                 cJSON_IsString(clarification_obj) ? clarification_obj->valuestring : "",
                 clarification_size);
    *confidence = cJSON_IsNumber(confidence_obj) ? confidence_obj->valuedouble : 0.0;
    cJSON_Delete(result);
    trim_command_text(event);
    clean_schedule_event_text(event);
    trim_command_text(datetime);
    trim_command_text(clarification);
    return is_schedule;
}

static bool validate_schedule_datetime(const char *datetime)
{
    int year = 0, month = 0, day = 0, hour = 0, minute = 0, consumed = 0;
    if (datetime == NULL ||
        sscanf(datetime, "%d-%d-%d %d:%d%n", &year, &month, &day, &hour, &minute, &consumed) != 5 ||
        datetime[consumed] != '\0' || year < 2024 || year > 2099 || month < 1 || month > 12 ||
        day < 1 || day > 31 || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
        return false;
    }
    struct tm due_tm = {
        .tm_year = year - 1900,
        .tm_mon = month - 1,
        .tm_mday = day,
        .tm_hour = hour,
        .tm_min = minute,
        .tm_sec = 0,
        .tm_isdst = -1,
    };
    time_t due = mktime(&due_tm);
    time_t now = time(NULL);
    return due > 0 && now > 0 && due > now;
}

static void schedule_extract_task(void *arg)
{
    char *user_text = (char *)arg;
    char event[96] = "";
    char datetime[64] = "";
    char clarification[128] = "";
    double confidence = 0.0;
    bool cloud_success = false;

    struct tm now_tm = { 0 };
    if (!app_time_get_local(&now_tm)) {
        finish_local_command_with_voice("时钟还没同步好，稍后再帮你添加日程。 ");
        goto done;
    }
    char *body = make_schedule_extract_body(user_text, &now_tm);
    char *response = calloc(1, 4096);
    if (body != NULL && response != NULL) {
        schedule_http_resp_t resp = { .buf = response, .cap = 4096 };
        esp_http_client_config_t cfg = {
            .url = CONFIG_SMART_POT_LLM_ENDPOINT,
            .method = HTTP_METHOD_POST,
            .event_handler = schedule_http_event_handler,
            .user_data = &resp,
            .crt_bundle_attach = esp_crt_bundle_attach,
            .timeout_ms = 18000,
            .keep_alive_enable = true,
        };
        esp_http_client_handle_t client = esp_http_client_init(&cfg);
        if (client != NULL) {
            char auth_header[192];
            snprintf(auth_header, sizeof(auth_header), "Bearer %s", CONFIG_SMART_POT_DEEPSEEK_API_KEY);
            esp_http_client_set_header(client, "Content-Type", "application/json");
            esp_http_client_set_header(client, "Authorization", auth_header);
            esp_http_client_set_post_field(client, body, strlen(body));
            esp_err_t err = esp_http_client_perform(client);
            int status = esp_http_client_get_status_code(client);
            ESP_LOGI(TAG, "Schedule extract HTTP err=%s status=%d", esp_err_to_name(err), status);
            if (err == ESP_OK && status >= 200 && status < 300) {
                cloud_success = parse_schedule_extract_response(response, event, sizeof(event),
                                                                datetime, sizeof(datetime),
                                                                clarification, sizeof(clarification),
                                                                &confidence);
                ESP_LOGI(TAG, "Schedule extract result event=%s datetime=%s confidence=%.2f clarification=%s",
                         event, datetime, confidence, clarification);
            } else {
                ESP_LOGW(TAG, "Schedule extract failed body=%.512s", response);
            }
            esp_http_client_cleanup(client);
        }
    }
    free(body);
    free(response);

    char fallback_item[96] = "";
    char fallback_deadline[64] = "";
    bool fallback_parsed = parse_schedule_command(user_text, fallback_item, sizeof(fallback_item),
                                                  fallback_deadline, sizeof(fallback_deadline));
    if (cloud_success && event[0] == '\0' && fallback_parsed && fallback_item[0] != '\0') {
        utf8_strlcpy(event, fallback_item, sizeof(event));
        ESP_LOGI(TAG, "Schedule event recovered from local parser: %s", event);
    }

    bool datetime_valid = validate_schedule_datetime(datetime);
    if (cloud_success && confidence >= SCHEDULE_CONFIDENCE_MIN && event[0] != '\0' && datetime_valid) {
        schedule_pending_clear();
        app_ui_add_schedule(event, datetime);
        app_ui_set_dialog_status("Schedule: smart added");
        finish_local_command_with_voice("好的，已经帮你安排好了。 ");
        goto done;
    }
    if (fallback_parsed && fallback_deadline[0] != '\0' &&
        (!cloud_success || event[0] == '\0' || !datetime_valid || confidence < SCHEDULE_CONFIDENCE_MIN)) {
        schedule_pending_clear();
        app_ui_add_schedule(fallback_item, fallback_deadline);
        app_ui_set_dialog_status(cloud_success ? "Schedule: local corrected" : "Schedule: local fallback");
        finish_local_command_with_voice(cloud_success ?
                                        "好的，已经帮你安排好了。 " :
                                        "网络有点慢，我先按本地识别帮你添加了。 ");
        goto done;
    }
    if (cloud_success) {
        schedule_pending_store(event, datetime_valid ? datetime : "");
        finish_local_command_with_voice(clarification[0] != '\0' ? clarification :
                                        "你想让我在什么时候提醒什么事情呢？");
        goto done;
    }

    /* Network/model failure: retain the deterministic parser as an offline fallback. */
    if (fallback_parsed && fallback_deadline[0] != '\0') {
        schedule_pending_clear();
        app_ui_add_schedule(fallback_item, fallback_deadline);
        app_ui_set_dialog_status("Schedule: local fallback");
        finish_local_command_with_voice("网络有点慢，我先按本地识别帮你添加了。 ");
    } else if (fallback_parsed && fallback_item[0] != '\0') {
        schedule_pending_store(fallback_item, "");
        finish_local_command_with_voice("我听到了事情，还差提醒时间。你可以直接说八点、明天上午八点这样。 ");
    } else {
        finish_local_command_with_voice("我没能确定具体时间和事情，请再说完整一点。 ");
    }

done:
    free(user_text);
    s_request_running = false;
    vTaskDelete(NULL);
}

static bool looks_like_schedule_add_command(const char *text)
{
    static const char *const explicit_markers[] = {
        "提醒我", "帮我提醒", "给我提醒", "设置提醒", "设个提醒", "提醒一下",
        "添加日程", "新增日程", "创建日程", "新建日程", "记录日程", "安排日程",
        "加入日程", "加到日程", "放进日程", "添加待办", "新增待办",
        "别忘了", "不要忘了", "到时候提醒", "到点提醒",
    };
    static const char *const task_markers[] = {
        "报告", "口头报告", "作业", "考试", "会议", "开会", "实验", "论文",
        "答辩", "项目", "PPT", "ppt", "课程", "复习", "浇花", "浇水",
        "吃饭", "吃药", "喝水", "起床", "出门", "打卡", "运动", "取快递",
    };
    static const char *const question_markers[] = {
        "吗", "呢", "是不是", "有没有", "多少", "几号", "几点", "？", "?",
    };
    if (text == NULL || strstr(text, "番茄") != NULL || strstr(text, "查看日程") != NULL ||
        strstr(text, "打开日程") != NULL || strstr(text, "显示日程") != NULL) {
        return false;
    }
    if (text_contains_any(text, explicit_markers,
                          sizeof(explicit_markers) / sizeof(explicit_markers[0]))) {
        return true;
    }
    if (text_has_schedule_time_hint(text) &&
        text_contains_any(text, task_markers, sizeof(task_markers) / sizeof(task_markers[0])) &&
        !text_contains_any(text, question_markers, sizeof(question_markers) / sizeof(question_markers[0]))) {
        return true;
    }
    return text_has_schedule_time_hint(text) &&
           (strstr(text, "记得") != NULL || strstr(text, "叫我") != NULL ||
            strstr(text, "喊我") != NULL || strstr(text, "到时候") != NULL);
}

static bool start_schedule_extract_request(const char *user_text)
{
    if (s_request_running) return false;
    char *copy = strdup(user_text != NULL ? user_text : "");
    if (copy == NULL) return false;
    s_request_running = true;
    app_ui_set_dialog_status("Schedule: understanding...");
    if (xTaskCreate(schedule_extract_task, "schedule_ai", 8192, copy, 5, NULL) != pdPASS) {
        s_request_running = false;
        free(copy);
        return false;
    }
    return true;
}

static bool handle_voice_mode_command(const char *user_text)
{
    static const char *const pomodoro_markers[] = {
        "番茄钟", "番茄中", "番茄计时", "打开番茄", "开启番茄", "开始番茄",
        "专注模式", "专注计时", "开始专注", "进入专注", "我要专注",
        "开始学习", "专注学习", "学习计时", "二十五分钟专注", "二十五分钟计时",
    };
    static const char *const stop_pomodoro_markers[] = {
        "关闭番茄钟", "退出番茄钟", "结束番茄钟", "停止番茄钟",
        "关闭番茄中", "退出专注", "结束专注", "停止计时",
    };
    static const char *const enable_markers[] = {
        "开启长对话", "打开长对话", "开长对话", "启用长对话",
        "进入长对话", "开启连续对话", "打开连续对话", "进入连续对话",
    };
    static const char *const disable_markers[] = {
        "关闭长对话", "关掉长对话", "关长对话", "退出长对话",
        "取消长对话", "结束长对话", "关闭连续对话", "退出连续对话",
    };
    static const char *const show_schedule_markers[] = {
        "查看日程", "打开日程", "进入日程", "显示日程", "看看日程",
        "查看待办", "打开待办", "显示待办", "看看待办", "待办列表",
        "查看任务", "打开任务", "任务列表", "提醒列表",
        "show schedule", "open schedule", "view schedule", "schedule page",
    };

    if (text_cancels_pending_schedule(user_text) && schedule_pending_is_active()) {
        schedule_pending_clear();
        app_ui_set_dialog_status("Schedule: pending canceled");
        finish_local_command_with_voice("好的，这条日程先不加了。");
        return true;
    }

    char pending_schedule_request[256];
    if (schedule_pending_build_request(user_text, pending_schedule_request,
                                       sizeof(pending_schedule_request))) {
        ESP_LOGI(TAG, "Schedule pending merged: %s", pending_schedule_request);
        if (!start_schedule_extract_request(pending_schedule_request)) {
            finish_local_command_with_voice("我还在处理上一条指令，请稍等一下。 ");
        }
        return true;
    }

    if (looks_like_schedule_add_command(user_text)) {
        if (!start_schedule_extract_request(user_text)) {
            finish_local_command_with_voice("我还在处理上一条指令，请稍等一下。 ");
        }
        return true;
    }

    char schedule_item[96];
    char schedule_deadline[64];
    if (parse_schedule_command(user_text, schedule_item, sizeof(schedule_item),
                               schedule_deadline, sizeof(schedule_deadline))) {
        ESP_LOGI(TAG, "Schedule command parsed: item=%s deadline=%s", schedule_item, schedule_deadline);
        schedule_pending_clear();
        app_ui_add_schedule(schedule_item, schedule_deadline);
        app_ui_set_dialog_status("Schedule: added");
        finish_local_command_with_voice("已添加日程。");
        return true;
    }
    if (strstr(user_text, "口头报告") != NULL &&
        (strstr(user_text, "添加") != NULL || strstr(user_text, "加入") != NULL ||
         strstr(user_text, "加到") != NULL || strstr(user_text, "加上") != NULL ||
         strstr(user_text, "记录") != NULL || strstr(user_text, "安排") != NULL ||
         strstr(user_text, "日程") != NULL || strstr(user_text, "待办") != NULL ||
         strstr(user_text, "任务") != NULL)) {
        ESP_LOGI(TAG, "Schedule command fallback: item=口头报告 text=%s", user_text);
        schedule_pending_clear();
        app_ui_add_schedule("口头报告", "");
        app_ui_set_dialog_status("Schedule: added");
        finish_local_command_with_voice("已添加日程。");
        return true;
    }

    if (text_contains_any(user_text, show_schedule_markers,
                          sizeof(show_schedule_markers) / sizeof(show_schedule_markers[0]))) {
        app_ui_show_schedule_page();
        app_ui_set_dialog_status("Schedule: shown");
        finish_local_command_with_voice("这是你的日程。");
        return true;
    }

    if (text_contains_any(user_text, stop_pomodoro_markers,
                          sizeof(stop_pomodoro_markers) / sizeof(stop_pomodoro_markers[0]))) {
        app_ui_stop_pomodoro();
        app_ui_set_dialog_status("Pomodoro: stopped");
        finish_local_command_with_voice("番茄钟已结束。");
        return true;
    }

    if (text_contains_any(user_text, pomodoro_markers, sizeof(pomodoro_markers) / sizeof(pomodoro_markers[0]))) {
        bool started = app_ui_start_pomodoro();
        app_ui_set_dialog_status(started ? "Pomodoro: focus mode" : "Pomodoro: start failed");
        finish_local_command_with_voice(started ?
                                        "番茄钟开始，努力专注。" :
                                        "番茄钟暂时没打开，请再试一次。");
        return true;
    }

    if (text_contains_any(user_text, disable_markers, sizeof(disable_markers) / sizeof(disable_markers[0]))) {
        app_voice_set_long_conversation(false);
        app_ui_refresh_long_mode();
        app_ui_set_dialog_status("Mode: short conversation");
        (void)app_tts_speak_text("已关闭长对话。");
        return true;
    }

    if (text_contains_any(user_text, enable_markers, sizeof(enable_markers) / sizeof(enable_markers[0]))) {
        app_voice_set_long_conversation(true);
        app_ui_refresh_long_mode();
        app_ui_set_dialog_status("Mode: long conversation");
        (void)app_tts_speak_text("已开启长对话。");
        return true;
    }

    return false;
}

bool app_llm_request_voice_reply(const char *user_text)
{
    if (handle_voice_mode_command(user_text)) {
        return true;
    }

    app_memory_command_t command = app_memory_get_command(user_text);
    if (command == APP_MEMORY_COMMAND_CLEAR_ALL) {
        app_memory_clear_all();
        app_ui_set_dialog_status("Memory: all cleared");
        return app_tts_speak_text("好的，所有记忆已清除。");
    }
    if (command == APP_MEMORY_COMMAND_CLEAR_PROFILE) {
        app_memory_clear_profile();
        app_ui_set_dialog_status("Memory: profile cleared");
        return app_tts_speak_text("好的，长期记忆已清除。");
    }
    if (command == APP_MEMORY_COMMAND_CLEAR_DIALOGUE) {
        app_memory_clear_dialogue();
        app_ui_set_dialog_status("Memory: cleared");
        return app_tts_speak_text("好的，记忆已清除。");
    }
    app_memory_learn_profile(user_text);
    /* Only warm Seed-TTS for cloud dialogue. Local commands above need the
     * queue immediately and must never be displaced by a prewarm command. */
    (void)app_tts_prepare_connection();
    if (!start_llm_request(user_text ? user_text : "voice wake")) {
        return app_tts_speak_text("我还在处理上一句，稍等一下。");
    }
    return true;
}
