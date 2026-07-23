package com.jinloes.prpilot.review.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One line of `claude --output-format stream-json` NDJSON output. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StreamEvent {
    private String type;
    private String subtype;

    @JsonProperty("is_error")
    private boolean isError;

    private EventMessage message;
    private String result;

    @JsonProperty("session_id")
    private String sessionId;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSubtype() {
        return subtype;
    }

    public void setSubtype(String subtype) {
        this.subtype = subtype;
    }

    public boolean isError() {
        return isError;
    }

    public void setError(boolean error) {
        isError = error;
    }

    public EventMessage getMessage() {
        return message;
    }

    public void setMessage(EventMessage message) {
        this.message = message;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
