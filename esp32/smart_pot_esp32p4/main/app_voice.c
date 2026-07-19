#include "app_voice.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "bsp/esp-bsp.h"
#include "esp_codec_dev.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_memory_utils.h"
#include "esp_wn_iface.h"
#include "esp_wn_models.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "model_path.h"

#include "app_asr.h"
#include "app_board_config.h"
#include "app_llm.h"
#include "app_tts.h"
#include "app_ui.h"
#include "app_wifi.h"

#ifndef CONFIG_SMART_POT_VOICE_WAKE_ENABLE
#define CONFIG_SMART_POT_VOICE_WAKE_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_VOICE_WAKE_COOLDOWN_MS
#define CONFIG_SMART_POT_VOICE_WAKE_COOLDOWN_MS 1500
#endif

#ifndef CONFIG_SMART_POT_VOICE_WAKE_THRESHOLD
#define CONFIG_SMART_POT_VOICE_WAKE_THRESHOLD 70
#endif

#ifndef CONFIG_SMART_POT_VOICE_WAKE_WAKENET_THRESHOLD_PERCENT
#define CONFIG_SMART_POT_VOICE_WAKE_WAKENET_THRESHOLD_PERCENT 45
#endif

#define VOICE_SAMPLE_RATE 16000
#define VOICE_FOLLOWUP_WINDOW_MS 12000
#define VOICE_REARM_DRAIN_FRAMES 4
#define VOICE_WAKE_PREROLL_PACKETS 12
#define VOICE_WAKE_WORD "你好小麦"
#define VOICE_WAKE_MODEL_NAME "ni3hao3xiao3mai4_tts2"
#define VOICE_WAKE_HINT "Wake: XiaoMai"

#ifndef APP_BOARD_WAKENET_ENABLE
#define APP_BOARD_WAKENET_ENABLE 0
#endif

#ifndef APP_BOARD_VOICE_WAKE_DIRECT_SAMPLES
#define APP_BOARD_VOICE_WAKE_DIRECT_SAMPLES 1600
#endif

#ifndef APP_BOARD_VOICE_WAKE_THRESHOLD
#define APP_BOARD_VOICE_WAKE_THRESHOLD CONFIG_SMART_POT_VOICE_WAKE_THRESHOLD
#endif

#ifndef APP_BOARD_VOICE_WAKE_THRESHOLD_MAX
#define APP_BOARD_VOICE_WAKE_THRESHOLD_MAX 300
#endif

#ifndef APP_BOARD_VOICE_WAKE_ENERGY_PACKETS
#define APP_BOARD_VOICE_WAKE_ENERGY_PACKETS 2
#endif

#ifndef APP_BOARD_VOICE_WAKELESS_FOLLOWUP_ENABLE
#define APP_BOARD_VOICE_WAKELESS_FOLLOWUP_ENABLE 0
#endif

#ifndef APP_BOARD_VOICE_TASK_STACK_BYTES
#define APP_BOARD_VOICE_TASK_STACK_BYTES 12288
#endif

static const char *TAG = "app_voice";
static volatile bool s_pause_requested;
static volatile bool s_mic_paused;
static volatile bool s_voice_ready;
static volatile bool s_manual_request;
static volatile bool s_conversation_busy;
static volatile bool s_wakenet_rearm_requested;
static volatile bool s_followup_request;
static volatile bool s_long_conversation_enabled;
static volatile TickType_t s_followup_deadline;

static bool transcript_has_text(const char *text)
{
    if (text == NULL) {
        return false;
    }
    while (*text == ' ' || *text == '\t' || *text == '\r' || *text == '\n') {
        text++;
    }
    if (*text == '\0') {
        return false;
    }
    return true;
}

