
/*
Hubitat Driver For Honeywell Thermistate
(C) 2020 - Taylor Brown

11-25-2020 :  Initial 

Credit https://github.com/dkilgore90/google-sdm-api/blob/35f793dc80dc55e266b2e3c8620e943494c5de1d/sdm-api-app.groovy
*/


import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field


@Field static String global_apiURL = "https://api.honeywell.com"
@Field static String global_redirectURL = "https://cloud.hubitat.com/oauth/stateredirect"
@Field static String global_conusmerKey = "DEb39Y2eKMrv3fGpoKudWvLOZ9LDey6N"
@Field static String global_consumerSecret = "hGyrQFX5TU4frGG5"

definition(
        name: "Honeywell Home",
        namespace: "thecloudtaylor",
        author: "Taylor Brown",
        description: "Honeywell Home App and Driver",
        category: "Thermostate",
        iconUrl: "",
        iconX2Url: "")

preferences 
{
    page(name: "setupPage", title: "Setup connection to Honeywell Home and Discover devices", install: true)
    page(name: "debugPage", title: "Debug Options", install: true)
}

mappings {
    path("/handleAuth") {
        action: [
            GET: "handleAuthRedirect"
        ]
    }
}

def setupPage() {
    dynamicPage(name: "setupPage", title: "Setup connection to Honeywell Home and Discover devices", install: true, uninstall: true) {

        connectToHoneywell()
        getDiscoverButton()
        
    //    section {
    //        input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
    //    }
        
        listDiscoveredDevices()
        
        getDebugLink()
    }
}


def debugPage() {
    dynamicPage(name:"debugPage", title: "Debug", install: false, uninstall: false) {
        section {
            paragraph "Debug buttons"
        }
        section {
            input 'refreshToken', 'button', title: 'Force Token Refresh', submitOnChange: true
        }
        section {
            input 'deleteDevices', 'button', title: 'Delete all devices', submitOnChange: true
        }
        section {
            input 'refreshDevices', 'button', title: 'Refresh all devices', submitOnChange: true
        }
    }
}

def LogDebug(logMessage)
{
    //if(settings?.debugOutput)
    //{
        log.debug "${logMessage}";
    //}
}

def LogInfo(logMessage)
{
    log.info "${logMessage}";
}

def LogWarn(logMessage)
{
    log.warn "${logMessage}";
}

def LogError(logMessage)
{
    log.error "${logMessage}";
}

def installed()
{
    LogInfo("Installing Honeywell Home.");
    createAccessToken();
    runEvery1Hour refreshToken
}

def uninstalled() 
{
    LogInfo("Uninstalling Honeywell Home.");

    for (device in getChildDevices())
    {
        //deleteChildDevice(device.deviceNetworkId)
    }
}

