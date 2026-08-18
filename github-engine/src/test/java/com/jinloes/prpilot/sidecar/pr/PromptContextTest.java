package com.jinloes.prpilot.sidecar.pr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PromptContextTest {

    @Nested
    class ValidSegment {
        @Test
        void acceptsOrdinaryOwnerAndRepoNames() {
            assertThat(PromptContext.validSegment("acme")).isTrue();
            assertThat(PromptContext.validSegment("my-repo_2.0")).isTrue();
        }

        @Test
        void rejectsAnythingThatCouldEscapeARequestPath() {
            assertThat(PromptContext.validSegment("..")).isFalse();
            assertThat(PromptContext.validSegment("a/b")).isFalse();
            assertThat(PromptContext.validSegment("a?b")).isFalse();
            assertThat(PromptContext.validSegment("a b")).isFalse();
        }

        @Test
        void rejectsNullAndEmpty() {
            assertThat(PromptContext.validSegment(null)).isFalse();
            assertThat(PromptContext.validSegment("")).isFalse();
        }
    }

    @Nested
    class ValidSha {
        @Test
        void acceptsAbbreviatedAndFullHexShas() {
            assertThat(PromptContext.validSha("abc1234")).isTrue();
            assertThat(PromptContext.validSha("A".repeat(40))).isTrue();
            assertThat(PromptContext.validSha("f".repeat(64))).isTrue();
        }

        @Test
        void rejectsNonHexRefsAndOutOfRangeLengths() {
            assertThat(PromptContext.validSha("main")).isFalse();
            assertThat(PromptContext.validSha("abc123")).isFalse();
            assertThat(PromptContext.validSha("f".repeat(65))).isFalse();
            assertThat(PromptContext.validSha(null)).isFalse();
        }
    }

    @Nested
    class OneLine {
        @Test
        void collapsesAllWhitespaceIncludingNewlines() {
            assertThat(PromptContext.oneLine("a\n\tb   c", 50)).isEqualTo("a b c");
        }

        @Test
        void marksTruncationSoTheModelKnowsTextWasCut() {
            assertThat(PromptContext.oneLine("abcdefghij", 4)).isEqualTo("abcd…");
        }

        @Test
        void keepsTextAtExactlyTheLimitIntact() {
            assertThat(PromptContext.oneLine("abcd", 4)).isEqualTo("abcd");
        }

        @Test
        void doesNotSplitASurrogatePairAtTheLimit() {
            assertThat(PromptContext.oneLine("abc😀z", 4)).isEqualTo("abc…");
            assertThat(PromptContext.oneLine("😀z", 1)).isEqualTo("…");
        }

        @Test
        void treatsNullAndBlankAsEmpty() {
            assertThat(PromptContext.oneLine(null, 10)).isEmpty();
            assertThat(PromptContext.oneLine("   \n ", 10)).isEmpty();
        }
    }

    @Nested
    class Bounded {
        @Test
        void preservesInternalLineStructure() {
            assertThat(PromptContext.bounded("a\nb\nc", 50)).isEqualTo("a\nb\nc");
        }

        @Test
        void appendsATruncationMarkerWhenOverTheLimit() {
            assertThat(PromptContext.bounded("abcdefghij", 4)).isEqualTo("abcd\n…[truncated]");
        }

        @Test
        void doesNotSplitASurrogatePairAtTheLimit() {
            assertThat(PromptContext.bounded("abc😀z", 4)).isEqualTo("abc\n…[truncated]");
            assertThat(PromptContext.bounded("😀z", 1)).isEqualTo("\n…[truncated]");
        }

        @Test
        void treatsNullAsEmpty() {
            assertThat(PromptContext.bounded(null, 10)).isEmpty();
        }
    }
}
