package com.jinloes.prpilot.sidecar;

import java.util.Map;

final class SidecarBootstrapService {
    InitializeResult initialize() {
        return new InitializeResult(
                "pr-pilot-sidecar",
                "0.1.0",
                1,
                Map.of("prSearchQuery", true, "reviewParse", true, "repoDetect", true));
    }

    record InitializeResult(
            String serviceName,
            String serviceVersion,
            int protocolVersion,
            Map<String, Boolean> capabilities) {}
}
