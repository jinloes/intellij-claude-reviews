package com.jinloes.prpilot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewSupervisorPromptsTest {
    @Nested
    class ParseDirectives {
        @Test
        void ignoresInventedGapIdsAndKeepsKnownSelections() throws Exception {
            CoverageGap known = new CoverageGap("G001", "H-known", "schema.sql", 10, "schema", 100);

            List<FollowUpDirective> directives =
                    ReviewSupervisorPrompts.parseDirectives(
                            "{\"selectedGapIds\":[\"G-invented\",\"G001\"]}", List.of(known));

            assertThat(directives)
                    .singleElement()
                    .satisfies(
                            directive -> {
                                assertThat(directive.gapId()).isEqualTo("G001");
                                assertThat(directive.targetId()).isEqualTo("H-known");
                            });
        }
    }
}
