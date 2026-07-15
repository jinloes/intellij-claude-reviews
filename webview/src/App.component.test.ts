import { describe, expect, it } from 'vitest'
import { clampLeftWidth, maxLeftWidth } from './lib/layout'

describe('responsive divider bounds', () => {
  it('keeps the divider within configured and viewport limits', () => {
    expect(maxLeftWidth(1_200)).toBe(420)
    expect(maxLeftWidth(700)).toBe(340)
    expect(maxLeftWidth(400)).toBe(180)
  })

  it('clamps stale widths after the viewport shrinks', () => {
    expect(clampLeftWidth(420, 700)).toBe(340)
    expect(clampLeftWidth(100, 1_200)).toBe(180)
    expect(clampLeftWidth(280, 1_200)).toBe(280)
  })
})
