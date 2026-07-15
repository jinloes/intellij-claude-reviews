import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AccessibleResizer } from './AccessibleResizer'

describe('AccessibleResizer', () => {
  it('exposes separator values and changes a vertical pane with arrow keys', () => {
    const onChange = vi.fn()
    render(<AccessibleResizer label="Resize PR list" orientation="vertical" value={200} min={100} max={300} onChange={onChange} />)
    const separator = screen.getByRole('separator', { name: 'Resize PR list' })
    expect(separator).toHaveAttribute('aria-valuenow', '200')
    fireEvent.keyDown(separator, { key: 'ArrowRight' })
    expect(onChange).toHaveBeenCalledWith(224)
  })

  it('clamps horizontal keyboard resizing and supports Home and End', () => {
    const onChange = vi.fn()
    render(<AccessibleResizer label="Resize chat" orientation="horizontal" value={290} min={100} max={300} onChange={onChange} />)
    const separator = screen.getByRole('separator', { name: 'Resize chat' })
    fireEvent.keyDown(separator, { key: 'ArrowUp' })
    fireEvent.keyDown(separator, { key: 'Home' })
    fireEvent.keyDown(separator, { key: 'End' })
    expect(onChange.mock.calls).toEqual([[300], [100], [300]])
  })
})
