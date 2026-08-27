import { useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, Info, RefreshCw, Settings2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { shouldFocusPrFilter } from '@/lib/keyboard'
import { useI18n } from '@/i18n/I18nProvider'
import { onHostMessage, sendToHost, type PR, type PRListStatus, type PRSearchScope, type ProviderReadiness } from '../../bridge/types'

interface Props {
  onSelect?: (pr: PR) => boolean | void
  selectedPr?: PR | null
}

type StateFilter = 'open' | 'closed' | 'all'
const FIRST_SUCCESS_KEY = 'pr-pilot:first-success-coach-shown'

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

function formatCreatedAt(createdAt?: string): string {
  if (!createdAt) return ''
  const date = new Date(createdAt)
  if (Number.isNaN(date.getTime())) return createdAt.slice(0, 10)
  const deltaMs = Date.now() - date.getTime()
  const deltaDays = Math.floor(deltaMs / (24 * 60 * 60 * 1000))
  if (deltaDays >= 0 && deltaDays < 7) {
    if (deltaDays === 0) return 'today'
    if (deltaDays === 1) return '1 day ago'
    return `${deltaDays} days ago`
  }
  return date.toLocaleDateString()
}

export function PRList({ onSelect, selectedPr }: Props) {
  const t = useI18n()
  const [prs, setPRs] = useState<PR[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [repoFilter, setRepoFilter] = useState('all')
  const [stateFilter, setStateFilter] = useState<StateFilter>('open')
  const [searchScope, setSearchScope] = useState<PRSearchScope>('currentRepo')
  const [listStatus, setListStatus] = useState<PRListStatus | null>(null)
  const [providerReadiness, setProviderReadiness] = useState<ProviderReadiness | null>(null)
  const [coachVisible, setCoachVisible] = useState(false)
  const [coachRecoveredSetup, setCoachRecoveredSetup] = useState(false)
  const [scopeHelpVisible, setScopeHelpVisible] = useState(false)
  const [spotlightedKey, setSpotlightedKey] = useState<string | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const sawSetupScreenRef = useRef(false)

  useEffect(() => {
    setSelected(selectedPr ? prKey(selectedPr) : null)
  }, [selectedPr])

  useEffect(() => {
    const cleanup = onHostMessage((msg) => {
      if (msg.type === 'prListLoaded') {
        setPRs(msg.prs)
        setRepoFilter(msg.defaultRepo ?? 'all')
        if (msg.listStatus) {
          setListStatus(msg.listStatus)
          setSearchScope(msg.listStatus.searchScope)
        }
        setProviderReadiness(msg.providerReadiness ?? null)
        setLoading(false)
        setRefreshing(false)
        const shouldCoach = sawSetupScreenRef.current || !localStorage.getItem(FIRST_SUCCESS_KEY)
        if (shouldCoach) {
          setCoachVisible(true)
          setCoachRecoveredSetup(sawSetupScreenRef.current)
          localStorage.setItem(FIRST_SUCCESS_KEY, '1')
        }
        sawSetupScreenRef.current = false
      } else if (msg.type === 'prLoading') {
        setRefreshing(true)
      } else if (msg.type === 'setupRequired') {
        sawSetupScreenRef.current = true
        setLoading(false)
        setRefreshing(false)
      } else if (msg.type === 'prDraftStatusUpdated') {
        setPRs((prev) =>
          prev.map((pr) =>
            pr.number === msg.number && pr.owner === msg.owner && pr.repo === msg.repo
              ? { ...pr, hasReviewDraft: msg.hasReviewDraft }
              : pr,
          ),
        )
      } else if (msg.type === 'activatePR') {
        const key = prKey(msg.pr)
        setFilter('')
        setRepoFilter('all')
        setStateFilter('open')
        setSpotlightedKey(msg.source === 'notification' ? key : null)
        setPRs((prev) => {
          const index = prev.findIndex((candidate) => prKey(candidate) === key)
          if (index < 0) return [msg.pr, ...prev]
          const next = prev.slice()
          next[index] = { ...next[index], ...msg.pr }
          return next
        })
      }
    })
    return cleanup
  }, [])

  const repos = useMemo(() => {
    const seen = new Set<string>()
    const list: string[] = []
    for (const pr of prs) {
      const key = `${pr.owner}/${pr.repo}`
      if (!seen.has(key)) {
        seen.add(key)
        list.push(key)
      }
    }
    return list.sort()
  }, [prs])

  useEffect(() => {
    if (repoFilter !== 'all' && !repos.includes(repoFilter)) setRepoFilter('all')
  }, [repos, repoFilter])

  const filtered = prs.filter((pr) => {
    const repoKey = `${pr.owner}/${pr.repo}`
    if (repoFilter !== 'all' && repoKey !== repoFilter) return false
    if (filter === '') return true
    const q = filter.toLowerCase()
    return (
      pr.title.toLowerCase().includes(q) ||
      pr.author.toLowerCase().includes(q) ||
      repoKey.toLowerCase().includes(q) ||
      String(pr.number).includes(q)
    )
  })

  function handleSelect(pr: PR) {
    const accepted = onSelect?.(pr)
    if (accepted === false) return
    setSelected(prKey(pr))
    sendToHost({ type: 'selectPR', number: pr.number, owner: pr.owner, repo: pr.repo })
  }

  function fetchWithFilters(
    s: StateFilter = stateFilter,
    scope: PRSearchScope = searchScope,
  ) {
    setRefreshing(true)
    sendToHost({ type: 'refreshPRs', state: s, searchScope: scope })
  }

  function handleStateFilter(val: string) {
    // ToggleGroup fires onValueChange('') when the active item is clicked again;
    // keep the current filter rather than leaving nothing selected.
    if (!val) return
    const s = val as StateFilter
    setStateFilter(s)
    fetchWithFilters(s, searchScope)
  }

  function handleSearchScope(scope: string) {
    const next = scope as PRSearchScope
    setSearchScope(next)
    fetchWithFilters(stateFilter, next)
  }

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (shouldFocusPrFilter(e) && document.activeElement !== searchRef.current) {
        e.preventDefault()
        searchRef.current?.focus()
      }
      if (e.key === 'Escape' && document.activeElement === searchRef.current) {
        setFilter('')
        searchRef.current?.blur()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <TooltipProvider delayDuration={400}>
      <nav className="flex min-h-0 flex-1 flex-col bg-background border-r border-border" aria-label={t('app.prList')}>
      {coachVisible && (
        <div className="shrink-0 border-b border-border bg-status-approve/5 px-3 py-2">
          <div className="flex items-start gap-2">
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-status-approve" />
            <div className="min-w-0 flex-1">
              <p className="text-xs font-semibold text-foreground">
                {providerReadiness?.authenticationStatus === 'unverified'
                  ? `${providerReadiness.provider === 'copilot' ? 'Copilot' : 'Claude'} CLI found — authentication is unverified.`
                  : coachRecoveredSetup
                    ? 'GitHub and the review provider are ready.'
                    : 'PR Pilot is ready to review.'}
              </p>
              <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground">
                Start with a PR from this list, switch scope if the PR you want is elsewhere, and look for
                <span className="font-mono text-foreground"> PR-DRAFT</span> vs
                <span className="font-mono text-foreground"> REV-DRAFT</span> badges.
              </p>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Button variant="outline" size="sm" className="h-6 px-2 text-[11px]" onClick={() => setScopeHelpVisible(true)}>
                  Why am I seeing this list?
                </Button>
                {searchScope !== 'authored' && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-6 px-2 text-[11px]"
                    onClick={() => handleSearchScope('authored')}
                  >
                    Show authored PRs
                  </Button>
                )}
              </div>
            </div>
            <Button
              variant="ghost"
              size="sm"
              className="h-6 w-6 shrink-0 p-0 text-muted-foreground"
              onClick={() => setCoachVisible(false)}
              aria-label="Dismiss setup coach"
            >
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      )}

      {/* Header */}
      <div className="shrink-0 px-3 pt-3 pb-2 space-y-2 border-b border-border">
        {/* Title row */}
        <div
          className="pr-list-toolbar flex min-w-0 items-center gap-2"
          data-testid="pr-list-toolbar"
        >
          <h1 className="min-w-0 truncate text-xs font-semibold tracking-widest uppercase text-muted-foreground">
            Pull Requests
          </h1>
          <Badge
            variant="outline"
            className={cn(
              'shrink-0 text-[10px] px-1.5 py-0 font-mono',
              filtered.length > 0 ? 'text-primary border-primary/40' : 'text-muted-foreground',
            )}
            title={`${filtered.length} visible of ${prs.length} loaded`}
          >
            {filtered.length}{filtered.length !== prs.length ? `/${prs.length}` : ''}
          </Badge>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => sendToHost({ type: 'openSettings' })}
                className="pr-list-toolbar-action ml-auto h-6 min-w-6 gap-1.5 px-2 text-xs text-muted-foreground hover:text-foreground"
                aria-label="Open PR Pilot settings"
              >
                <Settings2 className="w-3.5 h-3.5" />
                <span className="pr-list-toolbar-label">Settings</span>
              </Button>
            </TooltipTrigger>
            <TooltipContent>Open PR Pilot settings</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => fetchWithFilters()}
                disabled={refreshing}
                className="pr-list-toolbar-action h-6 min-w-6 gap-1.5 px-2 text-xs text-muted-foreground hover:text-foreground"
                aria-label="Refresh pull requests"
              >
                <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
                <span className="pr-list-toolbar-label">Refresh</span>
              </Button>
            </TooltipTrigger>
            <TooltipContent>Refresh pull requests</TooltipContent>
          </Tooltip>
        </div>

        {/* State filter + search scope */}
        <div className="flex items-center gap-2 flex-wrap">
          <ToggleGroup
            type="single"
            value={stateFilter}
            onValueChange={(val) => {
              // Prevent visual deselection when clicking the already-active filter
              if (val) handleStateFilter(val)
            }}
            className="gap-1"
            aria-label={t('filter.state')}
          >
            {(['open', 'closed', 'all'] as StateFilter[]).map((s) => (
              <ToggleGroupItem
                key={s}
                value={s}
                className="h-6 px-2 text-[11px] tracking-wide uppercase data-[state=on]:bg-primary/20 data-[state=on]:text-primary data-[state=on]:border-primary/40"
              >
                {s}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>

          <Separator orientation="vertical" className="h-4" />

          <Select value={searchScope} onValueChange={handleSearchScope}>
            <SelectTrigger className="h-7 min-w-40 flex-1 text-xs border-border bg-background" aria-label={t('filter.scope')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="currentRepo" className="text-xs">Current repo</SelectItem>
              <SelectItem value="reviewRequested" className="text-xs">Review requested</SelectItem>
              <SelectItem value="assigned" className="text-xs">Assigned to me</SelectItem>
              <SelectItem value="authored" className="text-xs">Authored by me</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {listStatus && (
          <div className="rounded border border-border bg-muted/25 px-2 py-1.5 text-[11px] text-muted-foreground leading-relaxed">
            <div className="flex items-start gap-2">
              <span className="flex-1">{scopeDescription(listStatus)}</span>
              <button
                type="button"
                className="inline-flex items-center gap-1 text-[11px] font-medium text-foreground hover:text-primary"
                onClick={() => setScopeHelpVisible((value) => !value)}
              >
                <Info className="h-3 w-3" />
                Why?
              </button>
            </div>
            {listStatus.limited && (
              <div className="mt-1 text-status-suggestion">Showing first {listStatus.resultLimit} results.</div>
            )}
            {spotlightedKey && (
              <div className="mt-1 text-status-note">A notification-opened PR is pinned even if it falls outside this scope.</div>
            )}
          </div>
        )}

        {listStatus && scopeHelpVisible && <ScopeHelpCard status={listStatus} repoFilter={repoFilter} stateFilter={stateFilter} filter={filter} />}

        {/* Repo filter */}
        {repos.length > 0 && (
          <Select value={repoFilter} onValueChange={setRepoFilter}>
            <SelectTrigger className="h-7 text-xs border-border bg-background" aria-label={t('filter.repository')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all" className="text-xs">
                All repos ({prs.length})
              </SelectItem>
              {repos.map((r) => {
                const count = prs.filter((pr) => `${pr.owner}/${pr.repo}` === r).length
                return (
                  <SelectItem key={r} value={r} className="text-xs font-mono">
                    {r} ({count})
                  </SelectItem>
                )
              })}
            </SelectContent>
          </Select>
        )}

        {/* Search */}
        <div className="flex items-center gap-2 rounded border border-border bg-muted/30 px-2 focus-within:border-ring transition-colors">
          <span className="text-xs text-muted-foreground shrink-0 font-mono">/</span>
          <input
            ref={searchRef}
            className="flex-1 bg-transparent text-sm py-1.5 outline-none placeholder:text-muted-foreground placeholder:italic caret-primary"
            placeholder="filter by title, author, #number…"
            aria-label="Filter pull requests"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            spellCheck={false}
          />
        </div>
      </div>

      {/* PR list */}
      <ScrollArea className="flex-1">
        <div>
          <div aria-live="polite" aria-atomic="true" className="sr-only">
            {loading ? 'Loading pull requests' : refreshing ? 'Refreshing pull requests' : `${filtered.length} pull requests shown`}
          </div>
          {loading && (
            <div className="flex items-center gap-2 p-5 text-sm text-muted-foreground">
              <span className="font-mono animate-pulse text-primary">█</span>
              loading…
            </div>
          )}

          {!loading && filtered.length === 0 && (
            <div className="flex flex-col items-start gap-3 p-5">
              <p className="text-sm text-muted-foreground">
                {filter
                  ? `No results for "${filter}"`
                  : repoFilter !== 'all'
                    ? `No pull requests in ${repoFilter}`
                    : `No pull requests for ${scopeLabel(searchScope).toLowerCase()}`}
              </p>
              {!filter && (
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    className="gap-1.5 text-xs"
                    onClick={() => fetchWithFilters()}
                    disabled={refreshing}
                  >
                    <RefreshCw className={cn('w-3 h-3', refreshing && 'animate-spin')} />
                    Refresh
                  </Button>
                  {stateFilter !== 'all' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => {
                        setStateFilter('all')
                        fetchWithFilters('all', searchScope)
                      }}
                    >
                      Show all states
                    </Button>
                  )}
                  {searchScope !== 'authored' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => handleSearchScope('authored')}
                    >
                      Show authored PRs
                    </Button>
                  )}
                  {searchScope !== 'reviewRequested' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => handleSearchScope('reviewRequested')}
                    >
                      Show review requests
                    </Button>
                  )}
                  {searchScope !== 'assigned' && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs"
                      onClick={() => handleSearchScope('assigned')}
                    >
                      Show assigned PRs
                    </Button>
                  )}
                  <Button
                    variant="outline"
                    size="sm"
                    className="gap-1.5 text-xs"
                    onClick={() => sendToHost({ type: 'openSettings' })}
                  >
                    <Settings2 className="w-3.5 h-3.5" />
                    Settings
                  </Button>
                </div>
              )}
            </div>
          )}

          <ul
            aria-label="Pull request results"
          >
            {filtered.map((pr) => (
              <li key={prKey(pr)}>
                <PRItem
                  pr={pr}
                  selected={selected === prKey(pr)}
                  spotlighted={spotlightedKey === prKey(pr)}
                  onClick={() => handleSelect(pr)}
                />
              </li>
            ))}
          </ul>
        </div>
      </ScrollArea>
      </nav>
    </TooltipProvider>
  )
}