static void trim_voice_text(char *text)
{
    if (text == NULL) {
        return;
    }

    char *start = text;
    while (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n' ||
           *start == ',' || *start == '.' || *start == ':' || *start == ';' ||
           *start == '!' || *start == '?') {
        start++;
    }
    if (start != text) {
        memmove(text, start, strlen(start) + 1);
    }

    size_t len = strlen(text);
    while (len > 0) {
        char ch = text[len - 1];
        if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n' &&
            ch != ',' && ch != '.' && ch != ':' && ch != ';' &&
            ch != '!' && ch != '?') {
            break;
        }
        text[--len] = '\0';
    }

    static const char *const punctuation[] = { "，", "。", "！", "？", "、", "：", "；" };
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

static bool remove_wake_phrase(char *text)
{
    if (text == NULL) {
        return false;
    }

    static const char *const wake_variants[] = {
        VOICE_WAKE_WORD, "你好，小麦", "你好 小麦", "你好小卖", "你好小脉",
        "你好小迈", "你好晓麦", "你好小妹", "你 好 小 麦", "你好 小 麦",
        "你好 小卖", "你好 小脉", "你好 小迈", "你好 晓麦", "你好 小妹",
        "你好小美", "你好 小美", "你好啊小麦", "你好呀小麦",
        "小麦", "小 麦", "小卖", "小脉", "小迈", "晓麦", "小妹", "小美",
    };
    trim_voice_text(text);

    const char *matched = NULL;
    for (size_t i = 0; i < sizeof(wake_variants) / sizeof(wake_variants[0]); i++) {
        size_t variant_len = strlen(wake_variants[i]);
        if (strncmp(text, wake_variants[i], variant_len) == 0) {
            matched = wake_variants[i];
            break;
        }
    }
    if (matched == NULL) {
        return false;
    }

    char *after = text + strlen(matched);
    memmove(text, after, strlen(after) + 1);
    trim_voice_text(text);
    return true;
}

#if !APP_BOARD_WAKENET_ENABLE
static int average_sample_level(const int16_t *samples, size_t sample_count)
{
    if (samples == NULL || sample_count == 0) {
        return 0;
    }
    int64_t sum = 0;
    for (size_t i = 0; i < sample_count; i++) {
        int32_t sample = samples[i];
        sum += sample < 0 ? -sample : sample;
    }
    return (int)(sum / (int64_t)sample_count);
}
#endif

static bool cooldown_done(TickType_t now, TickType_t last_wake_tick)
{
    return (now - last_wake_tick) > pdMS_TO_TICKS(CONFIG_SMART_POT_VOICE_WAKE_COOLDOWN_MS);
}

static bool open_microphone(esp_codec_dev_handle_t mic)
{
    esp_codec_dev_sample_info_t fs = {
        .sample_rate = VOICE_SAMPLE_RATE,
        .channel = 1,
        .bits_per_sample = 16,
    };
    if (esp_codec_dev_open(mic, &fs) != ESP_CODEC_DEV_OK) {
        ESP_LOGE(TAG, "Failed to open microphone codec");
        return false;
    }
    esp_codec_dev_set_in_gain(mic, 28.0f);
    return true;
}

static void transcribe_and_reply(esp_codec_dev_handle_t mic, bool require_wake_phrase);
static void transcribe_and_reply_with_prefix(esp_codec_dev_handle_t mic,
                                             bool require_wake_phrase,
                                             const int16_t *prefix_samples,
                                             size_t prefix_sample_count);

static void drain_microphone_frames(esp_codec_dev_handle_t mic, int16_t *samples,
                                    size_t bytes, int frames)
{
    if (mic == NULL || samples == NULL || bytes == 0) {
        return;
    }
    for (int i = 0; i < frames; i++) {
        (void)esp_codec_dev_read(mic, samples, bytes);
    }
}

