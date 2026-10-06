/**
 *  iStore Heat Pump Connect
 *
 *  Parent app for iStore R290 hot water heat pumps connected to the UNIVERS / iStore cloud
 *  (https://home.istore.net.au). Logs in with the same account as the UNIVERS EMS mobile app,
 *  discovers the heat pumps on the account, creates a child device for each one and polls
 *  the cloud for measurements.
 *
 *  Licensed under the MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String VERSION = "1.0.0"
@Field static final String BASE_URI = "https://home.istore.net.au"
@Field static final String DRIVER_NAMESPACE = "jc21"
@Field static final String DRIVER_NAME = "iStore Heat Pump"

@Field static final String PATH_PUBLIC_KEY = "/hossain-bff/framework/v1.0/user/public-key"
@Field static final String PATH_LOGIN = "/hossain-bff/framework/v1.0/user/login"
@Field static final String PATH_SET_SESSION = "/hossain-bff/framework/v1.0/user/set-session"
@Field static final String PATH_REFRESH = "/hossain-bff/framework/v1.0/user/refresh-token"
@Field static final String PATH_SITE_LIST = "/hossain-bff/site/v1.0/list"
@Field static final String PATH_ASSET_LIST = "/hossain-bff/monitor/v1.0/asset/list"
@Field static final String PATH_HIERARCHY = "/encompassbffservice/encompass-bff/asset-service/v1.0/asset-hierarchy"
@Field static final String PATH_ATTRIBUTES = "/encompassbffservice/encompass-bff/anti-timeseries/v1.0/attributes"
@Field static final String PATH_MEASUREMENTS = "/encompassbffservice/encompass-bff/anti-timeseries/v1.0/measurement-points"
@Field static final String PATH_CONTROL = "/hossain-bff/connect/v1.0/device/control"
@Field static final String PATH_ASSET_UPDATE = "/hossain-bff/monitor/v1.0/asset/update"

@Field static final String WATER_HEATER_TYPE = "Res_WaterHeater"
@Field static final String METADATA_ATTRIBUTES = "DeviceState,modelName,name,sn,manufacturerName,macCode"

@Field static final List POINTS = [
    "WH.OnOff",
    "WH.TargetTemp",
    "WH.TargetTempMin",
    "WH.TargetTempMax",
    "WH.TopTemp",
    "WH.BottomTemp",
    "PUB_WH.CompressorStatus",
    "PUB_WH.EnvirTemp",
    "PUB_WH.SuctionTemp",
    "PUB_WH.CoilTemp",
    "PUB_WH.Booster",
    "PUB_WH.WorkMode",
    "PUB_WH.4WayStatus",
    "PUB_WH.FanSpeed",
    "PUB_WH.DefrostStatus",
    "PRI_RE_WH.Timer1On",
    "PRI_RE_WH.Timer1OnTime",
    "PRI_RE_WH.Timer1Off",
    "PRI_RE_WH.Timer1OffTime",
    "PRI_RE_WH.Timer2On",
    "PRI_RE_WH.Timer2OnTime",
    "PRI_RE_WH.Timer2Off",
    "PRI_RE_WH.Timer2OffTime",
]

// API "code" values the portal treats as success, and the ones that mean the token has expired
@Field static final List SUCCESS_CODES = ["0", "200", "10000"]
@Field static final List AUTH_EXPIRED_CODES = ["88201", "31401"]

@Field static final Map POLL_INTERVALS = [
    "30s": "Every 30 seconds",
    "1"  : "Every minute",
    "2"  : "Every 2 minutes",
    "5"  : "Every 5 minutes",
    "10" : "Every 10 minutes",
    "15" : "Every 15 minutes",
]

@Field static final int MAX_BACKOFF_SECONDS = 900
@Field static final int OFFLINE_AFTER_FAILURES = 3
@Field static final int MAX_AUTH_FAILURES = 3

definition(
    name: "iStore Heat Pump Connect",
    namespace: "jc21",
    author: "Jamie Curnow",
    description: "Connect iStore R290 hot water heat pumps via the UNIVERS cloud",
    category: "Convenience",
    singleInstance: false,
    iconUrl: "",
    iconX2Url: "",
    documentationLink: "https://github.com/jc21/hubitat-istore-heat-pump"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "iStore Heat Pump Connect v${VERSION}", install: true, uninstall: true) {
        section("iStore / UNIVERS account") {
            paragraph "Use the same email and password as the UNIVERS EMS app or https://home.istore.net.au"
            input "username", "text", title: "Email / username", required: false, submitOnChange: true
            input "password", "password", title: "Password", required: false, submitOnChange: true
            if (state.organizations && state.organizations.size() > 1) {
                input "orgId", "enum", title: "Organisation", options: state.organizations, required: false, submitOnChange: true
            }
            input "btnLogin", "button", title: "Log in and discover heat pumps"
            paragraph statusText()
        }

        if (state.discovered) {
            section("Heat pumps") {
                input "selectedDevices", "enum", title: "Heat pumps to add to Hubitat", options: state.discovered,
                    multiple: true, required: false
            }
        }

        section("Polling") {
            input "pollInterval", "enum", title: "Poll interval", options: POLL_INTERVALS, defaultValue: "1", required: true
            paragraph "After a command the app also polls at +10s, +25s and +55s to confirm the change."
        }

        section("Advanced", hideable: true, hidden: true) {
            paragraph "Use these only if automatic login or discovery doesn't work for your account."
            input "manualToken", "password", title: "Manual access token (APP_PORTAL_...). Overrides username/password", required: false
            input "manualParentId", "text", title: "Manual parent ID (parentId)", required: false
            input "manualMdmId", "text", title: "Manual device ID (mdmId)", required: false
            input "btnRelogin", "button", title: "Force re-login"
            input "btnRefresh", "button", title: "Refresh all devices now"
        }

        section("Logging") {
            input "logEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
            input "appLabel", "text", title: "Name this app", required: false, submitOnChange: true
            if (settings.appLabel) app.updateLabel(settings.appLabel)
        }
    }
}

private String statusText() {
    List lines = []
    lines << "<b>Login:</b> ${state.authStatus ?: 'not logged in'}"
    if (state.lastError) lines << "<b>Last error:</b> ${state.lastError}"
    if (state.lastPoll) lines << "<b>Last successful poll:</b> ${new Date(state.lastPoll as Long).format('yyyy-MM-dd HH:mm:ss', location.timeZone)}"
    if (state.discovered != null) lines << "<b>Heat pumps found:</b> ${state.discovered.size()}"
    return lines.join("<br>")
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnLogin":
            state.authFailures = 0
            if (login()) discover()
            break
        case "btnRelogin":
            state.remove("accessToken")
            state.remove("refreshToken")
            state.authFailures = 0
            login()
            break
        case "btnRefresh":
            refreshAll()
            break
    }
}

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

void installed() {
    logDebug "installed"
    initialize()
}

void updated() {
    logDebug "updated"
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    state.failures = 0
    state.remove("nextPollAllowed")
    if (settings.logEnable) runIn(1800, "logsOff")

    if (!state.accessToken || settings.manualToken) login()
    syncChildren()
    schedulePolling()

    // Daily housekeeping at a random time so every hub doesn't hit the cloud at once
    Random rnd = new Random()
    schedule("${rnd.nextInt(60)} ${rnd.nextInt(60)} ${rnd.nextInt(24)} * * ?", "dailyMaintenance")

    runIn(3, "refreshAll")
}

void logsOff() {
    log.warn "${app.label}: debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void schedulePolling() {
    unschedule("poll")
    String interval = settings.pollInterval ?: "1"
    int sec = new Random().nextInt(30)
    if (interval == "30s") {
        schedule("${sec}/30 * * * * ?", "poll")
    } else {
        schedule("${sec} 0/${interval} * * * ?", "poll")
    }
}

void dailyMaintenance() {
    refreshToken()
    fetchMetadata()
}

// ---------------------------------------------------------------------------------------------
// Child devices
// ---------------------------------------------------------------------------------------------

private Map wantedDevices() {
    Map wanted = [:]
    Map discovered = state.discovered ?: [:]
    Map parents = state.discoveredParents ?: [:]
    (settings.selectedDevices ?: []).each { String mdmId ->
        wanted[mdmId] = [label: discovered[mdmId] ?: "iStore Heat Pump", parentId: parents[mdmId]]
    }
    if (settings.manualMdmId) {
        String mdmId = settings.manualMdmId.trim()
        wanted[mdmId] = [label: discovered[mdmId] ?: "iStore Heat Pump", parentId: settings.manualParentId?.trim()]
    }
    return wanted
}

private String dniFor(String mdmId) {
    return "istore-${mdmId}"
}

private void syncChildren() {
    Map wanted = wantedDevices()
    wanted.each { String mdmId, Map info ->
        def child = getChildDevice(dniFor(mdmId))
        if (!child) {
            try {
                child = addChildDevice(DRIVER_NAMESPACE, DRIVER_NAME, dniFor(mdmId), [name: DRIVER_NAME, label: info.label, isComponent: false])
                log.info "${app.label}: created device '${info.label}'"
            } catch (e) {
                log.error "${app.label}: couldn't create the child device. Is the '${DRIVER_NAME}' driver installed? ${e.message}"
                return
            }
        }
        child.updateDataValue("mdmId", mdmId)
        if (info.parentId) child.updateDataValue("parentId", info.parentId as String)
    }
    getChildDevices().each { child ->
        String mdmId = child.getDataValue("mdmId")
        if (!wanted.containsKey(mdmId)) {
            log.info "${app.label}: removing device '${child.label}'"
            deleteChildDevice(child.deviceNetworkId)
        }
    }
}

private List<String> childMdmIds() {
    return getChildDevices().collect { it.getDataValue("mdmId") }.findAll { it }
}

private def childFor(String mdmId) {
    return getChildDevice(dniFor(mdmId))
}

// ---------------------------------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------------------------------

/**
 * Full login with username/password. Synchronous because it is rare and the app page wants the
 * result straight away. Returns true when a usable access token is stored in state.
 */
