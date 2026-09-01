import type { PRListStatus } from '../../bridge/types'

function listNotices(
  status: PRListStatus | null,
  notificationPinned: boolean,
  notificationReviewStatusUnavailable: boolean,
): string[] {
  const notices: string[] = []
  if (status?.searchScope === 'currentRepo' && !status.currentRepo) {
    notices.push('Current repo was not detected; showing authored PRs.')
  }
  if (status?.limited) {
    notices.push(`Showing the first ${status.resultLimit} matching pull requests.`)
  }
  if (notificationPinned) {
    notices.push('A pull request opened from a notification is pinned outside the current scope.')
  }
  if (status?.reviewStatusAvailable === false || notificationReviewStatusUnavailable) {
    notices.push('Review status is unavailable. Refresh to try again.')
  }
  return notices
}

export function PRListNotices({
  status,
  notificationPinned,
  notificationReviewStatusUnavailable,
}: {
  status: PRListStatus | null
  notificationPinned: boolean
  notificationReviewStatusUnavailable: boolean
}) {
  const notices = listNotices(
    status,
    notificationPinned,
    notificationReviewStatusUnavailable,
  )
  if (notices.length === 0) return null

  return (
    <div
      className="space-y-0.5 rounded border border-border bg-muted/25 px-2 py-1.5 text-xs leading-relaxed text-muted-foreground"
      aria-live="polite"
      data-testid="pr-list-notices"
    >
      {notices.map((notice) => <p key={notice}>{notice}</p>)}
    </div>
  )
}
