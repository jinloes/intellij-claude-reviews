package com.jinloes.prpilot.model;

/**
 * A file-anchored finding CI already reported on the PR (a check-run annotation).
 *
 * <p>Plain JavaBean to match the other {@code core} models — Jackson bean introspection is the only
 * runtime serializer used against these.
 */
public final class CiAnnotation {

    private String file;
    private int line;
    private String level;
    private String message;

    public CiAnnotation() {}

    public CiAnnotation(String file, int line, String level, String message) {
        this.file = file;
        this.line = line;
        this.level = level;
        this.message = message;
    }

    public String getFile() {
        return file != null ? file : "";
    }

    public void setFile(String value) {
        this.file = value;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int value) {
        this.line = value;
    }

    /** {@code failure}, {@code warning}, or {@code notice}; empty when CI omitted it. */
    public String getLevel() {
        return level != null ? level.toLowerCase(java.util.Locale.ROOT) : "";
    }

    public void setLevel(String value) {
        this.level = value;
    }

    public String getMessage() {
        return message != null ? message : "";
    }

    public void setMessage(String value) {
        this.message = value;
    }
}
