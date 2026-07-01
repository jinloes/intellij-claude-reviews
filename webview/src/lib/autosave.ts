import type { ReviewResult } from '../bridge/types'

/**
 * Debounce window for autosaving edits to an already-saved draft. Edits made
 * after a review has been persisted are flushed to GitHub this many ms after
 * the last change. The initial save right after generation is immediate (see
 * {@link autosaveDelayMs}).
 */
export const AUTOSAVE_DEBOUNCE_MS = 30_000

/** Pane states whose `result` can be autosaved to a GitHub draft. */
export type SavableKind = 'reviewUnsaved' | 'draftPresent'

/**
 * Serializes a review into a comparable snapshot. Two results with identical
 * field values produce the same string, so snapshot equality is used to decide
 * whether the in-memory review differs from what was last saved.
 */
export function reviewSnapshot(result: ReviewResult): string {
  return JSON.stringify(result)
}

/**
 * True when the current review differs from the last successfully-saved
 * snapshot. A `null` current snapshot (no savable review present) is never
 * dirty.
 */
export function isReviewDirty(current: string | null, lastSaved: string | null): boolean {
  return current !== null && current !== lastSaved
}

/**
 * Delay before autosaving for a given savable state.
 *
 * - `reviewUnsaved` (a freshly generated review) saves immediately (`0`) — this
 *   is the "save on generate" behavior so an expensive model run is never lost.
 * - `draftPresent` (edits to an already-saved draft) debounces so rapid edits
 *   collapse into a single GitHub write.
 */
export function autosaveDelayMs(kind: SavableKind): number {
  return kind === 'reviewUnsaved' ? 0 : AUTOSAVE_DEBOUNCE_MS
}

