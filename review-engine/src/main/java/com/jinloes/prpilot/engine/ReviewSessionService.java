package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.CiAnnotation;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.review.RepoGuidelinesReader;
import com.jinloes.prpilot.review.ReviewOutcomeLog;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * Default {@link ReviewEngineApi} implementation: dispatches to the Claude or Copilot provider
 * service and adapts transport-neutral request records into {@link PRReviewRequest}.
 *
 * <p>Lives in {@code review-engine} rather than the sidecar so every client can reach it the same
 * way — the sidecar wraps it in JSON-RPC for VS Code, while an in-process client (IntelliJ, a
 * future CLI) can call it directly. It has no transport, Spring, or IDE dependencies.
 *
 * <p>Holds the currently-active provider instance so {@link #cancel()} can reach it; only one
 * review or chat request is ever in flight per instance, matching IntelliJ's "only one active
 * process at any time" behavior.
 */
public class ReviewSessionService implements ReviewEngineApi {

    private final AtomicReference<ClaudeService> activeClaude = new AtomicReference<>();
    private final AtomicReference<CopilotService> activeCopilot = new AtomicReference<>();
    private final ReviewOutcomeLog outcomeLog;
    private final GitWorktreeService worktreeService = new GitWorktreeService();

    public ReviewSessionService() {
        this(new ReviewOutcomeLog());
    }

    /** Test seam so outcome logging can be pointed at a temp file. */
    public ReviewSessionService(ReviewOutcomeLog outcomeLog) {
        this.outcomeLog = outcomeLog;
    }

    private static PullRequest toPullRequest(PrParams p) {
        return new PullRequest(
                p.title(),
                p.htmlUrl(),
                p.owner(),
                p.repo(),
                p.number(),
                p.body(),
                p.author(),
                p.createdAt(),
                p.isDraft());
    }

    @Override
    public ReviewResult generate(
            GenerateReviewParams params,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        PRReviewRequest request =
                PRReviewRequest.builder(toPullRequest(params.pr()), params.diff())
                        .priorReview(params.priorReview())
                        .existingReviews(params.existingReviews())
                        .repoGuidelines(params.repoGuidelines())
                        .focusAreas(params.focusAreas())
                        .customInstructions(params.customInstructions())
                        .ciStatus(params.ciStatus())
                        .commits(params.commits())
                        .linkedIssue(params.linkedIssue())
                        .repoProfile(params.repoProfile())
                        .ciAnnotations(toCiAnnotations(params.ciAnnotations()))
                        .build();
        if ("copilot".equals(params.provider())) {
            CopilotService service = new CopilotService(params.projectDir());
            activeCopilot.set(service);
            try {
                return service.reviewPR(
                        request,
                        params.model(),
                        params.effort(),
                        onStatus,
                        onChunk,
                        params.inheritMcp(),
                        params.configDir(),
                        params.selfCritique());
            } finally {
                activeCopilot.compareAndSet(service, null);
            }
        }
        ClaudeService service = new ClaudeService(params.projectDir());
        activeClaude.set(service);
        try {
            return service.reviewPR(
                    request, params.model(), params.selfCritique(), onStatus, onChunk);
        } finally {
            activeClaude.compareAndSet(service, null);
        }
    }

    @Override
    public ChatResult chat(ChatParams params, Consumer<String> onChunk)
            throws IOException, InterruptedException {
        boolean focused = params.rawPrompt() != null;
        List<ChatMessage> history =
                params.history() == null
                        ? List.of()
                        : params.history().stream()
                                .map(
                                        m ->
                                                new ChatMessage(
                                                        "USER".equalsIgnoreCase(m.role())
                                                                ? ChatMessage.Role.USER
                                                                : ChatMessage.Role.ASSISTANT,
                                                        m.content()))
                                .toList();
        if ("copilot".equals(params.provider())) {
            CopilotService service = new CopilotService(params.projectDir());
            activeCopilot.set(service);
            try {
                String content =
                        focused
                                ? service.chatWithPrompt(
                                        params.rawPrompt(),
                                        params.effort(),
                                        onChunk,
                                        params.inheritMcp(),
                                        params.configDir())
                                : service.chat(
                                        params.prContext(),
                                        history,
                                        params.userMessage(),
                                        params.effort(),
                                        onChunk,
                                        params.inheritMcp(),
                                        params.configDir());
                return new ChatResult(content);
            } finally {
                activeCopilot.compareAndSet(service, null);
            }
        }
        ClaudeService service = new ClaudeService(params.projectDir());
        activeClaude.set(service);
        try {
            String content =
                    focused
                            ? service.chatWithPrompt(params.rawPrompt(), onChunk)
                            : service.chat(
                                    params.prContext(), history, params.userMessage(), onChunk);
            return new ChatResult(content);
        } finally {
            activeClaude.compareAndSet(service, null);
        }
    }

    @Override
    public void cancel() {
        ClaudeService claude = activeClaude.get();
        if (claude != null) claude.cancelCurrentRequest();
        CopilotService copilot = activeCopilot.get();
        if (copilot != null) copilot.cancelCurrentRequest();
    }

    @Override
    public RecordOutcomeResult recordOutcome(RecordOutcomeParams params) {
        if (params == null) return new RecordOutcomeResult(0);
        ReviewOutcomeLog.Metadata metadata =
                new ReviewOutcomeLog.Metadata(
                        ClaudeService.PROMPT_VERSION, params.provider(), params.model());
        int recorded =
                outcomeLog.record(
                        toLineComments(params.generated()),
                        toLineComments(params.submitted()),
                        metadata);
        return new RecordOutcomeResult(recorded);
    }

    private static List<LineComment> toLineComments(List<OutcomeCommentParam> params) {
        if (params == null) return List.of();
        List<LineComment> comments = new ArrayList<>(params.size());
        for (OutcomeCommentParam param : params) {
            if (param == null) continue;
            LineComment comment =
                    new LineComment(param.file(), param.line(), param.type(), param.body());
            comment.setSeverity(param.severity());
            comment.setConfidence(param.confidence());
            comments.add(comment);
        }
        return comments;
    }

    private static List<CiAnnotation> toCiAnnotations(List<CiAnnotationParam> params) {
        if (params == null) return List.of();
        List<CiAnnotation> annotations = new ArrayList<>(params.size());
        for (CiAnnotationParam param : params) {
            if (param == null) continue;
            annotations.add(
                    new CiAnnotation(param.file(), param.line(), param.level(), param.message()));
        }
        return annotations;
    }

    @Override
    public GuidelinesResult readGuidelines(ReadGuidelinesParams params) {
        if (params == null || StringUtils.isBlank(params.projectDir())) {
            return new GuidelinesResult("");
        }
        // A null/empty glob list means "engine defaults"; RepoGuidelinesReader.read already applies
        // that fallback, so no host needs its own copy of the default file list.
        return new GuidelinesResult(
                RepoGuidelinesReader.read(new File(params.projectDir()), params.globs()));
    }

    @Override
    public GitRootResult findGitRoot(String startDir) {
        if (StringUtils.isBlank(startDir)) return new GitRootResult("");
        try {
            File root = worktreeService.findGitRoot(new File(startDir));
            return new GitRootResult(root == null ? "" : root.getAbsolutePath());
        } catch (RuntimeException e) {
            // An uncanonicalizable path is "not a repository" from the caller's perspective; the
            // only consumer decides between worktree and plain checkout, and neither wants a throw.
            return new GitRootResult("");
        }
    }

    @Override
    public WorktreeResult createWorktree(CreateWorktreeParams params) {
        if (params == null
                || StringUtils.isBlank(params.gitRoot())
                || StringUtils.isBlank(params.branch())) {
            return new WorktreeResult("skipped", "", "No branch to check out.");
        }
        File repoDir = new File(params.gitRoot());
        File worktreeDir = worktreeService.newWorktreePath(params.prNumber());
        try {
            if (StringUtils.isNotBlank(params.forkCloneUrl())) {
                worktreeService.createWorktreeFromFork(
                        repoDir,
                        params.forkCloneUrl(),
                        params.branch(),
                        params.headSha(),
                        worktreeDir);
            } else {
                worktreeService.createWorktree(
                        repoDir, params.branch(), params.headSha(), worktreeDir);
            }
            return new WorktreeResult("created", worktreeDir.getAbsolutePath(), "");
        } catch (IOException e) {
            // Domain result, not an exception: callers fall back to the user's own checkout, so a
            // failed worktree degrades review accuracy rather than failing the review.
            return new WorktreeResult("failed", "", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public WorktreeRemovalResult removeWorktree(RemoveWorktreeParams params) {
        if (params == null
                || StringUtils.isBlank(params.gitRoot())
                || StringUtils.isBlank(params.worktreeDir())) {
            return new WorktreeRemovalResult(false);
        }
        worktreeService.removeWorktree(new File(params.gitRoot()), new File(params.worktreeDir()));
        return new WorktreeRemovalResult(true);
    }
}
