package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSearchQueryService;
import com.jinloes.prpilot.sidecar.repo.RepoDetector;
import com.jinloes.prpilot.sidecar.review.ReviewJsonParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SidecarConfiguration {
    @Bean
    StdioFrameCodec stdioFrameCodec() {
        return new StdioFrameCodec();
    }

    @Bean
    SidecarBootstrapService sidecarBootstrapService() {
        return new SidecarBootstrapService();
    }

    @Bean
    ReviewJsonParser reviewJsonParser(ObjectMapper objectMapper) {
        return new ReviewJsonParser(objectMapper);
    }

    @Bean
    PrSearchQueryService prSearchQueryService() {
        return new PrSearchQueryService();
    }

    @Bean
    RepoDetector repoDetector() {
        return new RepoDetector();
    }

    @Bean
    GitHubAuthService gitHubAuthService() {
        return new GitHubAuthService();
    }

    @Bean
    PrListService prListService() {
        return new PrListService();
    }

    @Bean
    StdioJsonRpcServer stdioJsonRpcServer(
            ObjectMapper objectMapper,
            StdioFrameCodec frameCodec,
            SidecarBootstrapService bootstrapService,
            ReviewJsonParser reviewJsonParser,
            PrSearchQueryService prSearchQueryService,
            RepoDetector repoDetector,
            GitHubAuthService gitHubAuthService,
            PrListService prListService) {
        return new StdioJsonRpcServer(
                objectMapper,
                frameCodec,
                bootstrapService,
                reviewJsonParser,
                prSearchQueryService,
                repoDetector,
                gitHubAuthService,
                prListService);
    }
}
