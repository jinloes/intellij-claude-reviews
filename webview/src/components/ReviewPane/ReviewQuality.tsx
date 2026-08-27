import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ReviewQualityAction, ReviewQualityReport } from '@/lib/reviewQuality'

export function QualityCheckBadge({ count, onReview }: { count: number; onReview: () => void }) {
  return (
    <button
      type="button"
      onClick={onReview}
      className="flex w-full items-center gap-2 rounded border border-status-issue/50 bg-status-issue/10 px-3 py-2 text-left text-xs transition-colors hover:bg-status-issue/20"
    >
      <AlertTriangle className="w-3.5 h-3.5 shrink-0 text-status-issue" />
      <span className="font-semibold text-foreground">
        {count} trust {count === 1 ? 'risk' : 'risks'} detected
      </span>
      <span className="text-muted-foreground">— scanned automatically</span>
      <span className="ml-auto font-medium text-foreground">Review</span>
    </button>
  )
}

export function ReviewQualityCheckCard({
  report,
  onApplyRepair,
  onCollapse,
}: {
  report: ReviewQualityReport
  onApplyRepair: (action: ReviewQualityAction) => void
  onCollapse: () => void
}) {
  const riskCount = report.issues.reduce((count, issue) => count + issue.count, 0)

  return (
    <div className="rounded border border-border bg-muted/20 px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs font-semibold">Review Quality Check</span>
        <span className="text-xs text-status-issue">
          {riskCount} unresolved {riskCount === 1 ? 'risk' : 'risks'}
        </span>
        <Button variant="ghost" size="sm" className="ml-auto h-6 px-2 text-[11px]" onClick={onCollapse}>
          Hide
        </Button>
      </div>
      {report.issues.length === 0 ? (
        <p className="mt-1 text-xs text-status-approve">No major trust issues detected in this draft.</p>
      ) : (
        <ul className="mt-2 space-y-1">
          {report.issues.map((issue) => (
            <li key={issue.id} className="text-xs text-muted-foreground">
              <span className="text-foreground">{issue.title}</span> · {issue.count} · {issue.description}
            </li>
          ))}
        </ul>
      )}
      {report.suggestions.length > 0 && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {report.suggestions.includes('removeUnanchored') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('removeUnanchored')}>
              Remove unanchored comments
            </Button>
          )}
          {report.suggestions.includes('dropMissingRationale') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('dropMissingRationale')}>
              Drop comments without rationale
            </Button>
          )}
          {report.suggestions.includes('downgradeHighRisk') && (
            <Button variant="outline" size="sm" className="text-xs" onClick={() => onApplyRepair('downgradeHighRisk')}>
              Downgrade low-evidence issues
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
