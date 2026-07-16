#include "app_wifi.h"

#include <string.h>
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"

#include "app_ui.h"

#ifndef CONFIG_SMART_POT_WIFI_ENABLE
#define CONFIG_SMART_POT_WIFI_ENABLE 0
#endif

#ifndef CONFIG_SMART_POT_WIFI_SSID
#define CONFIG_SMART_POT_WIFI_SSID ""
#endif

#ifndef CONFIG_SMART_POT_WIFI_PASSWORD
#define CONFIG_SMART_POT_WIFI_PASSWORD ""
#endif

#ifndef CONFIG_SMART_POT_WIFI_MAXIMUM_RETRY
#define CONFIG_SMART_POT_WIFI_MAXIMUM_RETRY 5
#endif

#define WIFI_CONNECTED_BIT BIT0
#define WIFI_FAIL_BIT BIT1

static const char *TAG = "app_wifi";
static EventGroupHandle_t s_wifi_event_group;
static int s_retry_num;
static bool s_started;
static volatile bool s_connected;

static void wifi_event_handler(void *arg, esp_event_base_t event_base, int32_t event_id, void *event_data)
{
    (void)arg;

    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        app_ui_set_network_status("Wi-Fi: connecting");
        esp_wifi_connect();
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        wifi_event_sta_disconnected_t *disc = (wifi_event_sta_disconnected_t *)event_data;
        s_connected = false;
        s_retry_num++;
        if (s_wifi_event_group != NULL) {
            xEventGroupClearBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
        }
        ESP_LOGW(TAG, "Wi-Fi disconnected, reason=%d retry=%d",
                 disc != NULL ? disc->reason : -1, s_retry_num);
        app_ui_set_network_status("Wi-Fi: retrying");
        esp_wifi_connect();
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)event_data;
        s_retry_num = 0;
        s_connected = true;
        ESP_LOGI(TAG, "Connected to Wi-Fi SSID:%s ip:" IPSTR,
                 CONFIG_SMART_POT_WIFI_SSID, IP2STR(&event->ip_info.ip));
        app_ui_set_network_status("Wi-Fi: connected");
        xEventGroupSetBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
    }
}

static void wifi_task(void *arg)
{
    (void)arg;

    if (!CONFIG_SMART_POT_WIFI_ENABLE || strlen(CONFIG_SMART_POT_WIFI_SSID) == 0) {
        ESP_LOGW(TAG, "Wi-Fi disabled or SSID empty");
        app_ui_set_network_status("Wi-Fi: not configured");
        vTaskDelete(NULL);
        return;
    }

    s_wifi_event_group = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event_handler, NULL, NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event_handler, NULL, NULL));

    wifi_config_t wifi_config = { 0 };
    strlcpy((char *)wifi_config.sta.ssid, CONFIG_SMART_POT_WIFI_SSID, sizeof(wifi_config.sta.ssid));
    strlcpy((char *)wifi_config.sta.password, CONFIG_SMART_POT_WIFI_PASSWORD, sizeof(wifi_config.sta.password));
    wifi_config.sta.scan_method = WIFI_ALL_CHANNEL_SCAN;
    wifi_config.sta.threshold.authmode = WIFI_AUTH_OPEN;
    wifi_config.sta.sae_pwe_h2e = WPA3_SAE_PWE_BOTH;
    wifi_config.sta.failure_retry_cnt = 3;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_wifi_start());

    app_ui_set_network_status("Wi-Fi: connecting");
    while (true) {
        EventBits_t bits = xEventGroupWaitBits(
            s_wifi_event_group,
            WIFI_CONNECTED_BIT,
            pdFALSE,
            pdFALSE,
            pdMS_TO_TICKS(30000));

        if ((bits & WIFI_CONNECTED_BIT) == 0 && !s_connected) {
            ESP_LOGW(TAG, "Wi-Fi still not connected after 30s, retry=%d", s_retry_num);
            app_ui_set_network_status("Wi-Fi: retrying");
            esp_wifi_connect();
        } else {
            vTaskDelay(pdMS_TO_TICKS(1000));
        }
    }
}

void app_wifi_start(void)
{
    if (s_started) {
        return;
    }
    s_started = true;
    xTaskCreate(wifi_task, "smart_pot_wifi", 6144, NULL, 5, NULL);
}

bool app_wifi_is_connected(void)
{
    return s_connected;
}
