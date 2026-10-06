/**
 *  iStore Heat Pump
 *
 *  Child device for an iStore R290 hot water heat pump. Created and polled by the
 *  "iStore Heat Pump Connect" app, which does all communication with the cloud.
 *
 *  Licensed under the MIT License.
 */

import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

@Field static final String P_ON_OFF = "WH.OnOff"
@Field static final String P_TARGET = "WH.TargetTemp"
@Field static final String P_TARGET_MIN = "WH.TargetTempMin"
@Field static final String P_TARGET_MAX = "WH.TargetTempMax"
@Field static final String P_TOP = "WH.TopTemp"
@Field static final String P_BOTTOM = "WH.BottomTemp"
@Field static final String P_COMPRESSOR = "PUB_WH.CompressorStatus"
@Field static final String P_AMBIENT = "PUB_WH.EnvirTemp"
@Field static final String P_SUCTION = "PUB_WH.SuctionTemp"
@Field static final String P_COIL = "PUB_WH.CoilTemp"
@Field static final String P_BOOSTER = "PUB_WH.Booster"
@Field static final String P_WORK_MODE = "PUB_WH.WorkMode"
@Field static final String P_FOUR_WAY = "PUB_WH.4WayStatus"
@Field static final String P_FAN = "PUB_WH.FanSpeed"
@Field static final String P_DEFROST = "PUB_WH.DefrostStatus"

@Field static final Map WORK_MODES = [0: "Standby", 1: "Heating", 2: "Eco", 3: "Hybrid", 4: "Boost"]

// Device limits for the target temperature range, in °C
@Field static final int RANGE_MIN_C = 10
@Field static final int RANGE_MAX_C = 75

// How long an optimistic value is shown before the cloud has to confirm it
@Field static final long PENDING_MS = 50000

