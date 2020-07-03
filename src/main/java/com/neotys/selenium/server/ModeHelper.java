package com.neotys.selenium.server;

import com.neotys.rest.dataexchange.client.DataExchangeAPIClient;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClientFactory;
import com.neotys.rest.dataexchange.model.ContextBuilder;
import com.neotys.rest.design.client.DesignAPIClient;
import com.neotys.rest.design.client.DesignAPIClientFactory;
import org.openqa.grid.internal.TestSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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

    public static String ScriptNameKey = "neoload:script";

    public static String HostCapsDefault = "localhost";
    public static int PortCapsDefault = 7400;

    private static final Logger log = Logger.getLogger(ModeHelper.class.getName());
    private List<String> desiredW3CEventTypes = null;

    private ModeHelper() {}

    private String host;
    private int port;
    private String mode;
    private boolean debug = false;
    private String location;

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

    public static ModeHelper fromCapabilities(Map<String, Object> caps) {
        ModeHelper ret = new ModeHelper();
        ret.mode = getMode(caps.containsKey(ModeCapsKey) ? (String) caps.get(ModeCapsKey) : MODE_NONE);
        ret.host = caps.containsKey(HostCapsKey) ? (String) caps.get(HostCapsKey) : HostCapsDefault;
        ret.port = caps.containsKey(PortCapsKey) ? Integer.parseInt((String) caps.get(PortCapsKey)) : PortCapsDefault;
        ret.debug = caps.containsKey(DebugCapsKey) ? (boolean) caps.get(DebugCapsKey) : false;
        ret.location = caps.containsKey(LocationCapsKey) ? (String) caps.get(LocationCapsKey) : null;
        ret.desiredW3CEventTypes = getW3CDesiredEventTypesFromCaps(caps);
        String browserName = caps.get("browserName") != null ? caps.get("browserName").toString() : null;
        ret._isFF = "firefox".equalsIgnoreCase(browserName);
        ret._isCh = "chrome".equalsIgnoreCase(browserName);
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
            String url = String.format("http://%s:%s/DataExchange/v1/Service.svc/",this.host,this.port);
            try {
                System.setProperty("sun.net.client.defaultReadTimeout", "5000");
                System.setProperty("sun.net.client.defaultConnectTimeout", "5000");
                ContextBuilder cb = fContext != null ? fContext.get() : null;
                if(cb == null) {
                    cb = new ContextBuilder();
                    Map<String,Object> caps = session.getSlot().getCapabilities();
                    cb.os((String)caps.get("platformName"))
                            .location(this.getLocation())
                            .software((String)caps.get("browserName"));
                }
                return DataExchangeAPIClientFactory.newClient(url,cb.build());
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
}
