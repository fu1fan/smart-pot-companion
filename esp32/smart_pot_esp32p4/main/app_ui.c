#include "app_ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "esp_log.h"
#include "lvgl.h"
#include "bsp/esp-bsp.h"

#include "app_tts.h"
#include "app_time.h"
#include "app_voice.h"

#define UI_SCREEN_W 800
#define UI_SCREEN_H 480
#define SCHEDULE_MAX_ITEMS 4
#define SCHEDULE_ID_LEN 48
#define SCHEDULE_ITEM_LEN 96
#define SCHEDULE_DEADLINE_LEN 64
#define APP_UI_SHOW_MOTION_DEBUG_PAGE 0

static const char *TAG = "app_ui";

LV_IMAGE_DECLARE(cat_planter_img);
LV_IMAGE_DECLARE(home_static_bg_img);
LV_IMAGE_DECLARE(schedule_static_bg_img);
LV_IMAGE_DECLARE(emoji_heart_img);
LV_IMAGE_DECLARE(emoji_smile_img);
LV_IMAGE_DECLARE(emoji_happy_img);
LV_IMAGE_DECLARE(emoji_thirsty_img);
LV_IMAGE_DECLARE(emoji_dark_img);
LV_IMAGE_DECLARE(emoji_weak_img);
LV_IMAGE_DECLARE(emoji_wave_img);
LV_IMAGE_DECLARE(emoji_star_img);
LV_IMAGE_DECLARE(emoji_flower_img);
LV_IMAGE_DECLARE(emoji_water_img);
LV_IMAGE_DECLARE(emoji_sun_img);
LV_IMAGE_DECLARE(emoji_sleep_img);

static void cat_face_to_front(void);
static void update_cat_art(app_mood_t mood);
static void update_schedule_page(void);
static void check_schedule_reminders(void);
static bool normalize_pending_schedule_times(void);
static void touch_event_cb(lv_event_t *event);
static void notify_schedule_event(const char *event_type, uint8_t index);

typedef enum {
    UI_PAGE_FACE = 0,
    UI_PAGE_MOTION,
    UI_PAGE_SCHEDULE,
    UI_PAGE_POMODORO,
    UI_PAGE_COUNT,
} ui_page_t;

typedef enum {
    POMODORO_IDLE = 0,
    POMODORO_FOCUS,
    POMODORO_REST,
} pomodoro_phase_t;

static lv_obj_t *s_face_page;
static lv_obj_t *s_motion_page;
static lv_obj_t *s_schedule_page;
static lv_obj_t *s_pomodoro_page;
static lv_obj_t *s_face_mood_label;
static lv_obj_t *s_home_time_label;
static lv_obj_t *s_home_date_label;
static lv_obj_t *s_pomodoro_time_label;
static lv_obj_t *s_pomodoro_status_label;
static lv_obj_t *s_soil_bar;
static lv_obj_t *s_light_bar;
static lv_obj_t *s_mood_bar;
static lv_obj_t *s_soil_label;
static lv_obj_t *s_light_label;
static lv_obj_t *s_light_unit_label;
static lv_obj_t *s_mood_label;
static lv_obj_t *s_network_icon;
static lv_obj_t *s_network_label;
static lv_obj_t *s_voice_label;
static lv_obj_t *s_dialog_label;
static lv_obj_t *s_emoji_overlay;
static lv_obj_t *s_emoji_stage;
static lv_obj_t *s_emoji_art;
static lv_obj_t *s_emoji_caption_label;
static lv_obj_t *s_remote_popup;
static lv_obj_t *s_remote_popup_label;
static lv_obj_t *s_mode_label_face;
static lv_obj_t *s_mode_label_schedule;
static lv_obj_t *s_motion_status_label;
static lv_obj_t *s_motion_roll_label;
static lv_obj_t *s_motion_pitch_label;
static lv_obj_t *s_motion_accel_label;
static lv_obj_t *s_motion_gyro_label;
static lv_obj_t *s_motion_mag_label;
static lv_obj_t *s_motion_state_label;
static lv_obj_t *s_motion_event_label;
static lv_obj_t *s_motion_reaction_label;
static lv_obj_t *s_schedule_empty_label;
static lv_obj_t *s_memory_days_label;
static lv_obj_t *s_schedule_item_labels[SCHEDULE_MAX_ITEMS];
static lv_obj_t *s_schedule_deadline_labels[SCHEDULE_MAX_ITEMS];
static lv_obj_t *s_cat_area;
static lv_obj_t *s_cat_face_layer;
static lv_obj_t *s_schedule_face_eye_left;
static lv_obj_t *s_schedule_face_eye_right;
static lv_obj_t *s_schedule_face_blink_left;
static lv_obj_t *s_schedule_face_blink_right;
static lv_obj_t *s_schedule_face_mouth;
static lv_obj_t *s_cat_head;
static lv_obj_t *s_cat_eye_left;
static lv_obj_t *s_cat_eye_right;
static lv_obj_t *s_cat_highlight_left;
static lv_obj_t *s_cat_highlight_right;
static lv_obj_t *s_cat_happy_eye_left;
static lv_obj_t *s_cat_happy_eye_right;
static lv_obj_t *s_cat_blink_eye_left;
static lv_obj_t *s_cat_blink_eye_right;
static lv_obj_t *s_cat_swirl_eye_left;
static lv_obj_t *s_cat_swirl_eye_right;
static lv_obj_t *s_cat_brow_left;
static lv_obj_t *s_cat_brow_right;
static lv_obj_t *s_cat_mouth;
static lv_obj_t *s_cat_drop;
static lv_obj_t *s_cat_drop_left;
static lv_obj_t *s_cat_zzz_label;
static lv_obj_t *s_cat_heart;
static lv_timer_t *s_touch_blink_timer;
static lv_timer_t *s_remote_content_timer;
static lv_timer_t *s_heart_hide_timer;
static lv_timer_t *s_motion_reaction_timer;
static lv_timer_t *s_idle_blink_timer;
static lv_timer_t *s_schedule_blink_timer;
static lv_timer_t *s_schedule_blink_restore_timer;
static lv_timer_t *s_pomodoro_timer;
static lv_timer_t *s_schedule_cleanup_timer;
static lv_timer_t *s_emoji_overlay_timer;
static lv_timer_t *s_emoji_overlay_finish_timer;
static lv_timer_t *s_remote_popup_finish_timer;
static int32_t s_pomodoro_remaining_sec;
static pomodoro_phase_t s_pomodoro_phase = POMODORO_IDLE;
static char s_schedule_items[SCHEDULE_MAX_ITEMS][SCHEDULE_ITEM_LEN];
static char s_schedule_ids[SCHEDULE_MAX_ITEMS][SCHEDULE_ID_LEN];
static char s_schedule_deadlines[SCHEDULE_MAX_ITEMS][SCHEDULE_DEADLINE_LEN];
static time_t s_schedule_due_ts[SCHEDULE_MAX_ITEMS];
static bool s_schedule_completed[SCHEDULE_MAX_ITEMS];
static time_t s_schedule_completed_ts[SCHEDULE_MAX_ITEMS];
static bool s_schedule_reminded[SCHEDULE_MAX_ITEMS];
static uint8_t s_schedule_count;
static uint32_t s_growth_days = 1;
static lv_font_t *s_schedule_font;
static lv_font_t *s_schedule_time_font;
static lv_font_t *s_schedule_title_font;
static lv_font_t *s_todo_title_font;
static lv_font_t *s_todo_header_font;
static app_mood_t s_current_mood = APP_MOOD_HAPPY;
static bool s_touch_blink_active;
static bool s_motion_reaction_active;
static app_ui_motion_reaction_t s_motion_reaction;
static ui_page_t s_current_page = UI_PAGE_FACE;
static app_ui_schedule_event_cb_t s_schedule_event_cb;

static lv_point_precise_t s_cat_happy_eye_pts[] = { { 0, 9 }, { 16, 2 }, { 32, 9 } };
static lv_point_precise_t s_cat_blink_eye_pts[] = { { 0, 5 }, { 32, 5 } };

static const char *weekday_text(int weekday)
{
    static const char *const names[] = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
    if (weekday < 0 || weekday > 6) {
        return "--";
    }
    return names[weekday];
}

static void update_home_time(void)
{
    if (s_home_time_label == NULL || s_home_date_label == NULL) {
        return;
    }

    struct tm timeinfo = { 0 };
    if (!app_time_get_local(&timeinfo)) {
        lv_label_set_text(s_home_time_label, "--:--  Syncing time");
        lv_label_set_text(s_home_date_label, "");
        return;
    }

    char time_text[48];
    snprintf(time_text, sizeof(time_text), "%02d:%02d  %04d-%02d-%02d  %s",
             timeinfo.tm_hour, timeinfo.tm_min,
             timeinfo.tm_year + 1900, timeinfo.tm_mon + 1, timeinfo.tm_mday,
             weekday_text(timeinfo.tm_wday));
    lv_label_set_text(s_home_time_label, time_text);
    lv_label_set_text(s_home_date_label, "");
}

static void time_timer_cb(lv_timer_t *timer)
{
    (void)timer;
    update_home_time();
    if (normalize_pending_schedule_times()) {
        update_schedule_page();
    }
    check_schedule_reminders();
}

