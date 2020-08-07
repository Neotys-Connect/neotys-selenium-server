package com.neotys.selenium.server;

import jdk.nashorn.internal.runtime.regexp.joni.Regex;
import sun.security.util.ArrayUtil;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpProxySelector extends ProxySelector {
    private static final Logger log = Logger.getLogger(HttpProxySelector.class.getName());

    ProxySelector defsel = null;
    ModeHelper mode;
    HttpProxySelector(ProxySelector def, ModeHelper mode) {
        defsel = def;

        this.mode = mode;
        log.setLevel(mode.getLogger().getLevel());
    }

    public java.util.List<Proxy> select(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI can't be null.");
        }
        List<String> nonProxiedHosts = Arrays.asList(mode.getNonProxyHosts().split("\\|"));

        String protocol = uri.getScheme();
        if ("http".equalsIgnoreCase(protocol) ||
                "https".equalsIgnoreCase(protocol)) {
            if(!nonProxiedHosts.stream().anyMatch(spec -> uriMatchesExcludeSpec(uri, spec))) {
                log.fine("select for: " + uri.toString());
                ArrayList<Proxy> l = new ArrayList<Proxy>();
                Proxy p = mode.getHttpProxy();
                if (p != null)
                    l.add(p);
                // Populate the ArrayList with proxies
                return l;
            }
        }
        if (defsel != null) {
            return defsel.select(uri);
        } else {
            ArrayList<Proxy> l = new ArrayList<Proxy>();
            l.add(Proxy.NO_PROXY);
            return l;
        }
    }

    private boolean uriMatchesExcludeSpec(URI uri, String spec) {
        String s = uri.getHost();
        Pattern p = Pattern.compile(spec.replaceAll("\\*",".*?"));
        Matcher m = p.matcher(s);
        if (m.find()) {
            return true;
        }
        return false;
    }

    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (uri == null || sa == null || ioe == null) {
            throw new IllegalArgumentException("Arguments can't be null.");
        }
        if (defsel != null)
            defsel.connectFailed(uri, sa, ioe);
    }
}