package com.jinloes.prpilot.sidecar.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinloes.prpilot.sidecar.github.GitHubApiClient;
import com.jinloes.prpilot.sidecar.github.GitHubAuthService;
import com.jinloes.prpilot.sidecar.github.GitHubFailure;
import com.jinloes.prpilot.sidecar.github.GitHubResponse;
import com.jinloes.prpilot.sidecar.github.GitHubSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads CI state for a PR head commit: Checks API check runs, their file-anchored annotations, and
 * the legacy commit-status API as a fallback for Jenkins/Buildkite-style integrations.
 *
 * <p>Annotations are fetched for a check that failed, or that reports having annotations regardless
 * of its conclusion, with failing checks taking priority for the limited budget. Static-analysis
 * checks are routinely configured as advisory and conclude {@code success} or {@code neutral} while
 * still reporting file-anchored findings, so keying on the conclusion alone would discard the most
 * review-shaped evidence CI produces.
 *
 * <p>Everything is bounded before it leaves this class. CI output is attacker-influenceable (a
 * contributor controls the code that produces it) and unbounded in principle, so counts and
 * per-field lengths are capped here rather than at the prompt layer.
 */
public final class CheckRunService {

    static final int MAX_CHECK_RUNS = 30;
    static final int MAX_ANNOTATIONS = 20;
    static final int MAX_ANNOTATED_CHECKS = 5;
    static final int MAX_MESSAGE_CHARS = 200;
    static final int MAX_OUTPUT_CHARS = 300;

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final GitHubApiClient client;
    private final ObjectMapper mapper;

