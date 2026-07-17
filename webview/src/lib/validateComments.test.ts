import test from 'node:test'
import assert from 'node:assert/strict'

import { validateComments } from './validateComments.js'
import type { LineComment } from '../bridge/types'

function comment(file: string, line: number, body = 'note'): LineComment {
  return { file, line, type: 'issue', body }
}

void test('validateComments keeps general notes when diff parsing fails', () => {
  const result = validateComments('not a diff', [comment('src/Foo.ts', 12), comment('', 0, 'general note')])

  assert.equal(result.adjusted.length, 1)
  assert.equal(result.adjusted[0].file, '')
  assert.equal(result.adjusted[0].body, 'general note')
  assert.equal(result.orphans.length, 1)
  assert.equal(result.orphans[0].file, 'src/Foo.ts')
})

void test('validateComments matches a unique suffix but rejects ambiguous suffixes', () => {
  const uniqueDiff = [
    'diff --git a/src/one/Foo.ts b/src/one/Foo.ts',
    '--- a/src/one/Foo.ts',
    '+++ b/src/one/Foo.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  const unique = validateComments(uniqueDiff, [comment('Foo.ts', 1)])
  assert.equal(unique.adjusted.length, 1)
  assert.equal(unique.adjusted[0].line, 1)
  assert.equal(unique.orphans.length, 0)

  const ambiguousDiff = [
    'diff --git a/src/a/Foo.ts b/src/a/Foo.ts',
    '--- a/src/a/Foo.ts',
    '+++ b/src/a/Foo.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
    'diff --git a/src/b/Foo.ts b/src/b/Foo.ts',
    '--- a/src/b/Foo.ts',
    '+++ b/src/b/Foo.ts',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  const ambiguous = validateComments(ambiguousDiff, [comment('Foo.ts', 1)])
  assert.equal(ambiguous.adjusted.length, 0)
  assert.equal(ambiguous.orphans.length, 1)
  assert.equal(ambiguous.orphans[0].file, 'Foo.ts')
})

void test('validateComments does not match file against substring-without-boundary (UserAction vs Action)', () => {
  // Both files exist in the diff. A comment on "Action.java" must NOT match "UserAction.java"
  // because "Action.java" is not preceded by "/" in "UserAction.java".
  const diff = [
    'diff --git a/src/Action.java b/src/Action.java',
    '--- a/src/Action.java',
    '+++ b/src/Action.java',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
    'diff --git a/src/UserAction.java b/src/UserAction.java',
    '--- a/src/UserAction.java',
    '+++ b/src/UserAction.java',
    '@@ -1,1 +1,1 @@',
    '-old',
    '+new',
    '',
  ].join('\n')

  // Exact match on "src/Action.java" should still work
  const exact = validateComments(diff, [comment('src/Action.java', 1)])
  assert.equal(exact.adjusted.length, 1)
  assert.equal(exact.adjusted[0].file, 'src/Action.java')
  assert.equal(exact.orphans.length, 0)

  // Short name "Action.java" is ambiguous (matches both src/Action.java and src/UserAction.java
  // under the old broken rule) — with the fix, key.endsWith('/Action.java') matches only
  // src/Action.java (unique), so the comment is correctly anchored.
  const short = validateComments(diff, [comment('Action.java', 1)])
  assert.equal(short.adjusted.length, 1)
  assert.equal(short.adjusted[0].file, 'Action.java')
  assert.equal(short.orphans.length, 0)

  // A name that is a true non-boundary substring ("Action") should become an orphan.
  const sub = validateComments(diff, [comment('Action', 1)])
  assert.equal(sub.orphans.length, 1)
})
