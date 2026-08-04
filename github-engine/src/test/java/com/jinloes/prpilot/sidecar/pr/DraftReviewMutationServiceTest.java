package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DraftReviewMutationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private DraftReviewMutationService.SaveParams saveParams(
            List<DraftReviewMutationService.CommentInput> comments,
            List<DraftReviewMutationService.CommentInput> orphans) {
        return new DraftReviewMutationService.SaveParams(
                "https://github.com", "acme", "repo", 1, "summary", "APPROVE", comments, orphans);
    }

    @Test
    void savesDraftReviewWithoutExistingPendingReview() {
        List<String> paths = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        paths.add("GET " + path);
                        if (path.endsWith("/reviews")) {
                            return new DraftReviewMutationService.RestResponse(200, "[]");
                        }
                        return new DraftReviewMutationService.RestResponse(
                                200, "{\"head\":{\"sha\":\"abc123\"}}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        paths.add("POST " + path);
                        return new DraftReviewMutationService.RestResponse(200, "{\"id\":42}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        saveParams(
                                List.of(
                                        new DraftReviewMutationService.CommentInput(
                                                "a.java", 3, "issue", "fix", null, null, null,
                                                null)),
                                List.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.reviewId()).isEqualTo("42");
        assertThat(result.commentsDropped()).isFalse();
        assertThat(result.toString()).doesNotContain("secret-token");
        assertThat(paths).anyMatch(p -> p.equals("POST /repos/acme/repo/pulls/1/reviews"));
    }

    @Test
    void fallsBackToBodyOnlyReviewWhenCommentsAre422() throws Exception {
        AtomicInteger commentAttempts = new AtomicInteger();
        List<String> createdBodies = new ArrayList<>();
        List<String> updatedBodies = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        if (path.endsWith("/reviews")) {
                            return new DraftReviewMutationService.RestResponse(200, "[]");
                        }
                        return new DraftReviewMutationService.RestResponse(
                                200, "{\"head\":{\"sha\":\"abc123\"}}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        if (path.endsWith("/comments")) {
                            commentAttempts.incrementAndGet();
                            return new DraftReviewMutationService.RestResponse(422, "{}");
                        }
                        if (path.endsWith("/reviews") && jsonBody.contains("\"comments\":[]")) {
                            createdBodies.add(jsonBody);
                            return new DraftReviewMutationService.RestResponse(200, "{\"id\":7}");
                        }
                        return new DraftReviewMutationService.RestResponse(422, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        updatedBodies.add(jsonBody);
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        saveParams(
                                List.of(
                                        new DraftReviewMutationService.CommentInput(
                                                "a.java", 3, "issue", "fix", null, null, null,
                                                null)),
                                List.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.reviewId()).isEqualTo("7");
        assertThat(result.commentsDropped()).isTrue();
        assertThat(commentAttempts.get()).isEqualTo(1);
        assertThat(createdBodies).hasSize(1);
        assertThat(
                        new DraftReviewCodec(mapper)
                                .decode(
                                        mapper.readTree(createdBodies.get(0)).path("body").asText(),
                                        List.of())
                                .lineComments())
                .isEmpty();
        assertThat(updatedBodies).hasSize(1);
        String updatedBody;
        try {
            updatedBody = mapper.readTree(updatedBodies.get(0)).path("body").asText();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(updatedBody).contains("- `a.java:3`: fix");
        DraftReviewCodec.DecodedReview decoded =
                new DraftReviewCodec(mapper).decode(updatedBody, List.of());
        assertThat(decoded.lineComments()).isEmpty();
    }

    @Test
    void repairsFallbackMetadataWithOnlyAcceptedInlineComments() throws Exception {
        List<String> createdBodies = new ArrayList<>();
        List<String> updatedBodies = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        return path.endsWith("/reviews")
                                ? new DraftReviewMutationService.RestResponse(200, "[]")
                                : new DraftReviewMutationService.RestResponse(
                                        200, "{\"head\":{\"sha\":\"abc123\"}}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        if (path.endsWith("/comments")) {
                            return jsonBody.contains("bad")
                                    ? new DraftReviewMutationService.RestResponse(422, "{}")
                                    : new DraftReviewMutationService.RestResponse(201, "{}");
                        }
                        createdBodies.add(jsonBody);
                        return jsonBody.contains("\"comments\":[]")
                                ? new DraftReviewMutationService.RestResponse(201, "{\"id\":7}")
                                : new DraftReviewMutationService.RestResponse(422, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        updatedBodies.add(jsonBody);
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        saveParams(
                                List.of(
                                        new DraftReviewMutationService.CommentInput(
                                                "good.java",
                                                3,
                                                "issue",
                                                "good",
                                                null,
                                                null,
                                                null,
                                                null),
                                        new DraftReviewMutationService.CommentInput(
                                                "bad.java",
                                                4,
                                                "issue",
                                                "bad",
                                                null,
                                                null,
                                                null,
                                                null)),
                                List.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commentsDropped()).isTrue();
        assertThat(createdBodies).hasSize(2);
        assertThat(
                        new DraftReviewCodec(mapper)
                                .decode(
                                        mapper.readTree(createdBodies.get(1)).path("body").asText(),
                                        List.of())
                                .lineComments())
                .isEmpty();
        assertThat(updatedBodies).hasSize(1);
        String updatedBody = mapper.readTree(updatedBodies.get(0)).path("body").asText();
        DraftReviewCodec.DecodedReview decoded =
                new DraftReviewCodec(mapper).decode(updatedBody, List.of());
        assertThat(decoded.lineComments())
                .extracting(DraftReviewCodec.LineComment::body)
                .containsExactly("good");
        assertThat(updatedBody).contains("bad.java:4");
    }

    @Test
    void cleansUpFallbackReviewWhenUnexpectedCommentFailureOccurs() {
        List<String> deletedPaths = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        return path.endsWith("/reviews")
                                ? new DraftReviewMutationService.RestResponse(200, "[]")
                                : new DraftReviewMutationService.RestResponse(
                                        200, "{\"head\":{\"sha\":\"abc123\"}}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        return path.endsWith("/comments")
                                ? new DraftReviewMutationService.RestResponse(500, "{}")
                                : jsonBody.contains("\"comments\":[]")
                                        ? new DraftReviewMutationService.RestResponse(
                                                200, "{\"id\":7}")
                                        : new DraftReviewMutationService.RestResponse(422, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("finalization should not run");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        deletedPaths.add(path);
                        return new DraftReviewMutationService.RestResponse(204, "");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        saveParams(
                                List.of(
                                        new DraftReviewMutationService.CommentInput(
                                                "a.java",
                                                3,
                                                "issue",
                                                "unexpected",
                                                null,
                                                null,
                                                null,
                                                null)),
                                List.of()));

        assertThat(result.status()).isEqualTo("api_failed");
        assertThat(deletedPaths).containsExactly("/repos/acme/repo/pulls/1/reviews/7");
    }

    @Test
    void failsSaveWhenFallbackMetadataRepairIsRejected() {
        List<String> deletedPaths = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        if (path.endsWith("/reviews")) {
                            return new DraftReviewMutationService.RestResponse(200, "[]");
                        }
                        return new DraftReviewMutationService.RestResponse(
                                200, "{\"head\":{\"sha\":\"abc123\"}}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        if (path.endsWith("/comments")) {
                            return new DraftReviewMutationService.RestResponse(422, "{}");
                        }
                        if (jsonBody.contains("\"comments\":[]")) {
                            return new DraftReviewMutationService.RestResponse(200, "{\"id\":7}");
                        }
                        return new DraftReviewMutationService.RestResponse(422, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        return new DraftReviewMutationService.RestResponse(422, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        deletedPaths.add(path);
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        saveParams(
                                List.of(
                                        new DraftReviewMutationService.CommentInput(
                                                "a.java", 3, "issue", "fix", null, null, null,
                                                null)),
                                List.of()));

        assertThat(result.status()).isEqualTo("api_failed");
        assertThat(result.reviewId()).isNull();
        assertThat(deletedPaths).containsExactly("/repos/acme/repo/pulls/1/reviews/7");
    }

    @Test
    void retriesOnlyIdempotentHttpMethods() {
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("GET")).isTrue();
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("PUT")).isTrue();
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("DELETE")).isTrue();
        assertThat(DraftReviewMutationService.isRetryableHttpMethod("POST")).isFalse();
    }

    @Test
    void submitsReviewWithDefaultBodyWhenBlank() {
        List<String> bodies = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        bodies.add(jsonBody);
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        return new DraftReviewMutationService.RestResponse(200, "{}");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.submit(
                        new DraftReviewMutationService.SubmitParams(
                                "https://github.com",
                                "acme",
                                "repo",
                                1,
                                "42",
                                "REQUEST_CHANGES",
                                ""));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(bodies).anyMatch(b -> b.contains("Requesting changes."));
    }

    @Test
    void rejectsInvalidSubmitEvent() {
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        new DraftReviewMutationService.GitHubRestClient() {
                            @Override
                            public DraftReviewMutationService.RestResponse get(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse post(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse put(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse delete(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }
                        },
                        mapper);

        DraftReviewMutationResult result =
                service.submit(
                        new DraftReviewMutationService.SubmitParams(
                                "https://github.com", "acme", "repo", 1, "42", "BOGUS", "hi"));

        assertThat(result.status()).isEqualTo("invalid_request");
    }

    @Test
    void deletesDraftReview() {
        List<String> paths = new ArrayList<>();
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        paths.add(path);
                        return new DraftReviewMutationService.RestResponse(204, "");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.delete(
                        new DraftReviewMutationService.DeleteParams(
                                "https://github.com", "acme", "repo", 1, "42"));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(paths).containsExactly("/repos/acme/repo/pulls/1/reviews/42");
    }

    @Test
    void mapsNotAuthenticatedWithoutCallingClient() {
        DraftReviewMutationService.GitHubRestClient client =
                new DraftReviewMutationService.GitHubRestClient() {
                    @Override
                    public DraftReviewMutationService.RestResponse get(
                            String apiBase, String token, String path) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse post(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse put(
                            String apiBase, String token, String path, String jsonBody) {
                        throw new AssertionError("should not be called");
                    }

                    @Override
                    public DraftReviewMutationService.RestResponse delete(
                            String apiBase, String token, String path) {
                        throw new AssertionError("should not be called");
                    }
                };
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.notAuthenticated(),
                        client,
                        mapper);

        DraftReviewMutationResult result =
                service.delete(
                        new DraftReviewMutationService.DeleteParams(
                                "https://github.com", "acme", "repo", 1, "42"));

        assertThat(result.status()).isEqualTo("not_authenticated");
    }

    @Test
    void rejectsInvalidPullRequestIdentity() {
        DraftReviewMutationService service =
                new DraftReviewMutationService(
                        ignored -> GitHubAuthService.TokenResolution.resolved("secret-token"),
                        new DraftReviewMutationService.GitHubRestClient() {
                            @Override
                            public DraftReviewMutationService.RestResponse get(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse post(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse put(
                                    String apiBase, String token, String path, String jsonBody) {
                                throw new AssertionError("should not be called");
                            }

                            @Override
                            public DraftReviewMutationService.RestResponse delete(
                                    String apiBase, String token, String path) {
                                throw new AssertionError("should not be called");
                            }
                        },
                        mapper);

        DraftReviewMutationResult result =
                service.save(
                        new DraftReviewMutationService.SaveParams(
                                "https://github.com",
                                "acme",
                                "repo",
                                0,
                                "s",
                                "APPROVE",
                                List.of(),
                                List.of()));

        assertThat(result.status()).isEqualTo("invalid_request");
    }
}
