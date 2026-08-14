import { parseDiff, type FileData } from 'react-diff-view'

export type DiffParseResult =
  | { status: 'empty'; files: FileData[] }
  | { status: 'parsed'; files: FileData[] }
  | { status: 'unrenderable'; files: FileData[] }

export function parseDiffSafely(diff: string): DiffParseResult {
  if (!diff.trim()) return { status: 'empty', files: [] }
  try {
    const files = parseDiff(diff)
    const hasRenderableFile = files.some((file) => file.oldPath || file.newPath || file.hunks.length > 0)
    return files.length > 0 && hasRenderableFile
      ? { status: 'parsed', files }
      : { status: 'unrenderable', files: [] }
  } catch {
    return { status: 'unrenderable', files: [] }
  }
}


