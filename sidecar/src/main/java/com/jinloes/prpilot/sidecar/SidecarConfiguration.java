package com.jinloes.prpilot.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.engine.GitHubEngine;
import com.jinloes.prpilot.engine.GitHubEngineApi;
import com.jinloes.prpilot.engine.ReviewEngineApi;
import com.jinloes.prpilot.engine.ReviewSessionService;
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

    /**
     * The whole GitHub capability surface as one bean. Individual services stay internal to the
     * engine so adding a capability means adding it to {@link GitHubEngineApi}, not threading
     * another constructor argument through the sidecar.
     */
    @Bean
    GitHubEngineApi gitHubEngine() {
        return new GitHubEngine();
    }

    @Bean
    ReviewEngineApi reviewEngine() {
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
            GitHubEngineApi gitHubEngine,
            ReviewEngineApi reviewEngine,
            ExecutorService reviewExecutor) {
        return new StdioJsonRpcServer(
                objectMapper,
                frameCodec,
                bootstrapService,
                gitHubEngine,
                reviewEngine,
                reviewExecutor);
    }
}