private boolean login() {
    if (settings.manualToken) {
        state.accessToken = settings.manualToken.trim().replaceFirst(/^Bearer\s+/, "")
        state.authStatus = "using manual access token"
        state.remove("lastError")
        return true
    }
    if (!settings.username || !settings.password) {
        state.authStatus = "enter your username and password"
        return false
    }
    if ((state.authFailures ?: 0) >= MAX_AUTH_FAILURES) {
        state.authStatus = "login failed ${state.authFailures} times, paused. Check your credentials and press 'Log in'"
        return false
    }

    try {
        Map keyResp = syncRequest("GET", PATH_PUBLIC_KEY, [:], false)
        String publicKey = keyResp?.data?.publicKey
        if (!publicKey) throw new Exception("no public key returned")

        String encrypted = rsaOaepSha256Encrypt(publicKey, settings.password as String)
        Map body = [account: (settings.username as String).trim(), password: encrypted]
        if (keyResp.data.strategy != null) body.strategy = keyResp.data.strategy

        Map loginResp = syncRequest("POST", PATH_LOGIN, [json: body], false)
        if (!isSuccess(loginResp)) throw new Exception("login rejected (code ${loginResp?.code}): ${loginResp?.msg ?: loginResp?.message ?: 'check username and password'}")

        Map data = loginResp.data ?: [:]
        state.accessToken = data.accessToken
        if (data.refreshToken) state.refreshToken = data.refreshToken

        List orgs = (data.organizations ?: []) as List
        state.organizations = orgs.collectEntries { [(it.id as String): (it.name ?: it.id) as String] }
        String org = settings.orgId ?: (orgs ? orgs[0].id as String : null)
        if (org) selectOrganisation(org)

        state.authFailures = 0
        state.authStatus = "logged in as ${settings.username}"
        state.remove("lastError")
        log.info "${app.label}: logged in to the iStore cloud"
        return true
    } catch (e) {
        state.authFailures = (state.authFailures ?: 0) + 1
        state.authStatus = "login failed"
        state.lastError = e.message
        log.error "${app.label}: login failed: ${e.message}"
        return false
    }
}