metadata {
    definition(name: "iStore Heat Pump", namespace: "jc21", author: "Jamie Curnow",
               importUrl: "https://raw.githubusercontent.com/jc21/hubitat-istore-heat-pump/main/drivers/istore-heat-pump.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "Refresh"
        capability "HealthCheck"

        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "topTemperature", "number"
        attribute "bottomTemperature", "number"
        attribute "averageTankTemperature", "number"
        attribute "targetTempMin", "number"
        attribute "targetTempMax", "number"
        attribute "ambientTemperature", "number"
        attribute "coilTemperature", "number"
        attribute "suctionTemperature", "number"
        attribute "hotWaterAvailable", "number"
        attribute "compressor", "enum", ["on", "off"]
        attribute "booster", "enum", ["on", "off"]
        attribute "fan", "enum", ["on", "off"]
        attribute "fanSpeed", "number"
        attribute "defrost", "enum", ["on", "off"]
        attribute "fourWayValve", "enum", ["on", "off"]
        attribute "workMode", "string"
        attribute "operatingState", "enum", ["off", "defrosting", "boosting", "heating", "idle"]
        attribute "timer1Enabled", "enum", ["enabled", "disabled"]
        attribute "timer1OnTime", "string"
        attribute "timer1OffTime", "string"
        attribute "timer2Enabled", "enum", ["enabled", "disabled"]
        attribute "timer2OnTime", "string"
        attribute "timer2OffTime", "string"
        attribute "deviceName", "string"
        attribute "serialNumber", "string"
        attribute "model", "string"
        attribute "cloudState", "string"
        attribute "lastUpdated", "string"

        command "boostOn"
        command "boostOff"
        command "setTargetRange", [
            [name: "Minimum*", type: "NUMBER", description: "Lowest allowed setpoint (10–75°C)"],
            [name: "Maximum*", type: "NUMBER", description: "Highest allowed setpoint (10–75°C)"],
        ]
        command "setTimer", [
            [name: "Timer*", type: "ENUM", constraints: ["1", "2"]],
            [name: "State*", type: "ENUM", constraints: ["enabled", "disabled"]],
            [name: "On time*", type: "STRING", description: "HH:MM, 24 hour"],
            [name: "Off time*", type: "STRING", description: "HH:MM, 24 hour"],
        ]
        command "enableTimer", [[name: "Timer*", type: "ENUM", constraints: ["1", "2"]]]
        command "disableTimer", [[name: "Timer*", type: "ENUM", constraints: ["1", "2"]]]
        command "setDeviceName", [[name: "Name*", type: "STRING", description: "Name shown in the iStore / UNIVERS app"]]
    }

    preferences {
        input "coldInletTemp", "decimal", title: "Cold water inlet temperature (used for 'hot water available')",
            description: "In the hub's temperature scale", defaultValue: 15
        input "txtEnable", "bool", title: "Enable description text logging", defaultValue: true
        input "logEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

void installed() {
    sendEvent(name: "checkInterval", value: 1800)
    sendEvent(name: "healthStatus", value: "online")
}

void updated() {
    if (settings.logEnable) runIn(1800, "logsOff")
    // Re-render so a changed cold inlet temperature is reflected straight away
    if (state.raw) render(state.raw as Map)
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ---------------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------------

void refresh() {
    parent?.refreshDevice(mdmId())
}

void ping() {
    refresh()
}

void on() {
    sendCommand([(P_ON_OFF): 1])
}

void off() {
    sendCommand([(P_ON_OFF): 0])
}

void boostOn() {
    sendCommand([(P_BOOSTER): 1])
}

void boostOff() {
    sendCommand([(P_BOOSTER): 2])
}

void setHeatingSetpoint(temperature) {
    if (temperature == null) return
    int target = Math.round(toCelsius(temperature as BigDecimal)) as int
    Integer min = rawInt(P_TARGET_MIN)
    Integer max = rawInt(P_TARGET_MAX)
    if ((min != null && target < min) || (max != null && target > max)) {
        log.warn "${device.displayName}: setpoint ${temperature}° is outside the allowed range " +
            "${fmt(min)}–${fmt(max)}°${scale()}. Use setTargetRange to change the range"
        // Put the real value back so dashboards don't keep showing the rejected one
        if (state.raw?.get(P_TARGET) != null) sendTemp("heatingSetpoint", state.raw[P_TARGET])
        return
    }
    sendCommand([(P_TARGET): target])
}

void setTargetRange(minimum, maximum) {
    int min = Math.round(toCelsius(minimum as BigDecimal)) as int
    int max = Math.round(toCelsius(maximum as BigDecimal)) as int
    if (min < RANGE_MIN_C || max > RANGE_MAX_C || min >= max) {
        log.warn "${device.displayName}: target range must satisfy ${fmt(RANGE_MIN_C)} ≤ min < max ≤ ${fmt(RANGE_MAX_C)}°${scale()}"
        return
    }
    Integer target = rawInt(P_TARGET)
    if (target != null && (target < min || target > max)) {
        log.warn "${device.displayName}: the current setpoint (${fmt(target)}°${scale()}) is outside the new range; the heat pump may adjust it"
    }
    sendCommand([(P_TARGET_MIN): min, (P_TARGET_MAX): max])
}

void setTimer(timer, String enabled, String onTime, String offTime) {
    int n = timer as int
    if (!(n in [1, 2])) {
        log.warn "${device.displayName}: timer must be 1 or 2"
        return
    }
    onTime = normaliseTime(onTime)
    offTime = normaliseTime(offTime)
    if (!onTime || !offTime) {
        log.warn "${device.displayName}: timer times must be HH:MM in 24 hour time"
        return
    }
    int flag = enabled == "enabled" ? 1 : 0
    // The heat pump wants all four values of a timer written together
    sendCommand([
        (timerPoint(n, "On"))     : flag,
        (timerPoint(n, "Off"))    : flag,
        (timerPoint(n, "OnTime")) : onTime,
        (timerPoint(n, "OffTime")): offTime,
    ])
}

void enableTimer(timer) {
    setTimerFlag(timer as int, "enabled")
}

void disableTimer(timer) {
    setTimerFlag(timer as int, "disabled")
}

void setDeviceName(String name) {
    if (!name?.trim()) return
    parent?.renameDevice(mdmId(), name.trim())
}

private void setTimerFlag(int n, String enabled) {
    String onTime = state.raw?.get(timerPoint(n, "OnTime"))
    String offTime = state.raw?.get(timerPoint(n, "OffTime"))
    if (!onTime || !offTime) {
        log.warn "${device.displayName}: timer ${n} times aren't known yet, refresh first or use setTimer"
        return
    }
    setTimer(n, enabled, onTime, offTime)
}

/** Show the new values straight away, then hand them to the parent to send to the cloud. */
private void sendCommand(Map points) {
    if (!parent) {
        log.error "${device.displayName}: this device must be created by the iStore Heat Pump Connect app"
        return
    }
    Map pending = (state.pending ?: [:]) as Map
    long expires = now() + PENDING_MS
    points.each { k, v -> pending[k] = [value: v, expires: expires] }
    state.pending = pending
    logDebug "sending ${points}"
    render(((state.raw ?: [:]) as Map) + points)
    parent.sendControl(mdmId(), points)
}

// ---------------------------------------------------------------------------------------------
// Called by the parent app
// ---------------------------------------------------------------------------------------------

boolean hasPendingChanges() {
    return state.pending as boolean
}

/** New measurement values from the cloud (raw, °C), keyed by point ID. */
void applyPoints(Map cloud) {
    Map raw = ((state.raw ?: [:]) as Map) + cloud
    state.raw = raw

    Map effective = [:] + raw
    Map pending = (state.pending ?: [:]) as Map
    pending.keySet().toList().each { String pid ->
        Map p = pending[pid] as Map
        if (!cloud.containsKey(pid)) return
        if (sameValue(cloud[pid], p.value)) {
            logDebug "${pid} confirmed as ${p.value}"
            pending.remove(pid)
        } else if (now() < (p.expires as Long)) {
            effective[pid] = p.value
        } else {
            log.warn "${device.displayName}: the heat pump didn't apply ${pid} = ${p.value} (still ${cloud[pid]})"
            pending.remove(pid)
        }
    }
    state.pending = pending

    render(effective)
    setHealth("online")
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

/** Device details from the attributes endpoint. */
void applyMetadata(Map attrs) {
    if (attrs.name) sendEvent(name: "deviceName", value: textValue(attrs.name))
    if (attrs.sn) sendEvent(name: "serialNumber", value: textValue(attrs.sn))
    if (attrs.modelName) sendEvent(name: "model", value: textValue(attrs.modelName))
    if (attrs.manufacturerName) updateDataValue("manufacturer", textValue(attrs.manufacturerName))
    if (attrs.macCode) updateDataValue("mac", textValue(attrs.macCode))
    if (attrs.DeviceState != null) {
        String cloudState = textValue(attrs.DeviceState)
        sendEvent(name: "cloudState", value: cloudState)
        if (cloudState.toLowerCase() in ["offline", "0", "disconnected"]) {
            setHealth("offline")
        }
    }
}

void controlFailed(Map points) {
    Map pending = (state.pending ?: [:]) as Map
    points.keySet().each { pending.remove(it) }
    state.pending = pending
    if (state.raw) render(state.raw as Map)
}

void setHealth(String status) {
    if (device.currentValue("healthStatus") != status) {
        if (status == "offline") log.warn "${device.displayName}: offline"
        else logText "${device.displayName}: online"
    }
    sendEvent(name: "healthStatus", value: status)
}

// ---------------------------------------------------------------------------------------------
// Rendering cloud values into attributes
// ---------------------------------------------------------------------------------------------

private void render(Map v) {
    BigDecimal top = num(v[P_TOP])
    BigDecimal bottom = num(v[P_BOTTOM])
    BigDecimal target = num(v[P_TARGET])

    if (v[P_ON_OFF] != null) sendText("switch", flag(v[P_ON_OFF]) ? "on" : "off")
    if (top != null) {
        sendTemp("temperature", top)
        sendTemp("topTemperature", top)
    }
    if (bottom != null) sendTemp("bottomTemperature", bottom)
    if (top != null && bottom != null) sendTemp("averageTankTemperature", (top + bottom) / 2)
    if (target != null) sendTemp("heatingSetpoint", target)
    if (v[P_TARGET_MIN] != null) sendTemp("targetTempMin", v[P_TARGET_MIN])
    if (v[P_TARGET_MAX] != null) sendTemp("targetTempMax", v[P_TARGET_MAX])
    if (v[P_AMBIENT] != null) sendTemp("ambientTemperature", v[P_AMBIENT])
    if (v[P_COIL] != null) sendTemp("coilTemperature", v[P_COIL])
    if (v[P_SUCTION] != null) sendTemp("suctionTemperature", v[P_SUCTION])

    if (v[P_COMPRESSOR] != null) sendText("compressor", flag(v[P_COMPRESSOR]) ? "on" : "off")
    // Booster uses 1 = on, 2 = off
    if (v[P_BOOSTER] != null) sendText("booster", num(v[P_BOOSTER]) == 1 ? "on" : "off")
    if (v[P_FAN] != null) {
        sendText("fan", flag(v[P_FAN]) ? "on" : "off")
        if (num(v[P_FAN]) != null) sendEvent(name: "fanSpeed", value: num(v[P_FAN]))
    }
    if (v[P_DEFROST] != null) sendText("defrost", flag(v[P_DEFROST]) ? "on" : "off")
    if (v[P_FOUR_WAY] != null) sendText("fourWayValve", flag(v[P_FOUR_WAY]) ? "on" : "off")
    if (v[P_WORK_MODE] != null) {
        BigDecimal mode = num(v[P_WORK_MODE])
        sendText("workMode", WORK_MODES[mode?.intValue()] ?: "Unknown (${v[P_WORK_MODE]})")
    }

    [1, 2].each { int n ->
        if (v[timerPoint(n, "On")] != null) sendText("timer${n}Enabled", flag(v[timerPoint(n, "On")]) ? "enabled" : "disabled")
        if (v[timerPoint(n, "OnTime")]) sendText("timer${n}OnTime", v[timerPoint(n, "OnTime")] as String)
        if (v[timerPoint(n, "OffTime")]) sendText("timer${n}OffTime", v[timerPoint(n, "OffTime")] as String)
    }

    sendText("operatingState", operatingState(v))

    if (top != null && bottom != null && target != null) {
        BigDecimal cold = toCelsius((settings.coldInletTemp != null ? settings.coldInletTemp : defaultColdInlet()) as BigDecimal)
        if (target > cold) {
            BigDecimal pct = ((top + bottom) / 2 - cold) / (target - cold) * 100
            pct = pct.max(0).min(100).setScale(0, BigDecimal.ROUND_HALF_UP)
            sendText("hotWaterAvailable", pct, "%")
        }
    }
}

private String operatingState(Map v) {
    if (v[P_ON_OFF] != null && !flag(v[P_ON_OFF])) return "off"
    if (flag(v[P_DEFROST])) return "defrosting"
    if (num(v[P_BOOSTER]) == 1) return "boosting"
    if (flag(v[P_COMPRESSOR])) return "heating"
    return "idle"
}

private void sendTemp(String name, def celsius) {
    BigDecimal c = num(celsius)
    if (c == null) return
    BigDecimal value = scale() == "F" ? (celsiusToFahrenheit(c) as BigDecimal) : c
    sendText(name, value.setScale(1, BigDecimal.ROUND_HALF_UP), "°${scale()}")
}

private void sendText(String name, def value, String unit = null) {
    String current = device.currentValue(name)?.toString()
    if (current != value?.toString()) logText "${device.displayName} ${name} is ${value}${unit ?: ''}"
    Map evt = [name: name, value: value, descriptionText: "${device.displayName} ${name} is ${value}${unit ?: ''}"]
    if (unit) evt.unit = unit
    sendEvent(evt)
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private String mdmId() {
    return device.getDataValue("mdmId")
}

private String scale() {
    return location?.temperatureScale ?: "C"
}

private BigDecimal toCelsius(BigDecimal local) {
    return scale() == "F" ? (fahrenheitToCelsius(local) as BigDecimal) : local
}

private BigDecimal defaultColdInlet() {
    return scale() == "F" ? 59 : 15
}

/** Format a °C value in the hub's scale for log messages. */
private String fmt(def celsius) {
    if (celsius == null) return "?"
    BigDecimal c = celsius as BigDecimal
    return (scale() == "F" ? (celsiusToFahrenheit(c) as BigDecimal) : c).setScale(0, BigDecimal.ROUND_HALF_UP).toString()
}

private Integer rawInt(String pid) {
    BigDecimal n = num(state.raw?.get(pid))
    return n?.intValue()
}

private static String timerPoint(int n, String suffix) {
    return "PRI_RE_WH.Timer${n}${suffix}"
}

private static BigDecimal num(def v) {
    if (v == null || v == "") return null
    if (v instanceof Number) return v as BigDecimal
    try {
        return new BigDecimal(v.toString().trim())
    } catch (ignored) {
        return null
    }
}

private static boolean flag(def v) {
    BigDecimal n = num(v)
    if (n != null) return n != 0
    return v?.toString()?.toLowerCase() in ["true", "on"]
}

private static boolean sameValue(def a, def b) {
    BigDecimal na = num(a)
    BigDecimal nb = num(b)
    if (na != null && nb != null) return na.compareTo(nb) == 0
    return a?.toString()?.trim() == b?.toString()?.trim()
}

private static String normaliseTime(String t) {
    def m = (t ?: "").trim() =~ /^([01]?\d|2[0-3]):([0-5]\d)$/
    return m.matches() ? String.format("%02d:%s", m.group(1) as int, m.group(2)) : null
}

/** Attribute values may come back as plain values or as i18n objects. */
private static String textValue(def v) {
    if (v instanceof Map) return (v.value ?: v.defaultValue ?: v.i18nValue?.en_US ?: v.toString()) as String
    return v as String
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logText(String msg) {
    if (settings.txtEnable != false) log.info msg
}
