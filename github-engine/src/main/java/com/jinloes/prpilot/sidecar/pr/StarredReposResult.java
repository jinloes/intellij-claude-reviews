package com.jinloes.prpilot.sidecar.pr;

import java.util.List;

/** Token-free outcome for the authenticated user's starred repositories. */
public record StarredReposResult(
        String status,
        String message,
        int resultLimit,
        boolean limited,
        List<String> repositories) {
    static StarredReposResult success(boolean limited, List<String> repositories) {
        return new StarredReposResult(
                "ok", "Starred repositories loaded.", 200, limited, List.copyOf(repositories));
    }

    static StarredReposResult failure(String status, String message) {
        return new StarredReposResult(status, message, 200, false, List.of());
    }
}
