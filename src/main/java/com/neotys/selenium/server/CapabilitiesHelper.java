package com.neotys.selenium.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CapabilitiesHelper {

    private static final Logger log = Logger.getLogger(CapabilitiesHelper.class.getName());

    public static Map<String, Object> augmentRequestedCapabilitiesWithMode(Map<String, Object> caps) {
        Map<String, Object> requestedCapability = caps;
        ModeHelper mode = ModeHelper.fromCapabilities(caps);

        if (mode.isDesign()) {
            if (mode.createDesign() != null) {
                Map<String, Object> proxy = new HashMap<>();
                proxy.put("proxyType", "manual");
                proxy.put("httpProxy", String.format("%s:%s", mode.getHost(), mode.getRecorderPort()));
                //proxy.put("noProxy", "127.0.0.1,localhost");
                requestedCapability.put("proxy", proxy);
            } else {
                log.severe("Could not start the NeoLoad design session. Is NeoLoad running with a project open and not in recording mode already?");
            }
        }
        if (mode.isEUE()) {
            HashMap<String, Object> loggingPrefs = new HashMap<>();
            loggingPrefs.put("browser", "ALL");
            loggingPrefs.put("driver", "ALL");
            loggingPrefs.put("performance", "ALL");
            caps.put("loggingPrefs", loggingPrefs);

            if ("chrome".equals((String) caps.get("browserName"))) {
                // https://stackoverflow.com/a/56852955
                HashMap<String, Object> perfLoggingPrefs = new HashMap<>();
                perfLoggingPrefs.put("enableNetwork", true);
                perfLoggingPrefs.put("enablePage", true);
                perfLoggingPrefs.put("traceCategories", "toplevel,blink.console,blink.user_timing,benchmark" +
                        ",loading,latencyInfo,devtools.timeline,blink.image_decoding,disabled-by-default-devtools.timeline" +
                        ",disabled-by-default-devtools.timeline.frame,disabled-by-default-devtools.timeline.stack" +
                        ",devtools.timeline.picture,disabled-by-default-devtools.screenshot");

                // https://stackoverflow.com/questions/56812190/protractor-log-type-performance-not-found-error
                // https://stackoverflow.com/questions/47316649/can-i-programatically-get-chrome-devtools-performance-information

                String getKey = caps.get("chromeOptions") != null ? "chromeOptions" : "goog:chromeOptions";
                HashMap<String, Object> chromeOptions = (caps.get(getKey) != null ? (HashMap<String, Object>) caps.get(getKey) : new HashMap<>());
                chromeOptions.put("w3c", false);

                chromeOptions.put("perfLoggingPrefs", perfLoggingPrefs);

                ArrayList<String> args = (chromeOptions.get("args") != null ? (ArrayList<String>) chromeOptions.get("args") : new ArrayList<String>());
                if (args.stream().anyMatch(s -> s.toLowerCase().contains("headless")))
                    args.add("--window-size=1366,768'"); // to combat the dreaded 'element not interactable' when window size not set at all in headless mode
                chromeOptions.put("args", args);

                caps.put("chromeOptions", chromeOptions);
                caps.put("goog:chromeOptions", chromeOptions);
                caps.put("loggingPrefs", loggingPrefs);
                caps.put("goog:loggingPrefs", loggingPrefs);
            }
        }
        return requestedCapability;
    }
}
