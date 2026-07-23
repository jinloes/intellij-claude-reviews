package com.jinloes.prpilot.review.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** The `message` payload of an `assistant` stream-json event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EventMessage {
    private List<ContentBlock> content;

    public List<ContentBlock> getContent() {
        return content;
    }

    public void setContent(List<ContentBlock> content) {
        this.content = content;
    }
}
