import { describe, expect, it } from 'vitest'
import { setupSteps } from './setupSteps'

describe('provider onboarding steps', () => {
  it('shows a missing provider binary as incomplete', () => {
    const steps = setupSteps('provider_not_installed', {
      provider: 'claude',
      available: false,
      detail: 'Claude Code CLI is missing.',
      binaryStatus: 'missing',
      authenticationStatus: 'unavailable',
      authCommand: 'claude auth login',
    })

    expect(steps.find((step) => step.label === 'Install Claude Code CLI')?.done).toBe(false)
    expect(steps.find((step) => step.label === 'Verify provider authentication')?.done).toBe(false)
  })

  it('labels unsupported authentication checks as unverified rather than ready', () => {
    const steps = setupSteps('provider_not_installed', {
      provider: 'copilot',
      available: true,
      detail: 'Provider CLI found.',
      binaryStatus: 'ready',
      authenticationStatus: 'unverified',
      authCommand: 'copilot login',
    })

    expect(steps.find((step) => step.label === 'Install GitHub Copilot CLI')?.done).toBe(true)
    const auth = steps.find((step) => step.label === 'Verify provider authentication')
    expect(auth?.done).toBe(false)
    expect(auth?.detail).toContain('copilot login')
  })

  it('marks binary and authentication complete for a fully ready provider', () => {
    const steps = setupSteps('load_failed', {
      provider: 'claude',
      available: true,
      detail: 'Ready.',
      binaryStatus: 'ready',
      authenticationStatus: 'ready',
      authCommand: 'claude auth login',
    })

    expect(steps.slice(2, 4).map((step) => step.done)).toEqual([true, true])
  })
})
