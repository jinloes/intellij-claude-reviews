import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { PR, PRListStatus } from '../../bridge/types'
import { PRList } from './PRList'

const firstPr: PR = {
  number: 42,
  title: 'Improve pull request discovery',
  owner: 'acme',
  repo: 'platform',
  author: 'octocat',
  createdAt: '2026-07-29T00:00:00Z',
  htmlUrl: 'https://github.com/acme/platform/pull/42',
  isDraft: false,
  hasReviewDraft: false,
  reviewStatus: 'REVIEWED',
}

const normalStatus: PRListStatus = {
  searchScope: 'currentRepo',
  currentRepo: 'acme/platform',
  resultLimit: 50,
  limited: false,
  reviewStatusAvailable: true,
}

function hostMessage(message: object) {
  const handler = (window as unknown as { __handleMessage?: (payload: object) => void }).__handleMessage
  if (!handler) throw new Error('PRList did not register the bridge handler')
  act(() => handler({ protocolVersion: 1, ...message }))
}

function load(prs: PR[], listStatus: PRListStatus = normalStatus) {
  hostMessage({ type: 'prListLoaded', prs, listStatus })
}

afterEach(() => {
  localStorage.clear()
})

describe('PRList', () => {
  it('shows a compact one-time readiness message without duplicate actions', () => {
    render(<PRList />)
    load([firstPr])

    expect(screen.getByText(/PR Pilot is ready/)).toBeVisible()
    expect(screen.getByText(/Choose a pull request to start/)).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Show authored PRs' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Why am I seeing this list?' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss readiness message' }))

    expect(screen.queryByText(/Choose a pull request to start/)).not.toBeInTheDocument()
    expect(localStorage.getItem('pr-pilot:first-success-coach-shown')).toBe('1')
  })

  it('hides a redundant repository selector and exposes it for multiple repositories', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])
    expect(screen.queryByRole('combobox', { name: 'Repository' })).not.toBeInTheDocument()

    load([firstPr, { ...firstPr, number: 43, owner: 'acme', repo: 'infra' }])

    expect(screen.getByRole('combobox', { name: 'Repository' })).toBeVisible()
  })

  it('omits normal scope chrome and announces only actionable exceptions', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    expect(screen.getByRole('button', { name: 'Why?' })).toBeVisible()
    expect(screen.queryByTestId('pr-list-notices')).not.toBeInTheDocument()
    expect(screen.queryByText('Searching acme/platform')).not.toBeInTheDocument()

    load([firstPr], {
      searchScope: 'currentRepo',
      resultLimit: 50,
      limited: true,
      reviewStatusAvailable: false,
    })

    const notices = screen.getByTestId('pr-list-notices')
    expect(notices).toHaveTextContent('Current repo was not detected')
    expect(notices).toHaveTextContent('Showing the first 50')
    expect(notices).toHaveTextContent('Review status is unavailable')
  })

  it('does not replace a known review status with notification-only unavailable data', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    hostMessage({
      type: 'activatePR',
      source: 'notification',
      pr: {
        ...firstPr,
        title: 'Notification title',
        reviewStatus: 'UNAVAILABLE',
      },
    })

    expect(screen.getByText('Notification title')).toBeVisible()
    expect(screen.getByText('Reviewed')).toBeVisible()
    expect(screen.getByText('From notification')).toBeVisible()
    expect(screen.getByTestId('pr-list-notices')).toHaveTextContent('opened from a notification')
  })

  it('announces unavailable review freshness for a notification-only pull request', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    hostMessage({
      type: 'activatePR',
      source: 'notification',
      pr: {
        ...firstPr,
        number: 43,
        reviewStatus: 'UNAVAILABLE',
      },
    })

    const notices = screen.getByTestId('pr-list-notices')
    expect(notices).toHaveTextContent('opened from a notification')
    expect(notices).toHaveTextContent('Review status is unavailable')
  })

  it('keeps a notification-only pull request pinned across an in-flight refresh result', () => {
    localStorage.setItem('pr-pilot:first-success-coach-shown', '1')
    render(<PRList />)
    load([firstPr])

    hostMessage({
      type: 'activatePR',
      source: 'notification',
      pr: {
        ...firstPr,
        number: 43,
        title: 'Notification-only pull request',
        reviewStatus: 'UNAVAILABLE',
      },
    })
    load([firstPr])

    expect(screen.getByText('Notification-only pull request')).toBeVisible()
    expect(screen.getByText('From notification')).toBeVisible()
    const notices = screen.getByTestId('pr-list-notices')
    expect(notices).toHaveTextContent('opened from a notification')
    expect(notices).toHaveTextContent('Review status is unavailable')
  })
})