    public CheckRunService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                GitHubApiClient.http(),
                new ObjectMapper());
    }

    CheckRunService(
            GitHubAuthService.TokenResolver tokenResolver,
            GitHubApiClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public CheckStatusResult checkStatus(Params params) {
        if (!PromptContext.validRepo(params.owner(), params.repo())
                || !PromptContext.validSha(params.headSha())) {
            return CheckStatusResult.failure(GitHubFailure.INVALID_REQUEST);
        }
        GitHubSession session = GitHubSession.open(tokenResolver, params.githubBaseUrl());
        if (!session.isOpen()) {
            return CheckStatusResult.failure(session.failure());
        }

        String repoPath = "/repos/" + params.owner() + "/" + params.repo();
        GitHubResponse response =
                client.get(
                        session.apiBaseUrl(),
                        session.token(),
                        repoPath + "/commits/" + params.headSha() + "/check-runs?per_page=100");
        GitHubFailure failure = GitHubFailure.of(response);
        if (failure != null) {
            return CheckStatusResult.failure(failure);
        }

        List<CheckRunSummary> checkRuns;
        List<String> annotatedIds = new ArrayList<>();
        try {
            checkRuns = parseCheckRuns(response.body(), annotatedIds);
        } catch (IOException exception) {
            return CheckStatusResult.failure(GitHubFailure.INVALID_RESPONSE);
        }

        if (checkRuns.isEmpty()) {
            return legacyStatus(session, repoPath, params.headSha());
        }

        List<CheckAnnotation> annotations = annotations(session, repoPath, annotatedIds);
        String state =
                checkRuns.stream().anyMatch(CheckRunSummary::isPending)
                        ? CheckStatusResult.STATE_IN_PROGRESS
                        : CheckStatusResult.STATE_COMPLETE;
        return CheckStatusResult.success(
                state, checkRuns, annotations, render(state, checkRuns, annotations));
    }

    private List<CheckRunSummary> parseCheckRuns(String body, List<String> annotatedIds)
            throws IOException {
        JsonNode runs = mapper.readTree(body).path("check_runs");
        if (!runs.isArray()) {
            throw new IOException("check_runs was not an array");
        }
        List<CheckRunSummary> summaries = new ArrayList<>();
        List<AnnotationSource> sources = new ArrayList<>();
        for (JsonNode run : runs) {
            if (summaries.size() >= MAX_CHECK_RUNS) break;
            String name = PromptContext.oneLine(run.path("name").asText(""), 120);
            if (name.isEmpty()) continue;
            CheckRunSummary summary =
                    new CheckRunSummary(
                            name,
                            run.path("status").asText("completed"),
                            run.path("conclusion").asText(""),
                            checkOutput(run.path("output")));
            summaries.add(summary);

            // Probe a check's annotations when it failed (unchanged behavior) OR when it reports
            // having any (new). Static-analysis checks — Qodana, CodeQL, ktlint — are routinely
            // configured as advisory and conclude "success" or "neutral" while still reporting
            // file-anchored findings, which is the most review-shaped evidence CI produces.
            // The condition is a union rather than a swap so a provider that omits
            // annotations_count keeps the failing-check behavior it has today. annotations_count
            // arrives in this same list response, so testing it costs no extra request.
            String id = run.path("id").asText("");
            if (id.isEmpty() || !id.chars().allMatch(Character::isDigit)) continue;
            boolean reportsAnnotations = run.path("output").path("annotations_count").asInt(0) > 0;
            if (!summary.isFailing() && !reportsAnnotations) continue;
            sources.add(new AnnotationSource(id, summary.isFailing()));
        }

        // Failing checks first: the annotation budget is small, and a broken build is more
        // actionable than an advisory lint note.
        sources.stream()
                .filter(AnnotationSource::failing)
                .limit(MAX_ANNOTATED_CHECKS)
                .forEach(source -> annotatedIds.add(source.id()));
        sources.stream()
                .filter(source -> !source.failing())
                .limit(Math.max(0, MAX_ANNOTATED_CHECKS - annotatedIds.size()))
                .forEach(source -> annotatedIds.add(source.id()));
        return summaries;
    }

    /** A check run that reported at least one annotation, and whether it failed. */
    private record AnnotationSource(String id, boolean failing) {}

    private static String checkOutput(JsonNode output) {
        String title = PromptContext.oneLine(output.path("title").asText(""), 120);
        String summary = PromptContext.oneLine(output.path("summary").asText(""), MAX_OUTPUT_CHARS);
        if (title.isEmpty()) return summary;
        if (summary.isEmpty()) return title;
        return title + " — " + summary;
    }

    /**
     * Fetches annotations for the selected checks, stopping at {@link #MAX_ANNOTATIONS}. A failure
     * here degrades to fewer annotations rather than discarding the whole CI result: the check-run
     * conclusions alone are still useful.
     */
    private List<CheckAnnotation> annotations(
            GitHubSession session, String repoPath, List<String> annotatedIds) {
        List<CheckAnnotation> annotations = new ArrayList<>();
        for (String id : annotatedIds) {
            if (annotations.size() >= MAX_ANNOTATIONS) break;
            GitHubResponse response =
                    client.get(
                            session.apiBaseUrl(),
                            session.token(),
                            repoPath + "/check-runs/" + id + "/annotations?per_page=50");
            if (!response.isSuccess()) continue;
            try {
                JsonNode items = mapper.readTree(response.body());
                if (!items.isArray()) continue;
                for (JsonNode item : items) {
                    if (annotations.size() >= MAX_ANNOTATIONS) break;
                    String path = PromptContext.oneLine(item.path("path").asText(""), 200);
                    String message =
                            PromptContext.oneLine(
                                    item.path("message").asText(""), MAX_MESSAGE_CHARS);
                    if (path.isEmpty() || message.isEmpty()) continue;
                    annotations.add(
                            new CheckAnnotation(
                                    path,
                                    Math.max(0, item.path("start_line").asInt(0)),
                                    Math.max(0, item.path("end_line").asInt(0)),
                                    item.path("annotation_level").asText("warning"),
                                    message));
                }
            } catch (IOException ignored) {
                // A malformed annotation payload must not discard the usable check-run summary.
            }
        }
        return annotations;
    }

    /**
     * Falls back to the legacy commit-status API, which older CI integrations still write to and
     * which the Checks API does not surface.
     */
    private CheckStatusResult legacyStatus(GitHubSession session, String repoPath, String sha) {
        GitHubResponse response =
                client.get(
                        session.apiBaseUrl(),
                        session.token(),
                        repoPath + "/commits/" + sha + "/status");
        if (!response.isSuccess()) {
            return CheckStatusResult.none();
        }
        try {
            JsonNode statuses = mapper.readTree(response.body()).path("statuses");
            if (!statuses.isArray() || statuses.isEmpty()) {
                return CheckStatusResult.none();
            }
            List<CheckRunSummary> checkRuns = new ArrayList<>();
            for (JsonNode status : statuses) {
                if (checkRuns.size() >= MAX_CHECK_RUNS) break;
                String context = PromptContext.oneLine(status.path("context").asText(""), 120);
                if (context.isEmpty()) continue;
                String state = status.path("state").asText("pending");
                checkRuns.add(
                        new CheckRunSummary(
                                context,
                                "pending".equals(state) ? "in_progress" : "completed",
                                switch (state) {
                                    case "success" -> "success";
                                    case "failure", "error" -> "failure";
                                    default -> "";
                                },
                                PromptContext.oneLine(
                                        status.path("description").asText(""), MAX_OUTPUT_CHARS)));
            }
            if (checkRuns.isEmpty()) {
                return CheckStatusResult.none();
            }
            String state =
                    checkRuns.stream().anyMatch(CheckRunSummary::isPending)
                            ? CheckStatusResult.STATE_IN_PROGRESS
                            : CheckStatusResult.STATE_COMPLETE;
            return CheckStatusResult.success(
                    state, checkRuns, List.of(), render(state, checkRuns, List.of()));
        } catch (IOException exception) {
            return CheckStatusResult.none();
        }
    }

    /**
     * Renders the prompt text. Pending checks are stated explicitly so the model cannot infer that
     * silence means success, which is the main way partial CI could mislead a review.
     */
    static String render(
            String state, List<CheckRunSummary> checkRuns, List<CheckAnnotation> annotations) {
        List<CheckRunSummary> failing =
                checkRuns.stream().filter(CheckRunSummary::isFailing).toList();
        List<CheckRunSummary> pending =
                checkRuns.stream().filter(CheckRunSummary::isPending).toList();
        StringBuilder text = new StringBuilder();
        text.append(failing.size())
                .append(" of ")
                .append(checkRuns.size())
                .append(" checks failing.");
        if (CheckStatusResult.STATE_IN_PROGRESS.equals(state)) {
            text.append(" ")
                    .append(pending.size())
                    .append(" still running — their results are NOT yet known, so do not assume")
                    .append(" they pass.");
        }
        for (CheckRunSummary run : failing) {
            text.append("\n\nFAILING: ").append(run.name());
            if (!run.output().isEmpty()) {
                text.append("\n  ").append(run.output());
            }
        }
        for (CheckRunSummary run : pending) {
            text.append("\n\nSTILL RUNNING: ").append(run.name());
        }
        if (!annotations.isEmpty()) {
            text.append("\n\nCI reported these specific locations:");
            for (CheckAnnotation annotation : annotations) {
                text.append("\n  - [")
                        .append(annotation.level())
                        .append("] ")
                        .append(annotation.location())
                        .append(": ")
                        .append(annotation.message());
            }
        }
        return text.toString();
    }

    /** Identifies the commit whose CI state to read — the PR head SHA. */
    public record Params(String githubBaseUrl, String owner, String repo, String headSha) {}
}
