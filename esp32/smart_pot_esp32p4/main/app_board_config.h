#pragma once

/*
 * Hardware wiring that must travel with source control.
 * Keep Wi-Fi/API secrets in local sdkconfig, but do not rely on ignored
 * sdkconfig for board-level GPIO assignments.
 */

#define APP_BOARD_LIGHT_STRIP_ENABLE 1
#define APP_BOARD_LIGHT_STRIP_GPIO 21
#define APP_BOARD_LIGHT_STRIP_ACTIVE_HIGH 1
#define APP_BOARD_LIGHT_STRIP_ON_PERCENT 25
#define APP_BOARD_LIGHT_STRIP_OFF_PERCENT 35
#define APP_BOARD_LIGHT_STRIP_ON_CONFIRM_MS 8000
#define APP_BOARD_LIGHT_STRIP_OFF_CONFIRM_MS 30000
#define APP_BOARD_LIGHT_STRIP_MIN_ON_MS 300000
#define APP_BOARD_LIGHT_STRIP_MIN_OFF_MS 10000

#define APP_BOARD_WAKENET_ENABLE 0
#define APP_BOARD_VOICE_WAKE_DIRECT_SAMPLES 1600
#define APP_BOARD_VOICE_TASK_STACK_BYTES 12288
