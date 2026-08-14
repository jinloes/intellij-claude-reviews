package com.jinloes.prpilot.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
            assertThat(idx.add("owner", "repo", 1, "My PR", ""))
                    .isEqualTo(PendingReviewIndex.MutationResult.UPDATED);

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

        @Test
        void concurrentInstancesDoNotLoseOverlappingAdditions() throws Exception {
            Path file = tempDir.resolve("pending-prs.json");
            CountDownLatch firstLoaded = new CountDownLatch(1);
            CountDownLatch allowFirstMutation = new CountDownLatch(1);
            PendingReviewIndex first =
                    new PendingReviewIndex(
                            file,
                            () -> {
                                firstLoaded.countDown();
                                try {
                                    allowFirstMutation.await();
                                } catch (InterruptedException exception) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(exception);
                                }
                            });
            PendingReviewIndex second = new PendingReviewIndex(file);

            CompletableFuture<PendingReviewIndex.MutationResult> firstMutation =
                    CompletableFuture.supplyAsync(() -> first.add("owner", "repo", 1, "first", ""));
            assertThat(firstLoaded.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<PendingReviewIndex.MutationResult> secondMutation =
                    CompletableFuture.supplyAsync(
                            () -> second.add("owner", "repo", 2, "second", ""));

            try {
                assertThat(secondMutation).isNotDone();
            } finally {
                allowFirstMutation.countDown();
            }

            assertThat(firstMutation.get(5, TimeUnit.SECONDS))
                    .isEqualTo(PendingReviewIndex.MutationResult.UPDATED);
            assertThat(secondMutation.get(5, TimeUnit.SECONDS))
                    .isEqualTo(PendingReviewIndex.MutationResult.UPDATED);
            assertThat(first.list())
                    .extracting(PendingReviewIndex.Entry::number)
                    .containsExactly(2, 1);
        }
    }

    @Nested
    class Corruption {

        @Test
        void malformedJsonIsPreservedAndBlocksMutations() throws IOException {
            Path file = tempDir.resolve("pending-prs.json");
            String malformed = "{not-json";
            Files.writeString(file, malformed, StandardCharsets.UTF_8);
            PendingReviewIndex idx = new PendingReviewIndex(file);

            PendingReviewIndex.LoadResult loadResult = idx.listResult();

            assertThat(loadResult.healthy()).isFalse();
            assertThat(loadResult.entries()).isEmpty();
            assertThat(idx.draftState("owner", "repo", 1))
                    .isEqualTo(PendingReviewIndex.DraftState.UNAVAILABLE);
            assertThat(idx.add("owner", "repo", 1, "Title", "sha"))
                    .isEqualTo(PendingReviewIndex.MutationResult.BLOCKED_CORRUPT);
            assertThat(idx.remove("owner", "repo", 1))
                    .isEqualTo(PendingReviewIndex.MutationResult.BLOCKED_CORRUPT);
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(malformed);
        }

        @Test
        void truncatedJsonIsNotOverwrittenByTheNextMutation() throws IOException {
            Path file = tempDir.resolve("pending-prs.json");
            String truncated = "[{\"owner\":\"acme\"";
            Files.writeString(file, truncated, StandardCharsets.UTF_8);
            PendingReviewIndex idx = new PendingReviewIndex(file);

            assertThat(idx.add("owner", "repo", 2, "Title", "sha"))
                    .isEqualTo(PendingReviewIndex.MutationResult.BLOCKED_CORRUPT);

            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(truncated);
        }

        @Test
        void quarantinePreservesTheCorruptFileAndRestoresHealthyMutations() throws IOException {
            Path file = tempDir.resolve("pending-prs.json");
            String malformed = "{not-json";
            Files.writeString(file, malformed, StandardCharsets.UTF_8);
            PendingReviewIndex idx = new PendingReviewIndex(file);

            PendingReviewIndex.QuarantineResult result = idx.quarantineCorruptFile();

            assertThat(result.status()).isEqualTo(PendingReviewIndex.QuarantineStatus.QUARANTINED);
            assertThat(result.quarantinedPath()).exists();
            assertThat(Files.readString(result.quarantinedPath(), StandardCharsets.UTF_8))
                    .isEqualTo(malformed);
            assertThat(file).doesNotExist();
            assertThat(idx.listResult().healthy()).isTrue();
            assertThat(idx.add("owner", "repo", 1, "Title", "sha"))
                    .isEqualTo(PendingReviewIndex.MutationResult.UPDATED);
        }

        @Test
        void quarantineDoesNotMoveAHealthyIndex() {
            PendingReviewIndex idx = index();
            idx.add("owner", "repo", 1, "Title", "sha");

            PendingReviewIndex.QuarantineResult result = idx.quarantineCorruptFile();

            assertThat(result.status())
                    .isEqualTo(PendingReviewIndex.QuarantineStatus.ALREADY_HEALTHY);
            assertThat(idx.list()).hasSize(1);
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

        @Nested
        class RecoveryRegistration {

            @Test
            void closeRemovesRecoveryActionForTheIndexPath() throws IOException {
                Path file = tempDir.resolve("pending-prs.json");
                Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
                PendingReviewIndex index = new PendingReviewIndex(file);
                Runnable recoveryAction = () -> {};

                PendingReviewIndexNotifications.Registration registration =
                        PendingReviewIndexNotifications.observe(
                                index, index.listResult(), recoveryAction);

                assertThat(PendingReviewIndexNotifications.recoveryActionCount(file)).isEqualTo(1);

                registration.close();

                assertThat(PendingReviewIndexNotifications.recoveryActionCount(file)).isZero();
            }
        }
    }
}
