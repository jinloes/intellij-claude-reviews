import type { LineComment } from '@/bridge/types'

const SEVERITY_ORDER: Record<NonNullable<LineComment['severity']>, number> = {
  blocker: 0,
  major: 1,
  minor: 2,
  nit: 3,
}

export interface FindingNavItem {
  key: string
  index: number
  comment: LineComment
  label: string
  preview: string
}

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1)
}

export function findingLabel(comment: LineComment): string {
  return comment.severity
    ? `${capitalize(comment.severity)} ${comment.type}`
    : capitalize(comment.type)
}

export function findingPreview(body: string, maxLength = 88): string {
  const plainText = body
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[`*_>#~-]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return plainText.length <= maxLength ? plainText : `${plainText.slice(0, maxLength - 3).trimEnd()}...`
}

export function buildFindingNavItems(comments: readonly LineComment[]): FindingNavItem[] {
  return comments
    .map((comment, index) => ({
      key: `${comment.file}:${comment.line}:${index}`,
      index,
      comment,
      label: findingLabel(comment),
      preview: findingPreview(comment.body),
    }))
    .sort((left, right) => {
      const leftSeverity = left.comment.severity ? SEVERITY_ORDER[left.comment.severity] : 4
      const rightSeverity = right.comment.severity ? SEVERITY_ORDER[right.comment.severity] : 4
      return leftSeverity - rightSeverity
        || left.comment.file.localeCompare(right.comment.file)
        || left.comment.line - right.comment.line
        || left.index - right.index
    })
}
