import { act, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, type Mock } from 'vitest'
import type { VerifyResult } from './structuredResult'
import type { PR } from '../../bridge/types'
import { ChatPane } from './ChatPane'

const pr: PR = {
  number: 42,
  title: 'Improve chat layout',
  owner: 'acme',
  repo: 'widget',
  author: 'octocat',
  createdAt: '2026-07-15T00:00:00Z',
  htmlUrl: 'https://github.com/acme/widget/pull/42',
  isDraft: false,
  hasReviewDraft: false,
}

describe('ChatPane', () => {
  it('keeps an empty flexible message region so the composer can remain bottom-aligned', () => {
    render(<ChatPane pr={pr} />)

    expect(screen.getByTestId('chat-messages')).toHaveClass('flex-1', 'min-h-0', 'overflow-y-auto')
    expect(screen.getByRole('textbox', { name: 'Ask about this pull request' })).toBeVisible()
  })

  it('scrolls verification messages within the chat region', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    const onPendingMessageSent = vi.fn()
    const { rerender } = render(<ChatPane pr={pr} />)
    const messages = screen.getByTestId('chat-messages')
    Object.defineProperty(messages, 'scrollHeight', { configurable: true, value: 120 })

    rerender(
      <ChatPane
        pr={pr}
        pendingMessage={{ q: 'Verify this review comment', ctx: '', id: 456 }}
        onPendingMessageSent={onPendingMessageSent}
      />,
    )

    await waitFor(() => expect(messages.scrollTop).toBe(120))
    expect(onPendingMessageSent).toHaveBeenCalledTimes(1)
  })

  it('sends each pending verification message only once', async () => {
    const cefQuery = vi.fn()
    Object.assign(window, { cefQuery })
    const onPendingMessageSent = vi.fn()
    const pendingMessage = {
      q: 'Verify this review comment',
      ctx: '',
      id: 123,
    }

    render(
      <ChatPane
        pr={pr}
        pendingMessage={pendingMessage}
        onPendingMessageSent={onPendingMessageSent}
      />,
    )

    await waitFor(() => expect(cefQuery).toHaveBeenCalledTimes(1))
    expect(onPendingMessageSent).toHaveBeenCalledTimes(1)
    expect(screen.getByText(pendingMessage.q)).toBeVisible()
    expect(JSON.parse(cefQuery.mock.calls[0][0].request)).toMatchObject({
      type: 'askClaude',
      context: '',
      question: pendingMessage.q,
    })

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatResponse',
        prKey: 'acme/widget#42',
        response: 'Confirmed',
      })
    })

    await screen.findByText('Confirmed')
    expect(cefQuery).toHaveBeenCalledTimes(1)
    expect(onPendingMessageSent).toHaveBeenCalledTimes(1)
  })

  it('renders a structured verify-comment response as a card instead of raw JSON', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} />)

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatResponse',
        prKey: 'acme/widget#42',
        response: JSON.stringify({
          verdict: 'invalid',
          why: 'The diff shows the null check already exists at line 12.',
          action: 'revise',
          replacementComment: 'This check is redundant with the guard added above.',
        }),
      })
    })

    expect(await screen.findByText('Invalid')).toBeVisible()
    expect(screen.getByText(/Suggested action: Revise/)).toBeVisible()
    expect(screen.getByText('The diff shows the null check already exists at line 12.')).toBeVisible()
    expect(screen.getByText('This check is redundant with the guard added above.')).toBeVisible()
    expect(screen.queryByText(/"verdict":"invalid"/)).not.toBeInTheDocument()
  })

  it('renders structured JSON as a card while still streaming, instead of raw JSON with a blinking cursor', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} />)

    const fullJson = JSON.stringify({
      verdict: 'valid',
      why: 'Supported by the diff.',
      action: 'keep',
      replacementComment: null,
    })

    act(() => {
      const hostWindow = window as unknown as {
        __handleMessage: (message: unknown) => void
      }
      hostWindow.__handleMessage({
        protocolVersion: 1,
        type: 'chatChunk',
        prKey: 'acme/widget#42',
        chunk: fullJson,
      })
    })

    expect(await screen.findByText('Valid')).toBeVisible()
    expect(screen.getByText('Supported by the diff.')).toBeVisible()
    expect(screen.queryByText(/"verdict":"valid"/)).not.toBeInTheDocument()
  })

  function pushHostMessage(message: Record<string, unknown>) {
    act(() => {
      const hostWindow = window as unknown as { __handleMessage: (message: unknown) => void }
      hostWindow.__handleMessage({ protocolVersion: 1, prKey: 'acme/widget#42', ...message })
    })
  }

  function verifyJson(over: Record<string, unknown> = {}) {
    return JSON.stringify({
      verdict: 'invalid',
      why: 'Already handled upstream.',
      action: 'delete',
      replacementComment: null,
      ...over,
    })
  }

  type ApplyVerifyMock = Mock<(result: VerifyResult, token: string) => void>

  function newApplyMock(): ApplyVerifyMock {
    return vi.fn<(result: VerifyResult, token: string) => void>()
  }

  async function renderWithPendingVerify(token: string, onApplyVerifyAction: ApplyVerifyMock) {
    Object.assign(window, { cefQuery: vi.fn() })
    const view = render(
      <ChatPane
        pr={pr}
        pendingMessage={{ q: 'Verify this comment', ctx: '', id: 1, token }}
        onApplyVerifyAction={onApplyVerifyAction}
      />,
    )
    await screen.findByText('Verify this comment')
    return view
  }

  it('applies a delete verdict to the comment the verification was requested for', async () => {
    const onApplyVerifyAction = newApplyMock()
    await renderWithPendingVerify('verify-1', onApplyVerifyAction)

    pushHostMessage({ type: 'chatResponse', response: verifyJson() })

    const applyButton = await screen.findByRole('button', { name: 'Delete this comment' })
    applyButton.click()

    expect(onApplyVerifyAction).toHaveBeenCalledTimes(1)
    expect(onApplyVerifyAction.mock.calls[0][0]).toMatchObject({ action: 'delete' })
    expect(onApplyVerifyAction.mock.calls[0][1]).toBe('verify-1')
  })

  it('offers a replace action for a revise verdict that carries replacement text', async () => {
    const onApplyVerifyAction = newApplyMock()
    await renderWithPendingVerify('verify-2', onApplyVerifyAction)

    pushHostMessage({
      type: 'chatResponse',
      response: verifyJson({ action: 'revise', replacementComment: 'Narrow this to the null case.' }),
    })

    const applyButton = await screen.findByRole('button', { name: 'Replace comment text' })
    applyButton.click()

    expect(onApplyVerifyAction.mock.calls[0][0]).toMatchObject({
      action: 'revise',
      replacementComment: 'Narrow this to the null case.',
    })
  })

  it('offers no action for a keep verdict, or a revise verdict with no replacement text', async () => {
    const onApplyVerifyAction = newApplyMock()
    const { unmount } = await renderWithPendingVerify('verify-3', onApplyVerifyAction)
    pushHostMessage({ type: 'chatResponse', response: verifyJson({ verdict: 'valid', action: 'keep' }) })

    expect(await screen.findByText(/Suggested action: Keep as-is/)).toBeVisible()
    expect(screen.queryByRole('button', { name: /Delete this comment|Replace comment text/ })).not.toBeInTheDocument()
    unmount()

    await renderWithPendingVerify('verify-4', onApplyVerifyAction)
    pushHostMessage({ type: 'chatResponse', response: verifyJson({ action: 'revise', replacementComment: '   ' }) })

    expect(await screen.findByText(/Suggested action: Revise/)).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Replace comment text' })).not.toBeInTheDocument()
  })

  it('offers no action for a verdict that did not originate from a tracked verify request', async () => {
    Object.assign(window, { cefQuery: vi.fn() })
    render(<ChatPane pr={pr} onApplyVerifyAction={newApplyMock()} />)

    pushHostMessage({ type: 'chatResponse', response: verifyJson() })

    expect(await screen.findByText('Invalid')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Delete this comment' })).not.toBeInTheDocument()
  })

  it('disables the apply button after it has been used so an action cannot be applied twice', async () => {
    const onApplyVerifyAction = newApplyMock()
    await renderWithPendingVerify('verify-5', onApplyVerifyAction)
    pushHostMessage({ type: 'chatResponse', response: verifyJson() })

    const applyButton = await screen.findByRole('button', { name: 'Delete this comment' })
    act(() => applyButton.click())

    const appliedButton = await screen.findByRole('button', { name: 'Applied' })
    expect(appliedButton).toBeDisabled()
    act(() => appliedButton.click())
    expect(onApplyVerifyAction).toHaveBeenCalledTimes(1)
  })
})

