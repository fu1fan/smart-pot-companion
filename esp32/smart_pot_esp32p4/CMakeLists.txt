# The following lines of boilerplate have to be in your project's
# CMakeLists in this exact order for cmake to work correctly
cmake_minimum_required(VERSION 3.5)

set(SMART_POT_SDKCONFIG_DEFAULTS
        "${CMAKE_CURRENT_LIST_DIR}/sdkconfig.defaults"
)
set(SMART_POT_SECRETS_CONFIG "${CMAKE_CURRENT_LIST_DIR}/sdkconfig.secrets")
if(EXISTS "${SMART_POT_SECRETS_CONFIG}")
    list(APPEND SMART_POT_SDKCONFIG_DEFAULTS "${SMART_POT_SECRETS_CONFIG}")
endif()
set(SDKCONFIG_DEFAULTS "${SMART_POT_SDKCONFIG_DEFAULTS}")

include($ENV{IDF_PATH}/tools/cmake/project.cmake)

add_compile_options(-Wno-format)
project(smart_pot_esp32p4)

# esp_codec_dev 1.5.11 reconfigures I2S by disabling both channels even when
# they are already stopped. Guard those calls so normal mic/speaker handoffs do
# not emit ESP_ERR_INVALID_STATE on every open.
set(SMART_POT_CODEC_I2S
        "${CMAKE_CURRENT_LIST_DIR}/managed_components/espressif__esp_codec_dev/platform/audio_codec_data_i2s.c")
if(EXISTS "${SMART_POT_CODEC_I2S}")
    file(READ "${SMART_POT_CODEC_I2S}" SMART_POT_CODEC_I2S_SOURCE)
    string(REPLACE
            "if (dev_type & ESP_CODEC_DEV_TYPE_OUT) {\n        _i2s_drv_enable(i2s_data, true, false);"
            "if ((dev_type & ESP_CODEC_DEV_TYPE_OUT) && i2s_data->out_enable) {\n        _i2s_drv_enable(i2s_data, true, false);"
            SMART_POT_CODEC_I2S_PATCHED "${SMART_POT_CODEC_I2S_SOURCE}")
    string(REPLACE
            "if (dev_type & ESP_CODEC_DEV_TYPE_IN) {\n        _i2s_drv_enable(i2s_data, false, false);"
            "if ((dev_type & ESP_CODEC_DEV_TYPE_IN) && i2s_data->in_enable) {\n        _i2s_drv_enable(i2s_data, false, false);"
            SMART_POT_CODEC_I2S_PATCHED "${SMART_POT_CODEC_I2S_PATCHED}")
    if(NOT SMART_POT_CODEC_I2S_SOURCE STREQUAL SMART_POT_CODEC_I2S_PATCHED)
        file(WRITE "${SMART_POT_CODEC_I2S}" "${SMART_POT_CODEC_I2S_PATCHED}")
        message(STATUS "Applied SmartPot I2S state guard to esp_codec_dev")
    endif()
endif()

spiffs_create_partition_image(storage offline_audio FLASH_IN_PROJECT)