// ── PRItem ──────────────────────────────────────────────────────────────────
interface ItemProps {
  pr: PR
  selected: boolean
  spotlighted: boolean
  onClick: () => void
}

function PRItem({ pr, selected, spotlighted, onClick }: ItemProps) {
  const date = formatCreatedAt(pr.createdAt)

  return (
    <button
      className={cn(
        'pr-item w-full flex items-stretch border-b border-border text-left transition-colors',
        'hover:bg-accent/50 focus-visible:relative focus-visible:z-10 focus-visible:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring',
        selected && 'bg-accent/40 border-l-2 border-l-primary',
      )}
      onClick={onClick}
      aria-current={selected ? 'page' : undefined}
    >
      {/* Number gutter */}
      <div className="w-14 shrink-0 flex flex-col items-end justify-start px-3 py-2.5 border-r border-border gap-0.5">
        <span className={cn('text-xs font-mono font-medium', selected ? 'text-primary' : 'text-muted-foreground')}>
          #{pr.number}
        </span>
        {pr.isDraft && (
          <span
            className="text-[9px] font-bold tracking-wider text-muted-foreground leading-none"
            title="GitHub draft pull request"
            aria-label="GitHub draft pull request"
          >
            PR-DRAFT
          </span>
        )}
        {pr.hasReviewDraft && (
          <span
            className="text-[9px] font-bold tracking-wider text-[hsl(var(--status-comment))] leading-none"
            title="Saved review draft"
            aria-label="Saved review draft"
          >
            REV-DRAFT
          </span>
        )}
        {spotlighted && (
          <span
            className="text-[9px] font-bold tracking-wider text-[hsl(var(--status-note))] leading-none"
            title="Opened from a notification"
            aria-label="Opened from a notification"
          >
            NOTIFIED
          </span>
        )}
      </div>

      {/* Body */}
      <div className="flex-1 min-w-0 px-2.5 py-2.5 flex flex-col gap-0.5">
        <div className="flex items-baseline gap-1.5 min-w-0">
          <span className="text-sm text-foreground truncate flex-1 leading-snug">{pr.title}</span>
        </div>
        <span className="font-mono truncate text-[11px] text-muted-foreground">
          {pr.owner}/{pr.repo}
        </span>
        <div className="flex items-center gap-1 text-[11px]">
          <span className="text-[hsl(var(--status-approve))]">@{pr.author}</span>
          {date && (
            <>
              <span className="text-muted-foreground">·</span>
              <span className="text-muted-foreground">{date}</span>
            </>
          )}
        </div>
      </div>
    </button>
  )
}

