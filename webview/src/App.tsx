import { useCallback, useEffect, useRef, useState } from 'react'
import { Toaster } from 'sonner'
import { PRList, SetupScreen, type SetupReason } from './components/PRList'
import { ReviewPane } from './components/ReviewPane'
import { onHostMessage, sendToHost, type PR } from './bridge/types'
import { BRIDGE_PROTOCOL_VERSION } from './bridge/validation'
import { AccessibleResizer } from './components/layout/AccessibleResizer'
import { applyHostTheme } from './theme/hostTheme'
import { Button } from './components/ui/button'
import { useI18n } from './i18n/I18nProvider'
import { clampLeftWidth, maxLeftWidth, MIN_LEFT_PANE_WIDTH } from './lib/layout'

// Dev-mode fixture data — replaced by real bridge messages in production
const DEV_PRS: PR[] = [
  {
    number: 4821,
    title: 'Migrate auth middleware to JWT RS256 — remove legacy HMAC fallback',
    owner: 'acme',
    repo: 'platform',
    author: 'jsmith',
    createdAt: '2026-04-28T14:32:00Z',
    htmlUrl: '#',
    isDraft: false,
    hasReviewDraft: true,
  },
  {
    number: 4819,
    title: 'Add rate limiting to /api/v2/search endpoint',
    owner: 'acme',
    repo: 'platform',
    author: 'mchen',
    createdAt: '2026-04-27T09:11:00Z',
    htmlUrl: '#',
    isDraft: true,
    hasReviewDraft: false,
  },
  {
    number: 312,
    title: 'Switch CI pipeline from CircleCI to GitHub Actions',
    owner: 'acme',
    repo: 'infra',
    author: 'rlopez',
    createdAt: '2026-04-26T17:45:00Z',
    htmlUrl: '#',
    isDraft: false,
    hasReviewDraft: false,
  },
  {
    number: 4815,
    title: 'Refactor session store: extract Redis adapter behind interface',
    owner: 'acme',
    repo: 'platform',
    author: 'jsmith',
    createdAt: '2026-04-25T11:00:00Z',
    htmlUrl: '#',
    isDraft: true,
    hasReviewDraft: true,
  },
  {
    number: 88,
    title: 'Bump jackson-databind 2.17.1 → 2.18.0 (CVE-2024-38817)',
    owner: 'acme',
    repo: 'dependencies',
    author: 'bot',
    createdAt: '2026-04-24T08:00:00Z',
    htmlUrl: '#',
    isDraft: false,
    hasReviewDraft: false,
  },
]

const DEFAULT_LEFT = 280
const STORAGE_KEY = 'claude-reviews:divider-width'

function loadSavedWidth(): number {
  const saved = Number(localStorage.getItem(STORAGE_KEY))
  const max = maxLeftWidth()
  return saved >= MIN_LEFT_PANE_WIDTH && saved <= max ? saved : Math.min(DEFAULT_LEFT, max)
}

function seedDevData() {
  const w = window as unknown as {
    cefQuery?: unknown
    __handleMessage?: (json: string) => void
  }
  if (w.cefQuery) return
  if (!w.__handleMessage) return
  w.__handleMessage(JSON.stringify({ protocolVersion: BRIDGE_PROTOCOL_VERSION, type: 'prListLoaded', prs: DEV_PRS }))
}

