#include "app_memory.h"

#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "nvs.h"

#define MEMORY_NAMESPACE "pot_memory"
#define MEMORY_NVS_KEY "dialogue"
#define PROFILE_NVS_KEY "profile"
#define MEMORY_VERSION 1
#define PROFILE_VERSION 1
#define MEMORY_MAX_EXCHANGES 6
#define MEMORY_USER_TEXT_MAX 128
#define MEMORY_ASSISTANT_TEXT_MAX 192
#define PROFILE_NAME_MAX 48
#define PROFILE_FACT_MAX 128
#define PROFILE_MAX_FACTS 8
#define PROFILE_PROMPT_MAX 1280

static const char *TAG = "app_memory";

typedef struct {
    char user[MEMORY_USER_TEXT_MAX];
    char assistant[MEMORY_ASSISTANT_TEXT_MAX];
} memory_exchange_t;

typedef struct {
    uint8_t version;
    uint8_t count;
    uint8_t head;
    memory_exchange_t exchanges[MEMORY_MAX_EXCHANGES];
} memory_store_t;

typedef struct {
    uint8_t version;
    uint8_t count;
    uint8_t head;
    char name[PROFILE_NAME_MAX];
    char facts[PROFILE_MAX_FACTS][PROFILE_FACT_MAX];
} profile_store_t;

static memory_store_t s_store;
static profile_store_t s_profile;
static SemaphoreHandle_t s_lock;
static nvs_handle_t s_nvs;
static bool s_ready;

static size_t utf8_sequence_size(unsigned char lead)
{
    if (lead < 0x80) {
        return 1;
    }
    if (lead >= 0xc2 && lead <= 0xdf) {
        return 2;
    }
    if (lead >= 0xe0 && lead <= 0xef) {
        return 3;
    }
    if (lead >= 0xf0 && lead <= 0xf4) {
        return 4;
    }
    return 0;
}

static void utf8_strlcpy(char *dst, const char *src, size_t dst_size)
{
    if (dst_size == 0) {
        return;
    }

    size_t src_offset = 0;
    size_t dst_offset = 0;
    while (src[src_offset] != '\0' && dst_offset + 1 < dst_size) {
        size_t sequence_size = utf8_sequence_size((unsigned char)src[src_offset]);
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
        if (!valid) {
            src_offset++;
            continue;
        }
        if (dst_offset + sequence_size >= dst_size) {
            break;
        }
        memcpy(dst + dst_offset, src + src_offset, sequence_size);
        dst_offset += sequence_size;
        src_offset += sequence_size;
    }
    dst[dst_offset] = '\0';
}

static void normalize_assistant_identity(char *text)
{
    static const char old_name[] = "小薯";
    static const char new_name[] = "小麦";
    char *found = text;

    while ((found = strstr(found, old_name)) != NULL) {
        memcpy(found, new_name, strlen(new_name));
        found += strlen(new_name);
    }
}

static void reset_store(void)
{
    memset(&s_store, 0, sizeof(s_store));
    s_store.version = MEMORY_VERSION;
}

static void reset_profile(void)
{
    memset(&s_profile, 0, sizeof(s_profile));
    s_profile.version = PROFILE_VERSION;
}

static void save_blob(const char *key, const void *value, size_t value_size)
{
    esp_err_t err = nvs_set_blob(s_nvs, key, value, value_size);
    if (err == ESP_OK) {
        err = nvs_commit(s_nvs);
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Failed to save memory key %s: %s", key, esp_err_to_name(err));
    }
}

static void save_store(void)
{
    save_blob(MEMORY_NVS_KEY, &s_store, sizeof(s_store));
}

static void save_profile(void)
{
    save_blob(PROFILE_NVS_KEY, &s_profile, sizeof(s_profile));
}

static void append_message(cJSON *messages, const char *role, const char *content)
{
    cJSON *message = cJSON_CreateObject();
    if (message == NULL) {
        return;
    }
    char valid_content[PROFILE_PROMPT_MAX];
    utf8_strlcpy(valid_content, content, sizeof(valid_content));
    cJSON_AddStringToObject(message, "role", role);
    cJSON_AddStringToObject(message, "content", valid_content);
    cJSON_AddItemToArray(messages, message);
}

