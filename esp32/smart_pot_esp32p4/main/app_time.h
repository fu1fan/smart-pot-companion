#pragma once

#include <stdbool.h>
#include <time.h>

void app_time_start(void);
bool app_time_get_local(struct tm *timeinfo);
