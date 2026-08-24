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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the commit messages on a pull request.
 *
 * <p>Only the subject line and a bounded body are kept per commit: the value is the author's stated
 * intent, and full bodies on a long-lived branch would crowd out the diff itself. Same-repository
 * closing references are extracted from each raw message before that display truncation.
 */
public final class PrCommitsService {

    static final int MAX_COMMITS = 50;
    static final int MAX_SUBJECT_CHARS = 160;
    static final int MAX_BODY_CHARS = 300;

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final GitHubApiClient client;
    private final ObjectMapper mapper;

    public PrCommitsService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                GitHubApiClient.http(),
                new ObjectMapper());
    }

    PrCommitsService(
            GitHubAuthService.TokenResolver tokenResolver,
            GitHubApiClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public PrCommitsResult commits(PrSupplementalService.IdentityParams params) {
        if (params.number() <= 0 || !PromptContext.validRepo(params.owner(), params.repo())) {
            return PrCommitsResult.failure(GitHubFailure.INVALID_REQUEST);
        }
        GitHubSession session = GitHubSession.open(tokenResolver, params.githubBaseUrl());
        if (!session.isOpen()) {
            return PrCommitsResult.failure(session.failure());
        }
        GitHubResponse response =
                client.get(
                        session.apiBaseUrl(),
                        session.token(),
                        "/repos/"
                                + params.owner()
                                + "/"
                                + params.repo()
                                + "/pulls/"
                                + params.number()
                                + "/commits?per_page=100");
        GitHubFailure failure = GitHubFailure.of(response);
        if (failure != null) {
            return PrCommitsResult.failure(failure);
        }
        try {
            JsonNode commits = mapper.readTree(response.body());
            if (!commits.isArray()) {
                return PrCommitsResult.failure(GitHubFailure.INVALID_RESPONSE);
            }
            List<String> lines = new ArrayList<>();
            Set<Integer> closingIssueNumbers = new LinkedHashSet<>();
            int count = 0;
            int examined = 0;
            for (JsonNode commit : commits) {
                String message = commit.path("commit").path("message").asText("");
                if (!message.isBlank()) {
                    addClosingIssueNumbers(message, closingIssueNumbers);
                }
                if (count >= MAX_COMMITS) continue;
                examined++;
                if (message.isBlank()) continue;
                count++;
                lines.add("- " + subject(message));
                String body = body(message);
                if (!body.isEmpty()) {
                    lines.add("    " + body);
                }
            }
            // Only commits the cap actually excluded are "more" — skipping an empty message is not
            // truncation, and reporting it as such would overstate how much history was withheld.
            int dropped = commits.size() - examined;
            if (dropped > 0) {
                lines.add("…and " + dropped + " more commits.");
            }
            return PrCommitsResult.success(
                    count, String.join("\n", lines), List.copyOf(closingIssueNumbers));
        } catch (IOException exception) {
            return PrCommitsResult.failure(GitHubFailure.INVALID_RESPONSE);
        }
    }

    private static void addClosingIssueNumbers(String message, Set<Integer> issueNumbers) {
        for (int number : LinkedIssueService.referencedIssueNumbers(message)) {
            if (issueNumbers.size() >= LinkedIssueService.MAX_ISSUES) return;
            issueNumbers.add(number);
        }
    }

    private static String subject(String message) {
        int newline = message.indexOf('\n');
        return PromptContext.oneLine(
                newline < 0 ? message : message.substring(0, newline), MAX_SUBJECT_CHARS);
    }

    private static String body(String message) {
        int newline = message.indexOf('\n');
        return newline < 0
                ? ""
                : PromptContext.oneLine(message.substring(newline + 1), MAX_BODY_CHARS);
    }
}
