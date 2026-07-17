#include "app_voice.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "bsp/esp-bsp.h"
#include "esp_afe_config.h"
#include "esp_afe_sr_iface.h"
#include "esp_afe_sr_models.h"
#include "esp_codec_dev.h"
#include "esp_log.h"
#include "esp_wn_iface.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "model_path.h"

#include "app_asr.h"
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
#define VOICE_WAKE_ENERGY_PACKETS 2
#define VOICE_WAKE_WORD "你好小麦"
#define VOICE_WAKE_MODEL_NAME "ni3hao3xiao3mai4_tts2"
#define VOICE_WAKE_HINT "Wake: XiaoMai"

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
        "你好小迈", "你好晓麦", "小麦", "小卖", "小脉", "小迈", "晓麦",
    };
    trim_voice_text(text);

    char *first = NULL;
    const char *matched = NULL;
    for (size_t i = 0; i < sizeof(wake_variants) / sizeof(wake_variants[0]); i++) {
        char *pos = strstr(text, wake_variants[i]);
        if (pos != NULL && (first == NULL || pos < first)) {
            first = pos;
            matched = wake_variants[i];
        }
    }
    if (first == NULL || matched == NULL) {
        return false;
    }

    char *after = first + strlen(matched);
    memmove(text, after, strlen(after) + 1);
    trim_voice_text(text);
    return true;
}

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

static int apply_wakenet_threshold(const esp_afe_sr_iface_t *afe_handle,
                                   esp_afe_sr_data_t *afe_data)
{
    if (afe_handle == NULL || afe_data == NULL || afe_handle->set_wakenet_threshold == NULL) {
        return -1;
    }
    float threshold = (float)CONFIG_SMART_POT_VOICE_WAKE_WAKENET_THRESHOLD_PERCENT / 100.0f;
    if (threshold < 0.4f) {
        threshold = 0.4f;
    } else if (threshold > 0.9999f) {
        threshold = 0.9999f;
    }
    return afe_handle->set_wakenet_threshold(afe_data, 1, threshold);
}

static void rearm_wakenet(const esp_afe_sr_iface_t *afe_handle, esp_afe_sr_data_t *afe_data)
{
    int reset_result = afe_handle->reset_buffer != NULL ? afe_handle->reset_buffer(afe_data) : -1;
    int disable_result = afe_handle->disable_wakenet != NULL ? afe_handle->disable_wakenet(afe_data) : -1;
    int enable_result = afe_handle->enable_wakenet != NULL ? afe_handle->enable_wakenet(afe_data) : -1;
    int threshold_result = afe_handle->reset_wakenet_threshold != NULL ?
                           afe_handle->reset_wakenet_threshold(afe_data, 1) : -1;
    int custom_threshold_result = apply_wakenet_threshold(afe_handle, afe_data);
    ESP_LOGI(TAG, "WakeNet rearmed: buffer=%d disable=%d enable=%d reset_threshold=%d set_threshold=%d",
             reset_result, disable_result, enable_result, threshold_result, custom_threshold_result);
}

