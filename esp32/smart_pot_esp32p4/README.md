# ESP32-P4 Smart Pot Companion

Desktop emotional plant companion based on Waveshare ESP32-P4-WIFI6-Touch-LCD-4.3.

This project is derived from the official Waveshare `08_lvgl_demo_v9` example, then extended with a smart-pot application skeleton:

- LVGL screen expression and plant status dashboard
- Capacitive touch button for interaction
- Simulated light and soil moisture state loop
- Wi-Fi configuration placeholder for a later Waveshare hosted Wi-Fi milestone
- LLM dialog configuration entry for a later HTTP client integration

## Environment

Waveshare recommends ESP-IDF v5.3.1 or newer for this board. The copied official example was generated for ESP-IDF 5.4.0 and targets `esp32p4`.

## Build

Open this folder in an ESP-IDF terminal, then run:

```powershell
idf.py set-target esp32p4
idf.py menuconfig
idf.py build
```

Useful menuconfig entries are under `Smart Pot`:

- Enable Wi-Fi station placeholder
- Wi-Fi SSID / password
- Enable LLM dialog endpoint
- LLM HTTP endpoint

The first build downloads managed components such as LVGL and Waveshare BSP dependencies.

## Next Hardware Steps

Replace `main/app_sensors.c` simulation with the actual light and soil moisture drivers after the sensor modules and pins are fixed. The Waveshare board default I2C pins documented by Waveshare are `SCL=GPIO8` and `SDA=GPIO7`.
