package com.neotys.selenium.server;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.internal.JsonContext;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class PayloadContainer {

    protected Object json;

    public PayloadContainer() { }
    public PayloadContainer(String json) {
        this.json = Configuration.defaultConfiguration().jsonProvider().parse(json);
    }

    public String getValueOf(String jsonPath) { return getValueOf(jsonPath, Optional.empty()); }
    public String getValueOf(String jsonPath, Optional<String> defaultValue) {
        if(this.json == null) return null;
        try {
            return JsonPath.read(this.json, jsonPath);
        } catch(Exception ex) {
            if(defaultValue == null)
                return null;
            else if(defaultValue.isPresent())
                return defaultValue.get();
            else
                throw ex;
        }
    }
    public List<String> getValuesOf(String jsonPath) { return getValuesOf(jsonPath, Optional.empty()); }
    public List<String> getValuesOf(String jsonPath, Optional<String> defaultValue) {
        if(this.json == null) return null;
        try {
            return JsonPath.read(this.json, jsonPath);
        } catch(Exception ex) {
            if(defaultValue == null)
                return null;
            else if(defaultValue.isPresent())
                return Arrays.asList(defaultValue.get());
            else
                throw ex;
        }
    }

    public String getContentText() {
        return JsonPath.parse(this.json).jsonString();
    }
}
