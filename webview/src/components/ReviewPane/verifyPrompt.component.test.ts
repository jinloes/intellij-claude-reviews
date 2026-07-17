import { describe, expect, it } from 'vitest'
import type { LineComment } from '@/bridge/types'
import { buildExampleFixPrompt, buildVerifyCommentPrompt } from './verifyPrompt'

const comment: LineComment = {
  file: 'src/auth.ts',
  line: 2,
  type: 'issue',
  severity: 'major',
  category: 'security',
  confidence: 'high',
  rationale: 'The added branch bypasses the access check.',
  body: 'This appears to skip the authorization check for invited users.',
}

const diff = `diff --git a/src/auth.ts b/src/auth.ts
--- a/src/auth.ts
+++ b/src/auth.ts
@@ -1,2 +1,4 @@
 export const ready = true
+if (invitedUser) return true
+if (!hasAccess(user)) return false
+return isAllowed(user)
`

describe('buildVerifyCommentPrompt', () => {
  it('asks for a verification-specific verdict, why, and action', () => {
    const prompt = buildVerifyCommentPrompt(comment, diff)

    expect(prompt.question).toContain('Verify whether this draft review comment is supported by the provided context.')
    expect(prompt.question).toContain('Verdict: valid | invalid | unclear')
    expect(prompt.question).toContain('Action: keep | revise | delete')
    expect(prompt.question).toContain('Do not rely on code outside the provided context.')
  })

  it('includes comment metadata and the nearest diff excerpt in the focused context', () => {
    const prompt = buildVerifyCommentPrompt(comment, diff)

    expect(prompt.context).toContain('Draft review comment under verification:')
    expect(prompt.context).toContain('File: src/auth.ts')
    expect(prompt.context).toContain('Line: 2')
    expect(prompt.context).toContain('Type: issue')
    expect(prompt.context).toContain('Severity: major')
    expect(prompt.context).toContain('Category: security')
    expect(prompt.context).toContain('Original confidence: high')
    expect(prompt.context).toContain('Original rationale: The added branch bypasses the access check.')
    expect(prompt.context).toContain('Comment text: This appears to skip the authorization check for invited users.')
    expect(prompt.context).toContain('Relevant diff excerpt:')
    expect(prompt.context).toContain('@@ -1,2 +1,4 @@')
    expect(prompt.context).toContain('if (invitedUser) return true')
    expect(prompt.context).toContain('if (!hasAccess(user)) return false')
  })

  it('falls back gracefully when the relevant hunk cannot be found', () => {
    const prompt = buildVerifyCommentPrompt(comment, '')

    expect(prompt.context).toContain('Relevant diff excerpt:')
    expect(prompt.context).toContain('Unavailable — the changed hunk for this comment could not be extracted')
  })
})

describe('buildExampleFixPrompt', () => {
  it('asks for an example patch with rationale, risks, and tests', () => {
    const prompt = buildExampleFixPrompt(comment, diff)

    expect(prompt.question).toContain('Generate an example code change that addresses this draft review comment')
    expect(prompt.question).toContain('Approach: 1-2 concise bullets')
    expect(prompt.question).toContain('Example patch: one fenced code block')
    expect(prompt.question).toContain('If the context is insufficient, say so explicitly instead of guessing')
  })

  it('includes the same focused comment metadata and diff excerpt context', () => {
    const prompt = buildExampleFixPrompt(comment, diff)

    expect(prompt.context).toContain('Draft review comment requiring an example fix:')
    expect(prompt.context).toContain('File: src/auth.ts')
    expect(prompt.context).toContain('Line: 2')
    expect(prompt.context).toContain('Type: issue')
    expect(prompt.context).toContain('Comment text: This appears to skip the authorization check for invited users.')
    expect(prompt.context).toContain('Relevant diff excerpt:')
    expect(prompt.context).toContain('@@ -1,2 +1,4 @@')
  })
})