void app_memory_init(void)
{
    if (s_ready) {
        return;
    }

    s_lock = xSemaphoreCreateMutex();
    if (s_lock == NULL) {
        ESP_LOGW(TAG, "Failed to create dialogue memory lock");
        return;
    }

    esp_err_t err = nvs_open(MEMORY_NAMESPACE, NVS_READWRITE, &s_nvs);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Failed to open dialogue memory NVS: %s", esp_err_to_name(err));
        return;
    }

    size_t blob_size = sizeof(s_store);
    err = nvs_get_blob(s_nvs, MEMORY_NVS_KEY, &s_store, &blob_size);
    if (err != ESP_OK || blob_size != sizeof(s_store) ||
        s_store.version != MEMORY_VERSION ||
        s_store.count > MEMORY_MAX_EXCHANGES ||
        s_store.head >= MEMORY_MAX_EXCHANGES) {
        reset_store();
        save_store();
    }

    blob_size = sizeof(s_profile);
    err = nvs_get_blob(s_nvs, PROFILE_NVS_KEY, &s_profile, &blob_size);
    if (err != ESP_OK || blob_size != sizeof(s_profile) ||
        s_profile.version != PROFILE_VERSION ||
        s_profile.count > PROFILE_MAX_FACTS ||
        s_profile.head >= PROFILE_MAX_FACTS) {
        reset_profile();
        save_profile();
    }

    s_ready = true;
    ESP_LOGI(TAG, "Memory ready: %u dialogue exchanges, %u profile facts restored",
             s_store.count, s_profile.count);
}

void app_memory_append_profile_message(cJSON *messages)
{
    if (!s_ready || messages == NULL ||
        xSemaphoreTake(s_lock, pdMS_TO_TICKS(100)) != pdTRUE) {
        return;
    }

    if (s_profile.name[0] == '\0' && s_profile.count == 0) {
        xSemaphoreGive(s_lock);
        return;
    }

    char prompt[PROFILE_PROMPT_MAX];
    int written = snprintf(prompt, sizeof(prompt),
                           "Long-term remembered user profile. Use only when relevant. Name: %s. Facts:",
                           s_profile.name[0] != '\0' ? s_profile.name : "unknown");
    uint8_t oldest = (s_profile.head + PROFILE_MAX_FACTS - s_profile.count) % PROFILE_MAX_FACTS;
    for (uint8_t i = 0; i < s_profile.count && written > 0 && written < sizeof(prompt); i++) {
        written += snprintf(prompt + written, sizeof(prompt) - written, " [%u] %s",
                            i + 1, s_profile.facts[(oldest + i) % PROFILE_MAX_FACTS]);
    }
    append_message(messages, "system", prompt);
    xSemaphoreGive(s_lock);
}

void app_memory_append_messages(cJSON *messages)
{
    if (!s_ready || messages == NULL ||
        xSemaphoreTake(s_lock, pdMS_TO_TICKS(100)) != pdTRUE) {
        return;
    }

    uint8_t oldest = (s_store.head + MEMORY_MAX_EXCHANGES - s_store.count) % MEMORY_MAX_EXCHANGES;
    for (uint8_t i = 0; i < s_store.count; i++) {
        memory_exchange_t *exchange = &s_store.exchanges[(oldest + i) % MEMORY_MAX_EXCHANGES];
        char assistant[MEMORY_ASSISTANT_TEXT_MAX];
        append_message(messages, "user", exchange->user);
        utf8_strlcpy(assistant, exchange->assistant, sizeof(assistant));
        normalize_assistant_identity(assistant);
        append_message(messages, "assistant", assistant);
    }

    xSemaphoreGive(s_lock);
}