private void selectOrganisation(String org) {
    Map resp = syncRequest("POST", PATH_SET_SESSION, [json: [orgId: org]])
    if (!isSuccess(resp)) throw new Exception("couldn't select organisation (code ${resp?.code})")
    Map data = resp.data ?: [:]
    if (data.accessToken) state.accessToken = data.accessToken
    if (data.refreshToken) state.refreshToken = data.refreshToken
    if (data.refreshTokenExpire) state.refreshTokenExpire = data.refreshTokenExpire
}

/** Swap the refresh token for a new access token. Falls back to a full login. */
private boolean refreshToken() {
    if (settings.manualToken) return true
    if (state.refreshToken) {
        try {
            Map resp = syncRequest("GET", PATH_REFRESH, [query: [refreshToken: state.refreshToken]], false)
            if (isSuccess(resp) && resp.data?.accessToken) {
                state.accessToken = resp.data.accessToken
                if (resp.data.refreshToken) state.refreshToken = resp.data.refreshToken
                logDebug "access token refreshed"
                return true
            }
            logDebug "token refresh rejected (code ${resp?.code}), logging in again"
        } catch (e) {
            logDebug "token refresh failed: ${e.message}, logging in again"
        }
    }
    return login()
}

/** Called when the API reports an expired token. Guards against parallel re-logins. */
private boolean reauthenticate() {
    if (settings.manualToken) {
        state.authStatus = "manual access token rejected. Get a new one or use username/password"
        return false
    }
    Long started = state.authInProgress as Long
    if (started && now() - started < 60000) return false
    state.authInProgress = now()
    try {
        return refreshToken()
    } finally {
        state.remove("authInProgress")
    }
}

