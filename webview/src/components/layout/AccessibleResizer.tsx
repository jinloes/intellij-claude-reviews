import type { KeyboardEvent, PointerEvent } from 'react'
import { cn } from '@/lib/utils'

interface AccessibleResizerProps {
  label: string
  orientation: 'horizontal' | 'vertical'
  value: number
  min: number
  max: number
  onChange: (value: number) => void
  onCommit?: (value: number) => void
  onPointerDown?: (event: PointerEvent<HTMLButtonElement>) => void
  className?: string
  step?: number
}

export function AccessibleResizer({
  label,
  orientation,
  value,
  min,
  max,
  onChange,
  onCommit,
  onPointerDown,
  className,
  step = 24,
}: AccessibleResizerProps) {
  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    let next: number | null = null
    if (event.key === 'Home') next = min
    if (event.key === 'End') next = max
    if (orientation === 'vertical' && event.key === 'ArrowLeft') next = value - step
    if (orientation === 'vertical' && event.key === 'ArrowRight') next = value + step
    if (orientation === 'horizontal' && event.key === 'ArrowUp') next = value + step
    if (orientation === 'horizontal' && event.key === 'ArrowDown') next = value - step
    if (next === null) return
    event.preventDefault()
    const clamped = Math.max(min, Math.min(max, next))
    onChange(clamped)
    onCommit?.(clamped)
  }

  return (
    <button
      type="button"
      role="separator"
      tabIndex={0}
      aria-label={label}
      aria-orientation={orientation}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={Math.round(value)}
      className={cn(
        'shrink-0 border-0 bg-border p-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset',
        orientation === 'vertical' ? 'relative w-[5px] cursor-col-resize' : 'relative h-2 cursor-row-resize',
        className,
      )}
      onKeyDown={handleKeyDown}
      onPointerDown={onPointerDown}
    />
  )
}
