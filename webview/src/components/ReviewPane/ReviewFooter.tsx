import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  Check,
  ChevronDown,
  CloudUpload,
  Loader2,
  MessageSquare,
  RotateCcw,
  Trash2,
  XCircle,
} from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { useI18n } from '@/i18n/I18nProvider'
import type { ReviewQualityReport } from '@/lib/reviewQuality'
import type { PaneState, Verdict } from './reviewState'

interface ReviewFooterProps {
  state: PaneState
  saving: boolean
  autosaveDirty: boolean
  submitting: boolean
  deleting: boolean
  onSave: () => void
  onSubmit: (verdict: Verdict, comment?: string) => void
  onRegenerate: () => void
  onDelete: () => void
  onRunQualityCheck: () => void
  inlineCommentCount: number
  orphanCommentCount: number
  summary: string
  qualityReport: ReviewQualityReport | null
  diffUnavailable: boolean
}

export function ReviewFooter({
  state,
  saving,
  autosaveDirty,
  submitting,
  deleting,
  onSave,
  onSubmit,
  onRegenerate,
  onDelete,
  onRunQualityCheck,
  inlineCommentCount,
  orphanCommentCount,
  summary,
  qualityReport,
  diffUnavailable,
}: ReviewFooterProps) {
  if (state.kind === 'generating') return null

  if (state.kind === 'draftPresent' || state.kind === 'reviewUnsaved') {
    const busy = saving || submitting || deleting
    return (
      <div className="shrink-0 flex flex-wrap items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        {state.kind === 'draftPresent' ? (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs">
                <RotateCcw className="w-3.5 h-3.5" />
                Regenerate
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Regenerate review?</AlertDialogTitle>
                <AlertDialogDescription>
                  The current draft will be discarded and a new review generated from scratch.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction onClick={onRegenerate}>Regenerate</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : (
          <Button variant="ghost" size="sm" disabled={busy} className="gap-1.5 text-xs" onClick={onRegenerate}>
            <RotateCcw className="w-3.5 h-3.5" />
            Regenerate
          </Button>
        )}

        <div className="hidden flex-1 sm:block" />

        <div className="flex items-center gap-2">
          <span className="hidden text-[11px] text-muted-foreground lg:inline">
            Scans trust risks before submit.
          </span>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button variant="outline" size="sm" disabled={busy} className="gap-1.5 text-xs" onClick={onRunQualityCheck}>
                <Check className="w-3.5 h-3.5" />
                Quality Check
              </Button>
            </TooltipTrigger>
            <TooltipContent side="top" className="max-w-xs text-xs leading-relaxed">
              Checks for outdated anchors, high-risk low-evidence comments, and missing rationale, then offers one-click
              repairs.
            </TooltipContent>
          </Tooltip>
        </div>

        {state.kind === 'draftPresent' && (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="ghost" size="sm" disabled={deleting} className="gap-1.5 text-xs text-destructive hover:text-destructive">
                {deleting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                Delete
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete draft review?</AlertDialogTitle>
                <AlertDialogDescription>
                  This removes the pending review from GitHub permanently.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={onDelete}
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  Delete
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}

        <div className="ml-auto flex w-full shrink-0 items-center justify-end gap-2 sm:w-auto">
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="secondary"
                size="sm"
                onClick={onSave}
                disabled={saving || submitting || deleting || (!autosaveDirty && state.kind === 'draftPresent')}
                className="gap-1.5 text-xs"
              >
                {saving ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : autosaveDirty ? (
                  <CloudUpload className="w-3.5 h-3.5" />
                ) : (
                  <Check className="w-3.5 h-3.5" />
                )}
                {saving ? 'Saving…' : autosaveDirty ? 'Save now' : 'Saved'}
              </Button>
            </TooltipTrigger>
            <TooltipContent side="top" className="max-w-xs text-xs leading-relaxed">
              Changes save to the GitHub draft automatically. Click to save right now.
            </TooltipContent>
          </Tooltip>

          <SubmitSplitButton
            verdict={state.result.verdict}
            onSubmit={onSubmit}
            submitting={submitting}
            disabled={saving || deleting}
            inlineCommentCount={inlineCommentCount}
            orphanCommentCount={orphanCommentCount}
            summary={summary}
            qualityReport={qualityReport}
            diffUnavailable={diffUnavailable}
          />
        </div>
      </div>
    )
  }

  if (state.kind === 'saveError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <Button variant="secondary" size="sm" onClick={onSave} disabled={saving || submitting} className="gap-1.5 text-xs">
          {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CloudUpload className="w-3.5 h-3.5" />}
          {saving ? 'Saving…' : 'Retry Save'}
        </Button>
        <SubmitSplitButton
          verdict={state.result ? state.result.verdict : 'APPROVE'}
          onSubmit={onSubmit}
          submitting={submitting}
          disabled={saving}
          inlineCommentCount={inlineCommentCount}
          orphanCommentCount={orphanCommentCount}
          summary={summary}
          qualityReport={qualityReport}
          diffUnavailable={diffUnavailable}
        />
      </div>
    )
  }

  if (state.kind === 'submitError') {
    return (
      <div className="shrink-0 flex items-center gap-2 px-4 py-2.5 border-t border-border bg-card">
        <SubmitSplitButton
          verdict={state.result ? state.result.verdict : 'APPROVE'}
          onSubmit={onSubmit}
          submitting={submitting}
          disabled={false}
          inlineCommentCount={inlineCommentCount}
          orphanCommentCount={orphanCommentCount}
          summary={summary}
          qualityReport={qualityReport}
          diffUnavailable={diffUnavailable}
        />
      </div>
    )
  }

  return null
}

