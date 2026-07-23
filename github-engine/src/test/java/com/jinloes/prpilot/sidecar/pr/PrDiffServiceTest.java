package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PrDiffServiceTest {
    @Test
    void usesReviewLimitForReviewMode() {
        AtomicInteger requestedLimit = new AtomicInteger();
        PrDiffService service = service(requestedLimit);

        PrDiffResult result = service.get(params("review"));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.limitBytes()).isEqualTo(PrDiffService.REVIEW_LIMIT_BYTES);
        assertThat(requestedLimit.get()).isEqualTo(PrDiffService.REVIEW_LIMIT_BYTES);
    }

    @Test
    void usesValidationLimitForValidationMode() {
        AtomicInteger requestedLimit = new AtomicInteger();
        PrDiffService service = service(requestedLimit);

        PrDiffResult result = service.get(params("validation"));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.limitBytes()).isEqualTo(PrDiffService.VALIDATION_LIMIT_BYTES);
        assertThat(requestedLimit.get()).isEqualTo(PrDiffService.VALIDATION_LIMIT_BYTES);
    }

    @Test
    void rejectsUnknownModesWithoutResolvingAToken() {
        AtomicInteger tokenCalls = new AtomicInteger();
        PrDiffService service =
                new PrDiffService(
                        hostname -> {
                            tokenCalls.incrementAndGet();
                            return GitHubAuthService.TokenResolution.resolved("secret-token");
                        },
                        (api, token, owner, repo, number, limit) ->
                                PrDiffService.Response.ok("diff", false));

        PrDiffResult result = service.get(params("archive"));

        assertThat(result.status()).isEqualTo("invalid_request");
        assertThat(tokenCalls).hasValue(0);
    }

    @Test
    void retriesEveryTransientFailureClass() {
        for (PrDiffService.Status status :
                List.of(
                        PrDiffService.Status.RATE_LIMITED,
                        PrDiffService.Status.NETWORK,
                        PrDiffService.Status.TRANSIENT_API)) {
            ArrayDeque<PrDiffService.Response> responses =
                    new ArrayDeque<>(
                            List.of(
                                    PrDiffService.Response.of(status),
                                    PrDiffService.Response.ok("diff", false)));
            List<Integer> backoffs = new ArrayList<>();
            PrDiffService service =
                    new PrDiffService(
                            hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                            (api, token, owner, repo, number, limit) -> responses.removeFirst(),
                            backoffs::add);

            PrDiffResult result = service.get(params("review"));

            assertThat(result.status()).as(status.name()).isEqualTo("ok");
            assertThat(backoffs).as(status.name()).containsExactly(1);
        }
    }

    @Test
    void stopsAfterThreeTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> backoffs = new ArrayList<>();
        PrDiffService service =
                new PrDiffService(
                        hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        (api, token, owner, repo, number, limit) -> {
                            attempts.incrementAndGet();
                            return PrDiffService.Response.of(PrDiffService.Status.RATE_LIMITED);
                        },
                        backoffs::add);

        PrDiffResult result = service.get(params("review"));

        assertThat(result.status()).isEqualTo("rate_limited");
        assertThat(attempts).hasValue(3);
        assertThat(backoffs).containsExactly(1, 2);
    }

    @Test
    void doesNotRetryPermanentApiFailures() {
        AtomicInteger attempts = new AtomicInteger();
        PrDiffService service =
                new PrDiffService(
                        hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        (api, token, owner, repo, number, limit) -> {
                            attempts.incrementAndGet();
                            return PrDiffService.Response.of(PrDiffService.Status.API);
                        },
                        attempt -> {});

        assertThat(service.get(params("review")).status()).isEqualTo("api_failed");
        assertThat(attempts).hasValue(1);
    }

    private static PrDiffService service(AtomicInteger requestedLimit) {
        return new PrDiffService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                (api, token, owner, repo, number, limit) -> {
                    requestedLimit.set(limit);
                    return PrDiffService.Response.ok("diff", false);
                });
    }

    private static PrDiffService.Params params(String mode) {
        return new PrDiffService.Params("https://github.com", "acme", "widgets", 42, mode);
    }
}