static bool service_microphone_pause(esp_codec_dev_handle_t mic, int16_t *samples,
                                     size_t bytes, const char *ready_status)
{
    if (s_pause_requested) {
        if (!s_mic_paused) {
            if (esp_codec_dev_close(mic) != ESP_CODEC_DEV_OK) {
                ESP_LOGW(TAG, "Failed to pause microphone codec");
            } else {
                s_mic_paused = true;
                ESP_LOGI(TAG, "Microphone paused for speaker playback");
            }
        }
        vTaskDelay(pdMS_TO_TICKS(20));
        return true;
    }

    if (s_mic_paused) {
        if (!open_microphone(mic)) {
            app_ui_set_voice_status("Wake: mic resume failed");
            vTaskDelay(pdMS_TO_TICKS(200));
            return true;
        }
        drain_microphone_frames(mic, samples, bytes, VOICE_REARM_DRAIN_FRAMES);
        s_mic_paused = false;
        s_wakenet_rearm_requested = true;
        app_ui_set_voice_status(ready_status);
        ESP_LOGI(TAG, "Microphone resumed after speaker playback");
    }

    return false;
}

static bool service_pending_voice_request(esp_codec_dev_handle_t mic)
{
    if (s_manual_request) {
        s_manual_request = false;
        ESP_LOGI(TAG, "Manual voice conversation requested");
        app_ui_set_voice_status("ASR: listening");
        transcribe_and_reply(mic, false);
        return true;
    }

#if APP_BOARD_VOICE_WAKELESS_FOLLOWUP_ENABLE
    if (s_followup_request) {
        TickType_t now = xTaskGetTickCount();
        if ((int32_t)(now - s_followup_deadline) >= 0) {
            s_followup_request = false;
            app_ui_set_voice_status(VOICE_WAKE_HINT);
        } else {
            s_followup_request = false;
            ESP_LOGI(TAG, "Follow-up voice conversation requested");
            app_ui_set_voice_status("ASR: follow-up");
            transcribe_and_reply(mic, true);
            return true;
        }
    }
#endif

    return false;
}

