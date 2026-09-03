import type { LineComment } from '@/bridge/types'

export interface DiffFileLike {
  oldPath: string
  newPath: string
  type: string
}

export interface DiffFileNavItem {
  key: string
  index: number
  displayPath: string
  status: string
  commentCount: number
  segments: string[]
}

export interface DiffFileTreeNode {
  key: string
  name: string
  path: string
  depth: number
  children: DiffFileTreeNode[]
  file?: DiffFileNavItem
}

function commentMatchesPath(commentPath: string, displayPath: string): boolean {
  return commentPath === displayPath || commentPath.endsWith(displayPath) || displayPath.endsWith(commentPath)
}

export function displayPathForFile(file: DiffFileLike): string {
  return file.newPath !== '/dev/null' ? file.newPath : file.oldPath
}

export function middleTruncateFileName(fileName: string, maxLength = 34): string {
  if (fileName.length <= maxLength) return fileName
  if (maxLength < 8) return `${fileName.slice(0, Math.max(1, maxLength - 3))}...`

  const dotIndex = fileName.lastIndexOf('.')
  const extension = dotIndex > 0 && fileName.length - dotIndex <= 10 ? fileName.slice(dotIndex) : ''
  const stem = extension ? fileName.slice(0, dotIndex) : fileName
  const available = maxLength - extension.length - 3
  if (available < 4) return `${fileName.slice(0, maxLength - 3)}...`

  const prefixLength = Math.max(4, Math.floor(available * 0.35))
  const suffixLength = available - prefixLength
  const suffix = suffixLength > 0 ? stem.slice(-suffixLength) : ''
  return `${stem.slice(0, prefixLength)}...${suffix}${extension}`
}

export function buildDiffFileNavItems(files: readonly DiffFileLike[], comments: readonly LineComment[]): DiffFileNavItem[] {
  return files.map((file, index) => {
    const displayPath = displayPathForFile(file)
    return {
      key: `${file.oldPath}->${file.newPath}:${index}`,
      index,
      displayPath,
      status: file.type,
      commentCount: comments.filter((comment) => commentMatchesPath(comment.file, displayPath)).length,
      segments: displayPath.split('/').filter(Boolean),
    }
  })
}

export function buildDiffFileTree(items: readonly DiffFileNavItem[]): DiffFileTreeNode[] {
  type MutableNode = Omit<DiffFileTreeNode, 'children'> & { children: Map<string, MutableNode> }

  const root = new Map<string, MutableNode>()

  for (const item of items) {
    let currentLevel = root
    let currentPath = ''
    item.segments.forEach((segment, depth) => {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment
      const key = `${depth}:${currentPath}`
      let node = currentLevel.get(key)
      if (!node) {
        node = { key, name: segment, path: currentPath, depth, children: new Map() }
        currentLevel.set(key, node)
      }
      if (depth === item.segments.length - 1) {
        node.file = item
      }
      currentLevel = node.children
    })
  }

  const toImmutable = (nodes: Iterable<MutableNode>): DiffFileTreeNode[] =>
    Array.from(nodes)
      .sort((left, right) => {
        const leftIsDirectory = !left.file
        const rightIsDirectory = !right.file
        if (leftIsDirectory !== rightIsDirectory) return leftIsDirectory ? -1 : 1
        return left.name.localeCompare(right.name)
      })
      .map((node) => ({
        key: node.key,
        name: node.name,
        path: node.path,
        depth: node.depth,
        file: node.file,
        children: toImmutable(node.children.values()),
      }))

  const collapseSingleChildFolders = (nodes: DiffFileTreeNode[]): DiffFileTreeNode[] =>
    nodes.map((node) => {
      let mergedNode = { ...node, children: collapseSingleChildFolders(node.children) }

      while (!mergedNode.file && mergedNode.children.length === 1 && !mergedNode.children[0].file) {
        const child = mergedNode.children[0]
        mergedNode = {
          ...child,
          name: `${mergedNode.name}/${child.name}`,
          key: `${mergedNode.key}|${child.key}`,
          path: child.path,
          depth: mergedNode.depth,
          children: child.children,
        }
      }

      return mergedNode
    })

  return collapseSingleChildFolders(toImmutable(root.values()))
}

export function findActiveFileIndex(sectionTopOffsets: readonly number[], stickyOffset: number): number {
  if (sectionTopOffsets.length === 0) return 0

  let activeIndex = 0
  let lastOffset = Number.NEGATIVE_INFINITY
  for (let i = 0; i < sectionTopOffsets.length; i++) {
    const offset = sectionTopOffsets[i]
    if (!Number.isFinite(offset)) continue
    if (offset <= lastOffset) break
    lastOffset = offset
    if (offset <= stickyOffset) {
      activeIndex = i
      continue
    }
    break
  }
  return activeIndex
}

