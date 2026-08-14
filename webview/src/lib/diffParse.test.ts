import assert from 'node:assert/strict'
import test from 'node:test'
import { parseDiffSafely } from './diffParse'

const validDiff = [
  'diff --git a/src/value.ts b/src/value.ts',
  '--- a/src/value.ts',
  '+++ b/src/value.ts',
  '@@ -1 +1 @@',
  '-const value = 1',
  '+const value = 2',
].join('\n')

const binaryDiff = [
  'diff --git a/image.png b/image.png',
  'new file mode 100644',
  'index 0000000..1234567',
  'Binary files /dev/null and b/image.png differ',
].join('\n')

void test('parseDiffSafely distinguishes empty, parsed, and unrenderable content', () => {
  assert.deepEqual(parseDiffSafely(''), { status: 'empty', files: [] })
  assert.equal(parseDiffSafely(validDiff).status, 'parsed')
  assert.equal(parseDiffSafely(binaryDiff).status, 'parsed')
  assert.deepEqual(parseDiffSafely('not a unified diff'), { status: 'unrenderable', files: [] })
})


