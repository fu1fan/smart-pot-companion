#include "app_cloud.h"

#include <stdio.h>
#include <string.h>
#include <time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "bsp/display.h"
#include "cJSON.h"
#include "esp_log.h"
#include "mqtt_client.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "app_tts.h"
#include "app_ui.h"

#ifndef CONFIG_SMART_POT_CLOUD_ENABLE
#define CONFIG_SMART_POT_CLOUD_ENABLE 0
#endif
#ifndef CONFIG_SMART_POT_DEVICE_ID
#define CONFIG_SMART_POT_DEVICE_ID "smartpot-p4-001"
#endif
#ifndef CONFIG_SMART_POT_MQTT_URI
#define CONFIG_SMART_POT_MQTT_URI "mqtt://127.0.0.1:1883"
#endif
#ifndef CONFIG_SMART_POT_MQTT_USERNAME
#define CONFIG_SMART_POT_MQTT_USERNAME ""
#endif
#ifndef CONFIG_SMART_POT_MQTT_PASSWORD
#define CONFIG_SMART_POT_MQTT_PASSWORD ""
#endif

static const char *TAG = "smart_pot_cloud";
static esp_mqtt_client_handle_t s_client;
static bool s_connected;
static uint64_t s_sequence;
static uint32_t s_last_touch_count;
static int s_brightness = 100;
static bool s_standby;
static char s_topic_prefix[128];
static char s_lwt_topic[160];
static char s_command_buffer[2048];
static size_t s_command_len;
#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
static app_motion_state_t s_motion;
#endif

static void timestamp_now(char *out, size_t size)
{
    time_t now = time(NULL);
    struct tm value = {0};
    gmtime_r(&now, &value);
    strftime(out, size, "%Y-%m-%dT%H:%M:%SZ", &value);
}

static const char *mood_name(app_mood_t mood)
{
    switch (mood) {
        case APP_MOOD_THIRSTY: return "THIRSTY";
        case APP_MOOD_DARK: return "DARK";
        case APP_MOOD_WEAK: return "WEAK";
        default: return "HAPPY";
    }
}

static void publish_json(const char *suffix, cJSON *root, bool retain)
{
    if (!s_connected || s_client == NULL || root == NULL) return;
    char topic[176];
    snprintf(topic, sizeof(topic), "%s/%s", s_topic_prefix, suffix);
    char *json = cJSON_PrintUnformatted(root);
    if (json != NULL) {
        esp_mqtt_client_publish(s_client, topic, json, 0, 1, retain ? 1 : 0);
        cJSON_free(json);
    }
}

static void publish_online(bool online)
{
    char timestamp[32]; timestamp_now(timestamp, sizeof(timestamp));
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "deviceId", CONFIG_SMART_POT_DEVICE_ID);
    cJSON_AddBoolToObject(root, "online", online);
    cJSON_AddStringToObject(root, "changedAt", timestamp);
    publish_json("online", root, true);
    cJSON_Delete(root);
}

static void publish_reported(void)
{
    char timestamp[32]; timestamp_now(timestamp, sizeof(timestamp));
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "schemaVersion", 1);
    cJSON_AddStringToObject(root, "deviceId", CONFIG_SMART_POT_DEVICE_ID);
    cJSON_AddStringToObject(root, "reportedAt", timestamp);
    cJSON_AddNumberToObject(root, "brightnessPercent", s_brightness);
    cJSON_AddNumberToObject(root, "volumePercent", app_tts_get_volume());
    cJSON_AddBoolToObject(root, "standby", s_standby);
    cJSON_AddItemToObject(root, "content", cJSON_CreateObject());
    cJSON_AddNumberToObject(root, "scheduleRevision", 0);
    cJSON_AddStringToObject(root, "firmwareVersion", "0.2.0");
    publish_json("reported", root, true);
    cJSON_Delete(root);
}

