package com.jinloes.prpilot.sidecar.pr;

import java.util.Set;

public final class PrSearchQueryService {
    private static final Set<String> SEARCH_SCOPES =
            Set.of("currentRepo", "reviewRequested", "assigned", "authored");

    public QueryResult build(QueryParams params) {
        String state = normalizeState(params.state());
        String scope = normalizeScope(params.searchScope());
        String currentRepo = params.currentRepo() == null ? "" : params.currentRepo().trim();

        StringBuilder query = new StringBuilder("is:pr");
        if ("closed".equals(state)) {
            query.append(" is:closed");
        } else if (!"all".equals(state)) {
            query.append(" is:open");
        }

        switch (scope) {
            case "reviewRequested" -> query.append(" review-requested:@me");
            case "assigned" -> query.append(" assignee:@me");
            case "authored" -> query.append(" author:@me");
            default -> {
                if (currentRepo.isEmpty()) {
                    query.append(" author:@me");
                } else {
                    query.append(" repo:").append(currentRepo);
                }
            }
        }
        return new QueryResult(query.toString());
    }

    private String normalizeState(String state) {
        return "closed".equals(state) || "all".equals(state) ? state : "open";
    }

    private String normalizeScope(String scope) {
        return scope != null && SEARCH_SCOPES.contains(scope) ? scope : "currentRepo";
    }

    public record QueryParams(String state, String searchScope, String currentRepo) {}

    public record QueryResult(String query) {}
}
