package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.RepoGuidelinesReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "ClaudeReviewSettings", storages = @Storage("claudeReviews.xml"))
public class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    public static class State {
        /** Base URL of GitHub instance, e.g. https://github.com or https://github.mycompany.com */
        public String githubBaseUrl = "https://github.com";

        /** Whether background PR notifications are enabled. */
        public boolean notificationsEnabled = false;

        /** Notify when a review is requested from the current user. */
        public boolean notifyReviewRequested = true;

        /** Notify when a new PR is opened on a starred repository. */
        public boolean notifyStarredRepos = false;

        /** Poll interval in minutes. */
        public int notificationPollMinutes = 5;

        /** Model ID passed to the claude CLI for reviews. Empty string uses the CLI default. */
        public String reviewModel = "";

        /**
         * Model ID passed to the copilot CLI for reviews. Defaults to {@code claude-sonnet-4.6}:
         * strong at structured JSON output and code reasoning at sub-Opus latency. Empty string
         * uses the CLI's default routing.
         */
        public String reviewModelCopilot = "claude-sonnet-4.6";

        /** Backend CLI used to generate reviews and chat replies. */
        public String reviewProvider = ReviewProvider.CLAUDE.getId();

        /**
         * Reasoning effort passed to {@code copilot --reasoning-effort}. One of "none", "low",
         * "medium", "high", "xhigh", "max". Defaults to "high" for deeper review reasoning. Only
         * applied when {@code reviewProvider} is COPILOT.
         */
        public String reviewEffort = "high";

        /**
         * When true, the Copilot review/chat session inherits MCP servers from the user's trusted
         * Copilot config ({@code ~/.copilot/mcp-config.json}, or {@code copilotConfigDir}). The
         * SDK's on-disk config discovery is never enabled, so a PR's repo-local {@code .mcp.json}
         * is deliberately ignored. Only applied when {@code reviewProvider} is COPILOT.
         */
        public boolean copilotInheritMcp = false;

        /**
         * When true, Copilot review generation always enables MCP even if the general inheritance
         * toggle is off. This is review-only; chat still follows {@code copilotInheritMcp}.
         */
        public boolean copilotAutoEnableMcpOnReview = false;

        /**
         * Optional override of the Copilot config directory used to discover MCP servers. Empty
         * uses the CLI default ({@code ~/.copilot}). Only applied when {@code reviewProvider} is
         * COPILOT.
         */
        public String copilotConfigDir = "";

        /** Default focus areas the reviewer should prioritize. Sent as prompt steering context. */
        public String reviewFocusAreas = "";

        /** Default extra instructions appended to every review prompt. */
        public String reviewCustomInstructions = "";

        /**
         * Newline-separated list of guidance files (literal relative paths or globs like {@code
         * **}{@code /style.md}) scanned from the review working directory and folded into {@code
         * <repo_guidelines>}. Blank falls back to {@link
         * RepoGuidelinesReader#DEFAULT_GUIDANCE_GLOBS}.
         */
        public String reviewGuidanceGlobs = "";

