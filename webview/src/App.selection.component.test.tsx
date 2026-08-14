import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { PR } from './bridge/types'

const firstPr: PR = {
  number: 42,
  title: 'First pull request',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/42',
  isDraft: false,
  hasReviewDraft: true,
}
const secondPr: PR = { ...firstPr, number: 43, title: 'Second pull request' }

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('App did not register the bridge handler')
  handler({ protocolVersion: 1, ...message })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('App pull-request transitions', () => {
  it('confirmed discard switches PRs without flushing the rejected edit', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<App />)

    act(() => hostMessage({ type: 'prListLoaded', prs: [firstPr, secondPr] }))
    await user.click(screen.getByRole('button', { name: /First pull request/ }))
    act(() => hostMessage({
      type: 'draftLoaded',
      prKey: 'acme/widget#42',
      prState: 'DRAFT_PRESENT',
      reviewId: 'draft-1',
      result: {
        summary: 'Saved review.',
        verdict: 'COMMENT',
        lineComments: [{ file: 'missing.ts', line: 1, type: 'issue', body: 'Discard me.' }],
      },
      diff: '',
      validationDiff: '',
    }))
    await user.click(screen.getByRole('button', { name: 'Delete unanchored comment' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))
    cefQuery.mockClear()

    await user.click(screen.getByRole('button', { name: /Second pull request/ }))

    const outgoing = cefQuery.mock.calls.map(([arg]) => JSON.parse(arg.request) as { type: string; number?: number })
    expect(outgoing).toContainEqual(expect.objectContaining({ type: 'selectPR', number: 43 }))
    expect(outgoing.some((message) => message.type === 'saveDraft')).toBe(false)
  })

  it('cancelled discard keeps the current PR selected', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<App />)

    act(() => hostMessage({ type: 'prListLoaded', prs: [firstPr, secondPr] }))
    await user.click(screen.getByRole('button', { name: /First pull request/ }))
    act(() => hostMessage({
      type: 'draftLoaded',
      prKey: 'acme/widget#42',
      prState: 'DRAFT_PRESENT',
      reviewId: 'draft-1',
      result: {
        summary: 'Saved review.',
        verdict: 'COMMENT',
        lineComments: [{ file: 'missing.ts', line: 1, type: 'issue', body: 'Keep me.' }],
      },
      diff: '',
      validationDiff: '',
    }))
    await user.click(screen.getByRole('button', { name: 'Delete unanchored comment' }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))
    cefQuery.mockClear()

    await user.click(screen.getByRole('button', { name: /Second pull request/ }))

    expect(window.confirm).toHaveBeenCalled()
    expect(cefQuery).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /First pull request/ })).toHaveAttribute('aria-current', 'page')
  })
})


