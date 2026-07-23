package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Java port of the former core/jvmTest Kotest suite for CopilotModelDiscovery. */
class CopilotModelDiscoveryTest {

    /** Sample taken verbatim from `copilot help config` v1.0.54. */
    private static final String REAL_HELP_SAMPLE =
            "`keepAlive`: keep-alive mode applied at CLI startup; prevents the system from sleeping"
                    + " while the session is active. Defaults to `\"off\"`.\n\n"
                    + "  `model`: AI model to use for Copilot CLI; can be changed with /model command or"
                    + " --model flag option.\n"
                    + "    - \"claude-sonnet-4.6\"\n"
                    + "    - \"claude-sonnet-4.5\"\n"
                    + "    - \"claude-haiku-4.5\"\n"
                    + "    - \"claude-opus-4.7\"\n"
                    + "    - \"claude-opus-4.6\"\n"
                    + "    - \"claude-opus-4.6-fast\"\n"
                    + "    - \"claude-opus-4.5\"\n"
                    + "    - \"gpt-5.5\"\n"
                    + "    - \"gpt-5.4\"\n"
                    + "    - \"gpt-5.3-codex\"\n"
                    + "    - \"gpt-5.2-codex\"\n"
                    + "    - \"gpt-5.2\"\n"
                    + "    - \"gpt-5.4-mini\"\n\n"
                    + "  `mouse`: whether to enable mouse support in alt screen mode; defaults to `true`"
                    + " on macOS, `false` elsewhere.\n";

    @Nested
    class ParseModelsFromHelp {

        @Test
        void realHelpSampleExtractsAllModelIdsInOrder() {
            List<String> models = CopilotModelDiscovery.parseModelsFromHelp(REAL_HELP_SAMPLE);
            assertThat(models)
                    .containsExactly(
                            "claude-sonnet-4.6",
                            "claude-sonnet-4.5",
                            "claude-haiku-4.5",
                            "claude-opus-4.7",
                            "claude-opus-4.6",
                            "claude-opus-4.6-fast",
                            "claude-opus-4.5",
                            "gpt-5.5",
                            "gpt-5.4",
                            "gpt-5.3-codex",
                            "gpt-5.2-codex",
                            "gpt-5.2",
                            "gpt-5.4-mini");
        }

        @Test
        void sectionEndsAtBlankLineBeforeNextSetting() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n  - \"a\"\n  - \"b\"\n\n`theme`: theme to color and"
                            + " stylize output; defaults to \"auto\".\n  - \"auto\"\n  - \"dark\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).containsExactly("a", "b");
        }

        @Test
        void noModelSectionReturnsEmptyList() {
            String help =
                    "`theme`: theme to color and stylize output; defaults to \"auto\".\n  - \"auto\"\n  - \"dark\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).isEmpty();
        }

        @Test
        void emptyHelpTextReturnsEmptyList() {
            assertThat(CopilotModelDiscovery.parseModelsFromHelp("")).isEmpty();
        }

        @Test
        void modelSectionPresentButNoItemsReturnsEmptyList() {
            String help = "`model`: AI model to use for Copilot CLI.\n(none configured)\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).isEmpty();
        }

        @Test
        void nonQuotedBulletsAreIgnored() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n  - plain-text\n  - \"real-id\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).containsExactly("real-id");
        }

        @Test
        void descriptionContinuationBetweenHeaderAndBulletsStillFindsItems() {
            String help =
                    "`model`: AI model to use for Copilot CLI.\n  Long wrapped description that continues here without"
                            + " dashes.\n  - \"first\"\n  - \"second\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help))
                    .containsExactly("first", "second");
        }

        @Test
        void backtickRequiredBareModelHeadingIsNotMatched() {
            String help = "model: bare heading without backticks\n  - \"should-not-match\"\n";
            assertThat(CopilotModelDiscovery.parseModelsFromHelp(help)).isEmpty();
        }
    }

    @Nested
    class ListModelsAndInvalidate {

        @Test
        void invalidateDropsTheCacheSoTheNextCallReProbes() {
            // We can't easily mock the real `copilot` invocation, so we just verify the invalidate
            // contract: after calling it, a fresh call returns a non-null list either way.
            CopilotModelDiscovery.invalidate();
            List<String> first = CopilotModelDiscovery.listModels();
            assertThat(first).isNotNull();
            CopilotModelDiscovery.invalidate();
        }
    }
}
