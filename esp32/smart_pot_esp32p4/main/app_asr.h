#pragma once

#include <stddef.h>
#include <stdint.h>
#include "esp_codec_dev.h"

char *app_asr_transcribe_from_mic(esp_codec_dev_handle_t mic);
char *app_asr_transcribe_from_mic_with_prefix(esp_codec_dev_handle_t mic,
                                              const int16_t *prefix_samples,
                                              size_t prefix_sample_count);
