import { parseDiff } from 'react-diff-view'
import type { ChangeData, FileData, HunkData } from 'react-diff-view'
import type { LineComment } from '@/bridge/types'

const MAX_HUNK_CHANGES = 40
const EXCERPT_RADIUS = 6

export interface VerifyPrompt {
  question: string
  context: string
}

export interface ExampleFixPrompt {
  question: string
  context: string
}

function safeParse(diff: string): FileData[] {
  if (!diff) return []
  try {
    return parseDiff(diff)
  } catch {
    return []
  }
}

function newLineOf(change: ChangeData): number | undefined {
  if (change.type === 'insert') return change.lineNumber
  if (change.type === 'normal') return change.newLineNumber
  return undefined
}

function matchesPath(candidate: string | undefined, requested: string): boolean {
  if (!candidate) return false
  return candidate === requested || candidate.endsWith('/' + requested) || requested.endsWith('/' + candidate)
}

function findFile(files: FileData[], requestedPath: string): FileData | null {
  const matches = files.filter((file) => {
    const path = file.newPath !== '/dev/null' ? file.newPath : file.oldPath
    return matchesPath(path, requestedPath)
  })
  return matches.length === 1 ? matches[0] : null
}

function distanceToLine(hunk: HunkData, line: number): number {
  let best = Number.MAX_SAFE_INTEGER
  for (const change of hunk.changes) {
    const newLine = newLineOf(change)
    if (newLine === undefined) continue
    best = Math.min(best, Math.abs(newLine - line))
  }
  return best
}

function nearestChangeIndex(hunk: HunkData, line: number): number {
  let bestIdx = 0
  let bestDistance = Number.MAX_SAFE_INTEGER
  for (const [idx, change] of hunk.changes.entries()) {
    const newLine = newLineOf(change)
    if (newLine === undefined) continue
    const distance = Math.abs(newLine - line)
    if (distance < bestDistance) {
      bestDistance = distance
      bestIdx = idx
    }
  }
  return bestIdx
}

function renderHunkExcerpt(hunk: HunkData, targetLine: number): string {
  const targetIdx = nearestChangeIndex(hunk, targetLine)
  const fullHunk = hunk.changes.length <= MAX_HUNK_CHANGES
  const start = fullHunk ? 0 : Math.max(0, targetIdx - EXCERPT_RADIUS)
  const end = fullHunk ? hunk.changes.length : Math.min(hunk.changes.length, targetIdx + EXCERPT_RADIUS + 1)
  const lines = [hunk.content]
  if (start > 0) lines.push('...')
  lines.push(...hunk.changes.slice(start, end).map((change) => change.content))
  if (end < hunk.changes.length) lines.push('...')
  return lines.join('\n')
}

function extractRelevantDiffExcerpt(diff: string, comment: LineComment): string | null {
  const file = findFile(safeParse(diff), comment.file)
  if (!file) return null
  const ranked = [...file.hunks]
    .map((hunk) => ({ hunk, distance: distanceToLine(hunk, comment.line) }))
    .filter((entry) => entry.distance < Number.MAX_SAFE_INTEGER)
    .sort((left, right) => left.distance - right.distance)
  const best = ranked[0]?.hunk
  if (!best) return null
  return renderHunkExcerpt(best, comment.line)
}

function buildCommentContext(comment: LineComment, diff: string): string {
  const excerpt = extractRelevantDiffExcerpt(diff, comment)
  return [
    'File: ' + comment.file,
    'Line: ' + comment.line,
    'Type: ' + comment.type,
    comment.severity ? `Severity: ${comment.severity}` : null,
    comment.category ? `Category: ${comment.category}` : null,
    comment.confidence ? `Original confidence: ${comment.confidence}` : null,
    comment.rationale ? `Original rationale: ${comment.rationale}` : null,
    `Comment text: ${comment.body}`,
    'Relevant diff excerpt:',
    excerpt ?? 'Unavailable — the changed hunk for this comment could not be extracted from the current diff context.',
  ].filter((line): line is string => Boolean(line)).join('\n')
}

export function buildVerifyCommentPrompt(comment: LineComment, diff: string): VerifyPrompt {
  return {
    question:
      'Verify whether this draft review comment is supported by the provided context.\n\n' +
      'Respond with:\n' +
      '- Verdict: valid | invalid | unclear\n' +
      '- Why: cite the changed lines or explain why the context does not support the comment\n' +
      '- Action: keep | revise | delete\n' +
      'If you choose revise, provide one replacement comment. Do not rely on code outside the provided context.',
    context: ['Draft review comment under verification:', buildCommentContext(comment, diff)].join('\n'),
  }
}

export function buildExampleFixPrompt(comment: LineComment, diff: string): ExampleFixPrompt {
  return {
    question:
      'Generate an example code change that addresses this draft review comment using only the provided context.\n\n' +
      'Respond with:\n' +
      '- Approach: 1-2 concise bullets\n' +
      '- Example patch: one fenced code block\n' +
      '- Why this helps: cite the changed lines\n' +
      '- Risks/assumptions\n' +
      '- Test updates (if applicable)\n' +
      'If the context is insufficient, say so explicitly instead of guessing and list what is missing.',
    context: ['Draft review comment requiring an example fix:', buildCommentContext(comment, diff)].join('\n'),
  }
}


