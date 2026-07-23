package com.jinloes.prpilot.sidecar.pr;

/** Token-free pull-request metadata required for selection and worktree resolution. */
public record PrDetail(
        boolean merged, String title, String body, Head head, String baseRepoFullName) {
    public record Head(String sha, String ref, String repoFullName, String cloneUrl) {}
}
