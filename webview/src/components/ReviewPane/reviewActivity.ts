export type ReviewActivityOutcome = 'idle' | 'running' | 'completed' | 'failed' | 'cancelled'

export interface ReviewActivityEntry {
  message: string
  startedAtMs: number
  endedAtMs?: number
}

export interface ReviewActivity {
  runId: number
  outcome: ReviewActivityOutcome
  startedAtMs: number | null
  endedAtMs: number | null
  entries: ReviewActivityEntry[]
}

type TerminalOutcome = Exclude<ReviewActivityOutcome, 'idle' | 'running'>

const TOOL_LABELS: Array<[RegExp, string]> = [
  [/(^|[_:/.-])(read|view|open)(_file)?($|[_:/.-])|readfile/i, 'Reading files'],
  [/(^|[_:/.-])(grep|search|find|glob|rg)($|[_:/.-])/i, 'Searching the worktree'],
  [/(^|[_:/.-])(list|ls)($|[_:/.-])/i, 'Listing files'],
  [/(^|[_:/.-])(diff|compare)($|[_:/.-])/i, 'Inspecting the diff'],
]

export function emptyReviewActivity(): ReviewActivity {
  return {
    runId: 0,
    outcome: 'idle',
    startedAtMs: null,
    endedAtMs: null,
    entries: [],
  }
}

export function startReviewActivity(
  current: ReviewActivity,
  message: string,
  nowMs: number,
): ReviewActivity {
  const normalized = normalizeMessage(message)
  return {
    runId: current.runId + 1,
    outcome: 'running',
    startedAtMs: nowMs,
    endedAtMs: null,
    entries: normalized ? [{ message: normalized, startedAtMs: nowMs }] : [],
  }
}

export function appendReviewActivity(
  current: ReviewActivity,
  message: string,
  nowMs: number,
): ReviewActivity {
  const normalized = normalizeMessage(message)
  if (!normalized) return current
  if (current.outcome === 'idle') return startReviewActivity(current, normalized, nowMs)
  if (current.outcome !== 'running') return current

  return {
    ...current,
    entries: [
      ...finishCurrentEntry(current.entries, nowMs),
      { message: normalized, startedAtMs: nowMs },
    ],
  }
}

export function finishReviewActivity(
  current: ReviewActivity,
  outcome: TerminalOutcome,
  message: string,
  nowMs: number,
): ReviewActivity {
  if (current.outcome !== 'running') return current

  const normalized = normalizeMessage(message)
  const entries = finishCurrentEntry(current.entries, nowMs)
  return {
    ...current,
    outcome,
    endedAtMs: nowMs,
    entries: normalized
      ? [...entries, { message: normalized, startedAtMs: nowMs, endedAtMs: nowMs }]
      : entries,
  }
}

export function formatReviewActivityLabel(message: string): string {
  const normalized = normalizeMessage(message).replace(/[.…]+$/u, '')
  for (const [pattern, label] of TOOL_LABELS) {
    if (pattern.test(normalized)) return label
  }
  if (!normalized.includes(' ') && /^[\w:./-]+$/.test(normalized)) {
    const nameParts = normalized.split(/[:/]/)
    const name = nameParts[nameParts.length - 1] || normalized
    return `Using ${name.replace(/[-_.]+/g, ' ')}`
  }
  return normalized || 'Working'
}

export function formatActivityDuration(durationMs: number): string {
  const seconds = Math.max(0, Math.floor(durationMs / 1000))
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return remainingSeconds === 0 ? `${minutes}m` : `${minutes}m ${remainingSeconds}s`
}

function finishCurrentEntry(
  entries: ReviewActivityEntry[],
  nowMs: number,
): ReviewActivityEntry[] {
  if (entries.length === 0) return entries
  const last = entries[entries.length - 1]
  if (last?.endedAtMs !== undefined) return entries
  return [
    ...entries.slice(0, -1),
    {
      ...last,
      endedAtMs: nowMs,
    },
  ]
}

function normalizeMessage(message: string): string {
  return message.trim()
}