function SubmitSplitButton({
  verdict,
  onSubmit,
  submitting,
  disabled,
  inlineCommentCount,
  orphanCommentCount,
  summary,
  qualityReport,
  diffUnavailable,
}: {
  verdict: Verdict
  onSubmit: (verdict: Verdict, comment?: string) => void
  submitting: boolean
  disabled: boolean
  inlineCommentCount: number
  orphanCommentCount: number
  summary: string
  qualityReport: ReviewQualityReport | null
  diffUnavailable: boolean
}) {
  const [selectedVerdict, setSelectedVerdict] = useState(verdict)
  const [confirming, setConfirming] = useState<Verdict | null>(null)
  const [comment, setComment] = useState('')
  const [risksAcknowledged, setRisksAcknowledged] = useState(false)
  const pendingMenuVerdictRef = useRef<Verdict | null>(null)
  const t = useI18n()
  const qualityRiskCount = qualityReport?.issues.reduce((count, issue) => count + issue.count, 0) ?? 0
  const riskCount = qualityRiskCount + (diffUnavailable ? 1 : 0)
  const riskKey = qualityReport?.issues.map((issue) => `${issue.id}:${issue.count}`).join('|') ?? ''

  useEffect(() => setSelectedVerdict(verdict), [verdict])
  useEffect(() => setRisksAcknowledged(false), [riskKey, diffUnavailable, confirming])

  const icons: Record<Verdict, ReactNode> = {
    APPROVE: <Check className="w-3.5 h-3.5" />,
    REQUEST_CHANGES: <XCircle className="w-3.5 h-3.5" />,
    COMMENT: <MessageSquare className="w-3.5 h-3.5" />,
  }
  const labels: Record<Verdict, string> = {
    APPROVE: 'Approve',
    REQUEST_CHANGES: 'Request Changes',
    COMMENT: 'Comment',
  }
  const variants: Record<Verdict, 'default' | 'destructive' | 'secondary'> = {
    APPROVE: 'default',
    REQUEST_CHANGES: 'destructive',
    COMMENT: 'secondary',
  }

  const others = (['COMMENT', 'APPROVE', 'REQUEST_CHANGES'] as Verdict[])
    .filter((candidate) => candidate !== selectedVerdict)

  function requestMenuConfirmation(nextVerdict: Verdict) {
    setSelectedVerdict(nextVerdict)
    pendingMenuVerdictRef.current = nextVerdict
  }

  function openPendingMenuConfirmation(event: Event) {
    const nextVerdict = pendingMenuVerdictRef.current
    if (!nextVerdict) return
    event.preventDefault()
    pendingMenuVerdictRef.current = null
    setConfirming(nextVerdict)
  }

  return (
    <div className="flex items-stretch rounded-md overflow-hidden">
      <Button
        variant={variants[selectedVerdict]}
        size="sm"
        className="text-xs rounded-r-none gap-1.5 border-r border-white/20"
        onClick={() => setConfirming(selectedVerdict)}
        disabled={submitting || disabled}
      >
        {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : icons[selectedVerdict]}
        {submitting ? 'Submitting…' : labels[selectedVerdict]}
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant={variants[selectedVerdict]}
            size="sm"
            className="text-xs rounded-l-none px-1.5"
            disabled={submitting || disabled}
            aria-label="More submit options"
          >
            <ChevronDown className="w-3.5 h-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          className="w-48"
          onCloseAutoFocus={openPendingMenuConfirmation}
        >
          {others.filter((candidate) => candidate !== 'REQUEST_CHANGES').map((candidate) => (
            <DropdownMenuItem
              key={candidate}
              onSelect={() => requestMenuConfirmation(candidate)}
              className={candidate === 'COMMENT'
                ? 'gap-2 text-xs cursor-pointer text-status-comment focus:text-status-comment'
                : 'gap-2 text-xs cursor-pointer'}
            >
              {icons[candidate]}
              {labels[candidate]}
            </DropdownMenuItem>
          ))}
          {others.includes('REQUEST_CHANGES') && <DropdownMenuSeparator />}
          {others.includes('REQUEST_CHANGES') && (
            <DropdownMenuItem
              onSelect={() => requestMenuConfirmation('REQUEST_CHANGES')}
              className="gap-2 text-xs cursor-pointer text-destructive focus:text-destructive"
            >
              {icons.REQUEST_CHANGES}
              {labels.REQUEST_CHANGES}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <AlertDialog open={confirming !== null} onOpenChange={(open) => !open && setConfirming(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Submit {confirming ? labels[confirming].toLowerCase() : 'review'}?
            </AlertDialogTitle>
            <AlertDialogDescription>
              This will publish the pending GitHub review with {inlineCommentCount} inline comment{inlineCommentCount === 1 ? '' : 's'}
              {orphanCommentCount > 0
                ? ` and ${orphanCommentCount} unanchored comment${orphanCommentCount === 1 ? '' : 's'} in the review body`
                : ''}.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {summary && (
            <div className="max-h-28 overflow-y-auto rounded border border-border bg-muted/30 p-2 text-xs text-muted-foreground">
              {summary}
            </div>
          )}
          {riskCount > 0 && (
            <div className="rounded border border-status-issue/50 bg-status-issue/10 p-3 text-xs">
              <p className="font-semibold">
                {riskCount} unresolved trust {riskCount === 1 ? 'risk' : 'risks'}
              </p>
              <ul className="mt-2 list-disc space-y-1 pl-5">
                {qualityReport?.issues.map((issue) => (
                  <li key={issue.id}>{issue.title}: {issue.count}. {issue.description}</li>
                ))}
                {diffUnavailable && (
                  <li>The diff could not be rendered. Review the raw diff before publishing.</li>
                )}
              </ul>
              <label className="mt-3 flex items-start gap-2">
                <input
                  type="checkbox"
                  checked={risksAcknowledged}
                  onChange={(event) => setRisksAcknowledged(event.target.checked)}
                />
                <span>{t('quality.acknowledge')}</span>
              </label>
            </div>
          )}
          <label htmlFor="final-review-body" className="text-sm font-medium">{t('review.finalBody')}</label>
          <textarea
            id="final-review-body"
            className="min-h-[72px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:border-ring"
            placeholder="Optional final review body…"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
          />
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setComment('')}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={submitting || disabled || (riskCount > 0 && !risksAcknowledged)}
              onClick={() => {
                if (confirming) onSubmit(confirming, comment.trim())
                setComment('')
                setConfirming(null)
              }}
              className={confirming === 'REQUEST_CHANGES'
                ? 'bg-destructive text-destructive-foreground hover:bg-destructive/90'
                : undefined}
            >
              Submit {confirming ? labels[confirming] : 'Review'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
