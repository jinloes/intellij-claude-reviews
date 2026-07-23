package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewService;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSupplementalService;
import com.jinloes.prpilot.sidecar.repo.RepoDetector;
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
    PrDetailService prDetailService() {
        return new PrDetailService();
    }

    @Bean
    PrDiffService prDiffService() {
        return new PrDiffService();
    }

    @Bean
    DraftReviewService draftReviewService() {
        return new DraftReviewService();
    }

    @Bean
    DraftReviewMutationService draftReviewMutationService() {
        return new DraftReviewMutationService();
    }

    @Bean
    PrSupplementalService prSupplementalService() {
        return new PrSupplementalService();
    }

    @Bean
    StdioJsonRpcServer stdioJsonRpcServer(
            ObjectMapper objectMapper,
            StdioFrameCodec frameCodec,
            SidecarBootstrapService bootstrapService,
            RepoDetector repoDetector,
            GitHubAuthService gitHubAuthService,
            PrListService prListService,
            PrDetailService prDetailService,
            PrDiffService prDiffService,
            DraftReviewService draftReviewService,
            DraftReviewMutationService draftReviewMutationService,
            PrSupplementalService prSupplementalService) {
        return new StdioJsonRpcServer(
                objectMapper,
                frameCodec,
                bootstrapService,
                repoDetector,
                gitHubAuthService,
                prListService,
                prDetailService,
                prDiffService,
                draftReviewService,
                draftReviewMutationService,
                prSupplementalService);
    }
}