export default function App() {
  const t = useI18n()
  const [selectedPR, setSelectedPR] = useState<PR | null>(null)
  const [hasUnsavedReview, setHasUnsavedReview] = useState(false)
  const [leftWidth, setLeftWidth] = useState(loadSavedWidth)
  const [setup, setSetup] = useState<{ reason: SetupReason; detail: string } | null>(null)
  const [setupRefreshing, setSetupRefreshing] = useState(false)
  const [narrow, setNarrow] = useState(() => window.innerWidth < 640)
  const [activePane, setActivePane] = useState<'list' | 'review'>('list')
  const dragging = useRef(false)
  const dragStartX = useRef(0)
  const dragStartW = useRef(0)
  const selectedPrRef = useRef<PR | null>(null)
  const unsavedReviewRef = useRef(false)
  // Tracks the latest width synchronously so handleMouseUp can persist without stale closure
  const currentWidthRef = useRef(leftWidth)

  useEffect(() => {
    selectedPrRef.current = selectedPR
  }, [selectedPR])

  useEffect(() => {
    unsavedReviewRef.current = hasUnsavedReview
  }, [hasUnsavedReview])

  useEffect(() => {
    const id = setTimeout(seedDevData, 100)
    return () => clearTimeout(id)
  }, [])

  useEffect(() => {
    function updateLayout() {
      setNarrow(window.innerWidth < 640)
      setLeftWidth((width) => {
        const clamped = clampLeftWidth(width)
        currentWidthRef.current = clamped
        return clamped
      })
    }
    window.addEventListener('resize', updateLayout)
    return () => window.removeEventListener('resize', updateLayout)
  }, [])

  useEffect(() => onHostMessage((msg) => {
    if (msg.type === 'setupRequired') {
      setSetup({ reason: msg.reason, detail: msg.detail })
      setSetupRefreshing(false)
    } else if (msg.type === 'prLoading') {
      setSetupRefreshing(true)
    } else if (msg.type === 'prListLoaded') {
      setSetup(null)
      setSetupRefreshing(false)
    } else if (msg.type === 'themeChanged') {
      applyHostTheme(msg.theme)
    }
  }), [])

  useEffect(() => {
    return onHostMessage((msg) => {
      if (msg.type !== 'activatePR') return
      const nextPr = msg.pr
      const currentPr = selectedPrRef.current
      const samePr = currentPr
        && currentPr.number === nextPr.number
        && currentPr.owner === nextPr.owner
        && currentPr.repo === nextPr.repo
      if (!samePr && unsavedReviewRef.current) {
        const confirmed = window.confirm(
          'You have unsaved review changes for the currently selected PR. Switch anyway and discard in-memory edits?',
        )
        if (!confirmed) return
      }
      setSelectedPR(nextPr)
      if (window.innerWidth < 640) setActivePane('review')
      sendToHost({ type: 'selectPR', number: nextPr.number, owner: nextPr.owner, repo: nextPr.repo })
    })
  }, [])

  const handleMouseMove = useCallback((e: PointerEvent) => {
    if (!dragging.current) return
    const delta = e.clientX - dragStartX.current
    const newWidth = clampLeftWidth(dragStartW.current + delta)
    currentWidthRef.current = newWidth
    setLeftWidth(newWidth)
  }, [])

  function setAndPersistLeftWidth(width: number) {
    currentWidthRef.current = width
    setLeftWidth(width)
    localStorage.setItem(STORAGE_KEY, String(width))
  }

  function handleMouseUp() {
    if (!dragging.current) return
    dragging.current = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    localStorage.setItem(STORAGE_KEY, String(currentWidthRef.current))
    document.removeEventListener('pointermove', handleMouseMove)
    document.removeEventListener('pointerup', handleMouseUp)
  }

  function handleDividerMouseDown(e: React.PointerEvent) {
    dragging.current = true
    dragStartX.current = e.clientX
    dragStartW.current = leftWidth
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    document.addEventListener('pointermove', handleMouseMove)
    document.addEventListener('pointerup', handleMouseUp)
    e.preventDefault()
  }

  function nextPrSelectionAllowed(nextPr: PR): boolean {
    if (!selectedPR) return true
    const samePr =
      selectedPR.number === nextPr.number
      && selectedPR.owner === nextPr.owner
      && selectedPR.repo === nextPr.repo
    if (samePr || !hasUnsavedReview) return true
    return window.confirm(
      'You have unsaved review changes for the currently selected PR. Switch anyway and discard in-memory edits?',
    )
  }

  return (
    <>
    <Toaster theme="system" position="bottom-right" richColors />
    {setup ? (
      <main className="h-full bg-background" aria-label="PR Pilot setup">
        <SetupScreen
          reason={setup.reason}
          detail={setup.detail}
          refreshing={setupRefreshing}
          onRefresh={() => {
            setSetupRefreshing(true)
            sendToHost({ type: 'refreshPRs', state: 'open', searchScope: 'currentRepo' })
          }}
        />
      </main>
    ) : (
    <main className="flex h-full overflow-hidden" data-layout={narrow ? 'narrow' : 'wide'}>
      {narrow && (
        <div className="fixed left-2 top-2 z-40 flex rounded-md border border-border bg-card p-1 shadow">
          <Button size="sm" variant={activePane === 'list' ? 'default' : 'ghost'} onClick={() => setActivePane('list')}>
            {t('app.showPrList')}
          </Button>
          <Button size="sm" variant={activePane === 'review' ? 'default' : 'ghost'} disabled={!selectedPR} onClick={() => setActivePane('review')}>
            {t('app.showReview')}
          </Button>
        </div>
      )}
      {/* Left column — PR list */}
      <aside
        data-testid="pr-list-shell"
        aria-label={t('app.prList')}
        style={narrow ? undefined : { width: leftWidth, maxWidth: '45vw' }}
        className={`${narrow ? (activePane === 'list' ? 'flex w-full pt-12' : 'hidden') : 'flex'} shrink-0 flex-col overflow-hidden`}
      >
        <PRList
          selectedPr={selectedPR}
          onSelect={(nextPr) => {
            if (!nextPrSelectionAllowed(nextPr)) return false
            setSelectedPR(nextPr)
            if (narrow) setActivePane('review')
            return true
          }}
        />
      </aside>

      {/* Draggable divider */}
      {!narrow && (
        <AccessibleResizer
          label="Resize pull request list"
          orientation="vertical"
          value={leftWidth}
          min={MIN_LEFT_PANE_WIDTH}
          max={maxLeftWidth()}
          onChange={setLeftWidth}
          onCommit={setAndPersistLeftWidth}
          onPointerDown={(event) => handleDividerMouseDown(event)}
          className="before:absolute before:-inset-x-2 before:inset-y-0"
        />
      )}

      {/* Right column — review pane */}
      <section
        data-testid="review-pane-shell"
        aria-label={t('app.review')}
        className={`${narrow ? (activePane === 'review' ? 'flex w-full pt-12' : 'hidden') : 'flex'} min-w-0 flex-1 flex-col overflow-hidden`}
      >
        <ReviewPane pr={selectedPR} onDirtyStateChange={setHasUnsavedReview} />
      </section>
    </main>
    )}
    </>
  )
}
