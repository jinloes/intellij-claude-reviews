package com.jinloes.prpilot.model;

/** Authenticated reviewer's submitted-review state relative to a pull request's current head. */
public enum ReviewStatus {
    UNREVIEWED,
    REVIEWED,
    UPDATED_SINCE_REVIEW,
    UNAVAILABLE
}
