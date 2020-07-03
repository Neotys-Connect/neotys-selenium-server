package com.neotys.selenium.server;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import org.openqa.grid.web.servlet.handler.SeleniumBasedResponse;

import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

public class CommandResponse extends PayloadContainer {

    private String contentText = null;

    private Field forwardedContentField = null;

    private CommandResponse() {
        try {
            this.forwardedContentField = SeleniumBasedResponse.class.getDeclaredField("forwardedContent");
            this.forwardedContentField.setAccessible(true);
        } catch(NoSuchFieldException ex) {
        }
    }

    public String getContentText() {
        return contentText;
    }

    public static CommandResponse create(HttpServletResponse response) throws Exception {
        CommandResponse ret = new CommandResponse();
        SeleniumBasedResponse resp = (SeleniumBasedResponse)response;
        try {
            byte[] bytes = (byte[])ret.forwardedContentField.get(response);
            ret.contentText = new String(bytes, StandardCharsets.UTF_8);
            ret.json = Configuration.defaultConfiguration().jsonProvider().parse(ret.contentText);
        } catch(IllegalAccessException ex) {
            throw new Exception("Could not parse Selenium response.", ex);
        }
        return ret;
    }

    public String getValue() {
        if(this.json == null) return null;
        return JsonPath.read(this.json, "$.value");
    }

    public String getUsing() {
        if(this.json == null) return null;
        return JsonPath.read(this.json, "$.using");
    }

}
