import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  appendReviewActivity,
  emptyReviewActivity,
  finishReviewActivity,
  startReviewActivity,
} from './reviewActivity'
import { ReviewActivityLog } from './ReviewActivityLog'

function runningActivity() {
  const started = startReviewActivity(emptyReviewActivity(), 'Starting review…', 1_000)
  return appendReviewActivity(started, 'read_file', 2_000)
}

describe('ReviewActivityLog', () => {
  it('shows chronological safe activity while a review is running', () => {
    render(<ReviewActivityLog activity={runningActivity()} />)

    expect(screen.getByRole('region', { name: 'Review generation activity' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Hide review activity' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(screen.getByText('Starting review')).toBeVisible()
    expect(screen.getAllByText('Reading files')).toHaveLength(2)
    expect(screen.getByText('+1s')).toBeVisible()
    expect(screen.getByText(/Private reasoning, arguments, and file contents are not displayed/)).toBeVisible()
  })

  it('collapses after completion and remains available for inspection', () => {
    const running = runningActivity()
    const { rerender } = render(<ReviewActivityLog activity={running} />)

    rerender(
      <ReviewActivityLog
        activity={finishReviewActivity(running, 'completed', 'Review complete', 4_000)}
      />,
    )

    expect(screen.getByText('Completed in 3s')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Show review activity' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.queryByText('Review complete')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show review activity' }))

    expect(screen.getByText('Review complete')).toBeVisible()
  })
})
