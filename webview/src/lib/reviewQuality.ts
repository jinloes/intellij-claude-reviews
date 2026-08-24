import { validateComments } from '@/lib/validateComments'
import type { LineComment, ReviewResult } from '../bridge/types'

export type ReviewQualityAction = 'removeUnanchored' | 'dropMissingRationale' | 'downgradeHighRisk'

export interface ReviewQualityIssue {
  id: 'hallucinationRisk' | 'outdatedAnchors' | 'missingRationale'
  title: string
  severity: 'high' | 'medium' | 'low'
  count: number
  description: string
}

export interface ReviewQualityReport {
  issues: ReviewQualityIssue[]
  suggestions: ReviewQualityAction[]
  orphanComments: LineComment[]
  riskyComments: LineComment[]
  missingRationaleComments: LineComment[]
}

interface DiffFileStat {
  path: string
  changedLines: number
  diff: string
}

function parseDiffFileStats(diff: string): DiffFileStat[] {
  const starts = [...diff.matchAll(/^diff --git /gm)].map((match) => match.index)
  const sections = starts.length === 0
    ? [diff]
    : starts.map((start, index) => diff.slice(start, starts[index + 1] ?? diff.length))

  return sections.flatMap((section) => {
    const rows = section.split(/\r?\n/)
    const newPath = rows.find((row) => row.startsWith('+++ '))?.slice(4).trim()
    const oldPath = rows.find((row) => row.startsWith('--- '))?.slice(4).trim()
    const headerPath = rows[0]?.match(/\s"?b\/(.+?)"?$/)?.[1]
    const path = newPath?.startsWith('b/')
      ? newPath.slice(2)
      : oldPath?.startsWith('a/')
        ? oldPath.slice(2)
        : headerPath
    if (!path) return []

    const changedLines = rows.filter(
      (row) => (row.startsWith('+') && !row.startsWith('+++'))
        || (row.startsWith('-') && !row.startsWith('---')),
    ).length
    return [{ path, changedLines, diff: section }]
  })
}

/**
 * Flags a high-severity finding the model did not actually back up.
 *
 * Evidence is judged by what the model reported, not by how much it typed. The previous rule
 * treated any issue whose body was under 35 characters as unsupported, which is a proxy for
 * "unjustified" that fails in both directions: a precise one-line finding ("Deadlock: B locks A
 * here") was flagged, while a verbose but baseless paragraph passed. `confidence` and `rationale`
 * are the real signals and are now populated on every comment, so the character count is gone.
 */
function isHighRiskLowEvidence(comment: LineComment): boolean {
  const severity = comment.severity ?? 'minor'
  if (severity !== 'blocker' && severity !== 'major') return false
  // A model that rates its own high-severity claim "low" has told us it is unsure.
  if ((comment.confidence ?? 'medium') === 'low') return true
  // Otherwise a serious claim needs stated evidence — the rationale field exists for exactly this.
  return (comment.rationale ?? '').trim().length === 0
}

export function runReviewQualityCheck(result: ReviewResult, validationDiff: string): ReviewQualityReport {
  const { orphans } = validateComments(validationDiff, result.lineComments)
  const isOrphan = (comment: LineComment) => orphans.some((orphan) => matchesComment(orphan, comment))

  const riskyComments = result.lineComments.filter(
    (comment) => !isOrphan(comment) && isHighRiskLowEvidence(comment),
  )
  const missingRationaleComments = result.lineComments.filter((comment) => {
    if (isOrphan(comment) || riskyComments.some((risky) => matchesComment(risky, comment))) return false
    if (comment.type === 'note') return false
    const confidence = comment.confidence ?? 'medium'
    if (confidence === 'low') return false
    return !comment.rationale?.trim()
  })

  const issues: ReviewQualityIssue[] = []
  if (riskyComments.length > 0) {
    issues.push({
      id: 'hallucinationRisk',
      title: 'Potential hallucination risk',
      severity: 'high',
      count: riskyComments.length,
      description: 'High-severity comments with weak evidence (low confidence or missing rationale).',
    })
  }
  if (orphans.length > 0) {
    issues.push({
      id: 'outdatedAnchors',
      title: 'Outdated line anchors',
      severity: 'high',
      count: orphans.length,
      description: 'Comments no longer map to valid PR hunks and will not be attached inline.',
    })
  }
  if (missingRationaleComments.length > 0) {
    issues.push({
      id: 'missingRationale',
      title: 'Missing rationale',
      severity: 'medium',
      count: missingRationaleComments.length,
      description: 'Medium/high-confidence comments should include rationale for reviewer trust.',
    })
  }

  const suggestions: ReviewQualityAction[] = []
  if (orphans.length > 0) suggestions.push('removeUnanchored')
  if (missingRationaleComments.length > 0) suggestions.push('dropMissingRationale')
  if (riskyComments.length > 0) suggestions.push('downgradeHighRisk')

  return {
    issues,
    suggestions,
    orphanComments: orphans,
    riskyComments,
    missingRationaleComments,
  }
}

