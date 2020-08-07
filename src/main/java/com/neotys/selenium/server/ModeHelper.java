package com.neotys.selenium.server;

import com.neotys.rest.dataexchange.client.DataExchangeAPIClient;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClientFactory;
import com.neotys.rest.dataexchange.model.ContextBuilder;
import com.neotys.rest.design.client.DesignAPIClient;
import com.neotys.rest.design.client.DesignAPIClientFactory;
import org.openqa.grid.internal.TestSession;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ModeHelper {
    public static String MODE_NONE = "NONE";
    public static String MODE_EUE = "EUE";
    public static String MODE_DESIGN = "DESIGN";

    public static String ModeCapsKey = "neoload:mode";
    public static String HostCapsKey = "neoload:host";
    public static String PortCapsKey = "neoload:port";
    public static String DebugCapsKey = "neoload:debug";
    public static String LocationCapsKey = "neoload:location";
    public static String W3CCaptureEventTypesCapsKey = "neoload:w3cEventTypes";

    public static String UseSystemProxiesKey = "neoload:useSystemProxies";
    public static String ProxyHostKey = "neoload:proxyHost";
    public static String ProxyPortKey = "neoload:proxyPort";
    public static String ProxyUsernameKey = "neoload:proxyUser";
    public static String ProxyPasswordKey = "neoload:proxyPassword";
    public static String NonProxyHostsKey = "neoload:nonProxyHosts";

    public static String ScriptNameKey = "neoload:script";

    public static String HostCapsDefault = "localhost";
    public static int PortCapsDefault = 7400;

    public static String DefaultLocation = "UnspecifiedLocation";
    public static String DefaultPlatform = "UnspecifiedPlatform";
    public static String DefaultSoftware = "UnspecifiedSoftware";

    private static final Logger log = Logger.getLogger(ModeHelper.class.getName());
    private List<String> desiredW3CEventTypes = null;

    private ModeHelper() {}

    private String host;
    private int port;
    private String mode;
    private boolean debug = false;
    private String location;

    private boolean useSystemProxies = false;
    private String proxyHost = null;
    private int proxyPort = 0;
    private String proxyUsername = null;
    private String proxyPassword = null;
    private String nonProxyHosts = null;
    public Proxy getHttpProxy() {
        if(proxyHost == null) return null;

        Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost,proxyPort));
        if(proxyUsername != null) {
            Authenticator auth = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(proxyUsername, proxyPassword.toCharArray());
                }
            };
            // TODO: Update NeotysAPIClientOlingo.initializeConnection to specify at a per connection level
            Authenticator.setDefault(auth);
        }
        return p;
    }
    public String getNonProxyHosts() { return nonProxyHosts; }

    public String getMode() { return this.mode; }
    public String getHost() { return this.host; }
    public int getPort() { return this.port; }
    public boolean isDebug() { return this.debug; }

    private boolean _isFF = false;
    private boolean _isCh = false;
    public boolean isFirefox() { return this._isFF; }
    public boolean isChrome() { return this._isCh; }

    private Exception lastException = null;

    private static String getMode(String input) {
        if(input == null) return MODE_NONE;
        switch(input.toLowerCase().trim()) {
            case "e":
            case "enduserexperience":
                return MODE_EUE;
            case "d":
            case "design":
                return MODE_DESIGN;
            default:
                return MODE_NONE;
        }
    }

    public Logger getLogger() { return log; }

    public static ModeHelper fromCapabilities(Map<String, Object> caps, Level logLevel) {
        ModeHelper ret = new ModeHelper();
        LogUtils.setLoggerLevel(log, caps, logLevel);
        ret.mode = getMode(caps.containsKey(ModeCapsKey) ? (String) caps.get(ModeCapsKey) : MODE_NONE);
        ret.host = caps.containsKey(HostCapsKey) ? (String) caps.get(HostCapsKey) : HostCapsDefault;
        ret.port = caps.containsKey(PortCapsKey) ? Integer.parseInt((String) caps.get(PortCapsKey)) : PortCapsDefault;
        ret.debug = LogUtils.isNeoLoadDebug(caps);
        ret.location = caps.containsKey(LocationCapsKey) ? (String) caps.get(LocationCapsKey) : DefaultLocation;
        ret.desiredW3CEventTypes = getW3CDesiredEventTypesFromCaps(caps);
        String browserName = caps.get("browserName") != null ? caps.get("browserName").toString() : null;
        ret._isFF = "firefox".equalsIgnoreCase(browserName);
        ret._isCh = "chrome".equalsIgnoreCase(browserName);

        ret.useSystemProxies = caps.containsKey(UseSystemProxiesKey) ? String.format("%s",caps.get(UseSystemProxiesKey)).toLowerCase().contains("true") : false;
        ret.proxyHost = caps.containsKey(ProxyHostKey) ? (String) caps.get(ProxyHostKey) : null;
        ret.proxyPort = caps.containsKey(ProxyPortKey) ? Integer.parseInt(((Long)caps.get(ProxyPortKey)).toString()) : 0;
        ret.proxyUsername = caps.containsKey(ProxyUsernameKey) ? (String) caps.get(ProxyUsernameKey) : null;
        ret.proxyPassword = caps.containsKey(ProxyPasswordKey) ? (String) caps.get(ProxyPasswordKey) : null;
        ret.nonProxyHosts = caps.containsKey(NonProxyHostsKey) ? (String) caps.get(NonProxyHostsKey) : null;

        return ret;
    }

    private static List<String> getW3CDesiredEventTypesFromCaps(Map<String, Object> caps) {
        Object o = caps.get(W3CCaptureEventTypesCapsKey);
        List<String> values = new ArrayList<>();
        if(o != null) {
            if (o instanceof String)
                values = Arrays.stream(String.format("%s", o).split("\\|")).collect(Collectors.toList());
            else if (o instanceof ArrayList)
                values = (ArrayList) o;
        }
        values = values.stream().map(s -> s.trim().toLowerCase()).collect(Collectors.toList());
        return values;
    }

    public boolean isDesign() {
        return MODE_DESIGN.equals(this.mode);
    }
    public boolean isEUE() {
        return ModeHelper.MODE_EUE.equals(this.mode);
    }

    public DataExchangeAPIClient createEUE(TestSession session, Supplier<ContextBuilder> fContext) {
        if(isEUE()) {
            log.fine("createEUE[0]");
            String url = String.format("http://%s:%s/DataExchange/v1/Service.svc/",this.host,this.port);
            log.fine("createEUE[1]: " + url);

            try {
                System.setProperty("sun.net.client.defaultReadTimeout", "15000");
                System.setProperty("sun.net.client.defaultConnectTimeout", "15000");

                System.setProperty("java.net.useSystemProxies", new Boolean(useSystemProxies).toString());
                if(proxyHost != null) {
                    System.setProperty("http.proxyHost", proxyHost);
                    System.setProperty("http.proxyPort", new Integer(proxyPort).toString());
                    if(proxyUsername != null) System.setProperty("http.proxyUser", proxyUsername);
                    if(proxyPassword != null) System.setProperty("http.proxyPassword", proxyPassword);
                    if(nonProxyHosts != null) System.setProperty("http.nonProxyHosts", nonProxyHosts);

                    // TODO: Update NeotysAPIClientOlingo.initializeConnection to specify at a per connection level
                    HttpProxySelector ps = new HttpProxySelector(ProxySelector.getDefault(), this);
                    ProxySelector.setDefault(ps);
                } else {
                    System.clearProperty("http.proxyHost");
                    System.clearProperty("http.proxyPort");
                    System.clearProperty("http.proxyUser");
                    System.clearProperty("http.proxyPassword");
                    System.clearProperty("http.nonProxyHosts");

                    // TODO: Update NeotysAPIClientOlingo.initializeConnection to specify at a per connection level
                    ProxySelector.setDefault(ProxySelector.getDefault());

                }

                //log.fine("createEUE[beforeHTML]");
                //String html = getHTML(url);
                //log.fine("createEUE[afterHTML]: " + html.length());

                log.fine("createEUE[2]");
                ContextBuilder cb = fContext != null ? fContext.get() : null;
                log.fine("createEUE[3]");
                if(cb == null) {
                    log.fine("createEUE[4]");
                    Map<String,Object> caps = session.getSlot().getCapabilities();
                    log.fine("createEUE[5]");
                    cb = this.createEUEContext(caps);
                    log.fine("createEUE[6]");
                }
                log.fine("createEUE[7]");
                return DataExchangeAPIClientFactory.newClient(url,cb.build());
            } catch(Exception ex) {
                this.lastException = ex;
                if(ex == null) {
                    log.severe("Error, empty exception when create a NeoLoad Data Exchange API client.");
                } else {
                    log.severe("createEUE[ERROR]: " + ex.toString());
                    if (ex.getMessage() != null && ex.getMessage().toUpperCase().contains("NL-DATAEXCHANGE-NO-TEST-RUNNING")) {
                        log.severe("No NeoLoad Test is running");
                    } else {
                        StringWriter sw = new StringWriter();
                        ex.printStackTrace(new PrintWriter(sw));
                        String exceptionAsString = sw.toString();
                        log.severe("Could not create a NeoLoad Data Exchange API client:" + exceptionAsString);
                    }
                }
            }
        }
        return null;
    }

    public DesignAPIClient createDesign() {
        if(isDesign()) {
            String url = String.format("http://%s:%s/Design/v1/Service.svc/",this.host,this.port);
            try {
                return DesignAPIClientFactory.newClient(url);
            } catch(Exception ex) {
                this.lastException = ex;
                if(ex.getMessage().toUpperCase().contains("NL-DATAEXCHANGE-NO-TEST-RUNNING")) {
                    log.severe("No NeoLoad Test is running");
                } else {
                    log.severe("Could not create a NeoLoad Data Exchange API client:" + ex.toString());
                }
            }
        }
        return null;
    }

    public int getRecorderPort() {
        if(isDesign()) {
            try {
                DesignAPIClient cli = createDesign();
                return cli.getRecorderSettings().getProxySettings().getPort();
            } catch(Exception ex) {
                log.severe("Could not obtain recording port: " + ex.toString());
            }
        }
        return 8090;
    }

    public boolean isOnMode() {
        return MODE_DESIGN.equals(this.mode) || MODE_EUE.equals(this.mode);
    }

    public Exception getLastException() {
        return this.lastException;
    }

    public String getLocation() {
        return this.location;
    }

    public List<String> getDesiredW3CEventTypes() {
        return this.desiredW3CEventTypes==null ? Arrays.asList(new String[0]) : this.desiredW3CEventTypes;
    }


    public static String getHTML(String urlToRead) throws Exception {
        StringBuilder result = new StringBuilder();
        URL url = new URL(urlToRead);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String line;
        while ((line = rd.readLine()) != null) {
            result.append(line);
        }
        rd.close();
        return result.toString();
    }

    public ContextBuilder createEUEContext(Map<String, Object> caps) {
        ContextBuilder cb = new ContextBuilder();

        String platform = (String)caps.get("platformName");
        if(platform == null) platform = (String)caps.get("platform");
        if(platform == null) platform = DefaultPlatform;
        log.fine("createEUEContext[platform]: " + platform);
        cb = cb.os(platform);

        String location = this.getLocation();
        if(location == null) location = DefaultLocation;
        log.fine("createEUEContext[location]: " + location);
        cb = cb.location(location);

        String browser = (String)caps.get("browserName");
        if(browser == null) browser = (String)caps.get("browser");
        if(browser == null) browser = DefaultSoftware;
        log.fine("createEUEContext[browser]: " + browser);
        cb = cb.software(browser);

        return cb;
    }
}
