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
import com.jinloes.prpilot.sidecar.review.ReviewSessionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    ReviewSessionService reviewSessionService() {
        return new ReviewSessionService();
    }

    /**
     * Single background thread for provider CLI I/O: only one review or chat request is ever active
     * per sidecar process (matching IntelliJ's in-process behavior), so a size-1 pool is sufficient
     * — it just needs to be off the stdio read loop so {@code reviews/cancel} can be processed
     * while a review is in flight.
     */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService reviewExecutor() {
        return Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "review-session");
                    thread.setDaemon(true);
                    return thread;
                });
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
            PrSupplementalService prSupplementalService,
            ReviewSessionService reviewSessionService,
            ExecutorService reviewExecutor) {
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
                prSupplementalService,
                reviewSessionService,
                reviewExecutor);
    }
}
