import { describe, expect, it } from 'vitest'
import { englishMessages } from './messages.en'
import { pseudoLocalize } from './I18nProvider'

describe('pseudoLocalize', () => {
  it('delimits and expands translated copy', () => {
    const result = pseudoLocalize('Review')
    expect(result).toMatch(/^⟦.+~~~⟧$/)
    expect(result.length).toBeGreaterThan('Review'.length)
  })

  it('can pseudo-localize every catalog entry', () => {
    for (const message of Object.values(englishMessages)) {
      expect(pseudoLocalize(message)).not.toContain('undefined')
    }
  })
})
