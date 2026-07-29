package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

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

class LinkedIssueServiceTest {

    private static LinkedIssueService service(FakeClient client) {
        return new LinkedIssueService(
                hostname -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                client,
                new ObjectMapper());
    }

    private static LinkedIssueService.Params params(String prBody) {
        return new LinkedIssueService.Params("https://github.com", "acme", "widgets", prBody);
    }

    private static GitHubResponse ok(String body) {
        return new GitHubResponse(200, body);
    }

    @Nested
    class ReferencedIssueNumbers {
        @Test
        void recognizesEveryGitHubClosingKeyword() {
            for (String keyword :
                    List.of(
                            "close",
                            "closes",
                            "closed",
                            "fix",
                            "fixes",
                            "fixed",
                            "resolve",
                            "resolves",
                            "resolved")) {
                assertThat(LinkedIssueService.referencedIssueNumbers(keyword + " #7"))
                        .as(keyword)
                        .containsExactly(7);
            }
        }

        @Test
        void isCaseInsensitiveAndToleratesAColon() {
            assertThat(LinkedIssueService.referencedIssueNumbers("Closes: #12"))
                    .containsExactly(12);
            assertThat(LinkedIssueService.referencedIssueNumbers("FIXES #12")).containsExactly(12);
        }

        @Test
        void ignoresBareMentionsThatCloseNothing() {
            assertThat(LinkedIssueService.referencedIssueNumbers("Related to #12")).isEmpty();
            assertThat(LinkedIssueService.referencedIssueNumbers("see #12 for context")).isEmpty();
        }

        @Test
        void ignoresCrossRepositoryReferences() {
            assertThat(LinkedIssueService.referencedIssueNumbers("Closes other/repo#12")).isEmpty();
        }

        @Test
        void deduplicatesAndCapsTheNumberOfIssues() {
            assertThat(LinkedIssueService.referencedIssueNumbers("Closes #1 fixes #1"))
                    .containsExactly(1);
            assertThat(
                            LinkedIssueService.referencedIssueNumbers(
                                    "Closes #1 closes #2 closes #3 closes #4"))
                    .hasSize(LinkedIssueService.MAX_ISSUES);
        }

        @Test
        void handlesAnAbsentBody() {
            assertThat(LinkedIssueService.referencedIssueNumbers(null)).isEmpty();
            assertThat(LinkedIssueService.referencedIssueNumbers("  ")).isEmpty();
        }
    }

    @Nested
    class LinkedIssues {
        @Test
        void rendersTitleStateLabelsAndBody() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(
                            "{\"title\":\"Login fails\",\"state\":\"open\","
                                    + "\"labels\":[{\"name\":\"bug\"},{\"name\":\"p1\"}],"
                                    + "\"body\":\"SSO users cannot sign in.\"}"));

            LinkedIssueResult result = service(client).linkedIssues(params("Closes #7"));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.summary())
                    .isEqualTo(
                            "#7: Login fails (open)\nLabels: bug, p1\nSSO users cannot sign in.");
            assertThat(client.paths)
                    .singleElement()
                    .asString()
                    .isEqualTo("/repos/acme/widgets/issues/7");
        }

        @Test
        void skipsPullRequestsServedByTheIssuesEndpoint() {
            FakeClient client = new FakeClient();
            client.responses.add(ok("{\"title\":\"Some PR\",\"pull_request\":{}}"));

            LinkedIssueResult result = service(client).linkedIssues(params("Closes #7"));

            assertThat(result.count()).isZero();
            assertThat(result.summary()).isEmpty();
            assertThat(result.status()).isEqualTo("ok");
        }

        @Test
        void makesNoRequestWhenNothingIsLinked() {
            FakeClient client = new FakeClient();

            LinkedIssueResult result = service(client).linkedIssues(params("No references here"));

            assertThat(result.status()).isEqualTo("ok");
            assertThat(result.summary()).isEmpty();
            assertThat(client.paths).isEmpty();
        }

        @Test
        void keepsTheIssuesItCouldResolveWhenAnotherLookupFails() {
            FakeClient client = new FakeClient();
            client.responses.add(new GitHubResponse(404, ""));
            client.responses.add(ok("{\"title\":\"Second\",\"state\":\"open\"}"));

            LinkedIssueResult result = service(client).linkedIssues(params("Closes #1 closes #2"));

            assertThat(result.count()).isEqualTo(1);
            assertThat(result.summary()).isEqualTo("#2: Second (open)");
        }

        @Test
        void rejectsAnInvalidRepositoryBeforeParsingOrResolvingCredentials() {
            int[] tokenCalls = {0};
            LinkedIssueService service =
                    new LinkedIssueService(
                            hostname -> {
                                tokenCalls[0]++;
                                return GitHubAuthService.TokenResolution.resolved("t");
                            },
                            new FakeClient(),
                            new ObjectMapper());

            LinkedIssueResult result =
                    service.linkedIssues(
                            new LinkedIssueService.Params(
                                    "https://github.com", "..", "widgets", "Closes #1"));

            assertThat(result.status()).isEqualTo("invalid_request");
            assertThat(tokenCalls[0]).isZero();
        }

        @Test
        void surfacesAuthFailuresWithoutLeakingTheToken() {
            LinkedIssueService service =
                    new LinkedIssueService(
                            hostname -> GitHubAuthService.TokenResolution.notAuthenticated(),
                            new FakeClient(),
                            new ObjectMapper());

            LinkedIssueResult result = service.linkedIssues(params("Closes #1"));

            assertThat(result.status()).isEqualTo("not_authenticated");
            assertThat(result.toString()).doesNotContain("secret-token");
        }

        @Test
        void boundsAVeryLongIssueBody() {
            FakeClient client = new FakeClient();
            client.responses.add(
                    ok(
                            "{\"title\":\"Big\",\"state\":\"open\",\"body\":\""
                                    + "x".repeat(5000)
                                    + "\"}"));

            LinkedIssueResult result = service(client).linkedIssues(params("Closes #1"));

            assertThat(result.summary()).contains("…[truncated]");
            assertThat(result.summary().length())
                    .isLessThan(LinkedIssueService.MAX_BODY_CHARS + 200);
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
