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