static void publish_ack(const char *command_id, const char *status, const char *message)
{
    char timestamp[32]; timestamp_now(timestamp, sizeof(timestamp));
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "schemaVersion", 1);
    cJSON_AddStringToObject(root, "commandId", command_id != NULL ? command_id : "unknown");
    cJSON_AddStringToObject(root, "deviceId", CONFIG_SMART_POT_DEVICE_ID);
    cJSON_AddStringToObject(root, "status", status);
    cJSON_AddStringToObject(root, "acknowledgedAt", timestamp);
    if (message != NULL) cJSON_AddStringToObject(root, "message", message);
    publish_json("acks", root, false);
    cJSON_Delete(root);
}

static void publish_event(const char *type)
{
    char timestamp[32], id[64];
    timestamp_now(timestamp, sizeof(timestamp));
    snprintf(id, sizeof(id), "%s-%llu", CONFIG_SMART_POT_DEVICE_ID, (unsigned long long)esp_timer_get_time());
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "schemaVersion", 1);
    cJSON_AddStringToObject(root, "eventId", id);
    cJSON_AddStringToObject(root, "deviceId", CONFIG_SMART_POT_DEVICE_ID);
    cJSON_AddStringToObject(root, "type", type);
    cJSON_AddStringToObject(root, "occurredAt", timestamp);
    cJSON_AddItemToObject(root, "data", cJSON_CreateObject());
    publish_json("events", root, false);
    cJSON_Delete(root);
}

static const char *emoji_text(const char *id)
{
    if (id == NULL) return "";
    if (strcmp(id, "heart") == 0) return "<3  来自远方的拥抱";
    if (strcmp(id, "smile") == 0) return "今天也要开心呀 :)";
    if (strcmp(id, "happy") == 0) return "我现在心情很好！";
    if (strcmp(id, "thirsty") == 0) return "有一点口渴啦";
    if (strcmp(id, "dark") == 0) return "这里有点暗，想看看阳光";
    if (strcmp(id, "weak") == 0) return "今天需要主人多关心一下";
    if (strcmp(id, "wave") == 0) return "远远地向你挥挥叶子";
    if (strcmp(id, "star") == 0) return "送你一颗小星星 *";
    if (strcmp(id, "water") == 0) return "滴答，记得看看水分";
    if (strcmp(id, "sun") == 0) return "今天也要晒晒太阳";
    if (strcmp(id, "sleep") == 0) return "晚安，做个好梦 zZ";
    if (strcmp(id, "flower") == 0) return "送你一朵小花";
    return "小麦收到你的表情啦";
}

