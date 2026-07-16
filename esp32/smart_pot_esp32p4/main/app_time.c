#include "app_time.h"

#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include "esp_log.h"
#include "esp_netif_sntp.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "app_wifi.h"

static const char *TAG = "app_time";
static volatile bool s_time_synced;
static bool s_started;

static void time_sync_cb(struct timeval *tv)
{
    (void)tv;
    s_time_synced = true;
    ESP_LOGI(TAG, "SNTP time synchronized");
}

static void time_task(void *arg)
{
    (void)arg;

    setenv("TZ", "CST-8", 1);
    tzset();

    while (!app_wifi_is_connected()) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    esp_sntp_config_t config = ESP_NETIF_SNTP_DEFAULT_CONFIG("ntp.aliyun.com");
    config.sync_cb = time_sync_cb;
    esp_err_t err = esp_netif_sntp_init(&config);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "SNTP init failed: %s", esp_err_to_name(err));
        vTaskDelete(NULL);
        return;
    }

    err = esp_netif_sntp_sync_wait(pdMS_TO_TICKS(15000));
    if (err == ESP_OK) {
        s_time_synced = true;
    } else {
        ESP_LOGW(TAG, "SNTP sync wait timed out: %s", esp_err_to_name(err));
    }

    vTaskDelete(NULL);
}

void app_time_start(void)
{
    if (s_started) {
        return;
    }
    s_started = true;
    xTaskCreate(time_task, "smart_pot_time", 4096, NULL, 4, NULL);
}

bool app_time_get_local(struct tm *timeinfo)
{
    if (timeinfo == NULL || !s_time_synced) {
        return false;
    }

    time_t now = 0;
    time(&now);
    if (now < 1704067200) {
        return false;
    }
    localtime_r(&now, timeinfo);
    return true;
}
