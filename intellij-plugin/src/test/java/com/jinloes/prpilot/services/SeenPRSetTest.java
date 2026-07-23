package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinloes.prpilot.model.PullRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeenPRSetTest {

    @TempDir Path tempDir;

    private static PullRequest pr(String owner, String repo, int number) {
        return new PullRequest(
                "title", "http://example.com", owner, repo, number, "", "author", "2024-01-01");
    }

    private SeenPRSet set() {
        return new SeenPRSet(tempDir.resolve("seen-prs.json"));
    }

    @Nested
    class InitialState {

        @Test
        void newSetIsNotSeeded() {
            assertThat(set().isSeeded()).isFalse();
        }

        @Test
        void newSetDoesNotContainAnything() {
            assertThat(set().contains(pr("o", "r", 1))).isFalse();
        }
    }

    @Nested
    class AddAndContains {

        @Test
        void addThenContainsReturnsTrue() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));

            assertThat(s.contains(pr("o", "r", 1))).isTrue();
        }

        @Test
        void containsDifferentNumberReturnsFalse() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));

            assertThat(s.contains(pr("o", "r", 2))).isFalse();
        }

        @Test
        void multipleDistinctPrsAreAllContained() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            s.add(pr("o", "r", 2));
            s.add(pr("other", "repo", 5));

            assertThat(s.contains(pr("o", "r", 1))).isTrue();
            assertThat(s.contains(pr("o", "r", 2))).isTrue();
            assertThat(s.contains(pr("other", "repo", 5))).isTrue();
            assertThat(s.contains(pr("o", "r", 3))).isFalse();
        }
    }

    @Nested
    class Seeded {

        @Test
        void markSeededSetsSeededToTrue() {
            SeenPRSet s = set();
            s.markSeeded();

            assertThat(s.isSeeded()).isTrue();
        }
    }

    @Nested
    class Reset {

        @Test
        void clearsEntriesAndReturnsToUnseededState() {
            SeenPRSet s = set();
            PullRequest existing = pr("o", "r", 1);
            s.add(existing);
            s.markSeeded();

            s.reset();

            assertThat(s.isSeeded()).isFalse();
            assertThat(s.contains(existing)).isFalse();
        }

        @Test
        void persistsTheUnseededStateByRemovingThePriorFile() {
            Path file = tempDir.resolve("seen-prs.json");
            PullRequest existing = pr("o", "r", 1);
            SeenPRSet first = new SeenPRSet(file);
            first.add(existing);
            first.markSeeded();
            first.save();
            first.reset();

            SeenPRSet reloaded = new SeenPRSet(file);
            assertThat(reloaded.isSeeded()).isFalse();
            assertThat(reloaded.contains(existing)).isFalse();
        }
    }

    @Nested
    class Retain {

        @Test
        void retainWithLiveListRemovesAbsentEntries() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            s.add(pr("o", "r", 2));
            s.add(pr("o", "r", 3));

            s.retain(List.of(pr("o", "r", 2)));

            assertThat(s.contains(pr("o", "r", 1))).isFalse();
            assertThat(s.contains(pr("o", "r", 2))).isTrue();
            assertThat(s.contains(pr("o", "r", 3))).isFalse();
        }

        @Test
        void retainWithEmptyListRemovesAll() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));

            s.retain(List.of());

            assertThat(s.contains(pr("o", "r", 1))).isFalse();
        }

        @Test
        void retainWithFullListKeepsAll() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            s.add(pr("o", "r", 2));

            s.retain(List.of(pr("o", "r", 1), pr("o", "r", 2)));

            assertThat(s.contains(pr("o", "r", 1))).isTrue();
            assertThat(s.contains(pr("o", "r", 2))).isTrue();
        }
    }

    @Nested
    class Trim {

        @Test
        void belowMaxSizeNoChange() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            s.add(pr("o", "r", 2));

            s.trim(10);

            assertThat(s.contains(pr("o", "r", 1))).isTrue();
            assertThat(s.contains(pr("o", "r", 2))).isTrue();
        }

        @Test
        void dropsOldestEntries() {
            SeenPRSet s = set();
            for (int i = 1; i <= 5; i++) s.add(pr("o", "r", i));

            s.trim(3);

            // oldest (1, 2) dropped; newest (3, 4, 5) kept
            assertThat(s.contains(pr("o", "r", 1))).isFalse();
            assertThat(s.contains(pr("o", "r", 2))).isFalse();
            assertThat(s.contains(pr("o", "r", 3))).isTrue();
            assertThat(s.contains(pr("o", "r", 4))).isTrue();
            assertThat(s.contains(pr("o", "r", 5))).isTrue();
        }

        @Test
        void exactlyAtMaxSizeNoChange() {
            SeenPRSet s = set();
            s.add(pr("o", "r", 1));
            s.add(pr("o", "r", 2));

            s.trim(2);

            assertThat(s.contains(pr("o", "r", 1))).isTrue();
            assertThat(s.contains(pr("o", "r", 2))).isTrue();
        }
    }

    @Nested
    class Persistence {

        @Test
        void afterSaveAndReloadIsSeededIsTrue() {
            Path file = tempDir.resolve("seen-prs.json");
            SeenPRSet first = new SeenPRSet(file);
            first.add(pr("o", "r", 1));
            first.markSeeded();
            first.save();

            SeenPRSet second = new SeenPRSet(file);
            assertThat(second.isSeeded()).isTrue();
        }

        @Test
        void afterSaveAndReloadContainsAddedPrs() {
            Path file = tempDir.resolve("seen-prs.json");
            SeenPRSet first = new SeenPRSet(file);
            first.add(pr("org", "repo", 42));
            first.markSeeded();
            first.save();

            SeenPRSet second = new SeenPRSet(file);
            assertThat(second.contains(pr("org", "repo", 42))).isTrue();
            assertThat(second.contains(pr("org", "repo", 99))).isFalse();
        }
    }
}
