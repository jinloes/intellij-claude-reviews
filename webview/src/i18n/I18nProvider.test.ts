import { render, screen, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { englishMessages } from './messages.en'
import { I18nProvider } from './I18nProvider'
import { formatMessage, pseudoLocalize } from './format'

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

  it('interpolates named values without dropping unknown placeholders', () => {
    expect(formatMessage('{count} comments in {repo}; {unknown}', { count: 2, repo: 'acme/widget' }))
      .toBe('2 comments in acme/widget; {unknown}')
  })

  it('pseudo-localizes inline copy and accessible labels outside the catalog', async () => {
    render(createElement(
      I18nProvider,
      {
        locale: 'pseudo',
        children: createElement('button', { 'aria-label': 'Open settings' }, 'Hardcoded button'),
      },
    ))

    await waitFor(() => expect(screen.getByText(/^⟦.*Haardcoodeed buuttoon.*⟧$/)).toBeVisible())
    expect(screen.getByRole('button').getAttribute('aria-label')).toMatch(/^⟦.*~~~⟧$/)
  })
})
