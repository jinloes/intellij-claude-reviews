import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
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
  reviewStatus: 'UNREVIEWED',
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
  it('keeps the PR list mounted so recovery payloads are not lost behind setup', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    render(<App />)

    act(() => hostMessage({
      type: 'setupRequired',
      reason: 'gh_not_authenticated',
      detail: 'Sign in.',
    }))
    expect(screen.getByRole('main', { name: 'PR Pilot setup' })).toBeInTheDocument()

    act(() => hostMessage({ type: 'prListLoaded', prs: [firstPr] }))

    expect(screen.queryByRole('main', { name: 'PR Pilot setup' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /First pull request/ })).toBeInTheDocument()
  })

  it('renders specific repair guidance for an unavailable draft index', () => {
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    render(<App />)

    act(() => hostMessage({
      type: 'setupRequired',
      reason: 'draft_index_unavailable',
      detail: 'The file was preserved; repair it or quarantine it, then refresh PR Pilot.',
    }))

    expect(screen.getByRole('heading', { name: 'Draft index needs attention' })).toBeInTheDocument()
    expect(screen.getByText(/file was preserved; repair it or quarantine it/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check status' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Auth Guide' })).not.toBeInTheDocument()
  })

  it('confirmed discard switches PRs without flushing the rejected edit', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
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
    const switchDialog = screen.getByRole('alertdialog')
    expect(within(switchDialog).getByRole('heading', { name: /discard unsaved review changes/i })).toBeInTheDocument()
    await user.click(within(switchDialog).getByRole('button', { name: 'Discard and switch' }))

    const outgoing = cefQuery.mock.calls.map(([arg]) => JSON.parse(arg.request) as { type: string; number?: number })
    expect(outgoing).toContainEqual(expect.objectContaining({ type: 'selectPR', number: 43 }))
    expect(outgoing.some((message) => message.type === 'saveDraft')).toBe(false)
  })

  it('cancelled discard keeps the current PR selected', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
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
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Keep reviewing' }))

    expect(cefQuery).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /First pull request/ })).toHaveAttribute('aria-current', 'page')
  })

  it('blocks switching while a draft save is in progress', async () => {
    const user = userEvent.setup()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
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
    await user.click(screen.getByRole('button', { name: 'Save now' }))
    cefQuery.mockClear()

    await user.click(screen.getByRole('button', { name: /Second pull request/ }))
    const switchDialog = screen.getByRole('alertdialog')
    await user.click(within(switchDialog).getByRole('button', { name: 'Discard and switch' }))

    expect(within(switchDialog).getByRole('alert')).toHaveTextContent(/save is already in progress/i)
    expect(cefQuery).not.toHaveBeenCalled()
  })
})

it('does not use native browser dialogs for shared-webview flows', () => {
  const source = readFileSync(path.resolve(process.cwd(), 'src/App.tsx'), 'utf8')
  expect(source).not.toMatch(/window\.(?:prompt|confirm|alert)\(/)
})