function matchesComment(a: LineComment, b: LineComment): boolean {
  return a.file === b.file && a.line === b.line && a.body === b.body && a.type === b.type
}

export function applyReviewQualityRepairs(
  result: ReviewResult,
  report: ReviewQualityReport,
  repairs: ReviewQualityAction[],
): ReviewResult {
  const repairSet = new Set(repairs)
  const nextComments = result.lineComments.map((comment) => ({ ...comment }))

  let repaired = nextComments

  if (repairSet.has('removeUnanchored')) {
    repaired = repaired.filter((comment) => !report.orphanComments.some((orphan) => matchesComment(orphan, comment)))
  }

  // Deliberately drops rather than fabricates. An earlier version filled the gap with
  // "Evidence needs verification in <file>:<line>." — that cleared the warning and raised the
  // score while adding no evidence, which is worse than leaving the comment flagged. A comment
  // whose evidence the model would not state is either re-asked for or removed.
  if (repairSet.has('dropMissingRationale')) {
    repaired = repaired.filter(
      (comment) => !report.missingRationaleComments.some((target) => matchesComment(target, comment)),
    )
  }

  if (repairSet.has('downgradeHighRisk')) {
    repaired = repaired.map((comment) => {
      const risky = report.riskyComments.some((target) => matchesComment(target, comment))
      if (!risky) return comment
      return {
        ...comment,
        type: comment.type === 'issue' ? 'suggestion' : comment.type,
        severity:
          comment.severity === 'blocker' || comment.severity === 'major'
            ? 'minor'
            : comment.severity,
      }
    })
  }

  return { ...result, lineComments: repaired }
}

export interface DiffBatch {
  label: string
  files: string[]
  diff: string
}

export function buildDiffBatches(
  validationDiff: string,
  maxFilesPerBatch = 6,
  maxDiffCharsPerBatch = 220_000,
): DiffBatch[] {
  if (maxFilesPerBatch < 1 || maxDiffCharsPerBatch < 1) {
    throw new RangeError('Diff batch limits must be positive.')
  }
  const stats = parseDiffFileStats(validationDiff)
  if (stats.length === 0) return []

  const sorted = [...stats].sort((a, b) => b.changedLines - a.changedLines)
  const batches: DiffBatch[] = []
  let pending: DiffFileStat[] = []
  let pendingChars = 0

  function appendBatch(slice: DiffFileStat[]) {
    const files = slice.map((item) => item.path)
    const changedLines = slice.reduce((sum, item) => sum + item.changedLines, 0)
    batches.push({
      label: `Batch ${batches.length + 1} (${files.length} files, ${changedLines} changed lines)`,
      files,
      diff: `${slice.map((item) => item.diff.trimEnd()).join('\n')}\n`,
    })
  }

  for (const item of sorted) {
    const exceedsFileLimit = pending.length >= maxFilesPerBatch
    const exceedsCharacterLimit = pendingChars + item.diff.length > maxDiffCharsPerBatch
    if (pending.length > 0 && (exceedsFileLimit || exceedsCharacterLimit)) {
      appendBatch(pending)
      pending = []
      pendingChars = 0
    }
    pending.push(item)
    pendingChars += item.diff.length
  }
  if (pending.length > 0) appendBatch(pending)

  return batches
}

export function estimateFileConfidence(comments: LineComment[], files: string[]): number {
  const fileSet = new Set(files)
  const matched = comments.filter((comment) => fileSet.has(comment.file))
  if (matched.length === 0) return 0.55

  const score = matched.reduce((sum, comment) => {
    switch (comment.confidence) {
      case 'high':
        return sum + 1
      case 'medium':
        return sum + 0.66
      case 'low':
        return sum + 0.33
      default:
        return sum + 0.5
    }
  }, 0)

  return Math.min(1, Math.max(0, score / matched.length))
}