static void handle_command(const char *json)
{
    cJSON *root = cJSON_Parse(json);
    if (root == NULL) return;
    cJSON *id = cJSON_GetObjectItem(root, "commandId");
    cJSON *type = cJSON_GetObjectItem(root, "type");
    cJSON *payload = cJSON_GetObjectItem(root, "payload");
    if (!cJSON_IsString(id) || !cJSON_IsString(type) || !cJSON_IsObject(payload)) {
        cJSON_Delete(root); return;
    }
    bool ok = true;
    if (strcmp(type->valuestring, "SET_BRIGHTNESS") == 0) {
        cJSON *value = cJSON_GetObjectItem(payload, "brightnessPercent");
        ok = cJSON_IsNumber(value) && value->valueint >= 0 && value->valueint <= 100;
        if (ok) { s_brightness = value->valueint; s_standby = value->valueint == 0; bsp_display_brightness_set(value->valueint); }
    } else if (strcmp(type->valuestring, "SET_VOLUME") == 0) {
        cJSON *value = cJSON_GetObjectItem(payload, "volumePercent");
        ok = cJSON_IsNumber(value) && value->valueint >= 0 && value->valueint <= 100;
        if (ok) app_tts_set_volume((uint8_t)value->valueint);
    } else if (strcmp(type->valuestring, "SET_STANDBY") == 0) {
        cJSON *value = cJSON_GetObjectItem(payload, "standby");
        ok = cJSON_IsBool(value);
        if (ok) { s_standby = cJSON_IsTrue(value); bsp_display_brightness_set(s_standby ? 0 : s_brightness); }
    } else if (strcmp(type->valuestring, "SHOW_CONTENT") == 0) {
        cJSON *text = cJSON_GetObjectItem(payload, "text");
        cJSON *emoji = cJSON_GetObjectItem(payload, "emojiId");
        cJSON *duration = cJSON_GetObjectItem(payload, "durationSeconds");
        const char *display = cJSON_IsString(text) && text->valuestring[0] ? text->valuestring : emoji_text(cJSON_IsString(emoji) ? emoji->valuestring : NULL);
        app_ui_show_remote_content(display, (uint32_t)(cJSON_IsNumber(duration) ? duration->valueint : 20) * 1000U);
        if (cJSON_IsString(emoji) && strcmp(emoji->valuestring, "heart") == 0) app_ui_play_touch_reaction();
    } else if (strcmp(type->valuestring, "REMOTE_TOUCH") == 0) {
        app_ui_play_touch_reaction();
        app_tts_speak_text_no_followup("收到你从远方传来的摸摸啦。");
        publish_event("REMOTE_TOUCH");
    } else if (strcmp(type->valuestring, "SPEAK_TEXT") == 0) {
        cJSON *text = cJSON_GetObjectItem(payload, "text");
        ok = cJSON_IsString(text);
        if (ok) app_tts_speak_text_no_followup(text->valuestring);
    } else if (strcmp(type->valuestring, "RESTART") == 0) {
        publish_ack(id->valuestring, "COMPLETED", "restarting");
        cJSON_Delete(root);
        vTaskDelay(pdMS_TO_TICKS(250));
        esp_restart();
        return;
    } else if (strcmp(type->valuestring, "SYNC_PROFILE") != 0 &&
               strcmp(type->valuestring, "SYNC_SCHEDULE") != 0) {
        ok = false;
    }
    publish_ack(id->valuestring, ok ? "COMPLETED" : "FAILED", ok ? NULL : "invalid payload");
    if (ok) publish_reported();
    cJSON_Delete(root);
}

static void mqtt_event(void *args, esp_event_base_t base, int32_t event_id, void *data)
{
    (void)args; (void)base;
    esp_mqtt_event_handle_t event = data;
    if (event_id == MQTT_EVENT_CONNECTED) {
        s_connected = true;
        char topic[176];
        snprintf(topic, sizeof(topic), "%s/commands", s_topic_prefix);
        esp_mqtt_client_subscribe(s_client, topic, 1);
        publish_online(true);
        publish_reported();
        ESP_LOGI(TAG, "Connected to MQTT cloud as %s", CONFIG_SMART_POT_DEVICE_ID);
    } else if (event_id == MQTT_EVENT_DISCONNECTED) {
        s_connected = false;
    } else if (event_id == MQTT_EVENT_DATA) {
        if (event->current_data_offset == 0) s_command_len = 0;
        if (s_command_len + event->data_len < sizeof(s_command_buffer)) {
            memcpy(s_command_buffer + s_command_len, event->data, event->data_len);
            s_command_len += event->data_len;
        }
        if (event->current_data_offset + event->data_len == event->total_data_len) {
            s_command_buffer[s_command_len] = '\0';
            handle_command(s_command_buffer);
        }
    }
}

