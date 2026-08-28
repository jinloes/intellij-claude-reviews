import { useEffect, useId, useRef, useState } from 'react'
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Loader2,
  XCircle,
} from 'lucide-react'
import {
  formatActivityDuration,
  formatReviewActivityLabel,
  type ReviewActivity,
  type ReviewActivityOutcome,
} from './reviewActivity'

interface ReviewActivityLogProps {
  activity: ReviewActivity
}

export function ReviewActivityLog({ activity }: ReviewActivityLogProps) {
  const [manualExpansion, setManualExpansion] = useState<{
    runId: number
    outcome: ReviewActivityOutcome
    expanded: boolean
  } | null>(null)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const listRef = useRef<HTMLOListElement>(null)
  const contentId = useId()
  const expanded = manualExpansion?.runId === activity.runId
    && manualExpansion.outcome === activity.outcome
    ? manualExpansion.expanded
    : activity.outcome === 'running'

  useEffect(() => {
    if (activity.outcome !== 'running') return
    const intervalId = window.setInterval(() => setNowMs(Date.now()), 1000)
    return () => window.clearInterval(intervalId)
  }, [activity.outcome, activity.runId])

  useEffect(() => {
    const list = listRef.current
    if (expanded && list) list.scrollTop = list.scrollHeight
  }, [activity.entries.length, expanded])

  if (activity.outcome === 'idle' || activity.entries.length === 0) return null

  const elapsedMs = Math.max(
    0,
    (activity.endedAtMs ?? nowMs) - (activity.startedAtMs ?? nowMs),
  )
  const summary = activitySummary(activity.outcome, elapsedMs)

  return (
    <section
      aria-label="Review generation activity"
      className="overflow-hidden rounded-lg border border-border bg-card"
    >
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-muted/40"
        aria-expanded={expanded}
        aria-controls={contentId}
        aria-label={`${expanded ? 'Hide' : 'Show'} review activity`}
        onClick={() => setManualExpansion({
          runId: activity.runId,
          outcome: activity.outcome,
          expanded: !expanded,
        })}
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-medium">Review activity</span>
          <span className="block truncate text-xs text-muted-foreground">
            {activity.outcome === 'running'
              ? formatReviewActivityLabel(
                activity.entries[activity.entries.length - 1]?.message ?? '',
              )
              : summary}
          </span>
        </span>
        {activity.outcome === 'running' && (
          <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
            {formatActivityDuration(elapsedMs)}
          </span>
        )}
      </button>

      {expanded && (
        <div id={contentId} className="border-t border-border px-3 py-2">
          <ol ref={listRef} className="max-h-48 space-y-2 overflow-y-auto">
            {activity.entries.map((entry, index) => {
              const isLast = index === activity.entries.length - 1
              const isCurrent = activity.outcome === 'running' && isLast
              return (
                <li key={`${entry.startedAtMs}-${index}`} className="flex items-start gap-2 text-xs">
                  <ActivityIcon
                    outcome={isLast ? activity.outcome : 'completed'}
                    active={isCurrent}
                  />
                  <span className="min-w-0 flex-1 break-words">
                    {formatReviewActivityLabel(entry.message)}
                  </span>
                  <time className="shrink-0 tabular-nums text-muted-foreground">
                    +{formatActivityDuration(
                      entry.startedAtMs - (activity.startedAtMs ?? entry.startedAtMs),
                    )}
                  </time>
                </li>
              )
            })}
          </ol>
          <p className="mt-2 border-t border-border pt-2 text-[11px] text-muted-foreground">
            Shows lifecycle and tool names only. Private reasoning, arguments, and file contents are not displayed.
          </p>
        </div>
      )}
    </section>
  )
}

function ActivityIcon({
  outcome,
  active,
}: {
  outcome: ReviewActivityOutcome
  active: boolean
}) {
  if (active) {
    return <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin text-primary" />
  }
  if (outcome === 'failed') {
    return <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-status-changes" />
  }
  if (outcome === 'cancelled') {
    return <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" />
  }
  return <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-status-approve" />
}

function activitySummary(outcome: ReviewActivityOutcome, elapsedMs: number): string {
  const duration = formatActivityDuration(elapsedMs)
  if (outcome === 'completed') return `Completed in ${duration}`
  if (outcome === 'failed') return `Failed after ${duration}`
  if (outcome === 'cancelled') return `Cancelled after ${duration}`
  return `Running for ${duration}`
}
