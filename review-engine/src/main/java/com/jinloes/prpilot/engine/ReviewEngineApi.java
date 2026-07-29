package com.jinloes.prpilot.engine;

import com.jinloes.prpilot.model.ReviewResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The complete host-neutral AI review capability surface of this engine.
 *
 * <p>Counterpart to {@code GitHubEngineApi} in the {@code github-engine} module; see that type's
 * javadoc for the parity-boundary rules and the coverage enforcement, which apply identically here.
 *
 * <p>The request records live on this interface rather than on an implementation so any client —
 * the sidecar, IntelliJ in-process, or a future CLI — can build a request without depending on a
 * particular host or transport.
 *
 * <p>Do not add overloaded methods — {@link #RPC_METHODS} is keyed by bare method name.
 */
public interface ReviewEngineApi {

    /** Java method name to JSON-RPC wire method name. */
    Map<String, String> RPC_METHODS =
            Map.ofEntries(
                    Map.entry("generate", "reviews/generate"),
                    Map.entry("chat", "reviews/chat"),
                    Map.entry("cancel", "reviews/cancel"),
                    Map.entry("recordOutcome", "reviews/recordOutcome"));

    /** Pull-request identity and metadata needed to build a review prompt. */
    record PrParams(
            String title,
            String htmlUrl,
            String owner,
            String repo,
            int number,
            String body,
            String author,
            String createdAt,
            boolean isDraft) {}

    /** A file-anchored finding CI already reported, used to drop review comments restating it. */
    record CiAnnotationParam(String file, int line, String level, String message) {}

    /**
     * Everything needed for one full review generation.
     *
     * <p>{@code ciStatus}, {@code commits}, {@code linkedIssue}, and {@code repoProfile} are the
     * pre-rendered outputs of the corresponding {@code GitHubEngineApi} context capabilities. They
     * are rendered engine-side rather than per host so no host has to reimplement the formatting,
     * and all four are optional — omitting one drops its prompt section and nothing else.
     *
     * <p>{@code ciAnnotations} is the structured form of {@code ciStatus}: same data, but
     * machine-comparable, so duplicate findings can be dropped deterministically rather than by
     * asking the model nicely.
     */
    record GenerateReviewParams(
            String provider,
            String projectDir,
            String model,
            String effort,
            boolean inheritMcp,
            String configDir,
            boolean selfCritique,
            PrParams pr,
            String diff,
            String priorReview,
            String existingReviews,
            String repoGuidelines,
            String focusAreas,
            String customInstructions,
            String ciStatus,
            String commits,
            String linkedIssue,
            String repoProfile,
            List<CiAnnotationParam> ciAnnotations) {}

    /** One prior conversation turn. */
    record ChatMessageParam(String role, String content) {}

    /**
     * Chat request. Exactly one of {@code rawPrompt} (focused question, already fully built) or
     * {@code userMessage} (ordinary chat, wrapped with persona and context) is expected.
     */
    record ChatParams(
            String provider,
            String projectDir,
            String effort,
            boolean inheritMcp,
            String configDir,
            String prContext,
            List<ChatMessageParam> history,
            String userMessage,
            String rawPrompt) {}

    /** Result of {@link #chat}: the complete assistant response text. */
    record ChatResult(String content) {}

    /**
     * One comment, in either the generated or the submitted set. Carries only what classification
     * and segmentation need — the outcome log persists no comment text.
     */
    record OutcomeCommentParam(
            String file, int line, String type, String body, String severity, String confidence) {}

    /**
     * A completed review, for outcome logging.
     *
     * <p>Deliberately <b>stateless</b>: the caller supplies both sets rather than the engine
     * remembering the generated review between calls. IntelliJ never routes generation through
     * {@link ReviewSessionService} (it calls the provider services in-process), so an engine-held
     * snapshot would populate for VS Code only and silently record nothing on the host with most of
     * the users.
     *
     * <p>{@code promptVersion} is filled in engine-side and is not a parameter — it identifies the
     * prompt this engine build produces, which a host cannot know.
     */
    record RecordOutcomeParams(
            String provider,
            String model,
            List<OutcomeCommentParam> generated,
            List<OutcomeCommentParam> submitted) {}

    /** Result of {@link #recordOutcome}: how many outcome records were written. */
    record RecordOutcomeResult(int recorded) {}

    /**
     * Generates a review. Blocks until the provider CLI completes; callers own threading.
     *
     * @param onStatus receives human-readable progress labels
     * @param onChunk receives streaming output as {@code (kind, text)} where kind is {@code text}
     *     or {@code thinking}
     */
    ReviewResult generate(
            GenerateReviewParams params,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk)
            throws IOException, InterruptedException;

    /** Sends a chat message. Blocks until the provider CLI completes; callers own threading. */
    ChatResult chat(ChatParams params, Consumer<String> onChunk)
            throws IOException, InterruptedException;

    /** Cancels whichever provider currently has an active request; a no-op if none is active. */
    void cancel();

    /**
     * Records what the reviewer did with each generated comment. Instrumentation only — never
     * throws, and a failure to write must not affect the submission that triggered it.
     */
    RecordOutcomeResult recordOutcome(RecordOutcomeParams params);
}