// ---------------------------------------------------------------------------------------------
// Discovery
// ---------------------------------------------------------------------------------------------

/**
 * Find the water heaters on the account. The portal's site/asset list payloads aren't documented,
 * so this collects candidate site IDs from a couple of endpoints and asks the asset hierarchy
 * service for water heaters under each one. Manual IDs in "Advanced" are the fallback.
 */
private void discover() {
    Set<String> sites = [] as Set
    Map found = [:]
    Map parents = [:]

    if (settings.manualParentId) sites << settings.manualParentId.trim()

    [
        [method: "POST", path: PATH_SITE_LIST, opts: [json: [pageNo: 1, pageSize: 100]]],
        [method: "GET", path: PATH_SITE_LIST, opts: [query: [pageNo: 1, pageSize: 100]]],
        [method: "POST", path: PATH_ASSET_LIST, opts: [json: [pageNo: 1, pageSize: 100, mdmType: WATER_HEATER_TYPE]]],
    ].each { Map attempt ->
        try {
            Map resp = authedSyncRequest(attempt.method, attempt.path, attempt.opts)
            logDebug "discovery ${attempt.method} ${attempt.path}: ${truncate(JsonOutput.toJson(resp))}"
            if (isSuccess(resp)) scanForAssets(resp.data, null, sites, found, parents)
        } catch (e) {
            logDebug "discovery ${attempt.method} ${attempt.path} failed: ${e.message}"
        }
    }

    sites.each { String site ->
        try {
            Map resp = authedSyncRequest("POST", PATH_HIERARCHY, [form: [mdmIds: site, mdmTypes: WATER_HEATER_TYPE, attributes: "name,mdmType", locale: "en-US"]])
            logDebug "hierarchy for ${site}: ${truncate(JsonOutput.toJson(resp))}"
            List heaters = (resp?.data?.get(site)?.mdmObjects?.get(WATER_HEATER_TYPE) ?: []) as List
            heaters.each { Map h ->
                if (!h.mdmId) return
                found[h.mdmId as String] = (h.attributes?.name ?: h.mdmId) as String
                parents[h.mdmId as String] = site
            }
        } catch (e) {
            logDebug "hierarchy lookup for ${site} failed: ${e.message}"
        }
    }

    state.discovered = found
    state.discoveredParents = parents
    if (found) {
        state.remove("lastError")
        log.info "${app.label}: found ${found.size()} heat pump(s)"
    } else {
        state.lastError = "no heat pumps found automatically. Turn on debug logging and try again, or enter the IDs under Advanced"
        log.warn "${app.label}: ${state.lastError}"
    }
}

