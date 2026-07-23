import { act, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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
})