#if !APP_BOARD_WAKENET_ENABLE
static void run_energy_wake_loop(esp_codec_dev_handle_t mic)
{
    size_t sample_count = APP_BOARD_VOICE_WAKE_DIRECT_SAMPLES;
    int16_t *samples = calloc(sample_count, sizeof(int16_t));
    int16_t *preroll = calloc(sample_count * VOICE_WAKE_PREROLL_PACKETS, sizeof(int16_t));
    int16_t *prefix = calloc(sample_count * VOICE_WAKE_PREROLL_PACKETS, sizeof(int16_t));
    if (samples == NULL || preroll == NULL || prefix == NULL) {
        ESP_LOGE(TAG, "Failed to allocate direct voice wake buffer");
        app_ui_set_voice_status("Wake: no memory");
        free(samples);
        free(preroll);
        free(prefix);
        vTaskDelete(NULL);
        return;
    }

    TickType_t last_wake_tick = 0;
    int wake_energy_packets = 0;
    int wake_threshold = APP_BOARD_VOICE_WAKE_THRESHOLD;
    if (wake_threshold <= 0) {
        wake_threshold = CONFIG_SMART_POT_VOICE_WAKE_THRESHOLD;
    }
    if (wake_threshold <= 0 || wake_threshold > APP_BOARD_VOICE_WAKE_THRESHOLD_MAX) {
        wake_threshold = APP_BOARD_VOICE_WAKE_THRESHOLD_MAX;
    }
    size_t preroll_head = 0;
    size_t preroll_count = 0;
    s_voice_ready = true;
    s_wakenet_rearm_requested = false;
    app_ui_set_voice_status(VOICE_WAKE_HINT);
    ESP_LOGW(TAG, "WakeNet inference disabled by board config; using ASR wake phrase confirmation threshold=%d",
             wake_threshold);

    while (true) {
        if (service_microphone_pause(mic, samples, sample_count * sizeof(int16_t), VOICE_WAKE_HINT)) {
            continue;
        }
        if (s_wakenet_rearm_requested) {
            s_wakenet_rearm_requested = false;
            preroll_head = 0;
            preroll_count = 0;
        }

        int ret = esp_codec_dev_read(mic, samples, sample_count * sizeof(int16_t));
        if (ret != ESP_CODEC_DEV_OK) {
            ESP_LOGW(TAG, "Microphone read failed: %d", ret);
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        if (service_pending_voice_request(mic)) {
            continue;
        }

        memcpy(preroll + preroll_head * sample_count, samples,
               sample_count * sizeof(int16_t));
        preroll_head = (preroll_head + 1) % VOICE_WAKE_PREROLL_PACKETS;
        if (preroll_count < VOICE_WAKE_PREROLL_PACKETS) {
            preroll_count++;
        }

        TickType_t wake_now = xTaskGetTickCount();
        int level = average_sample_level(samples, sample_count);
        if (!s_conversation_busy && level >= wake_threshold) {
            wake_energy_packets++;
        } else {
            wake_energy_packets = 0;
        }
        if (!s_conversation_busy && wake_energy_packets >= APP_BOARD_VOICE_WAKE_ENERGY_PACKETS &&
            cooldown_done(wake_now, last_wake_tick)) {
            last_wake_tick = wake_now;
            wake_energy_packets = 0;
            ESP_LOGI(TAG, "Energy wake candidate level=%d threshold=%d",
                     level, wake_threshold);
            app_ui_set_voice_status("Wake: checking phrase");
            size_t oldest = (preroll_head + VOICE_WAKE_PREROLL_PACKETS - preroll_count) %
                            VOICE_WAKE_PREROLL_PACKETS;
            for (size_t i = 0; i < preroll_count; i++) {
                size_t source = (oldest + i) % VOICE_WAKE_PREROLL_PACKETS;
                memcpy(prefix + i * sample_count, preroll + source * sample_count,
                       sample_count * sizeof(int16_t));
            }
            transcribe_and_reply_with_prefix(mic, true, prefix, preroll_count * sample_count);
        } else if (!s_conversation_busy && cooldown_done(wake_now, last_wake_tick)) {
            app_ui_set_voice_status(VOICE_WAKE_HINT);
        }
    }
}
#endif

#if APP_BOARD_WAKENET_ENABLE
static float wakenet_threshold_value(void)
{
    float threshold = (float)CONFIG_SMART_POT_VOICE_WAKE_WAKENET_THRESHOLD_PERCENT / 100.0f;
    if (threshold < 0.4f) {
        threshold = 0.4f;
    } else if (threshold > 0.9999f) {
        threshold = 0.9999f;
    }
    return threshold;
}

static model_iface_data_t *create_wakenet_data(const esp_wn_iface_t *wn_handle,
                                               const char *wn_name,
                                               int expected_sample_count)
{
    model_iface_data_t *wn_data = wn_handle->create(wn_name, DET_MODE_95);
    if (wn_data == NULL) {
        ESP_LOGE(TAG, "Failed to create direct WakeNet data for %s", wn_name);
        return NULL;
    }

    int sample_count = wn_handle->get_samp_chunksize(wn_data);
    if (sample_count <= 0 || sample_count > 4096 ||
        (expected_sample_count > 0 && sample_count != expected_sample_count)) {
        ESP_LOGE(TAG, "Invalid direct WakeNet sample count: got=%d expected=%d",
                 sample_count, expected_sample_count);
        wn_handle->destroy(wn_data);
        return NULL;
    }

    float threshold = wakenet_threshold_value();
    int threshold_result = wn_handle->set_det_threshold != NULL ?
                           wn_handle->set_det_threshold(wn_data, threshold, 1) : -1;
    ESP_LOGI(TAG, "WakeNet instance ready: model=%s samples=%d threshold=%.2f result=%d",
             wn_name, sample_count, threshold, threshold_result);
    return wn_data;
}

static void run_direct_wakenet_loop(esp_codec_dev_handle_t mic)
{
    srmodel_list_t *models = esp_srmodel_init("model");
    if (models == NULL) {
        ESP_LOGE(TAG, "Failed to load ESP-SR models from model partition");
        app_ui_set_voice_status("Wake: model missing");
        return;
    }

    char *wn_name = esp_srmodel_filter(models, "wn9", VOICE_WAKE_MODEL_NAME);
    if (wn_name == NULL) {
        ESP_LOGE(TAG, "WakeNet model wn9_%s not found", VOICE_WAKE_MODEL_NAME);
        app_ui_set_voice_status("Wake: model not found");
        return;
    }

    const esp_wn_iface_t *wn_handle = esp_wn_handle_from_name(wn_name);
    if (wn_handle == NULL || wn_handle->create == NULL || wn_handle->detect == NULL ||
        wn_handle->get_samp_chunksize == NULL || wn_handle->destroy == NULL) {
        ESP_LOGE(TAG, "Invalid direct WakeNet interface for %s", wn_name);
        app_ui_set_voice_status("Wake: model invalid");
        return;
    }

    model_iface_data_t *wn_data = create_wakenet_data(wn_handle, wn_name, 0);
    if (wn_data == NULL) {
        app_ui_set_voice_status("Wake: model failed");
        return;
    }

    int sample_count = wn_handle->get_samp_chunksize(wn_data);
    int sample_rate = wn_handle->get_samp_rate != NULL ? wn_handle->get_samp_rate(wn_data) : 0;
    int channel_count = wn_handle->get_channel_num != NULL ? wn_handle->get_channel_num(wn_data) : 1;
    if (sample_count <= 0 || sample_count > 4096) {
        ESP_LOGE(TAG, "Invalid direct WakeNet sample count: %d", sample_count);
        app_ui_set_voice_status("Wake: model shape invalid");
        wn_handle->destroy(wn_data);
        return;
    }

    int16_t *samples = heap_caps_aligned_calloc(16, (size_t)sample_count,
                                                sizeof(int16_t),
                                                MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    if (samples == NULL) {
        ESP_LOGE(TAG, "Failed to allocate aligned internal WakeNet buffer");
        app_ui_set_voice_status("Wake: no memory");
        wn_handle->destroy(wn_data);
        return;
    }

    TickType_t last_wake_tick = 0;
    s_voice_ready = true;
    s_wakenet_rearm_requested = false;
    app_ui_set_voice_status(VOICE_WAKE_HINT);
    ESP_LOGI(TAG, "Direct WakeNet active: model=%s chunks=%d rate=%d channels=%d buffer=%p internal=%d",
             wn_name, sample_count, sample_rate, channel_count, samples,
             esp_ptr_internal(samples));

    while (true) {
        if (s_pause_requested && wn_data != NULL) {
            wn_handle->destroy(wn_data);
            wn_data = NULL;
            ESP_LOGI(TAG, "WakeNet destroyed before speaker playback");
        }

        if (service_microphone_pause(mic, samples, (size_t)sample_count * sizeof(int16_t),
                                     VOICE_WAKE_HINT)) {
            continue;
        }

        if (wn_data == NULL) {
            wn_data = create_wakenet_data(wn_handle, wn_name, sample_count);
            if (wn_data == NULL) {
                app_ui_set_voice_status("Wake: model restart failed");
                vTaskDelay(pdMS_TO_TICKS(250));
                continue;
            }
            s_wakenet_rearm_requested = false;
            app_ui_set_voice_status(VOICE_WAKE_HINT);
            ESP_LOGI(TAG, "WakeNet recreated after speaker playback");
        }

        if (s_wakenet_rearm_requested) {
            s_wakenet_rearm_requested = false;
            if (wn_handle->clean != NULL) {
                wn_handle->clean(wn_data);
            }
        }

        int ret = esp_codec_dev_read(mic, samples, (size_t)sample_count * sizeof(int16_t));
        if (ret != ESP_CODEC_DEV_OK) {
            ESP_LOGW(TAG, "Microphone read failed: %d", ret);
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        if (service_pending_voice_request(mic)) {
            continue;
        }

        if (s_conversation_busy) {
            continue;
        }

        wakenet_state_t state = wn_handle->detect(wn_data, samples);
        TickType_t now = xTaskGetTickCount();
        if (state == WAKENET_DETECTED && !s_conversation_busy &&
            cooldown_done(now, last_wake_tick)) {
            last_wake_tick = now;
            int channel = wn_handle->get_triggered_channel != NULL ?
                          wn_handle->get_triggered_channel(wn_data) : -1;
            ESP_LOGI(TAG, "Direct WakeNet detected %s channel=%d",
                     VOICE_WAKE_WORD, channel);
            app_ui_set_voice_status("Wake: detected");
            transcribe_and_reply(mic, false);
        } else if (!s_conversation_busy && cooldown_done(now, last_wake_tick)) {
            app_ui_set_voice_status(VOICE_WAKE_HINT);
        }
    }
}
#endif

static void transcribe_and_reply(esp_codec_dev_handle_t mic, bool require_wake_phrase)
{
    transcribe_and_reply_with_prefix(mic, require_wake_phrase, NULL, 0);
}

static void transcribe_and_reply_with_prefix(esp_codec_dev_handle_t mic,
                                             bool require_wake_phrase,
                                             const int16_t *prefix_samples,
                                             size_t prefix_sample_count)
{
    s_conversation_busy = true;
    if (!app_wifi_is_connected()) {
        ESP_LOGW(TAG, "Voice conversation skipped: Wi-Fi offline");
        app_ui_set_voice_status("ASR: Wi-Fi offline");
        if (!app_tts_speak_text("网络还没连上。")) {
            app_voice_conversation_complete();
        }
        return;
    }

    char *transcript = app_asr_transcribe_from_mic_with_prefix(
        mic, prefix_samples, prefix_sample_count);
    if (transcript_has_text(transcript)) {
        ESP_LOGI(TAG, "ASR text: %s", transcript);
        if (require_wake_phrase) {
            if (!remove_wake_phrase(transcript)) {
                ESP_LOGI(TAG, "Energy wake ignored because wake phrase was not confirmed");
                free(transcript);
                app_voice_conversation_complete();
                return;
            }
            if (!transcript_has_text(transcript)) {
                ESP_LOGI(TAG, "Energy wake confirmed without a following command");
                app_ui_set_voice_status("Wake: confirmed");
                free(transcript);
                if (!app_tts_speak_text("我在呢。")) {
                    app_voice_conversation_complete();
                }
                return;
            }
            ESP_LOGI(TAG, "Wake phrase removed, command=%s", transcript);
        }
        app_ui_set_voice_status("ASR: got text");
        if (!app_llm_request_voice_reply(transcript)) {
            if (!app_tts_speak_text("我刚才卡住了，再说一次好吗。")) {
                app_voice_conversation_complete();
            }
        }
        free(transcript);
    } else {
        /* Empty ASR results must not masquerade as a spoken model response. */
        app_ui_set_voice_status("ASR: no speech");
        ESP_LOGW(TAG, "ASR returned no text; returning to wake state");
        free(transcript);
        app_voice_conversation_complete();
    }
}

static void voice_task(void *arg)
{
    (void)arg;

    if (!CONFIG_SMART_POT_VOICE_WAKE_ENABLE) {
        app_ui_set_voice_status("Wake: disabled");
        vTaskDelete(NULL);
        return;
    }

    esp_codec_dev_handle_t mic = bsp_audio_codec_microphone_init();
    if (mic == NULL) {
        ESP_LOGE(TAG, "Failed to initialize microphone codec");
        app_ui_set_voice_status("Wake: mic init failed");
        vTaskDelete(NULL);
        return;
    }

    if (!open_microphone(mic)) {
        app_ui_set_voice_status("Wake: mic open failed");
        vTaskDelete(NULL);
        return;
    }

#if APP_BOARD_WAKENET_ENABLE
    run_direct_wakenet_loop(mic);
#else
    run_energy_wake_loop(mic);
#endif
    vTaskDelete(NULL);
    return;
}

void app_voice_start(void)
{
    BaseType_t created = xTaskCreate(voice_task, "smart_pot_voice",
                                     APP_BOARD_VOICE_TASK_STACK_BYTES, NULL, 5, NULL);
    if (created != pdPASS) {
        ESP_LOGE(TAG, "Failed to create voice task");
        app_ui_set_voice_status("Wake: task failed");
    }
}

void app_voice_request_conversation(void)
{
    if (!s_voice_ready) {
        ESP_LOGW(TAG, "Manual voice conversation rejected: voice not ready");
        app_ui_set_voice_status("ASR: voice not ready");
        return;
    }

    if (s_manual_request || s_conversation_busy || s_pause_requested) {
        ESP_LOGW(TAG, "Manual voice conversation rejected: busy request=%d conversation=%d pause=%d",
                 s_manual_request, s_conversation_busy, s_pause_requested);
        app_ui_set_voice_status("ASR: busy");
        return;
    }

    ESP_LOGI(TAG, "Manual voice conversation accepted");
    s_manual_request = true;
}

bool app_voice_toggle_long_conversation(void)
{
    s_long_conversation_enabled = !s_long_conversation_enabled;
    ESP_LOGI(TAG, "Long conversation mode: %s", s_long_conversation_enabled ? "on" : "off");
    return s_long_conversation_enabled;
}

bool app_voice_set_long_conversation(bool enabled)
{
    s_long_conversation_enabled = enabled;
    ESP_LOGI(TAG, "Long conversation mode: %s", s_long_conversation_enabled ? "on" : "off");
    return s_long_conversation_enabled;
}

bool app_voice_long_conversation_enabled(void)
{
    return s_long_conversation_enabled;
}

void app_voice_conversation_complete(void)
{
    app_voice_conversation_complete_with_followup(false);
}

void app_voice_conversation_complete_with_followup(bool enable_followup)
{
    s_conversation_busy = false;
    s_wakenet_rearm_requested = true;
#if APP_BOARD_VOICE_WAKELESS_FOLLOWUP_ENABLE
    if (enable_followup && s_voice_ready && s_long_conversation_enabled) {
        s_followup_deadline = xTaskGetTickCount() + pdMS_TO_TICKS(VOICE_FOLLOWUP_WINDOW_MS);
        s_followup_request = true;
        app_ui_set_voice_status("ASR: keep talking");
    } else {
#else
    (void)enable_followup;
    {
#endif
        s_followup_request = false;
        app_ui_set_voice_status(VOICE_WAKE_HINT);
    }
}

static bool wait_for_microphone_state(bool paused, uint32_t timeout_ms)
{
    TickType_t deadline = xTaskGetTickCount() + pdMS_TO_TICKS(timeout_ms);
    while (s_mic_paused != paused) {
        if ((int32_t)(xTaskGetTickCount() - deadline) >= 0) {
            return false;
        }
        vTaskDelay(pdMS_TO_TICKS(20));
    }
    return true;
}

bool app_voice_pause_microphone(uint32_t timeout_ms)
{
    if (!s_voice_ready) {
        return true;
    }
    s_pause_requested = true;
    return wait_for_microphone_state(true, timeout_ms);
}

bool app_voice_resume_microphone(uint32_t timeout_ms)
{
    if (!s_voice_ready) {
        return true;
    }
    s_pause_requested = false;
    return wait_for_microphone_state(false, timeout_ms);
}
