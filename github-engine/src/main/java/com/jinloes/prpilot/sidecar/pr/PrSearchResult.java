package com.jinloes.prpilot.sidecar.pr;

import java.util.List;

/** Token-free outcome for a bounded raw GitHub pull-request search. */
public record PrSearchResult(
        String status,
        String message,
        int resultLimit,
        boolean limited,
        List<PullRequestSummary> prs) {
    static PrSearchResult success(int limit, boolean limited, List<PullRequestSummary> prs) {
        return new PrSearchResult("ok", "Pull requests loaded.", limit, limited, List.copyOf(prs));
    }

    static PrSearchResult failure(String status, String message, int limit) {
        return new PrSearchResult(status, message, limit, false, List.of());
    }
}
