import test from 'node:test'
import assert from 'node:assert/strict'

import { AUTOSAVE_DEBOUNCE_MS, autosaveDelayMs, isReviewDirty, reviewSnapshot } from './autosave.js'
import type { ReviewResult } from '../bridge/types'

function review(summary: string): ReviewResult {
  return { summary, verdict: 'COMMENT', lineComments: [] }
}

void test('reviewSnapshot is stable for equal results and differs for changed ones', () => {
  assert.equal(reviewSnapshot(review('a')), reviewSnapshot(review('a')))
  assert.notEqual(reviewSnapshot(review('a')), reviewSnapshot(review('b')))
})

void test('isReviewDirty is false when snapshot matches last saved', () => {
  const snap = reviewSnapshot(review('a'))
  assert.equal(isReviewDirty(snap, snap), false)
})

void test('isReviewDirty is true when snapshot differs from last saved', () => {
  assert.equal(isReviewDirty(reviewSnapshot(review('a')), reviewSnapshot(review('b'))), true)
})

void test('isReviewDirty is true on first save when nothing has been saved yet', () => {
  assert.equal(isReviewDirty(reviewSnapshot(review('a')), null), true)
})

void test('isReviewDirty is false when there is no savable review', () => {
  assert.equal(isReviewDirty(null, null), false)
  assert.equal(isReviewDirty(null, reviewSnapshot(review('a'))), false)
})

void test('autosaveDelayMs saves freshly generated reviews immediately', () => {
  assert.equal(autosaveDelayMs('reviewUnsaved'), 0)
})

void test('autosaveDelayMs debounces edits to a saved draft', () => {
  assert.equal(autosaveDelayMs('draftPresent'), AUTOSAVE_DEBOUNCE_MS)
})