/** Walk a JSON tree looking for water heaters and anything that looks like a site ID. */
private void scanForAssets(def node, String parent, Set<String> sites, Map found, Map parents) {
    if (node instanceof Map) {
        String type = (node.mdmType ?: node.assetType ?: node.type) as String
        String id = (node.mdmId ?: node.assetId) as String
        String parentId = (node.parentId ?: node.siteId ?: parent) as String
        if (type == WATER_HEATER_TYPE && id) {
            found[id] = (node.name ?: node.attributes?.name ?: node.assetName ?: id) as String
            if (parentId) {
                parents[id] = parentId
                sites << parentId
            }
        } else if (node.siteId) {
            sites << (node.siteId as String)
        } else if (type?.toLowerCase()?.contains("site") && id) {
            sites << id
        }
        node.each { k, v -> if (v instanceof Map || v instanceof List) scanForAssets(v, parentId, sites, found, parents) }
    } else if (node instanceof List) {
        node.each { scanForAssets(it, parent, sites, found, parents) }
    }
}

// ---------------------------------------------------------------------------------------------
// Polling
// ---------------------------------------------------------------------------------------------

void refreshAll() {
    state.remove("nextPollAllowed")
    poll()
    fetchMetadata()
}

/** Called by children from refresh() */
void refreshDevice(String mdmId) {
    state.remove("nextPollAllowed")
    poll()
}

void poll() {
    List<String> ids = childMdmIds()
    if (!ids || !haveCredentials()) return
    Long next = state.nextPollAllowed as Long
    if (next && now() < next) {
        logDebug "backing off after ${state.failures} failure(s), skipping poll"
        return
    }
    apiAsync([method: "POST", path: PATH_MEASUREMENTS, form: [mdmIds: ids.join(","), pointIds: POINTS.join(",")]], "measurements")
}

void confirmPoll1() { confirmPoll() }
void confirmPoll2() { confirmPoll() }
void confirmPoll3() { confirmPoll() }

private void confirmPoll() {
    if (getChildDevices().any { it.hasPendingChanges() }) {
        state.remove("nextPollAllowed")
        poll()
    }
}

private void scheduleConfirmPolls() {
    runIn(10, "confirmPoll1")
    runIn(25, "confirmPoll2")
    runIn(55, "confirmPoll3")
}

private void handleMeasurements(boolean ok, Map json) {
    if (!ok) {
        recordFailure()
        return
    }
    Map data = (json?.data ?: [:]) as Map
    data.each { String mdmId, Map entry ->
        def child = childFor(mdmId)
        if (!child) return
        Map values = [:]
        (entry?.points ?: [:]).each { String pid, def p -> values[pid] = (p instanceof Map) ? p.value : p }
        child.applyPoints(values)
    }
    childMdmIds().findAll { !data.containsKey(it) }.each {
        log.warn "${app.label}: no data returned for device ${it}"
    }
    recordSuccess()
}

private void recordSuccess() {
    if (state.failures) log.info "${app.label}: cloud connection restored"
    state.failures = 0
    state.remove("nextPollAllowed")
    state.lastPoll = now()
}

private void recordFailure() {
    int failures = (state.failures ?: 0) + 1
    state.failures = failures
    int base = settings.pollInterval == "30s" ? 30 : ((settings.pollInterval ?: "1") as Integer) * 60
    int delay = Math.min(base * (1 << Math.min(failures, 6)), MAX_BACKOFF_SECONDS)
    state.nextPollAllowed = now() + delay * 1000L
    if (failures == OFFLINE_AFTER_FAILURES) {
        log.warn "${app.label}: ${failures} failed polls in a row, marking devices offline"
        getChildDevices().each { it.setHealth("offline") }
    }
}

