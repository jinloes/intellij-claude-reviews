import { describe, expect, it } from 'vitest'
import { focusedIndexAfterCommentDeletion } from './commentNavigation'

describe('focusedIndexAfterCommentDeletion', () => {
  it('selects the next comment when the focused comment is removed before the end', () => {
    expect(focusedIndexAfterCommentDeletion(1, 1, 3)).toBe(1)
  })

  it('selects the previous comment when the last focused comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(2, 2, 3)).toBe(1)
  })

  it('keeps the same logical comment focused when an earlier comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(2, 0, 3)).toBe(1)
  })

  it('keeps the focused comment when a later comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(0, 2, 3)).toBe(0)
  })

  it('returns the empty-state index when the only comment is removed', () => {
    expect(focusedIndexAfterCommentDeletion(0, 0, 1)).toBe(0)
  })
})
