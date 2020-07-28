package com.neotys.selenium.server;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogUtils {
    public static void setLoggerLevel(Logger log, Map<String, Object> caps, Level inheritedLogLevel) {
        if(isNeoLoadDebug(caps))
            log.setLevel(Level.ALL);
        else
            log.setLevel(inheritedLogLevel);
    }

    public static boolean isNeoLoadDebug(Map<String, Object> caps) {
        return caps.containsKey(ModeHelper.DebugCapsKey) ? (boolean) caps.get(ModeHelper.DebugCapsKey) : false;
    }
}
