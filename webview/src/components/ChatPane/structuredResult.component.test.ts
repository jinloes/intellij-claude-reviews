import { describe, expect, it } from 'vitest'
import { parseStructuredResult } from './structuredResult'

describe('parseStructuredResult — verify schema', () => {
  it('parses a well-formed verify result', () => {
    const content = JSON.stringify({
      verdict: 'invalid',
      why: 'The diff shows the check is present at line 12.',
      action: 'revise',
      replacementComment: 'This is missing null-checking for the optional field.',
    })

    const result = parseStructuredResult(content)

    expect(result).toEqual({
      kind: 'verify',
      verdict: 'invalid',
      why: 'The diff shows the check is present at line 12.',
      action: 'revise',
      replacementComment: 'This is missing null-checking for the optional field.',
    })
  })

  it('accepts a null replacementComment when action is not revise', () => {
    const content = JSON.stringify({
      verdict: 'valid',
      why: 'Confirmed by the excerpt.',
      action: 'keep',
      replacementComment: null,
    })

    const result = parseStructuredResult(content)

    expect(result?.kind).toBe('verify')
    expect(result).toMatchObject({ action: 'keep', replacementComment: null })
  })

  it('unwraps a fenced ```json code block despite instructions not to use one', () => {
    const content = [
      '```json',
      JSON.stringify({ verdict: 'unclear', why: 'Not enough context.', action: 'keep', replacementComment: null }),
      '```',
    ].join('\n')

    const result = parseStructuredResult(content)

    expect(result).toMatchObject({ kind: 'verify', verdict: 'unclear' })
  })

  it('rejects an invalid verdict enum value', () => {
    const content = JSON.stringify({ verdict: 'maybe', why: 'x', action: 'keep', replacementComment: null })
    expect(parseStructuredResult(content)).toBeNull()
  })

  it('rejects an invalid action enum value', () => {
    const content = JSON.stringify({ verdict: 'valid', why: 'x', action: 'ignore', replacementComment: null })
    expect(parseStructuredResult(content)).toBeNull()
  })

  it('rejects a missing required field', () => {
    const content = JSON.stringify({ verdict: 'valid', action: 'keep', replacementComment: null })
    expect(parseStructuredResult(content)).toBeNull()
  })
})

describe('parseStructuredResult — example-fix schema', () => {
  it('parses a well-formed example-fix result', () => {
    const content = JSON.stringify({
      approach: ['Add a null check before dereferencing.'],
      examplePatch: '```diff\n+if (value != null) {\n```',
      why: 'The excerpt shows the field can be null.',
      risks: ['May change behavior for legacy callers.'],
      testUpdates: ['Add a test for the null case.'],
      missingContext: [],
    })

    const result = parseStructuredResult(content)

    expect(result).toEqual({
      kind: 'fix',
      approach: ['Add a null check before dereferencing.'],
      examplePatch: '```diff\n+if (value != null) {\n```',
      why: 'The excerpt shows the field can be null.',
      risks: ['May change behavior for legacy callers.'],
      testUpdates: ['Add a test for the null case.'],
      missingContext: [],
    })
  })

  it('accepts a null examplePatch when context is insufficient', () => {
    const content = JSON.stringify({
      approach: [],
      examplePatch: null,
      why: 'Not enough context to propose a patch.',
      risks: [],
      testUpdates: [],
      missingContext: ['The full method body is not shown in the excerpt.'],
    })

    const result = parseStructuredResult(content)

    expect(result).toMatchObject({ kind: 'fix', examplePatch: null })
  })

  it('rejects a non-array approach field', () => {
    const content = JSON.stringify({
      approach: 'Add a null check.',
      examplePatch: null,
      why: 'x',
      risks: [],
      testUpdates: [],
      missingContext: [],
    })
    expect(parseStructuredResult(content)).toBeNull()
  })
})

describe('parseStructuredResult — non-JSON / free-form chat', () => {
  it('returns null for ordinary markdown chat replies', () => {
    expect(parseStructuredResult('This looks correct. The check at line 12 handles it.')).toBeNull()
  })

  it('returns null for malformed JSON', () => {
    expect(parseStructuredResult('{"verdict":"valid","why":')).toBeNull()
  })

  it('returns null for valid JSON that matches neither schema', () => {
    expect(parseStructuredResult(JSON.stringify({ foo: 'bar' }))).toBeNull()
  })

  it('returns null for a JSON array', () => {
    expect(parseStructuredResult(JSON.stringify([1, 2, 3]))).toBeNull()
  })
})

