import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LineComment } from '@/bridge/types'
import { DiffViewer } from './DiffViewer'

const diff = `diff --git a/src/auth.ts b/src/auth.ts
--- a/src/auth.ts
+++ b/src/auth.ts
@@ -1,1 +1,2 @@
 export const ready = true
+export const accessible = true
diff --git a/src/ui/button.tsx b/src/ui/button.tsx
--- a/src/ui/button.tsx
+++ b/src/ui/button.tsx
@@ -3,1 +3,2 @@
 export function Button() {}
+export function IconButton() {}
`

const comments: LineComment[] = [
  { file: 'src/auth.ts', line: 2, type: 'issue', body: 'Explain the new flag.' },
]

beforeAll(() => {
  Object.defineProperty(window, 'CSS', {
    configurable: true,
    value: { escape: (value: string) => value },
  })
})

describe('DiffViewer', () => {
  let scrollIntoViewSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows a changed-files tree and keeps the current file visible in the toolbar', () => {
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={comments} />
      </div>,
    )

    expect(screen.getByRole('navigation', { name: 'Changed files' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'src/auth.ts' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'src/ui/button.tsx' })).toBeVisible()
    expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/auth.ts')
  })

  it('jumps to the selected file from the tree and updates the current-file indicator', async () => {
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={comments} />
      </div>,
    )

    fireEvent.click(screen.getByRole('button', { name: 'src/ui/button.tsx' }))

    await waitFor(() => expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/ui/button.tsx'))
    expect(scrollIntoViewSpy).toHaveBeenCalled()
  })

  it('updates the current-file indicator while the diff scroll position changes', async () => {
    render(
      <div data-testid="review-scroll-body" style={{ overflowY: 'auto', maxHeight: '400px' }}>
        <DiffViewer diff={diff} comments={comments} />
      </div>,
    )

    const scrollBody = screen.getByTestId('review-scroll-body')
    const firstSection = screen.getByTestId('diff-file-section-0')
    const secondSection = screen.getByTestId('diff-file-section-1')
    const toolbar = screen.getByTestId('diff-current-file').parentElement

    vi.spyOn(scrollBody, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      width: 800,
      height: 400,
      top: 0,
      right: 800,
      bottom: 400,
      left: 0,
      toJSON: () => ({}),
    })
    if (toolbar) {
      vi.spyOn(toolbar, 'getBoundingClientRect').mockReturnValue({
        x: 0,
        y: 0,
        width: 800,
        height: 40,
        top: 0,
        right: 800,
        bottom: 40,
        left: 0,
        toJSON: () => ({}),
      })
    }

    let secondTop = 260
    vi.spyOn(firstSection, 'getBoundingClientRect').mockImplementation(() => ({
      x: 0,
      y: -120,
      width: 800,
      height: 220,
      top: -120,
      right: 800,
      bottom: 100,
      left: 0,
      toJSON: () => ({}),
    }))
    vi.spyOn(secondSection, 'getBoundingClientRect').mockImplementation(() => ({
      x: 0,
      y: secondTop,
      width: 800,
      height: 220,
      top: secondTop,
      right: 800,
      bottom: secondTop + 220,
      left: 0,
      toJSON: () => ({}),
    }))

    secondTop = 24
    fireEvent.scroll(scrollBody)

    await waitFor(() => expect(screen.getByTestId('diff-current-file-path')).toHaveTextContent('src/ui/button.tsx'))
  })
})




