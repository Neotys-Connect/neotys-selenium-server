package com.neotys.selenium.server;

import com.neotys.rest.dataexchange.client.DataExchangeAPIClient;
import com.neotys.rest.dataexchange.model.ContextBuilder;
import com.neotys.rest.dataexchange.model.Entry;
import com.neotys.rest.dataexchange.model.EntryBuilder;
import com.neotys.rest.design.client.DesignAPIClient;
import com.neotys.rest.design.model.SetContainerParams;
import com.neotys.rest.design.model.StartRecordingParams;
import com.neotys.rest.design.model.StopRecordingParams;
import com.neotys.rest.design.model.UpdateUserPathParams;
import com.neotys.rest.runtime.model.Status;
import org.openqa.grid.internal.TestSession;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NeoLoadSession {

    private static final Logger log = Logger.getLogger(NeoLoadSession.class.getName());

    private boolean _cliIsWorking = false;
    public static String ScriptNameKey = "ScriptName";
    private String _chosenScriptName = null;
    private static String DefaultScriptName = "UserPath";
    public static String TransactionCookieName = "nl_transaction";
    private boolean _transactionIsAuto = false;
    private RequestCommand _currentlySelectedElements = null;
    private Exception _sessionCreationException = null;
    //private static String AutoEndTransactionTimerKey = "AutoEndTransactionTimer";
    //private static String TransactionStartTimeKey = "TransactionStartTime";
    private long _transactionStartTime = 0;

    private TestSession _session = null;

    private NeoLoadSession(TestSession session) {
        this._session = session;
        session.put("NeoLoadSession", this);
    }
    public static NeoLoadSession get(TestSession session) {
        return (NeoLoadSession)session.get("NeoLoadSession");
    }

    public TestSession getSession() { return this._session; }

    public ModeHelper getMode() {
        return (ModeHelper)getSession().get("ModeHelper");
    }
    private NeoLoadSession setMode(ModeHelper mode) {
        getSession().put("ModeHelper", mode);
        return this;
    }

    public boolean hasInitializationFailure() {
        return this._sessionCreationException != null;
    }

    public static NeoLoadSession initializeSession(TestSession session) {
        NeoLoadSession ret = new NeoLoadSession(session);
        ModeHelper mode = ret.setMode(
                ModeHelper.fromCapabilities(session.getRequestedCapabilities())
        ).getMode();

        if(mode.isOnMode()) {
            log.fine("NeoLoad beforeSession: " + mode.getMode());
            log.fine(String.format("Configured to use %s:%s", mode.getHost(), mode.getPort()));
            ret._transactionIsAuto = false;
            ret._cliIsWorking = false;

            if(mode.isEUE()) {
                if(mode.createEUE(session, null) != null)
                    ret._cliIsWorking = true;
                else {
                    ret.markDataExchangeClientFailed(mode.getLastException());
                    log.severe("Cannot connect to NeoLoad Data Exchange API. Killing session.");
                }
            }
            if(mode.isDesign()) {
                if(mode.createDesign() != null) {
                    ret._cliIsWorking = true;
                } else {
                    ret._sessionCreationException = mode.getLastException();
                    log.severe("Cannot connect to NeoLoad Design API. Killing session.");
                }
            }
        }
        return ret;
    }

    public boolean markDataExchangeClientFailed(Exception ex) {
        this._cliIsWorking = false;
        this._sessionCreationException = ex;
        return checkRaiseLastException(null);
    }

    public boolean checkRaiseLastException(Consumer<Exception> fWhenException) {
        TestSession session = getSession();
        ModeHelper mode = ModeHelper.fromCapabilities(session.getRequestedCapabilities());
        if(this._sessionCreationException != null) {
            if(fWhenException != null)
                fWhenException.accept(this._sessionCreationException);
            return true;
        }
        return false;
    }

    private boolean isCLIWorking() {
        return this._cliIsWorking;
    }
    private String getChosenScriptName() {
        return this._chosenScriptName;
    }
    private String initScriptName(RequestCommand cmd) {
        TestSession session = getSession();
        String scriptName = (String) getSession().getRequestedCapabilities().get(ModeHelper.ScriptNameKey);
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

        this._chosenScriptName = scriptName;
        return scriptName;
    }

    public void peekBeforeCommand(HttpServletRequest request, HttpServletResponse response) {
        ModeHelper mode = getMode();
        if(mode.isOnMode()) {

            log.fine("NeoLoad beforeCommand");

            log.fine("beforeCommand[URI]: " + request.getRequestURI());

            try {
                RequestCommand cmd = RequestCommand.create(request);

                if(!CommandProcessor.isBackgroundJavascriptExecutor(cmd)) {

                    if (isCLIWorking()) {
                        switch (cmd.getCommand()) {
                            case create_session:
                                break;
                            default:
                                String scriptName = getChosenScriptName();
                                if(scriptName == null)
                                    scriptName = initScriptName(cmd);

                                if (mode.isDesign() && !_hasDesignInitialized)
                                    initDesignSession(scriptName);
                        }
                    }

                    log.fine("beforeCommand[content]: " + cmd.toString());

                    CommandProcessor cp = CommandProcessor.get(this);

                    switch (cmd.getCommand()) {
                        case url:
                            cp.processUrl(cmd);
                            break;
                        case cookie:
                            cp.processCookie(cmd);
                            break;
                        case click:
                            cp.processClick(cmd);
                            break;
                    }
                }
            } catch (Exception ex) {
                log.throwing(log.getClass().getCanonicalName(), "beforeCommand", ex);
            }
        }
    }

    public void peekAfterCommand(HttpServletRequest request, HttpServletResponse response) {
        TestSession session = getSession();
        ModeHelper mode = getMode();
        if(mode.isOnMode()) {
            log.fine("NeoLoad afterCommand");

            log.fine("afterCommand[URI]: " + request.getRequestURI());

            boolean isBackgroundExecutor = false;
            try {
                RequestCommand cmd = RequestCommand.create(request);

                isBackgroundExecutor = CommandProcessor.isBackgroundJavascriptExecutor(cmd);
                if(!isBackgroundExecutor) {

                    CommandResponse resp = CommandResponse.create(response);

                    if (RequestCommand.CommandEnum.element.equals(cmd.getCommand()) ||
                            RequestCommand.CommandEnum.elements.equals(cmd.getCommand())) {
                        cmd.setResponse(resp);
                        this._currentlySelectedElements = cmd;
                    }
                    if (RequestCommand.CommandEnum.execute.equals(cmd.getCommand())) {
                        log.fine("script[result]: " + resp.getContentText());
                    }

                    if(mode.isEUE()) {
                        if (RequestCommand.CommandEnum.url.equals(cmd.getCommand())) {
                            beginAutoEndTransactionTimer();
                        } else {
                            cancelAutoEndTransactionTimer();
                        }
                    }

                    log.fine("afterCommand[content]: " + resp.getContentText());
                }
            } catch (Exception ex) {
                log.throwing(log.getClass().getCanonicalName(), "afterCommand", ex);
            }

            if(!isBackgroundExecutor) {

                if (getCurrentTransactionName() != null && isCurrentTransactionAuto())
                    stopTransaction();

            }
        }
    }

    public void finalizeSession() {
        TestSession session = getSession();
        _sessionIsFinalizing = true;

        ModeHelper mode = getMode();
        if(mode.isOnMode()) {
            log.fine("NeoLoad afterSession");

            if (mode.isEUE())
                checkFinalizeTransaction();

            if (mode.isDesign())
                finalizeDesignSession();

        }
    }

    public String getCurrentTransactionName() {
        return (String)getSession().get(TransactionCookieName);
    }
    public boolean isCurrentTransactionAuto() {
        return this._transactionIsAuto;
    }

    private void checkFinalizeTransaction() {
        TestSession session = getSession();
        String priorTransaction = getCurrentTransactionName();
        if(priorTransaction != null)
            stopTransaction();
    }

    public void startTransaction(String name, boolean auto) {
        TestSession session = getSession();
        checkFinalizeTransaction();

        session.put(TransactionCookieName, name);
        this._transactionIsAuto = auto;

        ModeHelper mode = getMode();
        if(isCLIWorking()) {
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
            /*if(mode.isEUE()) {
                if(mode.isChrome()) {
                    PayloadContainer pcProfiler = executeJavascript(session, getChromeProfilerEnableJavascript());
                    log.fine("Chrome profiler enabled: " + pcProfiler.getContentText());
                }
            }*/
        }

        if(mode.isEUE()) {
            this._transactionStartTime = System.currentTimeMillis();
        }

    }

    public void stopTransaction() {
        TestSession session = getSession();
        String transactionName = (String)session.get(TransactionCookieName);
        CommandProcessor cp = CommandProcessor.get(this);
        if(transactionName != null) {
            ModeHelper mode = getMode();
            if(isCLIWorking()) {

                List<String> basePath = Arrays.asList("Selenium",
                        getChosenScriptName(),
                        transactionName);

                if (mode.isEUE()) {
                    long startTime = this._transactionStartTime;

                    long endTime = System.currentTimeMillis();
                    long diff = endTime - startTime;
                    final EntryBuilder eb = new EntryBuilder(
                            Utils.merge(basePath,Arrays.asList("Timer")), System.currentTimeMillis());

                    eb.unit("ms");
                    eb.value((double) diff);

                    try {
                        DataExchangeAPIClient cli = mode.createEUE(session, () -> {
                            ContextBuilder cb = new ContextBuilder();
                            Map<String,Object> caps = session.getSlot().getCapabilities();
                            cb.os((String)caps.get("platformName"))
                                    .software((String)caps.get("browserName"))
                                    .location(mode.getLocation())
                                    .script(getChosenScriptName())
                                    .instanceId("Script-" + System.currentTimeMillis());
                            return cb;
                        });
                        if (cli == null)
                            return;
                        ArrayList<Entry> entries = new ArrayList<>();
                        entries.add(eb.build());
                        if(!_sessionIsFinalizing) {
                            try {
                                entries.addAll(cp.includeW3C(basePath));
                            } catch (Exception ex) {
                                log.severe("Could not send W3C data to exchange API: " + ex.toString());
                            }
                        }
                        cli.addEntries(entries);
                        log.fine("DataExchangeAPI sent entry with value " + diff);

                    } catch (Exception ex) {
                        log.severe("Could not send data to exchange API: " + ex.toString());
                        markDataExchangeClientFailed(ex);
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


    private void finalizeDesignSession() {
        if(isCLIWorking()) {
            ModeHelper mode = getMode();
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
                                        .name(getChosenScriptName())
                                        .build())
                                .build());
                    log.fine("Design sent stop");
                } catch (Exception ex) {
                    log.severe("Could not stop session on design API: " + ex.toString());
                }
            }
        }
    }

    private boolean _hasDesignInitialized = false;
    private static boolean _wasDesignInitSuccess = false;

    private boolean initDesignSession(String scriptName) {
        TestSession session = getSession();
        if(!_hasDesignInitialized) {

            _wasDesignInitSuccess = false;

            ModeHelper mode = getMode();
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
                        _wasDesignInitSuccess = true;
                        break;
                }
                log.fine("Design mode started");
            } catch (Exception ex) {
                log.severe("Could not send data to design API: " + ex.toString());
            }
            _hasDesignInitialized = true;
        }

        return _wasDesignInitSuccess;
    }

    private void cancelAutoEndTransactionTimer() {
        /*Object o = session.get(AutoEndTransactionTimerKey);
        if(o != null)
            ((Timer)o).cancel();
        session.put(AutoEndTransactionTimerKey, null);*/
    }
    private void beginAutoEndTransactionTimer() {
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
    private boolean _sessionIsFinalizing = false;

    public RequestCommand getLastSelectedElementsRequestCommand() {
        return this._currentlySelectedElements;
    }
}
