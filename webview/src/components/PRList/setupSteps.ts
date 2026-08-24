import type { ProviderReadiness } from '../../bridge/types'

export type SetupReason =
  | 'gh_not_installed'
  | 'gh_not_authenticated'
  | 'provider_not_installed'
  | 'provider_not_authenticated'
  | 'load_failed'

export function setupSteps(
  reason: SetupReason,
  provider?: ProviderReadiness,
): Array<{ label: string; detail: string; done: boolean }> {
  return [
    {
      label: 'Install GitHub CLI',
      detail: 'PR Pilot uses gh for GitHub authentication and PR access.',
      done: reason !== 'gh_not_installed',
    },
    {
      label: 'Authenticate GitHub',
      detail: 'Run gh auth login for github.com or your Enterprise host.',
      done: reason === 'load_failed'
        || reason === 'provider_not_installed'
        || reason === 'provider_not_authenticated',
    },
    {
      label: `Install ${provider?.provider === 'copilot' ? 'GitHub Copilot CLI' : 'Claude Code CLI'}`,
      detail: provider?.detail ?? 'PR Pilot checks the selected provider before the first review.',
      done: provider?.binaryStatus === 'ready' || (provider?.available ?? false),
    },
    {
      label: 'Verify provider authentication',
      detail: provider?.authenticationStatus === 'unverified'
        ? `Authentication cannot be checked non-interactively. Run ${provider.authCommand ?? 'the provider sign-in command'} manually.`
        : provider?.authenticationStatus === 'ready'
          ? 'Provider authentication is ready.'
          : 'Authenticate the selected provider, then check status again.',
      done: provider?.authenticationStatus === 'ready',
    },
    {
      label: 'Load pull requests',
      detail: 'Refresh after setup; choose a search scope if the list is empty.',
      done: reason === 'provider_not_installed' || reason === 'provider_not_authenticated',
    },
  ]
}