        /**
         * When true, review generation runs a second self-critique pass that re-validates each
         * finding against the diff and the same context the first pass saw, dropping misattributed,
         * unsupported, and CI-duplicated ones. On by default: a misattributed comment costs a
         * reviewer more than the extra latency does, and precision is what makes the review worth
         * reading. Turn it off to roughly halve review latency.
         */
        public boolean reviewSelfCritique = true;
    }

    private State myState = new State();

    public static PluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public String getGithubBaseUrl() {
        return myState.githubBaseUrl != null ? myState.githubBaseUrl : "https://github.com";
    }

    public void setGithubBaseUrl(String url) {
        myState.githubBaseUrl = GithubBaseUrlValidator.normalize(url);
    }

    public boolean isNotificationsEnabled() {
        return myState.notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean v) {
        myState.notificationsEnabled = v;
    }

    public boolean isNotifyReviewRequested() {
        return myState.notifyReviewRequested;
    }

    public void setNotifyReviewRequested(boolean v) {
        myState.notifyReviewRequested = v;
    }

    public boolean isNotifyStarredRepos() {
        return myState.notifyStarredRepos;
    }

    public void setNotifyStarredRepos(boolean v) {
        myState.notifyStarredRepos = v;
    }

    public int getNotificationPollMinutes() {
        return myState.notificationPollMinutes > 0 ? myState.notificationPollMinutes : 5;
    }

    public void setNotificationPollMinutes(int v) {
        myState.notificationPollMinutes = Math.max(1, v);
    }

    public String getReviewModel() {
        return myState.reviewModel != null ? myState.reviewModel : "";
    }

    public void setReviewModel(String model) {
        myState.reviewModel = model != null ? model : "";
    }

    public String getReviewModelCopilot() {
        return myState.reviewModelCopilot != null ? myState.reviewModelCopilot : "";
    }

    public void setReviewModelCopilot(String model) {
        myState.reviewModelCopilot = model != null ? model : "";
    }

    /** Returns the model ID for the currently selected provider. */
    public String getActiveReviewModel() {
        return getReviewProvider() == ReviewProvider.COPILOT
                ? getReviewModelCopilot()
                : getReviewModel();
    }

    public ReviewProvider getReviewProvider() {
        return ReviewProvider.fromId(myState.reviewProvider);
    }

    public void setReviewProvider(ReviewProvider provider) {
        myState.reviewProvider =
                provider != null ? provider.getId() : ReviewProvider.CLAUDE.getId();
    }

    public String getReviewEffort() {
        return myState.reviewEffort != null && !myState.reviewEffort.isBlank()
                ? myState.reviewEffort
                : "high";
    }

    public void setReviewEffort(String effort) {
        myState.reviewEffort = effort != null ? effort : "high";
    }

    public boolean isCopilotInheritMcp() {
        return myState.copilotInheritMcp;
    }

    public void setCopilotInheritMcp(boolean inherit) {
        myState.copilotInheritMcp = inherit;
    }

    public boolean isCopilotAutoEnableMcpOnReview() {
        return myState.copilotAutoEnableMcpOnReview;
    }

    public void setCopilotAutoEnableMcpOnReview(boolean enabled) {
        myState.copilotAutoEnableMcpOnReview = enabled;
    }

    public String getCopilotConfigDir() {
        return myState.copilotConfigDir != null ? myState.copilotConfigDir : "";
    }

    public void setCopilotConfigDir(String dir) {
        myState.copilotConfigDir = dir != null ? dir.trim() : "";
    }

    public String getReviewFocusAreas() {
        return myState.reviewFocusAreas != null ? myState.reviewFocusAreas : "";
    }

    public void setReviewFocusAreas(String value) {
        myState.reviewFocusAreas = value != null ? value.trim() : "";
    }

    public String getReviewCustomInstructions() {
        return myState.reviewCustomInstructions != null ? myState.reviewCustomInstructions : "";
    }

    public void setReviewCustomInstructions(String value) {
        myState.reviewCustomInstructions = value != null ? value.trim() : "";
    }

    /** Raw newline-separated guidance-globs text, as edited in settings (may be blank). */
    public String getReviewGuidanceGlobsRaw() {
        return myState.reviewGuidanceGlobs != null ? myState.reviewGuidanceGlobs : "";
    }

    public void setReviewGuidanceGlobs(String value) {
        myState.reviewGuidanceGlobs = value != null ? value.trim() : "";
    }

    /**
     * Parsed guidance globs (one per non-blank line), falling back to {@link
     * RepoGuidelinesReader#DEFAULT_GUIDANCE_GLOBS} when nothing is configured.
     */
    public java.util.List<String> getReviewGuidanceGlobs() {
        String raw = getReviewGuidanceGlobsRaw();
        if (raw.isBlank()) {
            return RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS;
        }
        java.util.List<String> globs =
                raw.lines()
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .collect(java.util.stream.Collectors.toList());
        return globs.isEmpty() ? RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS : globs;
    }

    public boolean isReviewSelfCritique() {
        return myState.reviewSelfCritique;
    }

    public void setReviewSelfCritique(boolean value) {
        myState.reviewSelfCritique = value;
    }
}