static void copy_or_default(char *dst, size_t dst_size, const char *src, const char *fallback)
{
    if (dst == NULL || dst_size == 0) {
        return;
    }

    if (src == NULL || src[0] == '\0') {
        src = fallback;
    }

    size_t src_offset = 0;
    size_t dst_offset = 0;
    while (src[src_offset] != '\0') {
        unsigned char lead = (unsigned char)src[src_offset];
        size_t sequence_size = lead < 0x80 ? 1 :
                               (lead & 0xe0) == 0xc0 ? 2 :
                               (lead & 0xf0) == 0xe0 ? 3 :
                               (lead & 0xf8) == 0xf0 ? 4 : 1;
        bool valid_sequence = sequence_size > 1;
        if (sequence_size == 1) {
            valid_sequence = lead < 0x80;
        } else {
            for (size_t i = 1; i < sequence_size; i++) {
                unsigned char continuation = (unsigned char)src[src_offset + i];
                if (continuation == '\0' || (continuation & 0xc0) != 0x80) {
                    valid_sequence = false;
                    break;
                }
            }
        }
        if (!valid_sequence) {
            if (dst_offset + 1 >= dst_size) {
                break;
            }
            dst[dst_offset++] = '?';
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

static const lv_font_t *schedule_font(void)
{
    return s_schedule_font != NULL ? s_schedule_font : &lv_font_source_han_sans_sc_16_cjk;
}

static const lv_font_t *schedule_time_font(void)
{
    return s_schedule_time_font != NULL ? s_schedule_time_font : schedule_font();
}

static const lv_font_t *pomodoro_font(void)
{
    return &lv_font_montserrat_48;
}

static int chinese_digit_value(const char *text, size_t *bytes)
{
    static const struct {
        const char *word;
        int value;
    } digits[] = {
        { "零", 0 }, { "〇", 0 }, { "一", 1 }, { "二", 2 }, { "两", 2 },
        { "三", 3 }, { "四", 4 }, { "五", 5 }, { "六", 6 }, { "七", 7 },
        { "八", 8 }, { "九", 9 },
    };

    if (text == NULL || bytes == NULL) {
        return -1;
    }
    if (text[0] >= '0' && text[0] <= '9') {
        int value = 0;
        size_t offset = 0;
        while (text[offset] >= '0' && text[offset] <= '9') {
            value = value * 10 + text[offset] - '0';
            offset++;
        }
        *bytes = offset;
        return value;
    }
    if (strncmp(text, "十", strlen("十")) == 0) {
        *bytes = strlen("十");
        return 10;
    }
    for (size_t i = 0; i < sizeof(digits) / sizeof(digits[0]); i++) {
        size_t len = strlen(digits[i].word);
        if (strncmp(text, digits[i].word, len) == 0) {
            *bytes = len;
            return digits[i].value;
        }
    }
    return -1;
}

static bool parse_chinese_number_at(const char *text, int *value, size_t *bytes)
{
    if (text == NULL || value == NULL || bytes == NULL) {
        return false;
    }

    size_t first_bytes = 0;
    int first = chinese_digit_value(text, &first_bytes);
    if (first < 0) {
        return false;
    }

    const char *after_first = text + first_bytes;
    if (strncmp(after_first, "十", strlen("十")) == 0 && first > 0 && first < 10) {
        size_t ten_bytes = strlen("十");
        size_t last_bytes = 0;
        int last = chinese_digit_value(after_first + ten_bytes, &last_bytes);
        *value = first * 10 + (last >= 0 ? last : 0);
        *bytes = first_bytes + ten_bytes + (last >= 0 ? last_bytes : 0);
        return true;
    }
    if (first == 10) {
        size_t last_bytes = 0;
        int last = chinese_digit_value(after_first, &last_bytes);
        *value = 10 + (last >= 0 ? last : 0);
        *bytes = first_bytes + (last >= 0 ? last_bytes : 0);
        return true;
    }

    *value = first;
    *bytes = first_bytes;
    return true;
}

static bool parse_number_before_marker(const char *text, const char *marker, int *value)
{
    char *pos = strstr(text, marker);
    if (pos == NULL) {
        return false;
    }

    for (const char *scan = text; scan < pos; ) {
        int parsed = 0;
        size_t parsed_bytes = 0;
        if (parse_chinese_number_at(scan, &parsed, &parsed_bytes) && scan + parsed_bytes == pos) {
            *value = parsed;
            return true;
        }
        unsigned char lead = (unsigned char)*scan;
        scan += lead < 0x80 ? 1 :
                (lead & 0xe0) == 0xc0 ? 2 :
                (lead & 0xf0) == 0xe0 ? 3 :
                (lead & 0xf8) == 0xf0 ? 4 : 1;
    }
    return false;
}

static bool schedule_parse_due_time(const char *deadline, time_t *due_ts, char *display, size_t display_size)
{
    if (deadline == NULL || deadline[0] == '\0' || due_ts == NULL || display == NULL || display_size == 0) {
        return false;
    }

    struct tm now_tm = { 0 };
    if (!app_time_get_local(&now_tm)) {
        return false;
    }

    /* Smart schedule extraction emits an absolute local timestamp.  Parse and
     * validate it before the natural-language fallback below. */
    int iso_year = 0, iso_month = 0, iso_day = 0, iso_hour = 0, iso_minute = 0, iso_consumed = 0;
    if (sscanf(deadline, "%d-%d-%d %d:%d%n", &iso_year, &iso_month, &iso_day,
               &iso_hour, &iso_minute, &iso_consumed) == 5 && deadline[iso_consumed] == '\0') {
        if (iso_year < 2024 || iso_year > 2099 || iso_month < 1 || iso_month > 12 ||
            iso_day < 1 || iso_day > 31 || iso_hour < 0 || iso_hour > 23 ||
            iso_minute < 0 || iso_minute > 59) {
            return false;
        }
        struct tm iso_tm = {
            .tm_year = iso_year - 1900,
            .tm_mon = iso_month - 1,
            .tm_mday = iso_day,
            .tm_hour = iso_hour,
            .tm_min = iso_minute,
            .tm_sec = 0,
            .tm_isdst = -1,
        };
        time_t iso_due = mktime(&iso_tm);
        struct tm normalized_iso = { 0 };
        if (iso_due <= 0) return false;
        localtime_r(&iso_due, &normalized_iso);
        if (normalized_iso.tm_year != iso_year - 1900 || normalized_iso.tm_mon != iso_month - 1 ||
            normalized_iso.tm_mday != iso_day || normalized_iso.tm_hour != iso_hour ||
            normalized_iso.tm_min != iso_minute) {
            return false;
        }
        snprintf(display, display_size, "%02d-%02d/%02d:%02d",
                 iso_month, iso_day, iso_hour, iso_minute);
        *due_ts = iso_due;
        return true;
    }

    struct tm due_tm = now_tm;
    due_tm.tm_sec = 0;
    due_tm.tm_min = 0;
    due_tm.tm_hour = 9;

    const bool has_explicit_day = strstr(deadline, "今天") != NULL ||
                                  strstr(deadline, "今晚") != NULL ||
                                  strstr(deadline, "今早") != NULL ||
                                  strstr(deadline, "今下午") != NULL ||
                                  strstr(deadline, "今晚上") != NULL ||
                                  strstr(deadline, "明天") != NULL ||
                                  strstr(deadline, "后天") != NULL ||
                                  strstr(deadline, "大后天") != NULL ||
                                  strstr(deadline, "周") != NULL ||
                                  strstr(deadline, "星期") != NULL ||
                                  strstr(deadline, "月") != NULL ||
                                  strstr(deadline, "号") != NULL ||
                                  strstr(deadline, "日") != NULL;

    if (strstr(deadline, "大后天") != NULL) {
        due_tm.tm_mday += 3;
    } else if (strstr(deadline, "后天") != NULL) {
        due_tm.tm_mday += 2;
    } else if (strstr(deadline, "明天") != NULL) {
        due_tm.tm_mday += 1;
    }

    int month = 0;
    int day = 0;
    if (parse_number_before_marker(deadline, "月", &month) && parse_number_before_marker(deadline, "号", &day)) {
        due_tm.tm_mon = month - 1;
        due_tm.tm_mday = day;
    } else if (parse_number_before_marker(deadline, "月", &month) && parse_number_before_marker(deadline, "日", &day)) {
        due_tm.tm_mon = month - 1;
        due_tm.tm_mday = day;
    } else if (parse_number_before_marker(deadline, "号", &day) || parse_number_before_marker(deadline, "日", &day)) {
        due_tm.tm_mday = day;
    }

    static const char *const week_names[] = { "周日", "周一", "周二", "周三", "周四", "周五", "周六" };
    static const char *const weekday_names[] = { "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六" };
    for (int i = 0; i < 7; i++) {
        if (strstr(deadline, week_names[i]) != NULL || strstr(deadline, weekday_names[i]) != NULL ||
            (i == 0 && (strstr(deadline, "周天") != NULL || strstr(deadline, "星期天") != NULL))) {
            int delta = (i - now_tm.tm_wday + 7) % 7;
            if (delta == 0 || strstr(deadline, "下周") != NULL) {
                delta += 7;
            }
            due_tm.tm_mday += delta;
            break;
        }
    }

    int hour = -1;
    int minute = 0;
    if (parse_number_before_marker(deadline, "点", &hour)) {
        if (strstr(deadline, "点半") != NULL) {
            minute = 30;
        } else if (strstr(deadline, "点一刻") != NULL) {
            minute = 15;
        } else if (strstr(deadline, "点三刻") != NULL) {
            minute = 45;
        } else {
            int parsed_minute = 0;
            if (parse_number_before_marker(deadline, "分", &parsed_minute)) {
                minute = parsed_minute;
            }
        }
    }
    if (hour < 0) {
        const char *colon = strchr(deadline, ':');
        if (colon == NULL) {
            colon = strstr(deadline, "：");
        }
        if (colon != NULL) {
            const char *start = colon;
            while (start > deadline && start[-1] >= '0' && start[-1] <= '9') {
                start--;
            }
            char *hour_end = NULL;
            int parsed_hour = (int)strtol(start, &hour_end, 10);
            const char *minute_start = colon + (colon[0] == ':' ? 1 : strlen("："));
            char *minute_end = NULL;
            int parsed_minute = (int)strtol(minute_start, &minute_end, 10);
            if (hour_end == colon && minute_end > minute_start) {
                hour = parsed_hour;
                minute = parsed_minute;
            }
        }
    }
    if (hour < 0 && parse_number_before_marker(deadline, "时", &hour)) {
        int parsed_minute = 0;
        if (parse_number_before_marker(deadline, "分", &parsed_minute)) {
            minute = parsed_minute;
        }
    }
    if (hour < 0) {
        if (strstr(deadline, "今晚") != NULL || strstr(deadline, "晚上") != NULL) {
            hour = 20;
        } else if (strstr(deadline, "傍晚") != NULL) {
            hour = 18;
        } else if (strstr(deadline, "中午") != NULL) {
            hour = 12;
        } else if (strstr(deadline, "早上") != NULL || strstr(deadline, "上午") != NULL ||
                   strstr(deadline, "清晨") != NULL) {
            hour = 9;
        } else if (strstr(deadline, "今天") != NULL || strstr(deadline, "明天") != NULL ||
                   strstr(deadline, "后天") != NULL || strstr(deadline, "大后天") != NULL ||
                   strstr(deadline, "周") != NULL || strstr(deadline, "星期") != NULL ||
                   strstr(deadline, "号") != NULL || strstr(deadline, "日") != NULL) {
            hour = 9;
        }
    }
    if (hour < 0) {
        return false;
    }
    if ((strstr(deadline, "下午") != NULL || strstr(deadline, "晚上") != NULL ||
         strstr(deadline, "傍晚") != NULL) && hour < 12) {
        hour += 12;
    } else if (strstr(deadline, "中午") != NULL && hour < 11) {
        hour += 12;
    } else if (strstr(deadline, "凌晨") != NULL && hour == 12) {
        hour = 0;
    }
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
        return false;
    }

    due_tm.tm_hour = hour;
    due_tm.tm_min = minute;
    time_t due = mktime(&due_tm);
    if (due <= 0) {
        return false;
    }
    time_t now = mktime(&now_tm);
    if (!has_explicit_day && now > 0 && due <= now) {
        due_tm.tm_mday += 1;
        due = mktime(&due_tm);
        if (due <= 0) {
            return false;
        }
    }

    struct tm normalized = { 0 };
    localtime_r(&due, &normalized);
    snprintf(display, display_size, "%02d-%02d/%02d:%02d",
             normalized.tm_mon + 1, normalized.tm_mday, normalized.tm_hour, normalized.tm_min);
    *due_ts = due;
    return true;
}

static bool normalize_pending_schedule_times(void)
{
    bool changed = false;
    for (uint8_t i = 0; i < s_schedule_count; i++) {
        if (s_schedule_due_ts[i] > 0 || s_schedule_deadlines[i][0] == '\0') {
            continue;
        }

        char display[SCHEDULE_DEADLINE_LEN] = "";
        time_t due = 0;
        if (!schedule_parse_due_time(s_schedule_deadlines[i], &due, display, sizeof(display))) {
            continue;
        }

        copy_or_default(s_schedule_deadlines[i], sizeof(s_schedule_deadlines[i]), display, "");
        s_schedule_due_ts[i] = due;
        s_schedule_reminded[i] = false;
        changed = true;
        ESP_LOGI(TAG, "Schedule time normalized: row=%u display=%s due=%lld",
                 (unsigned int)i, s_schedule_deadlines[i], (long long)due);
    }
    return changed;
}

static void check_schedule_reminders(void)
{
    time_t now = 0;
    time(&now);
    if (now < 1704067200) {
        return;
    }

    for (uint8_t i = 0; i < s_schedule_count; i++) {
        if (s_schedule_completed[i] || s_schedule_reminded[i] || s_schedule_due_ts[i] <= 0) {
            continue;
        }
        int64_t seconds_left = (int64_t)s_schedule_due_ts[i] - (int64_t)now;
        if (seconds_left <= 0) {
            char text[160];
            snprintf(text, sizeof(text), "日程提醒，%s。", s_schedule_items[i]);
            if (app_tts_speak_text_no_followup(text)) {
                s_schedule_reminded[i] = true;
            }
        }
    }
}

static void remove_schedule_at(uint8_t index)
{
    if (index >= s_schedule_count) {
        return;
    }
    if (index + 1 < s_schedule_count) {
        memmove(s_schedule_ids[index], s_schedule_ids[index + 1],
                sizeof(s_schedule_ids[0]) * (s_schedule_count - index - 1));
        memmove(s_schedule_items[index], s_schedule_items[index + 1],
                sizeof(s_schedule_items[0]) * (s_schedule_count - index - 1));
        memmove(s_schedule_deadlines[index], s_schedule_deadlines[index + 1],
                sizeof(s_schedule_deadlines[0]) * (s_schedule_count - index - 1));
        memmove(&s_schedule_due_ts[index], &s_schedule_due_ts[index + 1],
                sizeof(s_schedule_due_ts[0]) * (s_schedule_count - index - 1));
        memmove(&s_schedule_completed[index], &s_schedule_completed[index + 1],
                sizeof(s_schedule_completed[0]) * (s_schedule_count - index - 1));
        memmove(&s_schedule_completed_ts[index], &s_schedule_completed_ts[index + 1],
                sizeof(s_schedule_completed_ts[0]) * (s_schedule_count - index - 1));
        memmove(&s_schedule_reminded[index], &s_schedule_reminded[index + 1],
                sizeof(s_schedule_reminded[0]) * (s_schedule_count - index - 1));
    }
    s_schedule_count--;
    s_schedule_ids[s_schedule_count][0] = '\0';
    s_schedule_items[s_schedule_count][0] = '\0';
    s_schedule_deadlines[s_schedule_count][0] = '\0';
    s_schedule_due_ts[s_schedule_count] = 0;
    s_schedule_completed[s_schedule_count] = false;
    s_schedule_completed_ts[s_schedule_count] = 0;
    s_schedule_reminded[s_schedule_count] = false;
}

static bool cleanup_completed_schedules(void)
{
    bool changed = false;
    time_t now = time(NULL);
    for (int32_t i = (int32_t)s_schedule_count - 1; i >= 0; i--) {
        if (s_schedule_completed[i] && s_schedule_completed_ts[i] > 0 &&
            now >= s_schedule_completed_ts[i] + 120) {
            remove_schedule_at((uint8_t)i);
            changed = true;
        }
    }
    return changed;
}

static void schedule_cleanup_timer_cb(lv_timer_t *timer)
{
    if (cleanup_completed_schedules()) {
        update_schedule_page();
    }
    bool has_completed = false;
    for (uint8_t i = 0; i < s_schedule_count; i++) {
        has_completed = has_completed || s_schedule_completed[i];
    }
    if (!has_completed) {
        s_schedule_cleanup_timer = NULL;
        lv_timer_delete(timer);
    }
}

static void schedule_ensure_cleanup_timer(void)
{
    if (s_schedule_cleanup_timer == NULL) {
        s_schedule_cleanup_timer = lv_timer_create(schedule_cleanup_timer_cb, 1000, NULL);
    }
}

static void update_growth_days_label(void)
{
    if (s_memory_days_label == NULL) {
        return;
    }

    char text[24];
    snprintf(text, sizeof(text), "%lu 天", (unsigned long)(s_growth_days > 0 ? s_growth_days : 1));
    lv_label_set_text(s_memory_days_label, text);
}

static void update_schedule_page(void)
{
    if (s_schedule_empty_label == NULL) {
        return;
    }

    if (s_schedule_count == 0) {
        lv_obj_add_flag(s_schedule_empty_label, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(s_schedule_empty_label, LV_OBJ_FLAG_HIDDEN);
    }

    for (uint8_t i = 0; i < SCHEDULE_MAX_ITEMS; i++) {
        if (s_schedule_item_labels[i] == NULL || s_schedule_deadline_labels[i] == NULL) {
            continue;
        }

        if (i < s_schedule_count) {
            lv_checkbox_set_text(s_schedule_item_labels[i], s_schedule_items[i]);
            lv_label_set_text(s_schedule_deadline_labels[i], s_schedule_deadlines[i]);
            if (s_schedule_completed[i]) {
                lv_obj_add_state(s_schedule_item_labels[i], LV_STATE_CHECKED);
                lv_obj_set_style_text_decor(s_schedule_item_labels[i], LV_TEXT_DECOR_STRIKETHROUGH, LV_PART_MAIN);
                lv_obj_set_style_text_decor(s_schedule_deadline_labels[i], LV_TEXT_DECOR_STRIKETHROUGH, LV_PART_MAIN);
                lv_obj_set_style_text_opa(s_schedule_item_labels[i], LV_OPA_70, LV_PART_MAIN);
                lv_obj_set_style_text_opa(s_schedule_deadline_labels[i], LV_OPA_70, LV_PART_MAIN);
            } else {
                lv_obj_remove_state(s_schedule_item_labels[i], LV_STATE_CHECKED);
                lv_obj_set_style_text_decor(s_schedule_item_labels[i], LV_TEXT_DECOR_NONE, LV_PART_MAIN);
                lv_obj_set_style_text_decor(s_schedule_deadline_labels[i], LV_TEXT_DECOR_NONE, LV_PART_MAIN);
                lv_obj_set_style_text_opa(s_schedule_item_labels[i], LV_OPA_COVER, LV_PART_MAIN);
                lv_obj_set_style_text_opa(s_schedule_deadline_labels[i], LV_OPA_COVER, LV_PART_MAIN);
            }
        } else {
            lv_checkbox_set_text(s_schedule_item_labels[i], "");
            lv_obj_remove_state(s_schedule_item_labels[i], LV_STATE_CHECKED);
            lv_obj_set_style_text_decor(s_schedule_item_labels[i], LV_TEXT_DECOR_NONE, LV_PART_MAIN);
            lv_obj_set_style_text_opa(s_schedule_item_labels[i], LV_OPA_COVER, LV_PART_MAIN);
            lv_label_set_text(s_schedule_deadline_labels[i], "");
            lv_obj_set_style_text_decor(s_schedule_deadline_labels[i], LV_TEXT_DECOR_NONE, LV_PART_MAIN);
            lv_obj_set_style_text_opa(s_schedule_deadline_labels[i], LV_OPA_COVER, LV_PART_MAIN);
        }
    }
}

static void notify_schedule_event(const char *event_type, uint8_t index)
{
    if (s_schedule_event_cb == NULL || event_type == NULL || index >= s_schedule_count) {
        return;
    }

    s_schedule_event_cb(event_type,
                        s_schedule_ids[index],
                        s_schedule_items[index],
                        s_schedule_deadlines[index],
                        s_schedule_due_ts[index],
                        s_schedule_completed[index]);
}

static void schedule_complete_event_cb(lv_event_t *event)
{
    if (lv_event_get_code(event) != LV_EVENT_VALUE_CHANGED) {
        return;
    }

    lv_obj_t *checkbox = lv_event_get_target_obj(event);
    uint8_t index = (uint8_t)(uintptr_t)lv_event_get_user_data(event);
    if (!lv_obj_has_state(checkbox, LV_STATE_CHECKED)) {
        if (index < s_schedule_count && s_schedule_completed[index]) {
            s_schedule_completed[index] = false;
            s_schedule_completed_ts[index] = 0;
            ESP_LOGI(TAG, "Schedule completion canceled: row=%u", (unsigned int)index);
            update_schedule_page();
            notify_schedule_event("SCHEDULE_COMPLETED", index);
        }
        return;
    }
    if (index >= s_schedule_count) {
        lv_obj_remove_state(checkbox, LV_STATE_CHECKED);
        return;
    }

    if (s_schedule_completed[index]) {
        lv_obj_add_state(checkbox, LV_STATE_CHECKED);
        return;
    }

    s_schedule_completed[index] = true;
    s_schedule_completed_ts[index] = time(NULL);
    ESP_LOGI(TAG, "Schedule completed: row=%u pending_removal=%u",
             (unsigned int)index, (unsigned int)s_schedule_count);
    (void)app_tts_play_success_chime();
    schedule_ensure_cleanup_timer();
    update_schedule_page();
    notify_schedule_event("SCHEDULE_COMPLETED", index);
}

static void update_pomodoro_page(void)
{
    if (s_pomodoro_time_label == NULL || s_pomodoro_status_label == NULL) {
        return;
    }

    int32_t remaining = s_pomodoro_remaining_sec;
    if (remaining < 0) {
        remaining = 0;
    }

    char text[16];
    snprintf(text, sizeof(text), "%02ld:%02ld", (long)(remaining / 60), (long)(remaining % 60));
    lv_label_set_text(s_pomodoro_time_label, text);
    switch (s_pomodoro_phase) {
    case POMODORO_FOCUS:
        lv_label_set_text(s_pomodoro_status_label, "Focus in progress");
        break;
    case POMODORO_REST:
        lv_label_set_text(s_pomodoro_status_label, remaining > 0 ? "Take a 5 min break" : "Break finished");
        break;
    case POMODORO_IDLE:
    default:
        lv_label_set_text(s_pomodoro_status_label, "Pomodoro closed");
        break;
    }
}

static void pomodoro_timer_cb(lv_timer_t *timer)
{
    (void)timer;
    if (s_pomodoro_phase == POMODORO_IDLE) {
        update_pomodoro_page();
        return;
    }

    if (s_pomodoro_remaining_sec > 0) {
        s_pomodoro_remaining_sec--;
    }
    if (s_pomodoro_remaining_sec <= 0) {
        if (s_pomodoro_phase == POMODORO_FOCUS) {
            s_pomodoro_phase = POMODORO_REST;
            s_pomodoro_remaining_sec = 5 * 60;
        } else {
            s_pomodoro_remaining_sec = 0;
        }
    }
    update_pomodoro_page();
}

static const char *mood_to_text(app_mood_t mood)
{
    switch (mood) {
    case APP_MOOD_HAPPY:
        return "Mood: happy";
    case APP_MOOD_THIRSTY:
        return "Mood: thirsty";
    case APP_MOOD_DARK:
        return "Mood: dim";
    case APP_MOOD_WEAK:
        return "Mood: weak";
    default:
        return "Mood: happy";
    }
}

static const char *mood_to_word(app_mood_t mood)
{
    switch (mood) {
    case APP_MOOD_THIRSTY:
        return "thirsty";
    case APP_MOOD_DARK:
        return "dim";
    case APP_MOOD_WEAK:
        return "weak";
    case APP_MOOD_HAPPY:
    default:
        return "happy";
    }
}

static uint8_t mood_to_percent(app_mood_t mood)
{
    switch (mood) {
    case APP_MOOD_HAPPY:
        return 92;
    case APP_MOOD_DARK:
        return 42;
    case APP_MOOD_WEAK:
        return 16;
    case APP_MOOD_THIRSTY:
    default:
        return 28;
    }
}

static lv_obj_t *make_bar(lv_obj_t *parent, int32_t y, lv_color_t color)
{
    lv_obj_t *bar = lv_bar_create(parent);
    lv_obj_set_size(bar, 160, 22);
    lv_obj_align(bar, LV_ALIGN_TOP_LEFT, 0, y);
    lv_bar_set_range(bar, 0, 100);
    lv_obj_set_style_bg_color(bar, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_set_style_bg_color(bar, color, LV_PART_INDICATOR);
    lv_obj_set_style_border_color(bar, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_border_width(bar, 2, LV_PART_MAIN);
    lv_obj_set_style_radius(bar, 999, LV_PART_MAIN);
    lv_obj_set_style_radius(bar, 999, LV_PART_INDICATOR);
    return bar;
}

static lv_obj_t *make_leaf(lv_obj_t *parent, int32_t x, int32_t y, int32_t w, int32_t h, lv_color_t color)
{
    lv_obj_t *leaf = lv_obj_create(parent);
    lv_obj_remove_style_all(leaf);
    lv_obj_set_size(leaf, w, h);
    lv_obj_align(leaf, LV_ALIGN_TOP_LEFT, x, y);
    lv_obj_set_style_radius(leaf, 999, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(leaf, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(leaf, color, LV_PART_MAIN);
    lv_obj_set_style_border_width(leaf, 0, LV_PART_MAIN);
    return leaf;
}

static void enable_page_switch_target(lv_obj_t *obj)
{
    if (obj == NULL) {
        return;
    }
    lv_obj_add_flag(obj, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(obj, touch_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_add_event_cb(obj, touch_event_cb, LV_EVENT_GESTURE, NULL);
}

static lv_obj_t *make_metric_card(lv_obj_t *parent, int32_t x, int32_t y, const char *title,
                                  lv_color_t accent, int icon_kind, lv_obj_t **value_out)
{
    lv_obj_t *card = lv_obj_create(parent);
    lv_obj_set_size(card, 386, 78);
    lv_obj_align(card, LV_ALIGN_TOP_LEFT, x, y);
    lv_obj_set_style_radius(card, 16, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(card, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_bg_color(card, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_set_style_border_color(card, accent, LV_PART_MAIN);
    lv_obj_set_style_border_width(card, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(card, 0, LV_PART_MAIN);
    lv_obj_remove_flag(card, LV_OBJ_FLAG_SCROLLABLE);
    enable_page_switch_target(card);

    lv_obj_t *icon = lv_obj_create(card);
    lv_obj_remove_style_all(icon);
    lv_obj_set_size(icon, 72, 68);
    lv_obj_align(icon, LV_ALIGN_LEFT_MID, 20, 0);
    lv_obj_clear_flag(icon, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(icon, LV_OBJ_FLAG_HIDDEN);
    if (icon_kind == 0) {
        lv_obj_t *drop = make_leaf(icon, 11, 4, 50, 60, accent);
        lv_obj_set_style_border_color(drop, lv_color_hex(0xffffff), LV_PART_MAIN);
        lv_obj_set_style_border_width(drop, 2, LV_PART_MAIN);
        make_leaf(drop, 18, 28, 5, 6, lv_color_hex(0x103747));
        make_leaf(drop, 31, 28, 5, 6, lv_color_hex(0x103747));
        make_leaf(drop, 24, 40, 8, 4, lv_color_hex(0x103747));
        make_leaf(drop, 14, 20, 5, 12, lv_color_hex(0xdaf7ff));
    } else if (icon_kind == 1) {
        lv_obj_t *sun = make_leaf(icon, 18, 16, 38, 38, accent);
        make_leaf(sun, 9, 12, 5, 6, lv_color_hex(0x4a3b0c));
        make_leaf(sun, 24, 12, 5, 6, lv_color_hex(0x4a3b0c));
        lv_obj_t *mouth = lv_label_create(sun);
        lv_label_set_text(mouth, "U");
        lv_obj_set_style_text_font(mouth, &lv_font_montserrat_16, LV_PART_MAIN);
        lv_obj_set_style_text_color(mouth, lv_color_hex(0x4a3b0c), LV_PART_MAIN);
        lv_obj_align(mouth, LV_ALIGN_CENTER, 0, 7);
        make_leaf(icon, 34, 1, 5, 12, accent);
        make_leaf(icon, 34, 57, 5, 10, accent);
        make_leaf(icon, 1, 33, 12, 5, accent);
        make_leaf(icon, 59, 33, 12, 5, accent);
        make_leaf(icon, 9, 9, 8, 8, accent);
        make_leaf(icon, 55, 9, 8, 8, accent);
        make_leaf(icon, 9, 53, 8, 8, accent);
        make_leaf(icon, 55, 53, 8, 8, accent);
    } else {
        make_leaf(icon, 13, 14, 26, 26, accent);
        make_leaf(icon, 34, 14, 26, 26, accent);
        make_leaf(icon, 20, 28, 34, 32, accent);
        lv_obj_t *heart_face = lv_label_create(icon);
        lv_label_set_text(heart_face, "^");
        lv_obj_set_style_text_font(heart_face, &lv_font_montserrat_18, LV_PART_MAIN);
        lv_obj_set_style_text_color(heart_face, lv_color_hex(0x5b1f2a), LV_PART_MAIN);
        lv_obj_align(heart_face, LV_ALIGN_CENTER, 0, 8);
    }

    lv_obj_t *title_label = lv_label_create(card);
    lv_label_set_text(title_label, title);
    lv_obj_set_style_text_font(title_label, &lv_font_montserrat_20, LV_PART_MAIN);
    lv_obj_set_style_text_color(title_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_align(title_label, LV_ALIGN_TOP_LEFT, 112, 11);

    lv_obj_t *value_label = lv_label_create(card);
    lv_label_set_text(value_label, "--%");
    lv_obj_set_style_text_font(value_label, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(value_label, accent, LV_PART_MAIN);
    lv_obj_align(value_label, LV_ALIGN_TOP_LEFT, 112, 38);
    if (value_out != NULL) {
        *value_out = value_label;
    }

    return card;
}

#if APP_UI_SHOW_MOTION_DEBUG_PAGE
static lv_obj_t *make_motion_debug_panel(lv_obj_t *parent, int32_t x, int32_t y, int32_t w, int32_t h,
                                         const char *title, lv_color_t accent, lv_obj_t **value_out)
{
    lv_obj_t *panel = lv_obj_create(parent);
    lv_obj_set_size(panel, w, h);
    lv_obj_align(panel, LV_ALIGN_TOP_LEFT, x, y);
    lv_obj_set_style_radius(panel, 8, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(panel, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(panel, lv_color_hex(0x0d1714), LV_PART_MAIN);
    lv_obj_set_style_border_color(panel, accent, LV_PART_MAIN);
    lv_obj_set_style_border_width(panel, 1, LV_PART_MAIN);
    lv_obj_set_style_pad_all(panel, 0, LV_PART_MAIN);
    lv_obj_remove_flag(panel, LV_OBJ_FLAG_SCROLLABLE);
    enable_page_switch_target(panel);

    lv_obj_t *title_label = lv_label_create(panel);
    lv_label_set_text(title_label, title);
    lv_obj_set_style_text_font(title_label, &lv_font_montserrat_16, LV_PART_MAIN);
    lv_obj_set_style_text_color(title_label, lv_color_hex(0xb8c7bd), LV_PART_MAIN);
    lv_obj_set_width(title_label, w - 24);
    lv_label_set_long_mode(title_label, LV_LABEL_LONG_CLIP);
    lv_obj_align(title_label, LV_ALIGN_TOP_LEFT, 12, h <= 60 ? 5 : 9);

    lv_obj_t *value_label = lv_label_create(panel);
    lv_label_set_text(value_label, "--");
    lv_obj_set_width(value_label, w - 24);
    lv_label_set_long_mode(value_label, LV_LABEL_LONG_CLIP);
    lv_obj_set_style_text_font(value_label,
                               h <= 60 ? &lv_font_montserrat_20 : &lv_font_montserrat_24,
                               LV_PART_MAIN);
    lv_obj_set_style_text_color(value_label, accent, LV_PART_MAIN);
    lv_obj_set_style_text_letter_space(value_label, 0, LV_PART_MAIN);
    lv_obj_align(value_label, LV_ALIGN_TOP_LEFT, 12, h <= 60 ? 24 : 36);
    if (value_out != NULL) {
        *value_out = value_label;
    }

    return panel;
}

static void make_motion_debug_page(lv_obj_t *screen)
{
    s_motion_page = lv_obj_create(screen);
    lv_obj_remove_style_all(s_motion_page);
    lv_obj_set_size(s_motion_page, UI_SCREEN_W, UI_SCREEN_H);
    lv_obj_set_style_bg_opa(s_motion_page, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_motion_page, lv_color_hex(0x06100e), LV_PART_MAIN);
    lv_obj_set_style_text_color(s_motion_page, lv_color_hex(0xf0fff8), LV_PART_MAIN);
    lv_obj_add_flag(s_motion_page, LV_OBJ_FLAG_HIDDEN);
    enable_page_switch_target(s_motion_page);

    lv_obj_t *title = lv_label_create(s_motion_page);
    lv_label_set_text(title, "MPU6050 DEBUG");
    lv_obj_set_style_text_font(title, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(title, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_align(title, LV_ALIGN_TOP_LEFT, 40, 24);

    lv_obj_t *subtitle = lv_label_create(s_motion_page);
    lv_label_set_text(subtitle, "Live angle, acceleration, gyro and detected state");
    lv_obj_set_width(subtitle, 430);
    lv_label_set_long_mode(subtitle, LV_LABEL_LONG_CLIP);
    lv_obj_set_style_text_font(subtitle, &lv_font_montserrat_16, LV_PART_MAIN);
    lv_obj_set_style_text_color(subtitle, lv_color_hex(0xb8c7bd), LV_PART_MAIN);
    lv_obj_align(subtitle, LV_ALIGN_TOP_LEFT, 42, 62);

    s_motion_status_label = lv_label_create(s_motion_page);
    lv_label_set_text(s_motion_status_label, "MPU: waiting");
    lv_obj_set_width(s_motion_status_label, 260);
    lv_label_set_long_mode(s_motion_status_label, LV_LABEL_LONG_CLIP);
    lv_obj_set_style_text_font(s_motion_status_label, &lv_font_montserrat_18, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_motion_status_label, lv_color_hex(0xffe46e), LV_PART_MAIN);
    lv_obj_set_style_text_align(s_motion_status_label, LV_TEXT_ALIGN_RIGHT, LV_PART_MAIN);
    lv_obj_align(s_motion_status_label, LV_ALIGN_TOP_RIGHT, -40, 56);

    make_motion_debug_panel(s_motion_page, 40, 100, 216, 96, "ROLL", lv_color_hex(0x86dfff),
                            &s_motion_roll_label);
    make_motion_debug_panel(s_motion_page, 292, 100, 216, 96, "PITCH", lv_color_hex(0xc9ee76),
                            &s_motion_pitch_label);
    make_motion_debug_panel(s_motion_page, 544, 100, 216, 96, "STATE", lv_color_hex(0xff9baa),
                            &s_motion_state_label);
    make_motion_debug_panel(s_motion_page, 40, 220, 720, 52, "ACCEL G",
                            lv_color_hex(0x86dfff), &s_motion_accel_label);
    make_motion_debug_panel(s_motion_page, 40, 284, 720, 52, "GYRO DPS",
                            lv_color_hex(0xc9ee76), &s_motion_gyro_label);
    make_motion_debug_panel(s_motion_page, 40, 348, 720, 52, "MAGNITUDE",
                            lv_color_hex(0xffe46e), &s_motion_mag_label);
    make_motion_debug_panel(s_motion_page, 40, 412, 348, 48, "LAST EVENT",
                            lv_color_hex(0xff9baa), &s_motion_event_label);
    make_motion_debug_panel(s_motion_page, 412, 412, 348, 48, "REACTION",
                            lv_color_hex(0x86dfff), &s_motion_reaction_label);

    lv_label_set_text(s_motion_event_label, "none");
    lv_label_set_text(s_motion_reaction_label, "none");
}
#endif

static lv_obj_t *make_line(lv_obj_t *parent, lv_point_precise_t *points, uint32_t point_count,
                           lv_color_t color, int32_t width)
{
    lv_obj_t *line = lv_line_create(parent);
    lv_line_set_points(line, points, point_count);
    lv_obj_set_style_line_color(line, color, LV_PART_MAIN);
    lv_obj_set_style_line_width(line, width, LV_PART_MAIN);
    lv_obj_set_style_line_rounded(line, false, LV_PART_MAIN);
    return line;
}

static lv_obj_t *make_local_line(lv_obj_t *parent, int32_t x, int32_t y, int32_t w, int32_t h,
                                 lv_point_precise_t *points, uint32_t point_count,
                                 lv_color_t color, int32_t width)
{
    lv_obj_t *line = make_line(parent, points, point_count, color, width);
    lv_obj_set_size(line, w, h);
    lv_obj_align(line, LV_ALIGN_TOP_LEFT, x, y);
    return line;
}

static void canvas_draw_line(lv_layer_t *layer, int32_t x1, int32_t y1, int32_t x2, int32_t y2,
                             lv_color_t color, int32_t width)
{
    lv_draw_line_dsc_t line;
    lv_draw_line_dsc_init(&line);
    line.p1.x = x1;
    line.p1.y = y1;
    line.p2.x = x2;
    line.p2.y = y2;
    line.color = color;
    line.width = width;
    line.opa = LV_OPA_COVER;
    line.round_start = 1;
    line.round_end = 1;
    lv_draw_line(layer, &line);
}

static void canvas_draw_polygon(lv_layer_t *layer, const lv_point_precise_t *points, uint16_t count,
                                lv_color_t fill, lv_color_t outline, int32_t outline_width)
{
    lv_draw_triangle_dsc_t tri;
    lv_draw_triangle_dsc_init(&tri);
    tri.color = fill;
    tri.opa = LV_OPA_COVER;
    for (uint16_t i = 1; i + 1 < count; ++i) {
        tri.p[0] = points[0];
        tri.p[1] = points[i];
        tri.p[2] = points[i + 1];
        lv_draw_triangle(layer, &tri);
    }
    if (outline_width > 0) {
        for (uint16_t i = 0; i < count; ++i) {
            const lv_point_precise_t *a = &points[i];
            const lv_point_precise_t *b = &points[(i + 1) % count];
            canvas_draw_line(layer, a->x, a->y, b->x, b->y, outline, outline_width);
        }
    }
}

static lv_point_precise_t cubic_point(lv_point_precise_t p0, lv_point_precise_t p1,
                                      lv_point_precise_t p2, lv_point_precise_t p3, float t)
{
    float u = 1.0f - t;
    lv_point_precise_t point;
    point.x = (int32_t)lroundf(u * u * u * p0.x + 3.0f * u * u * t * p1.x +
                              3.0f * u * t * t * p2.x + t * t * t * p3.x);
    point.y = (int32_t)lroundf(u * u * u * p0.y + 3.0f * u * u * t * p1.y +
                              3.0f * u * t * t * p2.y + t * t * t * p3.y);
    return point;
}

static void append_cubic(lv_point_precise_t *points, uint16_t *count, lv_point_precise_t p0,
                         lv_point_precise_t p1, lv_point_precise_t p2, lv_point_precise_t p3,
                         uint16_t steps, bool include_start)
{
    uint16_t first = include_start ? 0 : 1;
    for (uint16_t i = first; i <= steps; ++i) {
        points[(*count)++] = cubic_point(p0, p1, p2, p3, (float)i / (float)steps);
    }
}

static void canvas_draw_ellipse(lv_layer_t *layer, int32_t cx, int32_t cy, int32_t rx, int32_t ry,
                                lv_color_t fill, lv_color_t outline, int32_t outline_width)
{
    lv_point_precise_t points[24];
    for (uint16_t i = 0; i < 24; ++i) {
        float angle = (float)i * 2.0f * 3.14159265f / 24.0f;
        points[i].x = cx + (int32_t)lroundf(cosf(angle) * rx);
        points[i].y = cy + (int32_t)lroundf(sinf(angle) * ry);
    }
    canvas_draw_polygon(layer, points, 24, fill, outline, outline_width);
}

static void canvas_draw_leaf(lv_layer_t *layer, float bx, float by, float tx, float ty,
                             float half_width, lv_color_t fill)
{
    float dx = tx - bx;
    float dy = ty - by;
    float len = sqrtf(dx * dx + dy * dy);
    float px = -dy * half_width / len;
    float py = dx * half_width / len;
    lv_point_precise_t base = {(int32_t)bx, (int32_t)by};
    lv_point_precise_t tip = {(int32_t)tx, (int32_t)ty};
    lv_point_precise_t c1 = {(int32_t)(bx + dx * 0.28f + px * 0.55f),
                             (int32_t)(by + dy * 0.28f + py * 0.55f)};
    lv_point_precise_t c2 = {(int32_t)(tx - dx * 0.34f + px),
                             (int32_t)(ty - dy * 0.34f + py)};
    lv_point_precise_t c3 = {(int32_t)(tx - dx * 0.34f - px),
                             (int32_t)(ty - dy * 0.34f - py)};
    lv_point_precise_t c4 = {(int32_t)(bx + dx * 0.28f - px * 0.55f),
                             (int32_t)(by + dy * 0.28f - py * 0.55f)};
    lv_point_precise_t points[18];
    uint16_t count = 0;
    append_cubic(points, &count, base, c1, c2, tip, 8, true);
    append_cubic(points, &count, tip, c3, c4, base, 8, false);
    --count;
    canvas_draw_polygon(layer, points, count, fill, lv_color_hex(0x315b2a), 2);
    canvas_draw_line(layer, (int32_t)bx, (int32_t)by,
                     (int32_t)(tx - dx * 0.10f), (int32_t)(ty - dy * 0.10f),
                     lv_color_hex(0x557f32), 2);
    for (int branch = 1; branch <= 2; ++branch) {
        float f = 0.36f + branch * 0.18f;
        float mx = bx + dx * f;
        float my = by + dy * f;
        canvas_draw_line(layer, (int32_t)mx, (int32_t)my,
                         (int32_t)(mx + px * 0.42f), (int32_t)(my + py * 0.42f),
                         lv_color_hex(0x557f32), 1);
        canvas_draw_line(layer, (int32_t)mx, (int32_t)my,
                         (int32_t)(mx - px * 0.42f), (int32_t)(my - py * 0.42f),
                         lv_color_hex(0x557f32), 1);
    }
}

static void cat_canvas_delete_cb(lv_event_t *event)
{
    lv_obj_t *canvas = lv_event_get_target(event);
    lv_draw_buf_destroy(lv_canvas_get_draw_buf(canvas));
}

static void draw_cat_planter_canvas(lv_obj_t *canvas)
{
    lv_layer_t layer;
    lv_canvas_fill_bg(canvas, lv_color_hex(0x000000), LV_OPA_COVER);
    lv_canvas_init_layer(canvas, &layer);
    const lv_color_t outline = lv_color_hex(0x4d3b2d);

    canvas_draw_ellipse(&layer, 150, 272, 112, 10, lv_color_hex(0x28211d), lv_color_hex(0x28211d), 0);
    canvas_draw_line(&layer, 121, 142, 113, 72, lv_color_hex(0x4f993d), 6);
    canvas_draw_line(&layer, 150, 142, 151, 39, lv_color_hex(0x4f993d), 6);
    canvas_draw_line(&layer, 177, 142, 193, 73, lv_color_hex(0x4f993d), 6);
    canvas_draw_line(&layer, 137, 142, 93, 111, lv_color_hex(0x4f993d), 5);
    canvas_draw_line(&layer, 163, 142, 214, 111, lv_color_hex(0x4f993d), 5);
    canvas_draw_leaf(&layer, 121, 94, 58, 49, 23, lv_color_hex(0x9dce4e));
    canvas_draw_leaf(&layer, 150, 85, 151, 5, 28, lv_color_hex(0xb2d857));
    canvas_draw_leaf(&layer, 179, 94, 246, 49, 23, lv_color_hex(0x9dce4e));
    canvas_draw_leaf(&layer, 137, 130, 79, 102, 18, lv_color_hex(0x76b83f));
    canvas_draw_leaf(&layer, 164, 130, 229, 101, 18, lv_color_hex(0x77bc42));

    lv_point_precise_t ear_left[] = {{49, 166}, {46, 151}, {47, 130}, {53, 116},
                                     {66, 122}, {84, 137}, {94, 153}};
    lv_point_precise_t ear_right[] = {{251, 166}, {254, 151}, {253, 130}, {247, 116},
                                      {234, 122}, {216, 137}, {206, 153}};
    canvas_draw_polygon(&layer, ear_left, 7, lv_color_hex(0xffefd6), outline, 3);
    canvas_draw_polygon(&layer, ear_right, 7, lv_color_hex(0xffefd6), outline, 3);
    lv_point_precise_t inner_left[] = {{57, 151}, {56, 130}, {76, 143}};
    lv_point_precise_t inner_right[] = {{243, 151}, {244, 130}, {224, 143}};
    canvas_draw_polygon(&layer, inner_left, 3, lv_color_hex(0xf6b9a5), lv_color_hex(0xf6b9a5), 0);
    canvas_draw_polygon(&layer, inner_right, 3, lv_color_hex(0xf6b9a5), lv_color_hex(0xf6b9a5), 0);

    lv_point_precise_t body[40];
    uint16_t body_count = 0;
    lv_point_precise_t p0 = {51, 151};
    lv_point_precise_t p1 = {36, 171};
    lv_point_precise_t p2 = {38, 225};
    lv_point_precise_t p3 = {64, 250};
    append_cubic(body, &body_count, p0, p1, p2, p3, 8, true);
    p0 = p3; p1 = (lv_point_precise_t){91, 278}; p2 = (lv_point_precise_t){209, 278}; p3 = (lv_point_precise_t){236, 250};
    append_cubic(body, &body_count, p0, p1, p2, p3, 8, false);
    p0 = p3; p1 = (lv_point_precise_t){262, 225}; p2 = (lv_point_precise_t){264, 171}; p3 = (lv_point_precise_t){249, 151};
    append_cubic(body, &body_count, p0, p1, p2, p3, 8, false);
    p0 = p3; p1 = (lv_point_precise_t){215, 137}; p2 = (lv_point_precise_t){85, 137}; p3 = (lv_point_precise_t){51, 151};
    append_cubic(body, &body_count, p0, p1, p2, p3, 8, false);
    --body_count;
    canvas_draw_polygon(&layer, body, body_count, lv_color_hex(0xffefd6), outline, 3);

    canvas_draw_ellipse(&layer, 150, 145, 88, 14, lv_color_hex(0x59402c), outline, 2);
    canvas_draw_ellipse(&layer, 105, 142, 9, 4, lv_color_hex(0x795b3d), lv_color_hex(0x795b3d), 0);
    canvas_draw_ellipse(&layer, 153, 148, 11, 5, lv_color_hex(0x795b3d), lv_color_hex(0x795b3d), 0);
    canvas_draw_ellipse(&layer, 207, 142, 8, 4, lv_color_hex(0x795b3d), lv_color_hex(0x795b3d), 0);
    canvas_draw_line(&layer, 131, 151, 133, 176, lv_color_hex(0xeab882), 8);
    canvas_draw_line(&layer, 151, 153, 151, 180, lv_color_hex(0xeab882), 8);
    canvas_draw_line(&layer, 171, 151, 168, 176, lv_color_hex(0xeab882), 8);
    canvas_draw_ellipse(&layer, 74, 232, 15, 7, lv_color_hex(0xffa9af), lv_color_hex(0xffa9af), 0);
    canvas_draw_ellipse(&layer, 226, 232, 15, 7, lv_color_hex(0xffa9af), lv_color_hex(0xffa9af), 0);
    canvas_draw_line(&layer, 45, 214, 68, 217, outline, 2);
    canvas_draw_line(&layer, 45, 224, 68, 221, outline, 2);
    canvas_draw_line(&layer, 232, 217, 255, 214, outline, 2);
    canvas_draw_line(&layer, 232, 221, 255, 224, outline, 2);

    lv_canvas_finish_layer(canvas, &layer);
}

static void make_cat_art(lv_obj_t *parent)
{
    lv_obj_t *area = lv_obj_create(parent);
    s_cat_area = area;
    lv_obj_remove_style_all(area);
    lv_obj_set_size(area, 300, 286);
    lv_obj_align(area, LV_ALIGN_TOP_LEFT, 42, 96);
    lv_obj_clear_flag(area, LV_OBJ_FLAG_SCROLLABLE);
    enable_page_switch_target(area);

    lv_obj_t *cat_image = lv_image_create(area);
    lv_image_set_src(cat_image, &cat_planter_img);
    lv_obj_align(cat_image, LV_ALIGN_TOP_LEFT, 0, 0);

    /* Keep the dynamic expression in a page-level sibling layer.  Some image
     * decoders draw their alpha image after child objects on this target. */
    s_cat_face_layer = lv_obj_create(parent);
    lv_obj_remove_style_all(s_cat_face_layer);
    lv_obj_set_size(s_cat_face_layer, 300, 286);
    lv_obj_align(s_cat_face_layer, LV_ALIGN_TOP_LEFT, 42, 96);
    lv_obj_clear_flag(s_cat_face_layer, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *face = s_cat_face_layer;
    s_cat_head = lv_obj_create(face);
    lv_obj_remove_style_all(s_cat_head);
    lv_obj_set_size(s_cat_head, 1, 1);
    lv_obj_add_flag(s_cat_head, LV_OBJ_FLAG_HIDDEN);

    s_cat_eye_left = make_leaf(face, 88, 191, 28, 38, lv_color_hex(0x111111));
    s_cat_eye_right = make_leaf(face, 184, 191, 28, 38, lv_color_hex(0x111111));
    s_cat_highlight_left = make_leaf(face, 98, 197, 8, 11, lv_color_hex(0xffffff));
    s_cat_highlight_right = make_leaf(face, 194, 197, 8, 11, lv_color_hex(0xffffff));
    s_cat_happy_eye_left = make_local_line(face, 86, 201, 34, 16, s_cat_happy_eye_pts, 3,
                                           lv_color_hex(0x2a2118), 5);
    s_cat_happy_eye_right = make_local_line(face, 182, 201, 34, 16, s_cat_happy_eye_pts, 3,
                                             lv_color_hex(0x2a2118), 5);
    lv_obj_add_flag(s_cat_happy_eye_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_cat_happy_eye_right, LV_OBJ_FLAG_HIDDEN);
    s_cat_blink_eye_left = make_local_line(face, 86, 207, 34, 10, s_cat_blink_eye_pts, 2,
                                           lv_color_hex(0x2a2118), 5);
    s_cat_blink_eye_right = make_local_line(face, 182, 207, 34, 10, s_cat_blink_eye_pts, 2,
                                             lv_color_hex(0x2a2118), 5);
    lv_obj_add_flag(s_cat_blink_eye_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_cat_blink_eye_right, LV_OBJ_FLAG_HIDDEN);

    s_cat_swirl_eye_left = lv_label_create(face);
    lv_label_set_text(s_cat_swirl_eye_left, "@");
    lv_obj_set_style_text_font(s_cat_swirl_eye_left, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_cat_swirl_eye_left, lv_color_hex(0x2a2118), LV_PART_MAIN);
    lv_obj_align(s_cat_swirl_eye_left, LV_ALIGN_TOP_LEFT, 87, 193);
    lv_obj_add_flag(s_cat_swirl_eye_left, LV_OBJ_FLAG_HIDDEN);

    s_cat_swirl_eye_right = lv_label_create(face);
    lv_label_set_text(s_cat_swirl_eye_right, "@");
    lv_obj_set_style_text_font(s_cat_swirl_eye_right, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_cat_swirl_eye_right, lv_color_hex(0x2a2118), LV_PART_MAIN);
    lv_obj_align(s_cat_swirl_eye_right, LV_ALIGN_TOP_LEFT, 183, 193);
    lv_obj_add_flag(s_cat_swirl_eye_right, LV_OBJ_FLAG_HIDDEN);

    s_cat_brow_left = lv_label_create(face);
    lv_label_set_text(s_cat_brow_left, "");
    lv_obj_set_style_text_font(s_cat_brow_left, &lv_font_montserrat_24, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_cat_brow_left, lv_color_hex(0x2a2118), LV_PART_MAIN);
    lv_obj_align(s_cat_brow_left, LV_ALIGN_TOP_LEFT, 86, 174);

    s_cat_brow_right = lv_label_create(face);
    lv_label_set_text(s_cat_brow_right, "");
    lv_obj_set_style_text_font(s_cat_brow_right, &lv_font_montserrat_24, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_cat_brow_right, lv_color_hex(0x2a2118), LV_PART_MAIN);
    lv_obj_align(s_cat_brow_right, LV_ALIGN_TOP_LEFT, 186, 174);

    make_leaf(face, 145, 226, 10, 7, lv_color_hex(0x181818));
    s_cat_mouth = lv_label_create(face);
    lv_label_set_text(s_cat_mouth, "U");
    lv_obj_set_style_text_font(s_cat_mouth, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_cat_mouth, lv_color_hex(0x2a2118), LV_PART_MAIN);
    lv_obj_align(s_cat_mouth, LV_ALIGN_TOP_MID, -1, 226);

    s_cat_drop = make_leaf(face, 238, 184, 17, 25, lv_color_hex(0x70c8ff));
    lv_obj_add_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);

    s_cat_drop_left = make_leaf(face, 55, 197, 13, 20, lv_color_hex(0x70c8ff));
    lv_obj_add_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);

    s_cat_zzz_label = lv_label_create(face);
    lv_label_set_text(s_cat_zzz_label, "zZ");
    lv_obj_set_style_text_font(s_cat_zzz_label, &lv_font_montserrat_22, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_cat_zzz_label, lv_color_hex(0xff9baa), LV_PART_MAIN);
    lv_obj_align(s_cat_zzz_label, LV_ALIGN_TOP_LEFT, 245, 166);
    lv_obj_add_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);

    s_cat_heart = lv_obj_create(face);
    lv_obj_remove_style_all(s_cat_heart);
    lv_obj_set_size(s_cat_heart, 48, 48);
    lv_obj_align(s_cat_heart, LV_ALIGN_TOP_MID, 0, 92);
    lv_obj_clear_flag(s_cat_heart, LV_OBJ_FLAG_SCROLLABLE);
    make_leaf(s_cat_heart, 7, 5, 18, 18, lv_color_hex(0xff5f86));
    make_leaf(s_cat_heart, 23, 5, 18, 18, lv_color_hex(0xff5f86));
    make_leaf(s_cat_heart, 12, 15, 24, 26, lv_color_hex(0xff5f86));
    lv_obj_add_flag(s_cat_heart, LV_OBJ_FLAG_HIDDEN);

    lv_anim_t sweat_anim;
    lv_anim_init(&sweat_anim);
    lv_anim_set_var(&sweat_anim, s_cat_drop);
    lv_anim_set_exec_cb(&sweat_anim, (lv_anim_exec_xcb_t)lv_obj_set_y);
    lv_anim_set_values(&sweat_anim, 184, 194);
    lv_anim_set_duration(&sweat_anim, 760);
    lv_anim_set_playback_duration(&sweat_anim, 760);
    lv_anim_set_repeat_count(&sweat_anim, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_path_cb(&sweat_anim, lv_anim_path_ease_in_out);
    lv_anim_start(&sweat_anim);

    lv_anim_t sweat_left_anim;
    lv_anim_init(&sweat_left_anim);
    lv_anim_set_var(&sweat_left_anim, s_cat_drop_left);
    lv_anim_set_exec_cb(&sweat_left_anim, (lv_anim_exec_xcb_t)lv_obj_set_y);
    lv_anim_set_values(&sweat_left_anim, 197, 205);
    lv_anim_set_duration(&sweat_left_anim, 820);
    lv_anim_set_playback_duration(&sweat_left_anim, 820);
    lv_anim_set_repeat_count(&sweat_left_anim, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_path_cb(&sweat_left_anim, lv_anim_path_ease_in_out);
    lv_anim_start(&sweat_left_anim);

    lv_anim_t zzz_anim;
    lv_anim_init(&zzz_anim);
    lv_anim_set_var(&zzz_anim, s_cat_zzz_label);
    lv_anim_set_exec_cb(&zzz_anim, (lv_anim_exec_xcb_t)lv_obj_set_y);
    lv_anim_set_values(&zzz_anim, 166, 154);
    lv_anim_set_duration(&zzz_anim, 980);
    lv_anim_set_playback_duration(&zzz_anim, 980);
    lv_anim_set_repeat_count(&zzz_anim, LV_ANIM_REPEAT_INFINITE);
    lv_anim_set_path_cb(&zzz_anim, lv_anim_path_ease_in_out);
    lv_anim_start(&zzz_anim);

    cat_face_to_front();
    update_cat_art(s_current_mood);
}

static void cat_face_to_front(void)
{
    lv_obj_t *items[] = {
        s_cat_eye_left, s_cat_eye_right,
        s_cat_highlight_left, s_cat_highlight_right,
        s_cat_happy_eye_left, s_cat_happy_eye_right,
        s_cat_blink_eye_left, s_cat_blink_eye_right,
        s_cat_swirl_eye_left, s_cat_swirl_eye_right,
        s_cat_brow_left, s_cat_brow_right,
        s_cat_mouth, s_cat_drop, s_cat_drop_left, s_cat_zzz_label,
        s_cat_heart,
    };
    for (size_t i = 0; i < sizeof(items) / sizeof(items[0]); i++) {
        if (items[i] != NULL) {
            lv_obj_move_foreground(items[i]);
        }
    }
}

static void apply_motion_reaction_art(void)
{
    if (s_cat_eye_left == NULL || s_cat_eye_right == NULL || s_cat_mouth == NULL ||
        s_cat_brow_left == NULL || s_cat_brow_right == NULL) {
        return;
    }

    cat_face_to_front();
    if (s_cat_blink_eye_left != NULL) lv_obj_add_flag(s_cat_blink_eye_left, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_blink_eye_right != NULL) lv_obj_add_flag(s_cat_blink_eye_right, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_happy_eye_left != NULL) lv_obj_add_flag(s_cat_happy_eye_left, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_happy_eye_right != NULL) lv_obj_add_flag(s_cat_happy_eye_right, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_swirl_eye_left != NULL) lv_obj_add_flag(s_cat_swirl_eye_left, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_swirl_eye_right != NULL) lv_obj_add_flag(s_cat_swirl_eye_right, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_highlight_left != NULL) lv_obj_remove_flag(s_cat_highlight_left, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_highlight_right != NULL) lv_obj_remove_flag(s_cat_highlight_right, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_cat_eye_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_cat_eye_right, LV_OBJ_FLAG_HIDDEN);

    bool show_drop = false;
    bool show_drop_left = false;
    bool show_zzz = false;
    const char *zzz_text = "zZ";

    switch (s_motion_reaction) {
    case APP_UI_MOTION_REACTION_TAP:
        lv_obj_set_size(s_cat_eye_left, 36, 46);
        lv_obj_set_size(s_cat_eye_right, 36, 46);
        lv_obj_align(s_cat_eye_left, LV_ALIGN_TOP_LEFT, 82, 187);
        lv_obj_align(s_cat_eye_right, LV_ALIGN_TOP_LEFT, 180, 187);
        lv_label_set_text(s_cat_brow_left, "!");
        lv_label_set_text(s_cat_brow_right, "!");
        lv_label_set_text(s_cat_mouth, "O");
        break;
    case APP_UI_MOTION_REACTION_SHAKE:
        lv_obj_add_flag(s_cat_eye_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_cat_eye_right, LV_OBJ_FLAG_HIDDEN);
        if (s_cat_highlight_left != NULL) lv_obj_add_flag(s_cat_highlight_left, LV_OBJ_FLAG_HIDDEN);
        if (s_cat_highlight_right != NULL) lv_obj_add_flag(s_cat_highlight_right, LV_OBJ_FLAG_HIDDEN);
        if (s_cat_swirl_eye_left != NULL) lv_obj_remove_flag(s_cat_swirl_eye_left, LV_OBJ_FLAG_HIDDEN);
        if (s_cat_swirl_eye_right != NULL) lv_obj_remove_flag(s_cat_swirl_eye_right, LV_OBJ_FLAG_HIDDEN);
        lv_label_set_text(s_cat_brow_left, "\\");
        lv_label_set_text(s_cat_brow_right, "/");
        lv_label_set_text(s_cat_mouth, "~");
        show_drop = true;
        show_drop_left = true;
        show_zzz = true;
        zzz_text = "!!";
        break;
    case APP_UI_MOTION_REACTION_CARRIED:
    default:
        lv_obj_set_size(s_cat_eye_left, 30, 40);
        lv_obj_set_size(s_cat_eye_right, 30, 40);
        lv_obj_align(s_cat_eye_left, LV_ALIGN_TOP_LEFT, 87, 190);
        lv_obj_align(s_cat_eye_right, LV_ALIGN_TOP_LEFT, 183, 190);
        lv_label_set_text(s_cat_brow_left, "/");
        lv_label_set_text(s_cat_brow_right, "\\");
        lv_label_set_text(s_cat_mouth, "o");
        show_drop = true;
        break;
    }

    if (s_cat_drop != NULL) {
        if (show_drop) lv_obj_remove_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);
        else lv_obj_add_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);
    }
    if (s_cat_drop_left != NULL) {
        if (show_drop_left) lv_obj_remove_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);
        else lv_obj_add_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);
    }
    if (s_cat_zzz_label != NULL) {
        lv_label_set_text(s_cat_zzz_label, zzz_text);
        if (show_zzz) lv_obj_remove_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);
        else lv_obj_add_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);
    }
}

static void update_cat_art(app_mood_t mood)
{
    s_current_mood = mood;
    if (s_cat_head == NULL || s_cat_eye_left == NULL || s_cat_eye_right == NULL || s_cat_mouth == NULL) {
        return;
    }

    if (s_motion_reaction_active) {
        apply_motion_reaction_art();
        return;
    }

    const char *mouth = "U";
    const char *left_brow = "";
    const char *right_brow = "";
    bool show_drop = false;
    bool show_drop_left = false;
    bool show_zzz = false;

    switch (mood) {
    case APP_MOOD_HAPPY:
        mouth = "w";
        break;
    case APP_MOOD_THIRSTY:
        mouth = "^";
        left_brow = "\\";
        right_brow = "/";
        show_drop = true;
        show_drop_left = true;
        break;
    case APP_MOOD_DARK:
        mouth = "^";
        left_brow = "-";
        right_brow = "-";
        show_drop = true;
        show_zzz = true;
        break;
    case APP_MOOD_WEAK:
        mouth = "^";
        left_brow = "/";
        right_brow = "\\";
        show_drop = true;
        show_drop_left = true;
        break;
    default:
        break;
    }

    if (s_touch_blink_active) {
        cat_face_to_front();
        lv_label_set_text(s_cat_brow_left, "");
        lv_label_set_text(s_cat_brow_right, "");
        lv_obj_add_flag(s_cat_eye_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_cat_eye_right, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_cat_highlight_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_cat_highlight_right, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_cat_happy_eye_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_cat_happy_eye_right, LV_OBJ_FLAG_HIDDEN);
        if (s_cat_swirl_eye_left != NULL) lv_obj_add_flag(s_cat_swirl_eye_left, LV_OBJ_FLAG_HIDDEN);
        if (s_cat_swirl_eye_right != NULL) lv_obj_add_flag(s_cat_swirl_eye_right, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_cat_blink_eye_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_cat_blink_eye_right, LV_OBJ_FLAG_HIDDEN);
        lv_label_set_text(s_cat_mouth, mouth);
        if (show_drop && s_cat_drop != NULL) {
            lv_obj_remove_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);
        } else if (s_cat_drop != NULL) {
            lv_obj_add_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);
        }
        if (show_drop_left && s_cat_drop_left != NULL) {
            lv_obj_remove_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);
        } else if (s_cat_drop_left != NULL) {
            lv_obj_add_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);
        }
        if (show_zzz && s_cat_zzz_label != NULL) {
            lv_label_set_text(s_cat_zzz_label, "zZ");
            lv_obj_remove_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);
        } else if (s_cat_zzz_label != NULL) {
            lv_obj_add_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);
        }
        return;
    }
    cat_face_to_front();
    lv_obj_add_flag(s_cat_blink_eye_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_cat_blink_eye_right, LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_size(s_cat_eye_left, 28, 38);
    lv_obj_set_size(s_cat_eye_right, 28, 38);
    lv_obj_align(s_cat_eye_left, LV_ALIGN_TOP_LEFT, 88, 191);
    lv_obj_align(s_cat_eye_right, LV_ALIGN_TOP_LEFT, 184, 191);
    lv_obj_remove_flag(s_cat_eye_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_cat_eye_right, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_cat_highlight_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_remove_flag(s_cat_highlight_right, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_cat_happy_eye_left, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(s_cat_happy_eye_right, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_swirl_eye_left != NULL) lv_obj_add_flag(s_cat_swirl_eye_left, LV_OBJ_FLAG_HIDDEN);
    if (s_cat_swirl_eye_right != NULL) lv_obj_add_flag(s_cat_swirl_eye_right, LV_OBJ_FLAG_HIDDEN);
    lv_label_set_text(s_cat_brow_left, left_brow);
    lv_label_set_text(s_cat_brow_right, right_brow);
    lv_label_set_text(s_cat_mouth, mouth);
    if (show_drop) {
        lv_obj_remove_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(s_cat_drop, LV_OBJ_FLAG_HIDDEN);
    }
    if (s_cat_drop_left != NULL) {
        if (show_drop_left) {
            lv_obj_remove_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);
        } else {
            lv_obj_add_flag(s_cat_drop_left, LV_OBJ_FLAG_HIDDEN);
        }
    }
    if (s_cat_zzz_label != NULL) {
        lv_label_set_text(s_cat_zzz_label, "zZ");
        if (show_zzz) {
            lv_obj_remove_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);
        } else {
            lv_obj_add_flag(s_cat_zzz_label, LV_OBJ_FLAG_HIDDEN);
        }
    }
}

static void cat_heart_y_anim_cb(void *obj, int32_t y)
{
    lv_obj_set_y((lv_obj_t *)obj, y);
}

static void touch_blink_timer_cb(lv_timer_t *timer)
{
    s_touch_blink_active = false;
    s_touch_blink_timer = NULL;
    update_cat_art(s_current_mood);
    lv_timer_delete(timer);
}

static void idle_blink_timer_cb(lv_timer_t *timer)
{
    (void)timer;
    if (s_current_page != UI_PAGE_FACE || s_touch_blink_active ||
        s_cat_blink_eye_left == NULL || s_cat_blink_eye_right == NULL) {
        return;
    }

    s_touch_blink_active = true;
    update_cat_art(s_current_mood);
    if (s_touch_blink_timer != NULL) {
        lv_timer_delete(s_touch_blink_timer);
    }
    s_touch_blink_timer = lv_timer_create(touch_blink_timer_cb, 150, NULL);
    lv_timer_set_repeat_count(s_touch_blink_timer, 1);
}

static void schedule_face_set_blink(bool blinking)
{
    if (s_schedule_face_eye_left == NULL || s_schedule_face_eye_right == NULL ||
        s_schedule_face_blink_left == NULL || s_schedule_face_blink_right == NULL) {
        return;
    }

    if (blinking) {
        lv_obj_add_flag(s_schedule_face_eye_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_schedule_face_eye_right, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_schedule_face_blink_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_schedule_face_blink_right, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_remove_flag(s_schedule_face_eye_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_remove_flag(s_schedule_face_eye_right, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_schedule_face_blink_left, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_schedule_face_blink_right, LV_OBJ_FLAG_HIDDEN);
    }
}

static void schedule_blink_restore_timer_cb(lv_timer_t *timer)
{
    s_schedule_blink_restore_timer = NULL;
    schedule_face_set_blink(false);
    lv_timer_delete(timer);
}

static void schedule_blink_timer_cb(lv_timer_t *timer)
{
    (void)timer;
    if (s_current_page != UI_PAGE_SCHEDULE || s_schedule_face_eye_left == NULL) {
        return;
    }

    schedule_face_set_blink(true);
    if (s_schedule_blink_restore_timer != NULL) {
        lv_timer_delete(s_schedule_blink_restore_timer);
    }
    s_schedule_blink_restore_timer = lv_timer_create(schedule_blink_restore_timer_cb, 150, NULL);
    lv_timer_set_repeat_count(s_schedule_blink_restore_timer, 1);
}

static void heart_hide_timer_cb(lv_timer_t *timer)
{
    if (s_cat_heart != NULL) {
        lv_obj_add_flag(s_cat_heart, LV_OBJ_FLAG_HIDDEN);
    }
    s_heart_hide_timer = NULL;
    lv_timer_delete(timer);
}

void app_ui_play_touch_reaction(void)
{
    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for touch reaction");
        return;
    }

    if (s_cat_blink_eye_left != NULL && s_cat_blink_eye_right != NULL) {
        s_touch_blink_active = true;
        update_cat_art(s_current_mood);
        if (s_touch_blink_timer != NULL) {
            lv_timer_delete(s_touch_blink_timer);
        }
        s_touch_blink_timer = lv_timer_create(touch_blink_timer_cb, 180, NULL);
        lv_timer_set_repeat_count(s_touch_blink_timer, 1);
    }

    if (s_cat_heart != NULL) {
        if (s_heart_hide_timer != NULL) {
            lv_timer_delete(s_heart_hide_timer);
        }
        lv_anim_delete(s_cat_heart, cat_heart_y_anim_cb);
        lv_obj_clear_flag(s_cat_heart, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_style_opa(s_cat_heart, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_align(s_cat_heart, LV_ALIGN_TOP_MID, 0, 20);

        lv_anim_t y_anim;
        lv_anim_init(&y_anim);
        lv_anim_set_var(&y_anim, s_cat_heart);
        lv_anim_set_exec_cb(&y_anim, cat_heart_y_anim_cb);
        lv_anim_set_values(&y_anim, 20, -6);
        lv_anim_set_duration(&y_anim, 760);
        lv_anim_start(&y_anim);
        lv_obj_fade_out(s_cat_heart, 360, 360);

        s_heart_hide_timer = lv_timer_create(heart_hide_timer_cb, 820, NULL);
        lv_timer_set_repeat_count(s_heart_hide_timer, 1);
    }

    bsp_display_unlock();
}

static void motion_reaction_timer_cb(lv_timer_t *timer)
{
    s_motion_reaction_active = false;
    s_motion_reaction_timer = NULL;
    update_cat_art(s_current_mood);
    lv_timer_delete(timer);
}

void app_ui_play_motion_reaction(app_ui_motion_reaction_t reaction, uint32_t duration_ms)
{
    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for motion reaction");
        return;
    }

    if (s_touch_blink_timer != NULL) {
        lv_timer_delete(s_touch_blink_timer);
        s_touch_blink_timer = NULL;
    }
    s_touch_blink_active = false;

    s_motion_reaction = reaction;
    s_motion_reaction_active = true;
    update_cat_art(s_current_mood);

    if (s_motion_reaction_timer != NULL) {
        lv_timer_delete(s_motion_reaction_timer);
    }
    s_motion_reaction_timer = lv_timer_create(motion_reaction_timer_cb,
                                              duration_ms > 0 ? duration_ms : 1500,
                                              NULL);
    lv_timer_set_repeat_count(s_motion_reaction_timer, 1);
    bsp_display_unlock();
}

void app_ui_clear_motion_reaction(void)
{
    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for motion reaction clear");
        return;
    }

    if (s_motion_reaction_timer != NULL) {
        lv_timer_delete(s_motion_reaction_timer);
        s_motion_reaction_timer = NULL;
    }
    s_motion_reaction_active = false;
    update_cat_art(s_current_mood);
    bsp_display_unlock();
}

void app_ui_update_motion_debug(const app_ui_motion_debug_state_t *state)
{
    if (state == NULL) {
        return;
    }
    if (bsp_display_lock(20) != ESP_OK) {
        return;
    }

    if (s_motion_status_label == NULL) {
        bsp_display_unlock();
        return;
    }

    char text[96];
    if (!state->valid) {
        lv_label_set_text(s_motion_status_label, "MPU: waiting");
        if (s_motion_roll_label != NULL) lv_label_set_text(s_motion_roll_label, "-- deg");
        if (s_motion_pitch_label != NULL) lv_label_set_text(s_motion_pitch_label, "-- deg");
        if (s_motion_accel_label != NULL) lv_label_set_text(s_motion_accel_label, "X --  Y --  Z --");
        if (s_motion_gyro_label != NULL) lv_label_set_text(s_motion_gyro_label, "X --  Y --  Z --");
        if (s_motion_mag_label != NULL) lv_label_set_text(s_motion_mag_label, "|A| --  |G| --  fallD --");
        if (s_motion_state_label != NULL) lv_label_set_text(s_motion_state_label, "no sample E0");
        bsp_display_unlock();
        return;
    }

    lv_label_set_text(s_motion_status_label, "MPU: live");
    if (s_motion_roll_label != NULL) {
        snprintf(text, sizeof(text), "%+.1f deg", state->roll_deg);
        lv_label_set_text(s_motion_roll_label, text);
    }
    if (s_motion_pitch_label != NULL) {
        snprintf(text, sizeof(text), "%+.1f deg", state->pitch_deg);
        lv_label_set_text(s_motion_pitch_label, text);
    }
    if (s_motion_accel_label != NULL) {
        snprintf(text, sizeof(text), "X %+.2f   Y %+.2f   Z %+.2f",
                 state->accel_x_g, state->accel_y_g, state->accel_z_g);
        lv_label_set_text(s_motion_accel_label, text);
    }
    if (s_motion_gyro_label != NULL) {
        snprintf(text, sizeof(text), "X %+.1f   Y %+.1f   Z %+.1f",
                 state->gyro_x_dps, state->gyro_y_dps, state->gyro_z_dps);
        lv_label_set_text(s_motion_gyro_label, text);
    }
    if (s_motion_mag_label != NULL) {
        snprintf(text, sizeof(text), "|A| %.2fg   |G| %.1fdps   fallD %.1f/%.0fdeg",
                 state->accel_mag_g, state->gyro_mag_dps,
                 state->tilt_delta_deg, state->tilt_trigger_deg);
        lv_label_set_text(s_motion_mag_label, text);
    }
    if (s_motion_state_label != NULL) {
        snprintf(text, sizeof(text), "fall L%u E%lu",
                 (unsigned int)state->tilt_level,
                 (unsigned long)state->event_count);
        lv_label_set_text(s_motion_state_label, text);
    }

    bsp_display_unlock();
}

void app_ui_set_motion_debug_event(const char *event, const char *reaction)
{
    if (bsp_display_lock(50) != ESP_OK) {
        return;
    }

    if (s_motion_event_label != NULL) {
        lv_label_set_text(s_motion_event_label, event != NULL ? event : "none");
    }
    if (s_motion_reaction_label != NULL) {
        lv_label_set_text(s_motion_reaction_label, reaction != NULL ? reaction : "none");
    }

    bsp_display_unlock();
}

static void set_page_visible(lv_obj_t *page, bool visible)
{
    if (page == NULL) {
        return;
    }
    if (visible) {
        lv_obj_remove_flag(page, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_add_flag(page, LV_OBJ_FLAG_HIDDEN);
    }
}

static void switch_page(void)
{
    if (s_current_page == UI_PAGE_POMODORO) {
        return;
    }

    if (s_current_page == UI_PAGE_FACE) {
        s_current_page = UI_PAGE_SCHEDULE;
    } else {
        s_current_page = UI_PAGE_FACE;
    }
    set_page_visible(s_face_page, s_current_page == UI_PAGE_FACE);
    set_page_visible(s_motion_page, s_current_page == UI_PAGE_MOTION);
    set_page_visible(s_schedule_page, s_current_page == UI_PAGE_SCHEDULE);
    set_page_visible(s_pomodoro_page, false);
}

static void touch_event_cb(lv_event_t *event)
{
    lv_event_code_t code = lv_event_get_code(event);
    if (code != LV_EVENT_CLICKED && code != LV_EVENT_GESTURE) {
        return;
    }
    lv_event_stop_bubbling(event);
    switch_page();
}

static void talk_event_cb(lv_event_t *event)
{
    lv_event_stop_bubbling(event);
    app_voice_request_conversation();
}

static void update_mode_labels(void)
{
    const char *text = app_voice_long_conversation_enabled() ? "Long: ON" : "Long: OFF";
    if (s_mode_label_face != NULL) {
        lv_label_set_text(s_mode_label_face, text);
    }
    if (s_mode_label_schedule != NULL) {
        lv_label_set_text(s_mode_label_schedule, text);
    }
}

static void mode_event_cb(lv_event_t *event)
{
    lv_event_stop_bubbling(event);
    app_voice_toggle_long_conversation();
    update_mode_labels();
}

void app_ui_refresh_long_mode(void)
{
    if (bsp_display_lock(100) == ESP_OK) {
        update_mode_labels();
        bsp_display_unlock();
    }
}

bool app_ui_start_pomodoro(void)
{
    if (bsp_display_lock(1000) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for pomodoro start");
        return false;
    }

    s_pomodoro_remaining_sec = 25 * 60;
    s_pomodoro_phase = POMODORO_FOCUS;
    s_current_page = UI_PAGE_POMODORO;
    set_page_visible(s_face_page, false);
    set_page_visible(s_motion_page, false);
    set_page_visible(s_schedule_page, false);
    set_page_visible(s_pomodoro_page, true);
    update_pomodoro_page();
    if (s_pomodoro_timer != NULL) {
        lv_timer_reset(s_pomodoro_timer);
    }

    bsp_display_unlock();
    return true;
}

static void stop_pomodoro_unlocked(void)
{
    s_pomodoro_phase = POMODORO_IDLE;
    s_pomodoro_remaining_sec = 0;
    s_current_page = UI_PAGE_FACE;
    set_page_visible(s_pomodoro_page, false);
    set_page_visible(s_motion_page, false);
    set_page_visible(s_schedule_page, false);
    set_page_visible(s_face_page, true);
    update_home_time();
}

void app_ui_stop_pomodoro(void)
{
    if (bsp_display_lock(1000) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for pomodoro stop");
        return;
    }
    stop_pomodoro_unlocked();
    bsp_display_unlock();
}

void app_ui_show_schedule_page(void)
{
    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for schedule page");
        return;
    }

    s_current_page = UI_PAGE_SCHEDULE;
    set_page_visible(s_face_page, false);
    set_page_visible(s_motion_page, false);
    set_page_visible(s_schedule_page, true);
    set_page_visible(s_pomodoro_page, false);
    update_schedule_page();

    bsp_display_unlock();
}

void app_ui_add_schedule(const char *item, const char *deadline)
{
    if (bsp_display_lock(1000) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for schedule add");
        return;
    }

    if (s_schedule_count >= SCHEDULE_MAX_ITEMS) {
        bool removed_completed = false;
        for (uint8_t i = 0; i < s_schedule_count; i++) {
            if (s_schedule_completed[i]) {
                remove_schedule_at(i);
                removed_completed = true;
                break;
            }
        }
        if (!removed_completed) {
            remove_schedule_at(0);
        }
    }

    uint8_t added_index = s_schedule_count;
    char deadline_display[SCHEDULE_DEADLINE_LEN] = "";
    time_t due_ts = 0;
    if (schedule_parse_due_time(deadline, &due_ts, deadline_display, sizeof(deadline_display))) {
        copy_or_default(s_schedule_deadlines[s_schedule_count], sizeof(s_schedule_deadlines[s_schedule_count]),
                        deadline_display, "");
    } else {
        copy_or_default(s_schedule_deadlines[s_schedule_count], sizeof(s_schedule_deadlines[s_schedule_count]),
                        deadline, "");
    }
    s_schedule_ids[added_index][0] = '\0';
    copy_or_default(s_schedule_items[added_index], sizeof(s_schedule_items[added_index]), item, "Untitled");
    s_schedule_due_ts[added_index] = due_ts;
    s_schedule_completed[added_index] = false;
    s_schedule_completed_ts[added_index] = 0;
    s_schedule_reminded[added_index] = false;
    s_schedule_count++;
    ESP_LOGI(TAG, "Schedule added: item=%s deadline=%s count=%u",
             s_schedule_items[s_schedule_count - 1], s_schedule_deadlines[s_schedule_count - 1],
             (unsigned int)s_schedule_count);

    s_current_page = UI_PAGE_SCHEDULE;
    set_page_visible(s_face_page, false);
    set_page_visible(s_motion_page, false);
    set_page_visible(s_schedule_page, true);
    set_page_visible(s_pomodoro_page, false);
    update_schedule_page();
    bsp_display_unlock();
    notify_schedule_event("SCHEDULE_ADDED", added_index);
}

void app_ui_set_schedule_items(const app_ui_schedule_sync_item_t *items, uint8_t count)
{
    if (items == NULL && count > 0) {
        return;
    }
    if (bsp_display_lock(1000) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for schedule sync");
        return;
    }

    char old_ids[SCHEDULE_MAX_ITEMS][SCHEDULE_ID_LEN];
    time_t old_due_ts[SCHEDULE_MAX_ITEMS];
    time_t old_completed_ts[SCHEDULE_MAX_ITEMS];
    bool old_reminded[SCHEDULE_MAX_ITEMS];
    uint8_t old_count = s_schedule_count;
    memcpy(old_ids, s_schedule_ids, sizeof(old_ids));
    memcpy(old_due_ts, s_schedule_due_ts, sizeof(old_due_ts));
    memcpy(old_completed_ts, s_schedule_completed_ts, sizeof(old_completed_ts));
    memcpy(old_reminded, s_schedule_reminded, sizeof(old_reminded));

    s_schedule_count = count < SCHEDULE_MAX_ITEMS ? count : SCHEDULE_MAX_ITEMS;
    for (uint8_t i = 0; i < SCHEDULE_MAX_ITEMS; i++) {
        if (i < s_schedule_count) {
            copy_or_default(s_schedule_ids[i], sizeof(s_schedule_ids[i]), items[i].id, "");
            copy_or_default(s_schedule_items[i], sizeof(s_schedule_items[i]), items[i].title, "Untitled");
            copy_or_default(s_schedule_deadlines[i], sizeof(s_schedule_deadlines[i]), items[i].display_time, "");
            s_schedule_due_ts[i] = items[i].due_ts;
            s_schedule_completed[i] = items[i].completed;
            s_schedule_completed_ts[i] = items[i].completed ? items[i].completed_ts : 0;
            s_schedule_reminded[i] = false;
            for (uint8_t old = 0; old < old_count; old++) {
                if (s_schedule_ids[i][0] != '\0' && strcmp(s_schedule_ids[i], old_ids[old]) == 0 &&
                    s_schedule_due_ts[i] == old_due_ts[old]) {
                    s_schedule_reminded[i] = old_reminded[old];
                    if (s_schedule_completed[i] && s_schedule_completed_ts[i] <= 0) {
                        s_schedule_completed_ts[i] = old_completed_ts[old];
                    }
                    break;
                }
            }
            if (s_schedule_completed[i] && s_schedule_completed_ts[i] <= 0) {
                s_schedule_completed_ts[i] = time(NULL);
            }
            if (s_schedule_deadlines[i][0] == '\0' && s_schedule_due_ts[i] > 0) {
                struct tm due_tm = { 0 };
                localtime_r(&s_schedule_due_ts[i], &due_tm);
                snprintf(s_schedule_deadlines[i], sizeof(s_schedule_deadlines[i]), "%02d-%02d/%02d:%02d",
                         due_tm.tm_mon + 1, due_tm.tm_mday, due_tm.tm_hour, due_tm.tm_min);
            }
        } else {
            s_schedule_ids[i][0] = '\0';
            s_schedule_items[i][0] = '\0';
            s_schedule_deadlines[i][0] = '\0';
            s_schedule_due_ts[i] = 0;
            s_schedule_completed[i] = false;
            s_schedule_completed_ts[i] = 0;
            s_schedule_reminded[i] = false;
        }
    }

    bool has_completed = false;
    for (uint8_t i = 0; i < s_schedule_count; i++) {
        has_completed = has_completed || s_schedule_completed[i];
    }
    if (has_completed) {
        schedule_ensure_cleanup_timer();
    } else if (s_schedule_cleanup_timer != NULL) {
        lv_timer_delete(s_schedule_cleanup_timer);
        s_schedule_cleanup_timer = NULL;
    }

    update_schedule_page();
    bsp_display_unlock();
}

uint8_t app_ui_get_schedule_items(app_ui_schedule_sync_item_t *items, uint8_t max_count)
{
    if (items == NULL || max_count == 0) {
        return 0;
    }
    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for schedule report");
        return 0;
    }

    uint8_t count = s_schedule_count < max_count ? s_schedule_count : max_count;
    for (uint8_t i = 0; i < count; i++) {
        items[i].id = s_schedule_ids[i];
        items[i].title = s_schedule_items[i];
        items[i].display_time = s_schedule_deadlines[i];
        items[i].due_ts = s_schedule_due_ts[i];
        items[i].completed = s_schedule_completed[i];
        items[i].completed_ts = s_schedule_completed_ts[i];
    }

    bsp_display_unlock();
    return count;
}

void app_ui_set_schedule_event_callback(app_ui_schedule_event_cb_t callback)
{
    s_schedule_event_cb = callback;
}

void app_ui_set_growth_days(uint32_t days)
{
    s_growth_days = days > 0 ? days : 1;
    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for growth days");
        return;
    }
    update_growth_days_label();
    bsp_display_unlock();
}

static void pomodoro_exit_event_cb(lv_event_t *event)
{
    lv_event_stop_bubbling(event);
    stop_pomodoro_unlocked();
}

static void make_talk_button(lv_obj_t *parent)
{
    lv_obj_t *button = lv_button_create(parent);
    lv_obj_set_size(button, 160, 43);
    lv_obj_align(button, LV_ALIGN_BOTTOM_MID, -115, -45);
    lv_obj_set_style_bg_color(button, lv_color_hex(0xc9ee76), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(button, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_color(button, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_border_width(button, 2, LV_PART_MAIN);
    lv_obj_set_style_radius(button, 22, LV_PART_MAIN);
    lv_obj_remove_flag(button, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_add_event_cb(button, talk_event_cb, LV_EVENT_CLICKED, NULL);

    lv_obj_t *label = lv_label_create(button);
    lv_label_set_text(label, "Talk");
    lv_obj_remove_flag(label, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_20, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 2);
}

static void make_mode_button(lv_obj_t *parent, lv_obj_t **label_out)
{
    lv_obj_t *button = lv_button_create(parent);
    lv_obj_set_size(button, 160, 43);
    lv_obj_align(button, LV_ALIGN_BOTTOM_MID, 105, -45);
    lv_obj_set_style_bg_color(button, lv_color_hex(0x86dfff), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(button, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_border_color(button, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_border_width(button, 2, LV_PART_MAIN);
    lv_obj_set_style_radius(button, 22, LV_PART_MAIN);
    lv_obj_remove_flag(button, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_add_event_cb(button, mode_event_cb, LV_EVENT_CLICKED, NULL);

    lv_obj_t *label = lv_label_create(button);
    lv_label_set_text(label, "Long: OFF");
    lv_obj_remove_flag(label, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_20, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 2);

    if (label_out != NULL) {
        *label_out = label;
    }
}

void app_ui_init(void)
{
    extern const uint8_t _binary_NotoSansSC_GB2312_ttf_start[];
    extern const uint8_t _binary_NotoSansSC_GB2312_ttf_end[];
    extern const uint8_t _binary_Inkfree_ToDo_ttf_start[];
    extern const uint8_t _binary_Inkfree_ToDo_ttf_end[];

    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x0b1712), LV_PART_MAIN);
    lv_obj_set_style_text_color(screen, lv_color_hex(0xeaf8ee), LV_PART_MAIN);

    s_face_page = lv_obj_create(screen);
    lv_obj_remove_style_all(s_face_page);
    lv_obj_set_size(s_face_page, UI_SCREEN_W, UI_SCREEN_H);
    lv_obj_set_style_bg_opa(s_face_page, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_face_page, lv_color_hex(0x050706), LV_PART_MAIN);
    enable_page_switch_target(s_face_page);

    lv_obj_t *face_bg = lv_image_create(s_face_page);
    lv_image_set_src(face_bg, &home_static_bg_img);
    lv_obj_align(face_bg, LV_ALIGN_TOP_LEFT, 0, 0);
    lv_obj_move_background(face_bg);
    enable_page_switch_target(face_bg);

    s_home_time_label = lv_label_create(s_face_page);
    lv_label_set_text(s_home_time_label, "--:--  Syncing time");
    lv_obj_set_style_text_font(s_home_time_label, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_home_time_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_width(s_home_time_label, 410);
    lv_obj_set_style_text_align(s_home_time_label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(s_home_time_label, LV_ALIGN_TOP_MID, 0, 22);

    s_home_date_label = lv_label_create(s_face_page);
    lv_label_set_text(s_home_date_label, "");
    lv_obj_set_style_text_font(s_home_date_label, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_home_date_label, lv_color_hex(0xcbd8d0), LV_PART_MAIN);
    lv_obj_align(s_home_date_label, LV_ALIGN_TOP_MID, 0, 17);

    make_cat_art(s_face_page);

    s_face_mood_label = lv_label_create(s_face_page);
    lv_label_set_text(s_face_mood_label, "");
    lv_obj_add_flag(s_face_mood_label, LV_OBJ_FLAG_HIDDEN);

    lv_obj_t *humidity_card = make_metric_card(s_face_page, 340, 104, "Humidity",
                                               lv_color_hex(0x86dfff), 0, &s_soil_label);
    s_soil_bar = make_bar(humidity_card, 46, lv_color_hex(0x86dfff));
    lv_obj_align(s_soil_bar, LV_ALIGN_TOP_LEFT, 218, 46);

    lv_obj_t *light_card = make_metric_card(s_face_page, 340, 196, "Light",
                                            lv_color_hex(0xffe46e), 1, &s_light_label);
    s_light_unit_label = lv_label_create(light_card);
    lv_label_set_text(s_light_unit_label, "lux");
    lv_obj_set_style_text_font(s_light_unit_label, &lv_font_montserrat_26, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_light_unit_label, lv_color_hex(0xffe46e), LV_PART_MAIN);
    lv_obj_align(s_light_unit_label, LV_ALIGN_TOP_LEFT, 170, 38);
    s_light_bar = make_bar(light_card, 46, lv_color_hex(0xffe46e));
    lv_obj_align(s_light_bar, LV_ALIGN_TOP_LEFT, 218, 46);

    lv_obj_t *mood_card = make_metric_card(s_face_page, 340, 288, "Mood",
                                           lv_color_hex(0xff9baa), 2, &s_mood_label);
    lv_label_set_text(s_mood_label, "happy");
    s_mood_bar = make_bar(mood_card, 46, lv_color_hex(0xff9baa));
    lv_obj_align(s_mood_bar, LV_ALIGN_TOP_LEFT, 218, 46);

    s_voice_label = lv_label_create(s_face_page);
    lv_label_set_text(s_voice_label, "Wake: XiaoMai");
    lv_obj_set_style_text_font(s_voice_label, &lv_font_montserrat_16, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_voice_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_align(s_voice_label, LV_ALIGN_BOTTOM_LEFT, 72, -14);

    lv_obj_t *network_group = lv_obj_create(s_face_page);
    lv_obj_remove_style_all(network_group);
    lv_obj_set_size(network_group, 178, 30);
    lv_obj_align(network_group, LV_ALIGN_BOTTOM_RIGHT, -16, -8);
    lv_obj_remove_flag(network_group, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_clear_flag(network_group, LV_OBJ_FLAG_CLICKABLE);

    s_network_icon = lv_label_create(network_group);
    lv_label_set_text(s_network_icon, LV_SYMBOL_WIFI);
    lv_obj_set_style_text_font(s_network_icon, &lv_font_montserrat_22, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_network_icon, lv_color_hex(0x70daff), LV_PART_MAIN);
    lv_obj_align(s_network_icon, LV_ALIGN_LEFT_MID, 0, 1);

    s_network_label = lv_label_create(network_group);
    lv_label_set_text(s_network_label, "Wi-Fi: starting");
    lv_obj_set_style_text_font(s_network_label, &lv_font_montserrat_16, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_network_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_width(s_network_label, 146);
    lv_obj_set_style_text_align(s_network_label, LV_TEXT_ALIGN_RIGHT, LV_PART_MAIN);
    lv_obj_align(s_network_label, LV_ALIGN_RIGHT_MID, 0, 0);

    make_talk_button(s_face_page);
    make_mode_button(s_face_page, &s_mode_label_face);

    s_dialog_label = lv_label_create(s_face_page);
    lv_label_set_text(s_dialog_label, "");
    lv_obj_add_flag(s_dialog_label, LV_OBJ_FLAG_HIDDEN);

#if APP_UI_SHOW_MOTION_DEBUG_PAGE
    make_motion_debug_page(screen);
#endif

    s_schedule_page = lv_obj_create(screen);
    s_schedule_font = lv_tiny_ttf_create_data_ex(
        _binary_NotoSansSC_GB2312_ttf_start,
        (size_t)(_binary_NotoSansSC_GB2312_ttf_end - _binary_NotoSansSC_GB2312_ttf_start),
        24, LV_FONT_KERNING_NONE, 8);
    s_schedule_time_font = lv_tiny_ttf_create_data_ex(
        _binary_NotoSansSC_GB2312_ttf_start,
        (size_t)(_binary_NotoSansSC_GB2312_ttf_end - _binary_NotoSansSC_GB2312_ttf_start),
        18, LV_FONT_KERNING_NONE, 4);
    s_schedule_title_font = lv_tiny_ttf_create_data_ex(
        _binary_NotoSansSC_GB2312_ttf_start,
        (size_t)(_binary_NotoSansSC_GB2312_ttf_end - _binary_NotoSansSC_GB2312_ttf_start),
        34, LV_FONT_KERNING_NONE, 2);
    s_todo_title_font = lv_tiny_ttf_create_data_ex(
        _binary_Inkfree_ToDo_ttf_start,
        (size_t)(_binary_Inkfree_ToDo_ttf_end - _binary_Inkfree_ToDo_ttf_start),
        54, LV_FONT_KERNING_NORMAL, 4);
    s_todo_header_font = lv_tiny_ttf_create_data_ex(
        _binary_Inkfree_ToDo_ttf_start,
        (size_t)(_binary_Inkfree_ToDo_ttf_end - _binary_Inkfree_ToDo_ttf_start),
        30, LV_FONT_KERNING_NORMAL, 2);
    if (s_schedule_font == NULL) {
        ESP_LOGW(TAG, "Failed to load GB2312 schedule font; using LVGL fallback");
    }
    if (s_schedule_time_font == NULL) {
        ESP_LOGW(TAG, "Failed to load GB2312 schedule time font; using schedule font");
    }
    lv_obj_remove_style_all(s_schedule_page);
    lv_obj_set_size(s_schedule_page, UI_SCREEN_W, UI_SCREEN_H);
    lv_obj_set_style_bg_opa(s_schedule_page, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_schedule_page, lv_color_hex(0x0b1712), LV_PART_MAIN);
    lv_obj_set_style_text_color(s_schedule_page, lv_color_hex(0xf4fff7), LV_PART_MAIN);
    enable_page_switch_target(s_schedule_page);
    lv_obj_add_flag(s_schedule_page, LV_OBJ_FLAG_HIDDEN);

    lv_obj_t *schedule_bg = lv_image_create(s_schedule_page);
    lv_image_set_src(schedule_bg, &schedule_static_bg_img);
    lv_obj_align(schedule_bg, LV_ALIGN_TOP_LEFT, 0, 0);
    lv_obj_move_background(schedule_bg);
    enable_page_switch_target(schedule_bg);

    lv_obj_t *schedule_face_layer = lv_obj_create(s_schedule_page);
    lv_obj_remove_style_all(schedule_face_layer);
    lv_obj_set_size(schedule_face_layer, 92, 48);
    lv_obj_align(schedule_face_layer, LV_ALIGN_TOP_LEFT, 102, 188);
    lv_obj_remove_flag(schedule_face_layer, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_clear_flag(schedule_face_layer, LV_OBJ_FLAG_CLICKABLE);

    s_schedule_face_eye_left = lv_obj_create(schedule_face_layer);
    lv_obj_remove_style_all(s_schedule_face_eye_left);
    lv_obj_set_size(s_schedule_face_eye_left, 8, 10);
    lv_obj_align(s_schedule_face_eye_left, LV_ALIGN_TOP_LEFT, 26, 16);
    lv_obj_set_style_radius(s_schedule_face_eye_left, 999, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_schedule_face_eye_left, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_schedule_face_eye_left, lv_color_hex(0x151515), LV_PART_MAIN);

    s_schedule_face_eye_right = lv_obj_create(schedule_face_layer);
    lv_obj_remove_style_all(s_schedule_face_eye_right);
    lv_obj_set_size(s_schedule_face_eye_right, 8, 10);
    lv_obj_align(s_schedule_face_eye_right, LV_ALIGN_TOP_LEFT, 58, 16);
    lv_obj_set_style_radius(s_schedule_face_eye_right, 999, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_schedule_face_eye_right, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_schedule_face_eye_right, lv_color_hex(0x151515), LV_PART_MAIN);

    lv_obj_t *schedule_cheek_left = lv_obj_create(schedule_face_layer);
    lv_obj_remove_style_all(schedule_cheek_left);
    lv_obj_set_size(schedule_cheek_left, 14, 7);
    lv_obj_align(schedule_cheek_left, LV_ALIGN_TOP_LEFT, 10, 27);
    lv_obj_set_style_radius(schedule_cheek_left, 999, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(schedule_cheek_left, LV_OPA_70, LV_PART_MAIN);
    lv_obj_set_style_bg_color(schedule_cheek_left, lv_color_hex(0xf5a3b3), LV_PART_MAIN);

    lv_obj_t *schedule_cheek_right = lv_obj_create(schedule_face_layer);
    lv_obj_remove_style_all(schedule_cheek_right);
    lv_obj_set_size(schedule_cheek_right, 14, 7);
    lv_obj_align(schedule_cheek_right, LV_ALIGN_TOP_LEFT, 68, 27);
    lv_obj_set_style_radius(schedule_cheek_right, 999, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(schedule_cheek_right, LV_OPA_70, LV_PART_MAIN);
    lv_obj_set_style_bg_color(schedule_cheek_right, lv_color_hex(0xf5a3b3), LV_PART_MAIN);

    s_schedule_face_blink_left = lv_obj_create(schedule_face_layer);
    lv_obj_remove_style_all(s_schedule_face_blink_left);
    lv_obj_set_size(s_schedule_face_blink_left, 13, 3);
    lv_obj_align(s_schedule_face_blink_left, LV_ALIGN_TOP_LEFT, 23, 21);
    lv_obj_set_style_radius(s_schedule_face_blink_left, 2, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_schedule_face_blink_left, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_schedule_face_blink_left, lv_color_hex(0x151515), LV_PART_MAIN);
    lv_obj_add_flag(s_schedule_face_blink_left, LV_OBJ_FLAG_HIDDEN);

    s_schedule_face_blink_right = lv_obj_create(schedule_face_layer);
    lv_obj_remove_style_all(s_schedule_face_blink_right);
    lv_obj_set_size(s_schedule_face_blink_right, 13, 3);
    lv_obj_align(s_schedule_face_blink_right, LV_ALIGN_TOP_LEFT, 55, 21);
    lv_obj_set_style_radius(s_schedule_face_blink_right, 2, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_schedule_face_blink_right, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_schedule_face_blink_right, lv_color_hex(0x151515), LV_PART_MAIN);
    lv_obj_add_flag(s_schedule_face_blink_right, LV_OBJ_FLAG_HIDDEN);

    s_schedule_face_mouth = lv_label_create(schedule_face_layer);
    lv_label_set_text(s_schedule_face_mouth, "U");
    lv_obj_set_style_text_font(s_schedule_face_mouth, &lv_font_montserrat_18, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_schedule_face_mouth, lv_color_hex(0x151515), LV_PART_MAIN);
    lv_obj_align(s_schedule_face_mouth, LV_ALIGN_TOP_MID, 0, 22);

    lv_obj_t *schedule_title = lv_label_create(s_schedule_page);
    lv_label_set_text(schedule_title, "To Do List");
    lv_obj_set_style_text_font(schedule_title,
                               s_todo_title_font != NULL ? s_todo_title_font : &lv_font_montserrat_26,
                               LV_PART_MAIN);
    lv_obj_set_style_text_color(schedule_title, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_width(schedule_title, 310);
    lv_obj_set_style_text_align(schedule_title, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(schedule_title, LV_ALIGN_TOP_LEFT, 296, 52);

#if 0
    lv_obj_t *memory_card = lv_obj_create(s_schedule_page);
    lv_obj_set_size(memory_card, 260, 372);
    lv_obj_align(memory_card, LV_ALIGN_TOP_LEFT, 18, 84);
    lv_obj_set_style_radius(memory_card, 28, LV_PART_MAIN);
    lv_obj_set_style_bg_color(memory_card, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_set_style_border_color(memory_card, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_border_width(memory_card, 4, LV_PART_MAIN);
    lv_obj_set_style_pad_all(memory_card, 0, LV_PART_MAIN);
    lv_obj_remove_flag(memory_card, LV_OBJ_FLAG_SCROLLABLE);

    for (int32_t x = 18; x < 232; x += 20) {
        lv_obj_t *dash_top = make_leaf(memory_card, x, 13, 11, 2, lv_color_hex(0xffffff));
        lv_obj_set_style_radius(dash_top, 1, LV_PART_MAIN);
        lv_obj_t *dash_bottom = make_leaf(memory_card, x, 351, 11, 2, lv_color_hex(0xffffff));
        lv_obj_set_style_radius(dash_bottom, 1, LV_PART_MAIN);
    }
    for (int32_t y = 25; y < 344; y += 20) {
        make_leaf(memory_card, 13, y, 2, 11, lv_color_hex(0xffffff));
        make_leaf(memory_card, 242, y, 2, 11, lv_color_hex(0xffffff));
    }

    make_leaf(memory_card, 77, 67, 54, 38, lv_color_hex(0x5aac43));
    make_leaf(memory_card, 105, 41, 45, 65, lv_color_hex(0x8ac35c));
    make_leaf(memory_card, 137, 64, 54, 40, lv_color_hex(0x70b34e));
    make_leaf(memory_card, 103, 78, 23, 3, lv_color_hex(0xd7efc8));
    make_leaf(memory_card, 126, 54, 3, 42, lv_color_hex(0xd7efc8));
    make_leaf(memory_card, 149, 76, 25, 3, lv_color_hex(0xd7efc8));
    make_leaf(memory_card, 93, 93, 86, 17, lv_color_hex(0xf3f1eb));
    lv_obj_t *memory_pot = make_leaf(memory_card, 97, 104, 78, 63, lv_color_hex(0xf3f1eb));
    lv_obj_set_style_radius(memory_pot, 16, LV_PART_MAIN);
    make_leaf(memory_pot, 20, 22, 6, 8, lv_color_hex(0x161616));
    make_leaf(memory_pot, 51, 22, 6, 8, lv_color_hex(0x161616));
    make_leaf(memory_pot, 8, 35, 12, 7, lv_color_hex(0xf3a5ad));
    make_leaf(memory_pot, 58, 35, 12, 7, lv_color_hex(0xf3a5ad));
    lv_obj_t *memory_mouth = lv_label_create(memory_pot);
    lv_label_set_text(memory_mouth, "U");
    lv_obj_set_style_text_font(memory_mouth, &lv_font_montserrat_20, LV_PART_MAIN);
    lv_obj_set_style_text_color(memory_mouth, lv_color_hex(0x161616), LV_PART_MAIN);
    lv_obj_align(memory_mouth, LV_ALIGN_CENTER, 0, 10);
    lv_obj_t *memory_heart = lv_obj_create(memory_card);
    lv_obj_remove_style_all(memory_heart);
    lv_obj_set_size(memory_heart, 38, 38);
    lv_obj_align(memory_heart, LV_ALIGN_TOP_RIGHT, -38, 88);
    make_leaf(memory_heart, 3, 3, 15, 15, lv_color_hex(0xf49aaa));
    make_leaf(memory_heart, 16, 3, 15, 15, lv_color_hex(0xf49aaa));
    make_leaf(memory_heart, 8, 12, 20, 22, lv_color_hex(0xf49aaa));

    lv_obj_t *meet_label = lv_label_create(memory_card);
    lv_label_set_text(meet_label, "认识小麦已经");
    lv_obj_set_style_text_font(meet_label, schedule_font(), LV_PART_MAIN);
    lv_obj_set_style_text_color(meet_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_align(meet_label, LV_ALIGN_TOP_MID, 0, 180);
    lv_obj_t *days_label = lv_label_create(memory_card);
    lv_label_set_text(days_label, "1 天");
    lv_obj_set_style_text_font(days_label,
                               s_schedule_title_font != NULL ? s_schedule_title_font : &lv_font_montserrat_26,
                               LV_PART_MAIN);
    lv_obj_set_style_text_color(days_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_align(days_label, LV_ALIGN_TOP_MID, 0, 232);

    lv_obj_t *hill = make_local_line(memory_card, 7, 305, 246, 40, s_schedule_hill_pts,
                                     sizeof(s_schedule_hill_pts) / sizeof(s_schedule_hill_pts[0]),
                                     lv_color_hex(0xffffff), 3);
    (void)hill;
    make_leaf(memory_card, 48, 304, 3, 28, lv_color_hex(0x82ba4d));
    make_leaf(memory_card, 36, 306, 14, 8, lv_color_hex(0x82ba4d));
    make_leaf(memory_card, 49, 298, 15, 9, lv_color_hex(0x9bc95d));
    make_leaf(memory_card, 86, 309, 3, 19, lv_color_hex(0x82ba4d));
    make_leaf(memory_card, 76, 308, 12, 7, lv_color_hex(0x82ba4d));
    make_leaf(memory_card, 88, 303, 12, 7, lv_color_hex(0x9bc95d));
    make_leaf(memory_card, 187, 298, 3, 34, lv_color_hex(0x82ba4d));
    make_leaf(memory_card, 175, 306, 13, 8, lv_color_hex(0x82ba4d));
    make_leaf(memory_card, 190, 302, 13, 8, lv_color_hex(0x9bc95d));
    make_leaf(memory_card, 177, 287, 15, 15, lv_color_hex(0xf49aaa));
    make_leaf(memory_card, 188, 287, 15, 15, lv_color_hex(0xffafbd));
    make_leaf(memory_card, 183, 296, 15, 15, lv_color_hex(0xf28aa0));
    make_leaf(memory_card, 188, 296, 6, 6, lv_color_hex(0xf5ce55));
#endif

    lv_obj_t *memory_text = lv_label_create(s_schedule_page);
    lv_label_set_text(memory_text, "认识小麦已经");
    lv_obj_set_width(memory_text, 244);
    lv_obj_set_style_text_font(memory_text, schedule_font(), LV_PART_MAIN);
    lv_obj_set_style_text_color(memory_text, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_text_align(memory_text, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(memory_text, LV_ALIGN_TOP_LEFT, 28, 286);

    s_memory_days_label = lv_label_create(s_schedule_page);
    lv_label_set_text(s_memory_days_label, "1 天");
    lv_obj_set_width(s_memory_days_label, 244);
    lv_obj_set_style_text_font(s_memory_days_label,
                               s_schedule_title_font != NULL ? s_schedule_title_font : &lv_font_montserrat_26,
                               LV_PART_MAIN);
    lv_obj_set_style_text_color(s_memory_days_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_text_align(s_memory_days_label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(s_memory_days_label, LV_ALIGN_TOP_LEFT, 28, 326);
    update_growth_days_label();

    lv_obj_t *schedule_panel = lv_obj_create(s_schedule_page);
    lv_obj_set_size(schedule_panel, 476, 318);
    lv_obj_align(schedule_panel, LV_ALIGN_TOP_LEFT, 302, 138);
    lv_obj_set_style_radius(schedule_panel, 18, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(schedule_panel, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_bg_color(schedule_panel, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_set_style_border_color(schedule_panel, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_border_width(schedule_panel, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(schedule_panel, 0, LV_PART_MAIN);
    lv_obj_remove_flag(schedule_panel, LV_OBJ_FLAG_SCROLLABLE);
    enable_page_switch_target(schedule_panel);

    lv_obj_t *time_header = lv_label_create(schedule_panel);
    lv_label_set_text(time_header, "Time");
    lv_obj_set_width(time_header, 145);
    lv_obj_set_style_text_font(time_header,
                               s_todo_header_font != NULL ? s_todo_header_font : &lv_font_montserrat_22,
                               LV_PART_MAIN);
    lv_obj_set_style_text_color(time_header, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_text_align(time_header, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(time_header, LV_ALIGN_TOP_LEFT, 0, 10);
    lv_obj_t *task_header = lv_label_create(schedule_panel);
    lv_label_set_text(task_header, "Task");
    lv_obj_set_width(task_header, 331);
    lv_obj_set_style_text_font(task_header,
                               s_todo_header_font != NULL ? s_todo_header_font : &lv_font_montserrat_22,
                               LV_PART_MAIN);
    lv_obj_set_style_text_color(task_header, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_text_align(task_header, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(task_header, LV_ALIGN_TOP_LEFT, 145, 10);

    lv_obj_t *header_rule = lv_obj_create(schedule_panel);
    lv_obj_remove_style_all(header_rule);
    lv_obj_set_size(header_rule, 476, 2);
    lv_obj_align(header_rule, LV_ALIGN_TOP_LEFT, 0, 48);
    lv_obj_set_style_bg_opa(header_rule, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(header_rule, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_add_flag(header_rule, LV_OBJ_FLAG_HIDDEN);
    lv_obj_t *divider = lv_obj_create(schedule_panel);
    lv_obj_remove_style_all(divider);
    lv_obj_set_size(divider, 2, 316);
    lv_obj_align(divider, LV_ALIGN_TOP_LEFT, 145, 0);
    lv_obj_set_style_bg_opa(divider, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(divider, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_add_flag(divider, LV_OBJ_FLAG_HIDDEN);

    s_schedule_empty_label = lv_label_create(schedule_panel);
    lv_label_set_text(s_schedule_empty_label, "");
    lv_obj_add_flag(s_schedule_empty_label, LV_OBJ_FLAG_HIDDEN);

    for (uint8_t i = 0; i < SCHEDULE_MAX_ITEMS; i++) {
        int32_t y = 64 + i * 66;
        lv_obj_t *row_rule = lv_obj_create(schedule_panel);
        lv_obj_remove_style_all(row_rule);
        lv_obj_set_size(row_rule, 476, 1);
        lv_obj_align(row_rule, LV_ALIGN_TOP_LEFT, 0, y + 55);
        lv_obj_set_style_bg_opa(row_rule, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_bg_color(row_rule, lv_color_hex(0xffffff), LV_PART_MAIN);
        lv_obj_add_flag(row_rule, LV_OBJ_FLAG_HIDDEN);

        s_schedule_item_labels[i] = lv_checkbox_create(schedule_panel);
        lv_obj_set_width(s_schedule_item_labels[i], 304);
        lv_obj_set_style_text_font(s_schedule_item_labels[i], schedule_font(), LV_PART_MAIN);
        lv_obj_set_style_text_color(s_schedule_item_labels[i], lv_color_hex(0xffffff), LV_PART_MAIN);
        lv_obj_set_style_border_color(s_schedule_item_labels[i], lv_color_hex(0xffffff), LV_PART_INDICATOR);
        lv_obj_set_style_border_width(s_schedule_item_labels[i], 3, LV_PART_INDICATOR);
        lv_obj_set_style_bg_color(s_schedule_item_labels[i], lv_color_hex(0x050706), LV_PART_INDICATOR);
        lv_obj_set_style_radius(s_schedule_item_labels[i], 3, LV_PART_INDICATOR);
        lv_obj_remove_flag(s_schedule_item_labels[i], LV_OBJ_FLAG_EVENT_BUBBLE);
        lv_obj_add_event_cb(s_schedule_item_labels[i], schedule_complete_event_cb,
                            LV_EVENT_VALUE_CHANGED, (void *)(uintptr_t)i);
        lv_obj_align(s_schedule_item_labels[i], LV_ALIGN_TOP_LEFT, 166, y + 5);

        s_schedule_deadline_labels[i] = lv_label_create(schedule_panel);
        lv_obj_set_width(s_schedule_deadline_labels[i], 134);
        lv_label_set_long_mode(s_schedule_deadline_labels[i], LV_LABEL_LONG_CLIP);
        lv_obj_set_style_text_font(s_schedule_deadline_labels[i],
                                   schedule_time_font(),
                                   LV_PART_MAIN);
        lv_obj_set_style_text_color(s_schedule_deadline_labels[i], lv_color_hex(0xffffff), LV_PART_MAIN);
        lv_obj_set_style_text_align(s_schedule_deadline_labels[i], LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
        lv_obj_align(s_schedule_deadline_labels[i], LV_ALIGN_TOP_LEFT, 6, y + 16);
    }
    update_schedule_page();

    lv_timer_create(time_timer_cb, 1000, NULL);

    s_pomodoro_page = lv_obj_create(screen);
    lv_obj_remove_style_all(s_pomodoro_page);
    lv_obj_set_size(s_pomodoro_page, UI_SCREEN_W, UI_SCREEN_H);
    lv_obj_set_style_bg_opa(s_pomodoro_page, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_pomodoro_page, lv_color_hex(0x0a0706), LV_PART_MAIN);
    lv_obj_set_style_text_color(s_pomodoro_page, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_add_flag(s_pomodoro_page, LV_OBJ_FLAG_HIDDEN);

    lv_obj_t *tomato = lv_obj_create(s_pomodoro_page);
    lv_obj_remove_style_all(tomato);
    lv_obj_set_size(tomato, 104, 94);
    lv_obj_align(tomato, LV_ALIGN_CENTER, 0, -112);
    lv_obj_set_style_radius(tomato, 999, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(tomato, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(tomato, lv_color_hex(0xe94338), LV_PART_MAIN);
    lv_obj_set_style_border_width(tomato, 0, LV_PART_MAIN);

    make_leaf(tomato, 27, -14, 22, 34, lv_color_hex(0x45b461));
    make_leaf(tomato, 43, -18, 22, 38, lv_color_hex(0x5fca74));
    make_leaf(tomato, 57, -14, 22, 34, lv_color_hex(0x45b461));
    make_leaf(tomato, 38, 36, 8, 12, lv_color_hex(0x5b1713));
    make_leaf(tomato, 61, 36, 8, 12, lv_color_hex(0x5b1713));

    s_pomodoro_time_label = lv_label_create(s_pomodoro_page);
    lv_label_set_text(s_pomodoro_time_label, "25:00");
    lv_obj_set_size(s_pomodoro_time_label, 320, 64);
    lv_obj_set_style_text_font(s_pomodoro_time_label, pomodoro_font(), LV_PART_MAIN);
    lv_obj_set_style_text_color(s_pomodoro_time_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_set_style_text_align(s_pomodoro_time_label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_set_style_text_letter_space(s_pomodoro_time_label, 3, LV_PART_MAIN);
    lv_obj_set_style_transform_scale(s_pomodoro_time_label, 256, LV_PART_MAIN);
    lv_obj_align(s_pomodoro_time_label, LV_ALIGN_CENTER, 0, 0);

    s_pomodoro_status_label = lv_label_create(s_pomodoro_page);
    lv_label_set_text(s_pomodoro_status_label, "Focus in progress");
    lv_obj_set_style_text_font(s_pomodoro_status_label, &lv_font_source_han_sans_sc_16_cjk, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_pomodoro_status_label, lv_color_hex(0xf6f0eb), LV_PART_MAIN);
    lv_obj_align(s_pomodoro_status_label, LV_ALIGN_CENTER, 0, 76);

    lv_obj_t *focus_rule = lv_obj_create(s_pomodoro_page);
    lv_obj_remove_style_all(focus_rule);
    lv_obj_set_size(focus_rule, 190, 2);
    lv_obj_align(focus_rule, LV_ALIGN_CENTER, 0, 120);
    lv_obj_set_style_bg_opa(focus_rule, LV_OPA_40, LV_PART_MAIN);
    lv_obj_set_style_bg_color(focus_rule, lv_color_hex(0xffffff), LV_PART_MAIN);

    lv_obj_t *exit_button = lv_button_create(s_pomodoro_page);
    lv_obj_set_size(exit_button, 148, 54);
    lv_obj_align(exit_button, LV_ALIGN_BOTTOM_MID, 0, -20);
    lv_obj_set_style_bg_color(exit_button, lv_color_hex(0x3a302e), LV_PART_MAIN);
    lv_obj_set_style_radius(exit_button, 8, LV_PART_MAIN);
    lv_obj_remove_flag(exit_button, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_add_event_cb(exit_button, pomodoro_exit_event_cb, LV_EVENT_CLICKED, NULL);

    lv_obj_t *exit_label = lv_label_create(exit_button);
    lv_label_set_text(exit_label, "Exit");
    lv_obj_set_style_text_font(exit_label, &lv_font_source_han_sans_sc_16_cjk, LV_PART_MAIN);
    lv_obj_set_style_text_color(exit_label, lv_color_hex(0xffffff), LV_PART_MAIN);
    lv_obj_center(exit_label);

    s_pomodoro_remaining_sec = 25 * 60;
    update_pomodoro_page();
    s_pomodoro_timer = lv_timer_create(pomodoro_timer_cb, 1000, NULL);
    s_idle_blink_timer = lv_timer_create(idle_blink_timer_cb, 4200, NULL);
    s_schedule_blink_timer = lv_timer_create(schedule_blink_timer_cb, 3600, NULL);
    update_mode_labels();
}

void app_ui_update(const app_plant_state_t *state)
{
    if (state == NULL) {
        return;
    }

    if (bsp_display_lock(100) != ESP_OK) {
        ESP_LOGW(TAG, "Failed to lock display for UI update");
        return;
    }

    char text[48];
    update_cat_art(state->mood);
    if (s_face_mood_label != NULL) {
        lv_label_set_text(s_face_mood_label, mood_to_text(state->mood));
    }
    snprintf(text, sizeof(text), "%u%%", state->soil_percent);
    lv_label_set_text(s_soil_label, text);
    lv_bar_set_value(s_soil_bar, state->soil_percent, LV_ANIM_ON);
    snprintf(text, sizeof(text), "%lu", (unsigned long)state->light_lux);
    lv_label_set_text(s_light_label, text);
    lv_bar_set_value(s_light_bar, state->light_percent, LV_ANIM_ON);
    lv_label_set_text(s_mood_label, mood_to_word(state->mood));
    if (s_mood_bar != NULL) {
        lv_bar_set_value(s_mood_bar, mood_to_percent(state->mood), LV_ANIM_ON);
    }
    bsp_display_unlock();
}

void app_ui_set_network_status(const char *status)
{
    if (status == NULL || s_network_label == NULL) {
        return;
    }

    if (bsp_display_lock(100) == ESP_OK) {
        lv_label_set_text(s_network_label, status);
        bsp_display_unlock();
    }
}

void app_ui_set_dialog_status(const char *status)
{
    if (status == NULL || s_dialog_label == NULL) {
        return;
    }

    if (bsp_display_lock(100) == ESP_OK) {
        lv_label_set_text(s_dialog_label, status);
        bsp_display_unlock();
    }
}

static void emoji_stage_scale_cb(void *obj, int32_t value)
{
    lv_obj_set_style_transform_scale((lv_obj_t *)obj, value, LV_PART_MAIN);
}

static const lv_image_dsc_t *emoji_image_for_id(const char *id)
{
    if (strcmp(id, "heart") == 0) return &emoji_heart_img;
    if (strcmp(id, "happy") == 0) return &emoji_happy_img;
    if (strcmp(id, "thirsty") == 0) return &emoji_thirsty_img;
    if (strcmp(id, "dark") == 0) return &emoji_dark_img;
    if (strcmp(id, "weak") == 0) return &emoji_weak_img;
    if (strcmp(id, "wave") == 0) return &emoji_wave_img;
    if (strcmp(id, "star") == 0) return &emoji_star_img;
    if (strcmp(id, "flower") == 0) return &emoji_flower_img;
    if (strcmp(id, "water") == 0) return &emoji_water_img;
    if (strcmp(id, "sun") == 0) return &emoji_sun_img;
    if (strcmp(id, "sleep") == 0) return &emoji_sleep_img;
    return &emoji_smile_img;
}

static void emoji_overlay_finish_cb(lv_timer_t *timer)
{
    if (s_emoji_overlay != NULL) {
        lv_obj_add_flag(s_emoji_overlay, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_style_opa(s_emoji_overlay, LV_OPA_COVER, LV_PART_MAIN);
        lv_obj_set_style_transform_scale(s_emoji_stage, 256, LV_PART_MAIN);
    }
    s_emoji_overlay_finish_timer = NULL;
    lv_timer_delete(timer);
}

static void emoji_overlay_hide_cb(lv_timer_t *timer)
{
    if (s_emoji_overlay != NULL) {
        lv_obj_fade_out(s_emoji_overlay, 360, 0);
        if (s_emoji_overlay_finish_timer != NULL) {
            lv_timer_delete(s_emoji_overlay_finish_timer);
        }
        s_emoji_overlay_finish_timer = lv_timer_create(emoji_overlay_finish_cb, 380, NULL);
        lv_timer_set_repeat_count(s_emoji_overlay_finish_timer, 1);
    }
    s_emoji_overlay_timer = NULL;
    lv_timer_delete(timer);
}

static void ensure_emoji_overlay(void)
{
    if (s_emoji_overlay != NULL) {
        return;
    }

    s_emoji_overlay = lv_obj_create(lv_screen_active());
    lv_obj_remove_style_all(s_emoji_overlay);
    lv_obj_set_size(s_emoji_overlay, UI_SCREEN_W, UI_SCREEN_H);
    lv_obj_set_style_bg_opa(s_emoji_overlay, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_emoji_overlay, lv_color_hex(0x050706), LV_PART_MAIN);
    lv_obj_clear_flag(s_emoji_overlay, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(s_emoji_overlay, LV_OBJ_FLAG_HIDDEN);

    s_emoji_stage = lv_obj_create(s_emoji_overlay);
    lv_obj_remove_style_all(s_emoji_stage);
    lv_obj_set_size(s_emoji_stage, 430, 340);
    lv_obj_set_style_bg_opa(s_emoji_stage, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_emoji_stage, lv_color_hex(0x102118), LV_PART_MAIN);
    lv_obj_set_style_radius(s_emoji_stage, 42, LV_PART_MAIN);
    lv_obj_set_style_border_width(s_emoji_stage, 3, LV_PART_MAIN);
    lv_obj_set_style_border_color(s_emoji_stage, lv_color_hex(0xd8ffe3), LV_PART_MAIN);
    lv_obj_align(s_emoji_stage, LV_ALIGN_CENTER, 0, 0);

    s_emoji_art = lv_image_create(s_emoji_stage);
    lv_image_set_src(s_emoji_art, &emoji_smile_img);
    lv_image_set_scale(s_emoji_art, 320);
    lv_obj_align(s_emoji_art, LV_ALIGN_TOP_MID, 0, 20);

    s_emoji_caption_label = lv_label_create(s_emoji_stage);
    lv_label_set_text(s_emoji_caption_label, "用户向你投递了一个表情。");
    lv_label_set_long_mode(s_emoji_caption_label, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(s_emoji_caption_label, 370);
    lv_obj_set_style_text_align(s_emoji_caption_label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_set_style_text_font(s_emoji_caption_label, schedule_time_font(), LV_PART_MAIN);
    lv_obj_set_style_text_color(s_emoji_caption_label, lv_color_hex(0xeefbf2), LV_PART_MAIN);
    lv_obj_align(s_emoji_caption_label, LV_ALIGN_BOTTOM_MID, 0, -34);
}

void app_ui_show_emoji(const char *emoji_id, uint32_t duration_ms)
{
    if (bsp_display_lock(200) != ESP_OK) {
        return;
    }

    ensure_emoji_overlay();
    const char *id = emoji_id != NULL ? emoji_id : "";
    uint32_t stage_hex = 0x102118;

    if (strcmp(id, "heart") == 0) {
        stage_hex = 0x301018;
    } else if (strcmp(id, "water") == 0 || strcmp(id, "thirsty") == 0) {
        stage_hex = 0x071d2a;
    } else if (strcmp(id, "sun") == 0 || strcmp(id, "happy") == 0) {
        stage_hex = 0x2d2408;
    } else if (strcmp(id, "dark") == 0 || strcmp(id, "sleep") == 0) {
        stage_hex = 0x11122c;
    } else if (strcmp(id, "weak") == 0) {
        stage_hex = 0x1d2026;
    } else if (strcmp(id, "flower") == 0) {
        stage_hex = 0x2b1023;
    } else if (strcmp(id, "star") == 0) {
        stage_hex = 0x2e2708;
    } else if (strcmp(id, "wave") == 0) {
        stage_hex = 0x102816;
    }

    lv_obj_set_style_bg_color(s_emoji_stage, lv_color_hex(stage_hex), LV_PART_MAIN);
    lv_image_set_src(s_emoji_art, emoji_image_for_id(id));
    lv_label_set_text(s_emoji_caption_label, "用户向你投递了一个表情。");
    lv_obj_clear_flag(s_emoji_overlay, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_foreground(s_emoji_overlay);
    lv_obj_set_style_opa(s_emoji_overlay, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_transform_scale(s_emoji_stage, 224, LV_PART_MAIN);

    lv_anim_t scale;
    lv_anim_init(&scale);
    lv_anim_set_var(&scale, s_emoji_stage);
    lv_anim_set_exec_cb(&scale, emoji_stage_scale_cb);
    lv_anim_set_values(&scale, 224, 268);
    lv_anim_set_duration(&scale, 180);
    lv_anim_set_playback_duration(&scale, 180);
    lv_anim_set_path_cb(&scale, lv_anim_path_ease_out);
    lv_anim_start(&scale);

    if (s_emoji_overlay_timer != NULL) {
        lv_timer_delete(s_emoji_overlay_timer);
    }
    if (s_emoji_overlay_finish_timer != NULL) {
        lv_timer_delete(s_emoji_overlay_finish_timer);
        s_emoji_overlay_finish_timer = NULL;
    }
    s_emoji_overlay_timer = lv_timer_create(emoji_overlay_hide_cb, duration_ms > 0 ? duration_ms : 2000, NULL);
    lv_timer_set_repeat_count(s_emoji_overlay_timer, 1);
    bsp_display_unlock();
}

static void remote_popup_finish_cb(lv_timer_t *timer)
{
    if (s_remote_popup != NULL) {
        lv_obj_add_flag(s_remote_popup, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_y(s_remote_popup, -132);
    }
    s_remote_popup_finish_timer = NULL;
    lv_timer_delete(timer);
}

static void remote_content_hide_cb(lv_timer_t *timer)
{
    if (s_remote_popup != NULL) {
        lv_anim_t slide;
        lv_anim_init(&slide);
        lv_anim_set_var(&slide, s_remote_popup);
        lv_anim_set_exec_cb(&slide, (lv_anim_exec_xcb_t)lv_obj_set_y);
        lv_anim_set_values(&slide, lv_obj_get_y(s_remote_popup), -132);
        lv_anim_set_duration(&slide, 220);
        lv_anim_set_path_cb(&slide, lv_anim_path_ease_in);
        lv_anim_start(&slide);
        if (s_remote_popup_finish_timer != NULL) {
            lv_timer_delete(s_remote_popup_finish_timer);
        }
        s_remote_popup_finish_timer = lv_timer_create(remote_popup_finish_cb, 240, NULL);
        lv_timer_set_repeat_count(s_remote_popup_finish_timer, 1);
    }
    s_remote_content_timer = NULL;
    lv_timer_delete(timer);
}

static void ensure_remote_popup(void)
{
    if (s_remote_popup != NULL) {
        return;
    }

    s_remote_popup = lv_obj_create(lv_screen_active());
    lv_obj_remove_style_all(s_remote_popup);
    lv_obj_set_size(s_remote_popup, UI_SCREEN_W - 56, UI_SCREEN_H / 4);
    lv_obj_set_style_radius(s_remote_popup, 28, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_remote_popup, 230, LV_PART_MAIN);
    lv_obj_set_style_bg_color(s_remote_popup, lv_color_hex(0x18221b), LV_PART_MAIN);
    lv_obj_set_style_border_width(s_remote_popup, 2, LV_PART_MAIN);
    lv_obj_set_style_border_color(s_remote_popup, lv_color_hex(0x9ee5b3), LV_PART_MAIN);
    lv_obj_set_style_pad_all(s_remote_popup, 18, LV_PART_MAIN);
    lv_obj_clear_flag(s_remote_popup, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_clear_flag(s_remote_popup, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_align(s_remote_popup, LV_ALIGN_TOP_MID, 0, -132);
    lv_obj_add_flag(s_remote_popup, LV_OBJ_FLAG_HIDDEN);

    s_remote_popup_label = lv_label_create(s_remote_popup);
    lv_label_set_text(s_remote_popup_label, "");
    lv_label_set_long_mode(s_remote_popup_label, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(s_remote_popup_label, UI_SCREEN_W - 112);
    lv_obj_set_style_text_align(s_remote_popup_label, LV_TEXT_ALIGN_LEFT, LV_PART_MAIN);
    lv_obj_set_style_text_font(s_remote_popup_label, schedule_font(), LV_PART_MAIN);
    lv_obj_set_style_text_line_space(s_remote_popup_label, 5, LV_PART_MAIN);
    lv_obj_set_style_text_color(s_remote_popup_label, lv_color_hex(0xf7fff8), LV_PART_MAIN);
    lv_obj_align(s_remote_popup_label, LV_ALIGN_CENTER, 0, 0);
}

void app_ui_show_remote_content(const char *text, uint32_t duration_ms)
{
    if (text == NULL || text[0] == '\0') return;
    if (bsp_display_lock(200) != ESP_OK) return;
    ensure_remote_popup();
    lv_label_set_text(s_remote_popup_label, text);
    lv_obj_clear_flag(s_remote_popup, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_foreground(s_remote_popup);
    lv_obj_set_y(s_remote_popup, -132);

    lv_anim_t slide;
    lv_anim_init(&slide);
    lv_anim_set_var(&slide, s_remote_popup);
    lv_anim_set_exec_cb(&slide, (lv_anim_exec_xcb_t)lv_obj_set_y);
    lv_anim_set_values(&slide, -132, 12);
    lv_anim_set_duration(&slide, 220);
    lv_anim_set_path_cb(&slide, lv_anim_path_ease_out);
    lv_anim_start(&slide);

    if (s_remote_content_timer != NULL) lv_timer_delete(s_remote_content_timer);
    if (s_remote_popup_finish_timer != NULL) {
        lv_timer_delete(s_remote_popup_finish_timer);
        s_remote_popup_finish_timer = NULL;
    }
    s_remote_content_timer = lv_timer_create(remote_content_hide_cb, duration_ms > 0 ? duration_ms : 2000, NULL);
    lv_timer_set_repeat_count(s_remote_content_timer, 1);
    bsp_display_unlock();
}

void app_ui_set_voice_status(const char *status)
{
    if (status == NULL || s_voice_label == NULL) {
        return;
    }

    if (bsp_display_lock(100) == ESP_OK) {
        lv_label_set_text(s_voice_label, status);
        bsp_display_unlock();
    }
}
