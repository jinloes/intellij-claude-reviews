import { describe, expect, it } from 'vitest'
import type { LineComment } from '@/bridge/types'
import { buildFindingNavItems, findingLabel, findingPreview } from './findingNavigation'

describe('buildFindingNavItems', () => {
  it('sorts by severity before file and line while retaining canonical indexes', () => {
    const comments: LineComment[] = [
      { file: 'z.ts', line: 4, type: 'note', severity: 'nit', body: 'Nit.' },
      { file: 'b.ts', line: 2, type: 'issue', severity: 'blocker', body: 'Blocker.' },
      { file: 'a.ts', line: 8, type: 'issue', severity: 'major', body: 'Major.' },
      { file: 'a.ts', line: 1, type: 'suggestion', body: 'No severity.' },
    ]

    expect(buildFindingNavItems(comments).map((item) => ({
      index: item.index,
      label: item.label,
    }))).toEqual([
      { index: 1, label: 'Blocker issue' },
      { index: 2, label: 'Major issue' },
      { index: 0, label: 'Nit note' },
      { index: 3, label: 'Suggestion' },
    ])
  })

  it('keeps a large finding set complete and deterministically ordered', () => {
    const severities = ['nit', 'minor', 'major', 'blocker'] as const
    const comments: LineComment[] = Array.from({ length: 30 }, (_, index) => ({
      file: `src/file-${String(29 - index).padStart(2, '0')}.ts`,
      line: index + 1,
      type: 'issue',
      severity: severities[index % severities.length],
      body: `Finding ${index}`,
    }))

    const items = buildFindingNavItems(comments)

    expect(items).toHaveLength(30)
    expect(items.slice(0, 7).every((item) => item.comment.severity === 'blocker')).toBe(true)
    expect(new Set(items.map((item) => item.index)).size).toBe(30)
  })
})

describe('finding labels and previews', () => {
  it('combines severity and type into one primary label', () => {
    expect(findingLabel({
      file: 'src/auth.ts',
      line: 2,
      type: 'issue',
      severity: 'major',
      body: 'Check this.',
    })).toBe('Major issue')
  })

  it('turns markdown into a bounded one-line preview', () => {
    expect(findingPreview('Use **safe** [`parse`](https://example.com) here.\nThen validate.', 36))
      .toBe('Use safe parse here. Then validate.')
  })
})
