package com.jinloes.prpilot.sidecar.pr;

/** Structured, token-free outcome returned by {@code prs/getDetail}. */
public record PrDetailResult(String status, String message, PrDetail detail) {
    static PrDetailResult success(PrDetail detail) {
        return new PrDetailResult("ok", "Pull request details loaded.", detail);
    }

    static PrDetailResult failure(String status, String message) {
        return new PrDetailResult(status, message, null);
    }
}
