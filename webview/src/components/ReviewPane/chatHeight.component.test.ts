import { render } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  CHAT_HEIGHT_KEY,
  DEFAULT_CHAT_HEIGHT,
  MAX_CHAT_HEIGHT,
  MIN_CHAT_HEIGHT,
  loadChatHeight,
} from './chatHeight'
import { ReviewPane } from './ReviewPane'

describe('loadChatHeight', () => {
  it('falls back when a legacy persisted height cannot fit the chat controls', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, '100')

    expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)
  })

  it('preserves persisted heights within the usable range', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT))

    expect(loadChatHeight()).toBe(MIN_CHAT_HEIGHT)
  })

  it('treats the boundary around the minimum as expected', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT - 1))
    expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)

    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT + 1))
    expect(loadChatHeight()).toBe(MIN_CHAT_HEIGHT + 1)
  })

  it('falls back for missing, invalid, and oversized persisted values', () => {
    for (const value of [null, 'invalid', String(MAX_CHAT_HEIGHT + 1)]) {
      localStorage.clear()
      if (value !== null) localStorage.setItem(CHAT_HEIGHT_KEY, value)

      expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)
    }
  })
})

describe('ReviewPane layout notifications', () => {
  it('does not send a webviewLayoutChanged message when no PR is selected', () => {
    const cefQuery = vi.fn()
    Object.assign(window, { cefQuery })

    render(createElement(ReviewPane, { pr: null }))

    expect(cefQuery).not.toHaveBeenCalled()
  })
})

