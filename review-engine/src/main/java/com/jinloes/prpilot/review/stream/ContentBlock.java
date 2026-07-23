package com.jinloes.prpilot.review.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** A single content block inside a Claude stream-json `assistant` message. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ContentBlock {
    private String type;
    private String name;
    private Map<String, Object> input;
    private String text;
    private String thinking;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }
}
