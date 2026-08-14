package com.jinloes.prpilot.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private static final DateTimeFormatter QUARANTINE_AT_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    // Index instances are created by multiple project panels but share this process-wide file.
    private static final ConcurrentMap<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path indexFile;
    private final Object fileLock;
    private final Runnable afterLoadBeforeMutation;

    public PendingReviewIndex() {
        this(Path.of(System.getProperty("user.home"), ".pr-pilot", "pending-prs.json"));
    }

    public PendingReviewIndex(Path indexFile) {
        this(indexFile, () -> {});
    }

    PendingReviewIndex(Path indexFile, Runnable afterLoadBeforeMutation) {
        this.indexFile = indexFile.toAbsolutePath().normalize();
        this.fileLock = FILE_LOCKS.computeIfAbsent(this.indexFile, ignored -> new Object());
        this.afterLoadBeforeMutation = afterLoadBeforeMutation;
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

    public enum QuarantineStatus {
        QUARANTINED,
        ALREADY_HEALTHY,
        FAILED
    }

    public record QuarantineResult(QuarantineStatus status, Path quarantinedPath, String error) {}

    private LoadResult loadEntriesLocked() {
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

    public LoadResult listResult() {
        synchronized (fileLock) {
            return loadEntriesLocked();
        }
    }

    List<Entry> list() {
        synchronized (fileLock) {
            return loadEntriesLocked().entries();
        }
    }

    /**
     * Moves a corrupt index aside without replacing it or touching remote drafts. A later read then
     * starts from an empty, healthy local index.
     */
    public QuarantineResult quarantineCorruptFile() {
        synchronized (fileLock) {
            LoadResult loaded = loadEntriesLocked();
            if (loaded.healthy() || !Files.exists(indexFile)) {
                return new QuarantineResult(QuarantineStatus.ALREADY_HEALTHY, null, null);
            }

            Path quarantineFile = nextQuarantineFile();
            try {
                try {
                    Files.move(indexFile, quarantineFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(indexFile, quarantineFile);
                }
                return new QuarantineResult(QuarantineStatus.QUARANTINED, quarantineFile, null);
            } catch (IOException exception) {
                log.warn(
                        "Failed to quarantine corrupt pending review index at {}",
                        indexFile,
                        exception);
                return new QuarantineResult(
                        QuarantineStatus.FAILED,
                        null,
                        "PR Pilot could not quarantine the corrupt draft index.");
            }
        }
    }

    public MutationResult add(String owner, String repo, int number, String title, String headSha) {
        synchronized (fileLock) {
            LoadResult loaded = loadEntriesLocked();
            if (!loaded.healthy()) return MutationResult.BLOCKED_CORRUPT;
            afterLoadBeforeMutation.run();
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
    }

    boolean hasDraft(String owner, String repo, int number) {
        return draftState(owner, repo, number) == DraftState.PRESENT;
    }

    public DraftState draftState(String owner, String repo, int number) {
        synchronized (fileLock) {
            LoadResult loaded = loadEntriesLocked();
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
    }

    public MutationResult remove(String owner, String repo, int number) {
        synchronized (fileLock) {
            LoadResult loaded = loadEntriesLocked();
            if (!loaded.healthy()) return MutationResult.BLOCKED_CORRUPT;
            afterLoadBeforeMutation.run();
            List<Entry> entries = new ArrayList<>(loaded.entries());
            entries.removeIf(
                    e -> e.owner().equals(owner) && e.repo().equals(repo) && e.number() == number);
            return save(entries) ? MutationResult.UPDATED : MutationResult.FAILED;
        }
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

    Path indexPath() {
        return indexFile.toAbsolutePath().normalize();
    }

    private Path nextQuarantineFile() {
        String baseName =
                indexFile.getFileName()
                        + ".corrupt-"
                        + LocalDateTime.now().format(QUARANTINE_AT_FMT);
        Path candidate = indexFile.resolveSibling(baseName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = indexFile.resolveSibling(baseName + "-" + suffix);
            suffix++;
        }
        return candidate;
    }
}
