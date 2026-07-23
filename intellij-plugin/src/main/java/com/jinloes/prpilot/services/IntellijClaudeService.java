package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.settings.PluginSettings;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * IntelliJ adapter that fronts both the Claude and Copilot CLI backends. The active provider is
 * read from {@link PluginSettings} on every call so a settings change takes effect immediately.
 *
 * <p>Dispatches all blocking I/O to a pooled thread and marshals callbacks back to the EDT so
 * callers in the UI layer never need to manage threading themselves.
 */
public class IntellijClaudeService {

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class RuntimeSettings {
        private final ReviewProvider provider;
        private final String model;
        private final String effort;
        private final boolean inheritMcp;
        private final boolean forceMcpOnReview;
        private final String configDir;

        private RuntimeSettings(
                ReviewProvider provider,
                String model,
                String effort,
                boolean inheritMcp,
                boolean forceMcpOnReview,
                String configDir) {
            this.provider = provider;
            this.model = model;
            this.effort = effort;
            this.inheritMcp = inheritMcp;
            this.forceMcpOnReview = forceMcpOnReview;
            this.configDir = configDir;
        }
    }

    private final ClaudeService claude;
    private final CopilotService copilot;

    public IntellijClaudeService() {
        this.claude = new ClaudeService();
        this.copilot = new CopilotService();
    }

    public IntellijClaudeService(String projectDir) {
        this.claude = new ClaudeService(projectDir);
        this.copilot = new CopilotService(projectDir);
    }

    public void reviewPR(
            PRReviewRequest request,
            Consumer<String> onStatus,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        reviewPR(request, onStatus, null, onComplete, onError);
    }

    public void reviewPR(
            PRReviewRequest request,
            Consumer<String> onStatus,
            BiConsumer<String, String> onChunk,
            Consumer<ReviewResult> onComplete,
            Consumer<String> onError) {
        RuntimeSettings settings = readRuntimeSettings();
        Consumer<String> wrappedStatus = wrapCallback(onStatus);
        BiConsumer<String, String> wrappedChunk = wrapChunkCallback(onChunk);
        runOnPooledThread(
                settings.provider,
                "generate review",
                "Review interrupted.",
                onError,
                () ->
                        settings.provider == ReviewProvider.COPILOT
                                ? copilot.reviewPR(
                                        request,
                                        settings.model,
                                        settings.effort,
                                        wrappedStatus,
                                        wrappedChunk,
                                        resolveReviewInheritMcp(
                                                settings.inheritMcp, settings.forceMcpOnReview),
                                        settings.configDir)
                                : claude.reviewPR(
                                        request, settings.model, wrappedStatus, wrappedChunk),
                onComplete);
    }

    public void chat(
            String prContext,
            List<ChatMessage> history,
            String userMessage,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        RuntimeSettings settings = readRuntimeSettings();
        Consumer<String> wrappedChunk = wrapCallback(onChunk);
        runOnPooledThread(
                settings.provider,
                "answer chat question",
                "Chat interrupted.",
                onError,
                () ->
                        settings.provider == ReviewProvider.COPILOT
                                ? copilot.chat(
                                        prContext,
                                        history,
                                        userMessage,
                                        settings.effort,
                                        wrappedChunk,
                                        settings.inheritMcp,
                                        settings.configDir)
                                : claude.chat(prContext, history, userMessage, wrappedChunk),
                onDone);
    }

    /**
     * Sends a focused question about a specific code snippet. Builds the prompt with {@link
     * ClaudeService#buildFocusedChatPrompt} so the model receives only the code context and
     * question — no PR metadata or conversation history — matching VS Code's focused-chat path.
     */
    public void chatFocused(
            String focusedContext,
            String question,
            Consumer<String> onChunk,
            Consumer<String> onDone,
            Consumer<String> onError) {
        RuntimeSettings settings = readRuntimeSettings();
        String rawPrompt = ClaudeService.buildFocusedChatPrompt(focusedContext, question);
        Consumer<String> wrappedChunk = wrapCallback(onChunk);
        runOnPooledThread(
                settings.provider,
                "answer chat question",
                "Chat interrupted.",
                onError,
                () ->
                        settings.provider == ReviewProvider.COPILOT
                                ? copilot.chatWithPrompt(
                                        rawPrompt,
                                        settings.effort,
                                        wrappedChunk,
                                        settings.inheritMcp,
                                        settings.configDir)
                                : claude.chatWithPrompt(rawPrompt, wrappedChunk),
                onDone);
    }

    /** Cancels the currently running request on either backend. */
    public void cancelCurrentRequest() {
        // Cancel both — only one has an active process at any time, but reading the provider
        // setting here can race with a settings change so we just send the signal to both.
        claude.cancelCurrentRequest();
        copilot.cancelCurrentRequest();
    }

    private static void invokeLater(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r);
    }

    private static Consumer<String> wrapCallback(Consumer<String> callback) {
        return value -> invokeLater(() -> callback.accept(value));
    }

    private static BiConsumer<String, String> wrapChunkCallback(
            BiConsumer<String, String> callback) {
        return callback == null
                ? null
                : (kind, chunk) -> invokeLater(() -> callback.accept(kind, chunk));
    }

    private static RuntimeSettings readRuntimeSettings() {
        PluginSettings settings = PluginSettings.getInstance();
        return new RuntimeSettings(
                settings.getReviewProvider(),
                settings.getActiveReviewModel(),
                settings.getReviewEffort(),
                settings.isCopilotInheritMcp(),
                settings.isCopilotAutoEnableMcpOnReview(),
                settings.getCopilotConfigDir());
    }

    private static <T> void runOnPooledThread(
            ReviewProvider provider,
            String operation,
            String interruptedMessage,
            Consumer<String> onError,
            CheckedSupplier<T> supplier,
            Consumer<T> onSuccess) {
        runOnPooledThread(
                provider,
                operation,
                interruptedMessage,
                onError,
                () -> {
                    T value = supplier.get();
                    invokeLater(() -> onSuccess.accept(value));
                });
    }

    private static void runOnPooledThread(
            ReviewProvider provider,
            String operation,
            String interruptedMessage,
            Consumer<String> onError,
            CheckedRunnable runnable) {
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                runnable.run();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                invokeLater(() -> onError.accept(interruptedMessage));
                            } catch (Exception e) {
                                invokeLater(
                                        () ->
                                                onError.accept(
                                                        friendlyMessage(provider, e, operation)));
                            }
                        });
    }

    static String friendlyMessage(ReviewProvider provider, Exception e, String operation) {
        return UserFacingErrors.forProvider(provider, e, operation);
    }

    static boolean resolveReviewInheritMcp(boolean inheritMcp, boolean forceMcpOnReview) {
        return inheritMcp || forceMcpOnReview;
    }
}
