package com.jinloes.prpilot.sidecar.repo;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Outcome of a {@link RepoDetector#detect(String)} call. Every non-{@link #FOUND} value is a typed,
 * non-fatal outcome — callers should treat these as "no repository detected for this reason", not
 * as errors to surface via JSON-RPC error responses.
 */
public enum DetectStatus {
    /** An {@code origin} remote with a parseable owner/repo was found. */
    FOUND,
    /** {@code path} was missing, not absolute, or did not resolve to an existing directory. */
    INVALID_PATH,
    /** No {@code .git} directory or linked-worktree file was found in any ancestor of the path. */
    NOT_GIT,
    /** The resolved git directory has no {@code config} file. */
    CONFIG_MISSING,
    /** The {@code config} file exists but could not be read. */
    CONFIG_UNREADABLE,
    /** {@code config} was read but has no {@code [remote "origin"]} url. */
    ORIGIN_MISSING,
    /** The {@code origin} url was present but could not be parsed into owner/repo. */
    ORIGIN_URL_MALFORMED,
    /** The {@code .git} file did not contain a valid {@code gitdir: <path>} line. */
    GITDIR_MALFORMED,
    /** The {@code .git} file's {@code gitdir:} target could not be read or does not exist. */
    GITDIR_UNREADABLE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
