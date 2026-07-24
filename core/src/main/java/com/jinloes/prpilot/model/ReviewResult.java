package com.jinloes.prpilot.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the AI-generated review: a markdown summary, a verdict, and a list of inline line comments.
 * Mutable setters are preserved for Jackson deserialization and for Java interop from
 * intellij-plugin.
 */
public final class ReviewResult {

    private String summary;
    private String verdict;
    private List<LineComment> lineComments;

    public ReviewResult() {}

    public ReviewResult(String summary, String verdict, List<LineComment> lineComments) {
        this.summary = summary;
        this.verdict = verdict;
        this.lineComments =
                lineComments != null ? new ArrayList<>(lineComments) : new ArrayList<>();
    }

    public String getSummary() {
        return summary != null ? summary : "";
    }

    public void setSummary(String value) {
        this.summary = value;
    }

    public String getVerdict() {
        return verdict != null ? verdict : "COMMENT";
    }

    public void setVerdict(String value) {
        this.verdict = value;
    }

    public List<LineComment> getLineComments() {
        if (lineComments == null) {
            lineComments = new ArrayList<>();
        }
        return lineComments;
    }

    public void setLineComments(List<LineComment> value) {
        this.lineComments = value != null ? new ArrayList<>(value) : new ArrayList<>();
    }
}
