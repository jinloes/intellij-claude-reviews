package com.jinloes.prpilot.sidecar.review;

public record ReviewLineComment(
        String file,
        int line,
        String type,
        String severity,
        String category,
        String confidence,
        String rationale,
        String body) {}