function ScopeHelpCard({
  status,
  repoFilter,
  stateFilter,
  filter,
}: {
  status: PRListStatus
  repoFilter: string
  stateFilter: StateFilter
  filter: string
}) {
  const bullets = visibilityBullets(status, repoFilter, stateFilter, filter)
  return (
    <div className="rounded border border-border bg-card px-3 py-2 text-[11px] leading-relaxed text-muted-foreground">
      <p className="font-semibold text-foreground">Why you may or may not see a PR</p>
      <ul className="mt-1 space-y-1">
        {bullets.map((bullet) => (
          <li key={bullet}>• {bullet}</li>
        ))}
      </ul>
    </div>
  )
}

function scopeLabel(scope: PRSearchScope): string {
  switch (scope) {
    case 'currentRepo': return 'Current repo'
    case 'reviewRequested': return 'Review requested'
    case 'assigned': return 'Assigned to me'
    case 'authored': return 'Authored by me'
  }
}

function scopeDescription(status: PRListStatus): string {
  if (status.searchScope === 'currentRepo') {
    return status.currentRepo ? `Searching ${status.currentRepo}` : 'Current repo was not detected; showing authored PRs'
  }
  return `Searching ${scopeLabel(status.searchScope).toLowerCase()} PRs`
}

function visibilityBullets(
  status: PRListStatus,
  repoFilter: string,
  stateFilter: StateFilter,
  filter: string,
): string[] {
  const bullets = [
    status.searchScope === 'currentRepo'
      ? status.currentRepo
        ? `Only PRs from ${status.currentRepo} are loaded in this scope.`
        : 'The current repository could not be detected, so the list falls back to PRs you authored.'
      : `This scope only loads ${scopeLabel(status.searchScope).toLowerCase()} PRs.`,
    'GitHub draft pull requests show PR-DRAFT. Saved PR Pilot review drafts show REV-DRAFT.',
  ]
  if (stateFilter !== 'all') bullets.push(`State filter is set to ${stateFilter}.`)
  if (repoFilter !== 'all') bullets.push(`Repo filter is narrowing results to ${repoFilter}.`)
  if (filter.trim()) bullets.push(`Text filter is matching "${filter.trim()}".`)
  if (status.limited) bullets.push(`Only the first ${status.resultLimit} matching PRs are shown.`)
  bullets.push('Notification-opened PRs can be pinned into the list even if they are outside the current scope.')
  return bullets
}
