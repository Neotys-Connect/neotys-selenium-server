package com.neotys.selenium.server;

import com.jayway.jsonpath.Configuration;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class RequestCommand extends PayloadContainer {

    private static final Logger log = Logger.getLogger(RequestCommand.class.getName());

    public enum CommandEnum {
            create_session,
            url,
            click,
            title,
            element,
            elements,
            clear,
            value,
            cookie,
            execute
    }

    private CommandEnum command;
    private String sessionGuid;
    private String contentText;
    private CommandResponse response;

    public CommandEnum getCommand() {
        return command;
    }
    public void setCommand(CommandEnum command) {
        this.command = command;
    }
    private RequestCommand() { }

    public void setResponse(CommandResponse resp) {
        this.response = resp;
    }
    public CommandResponse getResponse() {
        return response;
    }

    public static RequestCommand create(HttpServletRequest request) throws Exception {

        RequestCommand ret = new RequestCommand();
        try {
            String uri = request.getRequestURI();
            ret.contentText = request.getReader().lines().collect(Collectors.joining());
            String[] uriParts = uri.split("/");
            List<String> partsList = Arrays.asList(uriParts);
            int sessionIndex = partsList.indexOf("session");
            if(sessionIndex == partsList.size()-1)
                ret.command = CommandEnum.create_session;
            else {
                ret.sessionGuid = uriParts[sessionIndex+1];
                if(sessionIndex+2 < uriParts.length) {
                    String[] rest = Arrays.copyOfRange(uriParts, sessionIndex + 2, uriParts.length);
                    log.fine(String.join("\n", rest));
                    String command = rest[0];
                    ret.command = Enum.valueOf(CommandEnum.class,command);
                    if(CommandEnum.cookie.equals(ret.command) && rest.length > 1)
                        ret.contentText = String.format("{cookie:{name:'%s'}}",rest[1]);
                    if(CommandEnum.element.equals(ret.command) || CommandEnum.elements.equals(ret.command)) {
                        log.fine("Selector: " + ret.contentText);
                    }
                    if("click".equalsIgnoreCase(rest[rest.length-1])) {
                        ret.command = CommandEnum.click;
                        ret.contentText = String.format("{id:'%s'}",rest[rest.length-2]);
                    }
                    if(CommandEnum.execute.equals(ret.command)) {
                        log.fine("script: " + ret.contentText);
                    }
                }
            }
            if(ret.contentText != null && ret.contentText.length() > 0) {
                ret.json = Configuration.defaultConfiguration().jsonProvider().parse(ret.contentText);
            }
        } catch(java.io.IOException ex) {
            throw new Exception("Could not parse Selenium request.", ex);
        }
        return ret;
    }

    @Override
    public String toString() {
        return String.format(
                "Command: %s\nSession: %s",
                this.command, this.sessionGuid
        );
    }
}
