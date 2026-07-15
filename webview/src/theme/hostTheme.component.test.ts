import { afterEach, describe, expect, it } from 'vitest'
import { applyHostTheme } from './hostTheme'

afterEach(() => {
  document.documentElement.className = ''
  delete document.documentElement.dataset.theme
})

describe('applyHostTheme', () => {
  it('applies dark and high-contrast semantics', () => {
    applyHostTheme('highContrastDark')
    expect(document.documentElement).toHaveClass('dark', 'high-contrast')
    expect(document.documentElement).toHaveAttribute('data-theme', 'highContrastDark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('removes stale dark classes when switching to light', () => {
    applyHostTheme('dark')
    applyHostTheme('light')
    expect(document.documentElement).not.toHaveClass('dark', 'high-contrast')
    expect(document.documentElement.style.colorScheme).toBe('light')
  })
})
