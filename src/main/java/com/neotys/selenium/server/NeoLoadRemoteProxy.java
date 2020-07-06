package com.neotys.selenium.server;

import com.jayway.jsonpath.JsonPath;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClient;
import com.neotys.rest.dataexchange.model.ContextBuilder;
import com.neotys.rest.dataexchange.model.Entry;
import com.neotys.rest.dataexchange.model.EntryBuilder;
import com.neotys.rest.dataexchange.model.StatusBuilder;
import com.neotys.rest.design.client.DesignAPIClient;
import com.neotys.rest.design.model.SetContainerParams;
import com.neotys.rest.design.model.StartRecordingParams;
import com.neotys.rest.design.model.StopRecordingParams;
import com.neotys.rest.design.model.UpdateUserPathParams;
import com.neotys.rest.runtime.model.Status;
import net.minidev.json.JSONArray;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.RemoteException;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.internal.TestSlot;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
https://stackoverflow.com/questions/43395659/properties-for-selenium-grid-hub-node-config
 */
public class NeoLoadRemoteProxy extends org.openqa.grid.selenium.proxy.DefaultRemoteProxy {

    private static final Logger log = Logger.getLogger(NeoLoadRemoteProxy.class.getName());
    private static String CLISessionKey = "cli";
    public static String CLIWorkingKey = "CLIWorking";
    public static String ScriptNameKey = "ScriptName";
    public static String ChosenScriptNameKey = "ChosenScriptName";
    public static String DefaultScriptName = "UserPath";
    public static String TransactionCookieName = "nl_transaction";
    public static String TransactionIsAuto = "TransactionIsAuto";
    public static String CurrentlySelectedElementsKey = "CurrentlySelectedElements";
    public static String JavascriptBackgroundExecutorKey = "neoloadBackgroundExecutor";
    public static String SessionCreationExceptionKey = "SessionCreationExceptionKey";

    private GridRegistry registry;

