package com.jinloes.prpilot.sidecar.pr;

/** Token-free pull-request fields required by the PR list UI. */
public record PullRequestSummary(
        int number,
        String title,
        String owner,
        String repo,
        String author,
        String createdAt,
        String htmlUrl,
        boolean isDraft) {}
