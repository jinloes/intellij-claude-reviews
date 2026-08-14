import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MarkdownContent } from './MarkdownContent'

describe('MarkdownContent', () => {
  it('renders bulleted and numbered lists with explicit markers and indentation', () => {
    render(<MarkdownContent>{'- First\n- Second\n\n1. One\n2. Two'}</MarkdownContent>)

    const lists = screen.getAllByRole('list')
    expect(lists[0]).toHaveClass('list-disc', 'pl-5')
    expect(lists[1]).toHaveClass('list-decimal', 'pl-5')
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
  })
})


