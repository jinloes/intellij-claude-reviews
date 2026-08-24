package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubApiClient;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrCommitsServiceTest {

    private static PrCommitsService service(FakeClient client) {
        return new PrCommitsService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                client,
                new ObjectMapper());
    }

    private static PrSupplementalService.IdentityParams params() {
        return new PrSupplementalService.IdentityParams(
                "https://github.com", "acme", "widgets", 42);
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    private static String commit(String message) {
        return "{\"commit\":{\"message\":\"" + message + "\"}}";
    }

    @Nested
    class Commits {
        @Test
        void rendersSubjectAndBodySeparately() {
            FakeClient client = new FakeClient();
            client.responses.add(ok("[" + commit("Fix login\\n\\nSSO tokens expired early") + "]"));

            PrCommitsResult result = service(client).commits(params());

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.summary()).isEqualTo("- Fix login\n    SSO tokens expired early");
        }

        @Test
        void requestsTheCommitsOfTheGivenPullRequest() {
            FakeClient client = new FakeClient();
            client.responses.add(ok("[" + commit("Fix") + "]"));

            service(client).commits(params());

            assertThat(client.paths)
                    .singleElement()
                    .asString()
                    .isEqualTo("/repos/acme/widgets/pulls/42/commits?per_page=100");
        }

        @Test
        void omitsTheBodyLineForSingleLineMessages() {
            FakeClient client = new FakeClient();
            client.responses.add(ok("[" + commit("Fix login") + "]"));

            assertThat(service(client).commits(params()).summary()).isEqualTo("- Fix login");
        }

        @Test
        void extractsDeduplicatedClosingReferencesFromRawMessages() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(
                            "["
                                    + commit("First\\n\\nFixes #7 and mentions #99")
                                    + ","
                                    + commit("Closes other/repo#8\\nResolves #7\\nFixed #8")
                                    + "]"));

            PrCommitsResult result = service(client).commits(params());

            assertThat(result.closingIssueNumbers()).containsExactly(7, 8);
        }

        @Test
        void extractsAClosingFooterBeyondTheRenderedBodyLimit() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(
                            "["
                                    + commit(
                                            "Subject\\n"
                                                    + "x"
                                                            .repeat(
                                                                    PrCommitsService.MAX_BODY_CHARS
                                                                            + 50)
                                                    + "\\nFixes #42")
                                    + "]"));

            PrCommitsResult result = service(client).commits(params());

            assertThat(result.summary()).doesNotContain("Fixes #42");
            assertThat(result.closingIssueNumbers()).containsExactly(42);
        }

        @Test
        void boundsClosingReferencesButStillScansCommitsBeyondTheDisplayCap() {
            StringBuilder body = new StringBuilder("[");
            for (int i = 0; i < PrCommitsService.MAX_COMMITS; i++) {
                if (i > 0) body.append(",");
                body.append(commit("Commit " + i));
            }
            body.append(",").append(commit("Fixes #4 closes #5 resolves #6 fixes #7")).append("]");
            FakeClient client = new FakeClient();
            client.responses.add(ok(body.toString()));

            PrCommitsResult result = service(client).commits(params());

            assertThat(result.count()).isEqualTo(PrCommitsService.MAX_COMMITS);
            assertThat(result.summary()).endsWith("…and 1 more commits.");
            assertThat(result.closingIssueNumbers()).containsExactly(4, 5, 6);
        }

        @Test
        void exposesClosingReferencesAsAnImmutableDefensiveCopy() {
            List<Integer> issueNumbers = new ArrayList<>(List.of(7));

            PrCommitsResult result = new PrCommitsResult("ok", "loaded", 1, "- Fix", issueNumbers);
            issueNumbers.add(8);

            assertThat(result.closingIssueNumbers()).containsExactly(7);
            assertThatThrownBy(() -> result.closingIssueNumbers().add(9))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void skipsCommitsWithNoMessage() {
            FakeClient client = new FakeClient();
            client.responses.add(ok("[" + commit("  ") + "," + commit("Real") + "]"));

            PrCommitsResult result = service(client).commits(params());

            assertThat(result.count()).isEqualTo(1);
            assertThat(result.summary()).isEqualTo("- Real");
        }

        @Test
        void boundsLongHistoriesAndSaysHowManyWereDropped() {
            StringBuilder body = new StringBuilder("[");
            for (int i = 0; i < PrCommitsService.MAX_COMMITS + 5; i++) {
                if (i > 0) body.append(",");
                body.append(commit("Commit " + i));
            }
            body.append("]");
            FakeClient client = new FakeClient();
            client.responses.add(ok(body.toString()));

            PrCommitsResult result = service(client).commits(params());

            assertThat(result.count()).isEqualTo(PrCommitsService.MAX_COMMITS);
            assertThat(result.summary()).endsWith("…and 5 more commits.");
        }

        @Test
        void rejectsAnInvalidIdentityBeforeResolvingCredentials() {
            int[] tokenCalls = {0};
            PrCommitsService service =
                    new PrCommitsService(
                            hostname -> {
                                tokenCalls[0]++;
                                return GitHubAuthService.TokenResolution.resolved("t");
                            },
                            new FakeClient(),
                            new ObjectMapper());

            assertThat(
                            service.commits(
                                            new PrSupplementalService.IdentityParams(
                                                    "https://github.com", "acme", "widgets", 0))
                                    .status())
                    .isEqualTo("invalid_request");
            assertThat(
                            service.commits(
                                            new PrSupplementalService.IdentityParams(
                                                    "https://github.com", "a/b", "widgets", 1))
                                    .status())
                    .isEqualTo("invalid_request");
            assertThat(tokenCalls[0]).isZero();
        }

        @Test
        void mapsApiAndTransportFailuresWithoutLeakingTheToken() {
            FakeClient unauthorized = new FakeClient();
            unauthorized.responses.add(new GitHubResponse(401, ""));
            PrCommitsResult result = service(unauthorized).commits(params());

            assertThat(result.status()).isEqualTo("not_authenticated");
            assertThat(result.summary()).isEmpty();
            assertThat(result.closingIssueNumbers()).isEmpty();
            assertThat(result.toString()).doesNotContain("secret-token");

            FakeClient offline = new FakeClient();
            offline.responses.add(GitHubResponse.networkError());
            assertThat(service(offline).commits(params()).status()).isEqualTo("network_error");
        }

        @Test
        void rejectsAMalformedPayload() {
            FakeClient client = new FakeClient();
            client.responses.add(ok("{}"));

            assertThat(service(client).commits(params()).status()).isEqualTo("api_failed");
        }
    }

    private static final class FakeClient implements GitHubApiClient {
        private final Deque<GitHubResponse> responses = new ArrayDeque<>();
        private final List<String> paths = new ArrayList<>();

        @Override
        public GitHubResponse get(String apiBaseUrl, String token, String path) {
            paths.add(path);
            GitHubResponse response = responses.poll();
            return response == null ? new GitHubResponse(404, "") : response;
        }
    }
}
