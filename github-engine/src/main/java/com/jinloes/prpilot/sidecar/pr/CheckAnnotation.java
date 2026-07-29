package com.jinloes.prpilot.sidecar.pr;

/**
 * A file-anchored CI annotation.
 *
 * <p>This is the highest-value CI signal available: GitHub already reports it in the same shape as
 * a review comment ({@code path} + line + message), so it is ground truth that can be compared
 * directly against model-generated findings.
 *
 * @param path repository-relative file path
 * @param startLine first line the annotation covers, 0 when GitHub omitted it
 * @param endLine last line the annotation covers, 0 when GitHub omitted it
 * @param level {@code notice}, {@code warning}, or {@code failure}
 * @param message bounded annotation text
 */
public record CheckAnnotation(
        String path, int startLine, int endLine, String level, String message) {

    /** {@code path:line} location, or just the path when no usable line was reported. */
    public String location() {
        return startLine > 0 ? path + ":" + startLine : path;
    }
}
