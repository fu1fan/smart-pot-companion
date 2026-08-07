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

# esp_hosted 1.4.x asserts and reboots the P4 when an SDIO receive allocation
# briefly fails under TLS/ASR traffic. Newer esp_hosted releases handle mempool
# allocation failures gracefully, but this board's coprocessor firmware is tied
# to the 1.4 protocol. Apply the equivalent guards to the generated component.
set(SMART_POT_HOSTED_SDIO
        "${CMAKE_CURRENT_LIST_DIR}/managed_components/espressif__esp_hosted/host/drivers/transport/sdio/sdio_drv.c")
if(EXISTS "${SMART_POT_HOSTED_SDIO}")
    file(READ "${SMART_POT_HOSTED_SDIO}" SMART_POT_HOSTED_SDIO_SOURCE)
    string(REPLACE "assert(*buf);" [=[if (!*buf) {
			ESP_LOGE(TAG, "No memory for %lu-byte SDIO stream buffer; dropping transfer",
				(unsigned long)len);
			return NULL;
		}]=] SMART_POT_HOSTED_SDIO_PATCHED "${SMART_POT_HOSTED_SDIO_SOURCE}")
    string(REPLACE "assert(pkt_rxbuff);" [=[if (!pkt_rxbuff) {
			ESP_LOGE(TAG, "No memory for SDIO RX packet; dropping remaining stream");
			return ESP_ERR_NO_MEM;
		}]=] SMART_POT_HOSTED_SDIO_PATCHED "${SMART_POT_HOSTED_SDIO_PATCHED}")
    string(REPLACE "assert(rxbuff);" [=[if (!rxbuff) {
			SDIO_DRV_UNLOCK();
			vTaskDelay(pdMS_TO_TICKS(1));
			continue;
		}]=] SMART_POT_HOSTED_SDIO_PATCHED "${SMART_POT_HOSTED_SDIO_PATCHED}")
    string(REPLACE "assert(copy_payload);" [=[if (!copy_payload) {
					ESP_LOGE(TAG, "No memory for %u-byte Wi-Fi RX payload; dropping packet",
						(unsigned int)buf_handle->payload_len);
					H_FREE_PTR_WITH_FUNC(buf_handle->free_buf_handle,
						buf_handle->priv_buffer_handle);
					continue;
				}]=] SMART_POT_HOSTED_SDIO_PATCHED "${SMART_POT_HOSTED_SDIO_PATCHED}")
    if(NOT SMART_POT_HOSTED_SDIO_SOURCE STREQUAL SMART_POT_HOSTED_SDIO_PATCHED)
        file(WRITE "${SMART_POT_HOSTED_SDIO}" "${SMART_POT_HOSTED_SDIO_PATCHED}")
        message(STATUS "Applied SmartPot no-memory guards to esp_hosted SDIO")
    endif()
endif()