void app_memory_add_exchange(const char *user_text, const char *assistant_text)
{
    if (!s_ready || user_text == NULL || assistant_text == NULL ||
        user_text[0] == '\0' || assistant_text[0] == '\0' ||
        xSemaphoreTake(s_lock, pdMS_TO_TICKS(100)) != pdTRUE) {
        return;
    }

    memory_exchange_t *exchange = &s_store.exchanges[s_store.head];
    utf8_strlcpy(exchange->user, user_text, sizeof(exchange->user));
    utf8_strlcpy(exchange->assistant, assistant_text, sizeof(exchange->assistant));
    normalize_assistant_identity(exchange->assistant);
    s_store.head = (s_store.head + 1) % MEMORY_MAX_EXCHANGES;
    if (s_store.count < MEMORY_MAX_EXCHANGES) {
        s_store.count++;
    }
    save_store();
    ESP_LOGI(TAG, "Dialogue memory saved: %u exchanges", s_store.count);
    xSemaphoreGive(s_lock);
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

static bool is_question(const char *text)
{
    static const char *const markers[] = { "吗", "呢", "什么", "哪", "？", "?" };
    return text_contains_any(text, markers, sizeof(markers) / sizeof(markers[0]));
}

static void trim_profile_value(char *text)
{
    size_t len = strlen(text);
    while (len > 0 && (text[len - 1] == ' ' || text[len - 1] == '.' ||
                       text[len - 1] == ',' || text[len - 1] == '!' ||
                       text[len - 1] == '?' || text[len - 1] == ';')) {
        text[--len] = '\0';
    }
    const char *punctuation[] = { "。", "，", "！", "？", "；" };
    for (size_t i = 0; i < sizeof(punctuation) / sizeof(punctuation[0]); i++) {
        char *end = strstr(text, punctuation[i]);
        if (end != NULL) {
            *end = '\0';
        }
    }
}

static bool extract_after_marker(const char *text, const char *marker, char *out, size_t out_size)
{
    const char *value = strstr(text, marker);
    if (value == NULL) {
        return false;
    }
    value += strlen(marker);
    while (*value == ' ' || *value == ':' || *value == '=') {
        value++;
    }
    utf8_strlcpy(out, value, out_size);
    trim_profile_value(out);
    return out[0] != '\0';
}

static void add_profile_fact(const char *fact)
{
    for (uint8_t i = 0; i < s_profile.count; i++) {
        uint8_t index = (s_profile.head + PROFILE_MAX_FACTS - s_profile.count + i) % PROFILE_MAX_FACTS;
        if (strcmp(s_profile.facts[index], fact) == 0) {
            return;
        }
    }

    utf8_strlcpy(s_profile.facts[s_profile.head], fact, PROFILE_FACT_MAX);
    s_profile.head = (s_profile.head + 1) % PROFILE_MAX_FACTS;
    if (s_profile.count < PROFILE_MAX_FACTS) {
        s_profile.count++;
    }
}

void app_memory_learn_profile(const char *user_text)
{
    static const char *const fact_markers[] = {
        "记住", "我喜欢", "我不喜欢", "我的生日", "我的纪念日", "我习惯", "我每天", "我通常"
    };
    char name[PROFILE_NAME_MAX];

    if (!s_ready || user_text == NULL || user_text[0] == '\0' ||
        is_question(user_text) ||
        xSemaphoreTake(s_lock, pdMS_TO_TICKS(100)) != pdTRUE) {
        return;
    }

    bool changed = false;
    if (extract_after_marker(user_text, "我的名字是", name, sizeof(name)) ||
        extract_after_marker(user_text, "我叫", name, sizeof(name)) ||
        extract_after_marker(user_text, "叫我", name, sizeof(name))) {
        if (strcmp(s_profile.name, name) != 0) {
            utf8_strlcpy(s_profile.name, name, sizeof(s_profile.name));
            changed = true;
        }
    }

    if (text_contains_any(user_text, fact_markers, sizeof(fact_markers) / sizeof(fact_markers[0]))) {
        uint8_t previous_count = s_profile.count;
        uint8_t previous_head = s_profile.head;
        add_profile_fact(user_text);
        changed = changed || previous_count != s_profile.count || previous_head != s_profile.head;
    }

    if (changed) {
        save_profile();
        ESP_LOGI(TAG, "Long-term profile saved: name=%s facts=%u",
                 s_profile.name[0] != '\0' ? s_profile.name : "unknown", s_profile.count);
    }
    xSemaphoreGive(s_lock);
}

void app_memory_clear_dialogue(void)
{
    if (!s_ready || xSemaphoreTake(s_lock, pdMS_TO_TICKS(100)) != pdTRUE) {
        return;
    }

    reset_store();
    save_store();
    xSemaphoreGive(s_lock);
    ESP_LOGI(TAG, "Dialogue memory cleared");
}

void app_memory_clear_profile(void)
{
    if (!s_ready || xSemaphoreTake(s_lock, pdMS_TO_TICKS(100)) != pdTRUE) {
        return;
    }

    reset_profile();
    save_profile();
    xSemaphoreGive(s_lock);
    ESP_LOGI(TAG, "Long-term profile cleared");
}

void app_memory_clear_all(void)
{
    app_memory_clear_dialogue();
    app_memory_clear_profile();
}

app_memory_command_t app_memory_get_command(const char *text)
{
    static const char *const clear_all[] = { "清除所有记忆", "清空所有记忆", "忘掉所有事情" };
    static const char *const clear_profile[] = { "清除长期记忆", "清空长期记忆", "忘掉我的档案", "忘记我的档案" };
    static const char *const clear_dialogue[] = { "清除记忆", "清空记忆", "忘掉我们的对话", "忘记我们的对话" };

    if (text_contains_any(text, clear_all, sizeof(clear_all) / sizeof(clear_all[0]))) {
        return APP_MEMORY_COMMAND_CLEAR_ALL;
    }
    if (text_contains_any(text, clear_profile, sizeof(clear_profile) / sizeof(clear_profile[0]))) {
        return APP_MEMORY_COMMAND_CLEAR_PROFILE;
    }
    if (text_contains_any(text, clear_dialogue, sizeof(clear_dialogue) / sizeof(clear_dialogue[0]))) {
        return APP_MEMORY_COMMAND_CLEAR_DIALOGUE;
    }
    return APP_MEMORY_COMMAND_NONE;
}
