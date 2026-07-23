// Parses the structured JSON responses requested by `buildVerifyCommentPrompt`/
// `buildExampleFixPrompt` (see ReviewPane/verifyPrompt.ts) so the chat pane can render
// them as a card instead of dumping raw JSON text through the markdown renderer.

export type VerifyVerdict = 'valid' | 'invalid' | 'unclear'
export type VerifyAction = 'keep' | 'revise' | 'delete'

export interface VerifyResult {
  kind: 'verify'
  verdict: VerifyVerdict
  why: string
  action: VerifyAction
  replacementComment: string | null
}

export interface ExampleFixResult {
  kind: 'fix'
  approach: string[]
  examplePatch: string | null
  why: string
  risks: string[]
  testUpdates: string[]
  missingContext: string[]
}

export type StructuredResult = VerifyResult | ExampleFixResult

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => typeof v === 'string')
}

// LLMs sometimes wrap "return only JSON" responses in a fenced code block despite
// instructions not to. Strip a single leading/trailing fence (with an optional
// language tag) before attempting to parse, and otherwise take the content as-is.
function extractJsonCandidate(content: string): string {
  const trimmed = content.trim()
  const fenced = /^```(?:json)?\s*\n([\s\S]*?)\n?```$/i.exec(trimmed)
  return fenced ? fenced[1].trim() : trimmed
}

function parseVerifyResult(value: Record<string, unknown>): VerifyResult | null {
  const { verdict, why, action, replacementComment } = value
  if (
    typeof verdict !== 'string'
    || !['valid', 'invalid', 'unclear'].includes(verdict)
    || typeof why !== 'string'
    || typeof action !== 'string'
    || !['keep', 'revise', 'delete'].includes(action)
    || (replacementComment !== null && typeof replacementComment !== 'string')
  ) {
    return null
  }
  return {
    kind: 'verify',
    verdict: verdict as VerifyVerdict,
    why,
    action: action as VerifyAction,
    replacementComment: replacementComment ?? null,
  }
}

function parseExampleFixResult(value: Record<string, unknown>): ExampleFixResult | null {
  const { approach, examplePatch, why, risks, testUpdates, missingContext } = value
  if (
    !isStringArray(approach)
    || (examplePatch !== null && typeof examplePatch !== 'string')
    || typeof why !== 'string'
    || !isStringArray(risks)
    || !isStringArray(testUpdates)
    || !isStringArray(missingContext)
  ) {
    return null
  }
  return {
    kind: 'fix',
    approach,
    examplePatch: examplePatch ?? null,
    why,
    risks,
    testUpdates,
    missingContext,
  }
}

/**
 * Attempts to parse an assistant message as one of the structured JSON schemas
 * requested by the "Verify" / "Suggest fix" comment actions. Returns `null` for
 * ordinary free-form chat replies so they fall back to plain markdown rendering.
 */
export function parseStructuredResult(content: string): StructuredResult | null {
  const candidate = extractJsonCandidate(content)
  if (!candidate.startsWith('{') || !candidate.endsWith('}')) return null
  let value: unknown
  try {
    value = JSON.parse(candidate)
  } catch {
    return null
  }
  if (!isRecord(value)) return null
  return parseVerifyResult(value) ?? parseExampleFixResult(value)
}