static void transcribe_and_reply(esp_codec_dev_handle_t mic, bool require_wake_phrase)
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

    char *transcript = app_asr_transcribe_from_mic(mic);
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

    srmodel_list_t *models = esp_srmodel_init("model");
    if (models == NULL) {
        ESP_LOGE(TAG, "Failed to load ESP-SR models from model partition");
        app_ui_set_voice_status("Wake: model missing");
        vTaskDelete(NULL);
        return;
    }

    char *wn_name = esp_srmodel_filter(models, "wn9", VOICE_WAKE_MODEL_NAME);
    if (wn_name == NULL) {
        ESP_LOGE(TAG, "WakeNet model wn9_%s not found", VOICE_WAKE_MODEL_NAME);
        app_ui_set_voice_status("Wake: model not found");
        vTaskDelete(NULL);
        return;
    }

    afe_config_t *afe_config = afe_config_init("M", models, AFE_TYPE_SR, AFE_MODE_HIGH_PERF);
    if (afe_config == NULL) {
        ESP_LOGE(TAG, "Failed to create AFE config");
        app_ui_set_voice_status("Wake: AFE config failed");
        vTaskDelete(NULL);
        return;
    }

    afe_config->aec_init = false;
    afe_config->se_init = false;
    afe_config->ns_init = false;
    afe_config->vad_init = false;
    afe_config->agc_init = true;
    afe_config->wakenet_init = true;
    afe_config->wakenet_model_name = wn_name;
    afe_config->wakenet_mode = DET_MODE_95;
    afe_config->memory_alloc_mode = AFE_MEMORY_ALLOC_MORE_PSRAM;

    const esp_afe_sr_iface_t *afe_handle = esp_afe_handle_from_config(afe_config);
    esp_afe_sr_data_t *afe_data = afe_handle->create_from_config(afe_config);
    afe_config_free(afe_config);
    if (afe_data == NULL) {
        ESP_LOGE(TAG, "Failed to create AFE data");
        app_ui_set_voice_status("Wake: AFE start failed");
        vTaskDelete(NULL);
        return;
    }
    afe_handle->print_pipeline(afe_data);

    int feed_chunksize = afe_handle->get_feed_chunksize(afe_data);
    int feed_channels = afe_handle->get_feed_channel_num(afe_data);
    int16_t *samples = calloc(feed_chunksize * feed_channels, sizeof(int16_t));
    if (samples == NULL) {
        app_ui_set_voice_status("Wake: no memory");
        afe_handle->destroy(afe_data);
        vTaskDelete(NULL);
        return;
    }

    TickType_t last_wake_tick = 0;
    int wake_energy_packets = 0;
    s_voice_ready = true;
    int threshold_result = apply_wakenet_threshold(afe_handle, afe_data);
    app_ui_set_voice_status(VOICE_WAKE_HINT);
    ESP_LOGI(TAG, "WakeNet ready: model=%s, chunks=%d, channels=%d threshold=%d%% result=%d",
             wn_name, feed_chunksize, feed_channels,
             CONFIG_SMART_POT_VOICE_WAKE_WAKENET_THRESHOLD_PERCENT, threshold_result);

    while (true) {
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
            continue;
        }

        if (s_mic_paused) {
            if (!open_microphone(mic)) {
                app_ui_set_voice_status("Wake: mic resume failed");
                vTaskDelay(pdMS_TO_TICKS(200));
                continue;
            }
            drain_microphone_frames(mic, samples,
                                    feed_chunksize * feed_channels * sizeof(int16_t),
                                    VOICE_REARM_DRAIN_FRAMES);
            s_mic_paused = false;
            s_wakenet_rearm_requested = true;
            app_ui_set_voice_status(VOICE_WAKE_HINT);
            ESP_LOGI(TAG, "Microphone resumed after speaker playback");
        }

        if (s_wakenet_rearm_requested) {
            s_wakenet_rearm_requested = false;
            rearm_wakenet(afe_handle, afe_data);
        }

        int ret = esp_codec_dev_read(mic, samples, feed_chunksize * feed_channels * sizeof(int16_t));
        if (ret != ESP_CODEC_DEV_OK) {
            ESP_LOGW(TAG, "Microphone read failed: %d", ret);
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        if (s_manual_request) {
            s_manual_request = false;
            ESP_LOGI(TAG, "Manual voice conversation requested");
            app_ui_set_voice_status("ASR: listening");
            transcribe_and_reply(mic, false);
            continue;
        }

        if (s_followup_request) {
            TickType_t now = xTaskGetTickCount();
            if ((int32_t)(now - s_followup_deadline) >= 0) {
                s_followup_request = false;
                app_ui_set_voice_status(VOICE_WAKE_HINT);
            } else {
                s_followup_request = false;
                ESP_LOGI(TAG, "Follow-up voice conversation requested");
                app_ui_set_voice_status("ASR: follow-up");
                transcribe_and_reply(mic, false);
                continue;
            }
        }

        TickType_t wake_now = xTaskGetTickCount();
        int level = average_sample_level(samples, (size_t)feed_chunksize * (size_t)feed_channels);
        if (!s_conversation_busy && level >= CONFIG_SMART_POT_VOICE_WAKE_THRESHOLD) {
            wake_energy_packets++;
        } else {
            wake_energy_packets = 0;
        }
        if (!s_conversation_busy && wake_energy_packets >= VOICE_WAKE_ENERGY_PACKETS &&
            cooldown_done(wake_now, last_wake_tick)) {
            last_wake_tick = wake_now;
            wake_energy_packets = 0;
            ESP_LOGI(TAG, "Energy wake candidate level=%d threshold=%d",
                     level, CONFIG_SMART_POT_VOICE_WAKE_THRESHOLD);
            app_ui_set_voice_status("Wake: checking phrase");
            transcribe_and_reply(mic, true);
            continue;
        }

        afe_handle->feed(afe_data, samples);
        afe_fetch_result_t *res = afe_handle->fetch_with_delay(afe_data, 0);
        if (res == NULL || res->ret_value == ESP_FAIL) {
            continue;
        }

        TickType_t now = xTaskGetTickCount();
        if (res->wakeup_state == WAKENET_DETECTED && !s_conversation_busy &&
            cooldown_done(now, last_wake_tick)) {
            last_wake_tick = now;
            ESP_LOGI(TAG, "WakeNet detected %s, word=%d, model=%d",
                     VOICE_WAKE_WORD, res->wake_word_index, res->wakenet_model_index);
            app_ui_set_voice_status("Wake: detected");
            transcribe_and_reply(mic, false);
        } else if (!s_conversation_busy && cooldown_done(now, last_wake_tick)) {
            app_ui_set_voice_status(VOICE_WAKE_HINT);
        }
    }
}

void app_voice_start(void)
{
    xTaskCreate(voice_task, "smart_pot_wakenet", 8192, NULL, 5, NULL);
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
    if (enable_followup && s_voice_ready && s_long_conversation_enabled) {
        s_followup_deadline = xTaskGetTickCount() + pdMS_TO_TICKS(VOICE_FOLLOWUP_WINDOW_MS);
        s_followup_request = true;
        app_ui_set_voice_status("ASR: keep talking");
    } else {
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
