import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR } from '../../bridge/types'
import { ReviewPane } from './ReviewPane'

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


