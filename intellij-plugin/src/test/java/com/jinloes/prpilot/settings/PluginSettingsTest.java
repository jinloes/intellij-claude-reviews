package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.RepoGuidelinesReader;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {

    @Test
    void reviewProviderDefaultsToClaude() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void setReviewProviderRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewProvider(ReviewProvider.COPILOT);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.COPILOT);
        s.setReviewProvider(ReviewProvider.CLAUDE);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void setReviewProviderNullDefaultsToClaude() {
        PluginSettings s = new PluginSettings();
        s.setReviewProvider(ReviewProvider.COPILOT);
        s.setReviewProvider(null);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void loadStateWithUnknownProviderIdFallsBackToClaude() {
        PluginSettings s = new PluginSettings();
        PluginSettings.State state = new PluginSettings.State();
        state.reviewProvider = "gemini-cli";
        s.loadState(state);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.CLAUDE);
    }

    @Test
    void loadStateWithCopilotIdResolvesCopilot() {
        PluginSettings s = new PluginSettings();
        PluginSettings.State state = new PluginSettings.State();
        state.reviewProvider = "copilot";
        s.loadState(state);
        assertThat(s.getReviewProvider()).isEqualTo(ReviewProvider.COPILOT);
    }

    @Test
    void reviewModelCopilotDefaultsToSonnet() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewModelCopilot()).isEqualTo("claude-sonnet-4.6");
    }

    @Test
    void reviewModelCopilotCanBeBlankedToFallBackToCliDefault() {
        PluginSettings s = new PluginSettings();
        s.setReviewModelCopilot("");
        assertThat(s.getReviewModelCopilot()).isEmpty();
    }

    @Test
    void reviewModelCopilotRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewModelCopilot("gpt-5.4");
        assertThat(s.getReviewModelCopilot()).isEqualTo("gpt-5.4");
        s.setReviewModelCopilot(null);
        assertThat(s.getReviewModelCopilot()).isEmpty();
    }

    @Test
    void reviewEffortDefaultsToHigh() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewEffort()).isEqualTo("high");
    }

    @Test
    void reviewEffortRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewEffort("xhigh");
        assertThat(s.getReviewEffort()).isEqualTo("xhigh");
    }

    @Test
    void reviewEffortBlankFallsBackToHigh() {
        PluginSettings s = new PluginSettings();
        s.setReviewEffort("");
        assertThat(s.getReviewEffort()).isEqualTo("high");
    }

    @Test
    void reviewEffortNullFallsBackToHigh() {
        PluginSettings s = new PluginSettings();
        s.setReviewEffort(null);
        assertThat(s.getReviewEffort()).isEqualTo("high");
    }

    @Test
    void reviewGuidanceGlobsDefaultsToSharedDefaults() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewGuidanceGlobs())
                .isEqualTo(RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS);
    }

    @Test
    void reviewGuidanceGlobsParsesNonBlankLines() {
        PluginSettings s = new PluginSettings();
        s.setReviewGuidanceGlobs("**/style.md\n\n  .linkedin/ai-agent/*.md  \n");
        assertThat(s.getReviewGuidanceGlobs())
                .containsExactly("**/style.md", ".linkedin/ai-agent/*.md");
    }

    @Test
    void reviewGuidanceGlobsBlankFallsBackToDefaults() {
        PluginSettings s = new PluginSettings();
        s.setReviewGuidanceGlobs("   \n  ");
        assertThat(s.getReviewGuidanceGlobs())
                .isEqualTo(RepoGuidelinesReader.DEFAULT_GUIDANCE_GLOBS);
    }

    @Test
    void reviewSelfCritiqueDefaultsToFalse() {
        PluginSettings s = new PluginSettings();
        assertThat(s.isReviewSelfCritique()).isFalse();
    }

    @Test
    void reviewSelfCritiqueRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setReviewSelfCritique(true);
        assertThat(s.isReviewSelfCritique()).isTrue();
    }

    @Test
    void copilotInheritMcpDefaultsToFalse() {
        PluginSettings s = new PluginSettings();
        assertThat(s.isCopilotInheritMcp()).isFalse();
    }

    @Test
    void copilotInheritMcpRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setCopilotInheritMcp(false);
        assertThat(s.isCopilotInheritMcp()).isFalse();
        s.setCopilotInheritMcp(true);
        assertThat(s.isCopilotInheritMcp()).isTrue();
    }

    @Test
    void copilotConfigDirDefaultsToEmpty() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getCopilotConfigDir()).isEmpty();
    }

    @Test
    void copilotConfigDirTrimsAndRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setCopilotConfigDir("  /custom/.copilot  ");
        assertThat(s.getCopilotConfigDir()).isEqualTo("/custom/.copilot");
        s.setCopilotConfigDir(null);
        assertThat(s.getCopilotConfigDir()).isEmpty();
    }

    @Test
    void copilotAutoEnableMcpOnReviewDefaultsToFalse() {
        PluginSettings s = new PluginSettings();
        assertThat(s.isCopilotAutoEnableMcpOnReview()).isFalse();
    }

    @Test
    void copilotAutoEnableMcpOnReviewRoundTrips() {
        PluginSettings s = new PluginSettings();
        s.setCopilotAutoEnableMcpOnReview(true);
        assertThat(s.isCopilotAutoEnableMcpOnReview()).isTrue();
        s.setCopilotAutoEnableMcpOnReview(false);
        assertThat(s.isCopilotAutoEnableMcpOnReview()).isFalse();
    }

    @Test
    void reviewFocusAreasDefaultsToEmptyAndTrims() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewFocusAreas()).isEmpty();

        s.setReviewFocusAreas("  security, performance  ");
        assertThat(s.getReviewFocusAreas()).isEqualTo("security, performance");

        s.setReviewFocusAreas(null);
        assertThat(s.getReviewFocusAreas()).isEmpty();
    }

    @Test
    void reviewCustomInstructionsDefaultsToEmptyAndTrims() {
        PluginSettings s = new PluginSettings();
        assertThat(s.getReviewCustomInstructions()).isEmpty();

        s.setReviewCustomInstructions("  Prefer regression tests.  ");
        assertThat(s.getReviewCustomInstructions()).isEqualTo("Prefer regression tests.");

        s.setReviewCustomInstructions(null);
        assertThat(s.getReviewCustomInstructions()).isEmpty();
    }

    @Test
    void reviewPromptDefaultsHandleNullPersistedValues() {
        PluginSettings s = new PluginSettings();
        PluginSettings.State state = new PluginSettings.State();
        state.reviewFocusAreas = null;
        state.reviewCustomInstructions = null;
        s.loadState(state);

        assertThat(s.getReviewFocusAreas()).isEmpty();
        assertThat(s.getReviewCustomInstructions()).isEmpty();
    }

    @Test
    void activeModelReflectsSelectedProvider() {
        PluginSettings s = new PluginSettings();
        s.setReviewModel("claude-opus-4-7");
        s.setReviewModelCopilot("gpt-5.4");

        s.setReviewProvider(ReviewProvider.CLAUDE);
        assertThat(s.getActiveReviewModel()).isEqualTo("claude-opus-4-7");

        s.setReviewProvider(ReviewProvider.COPILOT);
        assertThat(s.getActiveReviewModel()).isEqualTo("gpt-5.4");
    }
}
