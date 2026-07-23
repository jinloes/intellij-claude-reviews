package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingReviewIndexTest {

    @TempDir Path tempDir;

    private PendingReviewIndex index() {
        return new PendingReviewIndex(tempDir.resolve("pending-prs.json"));
    }

    @Nested
    class ListEntries {

        @Test
        void returnsEmptyListWhenFileDoesNotExist() {
            assertThat(index().list()).isEmpty();
        }
    }

    @Nested
    class Add {

        @Test
        void createsEntryWithCorrectFields() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "My PR", "");

            var entries = idx.list();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).owner()).isEqualTo("owner");
            assertThat(entries.get(0).repo()).isEqualTo("repo");
            assertThat(entries.get(0).number()).isEqualTo(1);
            assertThat(entries.get(0).title()).isEqualTo("My PR");
        }

        @Test
        void deduplicatesSameOwnerRepoNumberKeepsNewTitle() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "Old title", "");
            idx.add("owner", "repo", 1, "New title", "");

            var entries = idx.list();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).title()).isEqualTo("New title");
        }

        @Test
        void differentPrsAreBothKept() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR one", "");
            idx.add("owner", "repo", 2, "PR two", "");

            assertThat(idx.list()).hasSize(2);
        }

        @Test
        void newestEntryIsFirst() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "first", "");
            idx.add("owner", "repo", 2, "second", "");

            assertThat(idx.list().get(0).number()).isEqualTo(2);
        }

        @Test
        void storesAndReturnsHeadSha() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "My PR", "abc123");

            assertThat(idx.list().get(0).headSha()).isEqualTo("abc123");
        }

        @Test
        void nullHeadShaReturnedAsEmptyString() {
            var entry = new PendingReviewIndex.Entry("o", "r", 1, "t", "2024-01-01", null);
            assertThat(entry.headSha()).isEmpty();
        }
    }

    @Nested
    class HasDraft {

        @Test
        void returnsFalseWhenEmpty() {
            assertThat(index().hasDraft("owner", "repo", 1)).isFalse();
        }

        @Test
        void returnsTrueWhenMatchingEntryExists() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 42, "My PR", "");

            assertThat(idx.hasDraft("owner", "repo", 42)).isTrue();
        }

        @Test
        void returnsFalseWhenPrNumberDoesNotMatch() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 42, "My PR", "");

            assertThat(idx.hasDraft("owner", "repo", 99)).isFalse();
        }

        @Test
        void returnsFalseWhenOwnerDiffers() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR", "");

            assertThat(idx.hasDraft("other", "repo", 1)).isFalse();
        }

        @Test
        void returnsFalseWhenRepoDiffers() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR", "");

            assertThat(idx.hasDraft("owner", "other", 1)).isFalse();
        }
    }

    @Nested
    class Remove {

        @Test
        void removesMatchingEntry() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR one", "");
            idx.add("owner", "repo", 2, "PR two", "");

            idx.remove("owner", "repo", 1);

            var entries = idx.list();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).number()).isEqualTo(2);
        }

        @Test
        void nonExistentEntryIsANoOp() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "PR", "");

            idx.remove("owner", "repo", 99);

            assertThat(idx.list()).hasSize(1);
        }
    }

    @Nested
    class Persistence {

        @Test
        void entriesPersistAcrossInstances() {
            Path file = tempDir.resolve("pending-prs.json");
            new PendingReviewIndex(file).add("owner", "repo", 7, "Saved PR", "");

            PendingReviewIndex second = new PendingReviewIndex(file);
            assertThat(second.list()).hasSize(1);
            assertThat(second.list().get(0).number()).isEqualTo(7);
        }
    }

    @Nested
    class DisplayLabel {

        @Test
        void containsOwnerRepoAndNumber() {
            PendingReviewIndex idx = index();
            idx.add("myorg", "myrepo", 42, "Fix bug", "");

            String label = idx.list().get(0).displayLabel();
            assertThat(label).contains("myorg/myrepo #42").contains("Fix bug");
        }

        @Test
        void savedAtIsTruncatedTo16CharsWhenLong() {
            var entry =
                    new PendingReviewIndex.Entry(
                            "o", "r", 1, "title", "2024-01-15T10:30:00", "sha");
            assertThat(entry.displayLabel()).contains("2024-01-15 10:30");
        }

        @Test
        void shortSavedAtIsNotTruncatedBeyondItsLength() {
            var entry = new PendingReviewIndex.Entry("o", "r", 1, "title", "2024-01", "sha");
            assertThat(entry.displayLabel()).contains("2024-01");
        }
    }
}
