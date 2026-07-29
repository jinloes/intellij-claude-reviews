package com.jinloes.prpilot.sidecar.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GitHubFailureTest {

    @Nested
    class Of {
        @Test
        void returnsNullForEverySuccessStatus() {
            for (int status : new int[] {200, 201, 204, 299}) {
                assertThat(GitHubFailure.of(new GitHubResponse(status, "{}")))
                        .as("%d", status)
                        .isNull();
            }
        }

        @Test
        void mapsUnauthenticatedStatusesToAnActionableMessage() {
            for (int status : new int[] {401, 403}) {
                assertThat(GitHubFailure.of(new GitHubResponse(status, "")))
                        .as("%d", status)
                        .isEqualTo(GitHubFailure.NOT_AUTHENTICATED);
            }
            assertThat(GitHubFailure.NOT_AUTHENTICATED.message()).contains("gh auth login");
        }

        @Test
        void mapsRateLimitingSeparatelyFromGenericFailure() {
            assertThat(GitHubFailure.of(new GitHubResponse(429, "")))
                    .isEqualTo(GitHubFailure.RATE_LIMITED);
            assertThat(GitHubFailure.of(new GitHubResponse(500, "")))
                    .isEqualTo(GitHubFailure.API_FAILED);
        }

        @Test
        void mapsTheSyntheticTransportStatusToANetworkError() {
            assertThat(GitHubFailure.of(GitHubResponse.networkError()))
                    .isEqualTo(GitHubFailure.NETWORK_ERROR);
        }

        @Test
        void mapsUnexpectedClientErrorsToGenericApiFailure() {
            assertThat(GitHubFailure.of(new GitHubResponse(404, "")))
                    .isEqualTo(GitHubFailure.API_FAILED);
        }
    }

    @Nested
    class Constants {
        @Test
        void exposeStableStatusStringsHostsBranchOn() {
            assertThat(GitHubFailure.INVALID_BASE_URL.status()).isEqualTo("invalid_base_url");
            assertThat(GitHubFailure.INVALID_REQUEST.status()).isEqualTo("invalid_request");
            assertThat(GitHubFailure.NOT_INSTALLED.status()).isEqualTo("not_installed");
            assertThat(GitHubFailure.NOT_AUTHENTICATED.status()).isEqualTo("not_authenticated");
            assertThat(GitHubFailure.RATE_LIMITED.status()).isEqualTo("rate_limited");
            assertThat(GitHubFailure.NETWORK_ERROR.status()).isEqualTo("network_error");
            assertThat(GitHubFailure.API_FAILED.status()).isEqualTo("api_failed");
        }

        @Test
        void reuseTheApiFailedStatusForMalformedBodies() {
            assertThat(GitHubFailure.INVALID_RESPONSE.status()).isEqualTo("api_failed");
            assertThat(GitHubFailure.INVALID_RESPONSE.message())
                    .isNotEqualTo(GitHubFailure.API_FAILED.message());
        }
    }
}