void fetchMetadata() {
    List<String> ids = childMdmIds()
    if (!ids || !haveCredentials()) return
    apiAsync([method: "POST", path: PATH_ATTRIBUTES, query: [attributes: METADATA_ATTRIBUTES],
              form: [withI18n: "true", mdmIds: ids.join(","), locale: "en-US"]], "metadata")
}

private void handleMetadata(boolean ok, Map json) {
    if (!ok) return
    ((json?.data ?: [:]) as Map).each { String mdmId, def attrs ->
        def child = childFor(mdmId)
        if (child && attrs instanceof Map) child.applyMetadata(attrs)
    }
}

// ---------------------------------------------------------------------------------------------
// Control (called by children)
// ---------------------------------------------------------------------------------------------

void sendControl(String mdmId, Map points) {
    List body = points.collect { k, v -> [assetId: mdmId, controlPointId: k, value: v] }
    logDebug "control ${mdmId}: ${points}"
    apiAsync([method: "POST", path: PATH_CONTROL, json: body], "control", [mdmId: mdmId, points: points])
}

void renameDevice(String mdmId, String name) {
    apiAsync([method: "POST", path: PATH_ASSET_UPDATE, json: [assetId: mdmId, name: name]], "rename", [mdmId: mdmId, name: name])
}

private void handleControl(boolean ok, Map json, Map ctx) {
    def child = childFor(ctx.mdmId as String)
    if (ok) {
        scheduleConfirmPolls()
    } else {
        log.warn "${app.label}: command ${ctx.points} was rejected: ${json?.msg ?: json?.message ?: json?.code ?: 'no response'}"
        child?.controlFailed(ctx.points as Map)
        runIn(2, "confirmPoll1")
    }
}

private void handleRename(boolean ok, Map json, Map ctx) {
    def child = childFor(ctx.mdmId as String)
    if (ok) {
        child?.applyMetadata([name: ctx.name])
    } else {
        log.warn "${app.label}: rename was rejected: ${json?.msg ?: json?.message ?: json?.code ?: 'no response'}"
    }
}

// ---------------------------------------------------------------------------------------------
// HTTP plumbing
// ---------------------------------------------------------------------------------------------

private boolean haveCredentials() {
    return settings.manualToken || (settings.username && settings.password)
}

private Map buildParams(String method, String path, Map opts, boolean auth) {
    Map headers = [locale: "en-US"]
    if (auth && state.accessToken) headers.Authorization = "Bearer ${state.accessToken}"
    Map params = [uri: BASE_URI, path: path, headers: headers, contentType: "application/json", timeout: 30]
    if (opts.query) params.query = opts.query
    if (opts.json != null) {
        params.requestContentType = "application/json"
        params.body = JsonOutput.toJson(opts.json)
    } else if (opts.form != null) {
        params.requestContentType = "application/x-www-form-urlencoded"
        params.body = formEncode(opts.form as Map)
    }
    return params
}

private String formEncode(Map form) {
    return form.collect { k, v -> "${URLEncoder.encode(k as String, 'UTF-8')}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")
}

