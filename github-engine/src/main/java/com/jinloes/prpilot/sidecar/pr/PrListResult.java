package com.jinloes.prpilot.sidecar.pr;

import java.util.List;

/** Token-free outcome returned by the {@code prs/list} capability. */
public record PrListResult(
        String status,
        String message,
        String query,
        int resultLimit,
        boolean limited,
        List<PullRequestSummary> prs) {
    static PrListResult success(String query, boolean limited, List<PullRequestSummary> prs) {
        return new PrListResult(
                "ok", "Pull requests loaded.", query, 50, limited, List.copyOf(prs));
    }

    static PrListResult failure(String status, String message) {
        return new PrListResult(status, message, null, 50, false, List.of());
    }
}
