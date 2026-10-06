# iStore Heat Pump for Hubitat

A Hubitat app and driver for the **iStore R290 hot water heat pump**, using the UNIVERS / iStore cloud (https://home.istore.net.au). The heat pump needs the Wi-Fi module and must already be set up in the **UNIVERS EMS** mobile app.

This is a community project and isn't affiliated with iStore. Use it at your own risk.

## Features

- Logs in with your UNIVERS / iStore email and password. You don't need to copy tokens out of browser dev tools, and the app refreshes the session or logs in again by itself when it expires.
- Finds the heat pumps on your account automatically. If you have more than one, each gets its own Hubitat device.
- Monitors tank top and bottom temperature, average tank temperature, setpoint, setpoint range, ambient, coil and suction temperatures, plus compressor, fan, defrost, 4-way valve, booster, work mode and both timers.
- Controls power, booster, setpoint (checked against the min/max range), setpoint range, timers and the device name.
- Derived attributes: `operatingState` (off / defrosting / boosting / heating / idle) and `hotWaterAvailable` (%).
- Commands appear on the device straight away, then are confirmed by fast follow-up polls. If the heat pump doesn't apply a change within about 50 seconds, the device goes back to the real value.
- Uses non-blocking HTTP and one batched request per poll for all heat pumps. On errors, polling backs off exponentially (up to 15 min) and `healthStatus` reports when the cloud or the heat pump is offline.
- Supports °C and °F, following your hub's temperature scale.

## Installation

### Hubitat Package Manager
Search for **iStore Heat Pump**, or install from the manifest URL:
`https://raw.githubusercontent.com/jc21/hubitat-istore-heat-pump/main/packageManifest.json`

### Manual
1. **Drivers Code → New Driver**: paste [drivers/istore-heat-pump.groovy](drivers/istore-heat-pump.groovy) and save.
2. **Apps Code → New App**: paste [apps/istore-heat-pump-app.groovy](apps/istore-heat-pump-app.groovy) and save.
3. **Apps → Add User App → iStore Heat Pump Connect.**

## Setup

1. Enter the email and password you use for the UNIVERS EMS app.
2. Press **Log in and discover heat pumps**.
3. Select your heat pump(s), choose a poll interval (default: every minute) and press **Done**.

If discovery doesn't find your heat pump, open **Advanced** and enter the `parentId` and `mdmId` by hand. The [Home Assistant integration's guide](https://github.com/kungbernard/istore-ha#configuration) shows how to find them in the iStore web portal. If the password login doesn't work for your account, you can also paste a manual access token there.

Your password is only used to log in. It's encrypted with the portal's public key before it's sent, and the password and tokens are never written to the Hubitat logs.

## Attributes

| Attribute | Source | Notes |
|---|---|---|
| `switch` | WH.OnOff | on / off |
| `temperature` | WH.TopTemp | water temperature at the top of the tank |
| `topTemperature`, `bottomTemperature` | WH.TopTemp, WH.BottomTemp | |
| `averageTankTemperature` | derived | (top + bottom) / 2 |
| `heatingSetpoint` | WH.TargetTemp | |
| `targetTempMin`, `targetTempMax` | WH.TargetTempMin/Max | range the setpoint must stay within |
| `ambientTemperature`, `coilTemperature`, `suctionTemperature` | PUB_WH.EnvirTemp / CoilTemp / SuctionTemp | |
| `compressor`, `fan`, `defrost`, `fourWayValve` | PUB_WH.* | on / off (`fanSpeed` holds the raw fan value) |
| `booster` | PUB_WH.Booster | on / off (the cloud uses 1 = on, 2 = off) |
| `workMode` | PUB_WH.WorkMode | Standby, Heating, Eco, Hybrid, Boost |
| `operatingState` | derived | off, defrosting, boosting, heating, idle |
| `hotWaterAvailable` | derived | % of the way from the cold inlet temperature (a device preference) to the setpoint |
| `timer1Enabled`, `timer1OnTime`, `timer1OffTime` (and timer 2) | PRI_RE_WH.Timer* | |
| `deviceName`, `serialNumber`, `model`, `cloudState` | device attributes | refreshed daily |
| `healthStatus`, `lastUpdated` | | |

## Commands

| Command | Notes |
|---|---|
| `on`, `off` | power |
| `boostOn`, `boostOff` | electric booster |
| `setHeatingSetpoint(temp)` | refused if outside `targetTempMin`–`targetTempMax` |
| `setTargetRange(min, max)` | 10 ≤ min < max ≤ 75 °C |
| `setTimer(timer, enabled/disabled, "HH:MM", "HH:MM")` | writes all four timer values together |
| `enableTimer(timer)`, `disableTimer(timer)` | keeps the current times |
| `setDeviceName(name)` | renames the unit in the iStore / UNIVERS app |
| `refresh` | polls now |

## Polling

- Every 1 minute by default (configurable from 30 seconds to 15 minutes), with one request covering all heat pumps.
- After a command, extra polls at +10 s, +25 s and +55 s confirm the change. They stop as soon as it's confirmed.
- After a failure, the gap before the next poll doubles each time (up to 15 minutes). After 3 failures in a row the devices are marked `offline`.
- Device details (name, serial number, online state) are refreshed once a day.

## Credits

The cloud API details come from the [iStore Home Assistant integration](https://github.com/kungbernard/istore-ha) by @kungbernard.
