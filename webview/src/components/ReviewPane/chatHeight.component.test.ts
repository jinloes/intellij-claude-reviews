import { describe, expect, it } from 'vitest'
import {
  CHAT_HEIGHT_KEY,
  DEFAULT_CHAT_HEIGHT,
  MAX_CHAT_HEIGHT,
  MIN_CHAT_HEIGHT,
  loadChatHeight,
} from './chatHeight'

describe('loadChatHeight', () => {
  it('falls back when a legacy persisted height cannot fit the chat controls', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, '100')

    expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)
  })

  it('preserves persisted heights within the usable range', () => {
    localStorage.setItem(CHAT_HEIGHT_KEY, String(MIN_CHAT_HEIGHT))

    expect(loadChatHeight()).toBe(MIN_CHAT_HEIGHT)
  })

  it('falls back for missing, invalid, and oversized persisted values', () => {
    for (const value of [null, 'invalid', String(MAX_CHAT_HEIGHT + 1)]) {
      localStorage.clear()
      if (value !== null) localStorage.setItem(CHAT_HEIGHT_KEY, value)

      expect(loadChatHeight()).toBe(DEFAULT_CHAT_HEIGHT)
    }
  })
})

