package com.jinloes.prpilot.sidecar.repo;

import java.util.List;

/**
 * Token-free description of a repository's language and build tooling.
 *
 * <p>Lets a review be judged against the idioms of the stack it is actually written in rather than
 * generic advice. Derived entirely from marker files in the working tree, so it costs no request
 * and never fails hard — an unrecognized repository simply yields an empty profile.
 */
public record RepoProfileResult(
        String status,
        String message,
        List<String> languages,
        List<String> buildTools,
        String summary) {

    static RepoProfileResult success(
            List<String> languages, List<String> buildTools, String summary) {
        return new RepoProfileResult(
                "ok", "Repository profile detected.", languages, buildTools, summary);
    }

    /** Nothing recognizable in the tree; a normal outcome, not an error. */
    static RepoProfileResult none(String message) {
        return new RepoProfileResult("ok", message, List.of(), List.of(), "");
    }
}
