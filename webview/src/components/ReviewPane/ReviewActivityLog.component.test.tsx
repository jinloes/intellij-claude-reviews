import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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
    const onCancel = vi.fn()
    render(<ReviewActivityLog activity={runningActivity()} onCancel={onCancel} />)

    expect(screen.getByRole('region', { name: 'Review generation activity' })).toBeVisible()
    expect(screen.getByText('Generating review')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Show details for Generating review' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.getAllByText('Reading files')).toHaveLength(1)
    expect(screen.getByRole('progressbar', { name: 'Review generation progress' })).toHaveAttribute(
      'aria-valuetext',
      'Reading files',
    )
    expect(screen.queryByRole('region', { name: 'Review activity entries' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show details for Generating review' }))
    expect(screen.getByText('Starting review')).toBeVisible()
    expect(screen.getByText('+0s')).toBeVisible()
    expect(screen.getByRole('region', { name: 'Review activity entries' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByText(/Provider output, private reasoning, arguments, and file contents are not displayed/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Stop generation' }))
    expect(onCancel).toHaveBeenCalledOnce()
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
    expect(screen.getByRole('button', { name: 'Show details for Review activity' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.queryByText('Review complete')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show details for Review activity' }))

    expect(screen.getByText('Review complete')).toBeVisible()
  })
})
