import { CheckCircle2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ProviderReadiness } from '../../bridge/types'

interface Props {
  providerReadiness: ProviderReadiness | null
  recoveredSetup: boolean
  onDismiss: () => void
}

export function ReadinessCoach({ providerReadiness, recoveredSetup, onDismiss }: Props) {
  const readiness = providerReadiness?.authenticationStatus === 'unverified'
    ? `${providerReadiness.provider === 'copilot' ? 'Copilot' : 'Claude'} CLI found; authentication is unverified.`
    : recoveredSetup
      ? 'GitHub and the review provider are ready.'
      : 'PR Pilot is ready.'

  return (
    <div className="shrink-0 border-b border-border bg-status-approve/5 px-3 py-1.5" role="status">
      <div className="flex items-start gap-2">
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-status-approve" aria-hidden="true" />
        <p className="min-w-0 flex-1 text-xs leading-5 text-foreground">
          <span className="font-semibold">{readiness}</span>{' '}
          Choose a pull request to start.
        </p>
        <Button
          variant="ghost"
          size="sm"
          className="h-6 w-6 shrink-0 p-0 text-muted-foreground"
          onClick={onDismiss}
          aria-label="Dismiss readiness message"
        >
          <X className="h-3.5 w-3.5" aria-hidden="true" />
        </Button>
      </div>
    </div>
  )
}
