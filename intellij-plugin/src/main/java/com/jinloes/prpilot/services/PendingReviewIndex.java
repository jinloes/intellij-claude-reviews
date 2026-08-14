package com.jinloes.prpilot.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight local index recording which PRs have a pending (draft) review on GitHub. Stored as
 * JSON at {@code ~/.pr-pilot/pending-prs.json}.
 *
 * <p>This is intentionally minimal — it holds only enough data to populate the "Load Draft" list.
 * The actual review content is always fetched live from GitHub.
 */
public final class PendingReviewIndex {
    private static final Logger log = LoggerFactory.getLogger(PendingReviewIndex.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter SAVED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path indexFile;

    public PendingReviewIndex() {
        this(Path.of(System.getProperty("user.home"), ".pr-pilot", "pending-prs.json"));
    }

    public PendingReviewIndex(Path indexFile) {
        this.indexFile = indexFile;
    }

    /**
     * {@code headShaRaw} is nullable to handle old JSON entries saved before this field existed.
     */
    public record Entry(
            String owner,
            String repo,
            int number,
            String title,
            String savedAt,
            @JsonProperty("headSha") String headShaRaw) {

        /** Returns the head SHA, or empty string for entries saved before this field was added. */
        public String headSha() {
            return headShaRaw == null ? "" : headShaRaw;
        }

        public String displayLabel() {
            String normalized = savedAt.replace("T", " ");
            String truncated = normalized.substring(0, Math.min(16, normalized.length()));
            return owner + "/" + repo + " #" + number + " — " + title + "  (" + truncated + ")";
        }
    }

    public record LoadResult(List<Entry> entries, String error) {
        public LoadResult {
            entries = List.copyOf(entries);
        }

        public boolean healthy() {
            return error == null;
        }
    }

    public enum MutationResult {
        UPDATED,
        BLOCKED_CORRUPT,
        FAILED
    }

    public enum DraftState {
        PRESENT,
        ABSENT,
        UNAVAILABLE
    }

    private synchronized LoadResult loadEntries() {
        if (!Files.exists(indexFile)) return new LoadResult(List.of(), null);
        try {
            String json = Files.readString(indexFile, StandardCharsets.UTF_8);
            Entry[] entries = MAPPER.readValue(json, Entry[].class);
            return new LoadResult(List.of(entries), null);
        } catch (Exception e) {
            log.warn(
                    "Pending review index at {} is corrupt or unreadable; preserving it and blocking mutations until it is repaired or removed.",
                    indexFile,
                    e);
            return new LoadResult(List.of(), "Pending review index is corrupt or unreadable.");
        }
    }

    public synchronized LoadResult listResult() {
        return loadEntries();
    }

    public synchronized List<Entry> list() {
        return loadEntries().entries();
    }

    public synchronized MutationResult add(
            String owner, String repo, int number, String title, String headSha) {
        LoadResult loaded = loadEntries();
        if (!loaded.healthy()) return MutationResult.BLOCKED_CORRUPT;
        List<Entry> entries = new ArrayList<>(loaded.entries());
        entries.removeIf(
                e -> e.owner().equals(owner) && e.repo().equals(repo) && e.number() == number);
        entries.add(
                0,
                new Entry(
                        owner,
                        repo,
                        number,
                        title,
                        LocalDateTime.now().format(SAVED_AT_FMT),
                        headSha == null ? "" : headSha));
        return save(entries) ? MutationResult.UPDATED : MutationResult.FAILED;
    }

    public synchronized boolean hasDraft(String owner, String repo, int number) {
        return draftState(owner, repo, number) == DraftState.PRESENT;
    }

    public synchronized DraftState draftState(String owner, String repo, int number) {
        LoadResult loaded = loadEntries();
        if (!loaded.healthy()) return DraftState.UNAVAILABLE;
        boolean present =
                loaded.entries().stream()
                        .anyMatch(
                                e ->
                                        e.owner().equals(owner)
                                                && e.repo().equals(repo)
                                                && e.number() == number);
        return present ? DraftState.PRESENT : DraftState.ABSENT;
    }

    public synchronized MutationResult remove(String owner, String repo, int number) {
        LoadResult loaded = loadEntries();
        if (!loaded.healthy()) return MutationResult.BLOCKED_CORRUPT;
        List<Entry> entries = new ArrayList<>(loaded.entries());
        entries.removeIf(
                e -> e.owner().equals(owner) && e.repo().equals(repo) && e.number() == number);
        return save(entries) ? MutationResult.UPDATED : MutationResult.FAILED;
    }

    private boolean save(List<Entry> entries) {
        try {
            Files.createDirectories(indexFile.getParent());
            Path tmp = indexFile.resolveSibling(indexFile.getFileName().toString() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(entries), StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    indexFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            log.warn("Failed to save pending review index", e);
            return false;
        }
    }
}
