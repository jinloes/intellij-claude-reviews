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

void test('quality check identifies and repairs missing rationale', () => {
  const report = runReviewQualityCheck(result, diff)

  assert.equal(report.missingRationaleComments.length, 1)
  assert.ok(report.suggestions.includes('addMissingRationale'))
  const repaired = applyReviewQualityRepairs(result, report, ['addMissingRationale'])
  assert.match(repaired.lineComments[0].rationale ?? '', /Evidence needs verification/)
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
})