    public NeoLoadRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
        this.registry = registry;
        log.fine("NeoLoad Selenium Node initialized.");
    }

    @Override
    public TestSession getNewSession(Map<String, Object> caps) {
        Map<String, Object> requestedCapability = caps;
        ModeHelper mode = ModeHelper.fromCapabilities(caps);

        if(mode.isDesign()) {
            if(mode.createDesign() != null) {
                Map<String, Object> proxy = new HashMap<>();
                proxy.put("proxyType", "manual");
                proxy.put("httpProxy", String.format("%s:%s", mode.getHost(), mode.getRecorderPort()));
                //proxy.put("noProxy", "127.0.0.1,localhost");
                requestedCapability.put("proxy", proxy);
            } else {
                log.severe("Could not start the NeoLoad design session. Is NeoLoad running with a project open and not in recording mode already?");
            }
        }
        if(mode.isEUE()) {
            HashMap<String,Object> loggingPrefs = new HashMap<>();
            loggingPrefs.put("browser","ALL");
            loggingPrefs.put("driver","ALL");
            loggingPrefs.put("performance","ALL");
            caps.put("loggingPrefs", loggingPrefs);

            if("chrome".equals((String)caps.get("browserName"))) {
                // https://stackoverflow.com/a/56852955
                HashMap<String,Object> perfLoggingPrefs = new HashMap<>();
                perfLoggingPrefs.put("enableNetwork",true);
                perfLoggingPrefs.put("enablePage",true);
                perfLoggingPrefs.put("traceCategories","toplevel,blink.console,blink.user_timing,benchmark"+
                        ",loading,latencyInfo,devtools.timeline,blink.image_decoding,disabled-by-default-devtools.timeline"+
                        ",disabled-by-default-devtools.timeline.frame,disabled-by-default-devtools.timeline.stack"+
                        ",devtools.timeline.picture,disabled-by-default-devtools.screenshot");

                // https://stackoverflow.com/questions/56812190/protractor-log-type-performance-not-found-error
                // https://stackoverflow.com/questions/47316649/can-i-programatically-get-chrome-devtools-performance-information

                String getKey = caps.get("chromeOptions") != null ? "chromeOptions" : "goog:chromeOptions";
                HashMap<String,Object> chromeOptions = (caps.get(getKey) != null ? (HashMap<String,Object>)caps.get(getKey) : new HashMap<>());
                chromeOptions.put("w3c", false);

                chromeOptions.put("perfLoggingPrefs",perfLoggingPrefs);

                ArrayList<String> args = (chromeOptions.get("args") != null ? (ArrayList<String>)chromeOptions.get("args") : new ArrayList<String>() );
                if(args.stream().anyMatch(s -> s.toLowerCase().contains("headless")))
                    args.add("--window-size=1366,768'"); // to combat the dreaded 'element not interactable' when window size not set at all in headless mode
                chromeOptions.put("args",args);

                caps.put("chromeOptions",chromeOptions);
                caps.put("goog:chromeOptions",chromeOptions);
                caps.put("loggingPrefs", loggingPrefs);
                caps.put("goog:loggingPrefs", loggingPrefs);
            }
        }
        return super.getNewSession(requestedCapability);
    }

    private ModeHelper getMode(TestSession session) { return (ModeHelper)session.get("ModeHelper"); }

    @Override
    public void beforeSession(TestSession session) {
        super.beforeSession(session);
        boolean doSuper = true;

        ModeHelper mode = ModeHelper.fromCapabilities(session.getRequestedCapabilities());
        session.put("ModeHelper", mode);

        if(mode.isOnMode()) {
            log.fine("NeoLoad beforeSession: " + mode.getMode());
            log.fine(String.format("Configured to use %s:%s", mode.getHost(), mode.getPort()));
            session.put(TransactionIsAuto, false);
            session.put(CLIWorkingKey, false);

            if(mode.isEUE()) {
                if(mode.createEUE(session, null) != null)
                    session.put(CLIWorkingKey, true);
                else {
                    doSuper = false;
                    markDataExchangeClientFailed(session, mode.getLastException());
                    log.severe("Cannot connect to NeoLoad Data Exchange API. Killing session.");
                }
            }
            if(mode.isDesign()) {
                if(mode.createDesign() != null) {
                    session.put(CLIWorkingKey, true);
                } else {
                    doSuper = false;
                    session.put(SessionCreationExceptionKey, mode.getLastException());
                    log.severe("Cannot connect to NeoLoad Design API. Killing session.");
                }
            }
        }
        if(!doSuper)
            checkRaiseLastException(session);

    }

    private boolean _hasDesignInitialized(TestSession session) {
        Object v = session.get("_hasDesignInitialized");
        return (v != null) ? (boolean)v : false;
    }
    private boolean _wasDesignInitSuccess(TestSession session) {
        Object v = session.get("_wasDesignInitSuccess");
        return (v != null) ? (boolean)v : false;
    }

    private boolean initDesignSession(TestSession session, String scriptName) {
        if(!_hasDesignInitialized(session)) {

            session.put("_wasDesignInitSuccess", false);

            ModeHelper mode = getMode(session);
            try {
                DesignAPIClient cli = mode.createDesign();
                if (cli == null)
                    return false;
                Status s = cli.getStatus();
                switch (cli.getStatus()) {
                    case READY:
                        cli.startRecording(StartRecordingParams.newBuilder()
                                .virtualUser(scriptName)
                                .build());
                        session.put("_wasDesignInitSuccess", true);
                        break;
                }
                log.fine("Design mode started");
            } catch (Exception ex) {
                log.severe("Could not send data to design API: " + ex.toString());
            }
            session.put("_hasDesignInitialized", true);
        }

        return _wasDesignInitSuccess(session);
    }

    @Override
    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {

        ModeHelper mode = getMode(session);
        if(mode.isOnMode()) {

            log.fine("NeoLoad beforeCommand");

            log.fine("beforeCommand[URI]: " + request.getRequestURI());

            try {
                RequestCommand cmd = RequestCommand.create(request);

                if(!cmd.getContentText().contains(JavascriptBackgroundExecutorKey)) {

                    if (isCLIWorking(session)) {
                        switch (cmd.getCommand()) {
                            case create_session:
                                break;
                            default:
                                String scriptName = getChosenScriptName(session);
                                if(scriptName == null)
                                    scriptName = initScriptName(session, cmd);

                                if (mode.isDesign() && !_hasDesignInitialized(session))
                                    initDesignSession(session, scriptName);
                        }
                    }

                    log.fine("beforeCommand[content]: " + cmd.toString());

                    switch (cmd.getCommand()) {
                        case url:
                            processUrl(session, cmd);
                            break;
                        case cookie:
                            processCookie(session, cmd);
                            break;
                        case click:
                            processClick(session, cmd);
                            break;
                    }
                }
            } catch (Exception ex) {
                log.throwing(log.getClass().getCanonicalName(), "beforeCommand", ex);
            }
        }

        super.beforeCommand(session, request, response);
    }

    private boolean checkRaiseLastException(TestSession session) {
        Exception ex = (Exception)session.get(SessionCreationExceptionKey);
        ModeHelper mode = ModeHelper.fromCapabilities(session.getRequestedCapabilities());
        if(ex != null) {
            addNewEvent(new RemoteException(ex.toString()));
            boolean killSession = true;
            if(ex.toString().toUpperCase().contains("NL-API-ILLEGAL-SESSION")) killSession = true;
            if(mode.isDebug()) killSession = false;
            if(killSession) {
                //session.getSlot().getProxy().teardown();
                registry.removeIfPresent(this);
                //session.getSlot().startReleaseProcess();
                //session.sendDeleteSessionRequest();
                registry.terminate(session, SessionTerminationReason.CREATIONFAILED);
            }
            return true;
        }
        return false;
    }

    private String getChosenScriptName(TestSession session) {
        return (String)session.get(ChosenScriptNameKey);
    }
    private String initScriptName(TestSession session, RequestCommand cmd) {
        String scriptName = (String) session.getRequestedCapabilities().get(ModeHelper.ScriptNameKey);
        //{"script":"return ((title) => { window.nl_script=title; }).apply(null, arguments)","args":["Home Page should have the right title"]}
        if (RequestCommand.CommandEnum.execute.equals(cmd.getCommand())) {
            if ((cmd.getValueOf("$.script", java.util.Optional.of("")) + "").contains("nl_script")) {
                String s = cmd.getContentText();
                Pattern p = Pattern.compile("\"args\":\\[\"(.*?)\"");
                Matcher m = p.matcher(s);
                if (m.find()) {
                    String title = m.group(1);
                    scriptName = title;
                }
            }
        }
        if (!(scriptName != null && scriptName.trim().length() > 0))
            scriptName = DefaultScriptName;
        session.put(ChosenScriptNameKey, scriptName);
        return scriptName;
    }

    @Override
    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        super.afterCommand(session, request, response);

        ModeHelper mode = getMode(session);
        if(mode.isOnMode()) {
            log.fine("NeoLoad afterCommand");

            log.fine("afterCommand[URI]: " + request.getRequestURI());

            boolean isBackgroundExecutor = false;
            try {
                RequestCommand cmd = RequestCommand.create(request);

                isBackgroundExecutor = cmd.getContentText().contains(JavascriptBackgroundExecutorKey);
                if(!isBackgroundExecutor) {

                    CommandResponse resp = CommandResponse.create(response);

                    if (RequestCommand.CommandEnum.element.equals(cmd.getCommand()) ||
                            RequestCommand.CommandEnum.elements.equals(cmd.getCommand())) {
                        cmd.setResponse(resp);
                        session.put(CurrentlySelectedElementsKey, cmd);
                    }
                    if (RequestCommand.CommandEnum.execute.equals(cmd.getCommand())) {
                        log.fine("script[result]: " + resp.getContentText());
                    }

                    if(mode.isEUE()) {
                        if (RequestCommand.CommandEnum.url.equals(cmd.getCommand())) {
                            beginAutoEndTransactionTimer(session);
                        } else {
                            cancelAutoEndTransactionTimer(session);
                        }
                    }

                    log.fine("afterCommand[content]: " + resp.getContentText());
                }
            } catch (Exception ex) {
                log.throwing(log.getClass().getCanonicalName(), "afterCommand", ex);
            }

            if(!isBackgroundExecutor) {

                if (getCurrentTransactionName(session) != null && isCurrentTransactionAuto(session))
                    stopTransaction(session);

            }
        }
    }

    private void cancelAutoEndTransactionTimer(TestSession session) {
        /*Object o = session.get(AutoEndTransactionTimerKey);
        if(o != null)
            ((Timer)o).cancel();
        session.put(AutoEndTransactionTimerKey, null);*/
    }
    private static String AutoEndTransactionTimerKey = "AutoEndTransactionTimer";
    private void beginAutoEndTransactionTimer(TestSession session) {
        /*TimerTask task = new TimerTask() {
            public void run() {
                checkFinalizeTransaction(session);
            }
        };
        Timer timer = new Timer(session.getInternalKey());
        long delay = 2000L;
        timer.schedule(task, delay);
        session.put(AutoEndTransactionTimerKey, timer);*/
    }

    private static String SessionIsFinalizingKey = "SessionIsFinalizing";
    private boolean sessionIsFinalizing(TestSession session) {
        Object o = session.get(SessionIsFinalizingKey);
        return (o != null ? (boolean)o : false);
    }

    @Override
    public void beforeRelease(TestSession session) {
        super.beforeRelease(session);
    }

    @Override
    public void afterSession(TestSession session) {
        session.put(SessionIsFinalizingKey,true);

        ModeHelper mode = getMode(session);
        if(mode.isOnMode()) {
            log.fine("NeoLoad afterSession");

            if (mode.isEUE())
                checkFinalizeTransaction(session);

            if (mode.isDesign())
                finalizeDesignSession(session);

        }

        super.afterSession(session);
    }

    private void finalizeDesignSession(TestSession session) {
        if(isCLIWorking(session)) {
            ModeHelper mode = getMode(session);
            if (mode.isDesign()) {
                try {
                    DesignAPIClient cli = mode.createDesign();
                    if (cli == null)
                        return;
                    if(cli.getStatus().equals(Status.BUSY))
                        cli.stopRecording(StopRecordingParams.newBuilder()
                                .frameworkParameterSearch(true)
                                .genericParameterSearch(false)
                                .updateParams(UpdateUserPathParams.newBuilder()
                                        .updateSharedContainers(true)
                                        .includeVariables(true)
                                        //.deleteRecording(false)
                                        .name(getChosenScriptName(session))
                                        .build())
                                .build());
                    log.fine("Design sent stop");
                } catch (Exception ex) {
                    log.severe("Could not stop session on design API: " + ex.toString());
                }
            }
        }
    }

    private boolean isCLIWorking(TestSession session) {
        return (boolean)session.get(CLIWorkingKey);
    }

    private void processUrl(TestSession session, RequestCommand cmd) {
        String url = cmd.getValueOf("$.url");
        if(getCurrentTransactionName(session) == null)
        {
            try {
                java.net.URL uri = new java.net.URL(url);
                String transactionName = String.format("%s %s",
                        "Open", uri.getPath());
                startTransaction(session, transactionName, true);
            } catch(Exception ex) {
                log.warning("Could not derive a transaction name from command: " + cmd.toString());
            }
        }
        log.fine("processUrl[url]: " + url);
    }

    private void processClick(TestSession session, RequestCommand cmd) {
        log.fine(cmd.toString());
        RequestCommand prior = (RequestCommand)session.get(CurrentlySelectedElementsKey);
        if(prior != null) {
            String currentId = cmd.getValueOf("$.id");
            Object values = prior.getResponse().getValuesOf("$.value.*");
            JSONArray arr = (values instanceof JSONArray ? (JSONArray)values : null);
            if(arr != null && matchesAnyElementId(arr,currentId))
                if(!(getCurrentTransactionName(session) != null && !isCurrentTransactionAuto(session))) {
                    String unique = getElementText(session, currentId);
                    if(!(unique != null && unique.trim().length() > 0))
                        unique = getElementDOMID(session, currentId);
                    if(!(unique != null && unique.trim().length() > 0))
                        unique = getElementDOMName(session, currentId);

                    if(!(unique != null && unique.trim().length() > 0)) {
                        String tagName = getElementTagName(session, currentId);
                        unique = tagName + " " + prior.getValueOf("$.value");
                    }

                    String transactionName = String.format("Click %s", unique);
                    startTransaction(session, transactionName, true);
                }
            }
        }

    private boolean matchesAnyElementId(JSONArray arr, String currentId) {
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

    private String getElementText(TestSession session, String elementId) {
        return getElementData(session, elementId, "text");
    }
    private String getElementTagName(TestSession session, String elementId) {
        return getElementData(session, elementId, "name");
    }
    private String getElementDOMID(TestSession session, String elementId) {
        return getElementData(session, elementId, "attribute/id");
    }
    private String getElementDOMName(TestSession session, String elementId) {
        return getElementData(session, elementId, "attribute/name");
    }
    private String getElementData(TestSession session, String elementId, String path) {
        String sessionId = session.getExternalKey().getKey();
        try {
            Hub hub = this.getRegistry().getHub();
            String url = String.format(
                    "%s/session/%s/element/%s/%s",
                    hub.getWebDriverHubRequestURL(), sessionId, elementId, path);
            HttpRequest req = new HttpRequest(HttpMethod.GET, url);
            HttpResponse res = getClient(session, new URL(url)).execute(req);
            PayloadContainer pc = new PayloadContainer(res.getContentString());
            return pc.getValueOf("$.value");
        } catch(Exception ex) {
            ex = ex;
        }
        return null;
    }

    private PayloadContainer executeJavascript(TestSession session, String script) {
        String sessionId = session.getExternalKey().getKey();
        try {
            Hub hub = this.getRegistry().getHub();
            String url = String.format(
                    "%s/session/%s/execute/sync",
                    hub.getWebDriverHubRequestURL(), sessionId);
            HttpRequest req = new HttpRequest(HttpMethod.POST, url);
            Map<String, Object> json = new HashMap<>();
            json.put("script", script);
            json.put("args", JavascriptBackgroundExecutorKey.split(","));
            byte[] bytes = JsonPath.parse(json).jsonString().getBytes("UTF-8");
            req.setContent(bytes);
            HttpClient cli = getClient(session, new URL(url));
            boolean busy = session.getSlot().getProxy().isBusy();
            HttpResponse res = cli.execute(req);
            return new PayloadContainer(res.getContentString());
        } catch(Exception ex) {
            log.severe(ex.toString());
        }
        return null;
    }

    private HttpClient getClient(TestSession session, URL url) {
        TestSlot slot = session.getSlot();
        Integer browserTimeout = slot.getProxy().getConfig().browserTimeout;
        return slot.getProxy().getHttpClient(url, browserTimeout, browserTimeout);
    }

    private void processCookie(TestSession session, RequestCommand cmd) {
        try {
            String name = cmd.getValueOf("$.cookie.name");
            if (TransactionCookieName.equalsIgnoreCase(name)) {
                String value = cmd.getValueOf("$.cookie.value", null);
                if (value != null)
                    startTransaction(session, value, false);
                else
                    stopTransaction(session);
            }
        } catch (Exception ex) {
            log.throwing(NeoLoadRemoteProxy.class.getCanonicalName(), "processCookie", ex);
        }
    }

    private void startTransaction(TestSession session, String name, boolean auto) {
        checkFinalizeTransaction(session);

        session.put(TransactionCookieName, name);
        session.put(TransactionIsAuto, auto);

        ModeHelper mode = getMode(session);
        if(isCLIWorking(session)) {
            if (mode.isDesign()) {
                try {
                    DesignAPIClient cli = mode.createDesign();
                    if (cli == null)
                        return;
                    cli.setContainer(new SetContainerParams(name));
                    log.fine("Design sent entry with value" + name);
                } catch (Exception ex) {
                    log.severe("Could not send data to design API: " + ex.toString());
                }
            }
            if(mode.isEUE()) {
                if(mode.isChrome()) {
                    PayloadContainer pcProfiler = executeJavascript(session, getChromeProfilerEnableJavascript());
                    log.fine("Chrome profiler enabled: " + pcProfiler.getContentText());
                }
            }
        }

        if(mode.isEUE()) {
            session.put(TransactionStartTimeKey, System.currentTimeMillis());
        }

    }
    private static String TransactionStartTimeKey = "TransactionStartTime";

    private void stopTransaction(TestSession session) {
        String transactionName = (String)session.get(TransactionCookieName);
        if(transactionName != null) {
            ModeHelper mode = getMode(session);
            if(isCLIWorking(session)) {

                List<String> basePath = Arrays.asList("Selenium",
                        getChosenScriptName(session),
                        transactionName);

                if (mode.isEUE()) {
                    long startTime = (long) session.get(TransactionStartTimeKey);

                    long endTime = System.currentTimeMillis();
                    long diff = endTime - startTime;
                    final EntryBuilder eb = new EntryBuilder(
                            merge(basePath,Arrays.asList("Timer")), System.currentTimeMillis());

                    eb.unit("ms");
                    eb.value((double) diff);

                    try {
                        DataExchangeAPIClient cli = mode.createEUE(session, () -> {
                            ContextBuilder cb = new ContextBuilder();
                            Map<String,Object> caps = session.getSlot().getCapabilities();
                            cb.os((String)caps.get("platformName"))
                                    .software((String)caps.get("browserName"))
                                    .location(mode.getLocation())
                                    .script(getChosenScriptName(session))
                                    .instanceId("Script-" + System.currentTimeMillis());
                            return cb;
                        });
                        if (cli == null)
                            return;
                        ArrayList<Entry> entries = new ArrayList<>();
                        entries.add(eb.build());
                        if(!sessionIsFinalizing(session)) {
                            try {
                                entries.addAll(includeW3C(session, basePath));
                            } catch (Exception ex) {
                                log.severe("Could not send W3C data to exchange API: " + ex.toString());
                            }
                        }
                        cli.addEntries(entries);
                        log.fine("DataExchangeAPI sent entry with value " + diff);

                    } catch (Exception ex) {
                        log.severe("Could not send data to exchange API: " + ex.toString());
                        markDataExchangeClientFailed(session, ex);
                    }
                }
            }

            /*if(mode.isEUE()) {
                if(mode.isChrome()) {
                    try {
                        PayloadContainer pcProfiler = executeJavascript(session, getChromeProfilerFinalizeJavascript());
                        log.fine("Chrome profiler finalized: " + pcProfiler.getContentText());
                        if(pcProfiler.getValueOf("$.value") != null) {
                          pcProfiler = pcProfiler;
                        }
                    } catch(Exception ex) {

                    }
                }
            }*/

            session.put(TransactionCookieName, null);
        }
    }

    private boolean markDataExchangeClientFailed(TestSession session, Exception ex) {
        session.put(CLIWorkingKey, false);
        session.put(SessionCreationExceptionKey, ex);
        return checkRaiseLastException(session);
    }

    public static<T> List<T> merge(List<T> list1, List<T> list2)
    {
        return Stream.concat(list1.stream(), list2.stream())
                .collect(Collectors.toList());
    }
    private static String PriorW3CMeasurementsKey = "PriorW3CMeasurements";
    private List<Entry> includeW3C(TestSession session, List<String> basePath) {
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

        Map<String,Object> current = captureW3C(session);
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

    private Map<String,Object> filterEventsByTypes(Map<String,Object> source, Function<String,Boolean> fMatch) {
        HashMap<String,Object> hm = new HashMap<>() {
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

    private boolean w3cMeasurementsMatch(HashMap.Entry<String,Object> c, HashMap.Entry<String,Object> p) {
        try {
            boolean namesMatch = p.getKey().equalsIgnoreCase(c.getKey());
            boolean valuesMatch = new BigDecimal(p.getValue().toString()).compareTo(new BigDecimal(c.getValue().toString())) == 0;
            return namesMatch && valuesMatch;
        } catch (Exception ex) {
            log.severe(ex.toString());
        }
        return false;
    }

    private String getLastListItem(List<String> path) {
        return (path.size() > 0 ? path.get(path.size()-1) : null);
    }

    private static List<String> getPageW3CCaptureEventTypes() {
        return Arrays.asList("net|browser|mark|measure|paint|longtask".split("\\|"));
    }
    private static String splitChar = Character.toString((char)30);
    private HashMap<String,Object> captureW3C(TestSession session) {
        HashMap<String,Object> all = new HashMap<>();

        PayloadContainer pcTimers = executeJavascript(session, getW3CTimersScript());
        Object oTimers = JsonPath.read(pcTimers.json, "$.value");
        all.putAll((HashMap<String,Object>)oTimers);

        ModeHelper mode = getMode(session);
        List<String> pageEventTypes = getPageW3CCaptureEventTypes();
        List<String> desiredEventTypes = mode.getDesiredW3CEventTypes();
        List<String> requiredEventTypes = new ArrayList<String>(new HashSet<String>(
                Stream.concat(
                        pageEventTypes.stream().map(s -> s.trim().toLowerCase()),
                        desiredEventTypes.stream()).map(s -> s.trim().toLowerCase())
                .collect(Collectors.toList())));

        PayloadContainer pcTimeline = executeJavascript(session, getW3CTimelineScript());
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
    private List<Entry> getEntriesFromMeasures(Map<String, Object> all, List<String> basePath) {
        List<Entry> ret = new ArrayList<Entry>();
        final StatusBuilder sb = new StatusBuilder();
        sb.state(com.neotys.rest.dataexchange.model.Status.State.PASS);
        com.neotys.rest.dataexchange.model.Status status = sb.build();
        for(Map.Entry me : all.entrySet()) {
            EntryBuilder eb = new EntryBuilder(
                    merge(basePath,merge(
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
    private String getW3CTimersScript() {
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
    private String getW3CTimelineScript() {
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

    private String getCurrentTransactionName(TestSession session) {
        return (String)session.get(TransactionCookieName);
    }
    private boolean isCurrentTransactionAuto(TestSession session) {
        return (boolean)session.get(TransactionIsAuto);
    }

    private void checkFinalizeTransaction(TestSession session) {
        String priorTransaction = getCurrentTransactionName(session);
        if(priorTransaction != null)
            stopTransaction(session);
    }
}