void app_cloud_start(void)
{
    if (!CONFIG_SMART_POT_CLOUD_ENABLE) { ESP_LOGI(TAG, "MQTT cloud disabled"); return; }
    snprintf(s_topic_prefix, sizeof(s_topic_prefix), "smartpot/v1/devices/%s", CONFIG_SMART_POT_DEVICE_ID);
    snprintf(s_lwt_topic, sizeof(s_lwt_topic), "%s/online", s_topic_prefix);
    static char lwt_payload[192];
    snprintf(lwt_payload, sizeof(lwt_payload), "{\"deviceId\":\"%s\",\"online\":false,\"changedAt\":\"1970-01-01T00:00:00Z\"}", CONFIG_SMART_POT_DEVICE_ID);
    esp_mqtt_client_config_t config = {
        .broker.address.uri = CONFIG_SMART_POT_MQTT_URI,
        .credentials.client_id = CONFIG_SMART_POT_DEVICE_ID,
        .credentials.username = CONFIG_SMART_POT_MQTT_USERNAME,
        .credentials.authentication.password = CONFIG_SMART_POT_MQTT_PASSWORD,
        .session.last_will.topic = s_lwt_topic,
        .session.last_will.msg = lwt_payload,
        .session.last_will.qos = 1,
        .session.last_will.retain = true,
        .network.reconnect_timeout_ms = 5000,
    };
    s_client = esp_mqtt_client_init(&config);
    esp_mqtt_client_register_event(s_client, ESP_EVENT_ANY_ID, mqtt_event, NULL);
    esp_mqtt_client_start(s_client);
}

void app_cloud_update_plant_state(const app_plant_state_t *state)
{
    if (!s_connected || state == NULL) return;
    char timestamp[32]; timestamp_now(timestamp, sizeof(timestamp));
    wifi_ap_record_t ap = {0};
    int rssi = esp_wifi_sta_get_ap_info(&ap) == ESP_OK ? ap.rssi : 0;
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "schemaVersion", 1);
    cJSON_AddStringToObject(root, "deviceId", CONFIG_SMART_POT_DEVICE_ID);
    cJSON_AddNumberToObject(root, "sequence", ++s_sequence);
    cJSON_AddStringToObject(root, "recordedAt", timestamp);
    cJSON_AddNumberToObject(root, "soilPercent", state->soil_percent);
    cJSON_AddNumberToObject(root, "soilAdcRaw", state->soil_adc_raw);
    cJSON_AddBoolToObject(root, "soilDigitalDry", state->soil_digital_dry);
    cJSON_AddNumberToObject(root, "lightLux", state->light_lux);
    cJSON_AddNumberToObject(root, "lightPercent", state->light_percent);
    cJSON_AddNumberToObject(root, "touchCount", state->touch_count);
    cJSON_AddBoolToObject(root, "touchActive", state->touch_active);
#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
    cJSON *motion = cJSON_CreateObject();
    cJSON_AddNumberToObject(motion, "rollDeg", s_motion.roll_deg);
    cJSON_AddNumberToObject(motion, "pitchDeg", s_motion.pitch_deg);
    cJSON_AddNumberToObject(motion, "tiltDeltaDeg", s_motion.tilt_delta_deg);
    cJSON_AddBoolToObject(motion, "moving", s_motion.moving);
    cJSON_AddNumberToObject(motion, "tiltLevel", s_motion.tilt_level);
    cJSON_AddBoolToObject(motion, "fallen", s_motion.tilt_level > 0);
    cJSON_AddItemToObject(root, "motion", motion);
#endif
    cJSON_AddStringToObject(root, "mood", mood_name(state->mood));
    cJSON_AddNumberToObject(root, "wifiRssi", rssi);
    cJSON_AddNumberToObject(root, "uptimeSeconds", esp_timer_get_time() / 1000000ULL);
    publish_json("telemetry", root, false);
    cJSON_Delete(root);
    if (state->touch_count != s_last_touch_count) {
        if (s_last_touch_count != 0) publish_event("PHYSICAL_TOUCH");
        s_last_touch_count = state->touch_count;
    }
}

#ifdef CONFIG_SMART_POT_MPU6050_ENABLE
void app_cloud_update_motion(app_motion_event_t event, const app_motion_state_t *state)
{
    if (state != NULL) s_motion = *state;
    static const char *types[] = {
        "PHYSICAL_TAP",
        "SHAKE",
        "MOVE_STARTED",
        "MOVE_STOPPED",
        "FALLEN",
        "FALL_RECOVERED",
    };
    if (event >= APP_MOTION_EVENT_TAP && event <= APP_MOTION_EVENT_FALL_RECOVERED) publish_event(types[event]);
}
#endif
