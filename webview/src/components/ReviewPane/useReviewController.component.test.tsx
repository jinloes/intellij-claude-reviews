import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PR, ReviewResult } from '../../bridge/types'
import { MUTATION_WATCHDOG_MS, useReviewController } from './useReviewController'

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

const diff = [
  'diff --git a/src/value.ts b/src/value.ts',
  '--- a/src/value.ts',
  '+++ b/src/value.ts',
  '@@ -0,0 +1 @@',
  '+const value = readValue()',
].join('\n')

const review: ReviewResult = {
  summary: 'Review summary.',
  verdict: 'COMMENT',
  lineComments: [{
    file: 'src/value.ts',
    line: 1,
    type: 'issue',
    body: 'Handle this value.',
  }],
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('Review controller did not register the JCEF bridge handler')
  handler({ protocolVersion: 1, ...message })
}

function outgoingMessages(cefQuery: ReturnType<typeof vi.fn>) {
  return cefQuery.mock.calls.map(([argument]) => JSON.parse(argument.request) as {
    type: string
    number?: number
    saveId?: number
  })
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  delete (window as unknown as { cefQuery?: unknown }).cefQuery
})

describe('useReviewController', () => {
  it('correlates host events to the active PR and resets on PR switches', () => {
    const { result, rerender } = renderHook(
      ({ selectedPr }) => useReviewController({ pr: selectedPr }),
      { initialProps: { selectedPr: pr } },
    )

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#99',
        prState: 'DRAFT_PRESENT',
        reviewId: 'wrong-draft',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    expect(result.current.model.state.kind).toBe('draftLoading')

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    expect(result.current.model.state).toMatchObject({ kind: 'draftPresent', reviewId: 'draft-1' })

    rerender({ selectedPr: { ...pr, number: 43 } })
    expect(result.current.model.state.kind).toBe('draftLoading')
  })

  it('keeps autosave dirty until the matching acknowledgement', async () => {
    const cefQuery = vi.fn()
    const onDirtyStateChange = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result } = renderHook(() => useReviewController({ pr, onDirtyStateChange }))

    act(() => {
      hostMessage({
        type: 'reviewResult',
        prKey: 'acme/widget#42',
        result: review,
        diff,
        validationDiff: diff,
      })
    })

    let saveId = 0
    await waitFor(() => {
      const save = outgoingMessages(cefQuery).find((message) => message.type === 'saveDraft')
      expect(save?.saveId).toBeTypeOf('number')
      saveId = save?.saveId ?? 0
      expect(result.current.model.saving).toBe(true)
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(true)
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
    expect(result.current.model.saving).toBe(true)

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
      expect(result.current.model.saving).toBe(false)
      expect(result.current.model.autosaveDirty).toBe(false)
      expect(onDirtyStateChange).toHaveBeenLastCalledWith(false)
    })
  })

  it('clears a mutation watchdog when the host acknowledges the operation', () => {
    vi.useFakeTimers()
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.deleteDraft())

    expect(result.current.model.deleting).toBe(true)
    expect(vi.getTimerCount()).toBe(1)

    act(() => {
      hostMessage({ type: 'draftDeleted', prKey: 'acme/widget#42' })
    })

    expect(result.current.model.deleting).toBe(false)
    expect(result.current.model.state.kind).toBe('noDraft')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('recovers from a mutation watchdog timeout', () => {
    vi.useFakeTimers()
    ;(window as unknown as { cefQuery?: ReturnType<typeof vi.fn> }).cefQuery = vi.fn()
    const { result } = renderHook(() => useReviewController({ pr }))

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.deleteDraft())
    act(() => {
      vi.advanceTimersByTime(MUTATION_WATCHDOG_MS)
    })

    expect(result.current.model.deleting).toBe(false)
    expect(result.current.model.state).toMatchObject({
      kind: 'deleteError',
      message: 'The host did not respond in time. The draft may still exist on GitHub.',
    })
  })

  it('flushes a pending autosave for the outgoing PR during a switch', async () => {
    const cefQuery = vi.fn()
    ;(window as unknown as { cefQuery?: typeof cefQuery }).cefQuery = cefQuery
    const { result, rerender } = renderHook(
      ({ selectedPr }) => useReviewController({ pr: selectedPr }),
      { initialProps: { selectedPr: pr } },
    )

    act(() => {
      hostMessage({
        type: 'draftLoaded',
        prKey: 'acme/widget#42',
        prState: 'DRAFT_PRESENT',
        reviewId: 'draft-1',
        result: review,
        diff,
        validationDiff: diff,
      })
    })
    act(() => result.current.actions.editCommentHandlers.onEditComment(0, 'Edited before switching.'))

    await waitFor(() => expect(result.current.model.autosaveDirty).toBe(true))
    expect(outgoingMessages(cefQuery).filter((message) => message.type === 'saveDraft')).toEqual([])

    rerender({ selectedPr: { ...pr, number: 43 } })

    await waitFor(() => {
      expect(outgoingMessages(cefQuery).filter((message) => message.type === 'saveDraft')).toEqual([
        expect.objectContaining({ number: 42 }),
      ])
    })
    expect(result.current.model.state.kind).toBe('draftLoading')
  })
})
