package com.neotys.selenium.server;

import com.jayway.jsonpath.JsonPath;
import com.neotys.rest.dataexchange.model.Entry;
import com.neotys.rest.dataexchange.model.EntryBuilder;
import com.neotys.rest.dataexchange.model.StatusBuilder;
import net.minidev.json.JSONArray;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.math.BigDecimal;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CommandProcessor {

    private static final Logger log = Logger.getLogger(CommandProcessor.class.getName());

    private static String PriorW3CMeasurementsKey = "PriorW3CMeasurements";
    private static String splitChar = Character.toString((char)30);
    public static String JavascriptBackgroundExecutorKey = "neoloadBackgroundExecutor";

    private final NeoLoadSession _session;

    private CommandProcessor(NeoLoadSession session, Level inheritedLogLevel) {
        this._session = session;
        LogUtils.setLoggerLevel(log, session.getSession().getRequestedCapabilities(), inheritedLogLevel);
    }

    public static CommandProcessor get(NeoLoadSession neoLoadSession, Level inheritedLogLevel) {
        TestSession session = neoLoadSession.getSession();
        Object prior = session.get("CommandProcessor");
        if(!(prior != null && prior instanceof CommandProcessor))
            session.put("CommandProcessor", new CommandProcessor(neoLoadSession,inheritedLogLevel));
        return (CommandProcessor)session.get("CommandProcessor");
    }

    public void processUrl(RequestCommand cmd) {
        String url = cmd.getValueOf("$.url");
        if(this._session.getCurrentTransactionName() == null)
        {
            try {
                java.net.URL uri = new java.net.URL(url);
                String transactionName = String.format("%s %s",
                        "Open", uri.getPath());
                this._session.startTransaction(transactionName, true);
            } catch(Exception ex) {
                log.warning("Could not derive a transaction name from command: " + cmd.toString());
            }
        }
        log.fine("processUrl[url]: " + url);
    }

    public void processClick(RequestCommand cmd) {
        log.fine(cmd.toString());
        RequestCommand prior = this._session.getLastSelectedElementsRequestCommand();
        if(prior != null) {
            String currentId = cmd.getValueOf("$.id");
            Object values = prior.getResponse().getValuesOf("$.value.*");
            JSONArray arr = (values instanceof JSONArray ? (JSONArray)values : null);
            if(arr != null && matchesAnyElementId(arr,currentId))
                if(!(this._session.getCurrentTransactionName() != null && !this._session.isCurrentTransactionAuto())) {
                    String unique = getElementText(currentId);
                    if(!(unique != null && unique.trim().length() > 0))
                        unique = getElementDOMID(currentId);
                    if(!(unique != null && unique.trim().length() > 0))
                        unique = getElementDOMName(currentId);

                    if(!(unique != null && unique.trim().length() > 0)) {
                        String tagName = getElementTagName(currentId);
                        unique = tagName + " " + prior.getValueOf("$.value");
                    }

                    String transactionName = String.format("Click %s", unique);
                    this._session.startTransaction(transactionName, true);
                }
        }
    }

    public void processCookie(RequestCommand cmd) {
        try {
            String name = cmd.getValueOf("$.cookie.name");
            if (NeoLoadSession.TransactionCookieName.equalsIgnoreCase(name)) {
                String value = cmd.getValueOf("$.cookie.value", null);
                if (value != null)
                    this._session.startTransaction(value, false);
                else
                    this._session.stopTransaction();
            }
        } catch (Exception ex) {
            log.throwing(NeoLoadRemoteProxy.class.getCanonicalName(), "processCookie", ex);
        }
    }

    private static boolean matchesAnyElementId(JSONArray arr, String currentId) {
        boolean match = false;
        for(Object o : arr) {
            if(o instanceof String)
                match = currentId.equalsIgnoreCase((String)o);
            if(o instanceof LinkedHashMap) {
                Object ELEMENT = ((LinkedHashMap)o).get("ELEMENT");
                if(ELEMENT != null && ELEMENT instanceof String)
                    match = currentId.equalsIgnoreCase((String)ELEMENT);
            }
            if(match)
                break;
        }
        return match;
    }

    private String getElementText(String elementId) {
        return getElementData(elementId, "text");
    }
    private String getElementTagName(String elementId) {
        return getElementData(elementId, "name");
    }
    private String getElementDOMID(String elementId) {
        return getElementData(elementId, "attribute/id");
    }
    private String getElementDOMName(String elementId) {
        return getElementData(elementId, "attribute/name");
    }
    private String getElementData(String elementId, String path) {
        TestSession session = this._session.getSession();
        String sessionId = session.getExternalKey().getKey();
        try {
            Hub hub = session.getSlot().getProxy().getRegistry().getHub();
            String url = String.format(
                    "%s/session/%s/element/%s/%s",
                    hub.getWebDriverHubRequestURL(), sessionId, elementId, path);
            HttpRequest req = new HttpRequest(HttpMethod.GET, url);
            HttpResponse res = getClient(new URL(url)).execute(req);
            PayloadContainer pc = new PayloadContainer(res.getContentString());
            return pc.getValueOf("$.value");
        } catch(Exception ex) {
            ex = ex;
        }
        return null;
    }

    public String getCurrentTransactionNameFromWindow() {
        PayloadContainer pc = executeJavascript("return window.nl_transaction");
        Object oTimers = JsonPath.read(pc.json, "$.value");
        return (oTimers != null ? oTimers.toString() : null);
    }

    private PayloadContainer executeJavascript(String script) {
        TestSession session = this._session.getSession();
        String sessionId = session.getExternalKey().getKey();
        try {
            Hub hub = session.getSlot().getProxy().getRegistry().getHub();
            String url = String.format(
                    "%s/session/%s/execute/sync",
                    hub.getWebDriverHubRequestURL(), sessionId);
            HttpRequest req = new HttpRequest(HttpMethod.POST, url);
            Map<String, Object> json = new HashMap<>();
            json.put("script", script);
            json.put("args", JavascriptBackgroundExecutorKey.split(","));
            byte[] bytes = JsonPath.parse(json).jsonString().getBytes("UTF-8");
            req.setContent(bytes);
            HttpClient cli = getClient(new URL(url));
            boolean busy = session.getSlot().getProxy().isBusy();
            HttpResponse res = cli.execute(req);
            return new PayloadContainer(res.getContentString());
        } catch(Exception ex) {
            log.severe(ex.toString());
        }
        return null;
    }

    private HttpClient getClient(URL url) {
        TestSession session = this._session.getSession();
        TestSlot slot = session.getSlot();
        Integer browserTimeout = slot.getProxy().getConfig().browserTimeout;
        return slot.getProxy().getHttpClient(url, browserTimeout, browserTimeout);
    }

    public List<Entry> includeW3C(List<String> basePath) {
        TestSession session = this._session.getSession();
        List<Entry> ret = new ArrayList<>();

        List<String> pageEventTypes = getPageW3CCaptureEventTypes();
        Function<String,Boolean> fInPageTypes = new Function<String, Boolean>() {
            @Override
            public Boolean apply(String s) {
                return pageEventTypes.stream().anyMatch(typ -> s.equalsIgnoreCase(typ));
            }
        };
        Function<String,Boolean> fNotInPageTypes = new Function<String, Boolean>() {
            @Override
            public Boolean apply(String s) {
                return !pageEventTypes.stream().anyMatch(typ -> s.equalsIgnoreCase(typ));
            }
        };

        Map<String,Object> current = captureW3C();
        Map<String,Object> prior = null;
        if(session.get(PriorW3CMeasurementsKey) != null) {
            prior = (session.get(PriorW3CMeasurementsKey) != null ? (Map<String,Object>)session.get(PriorW3CMeasurementsKey) : null);
        }
        Map<String,Object> currentPage = filterEventsByTypes(current, fInPageTypes);
        if(prior != null) {

            Map<String,Object> priorPage = filterEventsByTypes(prior, fInPageTypes);
            HashMap<String,Object> newEntries = new HashMap<>();
            for(HashMap.Entry<String,Object> c : currentPage.entrySet()) {
                if(!priorPage.entrySet().stream().anyMatch(p -> w3cMeasurementsMatch(c,p)))
                    newEntries.put(c.getKey(),c.getValue());
            }

            if(newEntries.size() < 1) {
                Map<String,Object> currentOther = filterEventsByTypes(current, fNotInPageTypes);
                current = currentOther;
                ret.addAll(getEntriesFromMeasures(current, basePath));
            } else {
                current = currentPage;
                ret.addAll(getEntriesFromMeasures(current, basePath));
            }
            /**/
        } else {
            if(currentPage.size()>0)
                current = currentPage;
            ret.addAll(getEntriesFromMeasures(current, basePath));
        }
        session.put(PriorW3CMeasurementsKey, current);
        return ret;
    }

    private static Map<String,Object> filterEventsByTypes(Map<String,Object> source, Function<String,Boolean> fMatch) {
        HashMap<String,Object> hm = new HashMap<String, Object>() {
            {
                source.entrySet().stream().filter(e -> {
                            String s = (String)e.getKey().split(splitChar)[0];
                            return fMatch.apply(s);
                        }
                ).forEachOrdered(e -> put(e.getKey(),e.getValue()));
            }
        };
        return hm;
    }

    private static boolean w3cMeasurementsMatch(HashMap.Entry<String,Object> c, HashMap.Entry<String,Object> p) {
        try {
            boolean namesMatch = p.getKey().equalsIgnoreCase(c.getKey());
            boolean valuesMatch = new BigDecimal(p.getValue().toString()).compareTo(new BigDecimal(c.getValue().toString())) == 0;
            return namesMatch && valuesMatch;
        } catch (Exception ex) {
            log.severe(ex.toString());
        }
        return false;
    }

    public static boolean isBackgroundJavascriptExecutor(RequestCommand cmd) {
        return cmd.getContentText().contains(JavascriptBackgroundExecutorKey);
    }

    private String getLastListItem(List<String> path) {
        return (path.size() > 0 ? path.get(path.size()-1) : null);
    }

    private static List<String> getPageW3CCaptureEventTypes() {
        return Arrays.asList("net|browser|mark|measure|paint|longtask".split("\\|"));
    }
    private HashMap<String,Object> captureW3C() {
        TestSession session = this._session.getSession();
        HashMap<String,Object> all = new HashMap<>();

        PayloadContainer pcTimers = executeJavascript(getW3CTimersScript());
        Object oTimers = JsonPath.read(pcTimers.json, "$.value");
        all.putAll((HashMap<String,Object>)oTimers);

        ModeHelper mode = this._session.getMode();
        List<String> pageEventTypes = getPageW3CCaptureEventTypes();
        List<String> desiredEventTypes = mode.getDesiredW3CEventTypes();
        List<String> requiredEventTypes = new ArrayList<String>(new HashSet<String>(
                Stream.concat(
                        pageEventTypes.stream().map(s -> s.trim().toLowerCase()),
                        desiredEventTypes.stream()).map(s -> s.trim().toLowerCase())
                        .collect(Collectors.toList())));

        PayloadContainer pcTimeline = executeJavascript(getW3CTimelineScript());
        Object oTimeline = JsonPath.read(pcTimeline.json, "$.value");
        if(oTimeline != null) {
            if(oTimeline instanceof net.minidev.json.JSONArray) {
                net.minidev.json.JSONArray arr = (net.minidev.json.JSONArray)oTimeline;
                for(int i=0; i<arr.size(); i++) {
                    LinkedHashMap<String, Object> hm = (LinkedHashMap<String, Object>)arr.get(i);
                    String entryType = (String)hm.get("entryType");
                    if(hm != null && requiredEventTypes.stream().anyMatch(s -> s.equalsIgnoreCase(entryType))) {
                        String name = (String)hm.get("name");
                        Double value = Double.parseDouble(hm.get("startTime").toString());
                        all.put(getPName(entryType, name), value);
                    }
                }
            }
        }

        return all;
    }
    private static List<Entry> getEntriesFromMeasures(Map<String, Object> all, List<String> basePath) {
        List<Entry> ret = new ArrayList<Entry>();
        final StatusBuilder sb = new StatusBuilder();
        sb.state(com.neotys.rest.dataexchange.model.Status.State.PASS);
        com.neotys.rest.dataexchange.model.Status status = sb.build();
        for(Map.Entry me : all.entrySet()) {
            EntryBuilder eb = new EntryBuilder(
                    ListUtils.merge(basePath, ListUtils.merge(
                            Arrays.asList("W3C"),
                            Arrays.asList(((String)me.getKey()).split(splitChar))
                    )), System.currentTimeMillis());

            eb.unit("ms");
            eb.value(Double.parseDouble(me.getValue().toString()));
            eb.status(status);
            ret.add(eb.build());
        }
        return ret;
    }
    private static String getPName(String ...s) {
        return String.join(splitChar, s);
    }
    private static String getW3CTimersScript() {
        // https://developer.mozilla.org/en-US/docs/Web/API/Navigation_timing_API#Browser_compatibility
        HashMap<String,String> timings = new HashMap<>();
        timings.put(getPName("net","redirect"), "redirectStart:redirectEnd");
        timings.put(getPName("net","cache"), "fetchStart:domainLookupStart");
        timings.put(getPName("net","dns"), "domainLookupStart:domainLookupEnd");
        timings.put(getPName("net","tcp"), "connectStart:connectEnd");
        timings.put(getPName("net","ssl"), "secureConnectionStart:connectEnd");
        timings.put(getPName("net","request"), "requestStart:responseStart");
        timings.put(getPName("net","response"), "responseStart:responseEnd");
        timings.put(getPName("net","requestToResponseEnd"), "requestStart:responseEnd");
        timings.put(getPName("browser","preprocessing"), "responseEnd:domLoading");
        timings.put(getPName("browser","domContentLoad"), "domContentLoadedEventStart:domContentLoadedEventEnd");
        timings.put(getPName("browser","onLoad"), "loadEventStart:loadEventEnd");
        timings.put(getPName("browser","interactive"), "domLoading:domInteractive");
        timings.put(getPName("browser","documentComplete"), "domLoading:domComplete");
        timings.put(getPName("browser","navigateToLoadEnd"), "navigationStart:loadEventEnd");
        String navPrefix = "performance.timing";
        StringBuilder sb = new StringBuilder();
        sb.append("(args) => { var ret = {};");
        sb.append("var noz = (v) => (v&&v!=null?v:0);");
        sb.append("var asifa = (r,p,a) => { if(noz(a)>0) { r[p]=a; } };");
        sb.append("var asifb = (r,p,a,b) => { if(noz(a)>0&&noz(b)>0) { r[p]=b-a; } };");
        for(Map.Entry me : timings.entrySet()) {
            String[] spec = ((String)me.getValue()).split(":");
            if(spec.length > 1)
                sb.append(String.format("\nasifb(ret,'%s',%s,%s);",
                        (String)me.getKey(), navPrefix+"."+spec[0], navPrefix+"."+spec[1]
                ));
            else
                sb.append(String.format("\nasifb(ret,'%s',%s);",
                        (String)me.getKey(), navPrefix+"."+spec[0]
                ));
        }
        sb.append("return ret;}");
        return String.format("return (%s).apply(null, arguments)", sb.toString());
    }
    private static String getW3CTimelineScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("(args) => performance.getEntries()");
        return String.format("return (%s).apply(null, arguments)", sb.toString());
    }

    private String getChromeProfilerEnableJavascript() {
        StringBuilder sb = new StringBuilder();
        sb.append("(args) => { console.profile('neoload'); return true; }");
        return String.format("return (%s).apply(null, arguments)", sb.toString());
    }
    private String getChromeProfilerFinalizeJavascript() {
        StringBuilder sb = new StringBuilder();
        sb.append("(args) => { console.profileEnd('neoload'); return null; }"); // return console.profile[0];
        return String.format("return (%s).apply(null, arguments)", sb.toString());
    }

}
