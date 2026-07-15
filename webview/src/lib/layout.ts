export const MIN_LEFT_PANE_WIDTH = 180
export const MAX_LEFT_PANE_WIDTH = 420
export const MIN_RIGHT_PANE_WIDTH = 360

export function maxLeftWidth(viewportWidth = window.innerWidth): number {
  return Math.max(
    MIN_LEFT_PANE_WIDTH,
    Math.min(MAX_LEFT_PANE_WIDTH, viewportWidth - MIN_RIGHT_PANE_WIDTH),
  )
}

export function clampLeftWidth(width: number, viewportWidth = window.innerWidth): number {
  return Math.max(MIN_LEFT_PANE_WIDTH, Math.min(maxLeftWidth(viewportWidth), width))
}
