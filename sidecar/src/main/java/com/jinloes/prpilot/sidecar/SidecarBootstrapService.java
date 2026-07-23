package com.jinloes.prpilot.sidecar;

import java.util.Map;

final class SidecarBootstrapService {
    InitializeResult initialize() {
        return new InitializeResult(
                "pr-pilot-sidecar",
                "0.1.0",
                1,
                Map.ofEntries(
                        Map.entry("githubAuth", true),
                        Map.entry("prDetail", true),
                        Map.entry("prDiff", true),
                        Map.entry("prList", true),
                        Map.entry("repoDetect", true),
                        Map.entry("draftReview", true),
                        Map.entry("draftReviewMutations", true),
                        Map.entry("prSearch", true),
                        Map.entry("starredRepos", true),
                        Map.entry("existingReviews", true)));
    }

    record InitializeResult(
            String serviceName,
            String serviceVersion,
            int protocolVersion,
            Map<String, Boolean> capabilities) {}
}
