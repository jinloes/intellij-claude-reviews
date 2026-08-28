import { describe, expect, it } from 'vitest'
import {
  appendReviewActivity,
  emptyReviewActivity,
  finishReviewActivity,
  formatActivityDuration,
  formatReviewActivityLabel,
  startReviewActivity,
} from './reviewActivity'

describe('review activity transitions', () => {
  it('records chronological steps and closes the previous step', () => {
    const started = startReviewActivity(emptyReviewActivity(), 'Starting review…', 1_000)
    const reading = appendReviewActivity(started, 'read_file', 2_500)

    expect(reading).toMatchObject({
      runId: 1,
      outcome: 'running',
      startedAtMs: 1_000,
      endedAtMs: null,
    })
    expect(reading.entries).toEqual([
      { message: 'Starting review…', startedAtMs: 1_000, endedAtMs: 2_500 },
      { message: 'read_file', startedAtMs: 2_500 },
    ])
  })

  it('starts a timeline when the host sends the first generation status', () => {
    expect(appendReviewActivity(emptyReviewActivity(), 'Preparing worktree…', 1_000))
      .toMatchObject({
        runId: 1,
        outcome: 'running',
        startedAtMs: 1_000,
        entries: [{ message: 'Preparing worktree…', startedAtMs: 1_000 }],
      })
  })

  it.each([
    ['completed', 'Review complete'],
    ['failed', 'Review failed'],
    ['cancelled', 'Review cancelled'],
  ] as const)('records a terminal %s step', (outcome, message) => {
    const started = startReviewActivity(emptyReviewActivity(), 'Starting review…', 1_000)
    const finished = finishReviewActivity(started, outcome, message, 4_000)

    expect(finished.outcome).toBe(outcome)
    expect(finished.endedAtMs).toBe(4_000)
    expect(finished.entries).toEqual([
      { message: 'Starting review…', startedAtMs: 1_000, endedAtMs: 4_000 },
      { message, startedAtMs: 4_000, endedAtMs: 4_000 },
    ])
  })

  it('ignores late status and error events after cancellation', () => {
    const started = startReviewActivity(emptyReviewActivity(), 'Starting review…', 1_000)
    const cancelled = finishReviewActivity(
      started,
      'cancelled',
      'Review cancelled',
      2_000,
    )

    expect(appendReviewActivity(cancelled, 'read_file', 3_000)).toBe(cancelled)
    expect(finishReviewActivity(cancelled, 'failed', 'Review failed', 3_000)).toBe(cancelled)
  })
})

describe('review activity formatting', () => {
  it.each([
    ['read_file', 'Reading files'],
    ['grep_search', 'Searching the worktree'],
    ['list', 'Listing files'],
    ['compare-diff', 'Inspecting the diff'],
    ['custom_tool', 'Using custom tool'],
    ['Parsing review…', 'Parsing review'],
  ])('formats %s as %s', (status, expected) => {
    expect(formatReviewActivityLabel(status)).toBe(expected)
  })

  it.each([
    [0, '0s'],
    [12_000, '12s'],
    [60_000, '1m'],
    [75_000, '1m 15s'],
  ])('formats %dms as %s', (durationMs, expected) => {
    expect(formatActivityDuration(durationMs)).toBe(expected)
  })
})
