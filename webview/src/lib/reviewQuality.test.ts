import assert from 'node:assert/strict'
import test from 'node:test'
import type { ReviewResult } from '../bridge/types'
import { applyReviewQualityRepairs, buildDiffBatches, runReviewQualityCheck } from './reviewQuality'

const diff = `diff --git a/src/a.ts b/src/a.ts
--- a/src/a.ts
+++ b/src/a.ts
@@ -0,0 +1,2 @@
+const value = 1
+export { value }
`

const result: ReviewResult = {
  summary: 'Summary',
  verdict: 'COMMENT',
  lineComments: [{
    file: 'src/a.ts',
    line: 1,
    type: 'suggestion',
    body: 'Explain why this constant belongs at module scope.',
    severity: 'minor',
    confidence: 'high',
  }],
}

void test('quality check flags missing rationale and drops those comments rather than fabricating it', () => {
  const report = runReviewQualityCheck(result, diff)

  assert.equal(report.missingRationaleComments.length, 1)
  assert.ok(report.suggestions.includes('dropMissingRationale'))

  const repaired = applyReviewQualityRepairs(result, report, ['dropMissingRationale'])
  assert.equal(repaired.lineComments.length, 0)
})

void test('missing-rationale repair never invents evidence text', () => {
  const report = runReviewQualityCheck(result, diff)
  const repaired = applyReviewQualityRepairs(result, report, ['dropMissingRationale'])

  for (const comment of repaired.lineComments) {
    assert.doesNotMatch(comment.rationale ?? '', /Evidence needs verification/)
  }
})

void test('missing-rationale repair leaves comments that already have rationale', () => {
  const withRationale: ReviewResult = {
    ...result,
    lineComments: [
      result.lineComments[0],
      {
        file: 'src/a.ts',
        line: 2,
        type: 'suggestion',
        body: 'Export the value explicitly.',
        severity: 'minor',
        confidence: 'high',
        rationale: 'Line 2 re-exports the constant.',
      },
    ],
  }
  const report = runReviewQualityCheck(withRationale, diff)
  const repaired = applyReviewQualityRepairs(withRationale, report, ['dropMissingRationale'])

  assert.equal(repaired.lineComments.length, 1)
  assert.equal(repaired.lineComments[0].line, 2)
})

void test('diff batches prioritize larger changed files', () => {
  const secondFile = `${diff}diff --git a/src/b.ts b/src/b.ts
--- a/src/b.ts
+++ b/src/b.ts
@@ -0,0 +1,1 @@
+const b = 1
`
  const batches = buildDiffBatches(secondFile, 1)

  assert.deepEqual(batches.map((batch) => batch.files[0]), ['src/a.ts', 'src/b.ts'])
  assert.match(batches[0].diff, /src\/a\.ts/)
  assert.doesNotMatch(batches[0].diff, /src\/b\.ts/)
  assert.match(batches[1].diff, /src\/b\.ts/)
  assert.doesNotMatch(batches[1].diff, /src\/a\.ts/)
})

void test('diff batches preserve deleted files', () => {
  const deletedFile = `diff --git a/src/deleted.ts b/src/deleted.ts
deleted file mode 100644
--- a/src/deleted.ts
+++ /dev/null
@@ -1 +0,0 @@
-export const deleted = true
`

  const batches = buildDiffBatches(deletedFile)

  assert.deepEqual(batches.map((batch) => batch.files), [['src/deleted.ts']])
  assert.equal(batches[0].diff, deletedFile)
})

void test('diff batches preserve binary files with quoted paths', () => {
  const binaryFile = `diff --git "a/assets/hero image.png" "b/assets/hero image.png"
new file mode 100644
index 0000000..1234567
Binary files /dev/null and "b/assets/hero image.png" differ
`

  const batches = buildDiffBatches(binaryFile)

  assert.deepEqual(batches.map((batch) => batch.files), [['assets/hero image.png']])
  assert.equal(batches[0].diff, binaryFile)
})

void test('diff batches respect the context-size budget', () => {
  const secondFile = `${diff}diff --git a/src/b.ts b/src/b.ts
--- a/src/b.ts
+++ b/src/b.ts
@@ -0,0 +1 @@
+${'x'.repeat(80)}
`

  const batches = buildDiffBatches(secondFile, 6, diff.length + 20)

  assert.deepEqual(batches.map((batch) => batch.files), [['src/a.ts'], ['src/b.ts']])
})

void test('diff batch limits must be positive', () => {
  assert.throws(() => buildDiffBatches(diff, 0), /must be positive/)
  assert.throws(() => buildDiffBatches(diff, 1, 0), /must be positive/)
})

// ── high-risk / low-evidence detection ────────────────────────────────────────

function withComment(overrides: Partial<ReviewResult['lineComments'][number]>): ReviewResult {
  return {
    summary: 'Summary',
    verdict: 'COMMENT',
    lineComments: [{
      file: 'src/a.ts',
      line: 1,
      type: 'issue',
      body: 'Deadlock: B locks A here.',
      severity: 'blocker',
      confidence: 'high',
      rationale: 'Lock order inverted versus acquire() above.',
      ...overrides,
    }],
  }
}

// Regression guard for the retired `body.length < 35` rule: a terse but fully justified finding
// was previously flagged purely for being short.
void test('a short but justified high-severity finding is not flagged as low evidence', () => {
  const report = runReviewQualityCheck(withComment({}), diff)

  assert.equal(report.riskyComments.length, 0)
})

void test('a high-severity finding with no stated rationale is flagged', () => {
  const report = runReviewQualityCheck(withComment({ rationale: '   ' }), diff)

  assert.equal(report.riskyComments.length, 1)
})

void test('a high-severity finding the model itself rates low-confidence is flagged', () => {
  const report = runReviewQualityCheck(withComment({ confidence: 'low' }), diff)

  assert.equal(report.riskyComments.length, 1)
})

// Only serious claims need to carry evidence; a nit without rationale is not a quality problem.
void test('a low-severity finding without rationale is not flagged', () => {
  const report = runReviewQualityCheck(
    withComment({ severity: 'minor', rationale: undefined }),
    diff,
  )

  assert.equal(report.riskyComments.length, 0)
})

void test('a finding on a file absent from the diff is always flagged', () => {
  const report = runReviewQualityCheck(withComment({ file: 'src/never-touched.ts' }), diff)

  assert.equal(report.riskyComments.length, 1)
})

