package com.jinloes.prpilot.sidecar.pr;

/** Token-free bounded review-diff result returned by {@code prs/getDiff}. */
public record PrDiffResult(
        String status, String message, String diff, boolean truncated, int limitBytes) {
    static PrDiffResult success(String diff, boolean truncated, int limitBytes) {
        return new PrDiffResult("ok", "Pull request diff loaded.", diff, truncated, limitBytes);
    }

    static PrDiffResult failure(String status, String message) {
        return new PrDiffResult(status, message, null, false, 250_000);
    }
}