/** Synchronous request, returns the parsed JSON body. Throws on HTTP errors. */
private Map syncRequest(String method, String path, Map opts, boolean auth = true) {
    Map params = buildParams(method, path, opts, auth)
    Map result = null
    try {
        Closure handler = { resp -> result = toMap(resp.data) }
        if (method == "GET") {
            httpGet(params, handler)
        } else {
            httpPost(params, handler)
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        Map body = toMap(e.response?.data)
        if (e.statusCode == 401) return (body ?: [:]) + [code: AUTH_EXPIRED_CODES[0], httpStatus: 401]
        throw new Exception("HTTP ${e.statusCode}${body?.msg ? ': ' + body.msg : ''}")
    }
    return result ?: [:]
}

/** Synchronous authenticated request that re-logs in once if the token has expired. */
private Map authedSyncRequest(String method, String path, Map opts) {
    Map resp = syncRequest(method, path, opts)
    if (isAuthExpired(resp) && reauthenticate()) resp = syncRequest(method, path, opts)
    return resp
}

private void apiAsync(Map req, String handler, Map ctx = [:], boolean retried = false) {
    if (!state.accessToken && !login()) {
        dispatch(handler, false, null, ctx)
        return
    }
    Map params = buildParams(req.method as String, req.path as String, req, true)
    Map data = [req: req, handler: handler, ctx: ctx, retried: retried]
    if (req.method == "GET") {
        asynchttpGet("apiCallback", params, data)
    } else {
        asynchttpPost("apiCallback", params, data)
    }
}

void apiCallback(resp, Map data) {
    Map json = null
    try {
        String raw = resp.hasError() ? resp.getErrorData() : resp.getData()
        if (raw) json = toMap(new JsonSlurper().parseText(raw))
    } catch (e) {
        logDebug "couldn't parse response from ${data.req.path}: ${e.message}"
    }

    boolean authExpired = resp.status == 401 || isAuthExpired(json)
    if (authExpired) {
        if (!data.retried && reauthenticate()) {
            apiAsync(data.req as Map, data.handler as String, data.ctx as Map, true)
            return
        }
        state.lastError = "authentication failed"
        dispatch(data.handler as String, false, json, data.ctx as Map)
        return
    }

    boolean ok = resp.status == 200 && json != null && (json.code == null || isSuccess(json))
    if (!ok) {
        String msg = resp.hasError() ? resp.getErrorMessage() : (json?.msg ?: json?.message ?: "code ${json?.code}")
        state.lastError = "${data.req.path}: HTTP ${resp.status} ${msg ?: ''}".trim()
        logDebug "request failed: ${state.lastError}"
    } else {
        logDebug "${data.handler} OK: ${truncate(JsonOutput.toJson(json))}"
    }
    dispatch(data.handler as String, ok, json, data.ctx as Map)
}

private void dispatch(String handler, boolean ok, Map json, Map ctx) {
    switch (handler) {
        case "measurements": handleMeasurements(ok, json); break
        case "metadata": handleMetadata(ok, json); break
        case "control": handleControl(ok, json, ctx); break
        case "rename": handleRename(ok, json, ctx); break
        default: log.warn "${app.label}: unknown response handler ${handler}"
    }
}

private boolean isSuccess(Map resp) {
    return resp != null && SUCCESS_CODES.contains(resp.code as String)
}

private boolean isAuthExpired(Map resp) {
    return resp != null && (AUTH_EXPIRED_CODES.contains(resp.code as String) || resp.httpStatus == 401)
}

private Map toMap(def data) {
    if (data == null) return null
    if (data instanceof Map) return data as Map
    try {
        def parsed = new JsonSlurper().parseText(data.toString())
        return parsed instanceof Map ? parsed as Map : [data: parsed]
    } catch (ignored) {
        return null
    }
}

private String truncate(String s, int max = 1500) {
    return s == null || s.length() <= max ? s : s.substring(0, max) + "…"
}

private void logDebug(String msg) {
    if (settings.logEnable) log.debug "${app.label}: ${msg}"
}

// ---------------------------------------------------------------------------------------------
// RSA-OAEP (SHA-256, MGF1-SHA-256) password encryption, matching the portal's node-forge call.
// Written with BigInteger/MessageDigest only, because the Hubitat sandbox doesn't allow the
// javax.crypto OAEP parameter classes.
// ---------------------------------------------------------------------------------------------

private String rsaOaepSha256Encrypt(String publicKeyB64, String plain) {
    Map key = parseSpkiRsaKey(publicKeyB64.replaceAll(/-----[A-Z ]+-----|\s/, "").decodeBase64())
    BigInteger n = key.n
    BigInteger e = key.e
    int k = (n.bitLength() + 7).intdiv(8)
    int hLen = 32
    byte[] m = plain.getBytes("UTF-8")
    if (m.length > k - 2 * hLen - 2) throw new Exception("password too long to encrypt")

    // DB = lHash || PS || 0x01 || M
    byte[] db = new byte[k - hLen - 1]
    byte[] lHash = sha256(new byte[0])
    copyBytes(lHash, 0, db, 0, hLen)
    db[db.length - m.length - 1] = (byte) 0x01
    copyBytes(m, 0, db, db.length - m.length, m.length)

    byte[] seed = new byte[hLen]
    new Random().nextBytes(seed)

    byte[] dbMask = mgf1Sha256(seed, db.length)
    for (int i = 0; i < db.length; i++) db[i] = (byte) (db[i] ^ dbMask[i])
    byte[] seedMask = mgf1Sha256(db, hLen)
    for (int i = 0; i < hLen; i++) seed[i] = (byte) (seed[i] ^ seedMask[i])

    // EM = 0x00 || maskedSeed || maskedDB
    byte[] em = new byte[k]
    copyBytes(seed, 0, em, 1, hLen)
    copyBytes(db, 0, em, 1 + hLen, db.length)

    byte[] c = new BigInteger(1, em).modPow(e, n).toByteArray()
    byte[] out = new byte[k]
    if (c.length > k) {
        copyBytes(c, c.length - k, out, 0, k)
    } else {
        copyBytes(c, 0, out, k - c.length, c.length)
    }
    return out.encodeBase64().toString()
}

private static void copyBytes(byte[] src, int srcPos, byte[] dest, int destPos, int length) {
    for (int i = 0; i < length; i++) dest[destPos + i] = src[srcPos + i]
}

private byte[] sha256(byte[] input) {
    return java.security.MessageDigest.getInstance("SHA-256").digest(input)
}

private byte[] mgf1Sha256(byte[] seed, int length) {
    byte[] out = new byte[length]
    int pos = 0
    int counter = 0
    while (pos < length) {
        byte[] input = new byte[seed.length + 4]
        copyBytes(seed, 0, input, 0, seed.length)
        input[seed.length] = (byte) (counter >>> 24)
        input[seed.length + 1] = (byte) (counter >>> 16)
        input[seed.length + 2] = (byte) (counter >>> 8)
        input[seed.length + 3] = (byte) counter
        byte[] h = sha256(input)
        int len = Math.min(h.length, length - pos)
        copyBytes(h, 0, out, pos, len)
        pos += len
        counter++
    }
    return out
}

/**
 * Minimal DER reader for an X.509 SubjectPublicKeyInfo holding an RSA key:
 * SEQUENCE { SEQUENCE { OID, NULL }, BIT STRING { SEQUENCE { INTEGER n, INTEGER e } } }
 * Also accepts a bare PKCS#1 RSAPublicKey SEQUENCE { INTEGER n, INTEGER e }.
 */
private Map parseSpkiRsaKey(byte[] der) {
    Map outer = derRead(der, 0)
    if (outer.tag != 0x30) throw new Exception("public key isn't a DER sequence")
    Map first = derRead(der, outer.start)
    if (first.tag == 0x02) {
        Map eNode = derRead(der, first.end)
        return [n: derInt(der, first), e: derInt(der, eNode)]
    }
    Map bitString = derRead(der, first.end)
    if (bitString.tag != 0x03) throw new Exception("public key has no BIT STRING")
    Map rsaSeq = derRead(der, bitString.start + 1) // skip the "unused bits" byte
    Map nNode = derRead(der, rsaSeq.start)
    Map eNode = derRead(der, nNode.end)
    return [n: derInt(der, nNode), e: derInt(der, eNode)]
}

private Map derRead(byte[] der, int offset) {
    int tag = der[offset] & 0xff
    int pos = offset + 1
    int len = der[pos] & 0xff
    pos++
    if (len & 0x80) {
        int count = len & 0x7f
        len = 0
        for (int i = 0; i < count; i++) {
            len = (len << 8) | (der[pos] & 0xff)
            pos++
        }
    }
    return [tag: tag, start: pos, end: pos + len]
}

private BigInteger derInt(byte[] der, Map node) {
    byte[] bytes = new byte[(node.end as int) - (node.start as int)]
    copyBytes(der, node.start as int, bytes, 0, bytes.length)
    return new BigInteger(1, bytes)
}
