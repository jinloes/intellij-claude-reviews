package com.jinloes.prpilot.sidecar.review;

import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sidecar-side orchestrator that drives the shared {@code review-engine} Claude/Copilot services on
 * behalf of the VS Code extension, over JSON-RPC instead of an in-process Java call (as the
 * IntelliJ plugin uses directly).
 *
 * <p>Holds the currently-active provider instance so {@link #cancel()} can reach it; only one
 * review or chat request is ever in flight per sidecar process, matching the existing IntelliJ
 * behavior ("only one has an active process at any time").
 */
public class ReviewSessionService {

    private final AtomicReference<ClaudeService> activeClaude = new AtomicReference<>();
    private final AtomicReference<CopilotService> activeCopilot = new AtomicReference<>();

    public record PrParams(
            String title,
            String htmlUrl,
            String owner,
            String repo,
            int number,
            String body,
            String author,
            String createdAt,
            boolean isDraft) {}

    public record GenerateReviewParams(
            String provider,
            String projectDir,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            boolean selfCritique,
            PrParams pr,
            String diff,
            String knownPatterns,
            String priorReview,
            String existingReviews,
            String repoGuidelines,
            String focusAreas,
            String customInstructions) {}

    public record ChatMessageParam(String role, String content) {}

    public record ChatParams(
            String provider,
            String projectDir,
            String effort,
            boolean inheritMcp,
            String configDir,
            String prContext,
            List<ChatMessageParam> history,
            String userMessage,
            String rawPrompt) {}

    /** Result of {@code reviews/chat}: the complete assistant response text. */
    public record ChatResult(String content) {}

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

    public ReviewResult generate(
            GenerateReviewParams params,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException {
        PRReviewRequest request =
                new PRReviewRequest(
                        toPullRequest(params.pr()),
                        params.diff(),
                        params.knownPatterns(),
                        params.priorReview(),
                        params.existingReviews(),
                        params.repoGuidelines(),
                        params.focusAreas(),
                        params.customInstructions());
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

    /** Cancels whichever provider currently has an active request; a no-op if none is active. */
    public void cancel() {
        ClaudeService claude = activeClaude.get();
        if (claude != null) claude.cancelCurrentRequest();
        CopilotService copilot = activeCopilot.get();
        if (copilot != null) copilot.cancelCurrentRequest();
    }
}
