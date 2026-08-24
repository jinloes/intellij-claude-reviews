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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the issues a pull request declares it closes, by parsing GitHub's closing keywords out
 * of the PR body and merging validated closing references extracted from its commit messages.
 *
 * <p>Only same-repository {@code #N} references are followed. Cross-repository forms ({@code
 * owner/repo#N}) and full URLs are deliberately ignored: resolving them would mean issuing requests
 * against repositories the reviewer may not have intended to touch, for marginal value.
 *
 * <p>Pull requests are filtered out of the results. GitHub's issues endpoint also serves PRs, and a
 * "closes #N" pointing at another PR is a dependency note, not a statement of intent.
 */
public final class LinkedIssueService {

    /**
     * GitHub's closing keywords, anchored so that a bare {@code #12} mention — which does not close
     * anything — is not mistaken for a statement of the PR's purpose.
     */
    private static final Pattern CLOSING_REFERENCE =
            Pattern.compile(
                    "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b\\s*:?\\s+#(\\d{1,9})");

    public static final int MAX_ISSUES = 3;
    public static final int MAX_ISSUE_NUMBER = 999_999_999;
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_BODY_CHARS = 1500;

    private final GitHubAuthService.TokenResolver tokenResolver;
    private final GitHubApiClient client;
    private final ObjectMapper mapper;

    public LinkedIssueService() {
        this(
                new GitHubAuthService.ProcessTokenResolver(),
                GitHubApiClient.http(),
                new ObjectMapper());
    }

    LinkedIssueService(
            GitHubAuthService.TokenResolver tokenResolver,
            GitHubApiClient client,
            ObjectMapper mapper) {
        this.tokenResolver = Objects.requireNonNull(tokenResolver);
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public LinkedIssueResult linkedIssues(Params params) {
        if (!PromptContext.validRepo(params.owner(), params.repo())) {
            return LinkedIssueResult.failure(GitHubFailure.INVALID_REQUEST);
        }
        Set<Integer> numbers = referencedIssueNumbers(params.prBody(), params.commitIssueNumbers());
        if (numbers.isEmpty()) {
            return LinkedIssueResult.none();
        }
        GitHubSession session = GitHubSession.open(tokenResolver, params.githubBaseUrl());
        if (!session.isOpen()) {
            return LinkedIssueResult.failure(session.failure());
        }

        List<String> sections = new ArrayList<>();
        int resolved = 0;
        for (int number : numbers) {
            GitHubResponse response =
                    client.get(
                            session.apiBaseUrl(),
                            session.token(),
                            "/repos/" + params.owner() + "/" + params.repo() + "/issues/" + number);
            if (!response.isSuccess()) continue;
            String section = section(number, response.body());
            if (section != null) {
                sections.add(section);
                resolved++;
            }
        }
        return sections.isEmpty()
                ? LinkedIssueResult.none()
                : LinkedIssueResult.success(resolved, String.join("\n\n", sections));
    }

    /** Extracts closing references in document order, capped at {@link #MAX_ISSUES}. */
    static Set<Integer> referencedIssueNumbers(String prBody) {
        Set<Integer> numbers = new LinkedHashSet<>();
        if (prBody == null || prBody.isBlank()) return numbers;
        Matcher matcher = CLOSING_REFERENCE.matcher(prBody);
        while (matcher.find() && numbers.size() < MAX_ISSUES) {
            int number = Integer.parseInt(matcher.group(1));
            if (number > 0) numbers.add(number);
        }
        return numbers;
    }

    /**
     * Merges commit references after PR-body references, preserving order and the global issue cap.
     */
    static Set<Integer> referencedIssueNumbers(String prBody, List<Integer> commitIssueNumbers) {
        Set<Integer> numbers = referencedIssueNumbers(prBody);
        for (Integer number : commitIssueNumbers) {
            if (numbers.size() >= MAX_ISSUES) break;
            if (number != null && number > 0 && number <= MAX_ISSUE_NUMBER) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    /** Renders one issue, or null when the payload is a pull request or is unusable. */
    private String section(int number, String body) {
        try {
            JsonNode issue = mapper.readTree(body);
            if (issue.has("pull_request")) return null;
            String title = PromptContext.oneLine(issue.path("title").asText(""), MAX_TITLE_CHARS);
            if (title.isEmpty()) return null;
            StringBuilder section =
                    new StringBuilder("#").append(number).append(": ").append(title);
            String state = issue.path("state").asText("");
            if (!state.isEmpty()) {
                section.append(" (").append(state).append(")");
            }
            String labels = labels(issue.path("labels"));
            if (!labels.isEmpty()) {
                section.append("\nLabels: ").append(labels);
            }
            String issueBody = PromptContext.bounded(issue.path("body").asText(""), MAX_BODY_CHARS);
            if (!issueBody.isEmpty()) {
                section.append("\n").append(issueBody);
            }
            return section.toString();
        } catch (IOException exception) {
            return null;
        }
    }

    private static String labels(JsonNode labels) {
        if (!labels.isArray()) return "";
        List<String> names = new ArrayList<>();
        for (JsonNode label : labels) {
            String name = PromptContext.oneLine(label.path("name").asText(""), 60);
            if (!name.isEmpty()) names.add(name);
        }
        return String.join(", ", names);
    }

    /**
     * The PR body and validated commit issue numbers are supplied by the caller, which has already
     * fetched the PR detail and commits.
     */
    public record Params(
            String githubBaseUrl,
            String owner,
            String repo,
            String prBody,
            List<Integer> commitIssueNumbers) {
        public Params {
            commitIssueNumbers = List.copyOf(commitIssueNumbers);
        }
    }
}
