import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import type { PR } from '../../bridge/types'

interface Props {
  pr: PR
  spotlighted: boolean
}

interface StatusBadgeProps {
  children: string
  className?: string
}

function StatusBadge({ children, className }: StatusBadgeProps) {
  return (
    <Badge
      variant="outline"
      className={cn('h-auto whitespace-normal px-1.5 py-0.5 text-xs font-medium leading-4', className)}
    >
      {children}
    </Badge>
  )
}

export function PRStatusBadges({ pr, spotlighted }: Props) {
  return (
    <div className="flex flex-wrap items-center gap-1" aria-label="Pull request status">
      {pr.isDraft && (
        <StatusBadge className="border-muted-foreground/40 text-muted-foreground">Draft PR</StatusBadge>
      )}
      {pr.hasReviewDraft && (
        <StatusBadge className="border-status-comment/50 text-[hsl(var(--status-comment))]">Review draft</StatusBadge>
      )}
      {spotlighted && (
        <StatusBadge className="border-status-note/50 text-[hsl(var(--status-note))]">From notification</StatusBadge>
      )}
      {pr.reviewStatus === 'REVIEWED' && (
        <StatusBadge className="border-status-approve/50 text-[hsl(var(--status-approve))]">Reviewed</StatusBadge>
      )}
      {pr.reviewStatus === 'UPDATED_SINCE_REVIEW' && (
        <StatusBadge className="border-status-suggestion/60 text-[hsl(var(--status-suggestion))]">
          Updated since your review
        </StatusBadge>
      )}
    </div>
  )
}