def connectToHoneywell() 
{
    LogDebug("connectToHoneywell()");

    //if this isn't defined early then the redirect fails for some reason...
    def redirectLocation = "http://www.bing.com";
    if (state.accessToken == null)
    {
        createAccessToken();
    }

    def state = java.net.URLEncoder.encode("${getHubUID()}/apps/${app.id}/handleAuth?access_token=${state.accessToken}", "UTF-8")
    def escapedRedirectURL = java.net.URLEncoder.encode(global_redirectURL, "UTF-8")
    def authQueryString = "response_type=code&redirect_uri=${escapedRedirectURL}&client_id=${global_conusmerKey}&state=${state}";

    def params = [
        uri: global_apiURL,
        path: "/oauth2/authorize",
        queryString: authQueryString.toString()
    ]
    LogDebug("honeywell_auth request params: ${params}");

    try {
        httpPost(params) { response -> 
            if (response.status == 302) 
            {
                LogDebug("Response 302, getting redirect")
                redirectLocation = response.headers.'Location'
                LogDebug("Redirect: ${redirectLocation}");
            }
            else
            {
                LogError("Auth request Returned Invalid HTTP Response: ${response.status}")
                return false;
            } 
        }
    }
    catch (groovyx.net.http.HttpResponseException e) 
    {
        LogError("API Auth failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
        return false;
    }
        section("Honeywell Login")
        {
            paragraph "Click below to be redirected to Honeywall to authorize Hubitat access."
            href(
                name       : 'authHref',
                title      : 'Auth Link',
                url        : redirectLocation,
                description: 'Click this link to authorize with Honeywell Home'
            )
            //href url:redirectURL, external:true, required:false, title:"Connect to Honeywell:", description:description
        } 
        section("Settings")
        {
            input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
        }
}

def getDiscoverButton() 
{
    if (state.access_token == null) 
    {
        section 
        {
            paragraph "Device discovery button is hidden until authorization is completed."            
        }
    } 
    else 
    {
        section 
        {
            input 'discoverDevices', 'button', title: 'Discover', submitOnChange: true
        }
    }
}

def getDebugLink() {
    section{
        href(
            name       : 'debugHref',
            title      : 'Debug buttons',
            page       : 'debugPage',
            description: 'Access debug buttons (force Token refresh, delete child devices , refresh devices)'
        )
    }
}

def listDiscoveredDevices() {
    def children = getChildDevices()
    def builder = new StringBuilder()
    builder << "<ul>"
    children.each {
        if (it != null) {
            builder << "<li><a href='/device/edit/${it.getId()}'>${it.getLabel()}</a></li>"
        }
    }
    builder << "</ul>"
    def links = builder.toString()
    section {
        paragraph "Discovered devices are listed below:"
        paragraph links
    }
}

def updated() 
{
    LogDebug("Updated with config: ${settings}");
    refreshToken();
    runEvery1Hour refreshToken
}

def appButtonHandler(btn) {
    switch (btn) {
    case 'discoverDevices':
        discoverDevices()
        break
    case 'refreshToken':
        refreshToken()
        break
    case 'deleteDevices':
        deleteDevices()
        break
    case 'refreshDevices':
        updateThermostats()
        break
    }
}

def deleteDevices()
{
    def children = getChildDevices()
    LogInfo("Deleting all child devices: ${children}")
    children.each {
        if (it != null) {
            deleteChildDevice it.getDeviceNetworkId()
        }
    } 
}

def discoverDevices()
{
    LogDebug("discoverDevices()");

    def uri = global_apiURL + '/v2/locations' + "?apikey=" + global_conusmerKey
    def headers = [ Authorization: 'Bearer ' + state.access_token ]
    def contentType = 'application/json'
    def params = [ uri: uri, headers: headers, contentType: contentType ]
    LogDebug("Location Discovery-params ${params}")

    //add error checking
    def reJson =''
    try 
    {
        httpGet(params) { response ->
            def reCode = response.getStatus();
            reJson = response.getData();
            LogDebug("reCode: ${reCode}")
            LogDebug("reJson: ${reJson}")
        }
    }
    catch (groovyx.net.http.HttpResponseException e) 
    {
        LogError("Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }
    reJson.each {
        LogDebug("test: ${it}")
        //LogDebug("test: ${it.LocationID}")
        //LogDebug("test: ${it[LocationID]}")
        //LogDebug("test: ${it[0]}")
        //LogDebug("test: ${it.getType()}")
        //LogDebug("test: ${it['LocationID']}")
        //LogDebug("test: ${it[0]}")

        it.each
        {
            LogDebug("test2: ${it}")

        }

    }
    def test = reJson[0]; 
    //I suspect there can be multiple locations
    LogDebug("reJson2: ${test[LocationID]}")
    

    def locationID = reJson[0].LocationID.toString();
    LogDebug(locationID);

    reJson.devices.each {
        LogDebug("DeviceID: ${it.deviceID[0].toString()}")
        LogDebug("DeviceModel: ${it.deviceModel[0].toString()}")
        LogDebug("LocationID: ${it.parentRelations?.getAt(0)?.LocationID.toString()}")

        try 
        {
            addChildDevice(
                'thecloudtaylor',
                'Honeywell Home Thermostat',
                "${it.parentRelations[0].locationID} - ${it.deviceID[0].toString()}",
                [
                    name: "Honeywell - ${it.deviceModel[0].toString()} - ${it.deviceID[0].toString()}",
                    label: it.userDefinedDeviceName[0].toString()
                ])
        } 
        catch (com.hubitat.app.exception.UnknownDeviceTypeException e) 
        {
            "${e.message} - you need to install the appropriate driver: ${device.type}"
        } 
        catch (IllegalArgumentException ignored) 
        {
            //Intentionally ignored.  Expected if device id already exists in HE.
        }
    }
}

def discoverDevicesCallback(resp, data)
{
    LogDebug("discoverDevicesCallback()");

    def respCode = resp.getStatus()
    if (resp.hasError()) 
    {
        def respError = ''
        try 
        {
            respError = resp.getErrorJson()
        } 
        catch (Exception ignored) 
        {
            // no response body
        }
        if (respCode == 401 && !data.isRetry) 
        {
            LogWarn('Authorization token expired, will refresh and retry.')
            refreshToken()
            data.isRetry = true
            asynchttpGet(handleDeviceList, data.params, data)
        } 
        else 
        {
            LogWarn("Device-list response code: ${respCode}, body: ${respError}")
        }
    } 
    else 
    {
        def respJson = resp.getJson()
        LogDebug(respJson);
    }
}

def handleAuthRedirect() 
{
    LogDebug("handleAuthRedirect()");

    def authCode = params.code

    LogDebug("AuthCode: ${authCode}")
    def authorization = ("${global_conusmerKey}:${global_consumerSecret}").bytes.encodeBase64().toString()

    def headers = [
                    Authorization: authorization,
                    Accept: "application/json"
                ]
    def body = [
                    grant_type:"authorization_code",
                    code:authCode,
                    redirect_uri:global_redirectURL
    ]
    def params = [uri: global_apiURL, path: "/oauth2/token", headers: headers, body: body]
    
    try 
    {
        httpPost(params) { response -> loginResponse(response) }
    } 
    catch (groovyx.net.http.HttpResponseException e) 
    {
        LogError("Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
    }

    def stringBuilder = new StringBuilder()
    stringBuilder << "<!DOCTYPE html><html><head><title>Honeywell Connected to Hubitat</title></head>"
    stringBuilder << "<body><p>Hubitate and Honeywell are now connected.</p>"
    stringBuilder << "<p><a href=http://${location.hub.localIP}/installedapp/configure/${app.id}/setupPage>Click here</a> to return to the App main page.</p></body></html>"
    
    def html = stringBuilder.toString()

    render contentType: "text/html", data: html, status: 200
}


def refreshToken()
{
    LogDebug("getToken()");

    if (state.refresh_token != null)
    {
        def authorization = ("${global_conusmerKey}:${global_consumerSecret}").bytes.encodeBase64().toString()

        def headers = [
                        Authorization: authorization,
                        Accept: "application/json"
                    ]
        def body = [
                        grant_type:"refresh_token",
                        refresh_token:state.refresh_token

        ]
        def params = [uri: global_apiURL, path: "/oauth2/token", headers: headers, body: body]
        
        try 
        {
            httpPost(params) { response -> loginResponse(response) }
        } 
        catch (groovyx.net.http.HttpResponseException e) 
        {
            LogError("Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}")  
        }
    }
    else
    {
        LogError("Failed to refresh token, refresh token null.")
    }

    runEvery1Hour refreshToken
}

def loginResponse(response) 
{
    LogDebug("loginResponse()");

    def reCode = response.getStatus();
    def reJson = response.getData();
    LogDebug("reCode: {$reCode}")
    LogDebug("reJson: {$reJson}")

    if (reCode == 200)
    {
        state.access_token = reJson.access_token;
        state.refresh_token = reJson.refresh_token;
    }
    else
    {
        LogError("LoginResponse Failed HTTP Request Status: ${reCode}");
    }
}
/*
def updateThermostats(hubDevice)
{
    LogDebug("updateThermostat()");

    def children = getChildDevices()
    children.each {
        if (it != null) {
            def deviceID = it.getDeviceNetworkId();      
            LogDebug("Attempting to Update DeviceID: ${deviceID}");

            def uri = global_apiURL + '/v2/devices/thermostats/'+ deviceID + "?apikey=" + global_conusmerKey
            def headers = [ Authorization: 'Bearer ' + state.access_token ]
            def contentType = 'application/json'
            def params = [ uri: uri, headers: headers, contentType: contentType ]
            LogDebug("Location Discovery-params ${params}")

            //add error checking
            def reJson =''
            try 
            {
                httpGet(params) { response ->
                    def reCode = response.getStatus();
                    reJson = response.getData();
                    LogDebug("reCode: {$reCode}")
                    LogDebug("reJson: {$reJson}")
                }
            }
            catch (groovyx.net.http.HttpResponseException e) 
            {
                LogError("Login failed -- ${e.getLocalizedMessage()}: ${e.response.data}")
            }

            reJson.devices.each {
                LogDebug("DeviceID: ${it.deviceID}")
                LogDebug("DeviceID: ${it.userDefinedDeviceName}")



        }
    }
}
*/