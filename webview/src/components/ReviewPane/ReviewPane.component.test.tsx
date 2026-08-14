import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR } from '../../bridge/types'
import { ReviewPane, type ReviewPaneHandle } from './ReviewPane'

const pr: PR = {
  number: 42,
  title: 'Avoid duplicate approvals',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/42',
  isDraft: false,
  hasReviewDraft: true,
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('ReviewPane did not register the JCEF bridge handler')
  handler({ protocolVersion: 1, ...message })
}

afterEach(() => {
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('ReviewPane review submission', () => {
  it('discards a pending edit without saving it during PR cleanup', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    const ref = createRef<ReviewPaneHandle>()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { rerender } = render(<ReviewPane ref={ref} pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: {
          summary: 'Saved review.',
          verdict: 'COMMENT',
          lineComments: [{ file: 'missing.ts', line: 1, type: 'issue', body: 'Remove this.' }],
        },
        diff: '',
        validationDiff: '',
      })
    })
    await user.click(screen.getByRole('button', { name: 'Delete unanchored comment' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))

    expect(ref.current?.discardPendingChanges()).toBe(true)
    rerender(<ReviewPane ref={ref} pr={{ ...pr, number: 43 }} />)

    const saves = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string })
      .filter((message) => message.type === 'saveDraft')
    expect(saves).toEqual([])
  })

  it('refuses to discard after a save has already been sent', async () => {
    const cefQuery = vi.fn()
    const ref = createRef<ReviewPaneHandle>()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane ref={ref} pr={pr} />)

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: { summary: 'Generated review.', verdict: 'COMMENT', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })
    await waitFor(() => expect(cefQuery).toHaveBeenCalled())

    expect(ref.current?.discardPendingChanges()).toBe(false)
  })

  it('keeps the review visible and offers operation-specific recovery after delete fails', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Keep this review visible.', verdict: 'COMMENT', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))
    act(() => {
      hostMessage({ type: 'draftDeleteError', prKey: 'acme/widget#42', message: 'Delete failed.' })
    })

    expect(screen.getByText('Keep this review visible.')).toBeInTheDocument()
    expect(screen.getByText('Delete failed.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry delete' }))
    const deletes = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string })
      .filter((message) => message.type === 'deleteDraft')
    expect(deletes).toHaveLength(2)

    act(() => {
      hostMessage({ type: 'draftDeleteError', prKey: 'acme/widget#42', message: 'Still failed.' })
    })
    await user.click(screen.getByRole('button', { name: 'Keep draft' }))
    expect(screen.queryByText('Still failed.')).not.toBeInTheDocument()
    expect(screen.getByText('Keep this review visible.')).toBeInTheDocument()
  })

  it('requires acknowledgement before submitting when the diff cannot be rendered', async () => {
    const user = userEvent.setup()
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Review summary.', verdict: 'COMMENT', lineComments: [] },
        diff: 'not a unified diff',
        validationDiff: 'not a unified diff',
      })
    })
    await user.click(screen.getByRole('button', { name: 'Comment' }))

    expect(screen.getByText('The diff could not be rendered. Review the raw diff before publishing.')).toBeInTheDocument()
    const submit = screen.getByRole('button', { name: 'Submit Comment' })
    expect(submit).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    expect(submit).toBeEnabled()
  })

  it('keeps the selected submit option as the primary action', async () => {
    const user = userEvent.setup()
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Changes needed.', verdict: 'REQUEST_CHANGES', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    expect(screen.getByRole('button', { name: 'Request Changes' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'More submit options' }))
    await user.click(screen.getByRole('menuitem', { name: 'Approve' }))
    await user.click(await screen.findByRole('button', { name: 'Cancel' }))

    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Request Changes' })).not.toBeInTheDocument()
  })

  it('submits Comment from an Approve review split menu', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Looks good.', verdict: 'APPROVE', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    await user.click(screen.getByRole('button', { name: 'More submit options' }))
    const commentOption = screen.getByRole('menuitem', { name: 'Comment' })
    expect(commentOption).not.toHaveAttribute('data-disabled')
    await user.click(commentOption)
    await user.click(await screen.findByRole('button', { name: 'Submit Comment' }))

    const outgoing = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string; verdict?: string })
      .filter((message) => message.type === 'submitReview')
    expect(outgoing).toEqual([expect.objectContaining({ verdict: 'COMMENT' })])
  })

  it('sends one submitReview message when the confirmation action is clicked twice before rerender', () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: { summary: 'Looks good.', verdict: 'APPROVE', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const confirm = screen.getByRole('button', { name: 'Submit Approve' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    const outgoing = cefQuery.mock.calls
      .map(([arg]) => JSON.parse(arg.request) as { type: string })
      .filter((message) => message.type === 'submitReview')
    expect(outgoing).toHaveLength(1)
  })

  it('keeps a review dirty until the matching save acknowledgement', async () => {
    const cefQuery = vi.fn()
    const onDirtyStateChange = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} onDirtyStateChange={onDirtyStateChange} />)

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: { summary: 'Generated review.', verdict: 'COMMENT', lineComments: [] },
        diff: '',
        validationDiff: '',
      })
    })

    let saveId = 0
    await waitFor(() => {
      const save = cefQuery.mock.calls
        .map(([arg]) => JSON.parse(arg.request) as {
          type: string
          saveId?: number
          generatedResult?: { summary: string }
        })
        .find((message) => message.type === 'saveDraft')
      expect(save?.saveId).toBeTypeOf('number')
      expect(save?.generatedResult?.summary).toBe('Generated review.')
      saveId = save?.saveId ?? 0
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(true)
      expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
    })

    act(() => {
      hostMessage({
        type: 'draftSaved',
        prKey: 'acme/widget#42',
        saveId: saveId + 1,
        reviewId: 'stale',
        commentsDropped: false,
      })
    })
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()

    act(() => {
      hostMessage({
        type: 'draftSaved',
        prKey: 'acme/widget#42',
        saveId,
        reviewId: 'draft-1',
        commentsDropped: false,
      })
    })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Saved' })).toBeDisabled()
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(false)
    })
  })

  it('revalidates an unanchored comment when the full diff arrives late', async () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    render(<ReviewPane pr={pr} />)

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: {
          summary: 'Generated review.',
          verdict: 'COMMENT',
          lineComments: [{ file: 'src/value.ts', line: 1, type: 'issue', body: 'Handle this value.' }],
        },
        diff: '',
        validationDiff: '',
      })
    })
    expect(await screen.findByText('· 1 unanchored')).toBeInTheDocument()

    act(() => {
      hostMessage({
        type: 'validationDiffUpdated',
        prKey: 'acme/widget#42',
        validationDiff: [
          'diff --git a/src/value.ts b/src/value.ts',
          '--- a/src/value.ts',
          '+++ b/src/value.ts',
          '@@ -0,0 +1 @@',
          '+const value = readValue()',
        ].join('\n'),
      })
    })

    await waitFor(() => {
      expect(screen.queryByText('· 1 unanchored')).not.toBeInTheDocument()
      expect(screen.getByText('Generated review.')).toBeInTheDocument()
    })
  })
})


