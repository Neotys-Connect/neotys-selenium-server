package com.neotys.selenium.server;

import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.common.exception.RemoteException;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.SessionTerminationReason;
import org.openqa.grid.internal.TestSession;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
https://stackoverflow.com/questions/43395659/properties-for-selenium-grid-hub-node-config
 */
public class NeoLoadRemoteProxy extends org.openqa.grid.selenium.proxy.DefaultRemoteProxy {

    private static final Logger log = Logger.getLogger(NeoLoadRemoteProxy.class.getName());

    private GridRegistry registry;

    public NeoLoadRemoteProxy(RegistrationRequest request, GridRegistry registry) {
        super(request, registry);
        this.registry = registry;

        if(request.getConfiguration().debug)
            log.setLevel(Level.ALL);
        else
            log.setLevel(Level.WARNING);

        log.info("NeoLoadRemoteProxy created");
        log.fine("NeoLoadRemoteProxy constructed");
    }

    @Override
    public TestSession getNewSession(Map<String, Object> caps) {
        return super.getNewSession(
                CapabilitiesHelper.augmentRequestedCapabilitiesWithMode(caps, log.getLevel())
        );
    }

    private ModeHelper getMode(TestSession session) { return (ModeHelper)session.get("ModeHelper"); }

    @Override
    public void beforeSession(TestSession session) {
        super.beforeSession(session);
        boolean doSuper = true;

        log.fine("beforeSession[log]: " + log.getLevel().getName());

        NeoLoadSession ses = NeoLoadSession.initializeSession(session, log.getLevel());
        doSuper = !ses.hasInitializationFailure();

        if(!doSuper) // proxy class should handle lifecycle operations (like killing a session)
            ses.checkRaiseLastException((ex) -> {
                addNewEvent(new RemoteException(ex.toString()));
                boolean killSession = true;
                if(ex.toString().toUpperCase().contains("NL-API-ILLEGAL-SESSION")) killSession = true;
                if(ses.getMode().isDebug()) killSession = false;
                if(killSession) {
                    //session.getSlot().getProxy().teardown();
                    //registry.removeIfPresent(this);
                    //session.getSlot().startReleaseProcess();
                    //session.sendDeleteSessionRequest();
                    registry.terminate(session, SessionTerminationReason.CREATIONFAILED);
                }
            });

    }

    @Override
    public void beforeCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        log.fine("beforeCommand");

        NeoLoadSession.get(session).peekBeforeCommand(request, response);

        super.beforeCommand(session, request, response);
    }

    @Override
    public void afterCommand(TestSession session, HttpServletRequest request, HttpServletResponse response) {
        log.fine("afterCommand");

        super.afterCommand(session, request, response);

        NeoLoadSession.get(session).peekAfterCommand(request, response);
    }

    @Override
    public void beforeRelease(TestSession session) {
        log.fine("beforeRelease");

        super.beforeRelease(session);
    }

    @Override
    public void afterSession(TestSession session) {
        log.fine("afterSession");

        NeoLoadSession.get(session).finalizeSession();

        super.afterSession(session);
    }
}
