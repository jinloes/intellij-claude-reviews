export function adjacentCommentIndex(
  focusedIndex: number,
  direction: -1 | 1,
  commentCount: number,
): number {
  if (commentCount <= 0) return 0

  const currentIndex = Math.min(Math.max(focusedIndex, 0), commentCount - 1)
  return (currentIndex + direction + commentCount) % commentCount
}

/**
 * Returns the inline-comment index to focus after one comment is removed.
 *
 * When the focused comment is deleted, the next comment takes its place when
 * available; deleting the last comment selects the preceding one instead.
 */
export function focusedIndexAfterCommentDeletion(
  focusedIndex: number,
  deletedIndex: number,
  commentCount: number,
): number {
  if (commentCount <= 1) return 0

  const lastIndex = commentCount - 1
  const currentIndex = Math.min(Math.max(focusedIndex, 0), lastIndex)
  if (deletedIndex < 0 || deletedIndex > lastIndex) return currentIndex
  if (deletedIndex < currentIndex) return currentIndex - 1
  if (deletedIndex === currentIndex) return Math.min(currentIndex, lastIndex - 1)
  return currentIndex
}
