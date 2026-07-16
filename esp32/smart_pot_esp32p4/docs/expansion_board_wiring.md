# Expansion board wiring

This note matches the sensor expansion schematics dated 2026-06-06/07.

## Recommended ESP32-P4 main-board wiring

Use the Waveshare 40-pin expansion header. Confirm the signal name on the
main-board silkscreen or schematic before powering on.

| Expansion PCB net | Connect to ESP32-P4 main board | Firmware config |
| --- | --- | --- |
| +5V | 5V on 40-pin header | Powers the PCB 5V-to-3.0V regulator |
| +3.3V | 3.3V on 40-pin header | Pullups and TTP223/BH1750 logic supply |
| GND | GND on 40-pin header | Common ground, required |
| SCL | GPIO8 / BSP I2C SCL | `SMART_POT_BH1750_USE_BSP_I2C=y` |
| SDA | GPIO7 / BSP I2C SDA | `SMART_POT_BH1750_USE_BSP_I2C=y` |
| Q_OUT | GPIO48 | `SMART_POT_TTP223_GPIO=48` |
| 555_OUT | GPIO47 | `SMART_POT_SOIL_555_GPIO=47` |

Do not feed 5V into `Q_OUT` or any ESP32-P4 GPIO. The TTP223 part on this
schematic must be powered from 3.3V so that `Q_OUT` is also 3.3V-level.

## Sub-board connections

### BH1750 light board

The light board header is:

| Light board net | Connect to main expansion PCB |
| --- | --- |
| +3.3V | +3.3V |
| SCL | SCL |
| SDA | SDA |
| ADDR | ADDR |
| GND | GND |

The ADDR switch on the main PCB selects the BH1750 I2C address:

| ADDR switch | I2C address | Firmware config |
| --- | --- | --- |
| 0 | `0x23` | `SMART_POT_BH1750_ADDR_SELECT=0` |
| 1 | `0x5c` | `SMART_POT_BH1750_ADDR_SELECT=1` |

### Soil probe board

The soil probe board is only the external probe connection:

| Soil probe board net | Connect to main expansion PCB |
| --- | --- |
| 555_SIGNAL | 555_SIGNAL |
| GND | GND |

The ESP32-P4 does not read `555_SIGNAL` directly. It reads the conditioned
`555_OUT` square-wave output from the main expansion PCB.

## First power-on checklist

1. Verify +5V, +3.3V, and GND continuity before inserting the main board.
2. Verify the TTP223 is powered from 3.3V, not 5V.
3. Keep the BH1750 ADDR switch at 0 for the current firmware default.
4. After flashing, watch serial logs for:
   - `BH1750 configured`
   - `TTP223 touch input configured on GPIO48`
   - `555 soil frequency input configured on GPIO47`
   - `Sensor state: soil=... light=... touch=...`
5. Calibrate soil moisture later by recording dry and wet frequencies and
   updating `SMART_POT_SOIL_555_DRY_HZ` and `SMART_POT_SOIL_555_WET_HZ`.
